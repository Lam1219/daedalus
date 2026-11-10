"""Tests for the personal threshold learned from shadow-mode history."""

import numpy as np
import pytest

from daedalus_watch_kit.stream_features import Context, WindowFeatures
from daedalus_watch_kit.tremor_learning import learn
from daedalus_watch_kit.tremor_stream import T_MIN, TremorParams

POP = TremorParams(threshold_dps=6.0, min_prominence=16.0)


def win(i, dps, hz, prom=40.0, ctx=Context.REST_TREMOR_POSSIBLE):
    return WindowFeatures(
        t_end_ms=i * 1000, tremor_dps=dps, peak_hz=hz, prominence=prom, slow_dps=0.5,
        motion_dps=dps + 0.5, mean_rotation_dps=0.0, acc_std=0.05, gravity_change_deg=0.0,
        freeze_index=0.0, cadence_spm=0.0, step_regularity=0.0, arm_swing_dps=0.5, context=ctx,
    )


def quiet(n, start=0):
    return [win(start + i, 0.4, 5.0, prom=3.0) for i in range(n)]


def test_slight_consistent_tremor_lowers_threshold():
    rng = np.random.default_rng(0)
    hist = quiet(3000) + [win(3000 + i, rng.uniform(1.6, 2.4), 4.6 + rng.normal(0, 0.15)) for i in range(600)]
    got = learn(hist, POP)
    assert got.personalised
    assert got.person_hz == pytest.approx(4.6, abs=0.25)
    assert got.evidence_min == pytest.approx(10, abs=1)
    assert T_MIN <= got.params.threshold_dps < 2.0
    assert got.params.min_prominence == 16.0


def test_strong_tremor_never_raises_threshold():
    hist = [win(i, 30.0, 5.0) for i in range(900)]
    got = learn(hist, POP)
    assert got.personalised and got.params.threshold_dps == 6.0


def test_too_little_evidence_keeps_population():
    hist = quiet(5000) + [win(5000 + i, 2.0, 4.5) for i in range(120)]   # 2 minutes
    got = learn(hist, POP)
    assert not got.personalised and got.params == POP


def test_scattered_frequencies_keep_population():
    rng = np.random.default_rng(1)
    hist = [win(i, 3.0, rng.uniform(3.5, 7.5)) for i in range(1200)]       # 20 min, no steady rhythm
    got = learn(hist, POP)
    assert not got.personalised


def test_moving_or_walking_windows_dont_count():
    hist = [win(i, 2.0, 4.5, ctx=Context.ACTIVE_HAND) for i in range(900)]
    hist += [win(900 + i, 2.0, 4.5, ctx=Context.WALKING) for i in range(900)]
    assert not learn(hist, POP).personalised
