"""Tests for the streaming features (automatic cues, design 2.3)."""

import numpy as np
import pytest

from daedalus_watch_kit.stream_features import (
    FS, Context, StreamFeatureExtractor, compute_features, resample_uniform,
)

G = 9.80665


def recording(seconds, gyro=None, acc=None, seed=0):
    """A 50 Hz recording: watch flat (gravity on z), small sensor noise, plus optional signals.

    gyro(t) and acc(t) return (3, n) arrays added to the baseline.
    """
    rng = np.random.default_rng(seed)
    n = int(seconds * FS)
    t = np.arange(n) / FS
    a = np.zeros((3, n))
    a[2] = G
    a += rng.normal(0, 0.02, a.shape)
    g = rng.normal(0, 0.002, (3, n))
    if gyro is not None:
        g += gyro(t)
    if acc is not None:
        a += acc(t)
    return (t * 1000).astype(np.int64), a, g


def tremor_gyro(hz, dps):
    amp = np.radians(dps) * np.sqrt(2)  # RMS -> amplitude
    return lambda t: np.vstack([amp * np.sin(2 * np.pi * hz * t), 0 * t, 0 * t])


def walking(spm, acc_amp=2.0, swing_dps=60.0):
    stride_hz = spm / 120.0
    step_hz = spm / 60.0

    def acc(t):
        # Heel strikes at the step rate, plus a smaller stride component.
        bump = acc_amp * (np.sin(2 * np.pi * step_hz * t) + 0.5 * np.sin(2 * np.pi * stride_hz * t))
        return np.vstack([0.3 * bump, 0 * t, bump])

    def gyro(t):
        amp = np.radians(swing_dps) * np.sqrt(2)
        return np.vstack([0 * t, amp * np.sin(2 * np.pi * stride_hz * t), 0 * t])

    return acc, gyro


def test_still_watch_is_still():
    t, a, g = recording(10)
    w = compute_features(t, a, g)
    assert len(w) == 9  # first window at 2 s, then one per second
    assert all(x.context == Context.STILL for x in w)
    assert w[-1].tremor_dps < 0.5


@pytest.mark.parametrize("hz", [4.0, 5.0, 6.5])
def test_tremor_frequency_and_amplitude(hz):
    t, a, g = recording(10, gyro=tremor_gyro(hz, 8.0))
    w = compute_features(t, a, g)[-1]
    assert w.peak_hz == pytest.approx(hz, abs=0.25)
    assert w.tremor_dps == pytest.approx(8.0, rel=0.15)
    assert w.prominence > 20
    assert w.context == Context.REST_TREMOR_POSSIBLE


def test_slow_arm_movement_is_active():
    big = lambda t: np.vstack([np.radians(60) * np.sin(2 * np.pi * 0.8 * t), 0 * t, 0 * t])
    t, a, g = recording(10, gyro=big)
    assert compute_features(t, a, g)[-1].context == Context.ACTIVE_HAND


def test_posture_change_is_active():
    t, a, g = recording(6)
    # Tilt the watch 60 degrees between seconds 3 and 4.
    ang = np.clip((t / 1000.0 - 3.0), 0, 1) * np.radians(60)
    a[0] = G * np.sin(ang)
    a[2] = G * np.cos(ang)
    w = compute_features(t, a, g)
    assert max(x.gravity_change_deg for x in w) > 25
    assert any(x.context == Context.ACTIVE_HAND for x in w)


@pytest.mark.parametrize("spm", [80, 100, 120])
def test_walking_cadence(spm):
    acc, gyro = walking(spm)
    t, a, g = recording(12, gyro=gyro, acc=acc)
    w = compute_features(t, a, g)[-1]
    assert w.context in (Context.WALKING, Context.TURNING)
    assert w.cadence_spm == pytest.approx(spm, abs=5)
    assert w.step_regularity > 0.7
    assert w.arm_swing_dps > 30


def test_freeze_index_rises_with_trembling_in_place():
    acc_walk, gyro_walk = walking(100)
    t, a, g = recording(8, gyro=gyro_walk, acc=acc_walk)
    walk_fi = compute_features(t, a, g)[-1].freeze_index
    tremble = lambda t: np.vstack([0 * t, 0 * t, 1.0 * np.sin(2 * np.pi * 6.0 * t)])
    t, a, g = recording(8, acc=tremble)
    tremble_fi = compute_features(t, a, g)[-1].freeze_index
    assert tremble_fi > 10 * walk_fi


def test_streaming_matches_batch():
    acc, gyro = walking(96)
    t, a, g = recording(9, gyro=lambda s: gyro(s) + tremor_gyro(5, 3)(s), acc=acc, seed=3)
    batch = compute_features(t, a, g)
    ex = StreamFeatureExtractor()
    stream = [w for i in range(len(t)) if (w := ex.push(t[i], *a[:, i], *g[:, i])) is not None]
    assert len(stream) == len(batch)
    for s, b in zip(stream, batch):
        assert s.t_end_ms == b.t_end_ms
        for k in ("tremor_dps", "peak_hz", "freeze_index", "cadence_spm", "arm_swing_dps", "gravity_change_deg"):
            assert getattr(s, k) == pytest.approx(getattr(b, k), rel=1e-9, abs=1e-9)
        assert s.context == b.context


def test_resample_uniform():
    t = np.array([0, 19, 41, 60, 80])
    t2, x = resample_uniform(t, np.array([0.0, 1.9, 4.1, 6.0, 8.0]))
    assert list(t2) == [0, 20, 40, 60, 80]
    assert x == pytest.approx([0, 2, 4, 6, 8], abs=1e-9)
