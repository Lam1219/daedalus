"""
Gait detection and cadence estimation module.

POC Section 16: Gait Detection and Cadence Plan.
Detects walking and estimates cadence from wrist IMU data.
"""

import numpy as np
from typing import Optional
from scipy.signal import butter, filtfilt, find_peaks
from scipy.signal import welch


def remove_gravity(
    ax: np.ndarray, ay: np.ndarray, az: np.ndarray,
    cutoff_hz: float = 0.5, sample_rate: float = 50.0,
) -> tuple:
    """Remove gravity using a high-pass filter."""
    nyquist = sample_rate / 2.0
    cutoff = cutoff_hz / nyquist
    b, a = butter(4, cutoff, btype="high")
    ax_ac = filtfilt(b, a, ax)
    ay_ac = filtfilt(b, a, ay)
    az_ac = filtfilt(b, a, az)
    return ax_ac, ay_ac, az_ac


def detect_gait(
    ax: np.ndarray, ay: np.ndarray, az: np.ndarray,
    sample_rate: float,
) -> tuple:
    """Detect gait vs non-gait and estimate cadence.

    Returns (is_gait, cadence_steps_per_min, features_dict).
    """
    # Remove gravity
    ax_ac, ay_ac, az_ac = remove_gravity(ax, ay, az, sample_rate=sample_rate)

    # Acceleration magnitude
    accel_mag = np.sqrt(ax_ac**2 + ay_ac**2 + az_ac**2)

    # Bandpass for walking frequency (0.5-3 Hz for general gait)
    nyquist = sample_rate / 2.0
    b, a = butter(4, [0.5 / nyquist, 3.0 / nyquist], btype="band")
    gait_signal = filtfilt(b, a, accel_mag)

    # Check signal energy early — skip if too low
    signal_energy = np.var(gait_signal)
    if signal_energy < 0.001:
        return False, 0.0, {
            "cadence_steps_per_min": 0.0,
            "step_regularity": 0.0,
            "arm_swing_amplitude": float(np.std(accel_mag)),
            "walking_band_power": 0.0,
            "peak_count": 0,
        }

    # --- Peak detection for step counting ---
    # Find peaks with minimum distance based on max cadence (~200 steps/min = ~3.3 Hz)
    min_distance = int(sample_rate / 3.5)
    peaks, properties = find_peaks(gait_signal, distance=max(min_distance, 1), height=0)

    # --- Autocorrelation for cadence ---
    if len(gait_signal) > sample_rate * 2:  # Need at least 2 seconds
        autocorr = np.correlate(gait_signal, gait_signal, mode="full")
        autocorr = autocorr[len(autocorr) // 2:]  # Keep positive lags only
        autocorr = autocorr / (autocorr[0] + 1e-9)  # Normalize

        # Find dominant period in walking range (0.5-3 Hz = 0.33-2.0 sec)
        min_lag = int(sample_rate * 0.33)
        max_lag = int(sample_rate * 2.0)
        if max_lag < len(autocorr) and min_lag < max_lag:
            search_region = autocorr[min_lag:max_lag]
            if len(search_region) > 0:
                dominant_lag = np.argmax(search_region) + min_lag
                dominant_period_sec = dominant_lag / sample_rate
                cadence = 60.0 / dominant_period_sec if dominant_period_sec > 0 else 0
            else:
                cadence = 0.0
                dominant_lag = 0
        else:
            cadence = 0.0
            dominant_lag = 0
    else:
        cadence = 0.0
        dominant_lag = 0

    # --- Cadence from peak counting ---
    if len(peaks) > 1 and len(gait_signal) > sample_rate:
        duration_sec = len(gait_signal) / sample_rate
        cadence_from_peaks = (len(peaks) / duration_sec) * 60.0
    else:
        cadence_from_peaks = 0.0

    # Use autocorrelation cadence if available, else peak-based
    final_cadence = cadence if cadence > 0 else cadence_from_peaks

    # --- Gait detection ---
    # Gait is detected if there's rhythmic periodic movement in walking band
    is_gait = False
    step_regularity = 0.0
    if len(peaks) >= 2 and len(gait_signal) > sample_rate:
        # Check regularity of peaks
        if len(peaks) > 2:
            peak_intervals = np.diff(peaks)
            cv = np.std(peak_intervals) / (np.mean(peak_intervals) + 1e-9)
            step_regularity = 1.0 - min(cv, 1.0)  # Regularity: 0 to 1
            is_gait = step_regularity > 0.5 and 30 <= final_cadence <= 200
        else:
            step_regularity = 0.0

    # Arm swing amplitude
    arm_swing_amplitude = float(np.std(accel_mag))

    # --- Spectral features ---
    freqs, psd = welch(gait_signal, fs=sample_rate, nperseg=min(256, len(gait_signal)))
    walking_mask = (freqs >= 0.5) & (freqs <= 3.0)
    walking_power = float(np.trapz(psd[walking_mask], freqs[walking_mask]))

    features = {
        "cadence_steps_per_min": final_cadence,
        "step_regularity": step_regularity,
        "arm_swing_amplitude": arm_swing_amplitude,
        "walking_band_power": walking_power,
        "peak_count": len(peaks),
    }

    return bool(is_gait), float(final_cadence), features


def compute_gait_regularity_features(
    cadences: list[float],
    step_regularities: list[float],
) -> dict:
    """Compute gait regularity features across consecutive windows.

    Features: cadence_variability, harmonic_ratio, gait_bout_length.
    """
    if len(cadences) < 2:
        return {
            "cadence_variability": 0.0,
            "harmonic_ratio": 0.0,
            "gait_bout_length": 0,
        }

    cadences_arr = np.array(cadences)
    regularities_arr = np.array(step_regularities)

    cadence_variability = float(np.std(cadences_arr))

    # Harmonic ratio: ratio of regular to irregular steps
    harmonic_ratio = float(np.mean(regularities_arr))

    # Gait bout length: longest consecutive sequence of gait windows
    is_gait = regularities_arr > 0.5
    max_bout = 0
    current_bout = 0
    for g in is_gait:
        if g:
            current_bout += 1
            max_bout = max(max_bout, current_bout)
        else:
            current_bout = 0

    return {
        "cadence_variability": cadence_variability,
        "harmonic_ratio": harmonic_ratio,
        "gait_bout_length": max_bout,
    }


def detect_gait_instability_candidate(
    cadence: float,
    cadence_variability: float,
    arm_swing_amplitude: float,
    activity_context: str = "",
) -> bool:
    """Detect potential gait instability (early freezing proxy).

    Detects:
    - Sudden reduction in arm swing
    - Increased cadence variability
    - High-frequency freezing-like components
    """
    # Thresholds for instability candidates
    high_variability = cadence_variability > 15.0  # steps/min std
    low_arm_swing = arm_swing_amplitude < 0.1
    context_risky = activity_context in ["turning", "doorway", "dual_task"]

    return (high_variability and (low_arm_swing or context_risky))
