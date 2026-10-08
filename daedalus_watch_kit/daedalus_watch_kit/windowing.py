"""
Windowing module — creates fixed-length analysis windows from synced time series.

Stage 3 of the core processing pipeline (POC Section 12, Stage 3).
"""

import pandas as pd
import numpy as np
from dataclasses import dataclass
from typing import Optional

from .event_labels import ALL_EVENTS


@dataclass
class WindowConfig:
    """Configuration for a windowing scheme."""
    purpose: str
    window_length_sec: float
    overlap_pct: float = 50.0

    @property
    def window_length_ms(self) -> int:
        return int(self.window_length_sec * 1000)

    @property
    def step_ms(self) -> int:
        return int(self.window_length_ms * (1 - self.overlap_pct / 100.0))


# Standard window configs from POC
WINDOW_CONFIGS = {
    "signal_quality": WindowConfig("signal_quality", window_length_sec=10, overlap_pct=50),
    "hrv_dynamic": WindowConfig("hrv_dynamic", window_length_sec=60, overlap_pct=50),
    "hrv_standard": WindowConfig("hrv_standard", window_length_sec=300, overlap_pct=0),
    "tremor": WindowConfig("tremor", window_length_sec=2, overlap_pct=50),
    "gait": WindowConfig("gait", window_length_sec=4, overlap_pct=50),
    "stress": WindowConfig("stress", window_length_sec=30, overlap_pct=50),
}


def create_windows(
    df: pd.DataFrame,
    config: WindowConfig,
    epoch_column: str = "epoch_ms",
    subject_id: str = "",
    session_id: str = "",
    event_labels: Optional[pd.DataFrame] = None,
) -> pd.DataFrame:
    """Create fixed-length overlapping windows from a time series.

    Each window includes: subject_id, session_id, window_start_ms, window_end_ms,
    activity_label, and the indices of samples within the window.
    """
    if df.empty or epoch_column not in df.columns:
        return pd.DataFrame()

    timestamps = df[epoch_column].values.astype(np.int64)
    t_start = timestamps[0]
    t_end = timestamps[-1]
    step = config.step_ms
    length = config.window_length_ms

    windows = []
    current = t_start
    while current + length <= t_end:
        window_end = current + length
        # Find sample indices in this window
        mask = (timestamps >= current) & (timestamps < window_end)
        indices = np.where(mask)[0]

        if len(indices) == 0:
            current += step
            continue

        # Determine activity label from event labels if provided
        activity_label = _determine_activity_label(current, window_end, event_labels)

        windows.append({
            "subject_id": subject_id,
            "session_id": session_id,
            "window_start_ms": int(current),
            "window_end_ms": int(window_end),
            "activity_label": activity_label,
            "sample_count": len(indices),
            "sample_indices": list(indices),
        })
        current += step

    return pd.DataFrame(windows)


def _determine_activity_label(
    window_start: int,
    window_end: int,
    event_labels: Optional[pd.DataFrame],
) -> str:
    """Determine the dominant activity label for a window from event labels."""
    if event_labels is None or event_labels.empty:
        return "unknown"

    # Find events that fall within this window
    mask = (event_labels["epoch_ms"] >= window_start) & (event_labels["epoch_ms"] < window_end)
    window_events = event_labels.loc[mask, "event_name"].tolist()

    if not window_events:
        return "unknown"

    # Return the most recent state-starting event
    for event in reversed(window_events):
        if event.endswith("_START") and event in ALL_EVENTS:
            return event.replace("_START", "")

    return "unknown"


def create_all_windows(
    synced_df: pd.DataFrame,
    subject_id: str = "",
    session_id: str = "",
    event_labels: Optional[pd.DataFrame] = None,
) -> dict:
    """Create windows for all standard analysis purposes.

    Returns dict mapping window purpose -> windows DataFrame.
    """
    result = {}
    for purpose, config in WINDOW_CONFIGS.items():
        result[purpose] = create_windows(
            synced_df, config,
            subject_id=subject_id,
            session_id=session_id,
            event_labels=event_labels,
        )
    return result
