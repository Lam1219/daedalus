"""Tests for the wrist freezing-of-gait detector (fog-1.0)."""

import numpy as np

from daedalus_watch_kit.fog import COOLDOWN_S, FogDetector, detect
from daedalus_watch_kit.stream_features import FS, Context, WindowFeatures, compute_features

G = 9.80665


def win(sec, ctx, reg=0.9, fi=0.3, swing=60.0, grav=0.0):
    return WindowFeatures(
        t_end_ms=int(sec * 1000), tremor_dps=0.0, peak_hz=5.0, prominence=1.0, slow_dps=30.0,
        motion_dps=40.0, mean_rotation_dps=0.0, acc_std=1.0, gravity_change_deg=grav,
        freeze_index=fi, cadence_spm=100.0, step_regularity=reg, arm_swing_dps=swing, context=ctx,
    )


def walk(start, end, ctx=Context.WALKING):
    return [win(s, ctx) for s in range(start, end)]


def test_trembling_after_walking_is_a_candidate():
    ws = walk(4, 14) + [win(14, Context.ACTIVE_HAND, reg=0.1, fi=6.0, swing=20)]
    c = detect(ws)
    assert len(c) == 1
    assert c[0].reason == "freeze_index"
    assert c[0].detected_ms == 14000
    assert c[0].walk_cadence_spm == 100.0
    assert c[0].confidence > 0.5


def test_shuffling_after_walking_is_a_candidate():
    ws = walk(4, 14) + [win(14, Context.ACTIVE_HAND, reg=0.2, fi=0.5, swing=10)]
    c = detect(ws)
    assert len(c) == 1 and c[0].reason == "arm_swing"


def test_short_walk_is_ignored():
    ws = walk(4, 6) + [win(6, Context.ACTIVE_HAND, reg=0.1, fi=6.0)]
    assert detect(ws) == []


def test_voluntary_stop_with_still_wrist_cancels():
    ws = walk(4, 14) + [win(s, Context.STILL, reg=0.1, fi=0.2, swing=1) for s in range(14, 20)]
    assert detect(ws) == []


def test_sitting_down_is_not_a_freeze():
    ws = walk(4, 14) + [win(14, Context.ACTIVE_HAND, reg=0.1, fi=6.0, swing=10, grav=40)]
    assert detect(ws) == []


def test_late_change_is_not_linked_to_the_walk():
    ws = walk(4, 14) + [win(s, Context.ACTIVE_HAND, reg=0.6, fi=0.5) for s in range(14, 18)]
    ws += [win(18, Context.ACTIVE_HAND, reg=0.1, fi=6.0)]
    assert detect(ws) == []


def test_turning_needs_two_windows():
    ws = walk(4, 12) + walk(12, 14, Context.TURNING)
    one = ws + [win(14, Context.ACTIVE_HAND, reg=0.1, fi=6.0), win(15, Context.ACTIVE_HAND, reg=0.6, fi=0.5)]
    assert detect(one) == []
    two = ws + [win(14, Context.ACTIVE_HAND, reg=0.1, fi=6.0), win(15, Context.ACTIVE_HAND, reg=0.1, fi=6.0)]
    c = detect(two)
    assert len(c) == 1 and c[0].turning and c[0].detected_ms == 15000


def test_cooldown_between_candidates():
    freeze = lambda s: win(s, Context.ACTIVE_HAND, reg=0.1, fi=6.0)
    ws = walk(4, 14) + [freeze(14)] + walk(15, 20) + [freeze(20)]
    assert len(detect(ws)) == 1
    later = 14 + COOLDOWN_S + 1
    ws += walk(21, later) + [freeze(later)]
    assert len(detect(ws)) == 2


def test_end_to_end_walk_then_tremble_in_place():
    """Raw signals through stream_features: 12 s walking, then 6 s of 6 Hz trembling in place."""
    rng = np.random.default_rng(1)
    n_walk, n_frz = int(12 * FS), int(6 * FS)
    t = np.arange(n_walk + n_frz) / FS
    acc = np.zeros((3, len(t)))
    gyr = rng.normal(0, 0.002, (3, len(t)))
    acc[2] = G
    tw = t[:n_walk]
    bump = 2.0 * (np.sin(2 * np.pi * (100 / 60) * tw) + 0.5 * np.sin(2 * np.pi * (100 / 120) * tw))
    acc[2, :n_walk] += bump
    acc[0, :n_walk] += 0.3 * bump
    gyr[1, :n_walk] += np.radians(60) * np.sqrt(2) * np.sin(2 * np.pi * (100 / 120) * tw)
    tf = t[n_walk:]
    acc[2, n_walk:] += 1.0 * np.sin(2 * np.pi * 6.0 * tf)
    gyr[1, n_walk:] += np.radians(5) * np.sin(2 * np.pi * 6.0 * tf)
    acc += rng.normal(0, 0.02, acc.shape)

    windows = compute_features((t * 1000).astype(np.int64), acc, gyr)
    c = detect(windows)
    assert len(c) == 1
    delay_s = c[0].detected_ms / 1000 - 12.0
    assert 0 < delay_s <= 5, f"detected {delay_s:.0f} s after the freeze began"


def test_end_to_end_voluntary_stop():
    rng = np.random.default_rng(2)
    n_walk, n_stop = int(12 * FS), int(8 * FS)
    t = np.arange(n_walk + n_stop) / FS
    acc = np.zeros((3, len(t)))
    acc[2] = G
    gyr = rng.normal(0, 0.002, (3, len(t)))
    tw = t[:n_walk]
    bump = 2.0 * (np.sin(2 * np.pi * (100 / 60) * tw) + 0.5 * np.sin(2 * np.pi * (100 / 120) * tw))
    acc[2, :n_walk] += bump
    gyr[1, :n_walk] += np.radians(60) * np.sqrt(2) * np.sin(2 * np.pi * (100 / 120) * tw)
    acc += rng.normal(0, 0.02, acc.shape)
    assert detect(compute_features((t * 1000).astype(np.int64), acc, gyr)) == []
