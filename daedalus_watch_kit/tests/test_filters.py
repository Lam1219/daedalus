"""
Tests for signal filtering functions used across the pipeline.

Covers: bandpass filters, gravity removal, signal preprocessing.
"""

import numpy as np
import pytest

from daedalus_watch_kit.tremor import bandpass_filter
from daedalus_watch_kit.gait import remove_gravity


class TestBandpassFilter:
    """Tests for the bandpass filter function."""

    def test_bandpass_preserves_in_band_signal(self):
        """A sine wave within the passband should be preserved."""
        sample_rate = 50.0
        t = np.arange(0, 10, 1 / sample_rate)
        freq = 5.0  # Within 3-8 Hz tremor band
        signal = np.sin(2 * np.pi * freq * t)

        filtered = bandpass_filter(signal, 3.0, 8.0, sample_rate)

        # The filtered signal should retain most of its power
        original_power = np.mean(signal**2)
        filtered_power = np.mean(filtered**2)
        assert filtered_power > 0.3 * original_power

    def test_bandpass_attenuates_low_frequency(self):
        """A low-frequency signal should be attenuated by the bandpass."""
        sample_rate = 50.0
        t = np.arange(0, 10, 1 / sample_rate)
        freq = 0.5  # Below tremor band
        signal = np.sin(2 * np.pi * freq * t)

        filtered = bandpass_filter(signal, 3.0, 8.0, sample_rate)

        original_power = np.mean(signal**2)
        filtered_power = np.mean(filtered**2)
        assert filtered_power < 0.1 * original_power

    def test_bandpass_attenuates_high_frequency(self):
        """A high-frequency signal should be attenuated by the bandpass."""
        sample_rate = 50.0
        t = np.arange(0, 10, 1 / sample_rate)
        freq = 20.0  # Above tremor band
        signal = np.sin(2 * np.pi * freq * t)

        filtered = bandpass_filter(signal, 3.0, 8.0, sample_rate)

        original_power = np.mean(signal**2)
        filtered_power = np.mean(filtered**2)
        assert filtered_power < 0.1 * original_power

    def test_bandpass_output_length_matches_input(self):
        """Filtered output should have the same length as input."""
        sample_rate = 50.0
        signal = np.random.randn(500)

        filtered = bandpass_filter(signal, 3.0, 8.0, sample_rate)

        assert len(filtered) == len(signal)

    def test_bandpass_with_narrow_band(self):
        """Filter should work with narrow bands."""
        sample_rate = 100.0
        t = np.arange(0, 5, 1 / sample_rate)
        freq = 4.5  # In 4-5 Hz narrow band
        signal = np.sin(2 * np.pi * freq * t)

        filtered = bandpass_filter(signal, 4.0, 5.0, sample_rate)

        original_power = np.mean(signal**2)
        filtered_power = np.mean(filtered**2)
        assert filtered_power > 0.2 * original_power

    def test_bandpass_invalid_frequencies_clamped(self):
        """Filter should handle edge-case frequencies gracefully."""
        sample_rate = 50.0
        signal = np.random.randn(500)

        # Should not raise even with extreme frequencies
        result = bandpass_filter(signal, 0.001, 24.999, sample_rate)
        assert len(result) == len(signal)

    def test_bandpass_zero_order_raises(self):
        """Bandpass with invalid signal length should still return array."""
        sample_rate = 50.0
        signal = np.array([1.0])

        # Single sample — filtfilt needs enough data
        with pytest.raises(ValueError):
            bandpass_filter(signal, 3.0, 8.0, sample_rate)


class TestGravityRemoval:
    """Tests for gravity removal via high-pass filtering."""

    def test_removes_constant_offset(self):
        """A constant signal (simulating gravity) should be removed."""
        sample_rate = 50.0
        ax = np.ones(500) * 9.8  # Gravity on x-axis
        ay = np.zeros(500)
        az = np.zeros(500)

        ax_ac, ay_ac, az_ac = remove_gravity(ax, ay, az, sample_rate=sample_rate)

        # After gravity removal, the DC component should be near zero
        assert np.abs(np.mean(ax_ac)) < 0.5

    def test_preserves_high_frequency_motion(self):
        """High-frequency motion should be preserved after gravity removal."""
        sample_rate = 50.0
        t = np.arange(0, 10, 1 / sample_rate)
        # 5 Hz oscillation on top of gravity
        ax = np.sin(2 * np.pi * 5 * t) + 9.8
        ay = np.zeros_like(t)
        az = np.zeros_like(t)

        ax_ac, _, _ = remove_gravity(ax, ay, az, sample_rate=sample_rate)

        # The oscillation should still be present
        assert np.std(ax_ac) > 0.1

    def test_output_shapes_match_input(self):
        """Output arrays should match input shapes."""
        sample_rate = 50.0
        n = 1000
        ax = np.random.randn(n)
        ay = np.random.randn(n)
        az = np.random.randn(n)

        ax_ac, ay_ac, az_ac = remove_gravity(ax, ay, az, sample_rate=sample_rate)

        assert len(ax_ac) == n
        assert len(ay_ac) == n
        assert len(az_ac) == n
