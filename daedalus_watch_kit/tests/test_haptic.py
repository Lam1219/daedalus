"""
Tests for haptic engine test analysis.

POC Section 17: Haptic Engine Test Plan.
Tests: pattern generation, latency computation, performance evaluation.
"""

import numpy as np
import pandas as pd
import pytest

from daedalus_watch_kit.haptic import (
    HapticPattern,
    gait_metronome_pattern,
    breathing_pacing_pattern,
    tremor_grounding_pattern,
    compute_haptic_latency,
    compute_haptic_performance,
    evaluate_haptic_patterns,
)
from daedalus_watch_kit.quality_rules import (
    HAPTIC_LATENCY_TARGET_MS, HAPTIC_JITTER_TARGET_MS,
)


class TestHapticPatterns:
    """Tests for haptic pattern generation."""

    def test_gait_metronome_interval(self):
        """Interval should be 60/cadence * 1000 ms."""
        pattern = gait_metronome_pattern(cadence_steps_per_min=100)
        assert pattern.pulse_interval_ms == 600.0  # 60/100 * 1000
        assert pattern.pulse_duration_ms == 20
        assert pattern.intensity == "medium"

    def test_gait_metronome_fast_cadence(self):
        """Fast cadence should yield shorter interval."""
        pattern = gait_metronome_pattern(cadence_steps_per_min=120)
        assert pattern.pulse_interval_ms == 500.0

    def test_breathing_pacing_cycle(self):
        """Breathing pattern should be 10 seconds (4+6)."""
        pattern = breathing_pacing_pattern()
        # Inhale + Exhale = 4 + 6 = 10 seconds = 10000 ms
        assert pattern.pulse_interval_ms == 10000.0
        assert pattern.pulse_duration_ms == 4000  # Inhale is longer

    def test_tremor_grounding_pattern(self):
        """Tremor pattern should be 2-3 Hz with gentle intensity."""
        pattern = tremor_grounding_pattern()
        # At 2.5 Hz, interval = 400 ms
        assert 333 <= pattern.pulse_interval_ms <= 500  # 2-3 Hz range
        assert 10 <= pattern.pulse_duration_ms <= 20
        assert pattern.intensity == "gentle"


class TestHapticLatency:
    """Tests for latency computation."""

    def test_latency_computation(self):
        """Latency should be onset - trigger."""
        triggers = np.array([1000, 2000, 3000])
        onsets = np.array([1100, 2050, 3120])
        latencies = compute_haptic_latency(triggers, onsets)
        np.testing.assert_array_equal(latencies, [100, 50, 120])

    def test_empty_arrays(self):
        """Empty arrays should return empty result."""
        latencies = compute_haptic_latency(np.array([]), np.array([]))
        assert len(latencies) == 0


class TestHapticPerformance:
    """Tests for haptic performance metrics."""

    def test_acceptable_performance(self):
        """Performance within targets should be marked acceptable."""
        triggers = np.array([1000, 2000, 3000, 4000, 5000])
        onsets = np.array([1080, 2070, 3090, 4085, 5075])  # ~80 ms latency, ~7 ms jitter

        result = compute_haptic_performance(triggers, onsets)

        assert result["latency_acceptable"] is True
        assert result["jitter_acceptable"] is True
        assert result["mean_latency_ms"] < HAPTIC_LATENCY_TARGET_MS

    def test_unacceptable_latency(self):
        """High latency should be marked unacceptable."""
        triggers = np.array([1000, 2000, 3000])
        onsets = np.array([1200, 2250, 3300])  # ~250 ms latency

        result = compute_haptic_performance(triggers, onsets)

        assert result["latency_acceptable"] is False

    def test_high_jitter(self):
        """High jitter should be marked unacceptable."""
        triggers = np.array([1000, 2000, 3000, 4000, 5000])
        onsets = np.array([1050, 2200, 3030, 4300, 5040])  # Variable latency

        result = compute_haptic_performance(triggers, onsets)

        assert result["jitter_acceptable"] is False

    def test_missed_pulses(self):
        """Missed pulses should be tracked."""
        triggers = np.array([1000, 2000, 3000])
        onsets = np.array([1050, 3050])  # One pulse missed

        result = compute_haptic_performance(triggers, onsets, expected_count=3)

        assert result["missed_pulse_pct"] > 0
        assert result["num_delivered"] == 2
        assert result["num_expected"] == 3

    def test_empty_performance(self):
        """Empty data should return NaN metrics."""
        result = compute_haptic_performance(np.array([]), np.array([]))
        assert np.isnan(result["mean_latency_ms"])

    def test_all_metrics_present(self):
        """All expected metric keys should be present."""
        triggers = np.array([1000, 2000])
        onsets = np.array([1050, 2060])
        result = compute_haptic_performance(triggers, onsets)
        expected = {
            "mean_latency_ms", "median_latency_ms", "p95_latency_ms",
            "jitter_ms", "missed_pulse_pct", "num_delivered", "num_expected",
            "latency_acceptable", "jitter_acceptable",
        }
        assert set(result.keys()) == expected


class TestEvaluateHapticPatterns:
    """Tests for multi-pattern haptic evaluation."""

    def test_single_pattern_evaluation(self):
        """Should evaluate a single pattern type."""
        data = pd.DataFrame({
            "trigger_ms": [1000, 2000, 3000],
            "onset_ms": [1050, 2060, 3070],
            "pattern_type": ["gait_metronome"] * 3,
        })
        result = evaluate_haptic_patterns(data)
        assert "gait_metronome" in result
        assert result["gait_metronome"]["mean_latency_ms"] > 0

    def test_multiple_pattern_evaluation(self):
        """Should evaluate multiple pattern types."""
        data = pd.DataFrame({
            "trigger_ms": [1000, 2000, 3000, 4000],
            "onset_ms": [1050, 2060, 3040, 4080],
            "pattern_type": ["gait_metronome", "gait_metronome", "breathing_pacing", "breathing_pacing"],
        })
        result = evaluate_haptic_patterns(data)
        assert "gait_metronome" in result
        assert "breathing_pacing" in result

    def test_subjective_ratings(self):
        """Subjective ratings should be included when provided."""
        data = pd.DataFrame({
            "trigger_ms": [1000, 2000],
            "onset_ms": [1050, 2060],
            "pattern_type": ["gait_metronome", "gait_metronome"],
        })
        ratings = np.array([8.0, 9.0])
        result = evaluate_haptic_patterns(data, subjective_ratings=ratings)
        assert "subjective" in result
        assert result["subjective"]["usable"] is True  # Mean > 7

    def test_subjective_not_usable(self):
        """Low subjective ratings should be marked not usable."""
        data = pd.DataFrame({
            "trigger_ms": [1000, 2000],
            "onset_ms": [1050, 2060],
            "pattern_type": ["gait_metronome", "gait_metronome"],
        })
        ratings = np.array([4.0, 5.0])
        result = evaluate_haptic_patterns(data, subjective_ratings=ratings)
        assert result["subjective"]["usable"] is False  # Mean < 7
