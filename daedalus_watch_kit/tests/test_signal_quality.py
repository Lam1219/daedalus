"""
Tests for signal quality scoring.

POC Section 12, Stage 4: Signal Quality Scoring.
Tests: IMU SQI, HR SQI, PPG SQI, bad segment classification.
"""

import numpy as np
import pandas as pd
import pytest

from daedalus_watch_kit.signal_quality import (
    compute_imu_quality,
    compute_hr_quality_from_hr,
    compute_hr_quality_from_ppg,
    classify_bad_segments,
    signal_quality_report,
)
from daedalus_watch_kit.quality_rules import SQI_GOOD, SQI_UNCERTAIN


class TestIMUQuality:
    """Tests for IMU signal quality index."""

    def test_clean_signal_high_sqi(self):
        """Clean IMU data should yield high SQI."""
        df = pd.DataFrame({
            "ax": np.random.randn(100),
            "ay": np.random.randn(100),
            "az": np.random.randn(100) + 9.8,
            "gx": np.random.randn(100) * 0.01,
            "gy": np.random.randn(100) * 0.01,
            "gz": np.random.randn(100) * 0.01,
        })
        sqi = compute_imu_quality(df)
        assert sqi > 0.7

    def test_missing_data_low_sqi(self):
        """Data with many missing values should yield low SQI."""
        df = pd.DataFrame({
            "ax": [np.nan] * 60 + [1.0] * 40,
            "ay": [1.0] * 100,
            "az": [1.0] * 100,
            "gx": [1.0] * 100,
            "gy": [1.0] * 100,
            "gz": [1.0] * 100,
        })
        sqi = compute_imu_quality(df)
        assert sqi < 0.8

    def test_empty_dataframe_zero_sqi(self):
        """Empty dataframe should yield SQI of 0."""
        df = pd.DataFrame()
        sqi = compute_imu_quality(df)
        assert sqi == 0.0

    def test_clipping_reduces_sqi(self):
        """Clipped/unrealistic values should reduce SQI."""
        df = pd.DataFrame({
            "ax": [25.0] * 50 + [0.0] * 50,  # > 20 m/s^2
            "ay": [0.0] * 100,
            "az": [0.0] * 100,
            "gx": [0.0] * 100,
            "gy": [0.0] * 100,
            "gz": [0.0] * 100,
        })
        sqi = compute_imu_quality(df)
        assert sqi < 1.0


class TestHRQualityFromHR:
    """Tests for HR SQI from HR values alone."""

    def test_clean_hr_high_sqi(self):
        """Stable HR values should yield high SQI."""
        hr = pd.Series([72.0, 73.0, 72.5, 73.5, 72.0, 73.0])
        sqi = compute_hr_quality_from_hr(hr)
        assert sqi > 0.8

    def test_missing_values_reduce_sqi(self):
        """Many NaN values should reduce SQI."""
        hr = pd.Series([np.nan] * 60 + [72.0] * 40)
        sqi = compute_hr_quality_from_hr(hr)
        assert sqi < 0.7

    def test_implausible_values_reduce_sqi(self):
        """Implausible HR values should reduce SQI."""
        hr = pd.Series([72.0, 300.0, 73.0, 10.0, 74.0])
        sqi = compute_hr_quality_from_hr(hr)
        assert sqi < 0.8

    def test_empty_series_zero_sqi(self):
        """Empty series should yield 0."""
        hr = pd.Series([], dtype=float)
        sqi = compute_hr_quality_from_hr(hr)
        assert sqi == 0.0

    def test_motion_penalty(self):
        """High motion intensity should reduce SQI."""
        hr = pd.Series([72.0, 73.0, 72.5, 73.0, 72.0])
        motion = np.array([2.0, 2.5, 3.0, 2.0, 2.5])  # High motion
        sqi = compute_hr_quality_from_hr(hr, motion)
        assert sqi < 1.0


class TestHRQualityFromPPG:
    """Tests for HR SQI from raw PPG."""

    def test_clean_ppg_high_sqi(self):
        """Clean PPG signal should yield good SQI."""
        t = np.arange(0, 10, 1 / 50.0)
        ppg = np.sin(2 * np.pi * 1.2 * t)  # ~72 bpm signal
        sqi = compute_hr_quality_from_ppg(ppg)
        assert sqi > 0.4  # Clean sine wave has good SNR

    def test_noisy_ppg_low_sqi(self):
        """Noisy PPG should yield lower SQI."""
        ppg = np.random.randn(500)  # Pure noise
        sqi = compute_hr_quality_from_ppg(ppg)
        # Noise has low SNR
        assert sqi < 0.8

    def test_empty_signal_zero_sqi(self):
        """Empty PPG should yield 0."""
        sqi = compute_hr_quality_from_ppg(np.array([]))
        assert sqi == 0.0


class TestBadSegmentClassification:
    """Tests for bad segment classification."""

    def test_all_good_segments(self):
        """All good SQI values should not be classified as bad."""
        sqi = np.array([0.9, 0.85, 0.95])
        bad = classify_bad_segments(sqi)
        assert not bad.any()

    def test_all_bad_segments(self):
        """All bad SQI values should be classified as bad."""
        sqi = np.array([0.1, 0.3, 0.4])
        bad = classify_bad_segments(sqi)
        assert bad.all()

    def test_mixed_segments(self):
        """Mixed SQI should classify correctly."""
        sqi = np.array([0.9, 0.3, 0.85, 0.1])
        bad = classify_bad_segments(sqi)
        expected = np.array([False, True, False, True])
        np.testing.assert_array_equal(bad, expected)


class TestSignalQualityReport:
    """Tests for signal quality summary reports."""

    def test_report_with_mixed_sqi(self):
        """Report should summarize mixed SQI values."""
        df = pd.DataFrame({"sqi": [0.9, 0.6, 0.3, 0.85, 0.95]})
        report = signal_quality_report(df)

        assert report["total_windows"] == 5
        assert report["good_windows"] == 3  # 0.9, 0.85, 0.95
        assert report["bad_windows"] == 1   # 0.3
        assert report["uncertain_windows"] == 1  # 0.6

    def test_empty_report(self):
        """Empty dataframe should return minimal report."""
        df = pd.DataFrame({"sqi": []})
        report = signal_quality_report(df)
        assert report["total_windows"] == 0
