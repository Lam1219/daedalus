"""Tests for the streaming tremor detector (tremor-stream-1.0)."""

import pytest

from daedalus_watch_kit.stream_features import Context, WindowFeatures
from daedalus_watch_kit.tremor_stream import (
    T_MIN, TremorDetector, TremorParams, after_feedback, calibrate, detect, window_positive,
)


def win(sec, dps=0.0, hz=5.0, prom=20.0, ctx=Context.REST_TREMOR_POSSIBLE, slow=1.0, slow4=0.0):
    return WindowFeatures(
        t_end_ms=int(sec * 1000), tremor_dps=dps, peak_hz=hz, prominence=prom, slow_dps=slow,
        motion_dps=dps + 1, mean_rotation_dps=0.0, acc_std=0.05, gravity_change_deg=0.0,
        freeze_index=0.0, cadence_spm=0.0, step_regularity=0.0, arm_swing_dps=slow4, context=ctx,
    )


P = TremorParams(threshold_dps=3.0)


def test_window_rule():
    assert window_positive(win(2, dps=5), P)
    assert not window_positive(win(2, dps=2), P)                       # below T
    assert not window_positive(win(2, dps=5, hz=9.0), P)               # outside the band
    assert not window_positive(win(2, dps=5, prom=2.0), P)             # no clear peak
    assert window_positive(win(2, dps=5, ctx=Context.STILL), P)            # slight tremor looks still
    assert not window_positive(win(2, dps=5, ctx=Context.ACTIVE_HAND), P)
    assert not window_positive(win(2, dps=5, ctx=Context.WALKING), P)


def test_personal_band():
    p = TremorParams(threshold_dps=3.0, person_hz=4.5)
    assert p.band() == (3.5, 6.0)
    assert not window_positive(win(2, dps=5, hz=6.8), p)


def test_episode_starts_after_three_of_four_and_ends():
    ws = [win(s) for s in range(2, 10)]                              # quiet
    ws += [win(s, dps=6) for s in range(10, 13)]                     # 3 positives -> start at 12 s
    ws += [win(s, dps=6) for s in range(13, 30)]
    ws += [win(s) for s in range(30, 40)]                            # quiet -> end
    det = TremorDetector(P)
    events = [(k, w.t_end_ms) for w in ws for k, _ in det.update(w)]
    assert events[0] == ("start", 12000)
    assert events[1][0] == "end"
    eps = detect(ws, P)
    assert len(eps) == 1
    ep = eps[0]
    assert ep.start_ms == 8000          # first positive window (ends 10 s) covers 8-10 s
    assert ep.detected_ms == 12000
    assert ep.end_ms == 29000
    assert ep.hz == 5.0
    assert ep.strength == "light"
    assert 0.5 < ep.confidence < 1


def test_one_gap_does_not_end_an_episode():
    ws = [win(s, dps=6) for s in range(2, 12)]
    ws += [win(12), win(13)]                                         # two misses
    ws += [win(s, dps=6) for s in range(14, 20)]
    ws += [win(s) for s in range(20, 30)]
    assert len(detect(ws, P)) == 1


def test_unstable_frequency_does_not_start():
    ws = [win(2, dps=6, hz=3.6), win(3, dps=6, hz=7.4), win(4, dps=6, hz=3.6), win(5, dps=6, hz=7.4)]
    assert detect(ws, P) == []


def test_isolated_positives_do_not_start():
    ws = [win(s, dps=6 if s % 2 == 0 else 0) for s in range(2, 30)]   # 2 of every 4
    assert detect(ws, P) == []


def test_strength_levels():
    ws = [win(s, dps=25) for s in range(2, 12)]
    assert detect(ws, P)[0].strength == "moderate"


def test_calibration_threshold():
    rest = [win(s, dps=0.8) for s in range(2, 20)]
    assert calibrate(rest).threshold_dps == pytest.approx(2.4)
    assert calibrate([win(s, dps=0.1) for s in range(2, 20)]).threshold_dps == T_MIN
    # A tremor found at calibration caps T at half its strength, and sets the person's frequency.
    p = calibrate([win(s, dps=3.0) for s in range(2, 20)], tremor_band_dps=8.0, tremor_hz=4.8)
    assert p.threshold_dps == pytest.approx(4.0)
    assert p.person_hz == 4.8


def test_feedback_steps_and_bounds():
    p = after_feedback(TremorParams(threshold_dps=10.0), false_positive=True, base_dps=10.0)
    assert p.threshold_dps == pytest.approx(11.5)
    p = after_feedback(TremorParams(threshold_dps=10.0), false_positive=False, base_dps=10.0)
    assert p.threshold_dps == pytest.approx(9.0)
    # Repeated "Not shaking" stops at 1.5 x the starting threshold; "I'm shaking" at 0.5 x.
    p = TremorParams(threshold_dps=6.0)
    for _ in range(20):
        p = after_feedback(p, True, base_dps=6.0)
    assert p.threshold_dps == pytest.approx(9.0)
    for _ in range(40):
        p = after_feedback(p, False, base_dps=6.0)
    assert p.threshold_dps == pytest.approx(3.0)
    assert after_feedback(TremorParams(threshold_dps=T_MIN), False, base_dps=T_MIN).threshold_dps == T_MIN


def test_rest_gate():
    p = TremorParams(threshold_dps=3.0, max_slow_ratio=0.25, max_slow4_dps=4.0)
    assert window_positive(win(2, dps=10, slow=2.0, slow4=2.0), p)
    assert not window_positive(win(2, dps=10, slow=3.0, slow4=2.0), p)     # slow movement > 0.25 x tremor
    assert not window_positive(win(2, dps=10, slow=2.0, slow4=6.0), p)     # moving over the last 4 s
    off = TremorParams(threshold_dps=3.0, max_slow_ratio=float("inf"), max_slow4_dps=float("inf"))
    assert window_positive(win(2, dps=10, slow=3.0, slow4=6.0), off)       # inf turns it off
    assert not window_positive(win(2, dps=10, slow=6.0, slow4=2.0), P)     # on by default (0.5)
    # Feedback keeps the gate.
    assert after_feedback(p, True, base_dps=3.0).max_slow_ratio == 0.25


def test_classifier_decisions_override_the_rule():
    # Quiet windows, but a classifier says "tremor" for 10 s: the episode logic still applies.
    ws = [win(s, dps=6) for s in range(2, 30)]
    flags = [10 <= s < 20 for s in range(2, 30)]
    eps = detect(ws, P, positive=flags)
    assert len(eps) == 1
    assert eps[0].detected_ms == 12000
