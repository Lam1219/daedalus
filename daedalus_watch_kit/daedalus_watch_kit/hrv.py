"""
HRV processing module.

POC Section 13: HRV Processing Plan.
Computes time-domain, frequency-domain, and nonlinear HRV features from RR
intervals. The RR intervals come from either the Samsung Galaxy Watch5 Pro
(derived) or the Cycplus H2 chest strap (reference ground truth).
"""

import numpy as np
import pandas as pd
from typing import Optional
from scipy.signal import welch, lombscargle
from scipy.interpolate import interp1d


def clean_ibi(
    rr_intervals: np.ndarray,
    min_rr_ms: float = 300,
    max_rr_ms: float = 2000,
    max_step_change_pct: float = 20,
) -> np.ndarray:
    """Remove ectopic/artifact beats from RR interval series.

    Returns cleaned RR intervals with artifacts replaced by interpolated values.
    """
    cleaned = rr_intervals.copy().astype(float)
    mask = np.ones(len(cleaned), dtype=bool)

    # Physiological range filter
    mask &= (cleaned >= min_rr_ms) & (cleaned <= max_rr_ms)

    # Step change filter
    if len(cleaned) > 1:
        pct_change = np.abs(np.diff(cleaned)) / cleaned[:-1] * 100
        step_mask = np.concatenate([[True], pct_change <= max_step_change_pct])
        mask &= step_mask

    # Replace bad values with linear interpolation
    bad_indices = np.where(~mask)[0]
    good_indices = np.where(mask)[0]
    if len(bad_indices) > 0 and len(good_indices) >= 2:
        # Clamp bad indices to interpolation range
        good_min, good_max = good_indices[0], good_indices[-1]
        clamped_bad = bad_indices[(bad_indices >= good_min) & (bad_indices <= good_max)]
        if len(clamped_bad) > 0:
            interp_fn = interp1d(good_indices, cleaned[good_indices], kind="linear",
                                 fill_value="extrapolate")
            cleaned[clamped_bad] = interp_fn(clamped_bad)
        # For out-of-range bad indices, use nearest good value
        for idx in bad_indices:
            if idx not in clamped_bad:
                nearest = good_indices[np.argmin(np.abs(good_indices - idx))]
                cleaned[idx] = cleaned[nearest]

    return cleaned


def compute_time_domain_features(rr_intervals: np.ndarray) -> dict:
    """Compute time-domain HRV features.

    Features: mean_hr, sdnn, rmssd, pnn50, nn_outlier_rate
    """
    if len(rr_intervals) < 2:
        return {
            "mean_hr": np.nan,
            "sdnn": np.nan,
            "rmssd": np.nan,
            "pnn50": np.nan,
            "nn_outlier_rate": np.nan,
        }

    rr = rr_intervals.astype(float)

    # Mean HR
    mean_hr = 60000.0 / np.mean(rr)  # bpm

    # SDNN
    sdnn = np.std(rr)

    # RMSSD
    diff = np.diff(rr)
    rmssd = np.sqrt(np.mean(diff**2))

    # pNN50
    nn50 = np.sum(np.abs(diff) > 50)
    pnn50 = nn50 / len(diff) * 100

    # NN outlier rate
    q1, q3 = np.percentile(rr, [25, 75])
    iqr = q3 - q1
    outliers = np.sum((rr < q1 - 1.5 * iqr) | (rr > q3 + 1.5 * iqr))
    nn_outlier_rate = outliers / len(rr)

    return {
        "mean_hr": float(mean_hr),
        "sdnn": float(sdnn),
        "rmssd": float(rmssd),
        "pnn50": float(pnn50),
        "nn_outlier_rate": float(nn_outlier_rate),
    }


def compute_frequency_domain_features(
    rr_intervals: np.ndarray,
    sample_times_ms: Optional[np.ndarray] = None,
    method: str = "welch",
) -> dict:
    """Compute frequency-domain HRV features.

    Uses Lomb-Scargle for irregular sampling or Welch on resampled data.

    Features: lf_power, hf_power, lf_hf_ratio, total_power
    """
    if len(rr_intervals) < 4:
        return {
            "lf_power": np.nan, "hf_power": np.nan,
            "lf_hf_ratio": np.nan, "total_power": np.nan,
        }

    rr = rr_intervals.astype(float)

    if method == "lomb_scargle" or sample_times_ms is None:
        # Lomb-Scargle for irregularly sampled data
        if sample_times_ms is None:
            # Cumulative time from RR intervals
            sample_times_ms = np.cumsum(rr)

        # Convert to seconds
        t_sec = sample_times_ms / 1000.0
        # Remove mean
        rr_centered = rr - np.mean(rr)

        # Frequency range: 0.01 to 0.5 Hz (typical HRV band)
        freqs = np.linspace(0.01, 0.5, 256)
        pgram = lombscargle(t_sec, rr_centered, 2 * np.pi * freqs)

        # Normalize power
        power = 4 * pgram / len(rr)

        # LF: 0.04-0.15 Hz, HF: 0.15-0.4 Hz
        lf_mask = (freqs >= 0.04) & (freqs <= 0.15)
        hf_mask = (freqs >= 0.15) & (freqs <= 0.4)

        lf_power = np.trapz(power[lf_mask], freqs[lf_mask])
        hf_power = np.trapz(power[hf_mask], freqs[hf_mask])

    else:
        # Resample at 4 Hz then Welch
        fs_resample = 4.0  # Hz
        t_sec = sample_times_ms / 1000.0
        t_uniform = np.arange(t_sec[0], t_sec[-1], 1 / fs_resample)
        interp_fn = interp1d(t_sec, rr, kind="cubic", fill_value="extrapolate")
        rr_uniform = interp_fn(t_uniform)

        freqs, psd = welch(rr_uniform, fs=fs_resample, nperseg=min(256, len(rr_uniform)))

        lf_mask = (freqs >= 0.04) & (freqs <= 0.15)
        hf_mask = (freqs >= 0.15) & (freqs <= 0.4)

        lf_power = np.trapz(psd[lf_mask], freqs[lf_mask])
        hf_power = np.trapz(psd[hf_mask], freqs[hf_mask])

    lf_hf_ratio = lf_power / (hf_power + 1e-9)
    total_power = lf_power + hf_power

    return {
        "lf_power": float(lf_power),
        "hf_power": float(hf_power),
        "lf_hf_ratio": float(lf_hf_ratio),
        "total_power": float(total_power),
    }


def compute_sample_entropy(rr_intervals: np.ndarray, m: int = 2, r_factor: float = 0.2) -> float:
    """Compute sample entropy of RR interval series.

    A measure of complexity/regularity.
    """
    if len(rr_intervals) < 10:
        return np.nan

    rr = rr_intervals.astype(float)
    r = r_factor * np.std(rr)
    n = len(rr)

    def _count_matches(embedding, r_val):
        count = 0
        for i in range(len(embedding)):
            for j in range(i + 1, len(embedding)):
                if np.all(np.abs(embedding[i] - embedding[j]) < r_val):
                    count += 1
        return count

    # Create embeddings
    def create_embeddings(data, dim):
        return np.array([data[i:i + dim] for i in range(len(data) - dim + 1)])

    emb_m = create_embeddings(rr, m)
    emb_m1 = create_embeddings(rr, m + 1)

    a = _count_matches(emb_m1, r)
    b = _count_matches(emb_m, r)

    if b == 0:
        return np.nan

    return -np.log(a / b)


def compute_all_hrv_features(
    rr_intervals: np.ndarray,
    sample_times_ms: Optional[np.ndarray] = None,
) -> dict:
    """Compute all HRV features for a window."""
    cleaned = clean_ibi(rr_intervals)

    time_features = compute_time_domain_features(cleaned)
    freq_features = compute_frequency_domain_features(cleaned, sample_times_ms)
    sample_ent = compute_sample_entropy(cleaned)

    return {
        **time_features,
        **freq_features,
        "sample_entropy": sample_ent,
    }
