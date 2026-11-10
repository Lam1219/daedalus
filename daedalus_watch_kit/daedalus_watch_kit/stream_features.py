"""Streaming motion features for automatic cues (Documentation/automatic-cues-design.md, 2.3).

This module is the reference for the on-device versions: the Galaxy Watch (Kotlin) and the
Apple Watch (Swift) must compute the same numbers on the same samples. That is why it uses only
operations that port line for line: evenly spaced 50 Hz samples, a Hann window, a DFT, sums.
There are no filtfilt or other non-causal filters.

Every HOP_S (1 s) a window is computed from the latest samples:

  2 s (100 samples), the tremor and freeze-index window
    tremor_dps        RMS rotation in 3-8 Hz, deg/s (gyroscope, three axes summed)
    peak_hz           frequency of the highest gyroscope power in 3-8 Hz, interpolated
    prominence        power at the peak / mean power over 0.5-12 Hz away from the peak (> 1 Hz)
    slow_dps          RMS rotation in 0.5-2.5 Hz (voluntary movement), deg/s
    motion_dps        RMS rotation in 0.5-12 Hz, deg/s
    mean_rotation_dps size of the mean rotation vector, deg/s (whole-body turning)
    acc_std           standard deviation of the acceleration magnitude, m/s²
    gravity_change_deg  angle between this window's mean acceleration direction and the previous one's
    freeze_index      acceleration-magnitude power 3-8 Hz / 0.5-3 Hz (Moore 2008)

  4 s (200 samples), the gait window
    cadence_spm       steps per minute from the stride period (autocorrelation of the acceleration magnitude)
    step_regularity   autocorrelation at that stride period, 0-1
    arm_swing_dps     RMS rotation in 0.5-2 Hz, deg/s

Input units are the watch session units: acceleration in m/s² (gravity included), rotation rate
in rad/s. Use resample_uniform first if the samples are not evenly spaced.

Wrist axes differ between watch models and between left and right wrists, so every feature is
axis-free: magnitudes, sums over axes, or angles between vectors.
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from enum import Enum
from typing import Iterable, Optional

import numpy as np

VERSION = "stream-features-1.0"

FS = 50.0
HOP = 50            # samples, 1 s
WIN = 100           # samples, 2 s
GAIT_WIN = 200      # samples, 4 s
RAD_TO_DEG = 180.0 / np.pi

# Stride (two steps) period search range: 35-70 strides/min = 70-140 steps/min.
STRIDE_LAG_MIN = int(round(FS * 60 / 70))    # 43 samples
STRIDE_LAG_MAX = int(round(FS * 60 / 35))    # 86 samples

# Context thresholds (provisional; tuned on Parkinson@Home and the volunteer recordings).
WALK_REGULARITY = 0.5
WALK_ACC_STD = 0.5          # m/s²
WALK_MAX_FREEZE_INDEX = 1.0  # walking puts most acceleration power below 3 Hz; trembling doesn't
STILL_MOTION_DPS = 3.0
STILL_ACC_STD = 0.15        # m/s²
REST_SLOW_DPS = 20.0        # same limit as tremor-1.0's "moved" rule
POSTURE_CHANGE_DEG = 15.0
TURN_MEAN_ROTATION_DPS = 25.0


class Context(str, Enum):
    STILL = "still"
    REST_TREMOR_POSSIBLE = "rest"   # hand at rest or holding a posture: tremor can be judged
    ACTIVE_HAND = "active"          # voluntary hand or arm movement, or a posture change
    WALKING = "walking"
    TURNING = "turning"             # walking and turning


@dataclass
class WindowFeatures:
    t_end_ms: int
    tremor_dps: float
    peak_hz: float
    prominence: float
    slow_dps: float
    motion_dps: float
    mean_rotation_dps: float
    acc_std: float
    gravity_change_deg: float
    freeze_index: float
    cadence_spm: float
    step_regularity: float
    arm_swing_dps: float
    context: Context

    def to_dict(self) -> dict:
        d = asdict(self)
        d["context"] = self.context.value
        return d


# --------------------------------------------------------------------- helpers

def resample_uniform(t_ms: np.ndarray, *channels: np.ndarray, fs: float = FS):
    """Linear interpolation of each channel onto an even grid from t_ms[0]. Returns (t_ms, channels...)."""
    t_ms = np.asarray(t_ms, dtype=float)
    n = int((t_ms[-1] - t_ms[0]) / 1000.0 * fs) + 1
    grid = t_ms[0] + np.arange(n) * 1000.0 / fs
    return (grid.astype(np.int64), *[np.interp(grid, t_ms, np.asarray(c, dtype=float)) for c in channels])


def _hann(n: int) -> np.ndarray:
    return 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / (n - 1))


_HANN = {WIN: _hann(WIN), GAIT_WIN: _hann(GAIT_WIN)}


def _psd(x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """One-sided power spectral density of the last axis (demeaned, Hann). Returns (freqs, psd).

    The normalisation matches tremor-1.0: integrating the psd over a band gives the band's
    mean-square value, so sqrt(band power) is an RMS in the input's units.
    """
    n = x.shape[-1]
    w = _HANN[n]
    xc = (x - x.mean(axis=-1, keepdims=True)) * w
    spec = np.fft.rfft(xc, axis=-1)
    psd = 2.0 * np.abs(spec) ** 2 / (FS * np.sum(w * w))
    return np.fft.rfftfreq(n, 1.0 / FS), psd


def _band(freqs: np.ndarray, psd: np.ndarray, low: float, high: float) -> np.ndarray:
    df = freqs[1] - freqs[0]
    m = (freqs >= low - 1e-9) & (freqs <= high + 1e-9)
    return psd[..., m].sum(axis=-1) * df


def _autocorr(x: np.ndarray, lag: int) -> float:
    """Normalised autocorrelation of a demeaned signal at one lag (unbiased by overlap length)."""
    a, b = x[:-lag], x[lag:]
    den = np.sqrt(np.sum(a * a) * np.sum(b * b))
    return float(np.sum(a * b) / den) if den > 0 else 0.0


# --------------------------------------------------------------------- one window

def window_features(
    acc2: np.ndarray, gyr2: np.ndarray, acc4: Optional[np.ndarray], gyr4: Optional[np.ndarray],
    prev_gravity: Optional[np.ndarray], t_end_ms: int,
) -> tuple[WindowFeatures, np.ndarray]:
    """Features of one hop.

    acc2 and gyr2 are (3, 100) arrays, the last 2 s. acc4 and gyr4 are (3, 200) arrays, the last
    4 s, or None before 4 s of data exist. Returns the features and this window's gravity
    direction, which is passed back as prev_gravity next time.
    """
    # Gyroscope spectrum over 2 s, three axes summed.
    f, p = _psd(gyr2)
    p = p.sum(axis=0)
    tremor_dps = float(np.sqrt(_band(f, p, 3.0, 8.0)) * RAD_TO_DEG)
    slow_dps = float(np.sqrt(_band(f, p, 0.5, 2.5)) * RAD_TO_DEG)
    motion_dps = float(np.sqrt(_band(f, p, 0.5, 12.0)) * RAD_TO_DEG)

    in_band = np.where((f >= 3.0 - 1e-9) & (f <= 8.0 + 1e-9))[0]
    k = int(in_band[np.argmax(p[in_band])])
    peak_hz = float(f[k])
    if 0 < k < len(p) - 1:
        a, b, c = p[k - 1], p[k], p[k + 1]
        den = a - 2 * b + c
        if den != 0:
            peak_hz += float(np.clip(0.5 * (a - c) / den, -0.5, 0.5)) * (f[1] - f[0])
    rest = (f >= 0.5 - 1e-9) & (f <= 12.0 + 1e-9) & (np.abs(f - f[k]) > 1.0 + 1e-9)
    mean_rest = float(p[rest].mean()) if rest.any() else 0.0
    prominence = float(p[k] / mean_rest) if mean_rest > 0 else 0.0

    mean_rotation_dps = float(np.linalg.norm(gyr2.mean(axis=1)) * RAD_TO_DEG)

    # Acceleration magnitude: steps, freeze index, stillness.
    mag2 = np.sqrt((acc2 * acc2).sum(axis=0))
    acc_std = float(mag2.std())
    fa, pa = _psd(mag2)
    low = float(_band(fa, pa, 0.5, 3.0))
    high = float(_band(fa, pa, 3.0 + 0.5, 8.0))  # bins are 0.5 Hz apart: 3.5-8 Hz, so 3.0 Hz is not counted twice
    freeze_index = high / low if low > 0 else 0.0

    gravity = acc2.mean(axis=1)
    norm = np.linalg.norm(gravity)
    gravity = gravity / norm if norm > 0 else gravity
    if prev_gravity is None:
        gravity_change_deg = 0.0
    else:
        cosang = float(np.clip(np.dot(gravity, prev_gravity), -1.0, 1.0))
        gravity_change_deg = float(np.degrees(np.arccos(cosang)))

    # Gait window, 4 s.
    cadence_spm, step_regularity, arm_swing_dps = 0.0, 0.0, 0.0
    if acc4 is not None and gyr4 is not None:
        mag4 = np.sqrt((acc4 * acc4).sum(axis=0))
        x = mag4 - mag4.mean()
        best_lag, best_r = 0, -1.0
        for lag in range(STRIDE_LAG_MIN, STRIDE_LAG_MAX + 1):
            r = _autocorr(x, lag)
            if r > best_r:
                best_lag, best_r = lag, r
        step_regularity = max(best_r, 0.0)
        cadence_spm = 2 * 60.0 * FS / best_lag if best_lag else 0.0
        fg, pg = _psd(gyr4)
        arm_swing_dps = float(np.sqrt(_band(fg, pg.sum(axis=0), 0.5, 2.0)) * RAD_TO_DEG)

    feats = WindowFeatures(
        t_end_ms=int(t_end_ms), tremor_dps=tremor_dps, peak_hz=peak_hz, prominence=prominence,
        slow_dps=slow_dps, motion_dps=motion_dps, mean_rotation_dps=mean_rotation_dps,
        acc_std=acc_std, gravity_change_deg=gravity_change_deg, freeze_index=freeze_index,
        cadence_spm=cadence_spm, step_regularity=step_regularity, arm_swing_dps=arm_swing_dps,
        context=Context.STILL,
    )
    feats.context = classify_context(feats)
    return feats, gravity


def classify_context(f: WindowFeatures) -> Context:
    """Rule-based context, in priority order: walking, posture change, still, rest, active."""
    # A steady 3-8 Hz tremble also repeats at stride lags, so regularity alone isn't walking.
    walking = (f.step_regularity >= WALK_REGULARITY and f.acc_std >= WALK_ACC_STD
               and f.freeze_index < WALK_MAX_FREEZE_INDEX)
    if walking:
        return Context.TURNING if f.mean_rotation_dps >= TURN_MEAN_ROTATION_DPS else Context.WALKING
    if f.gravity_change_deg >= POSTURE_CHANGE_DEG:
        return Context.ACTIVE_HAND
    if f.motion_dps < STILL_MOTION_DPS and f.acc_std < STILL_ACC_STD:
        return Context.STILL
    if f.slow_dps < REST_SLOW_DPS:
        return Context.REST_TREMOR_POSSIBLE
    return Context.ACTIVE_HAND


# --------------------------------------------------------------------- streaming and batch

class StreamFeatureExtractor:
    """Push evenly spaced 50 Hz samples one at a time; a WindowFeatures comes out every second.

    This mirrors what the watches do: a 4 s ring buffer, one computation per hop. The first
    window comes out once 2 s of samples exist; gait features stay 0 until 4 s exist.
    """

    def __init__(self):
        self._acc = np.zeros((3, GAIT_WIN))
        self._gyr = np.zeros((3, GAIT_WIN))
        self._n = 0
        self._prev_gravity: Optional[np.ndarray] = None

    def push(self, t_ms: int, ax, ay, az, gx, gy, gz) -> Optional[WindowFeatures]:
        self._acc = np.roll(self._acc, -1, axis=1)
        self._gyr = np.roll(self._gyr, -1, axis=1)
        self._acc[:, -1] = (ax, ay, az)
        self._gyr[:, -1] = (gx, gy, gz)
        self._n += 1
        if self._n < WIN or (self._n - WIN) % HOP != 0:
            return None
        full = self._n >= GAIT_WIN
        feats, self._prev_gravity = window_features(
            self._acc[:, -WIN:], self._gyr[:, -WIN:],
            self._acc if full else None, self._gyr if full else None,
            self._prev_gravity, t_ms,
        )
        return feats


def compute_features(t_ms: np.ndarray, acc: np.ndarray, gyr: np.ndarray) -> list[WindowFeatures]:
    """All windows of an evenly sampled 50 Hz recording. acc and gyr are (3, n) arrays.

    Gives the same windows as pushing every sample through StreamFeatureExtractor,
    just without the per-sample overhead.
    """
    n = acc.shape[1]
    out: list[WindowFeatures] = []
    prev = None
    for end in range(WIN, n + 1, HOP):
        g = end >= GAIT_WIN
        f, prev = window_features(
            acc[:, end - WIN:end], gyr[:, end - WIN:end],
            acc[:, end - GAIT_WIN:end] if g else None, gyr[:, end - GAIT_WIN:end] if g else None,
            prev, int(t_ms[end - 1]),
        )
        out.append(f)
    return out


def features_frame(windows: Iterable[WindowFeatures]):
    """WindowFeatures as a pandas DataFrame (one row per second)."""
    import pandas as pd
    return pd.DataFrame([w.to_dict() for w in windows])
