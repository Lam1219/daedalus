"""
Tests for stress detection module.

POC Section 14: Stress Detection Plan.
Tests: personalized baseline, stress score, classifier, evaluation.
"""

import numpy as np
import pytest

from daedalus_watch_kit.stress import (
    PersonalBaseline,
    z_score,
    compute_stress_score,
    PersonalizedStressClassifier,
    evaluate_stress_detection,
)


class TestZScore:
    """Tests for z-score computation."""

    def test_standard_z_score(self):
        """Should compute standard z-score."""
        assert z_score(80, 70, 10) == 1.0

    def test_zero_std_returns_zero(self):
        """Zero std should return 0 to avoid division by zero."""
        assert z_score(80, 70, 0) == 0.0

    def test_nan_std_returns_zero(self):
        """NaN std should return 0."""
        assert z_score(80, 70, np.nan) == 0.0

    def test_negative_z_score(self):
        """Values below mean should yield negative z-score."""
        assert z_score(60, 70, 10) == -1.0


class TestPersonalBaseline:
    """Tests for personalized baseline creation."""

    def test_from_hrv_features(self):
        """Should extract baseline values from HRV features dict."""
        features = {
            "mean_hr": 72.0, "rmssd": 50.0, "sdnn": 60.0,
            "lf_hf_ratio": 2.0, "sample_entropy": 1.2,
        }
        baseline = PersonalBaseline.from_hrv_features(features)
        assert baseline.mean_hr == 72.0
        assert baseline.rmssd == 50.0
        assert baseline.sdnn == 60.0
        assert baseline.lf_hf == 2.0
        assert baseline.sample_entropy == 1.2


class TestStressScore:
    """Tests for rule-based stress score computation."""

    def test_rest_state_low_score(self):
        """Rest-state features similar to baseline should yield low score."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        features = {"mean_hr": 71, "rmssd": 49, "lf_hf_ratio": 2.1}
        score = compute_stress_score(features, baseline, motion_intensity=0.0, signal_quality=1.0)
        # Should be near the middle (close to baseline)
        assert score < 60

    def test_stress_state_high_score(self):
        """Stress-state features should yield higher score."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        # Stress: elevated HR, reduced RMSSD, elevated LF/HF
        features = {"mean_hr": 95, "rmssd": 20, "lf_hf_ratio": 5.0}
        score = compute_stress_score(features, baseline, motion_intensity=0.0, signal_quality=1.0)
        assert score > 50

    def test_motion_penalty_increases_score(self):
        """High motion should increase the stress score."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        features = {"mean_hr": 70, "rmssd": 50, "lf_hf_ratio": 2.0}
        score_low_motion = compute_stress_score(features, baseline, motion_intensity=0.0, signal_quality=1.0)
        score_high_motion = compute_stress_score(features, baseline, motion_intensity=2.0, signal_quality=1.0)
        assert score_high_motion >= score_low_motion

    def test_low_quality_reduces_score(self):
        """Low signal quality should reduce the stress score."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        features = {"mean_hr": 90, "rmssd": 25, "lf_hf_ratio": 4.0}
        score_good = compute_stress_score(features, baseline, signal_quality=1.0)
        score_bad = compute_stress_score(features, baseline, signal_quality=0.2)
        # Low quality slightly reduces score (sqi_weight term)
        assert score_bad <= score_good

    def test_score_range_0_to_100(self):
        """Score should always be between 0 and 100."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        features = {"mean_hr": 70, "rmssd": 50, "lf_hf_ratio": 2.0}
        for _ in range(10):
            score = compute_stress_score(features, baseline)
            assert 0 <= score <= 100


class TestPersonalizedStressClassifier:
    """Tests for the stress classifier wrapper."""

    def test_predict_returns_0_or_1(self):
        """Predict should return binary 0 or 1."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        classifier = PersonalizedStressClassifier(baseline)
        features = {"mean_hr": 70, "rmssd": 50, "lf_hf_ratio": 2.0}
        prediction = classifier.predict(features)
        assert prediction in (0, 1)

    def test_threshold_affects_prediction(self):
        """Lower threshold should yield more positive predictions."""
        baseline = PersonalBaseline(
            mean_hr=70, rmssd=50, sdnn=60, lf_hf=2.0, sample_entropy=1.2,
        )
        classifier = PersonalizedStressClassifier(baseline)
        features = {"mean_hr": 70, "rmssd": 50, "lf_hf_ratio": 2.0}

        classifier.set_threshold(10)
        pred_low = classifier.predict(features)

        classifier.set_threshold(90)
        pred_high = classifier.predict(features)

        assert pred_low >= pred_high


class TestEvaluateStressDetection:
    """Tests for stress detection evaluation metrics."""

    def test_perfect_classification(self):
        """Perfect predictions should yield F1=1.0."""
        scores = np.array([80, 85, 20, 15])
        labels = np.array([1, 1, 0, 0])
        result = evaluate_stress_detection(scores, labels, threshold=50)
        assert pytest.approx(result["f1"], abs=1e-6) == 1.0
        assert result["false_alarm_rate"] == 0.0

    def test_all_wrong_classification(self):
        """All wrong predictions should yield F1=0.0."""
        scores = np.array([80, 85, 20, 15])
        labels = np.array([0, 0, 1, 1])
        result = evaluate_stress_detection(scores, labels, threshold=50)
        assert result["f1"] == 0.0

    def test_false_alarm_rate(self):
        """False alarm rate should count positive predictions during rest."""
        scores = np.array([60, 70, 20, 15])  # 2 false alarms
        labels = np.array([0, 0, 0, 0])      # All rest
        result = evaluate_stress_detection(scores, labels, threshold=50)
        assert pytest.approx(result["false_alarm_rate"], abs=1e-6) == 0.5  # 2/4

    def test_returns_all_metrics(self):
        """Should return all expected metric keys."""
        scores = np.array([80, 20])
        labels = np.array([1, 0])
        result = evaluate_stress_detection(scores, labels, threshold=50)
        expected = {"f1", "precision", "recall", "accuracy", "false_alarm_rate", "confusion_matrix"}
        assert set(result.keys()) == expected
