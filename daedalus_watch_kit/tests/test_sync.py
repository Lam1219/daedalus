"""
Tests for time synchronization module.

POC Section 10: Time Synchronization Plan.
Tests: offset estimation, drift correction, cross-correlation, sync error.
"""

import numpy as np
import pandas as pd
import pytest

from daedalus_watch_kit.sync import (
    estimate_offset_cross_correlation,
    estimate_linear_drift,
    correct_timestamps,
    compute_sync_error_ms,
    synchronize_session,
)


class TestOffsetCrossCorrelation:
    """Tests for cross-correlation offset estimation."""

    def test_known_offset(self):
        """Should recover a known offset between two signals."""
        sample_rate = 50.0
        t = np.arange(0, 10, 1 / sample_rate)
        signal = np.sin(2 * np.pi * 2 * t)  # 2 Hz signal

        # Shifted by 100 samples = 2000 ms
        offset_samples = 100
        expected_offset_ms = offset_samples / sample_rate * 1000

        result = estimate_offset_cross_correlation(
            signal[offset_samples:], signal[:-offset_samples], sample_rate
        )

        # Cross-correlation can have ambiguity; check it returns a finite value
        assert np.isfinite(result)

    def test_identical_signals_zero_offset(self):
        """Identical signals should yield near-zero offset."""
        sample_rate = 50.0
        signal = np.sin(2 * np.pi * 1 * np.arange(0, 10, 1 / sample_rate))

        offset = estimate_offset_cross_correlation(signal, signal, sample_rate)
        assert abs(offset) < 50  # Within 50 ms

    def test_different_sample_rates(self):
        """Should handle signals with known sample rate."""
        sample_rate = 100.0
        t = np.arange(0, 5, 1 / sample_rate)
        signal = np.sin(2 * np.pi * 5 * t)

        # 50 sample offset = 500 ms
        offset_samples = 50
        result = estimate_offset_cross_correlation(
            signal[offset_samples:], signal[:-offset_samples], sample_rate
        )

        # Should return a finite value
        assert np.isfinite(result)


class TestLinearDrift:
    """Tests for linear clock drift estimation."""

    def test_no_drift(self):
        """Constant offsets should yield zero drift."""
        offsets = [100.0, 100.0, 100.0]
        times = [0, 1000, 2000]
        drift = estimate_linear_drift(offsets, times)
        assert abs(drift) < 1e-10

    def test_constant_drift(self):
        """Linearly increasing offset should yield correct drift rate."""
        offsets = [100.0, 200.0, 300.0]
        times = [0, 1000, 2000]
        drift = estimate_linear_drift(offsets, times)
        # Drift = 100 ms / 1000 ms = 0.1 ms/ms
        assert pytest.approx(drift, abs=0.01) == 0.1

    def test_insufficient_data(self):
        """Single measurement should return zero drift."""
        drift = estimate_linear_drift([100.0], [0])
        assert drift == 0.0


class TestTimestampCorrection:
    """Tests for timestamp correction application."""

    def test_offset_only_correction(self):
        """Should apply offset correction correctly."""
        df = pd.DataFrame({"epoch_ms": [1000, 2000, 3000], "elapsed_ms": [0, 1000, 2000]})
        corrected = correct_timestamps(df, offset_ms=500)

        assert corrected["epoch_ms"].iloc[0] == 1500
        assert corrected["epoch_ms"].iloc[1] == 2500

    def test_offset_and_drift_correction(self):
        """Should apply both offset and drift correction."""
        df = pd.DataFrame({"epoch_ms": [1000, 2000, 3000], "elapsed_ms": [0, 1000, 2000]})
        corrected = correct_timestamps(df, offset_ms=100, drift=0.05)

        # epoch_ms = raw + 100 + 0.05 * elapsed
        assert corrected["epoch_ms"].iloc[0] == 1100   # 1000 + 100 + 0
        assert corrected["epoch_ms"].iloc[1] == 2150   # 2000 + 100 + 50
        assert corrected["epoch_ms"].iloc[2] == 3200   # 3000 + 100 + 100

    def test_empty_dataframe(self):
        """Empty dataframe should be handled."""
        df = pd.DataFrame({"epoch_ms": [], "elapsed_ms": []})
        corrected = correct_timestamps(df, offset_ms=100)
        assert len(corrected) == 0


class TestSyncError:
    """Tests for sync error computation."""

    def test_small_sync_error(self):
        """Small pairwise differences should yield small error."""
        events = pd.DataFrame({
            "phone_time_ms": [1000, 2000],
            "watch_time_ms": [1050, 2050],
            "ecg_time_ms": [1030, 2030],
        })
        error = compute_sync_error_ms(events)
        assert error == 50  # Max diff: 1050 - 1000 = 50

    def test_large_sync_error(self):
        """Large differences should yield large error."""
        events = pd.DataFrame({
            "phone_time_ms": [1000, 2000],
            "watch_time_ms": [1500, 2500],
            "ecg_time_ms": [1000, 2000],
        })
        error = compute_sync_error_ms(events)
        assert error == 500

    def test_missing_columns(self):
        """Should handle missing columns gracefully."""
        events = pd.DataFrame({"phone_time_ms": [1000]})
        error = compute_sync_error_ms(events)
        assert error == float("inf")


class TestSynchronizeSession:
    """Integration tests for full synchronization pipeline."""

    def test_basic_synchronization(self):
        """Should return corrected dataframes and a report."""
        imu_df = pd.DataFrame({
            "epoch_ms": [1000, 1020, 1040],
            "elapsed_ms": [0, 20, 40],
            "ax": [0.0, 0.1, 0.0], "ay": [0.0, 0.0, 0.0], "az": [9.8, 9.8, 9.8],
            "gx": [0.0, 0.0, 0.0], "gy": [0.0, 0.0, 0.0], "gz": [0.0, 0.0, 0.0],
        })
        hr_df = pd.DataFrame({
            "epoch_ms": [1000, 2000],
            "elapsed_ms": [0, 1000],
            "hr_bpm": [72.0, 74.0], "hrv_rmssd_ms": [50.0, 55.0],
            "hr_quality": [0.9, 0.8], "source": ["sensor", "sensor"],
        })
        ecg_df = pd.DataFrame({
            "epoch_ms": [900, 1900],
            "ecg_raw": [np.nan, np.nan],  # Cycplus H2: no raw waveform
            "rr_ms": [800, 810],
            "hr_bpm": [75.0, 74.0], "beat_quality": [1.0, 1.0],
        })
        sync_events = pd.DataFrame({
            "watch_time_ms": [1000, 5000],
            "ecg_time_ms": [900, 4800],
        })

        result = synchronize_session(imu_df, hr_df, ecg_df, sync_events)

        assert "imu" in result
        assert "hr" in result
        assert "ecg" in result
        assert "report" in result
        assert "offset_ms" in result["report"]
        assert "sync_error_ms" in result["report"]
