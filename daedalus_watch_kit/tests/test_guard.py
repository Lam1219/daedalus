"""Tests for the guard engine (guard-1.0): streaming resampler, detectors, event rows."""

import json
from pathlib import Path

import numpy as np
import pytest

from daedalus_watch_kit import event_labels as ev
from daedalus_watch_kit import guard
from daedalus_watch_kit import stream_features as sf
from daedalus_watch_kit.fog import FogParams
from daedalus_watch_kit.tremor_stream import TremorParams

GOLDEN = Path(__file__).resolve().parents[1] / "golden"


def case(name):
    p = json.loads((GOLDEN / f"{name}.json").read_text())
    i = p["input"]
    t = np.array(i["t_ms"], dtype=np.int64)
    acc = np.vstack([i["ax"], i["ay"], i["az"]])
    gyr = np.vstack([i["gx"], i["gy"], i["gz"]])
    return p, t, acc, gyr


def keys(notes):
    return [kv.split("=", 1)[0] for kv in notes.split(";")]


def test_even_samples_pass_through_unchanged():
    r = guard.StreamResampler()
    out = []
    for k in range(10):
        out += r.push(1000 + 20 * k, (k, -k))[1]
    assert [t for t, _ in out] == [1000 + 20 * k for k in range(10)]
    assert all(v[0] == k and v[1] == -k for k, (_, v) in enumerate(out))


def test_streaming_resampler_matches_resample_uniform():
    _, t, acc, gyr = case("jittered_timestamps")
    r = guard.StreamResampler()
    grid = [g for i in range(len(t)) for g in r.push(t[i], np.r_[acc[:, i], gyr[:, i]])[1]]
    t_u, *ch = sf.resample_uniform(t, *acc, *gyr)
    assert [g[0] for g in grid] == list(t_u)
    assert np.allclose(np.array([g[1] for g in grid]).T, np.vstack(ch), rtol=0, atol=1e-12)


def test_gap_restarts_the_grid():
    r = guard.StreamResampler()
    r.push(0, (0.0,))
    r.push(20, (1.0,))
    restarted, out = r.push(400, (5.0,))          # 380 ms gap
    assert restarted and r.restarts == 1
    assert out == [(400, pytest.approx(np.array([5.0])))]
    restarted, out = r.push(400, (7.0,))          # same time again: skipped
    assert not restarted and out == [] and r.restarts == 1
    restarted, out = r.push(399, (6.0,))          # time going backwards
    assert restarted and r.restarts == 2


def test_engine_windows_match_compute_features():
    _, t, acc, gyr = case("walk_then_tremble")
    eng = guard.GuardEngine(guard.WALK)
    windows = []
    for i in range(len(t)):
        restarted, grid = eng.resampler.push(t[i], np.r_[acc[:, i], gyr[:, i]])
        for gt, v in grid:
            w = eng.extractor.push(gt, *v)
            if w is not None:
                windows.append(w)
    batch = sf.compute_features(t, acc, gyr)
    assert len(windows) == len(batch)
    for a, b in zip(windows, batch):
        assert a.to_dict() == pytest.approx(b.to_dict())


def test_rest_tremor_events():
    p, t, acc, gyr = case("rest_tremor_episode")
    rows = guard.run(guard.REST, t, acc, gyr)
    names = [n for _, n, _ in rows]
    assert names == [ev.GUARD_START, ev.AUTO_TREMOR_START, ev.AUTO_TREMOR_END, ev.GUARD_END]
    start = rows[1]
    assert keys(start[2]) == ["type", "action", "model", "start_ms", "hz", "strength", "confidence", "threshold_dps"]
    assert "action=shadow" in start[2] and "model=tremor-stream-1.0" in start[2]
    assert start[0] == p["expected"]["tremor_episodes"][0]["detected_ms"]
    assert keys(rows[2][2]) == ["type", "action", "model", "start_ms", "end_ms", "hz", "strength", "peak_dps"]
    assert rows[-1][2] == f"type=guard;mode=rest;windows={len(p['expected']['windows'])};gaps=0"


def test_walk_guard_logs_a_freeze_candidate_and_rest_guard_does_not():
    _, t, acc, gyr = case("walk_then_tremble")
    walk = guard.run(guard.WALK, t, acc, gyr)
    fog_rows = [r for r in walk if r[1] == ev.AUTO_FOG_CANDIDATE]
    assert len(fog_rows) == 1
    assert keys(fog_rows[0][2]) == ["type", "action", "model", "walk_start_ms", "walk_end_ms", "reason",
                                    "turning", "freeze_index", "arm_swing_share", "confidence",
                                    "freeze_index_threshold"]
    assert "reason=freeze_index" in fog_rows[0][2] and "turning=false" in fog_rows[0][2]
    rest = guard.run(guard.REST, t, acc, gyr)
    assert not any(r[1] == ev.AUTO_FOG_CANDIDATE for r in rest)


def test_open_episode_ends_at_finish():
    _, t, acc, gyr = case("rest_tremor_episode")
    cut = np.searchsorted(t, 15000)                  # stop while the tremor is still going
    rows = guard.run(guard.REST, t[:cut], acc[:, :cut], gyr[:, :cut])
    names = [n for _, n, _ in rows]
    assert names == [ev.GUARD_START, ev.AUTO_TREMOR_START, ev.AUTO_TREMOR_END, ev.GUARD_END]
    assert rows[2][0] == int(t[cut - 1])


def test_personal_params_are_used():
    _, t, acc, gyr = case("slight_tremor_personal_threshold")
    assert [n for _, n, _ in guard.run(guard.REST, t, acc, gyr)].count(ev.AUTO_TREMOR_START) == 0
    personal = guard.run(guard.REST, t, acc, gyr, TremorParams(threshold_dps=1.5, person_hz=4.5))
    starts = [r for r in personal if r[1] == ev.AUTO_TREMOR_START]
    assert len(starts) == 1 and "threshold_dps=1.50" in starts[0][2]


def test_fmt_rounds_like_python():
    assert guard.fmt(0.125, 2) == "0.12"     # exact tie: to even
    assert guard.fmt(0.375, 2) == "0.38"
    assert guard.fmt(2.675, 2) == "2.67"     # 2.675 is just below in binary
    assert guard.fmt(4.0, 1) == "4.0"


def test_bad_mode():
    with pytest.raises(ValueError):
        guard.GuardEngine("sleep")


def test_fog_params_threshold_in_notes():
    _, t, acc, gyr = case("walk_then_tremble")
    rows = guard.run(guard.WALK, t, acc, gyr, fog_params=FogParams(freeze_index_threshold=3.0))
    assert any("freeze_index_threshold=3.00" in r[2] for r in rows if r[1] == ev.AUTO_FOG_CANDIDATE)
