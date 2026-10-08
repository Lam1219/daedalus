"""
Tests for tremor detection module.

POC Section 15: Tremor Detection Plan.
Tests: tremor features, rule-based classification, probability output.
"""

import numpy as np
import pytest

from daedalus_watch_kit.tremor import (
    bandpass_filter,
    compute_acceleration_magnitude,
    compute_angular_velocity_magnitude,
    compute_tremor_features,
    classify_tremor_rule_based,
    classify_tremor_with_probability,
)
from daedalus_watch_kit.quality_rules import TREMOR_BAND_LOW_HZ, TREMOR_BAND_HIGH_HZ


class TestSignalMagnitudes:
    """Tests for magnitude computation utilities."""

    def test_acceleration_magnitude(self):
        """Magnitude should be sqrt(ax^2 + ay^2 + az^2)."""
        ax = np.array([3.0, 0.0])
        ay = np.array([4.0, 0.0])
        az = np.array([0.0, 5.0])
        mag = compute_acceleration_magnitude(ax, ay, az)
        np.testing.assert_array_almost_equal(mag, [5.0, 5.0])

    def test_angular_velocity_magnitude(self):
        """Magnitude should be sqrt(gx^2 + gy^2 + gz^2)."""
        gx = np.array([1.0])
        gy = np.array([1.0])
        gz = np.array([1.0])
        mag = compute_angular_velocity_magnitude(gx, gy, gz)
        np.testing.assert_array_almost_equal(mag, [np.sqrt(3)])


class TestTremorFeatures:
    """Tests for tremor feature computation."""

    def test_tremor_like_signal_detected(self):
        """A sine wave in the tremor band should produce tremor-like features."""
        sample_rate = 50.0
        t = np.arange(0, 4, 1 / sample_rate)  # 4 seconds
        freq = 5.0  # In tremor band

        ax = np.sin(2 * np.pi * freq * t) * 0.5
        ay = np.zeros_like(t)
        az = np.zeros_like(t)
        gx = np.zeros_like(t)
        gy = np.zeros_like(t)
        gz = np.zeros_like(t)

        features = compute_tremor_features(ax, ay, az, gx, gy, gz, sample_rate)

        # Dominant frequency should be near the input frequency
        assert TREMOR_BAND_LOW_HZ <= features["dominant_freq"] <= TREMOR_BAND_HIGH_HZ
        assert features["band_power_3_8hz"] > 0

    def test_low_frequency_not_tremor(self):
        """Low-frequency movement should not produce tremor features."""
        sample_rate = 50.0
        t = np.arange(0, 4, 1 / sample_rate)
        freq = 0.5  # Below tremor band

        ax = np.sin(2 * np.pi * freq * t)
        ay = np.zeros_like(t)
        az = np.zeros_like(t)
        gx = np.zeros_like(t)
        gy = np.zeros_like(t)
        gz = np.zeros_like(t)

        features = compute_tremor_features(ax, ay, az, gx, gy, gz, sample_rate)

        # Dominant frequency should be outside tremor band or have low power
        if features["dominant_freq"] > 0:
            assert features["band_power_3_8hz"] < 0.1

    def test_all_features_present(self):
        """All expected tremor features should be computed."""
        sample_rate = 50.0
        n = 200
        ax, ay, az = np.random.randn(3, n) * 0.1
        gx, gy, gz = np.random.randn(3, n) * 0.01

        features = compute_tremor_features(ax, ay, az, gx, gy, gz, sample_rate)

        expected = {
            "band_power_3_8hz", "dominant_freq", "spectral_entropy",
            "peak_prominence", "rms_accel", "rms_gyro", "jerk",
        }
        assert set(features.keys()) == expected

    def test_short_window_handles_gracefully(self):
        """Very short windows should still return all features."""
        # Need at least 30 samples for the filter to work (padlen requirement)
        n = 30
        ax = np.random.randn(n) * 0.1
        ay = np.random.randn(n) * 0.1
        az = np.random.randn(n) * 0.1
        gx = np.zeros(n)
        gy = np.zeros(n)
        gz = np.zeros(n)

        features = compute_tremor_features(ax, ay, az, gx, gy, gz, sample_rate=50.0)
        assert "band_power_3_8hz" in features


class TestTremorClassification:
    """Tests for tremor classification functions."""

    def test_tremor_candidate_detected(self):
        """Features meeting all criteria should be classified as tremor."""
        features = {
            "band_power_3_8hz": 0.5,
            "dominant_freq": 5.0,
            "spectral_entropy": 1.5,
            "rms_accel": 0.3,
            "rms_gyro": 0.05,
            "jerk": 0.1,
            "peak_prominence": 5.0,
        }
        result = classify_tremor_rule_based(features)
        assert result is True

    def test_non_tremor_not_detected(self):
        """Low power signal should not be classified as tremor."""
        features = {
            "band_power_3_8hz": 0.001,
            "dominant_freq": 0.5,
            "spectral_entropy": 5.0,
            "rms_accel": 0.01,
            "rms_gyro": 0.001,
            "jerk": 0.001,
            "peak_prominence": 0.5,
        }
        result = classify_tremor_rule_based(features)
        assert result is False

    def test_probability_output(self):
        """Probability output should return all expected fields."""
        features = {
            "band_power_3_8hz": 0.3,
            "dominant_freq": 5.0,
            "spectral_entropy": 2.0,
            "rms_accel": 0.2,
            "rms_gyro": 0.03,
            "jerk": 0.05,
            "peak_prominence": 3.0,
        }
        result = classify_tremor_with_probability(features)
        assert "tremor_probability" in result
        assert "tremor_frequency_hz" in result
        assert "tremor_amplitude_score" in result
        assert "is_tremor_candidate" in result
        assert 0 <= result["tremor_probability"] <= 1

    def test_frequency_in_tremor_band(self):
        """Dominant frequency should be reported in output."""
        features = {
            "band_power_3_8hz": 0.5,
            "dominant_freq": 4.5,
            "spectral_entropy": 1.0,
            "rms_accel": 0.3,
            "rms_gyro": 0.05,
            "jerk": 0.1,
            "peak_prominence": 4.0,
        }
        result = classify_tremor_with_probability(features)
        assert result["tremor_frequency_hz"] == 4.5
