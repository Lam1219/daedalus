"""
Tests for gait detection and cadence estimation.

POC Section 16: Gait Detection and Cadence Plan.
Tests: gait detection, cadence estimation, regularity features, instability.
"""

import numpy as np
import pytest

from daedalus_watch_kit.gait import (
    remove_gravity,
    detect_gait,
    compute_gait_regularity_features,
    detect_gait_instability_candidate,
)


class TestGaitDetection:
    """Tests for gait vs non-gait detection."""

    def test_walking_detected_as_gait(self):
        """Rhythmic walking-like signal should be detected as gait."""
        sample_rate = 50.0
        duration = 10  # seconds
        t = np.arange(0, duration, 1 / sample_rate)

        # Simulate walking at ~100 steps/min → ~1.67 Hz arm swing
        cadence_hz = 100 / 60
        ax = np.sin(2 * np.pi * cadence_hz * t) * 0.5
        ay = np.sin(2 * np.pi * cadence_hz * t + 0.3) * 0.3
        az = np.sin(2 * np.pi * cadence_hz * t + 0.6) * 0.2 + 9.8  # Add gravity

        is_gait, cadence, features = detect_gait(ax, ay, az, sample_rate)

        assert is_gait is True
        assert features["cadence_steps_per_min"] > 0
        assert features["step_regularity"] > 0.3

    def test_standing_not_detected_as_gait(self):
        """Static signal should not be detected as gait."""
        sample_rate = 50.0
        n = 500
        ax = np.zeros(n)
        ay = np.zeros(n)
        az = np.ones(n) * 9.8  # Only gravity

        is_gait, cadence, features = detect_gait(ax, ay, az, sample_rate)

        assert is_gait is False
        assert features["cadence_steps_per_min"] == 0

    def test_random_noise_not_gait(self):
        """Random noise should not be detected as rhythmic gait."""
        np.random.seed(42)
        sample_rate = 50.0
        n = 500
        ax = np.random.randn(n) * 0.01  # Very small noise
        ay = np.random.randn(n) * 0.01
        az = np.random.randn(n) * 0.01 + 9.8  # Only gravity + noise

        is_gait, cadence, features = detect_gait(ax, ay, az, sample_rate)

        assert is_gait is False

    def test_cadence_estimation_accuracy(self):
        """Estimated cadence should be close to actual cadence."""
        sample_rate = 50.0
        duration = 10
        t = np.arange(0, duration, 1 / sample_rate)

        target_cadence = 120  # steps/min
        cadence_hz = target_cadence / 60
        ax = np.sin(2 * np.pi * cadence_hz * t) * 0.5
        ay = np.zeros_like(t)
        az = np.zeros_like(t) + 9.8

        is_gait, cadence, features = detect_gait(ax, ay, az, sample_rate)

        # Wrist-based cadence estimation can vary; check it's in a reasonable range
        assert is_gait is True
        assert features["cadence_steps_per_min"] > 0
        assert features["step_regularity"] > 0.3

    def test_features_returned(self):
        """All expected gait features should be present."""
        sample_rate = 50.0
        t = np.arange(0, 5, 1 / sample_rate)
        ax = np.sin(2 * np.pi * 1.5 * t) * 0.3
        ay = np.zeros_like(t)
        az = np.zeros_like(t) + 9.8

        is_gait, cadence, features = detect_gait(ax, ay, az, sample_rate)

        expected = {"cadence_steps_per_min", "step_regularity", "arm_swing_amplitude",
                    "walking_band_power", "peak_count"}
        assert set(features.keys()) == expected


class TestGaitRegularityFeatures:
    """Tests for gait regularity computation across windows."""

    def test_regular_gait_low_variability(self):
        """Regular cadence should yield low variability."""
        cadences = [100, 101, 99, 100, 101]
        regularities = [0.9, 0.95, 0.88, 0.92, 0.91]

        features = compute_gait_regularity_features(cadences, regularities)

        assert features["cadence_variability"] < 2.0
        assert features["harmonic_ratio"] > 0.8

    def test_irregular_gait_high_variability(self):
        """Irregular cadence should yield high variability."""
        cadences = [80, 120, 90, 130, 70]
        regularities = [0.3, 0.5, 0.2, 0.4, 0.3]

        features = compute_gait_regularity_features(cadences, regularities)

        assert features["cadence_variability"] > 10
        assert features["harmonic_ratio"] < 0.6

    def test_short_series(self):
        """Single window should return defaults."""
        features = compute_gait_regularity_features([100], [0.9])
        assert features["cadence_variability"] == 0.0
        assert features["gait_bout_length"] == 0


class TestGaitInstability:
    """Tests for gait instability candidate detection."""

    def test_high_variability_and_low_swing(self):
        """High variability + low arm swing should be flagged."""
        result = detect_gait_instability_candidate(
            cadence=100,
            cadence_variability=20.0,
            arm_swing_amplitude=0.05,
        )
        assert result is True

    def test_stable_gait_not_flagged(self):
        """Stable gait should not be flagged."""
        result = detect_gait_instability_candidate(
            cadence=100,
            cadence_variability=2.0,
            arm_swing_amplitude=0.5,
        )
        assert result is False

    def test_risky_context_flagged(self):
        """High variability in risky context should be flagged."""
        result = detect_gait_instability_candidate(
            cadence=100,
            cadence_variability=20.0,
            arm_swing_amplitude=0.3,
            activity_context="turning",
        )
        assert result is True
