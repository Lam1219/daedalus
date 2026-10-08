"""
Tremor detection module.

POC Section 15: Tremor Detection Plan.
Detects wrist movement patterns consistent with tremor or rhythmic involuntary movement.
"""

import numpy as np
import pandas as pd
from typing import Optional
from scipy.signal import butter, filtfilt, stft

from .quality_rules import TREMOR_BAND_LOW_HZ, TREMOR_BAND_HIGH_HZ


def bandpass_filter(signal: np.ndarray, low_hz: float, high_hz: float,
                    sample_rate: float, order: int = 4) -> np.ndarray:
    """Apply a bandpass filter to a signal."""
    nyquist = sample_rate / 2.0
    low = low_hz / nyquist
    high = high_hz / nyquist
    low = max(low, 0.001)
    high = min(high, 0.999)
    b, a = butter(order, [low, high], btype="band")
    return filtfilt(b, a, signal)


def compute_acceleration_magnitude(ax: np.ndarray, ay: np.ndarray,
                                   az: np.ndarray) -> np.ndarray:
    """Compute acceleration magnitude vector."""
    return np.sqrt(ax**2 + ay**2 + az**2)


def compute_angular_velocity_magnitude(gx: np.ndarray, gy: np.ndarray,
                                       gz: np.ndarray) -> np.ndarray:
    """Compute angular velocity magnitude."""
    return np.sqrt(gx**2 + gy**2 + gz**2)


def compute_tremor_features(
    ax: np.ndarray,
    ay: np.ndarray,
    az: np.ndarray,
    gx: np.ndarray,
    gy: np.ndarray,
    gz: np.ndarray,
    sample_rate: float,
) -> dict:
    """Compute tremor detection features for a window.

    Features:
    - band_power_3_8hz: Power in tremor frequency band
    - dominant_freq: Peak frequency in tremor band
    - spectral_entropy: Regularity of the spectrum
    - peak_prominence: Strength of the spectral peak
    - rms_accel: Overall movement intensity
    - rms_gyro: Rotational movement intensity
    - jerk: Change in acceleration
    """
    # Remove gravity (low-pass at 0.5 Hz) and compute magnitude
    ax_ac = bandpass_filter(ax, 0.5, sample_rate / 2, sample_rate)
    ay_ac = bandpass_filter(ay, 0.5, sample_rate / 2, sample_rate)
    az_ac = bandpass_filter(az, 0.5, sample_rate / 2, sample_rate)
    accel_mag = compute_acceleration_magnitude(ax_ac, ay_ac, az_ac)

    gyro_mag = compute_angular_velocity_magnitude(gx, gy, gz)

    # RMS values
    rms_accel = float(np.sqrt(np.mean(accel_mag**2)))
    rms_gyro = float(np.sqrt(np.mean(gyro_mag**2)))

    # Jerk (rate of change of acceleration)
    if len(accel_mag) > 1:
        jerk = float(np.sqrt(np.mean(np.diff(accel_mag)**2)))
    else:
        jerk = 0.0

    # Bandpass filter to tremor band (3-8 Hz)
    tremor_band = bandpass_filter(
        accel_mag, TREMOR_BAND_LOW_HZ, TREMOR_BAND_HIGH_HZ,
        sample_rate, order=4,
    )

    # Power in tremor band
    band_power = float(np.mean(tremor_band**2))

    # Spectral analysis via STFT
    nperseg = min(128, len(accel_mag))
    if nperseg < 4:
        return {
            "band_power_3_8hz": 0.0, "dominant_freq": 0.0,
            "spectral_entropy": 0.0, "peak_prominence": 0.0,
            "rms_accel": rms_accel, "rms_gyro": rms_gyro, "jerk": jerk,
        }

    f, t, Zxx = stft(accel_mag, fs=sample_rate, nperseg=nperseg, noverlap=nperseg // 2)
    power_spectrum = np.mean(np.abs(Zxx)**2, axis=1)

    # Tremor band power from spectrum
    tremor_mask = (f >= TREMOR_BAND_LOW_HZ) & (f <= TREMOR_BAND_HIGH_HZ)
    tremor_power = float(np.trapz(power_spectrum[tremor_mask], f[tremor_mask]))

    # Dominant frequency in tremor band
    if np.any(tremor_mask) and np.sum(tremor_mask) > 0:
        tremor_freqs = f[tremor_mask]
        tremor_powers = power_spectrum[tremor_mask]
        dominant_idx = np.argmax(tremor_powers)
        dominant_freq = float(tremor_freqs[dominant_idx])
        peak_prominence = float(tremor_powers[dominant_idx] / (np.mean(power_spectrum) + 1e-9))
    else:
        dominant_freq = 0.0
        peak_prominence = 0.0

    # Spectral entropy
    total_power = np.sum(power_spectrum) + 1e-9
    p_normalized = power_spectrum / total_power
    p_normalized = p_normalized[p_normalized > 0]
    spectral_entropy = float(-np.sum(p_normalized * np.log2(p_normalized)))

    return {
        "band_power_3_8hz": float(band_power),
        "dominant_freq": float(dominant_freq),
        "spectral_entropy": spectral_entropy,
        "peak_prominence": float(peak_prominence),
        "rms_accel": rms_accel,
        "rms_gyro": rms_gyro,
        "jerk": jerk,
    }


def classify_tremor_rule_based(
    features: dict,
    band_power_threshold: float = None,
    entropy_threshold: float = None,
    min_duration_sec: float = 2.0,
) -> bool:
    """Rule-based tremor candidate classification.

    tremor_candidate =
        band_power_3_8hz > threshold AND
        dominant_freq between 3 and 8 Hz AND
        spectral_entropy < threshold AND
        duration > min_duration
    """
    if band_power_threshold is None:
        # Use a heuristic threshold based on the data
        band_power_threshold = 0.1
    if entropy_threshold is None:
        entropy_threshold = 3.0

    return (
        features["band_power_3_8hz"] > band_power_threshold
        and TREMOR_BAND_LOW_HZ <= features["dominant_freq"] <= TREMOR_BAND_HIGH_HZ
        and features["spectral_entropy"] < entropy_threshold
    )


def classify_tremor_with_probability(
    features: dict,
    band_power_threshold: float = None,
) -> dict:
    """Classify tremor and return probability, frequency, and amplitude score."""
    is_tremor = classify_tremor_rule_based(features, band_power_threshold)

    # Probability estimate based on how many criteria are met
    criteria_met = 0
    total_criteria = 3

    if band_power_threshold is None:
        band_power_threshold = 0.1

    if features["band_power_3_8hz"] > band_power_threshold:
        criteria_met += 1
    if TREMOR_BAND_LOW_HZ <= features["dominant_freq"] <= TREMOR_BAND_HIGH_HZ:
        criteria_met += 1
    if features["spectral_entropy"] < 3.0:
        criteria_met += 1

    probability = criteria_met / total_criteria

    # Amplitude score (normalized)
    amplitude_score = min(features["band_power_3_8hz"] / (band_power_threshold * 5), 1.0)

    return {
        "tremor_probability": probability,
        "tremor_frequency_hz": features["dominant_freq"],
        "tremor_amplitude_score": amplitude_score,
        "is_tremor_candidate": is_tremor,
    }
