"""
Time synchronization module.

Stage 2 of the core processing pipeline (POC Section 10).
Estimates clock offsets and drift between watch, phone, and ECG devices.
"""

import numpy as np
import pandas as pd
from scipy.signal import correlate


def estimate_offset_cross_correlation(
    watch_signal: np.ndarray,
    reference_signal: np.ndarray,
    sample_rate: float,
) -> float:
    """Estimate clock offset via cross-correlation of two signals.

    Returns offset in milliseconds (positive means watch is ahead).
    """
    corr = correlate(watch_signal - watch_signal.mean(),
                     reference_signal - reference_signal.mean(),
                     mode="full")
    lag = np.argmax(corr) - len(reference_signal) + 1
    offset_ms = (lag / sample_rate) * 1000.0
    return offset_ms


def estimate_linear_drift(
    offsets_ms: list[float],
    elapsed_times_ms: list[float],
) -> float:
    """Estimate linear clock drift from multiple offset measurements.

    Returns drift rate in ms per ms (slope of offset vs elapsed time).
    """
    if len(offsets_ms) < 2:
        return 0.0
    coeffs = np.polyfit(elapsed_times_ms, offsets_ms, 1)
    return coeffs[0]  # drift rate (ms/ms)


def correct_timestamps(
    df: pd.DataFrame,
    offset_ms: float,
    drift: float = 0.0,
    elapsed_column: str = "elapsed_ms",
) -> pd.DataFrame:
    """Apply offset and linear drift correction to timestamps.

    corrected_time = raw_time + offset + drift * elapsed_time
    """
    df = df.copy()
    if "epoch_ms" in df.columns:
        df["epoch_ms"] = (
            df["epoch_ms"].astype(np.int64)
            + int(offset_ms)
            + (df[elapsed_column].astype(np.float64) * drift).astype(np.int64)
        )
    return df


def compute_sync_error_ms(sync_events: pd.DataFrame) -> float:
    """Compute the maximum synchronization error from sync event timestamps.

    sync_events should have columns: phone_time_ms, watch_time_ms, ecg_time_ms
    Returns max absolute pairwise difference in ms.
    """
    errors = []
    cols = [c for c in ["phone_time_ms", "watch_time_ms", "ecg_time_ms"] if c in sync_events.columns]
    for _, row in sync_events.iterrows():
        values = [row[c] for c in cols if pd.notna(row[c])]
        if len(values) >= 2:
            errors.append(max(values) - min(values))
    return float(max(errors)) if errors else float("inf")


def synchronize_session(
    imu_df: pd.DataFrame,
    hr_df: pd.DataFrame,
    ecg_df: pd.DataFrame,
    sync_events: pd.DataFrame,
) -> dict:
    """Full synchronization pipeline for a session.

    Returns dict with corrected dataframes and sync report.
    """
    # Step 1: Estimate initial offset from sync events
    if len(sync_events) >= 2 and "watch_time_ms" in sync_events.columns and "ecg_time_ms" in sync_events.columns:
        offset_ms = float(sync_events["watch_time_ms"].iloc[0] - sync_events["ecg_time_ms"].iloc[0])
    else:
        offset_ms = 0.0

    # Step 2: Estimate drift if we have start and end sync events
    drift = 0.0
    if len(sync_events) >= 2:
        offsets = [
            float(sync_events["watch_time_ms"].iloc[0] - sync_events["ecg_time_ms"].iloc[0]),
            float(sync_events["watch_time_ms"].iloc[-1] - sync_events["ecg_time_ms"].iloc[-1]),
        ]
        elapsed = [0.0, float(sync_events["watch_time_ms"].iloc[-1] - sync_events["watch_time_ms"].iloc[0])]
        drift = estimate_linear_drift(offsets, elapsed)

    # Step 3: Cross-correlation refinement using IMU vs ECG motion
    if len(imu_df) > 0 and len(ecg_df) > 0:
        # Use acceleration magnitude as proxy
        imu_mag = np.sqrt(imu_df["ax"]**2 + imu_df["ay"]**2 + imu_df["az"]**2).values
        # ECG may have motion artifact spikes; use HR changes as proxy
        if "hr_bpm" in ecg_df.columns:
            ref_signal = ecg_df["hr_bpm"].values
            # Resample to comparable rates for cross-correlation
            sample_rate = 50.0  # typical IMU sample rate
            cc_offset = estimate_offset_cross_correlation(imu_mag[:len(ref_signal)], ref_signal, sample_rate)
            offset_ms = (offset_ms + cc_offset) / 2  # average

    # Step 4: Apply corrections
    corrected_imu = correct_timestamps(imu_df, offset_ms, drift)
    corrected_hr = correct_timestamps(hr_df, offset_ms, drift)
    corrected_ecg = ecg_df  # ECG is reference, no correction needed

    # Step 5: Compute sync error
    sync_error = compute_sync_error_ms(sync_events)

    report = {
        "offset_ms": offset_ms,
        "drift_rate": drift,
        "sync_error_ms": sync_error,
        "acceptable": sync_error < 250.0,
    }

    return {
        "imu": corrected_imu,
        "hr": corrected_hr,
        "ecg": corrected_ecg,
        "report": report,
    }
