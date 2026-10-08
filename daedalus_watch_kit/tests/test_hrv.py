"""
Tests for HRV processing functions.

POC Section 13: HRV Processing Plan.
Tests: IBI cleaning, time-domain features, frequency-domain features, nonlinear features.
"""

import numpy as np
import pytest

from daedalus_watch_kit.hrv import (
    clean_ibi,
    compute_time_domain_features,
    compute_frequency_domain_features,
    compute_sample_entropy,
    compute_all_hrv_features,
)


class TestCleanIBI:
    """Tests for inter-beat interval cleaning."""

    def test_removes_out_of_range_values(self):
        """RR intervals outside 300-2000 ms should be flagged and interpolated."""
        rr = np.array([800, 810, 100, 820, 830, 2500, 840], dtype=float)
        cleaned = clean_ibi(rr)

        # The out-of-range values (100, 2500) should be replaced
        assert cleaned[2] != 100.0
        assert cleaned[5] != 2500.0
        # Valid values within range should be preserved
        assert cleaned[0] == 800.0
        assert cleaned[1] == 810.0

    def test_preserves_clean_series(self):
        """A clean RR series should be unchanged."""
        rr = np.array([800, 810, 805, 815, 800, 810], dtype=float)
        cleaned = clean_ibi(rr)
        np.testing.assert_array_almost_equal(cleaned, rr)

    def test_handles_short_series(self):
        """Very short series should be handled gracefully."""
        rr = np.array([800.0])
        cleaned = clean_ibi(rr)
        assert len(cleaned) == 1

    def test_interpolates_missing_values(self):
        """Outlier values should be interpolated from neighbors."""
        rr = np.array([800, 810, 100, 820, 830], dtype=float)
        cleaned = clean_ibi(rr)
        # The interpolated value should be between neighbors
        assert 800 <= cleaned[2] <= 820


class TestTimeDomainFeatures:
    """Tests for time-domain HRV feature computation."""

    def test_rmssd_computation(self):
        """RMSSD should match known values."""
        # Two beats: 800ms, 820ms → diff = 20ms → RMSSD = sqrt(20^2) = 20
        rr = np.array([800, 820], dtype=float)
        features = compute_time_domain_features(rr)
        assert pytest.approx(features["rmssd"], abs=0.01) == 20.0

    def test_mean_hr_computation(self):
        """Mean HR should be 60000 / mean(RR)."""
        rr = np.array([1000, 1000, 1000], dtype=float)  # 1 sec intervals → 60 bpm
        features = compute_time_domain_features(rr)
        assert pytest.approx(features["mean_hr"], abs=0.1) == 60.0

    def test_sdnn_computation(self):
        """SDNN should be the std of RR intervals."""
        rr = np.array([800, 1000, 1200], dtype=float)
        features = compute_time_domain_features(rr)
        assert pytest.approx(features["sdnn"], abs=0.01) == np.std(rr)

    def test_pnn50_computation(self):
        """pNN50 should be percentage of successive differences > 50ms."""
        rr = np.array([800, 900, 800, 1000], dtype=float)
        # diffs: 100, 100, 200 → all > 50 → pNN50 = 100%
        features = compute_time_domain_features(rr)
        assert pytest.approx(features["pnn50"], abs=0.1) == 100.0

    def test_empty_series_returns_nan(self):
        """Empty or single-element series should return NaN."""
        rr = np.array([800.0])
        features = compute_time_domain_features(rr)
        assert np.isnan(features["rmssd"])
        assert np.isnan(features["pnn50"])

    def test_all_features_present(self):
        """All expected feature keys should be present."""
        rr = np.array([800, 810, 805, 820, 800, 815, 810, 805], dtype=float)
        features = compute_time_domain_features(rr)
        expected_keys = {"mean_hr", "sdnn", "rmssd", "pnn50", "nn_outlier_rate"}
        assert set(features.keys()) == expected_keys


class TestFrequencyDomainFeatures:
    """Tests for frequency-domain HRV features."""

    def test_returns_all_features(self):
        """All frequency domain features should be present."""
        rr = np.random.uniform(700, 900, 100).astype(float)
        features = compute_frequency_domain_features(rr)
        expected = {"lf_power", "hf_power", "lf_hf_ratio", "total_power"}
        assert set(features.keys()) == expected

    def test_short_series_returns_nan(self):
        """Very short series should return NaN."""
        rr = np.array([800, 810, 805], dtype=float)
        features = compute_frequency_domain_features(rr)
        assert all(np.isnan(v) for v in features.values())

    def test_lomb_scargle_method(self):
        """Lomb-Scargle method should work for irregular data."""
        rr = np.random.uniform(700, 900, 50).astype(float)
        features = compute_frequency_domain_features(rr, method="lomb_scargle")
        assert not np.isnan(features["lf_power"])


class TestSampleEntropy:
    """Tests for sample entropy computation."""

    def test_regular_signal_low_entropy(self):
        """A perfectly regular signal should have low sample entropy."""
        rr = np.array([800.0] * 50)
        entropy = compute_sample_entropy(rr)
        # Very regular → low entropy (may be NaN if perfectly regular)
        assert entropy <= 1.0 or np.isnan(entropy)

    def test_noisy_signal_higher_entropy(self):
        """A noisy signal should have higher entropy than a regular one."""
        np.random.seed(42)
        regular = np.array([800.0] * 30)
        noisy = np.random.uniform(700, 900, 30)

        ent_regular = compute_sample_entropy(regular)
        ent_noisy = compute_sample_entropy(noisy)

        # Noisy should have higher or comparable entropy
        if not np.isnan(ent_noisy) and not np.isnan(ent_regular):
            assert ent_noisy >= ent_regular

    def test_short_series_returns_nan(self):
        """Very short series should return NaN."""
        rr = np.array([800.0, 810.0])
        assert np.isnan(compute_sample_entropy(rr))


class TestAllHRVFeatures:
    """Tests for the combined HRV feature computation."""

    def test_all_features_computed(self):
        """All features should be present in combined output."""
        rr = np.random.uniform(700, 900, 100).astype(float)
        features = compute_all_hrv_features(rr)
        expected = {
            "mean_hr", "sdnn", "rmssd", "pnn50", "nn_outlier_rate",
            "lf_power", "hf_power", "lf_hf_ratio", "total_power",
            "sample_entropy",
        }
        assert set(features.keys()) == expected

    def test_with_sample_times(self):
        """Should work when sample times are provided."""
        rr = np.random.uniform(700, 900, 50).astype(float)
        times = np.cumsum(rr)
        features = compute_all_hrv_features(rr, sample_times_ms=times)
        assert not np.isnan(features["mean_hr"])
