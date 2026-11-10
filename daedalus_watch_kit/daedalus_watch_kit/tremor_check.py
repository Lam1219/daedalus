"""The 20 s tremor check, tremor-1.0: Python twin of TremorAnalyzer.kt / TremorAnalyzer.swift.

Same steps and constants as the watch apps:
  1. drop the first second (the start buzz) and resample evenly at 50 Hz;
  2. remove each axis's mean and apply a Hann window;
  3. sum the three axes' power spectra over 0.5-12 Hz in 0.05 Hz steps.
The outcome is MOVED if there is too much slow movement, TREMOR for a clear 3-8 Hz peak with
enough power, NONE otherwise, and INCOMPLETE for short or gappy data.
Change all copies together.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

import numpy as np

VERSION = "tremor-1.0"

SETTLE_MS = 1000
FS = 50.0
MIN_DURATION_S = 10.0
MAX_GAP_MS = 250
F_MIN, F_MAX, DF = 0.5, 12.0, 0.05
BAND_LOW, BAND_HIGH = 3.0, 8.0
SLOW_HIGH = 2.5
PEAK_EXCLUDE_HZ = 1.0
MIN_TREMOR_DPS = 1.5
MODERATE_DPS = 10.0
STRONG_DPS = 40.0
MAX_SLOW_DPS = 20.0
MIN_PROMINENCE = 6.0


@dataclass
class CheckResult:
    outcome: str                      # "tremor", "none", "moved", "incomplete"
    frequency_hz: Optional[float] = None
    strength: Optional[str] = None
    band_dps: float = 0.0
    prominence: float = 0.0
    version: str = VERSION


def analyze(t_ms, gx, gy, gz) -> CheckResult:
    t_ms = np.asarray(t_ms, dtype=float)
    if len(t_ms) < 2:
        return CheckResult("incomplete")
    start = t_ms[0] + SETTLE_MS
    end = t_ms[-1]
    duration = (end - start) / 1000.0
    if duration < MIN_DURATION_S:
        return CheckResult("incomplete")
    gaps = np.diff(t_ms)
    if np.any((gaps > MAX_GAP_MS) & (t_ms[1:] > start)):
        return CheckResult("incomplete")

    n = int(duration * FS) + 1
    grid = start + np.arange(n) * 1000.0 / FS
    axes = [np.interp(grid, t_ms, np.asarray(a, dtype=float)) for a in (gx, gy, gz)]
    w = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / (n - 1))
    wp = float(np.sum(w * w))
    sig = np.vstack([(a - a.mean()) * w for a in axes])

    freqs = F_MIN + DF * np.arange(int(round((F_MAX - F_MIN) / DF)) + 1)
    phase = np.exp(-2j * np.pi * np.outer(freqs, np.arange(n)) / FS)   # (bins, n)
    spec = sig @ phase.T                                                # (3, bins)
    psd = 2 * (np.abs(spec) ** 2).sum(axis=0) / (FS * wp)

    def band(lo, hi):
        m = (freqs >= lo - 1e-9) & (freqs <= hi + 1e-9)
        return float(psd[m].sum() * DF)

    deg = 180.0 / np.pi
    slow_dps = np.sqrt(band(F_MIN, SLOW_HIGH)) * deg
    band_dps = np.sqrt(band(BAND_LOW, BAND_HIGH)) * deg
    in_band = np.where((freqs >= BAND_LOW - 1e-9) & (freqs <= BAND_HIGH + 1e-9))[0]
    peak = int(in_band[np.argmax(psd[in_band])])
    rest = np.abs(freqs - freqs[peak]) > PEAK_EXCLUDE_HZ
    prominence = float(psd[peak] / psd[rest].mean()) if psd[rest].sum() > 0 else 0.0

    if slow_dps > MAX_SLOW_DPS:
        return CheckResult("moved", band_dps=band_dps, prominence=prominence)
    if band_dps < MIN_TREMOR_DPS or prominence < MIN_PROMINENCE:
        return CheckResult("none", band_dps=band_dps, prominence=prominence)
    hz = float(freqs[peak])
    if 0 < peak < len(psd) - 1:
        a, b, c = psd[peak - 1], psd[peak], psd[peak + 1]
        den = a - 2 * b + c
        if den != 0:
            hz += float(np.clip(0.5 * (a - c) / den, -0.5, 0.5)) * DF
    strength = "strong" if band_dps >= STRONG_DPS else "moderate" if band_dps >= MODERATE_DPS else "light"
    return CheckResult("tremor", round(hz, 1), strength, band_dps, prominence)
