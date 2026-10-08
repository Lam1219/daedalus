"""
Haptic engine test analysis module.

POC Section 17: Haptic Engine Test Plan.
Analyzes haptic latency, jitter, and pattern delivery performance.
"""

import numpy as np
import pandas as pd
from dataclasses import dataclass
from typing import Optional

from .quality_rules import (
    HAPTIC_LATENCY_TARGET_MS, HAPTIC_JITTER_TARGET_MS,
    HAPTIC_MISSED_PULSE_PCT,
    HAPTIC_GAIT_PULSE_DURATION_MS,
    HAPTIC_BREATHING_INHALE_SEC, HAPTIC_BREATHING_EXHALE_SEC,
    HAPTIC_TREMOR_PULSE_FREQ_HZ, HAPTIC_TREMOR_PULSE_DURATION_MS,
)


@dataclass
class HapticPattern:
    """Describes a haptic pattern configuration."""
    name: str
    pulse_interval_ms: float
    pulse_duration_ms: float
    intensity: str = "medium"


def gait_metronome_pattern(cadence_steps_per_min: float) -> HapticPattern:
    """Create a gait metronome haptic pattern.

    Pulse interval = 60 / target_cadence
    Pulse duration = 20 ms
    """
    interval_ms = 60.0 / cadence_steps_per_min * 1000
    return HapticPattern(
        name="gait_metronome",
        pulse_interval_ms=interval_ms,
        pulse_duration_ms=HAPTIC_GAIT_PULSE_DURATION_MS,
        intensity="medium",
    )


def breathing_pacing_pattern() -> HapticPattern:
    """Create a breathing pacing haptic pattern.

    Inhale: 4 sec, Exhale: 6 sec (6 breaths/min).
    """
    total_cycle_ms = (HAPTIC_BREATHING_INHALE_SEC + HAPTIC_BREATHING_EXHALE_SEC) * 1000
    return HapticPattern(
        name="breathing_pacing",
        pulse_interval_ms=total_cycle_ms,
        pulse_duration_ms=HAPTIC_BREATHING_INHALE_SEC * 1000,  # Inhale cue is longer
        intensity="medium",
    )


def tremor_grounding_pattern() -> HapticPattern:
    """Create a tremor grounding haptic pattern (exploratory).

    Frequency: 2-3 Hz, Duration: 10-20 ms, Intensity: gentle.
    """
    interval_ms = 1000.0 / HAPTIC_TREMOR_PULSE_FREQ_HZ
    return HapticPattern(
        name="tremor_grounding",
        pulse_interval_ms=interval_ms,
        pulse_duration_ms=HAPTIC_TREMOR_PULSE_DURATION_MS,
        intensity="gentle",
    )


def compute_haptic_latency(
    trigger_timestamps_ms: np.ndarray,
    onset_timestamps_ms: np.ndarray,
) -> np.ndarray:
    """Compute trigger-to-onset latency for each haptic event.

    Returns array of latency values in ms. Arrays must be same length;
    if not, truncates to the shorter array.
    """
    min_len = min(len(trigger_timestamps_ms), len(onset_timestamps_ms))
    return onset_timestamps_ms[:min_len] - trigger_timestamps_ms[:min_len]


def compute_haptic_performance(
    trigger_timestamps_ms: np.ndarray,
    onset_timestamps_ms: np.ndarray,
    expected_count: Optional[int] = None,
) -> dict:
    """Compute haptic performance metrics.

    Metrics:
    - mean_latency_ms
    - median_latency_ms
    - p95_latency_ms
    - jitter_ms (std of latency)
    - missed_pulse_pct
    - latency_acceptable (bool)
    - jitter_acceptable (bool)
    """
    latencies = compute_haptic_latency(trigger_timestamps_ms, onset_timestamps_ms)

    if len(latencies) == 0:
        return {
            "mean_latency_ms": np.nan, "median_latency_ms": np.nan,
            "p95_latency_ms": np.nan, "jitter_ms": np.nan,
            "missed_pulse_pct": np.nan, "latency_acceptable": False,
            "jitter_acceptable": False,
        }

    jitter = float(np.std(latencies))
    missed_pct = 0.0
    if expected_count is not None and expected_count > 0:
        missed_pct = (expected_count - len(latencies)) / expected_count * 100

    return {
        "mean_latency_ms": float(np.mean(latencies)),
        "median_latency_ms": float(np.median(latencies)),
        "p95_latency_ms": float(np.percentile(latencies, 95)),
        "jitter_ms": jitter,
        "missed_pulse_pct": float(missed_pct),
        "num_delivered": len(latencies),
        "num_expected": expected_count,
        "latency_acceptable": float(np.mean(latencies)) < HAPTIC_LATENCY_TARGET_MS,
        "jitter_acceptable": jitter < HAPTIC_JITTER_TARGET_MS,
    }


def evaluate_haptic_patterns(
    latency_data: pd.DataFrame,
    subjective_ratings: Optional[np.ndarray] = None,
) -> dict:
    """Evaluate haptic pattern performance from test data.

    latency_data should have columns: trigger_ms, onset_ms, pattern_type
    """
    results = {}
    for pattern_type in latency_data["pattern_type"].unique():
        subset = latency_data[latency_data["pattern_type"] == pattern_type]
        trigger_ts = subset["trigger_ms"].values
        onset_ts = subset["onset_ms"].values
        results[pattern_type] = compute_haptic_performance(trigger_ts, onset_ts)

    if subjective_ratings is not None and len(subjective_ratings) > 0:
        results["subjective"] = {
            "mean_rating": float(np.mean(subjective_ratings)),
            "median_rating": float(np.median(subjective_ratings)),
            "usable": float(np.mean(subjective_ratings)) > 7.0,  # Target: > 7/10
        }

    return results
