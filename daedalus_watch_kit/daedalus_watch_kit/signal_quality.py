"""
Signal quality scoring module.

Stage 4 of the core processing pipeline (POC Section 12, Stage 4).
Computes HR_SQI and IMU_SQI for each analysis window.
"""

import numpy as np
import pandas as pd
from typing import Optional

from .quality_rules import (
    SQI_GOOD, SQI_UNCERTAIN,
    MAX_MISSING_SAMPLES_PCT,
    HR_MIN_BPM, HR_MAX_BPM,
    classify_sqi,
)


def compute_imu_quality(
    imu_window: pd.DataFrame,
    missing_threshold_pct: float = MAX_MISSING_SAMPLES_PCT,
) -> float:
    """Compute IMU Signal Quality Index (0 to 1) for a window.

    Features:
    - Missing sample ratio
    - Sensor clipping / unrealistic values
    - Signal noise floor
    """
    if imu_window.empty:
        return 0.0

    sqi = 1.0

    # Missing samples
    for col in ["ax", "ay", "az", "gx", "gy", "gz"]:
        if col in imu_window.columns:
            missing_pct = imu_window[col].isna().sum() / len(imu_window) * 100
            if missing_pct > missing_threshold_pct:
                sqi *= 0.5
            elif missing_pct > 5:
                sqi *= 0.8

    # Unrealistic values (clipping)
    accel_cols = ["ax", "ay", "az"]
    present_cols = [c for c in accel_cols if c in imu_window.columns]
    if present_cols:
        accel_data = imu_window[present_cols].dropna()
        if not accel_data.empty:
            # Typical watch accelerometer range: +/- 2g (~19.6 m/s^2)
            if (accel_data.abs() > 20.0).any().any():
                sqi *= 0.6

    # Noise floor — very low variance suggests sensor issue
    if present_cols:
        accel_data = imu_window[present_cols].dropna()
        if not accel_data.empty:
            variances = accel_data.var()
            if (variances < 1e-6).any():
                sqi *= 0.7

    return float(max(0.0, min(1.0, sqi)))


def compute_hr_quality_from_hr(
    hr_values: pd.Series,
    imu_motion_intensity: Optional[np.ndarray] = None,
) -> float:
    """Compute HR Signal Quality Index from HR values alone (no raw PPG).

    Features:
    - HR variability plausibility
    - Sudden HR jumps
    - Missing HR samples
    - IMU motion intensity correlation
    """
    if hr_values.empty or hr_values.isna().all():
        return 0.0

    sqi = 1.0

    # Missing samples
    missing_pct = hr_values.isna().sum() / len(hr_values) * 100
    if missing_pct > MAX_MISSING_SAMPLES_PCT:
        sqi *= 0.3
    elif missing_pct > 5:
        sqi *= 0.7

    # Physiological plausibility
    valid_hr = hr_values.dropna()
    if len(valid_hr) > 0:
        implausible = ((valid_hr < HR_MIN_BPM) | (valid_hr > HR_MAX_BPM)).sum()
        implausible_pct = implausible / len(valid_hr)
        if implausible_pct > 0.1:
            sqi *= 0.3
        elif implausible_pct > 0.0:
            sqi *= 0.8

    # Sudden HR jumps (> 20 bpm between consecutive samples is suspicious)
    if len(valid_hr) > 1:
        diffs = valid_hr.diff().abs().dropna()
        large_jumps = (diffs > 20).sum()
        jump_pct = large_jumps / len(diffs)
        if jump_pct > 0.1:
            sqi *= 0.5

    # Motion context penalty
    if imu_motion_intensity is not None and len(imu_motion_intensity) > 0:
        mean_intensity = np.mean(imu_motion_intensity)
        if mean_intensity > 1.0:  # High motion
            sqi *= 0.7
        elif mean_intensity > 0.5:
            sqi *= 0.85

    return float(max(0.0, min(1.0, sqi)))


def compute_hr_quality_from_ppg(
    ppg_signal: np.ndarray,
    imu_signal: Optional[np.ndarray] = None,
) -> float:
    """Compute HR SQI from raw PPG signal (if available).

    Features:
    - Pulse morphology quality
    - Peak regularity
    - Signal-to-noise ratio
    - Motion intensity correlation
    - Amplitude stability
    """
    if len(ppg_signal) == 0:
        return 0.0

    sqi = 1.0

    # SNR estimate: ratio of signal variance to high-frequency noise
    from scipy.signal import butter, filtfilt
    b, a = butter(4, 0.1, btype="high")
    noise = filtfilt(b, a, ppg_signal - np.mean(ppg_signal))
    signal_var = np.var(ppg_signal)
    noise_var = np.var(noise)
    if noise_var > 0:
        snr = signal_var / noise_var
        if snr < 5:
            sqi *= 0.4
        elif snr < 10:
            sqi *= 0.6
        elif snr < 20:
            sqi *= 0.8

    # Amplitude stability
    if len(ppg_signal) > 10:
        segments = np.array_split(ppg_signal, min(10, len(ppg_signal)))
        amps = [np.ptp(s) for s in segments if len(s) > 0]
        if len(amps) > 1:
            amp_cv = np.std(amps) / (np.mean(amps) + 1e-9)
            if amp_cv > 1.0:
                sqi *= 0.5

    # Motion artifact correlation
    if imu_signal is not None and len(imu_signal) == len(ppg_signal):
        corr = np.abs(np.corrcoef(ppg_signal, imu_signal)[0, 1])
        if corr > 0.7:
            sqi *= 0.5  # High correlation with motion suggests contamination

    return float(max(0.0, min(1.0, sqi)))


def classify_bad_segments(
    sqi_values: np.ndarray,
    threshold: float = SQI_UNCERTAIN,
) -> np.ndarray:
    """Classify windows as bad (True) or acceptable (False) based on SQI."""
    return sqi_values < threshold


def signal_quality_report(windows_sqi: pd.DataFrame) -> dict:
    """Generate a summary report for signal quality across windows."""
    if windows_sqi.empty:
        return {"total_windows": 0}

    sqi = windows_sqi["sqi"].values
    return {
        "total_windows": len(sqi),
        "good_windows": int(np.sum(sqi >= SQI_GOOD)),
        "uncertain_windows": int(np.sum((sqi >= SQI_UNCERTAIN) & (sqi < SQI_GOOD))),
        "bad_windows": int(np.sum(sqi < SQI_UNCERTAIN)),
        "mean_sqi": float(np.mean(sqi)),
        "median_sqi": float(np.median(sqi)),
        "good_pct": float(np.mean(sqi >= SQI_GOOD) * 100),
    }
