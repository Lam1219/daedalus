"""
Stress detection module.

POC Section 14: Stress Detection Plan.
Builds personalized deviation models for stress detection.
"""

import numpy as np
import pandas as pd
from typing import Optional
from dataclasses import dataclass


@dataclass
class PersonalBaseline:
    """Personalized baseline HRV metrics from a resting state."""
    mean_hr: float
    rmssd: float
    sdnn: float
    lf_hf: float
    sample_entropy: float

    @classmethod
    def from_hrv_features(cls, features: dict) -> "PersonalBaseline":
        return cls(
            mean_hr=features.get("mean_hr", np.nan),
            rmssd=features.get("rmssd", np.nan),
            sdnn=features.get("sdnn", np.nan),
            lf_hf=features.get("lf_hf_ratio", np.nan),
            sample_entropy=features.get("sample_entropy", np.nan),
        )


def z_score(value: float, mean: float, std: float) -> float:
    """Compute z-score. Returns 0 if std is 0."""
    if std == 0 or np.isnan(std):
        return 0.0
    return (value - mean) / std


def compute_stress_score(
    current_features: dict,
    baseline: PersonalBaseline,
    motion_intensity: float = 0.0,
    signal_quality: float = 1.0,
    weights: Optional[dict] = None,
) -> float:
    """Compute a rule-based stress score (0-100).

    stress_score = w1*z(HR) - w2*z(RMSSD) + w3*z(LF/HF) + w4*motion_penalty - w5*sqi_weight

    Higher scores indicate higher stress.
    """
    if weights is None:
        weights = {"w1": 0.3, "w2": 0.3, "w3": 0.2, "w4": 0.1, "w5": 0.1}

    # Compute baselines std from typical ranges if not available
    hr_std = baseline.mean_hr * 0.1 + 1  # 10% + floor
    rmssd_std = baseline.rmssd * 0.3 + 1
    lf_hf_std = baseline.lf_hf * 0.3 + 0.1

    z_hr = z_score(current_features.get("mean_hr", baseline.mean_hr), baseline.mean_hr, hr_std)
    z_rmssd = z_score(current_features.get("rmssd", baseline.rmssd), baseline.rmssd, rmssd_std)
    z_lf_hf = z_score(current_features.get("lf_hf_ratio", baseline.lf_hf), baseline.lf_hf, lf_hf_std)

    # Motion context penalty — high motion can mimic stress
    motion_penalty = min(motion_intensity / 2.0, 1.0)

    # Signal quality weight
    sqi_weight = 1.0 - signal_quality

    raw = (
        weights["w1"] * z_hr
        - weights["w2"] * z_rmssd
        + weights["w3"] * z_lf_hf
        + weights["w4"] * motion_penalty
        - weights["w5"] * sqi_weight
    )

    # Normalize to 0-100 using sigmoid-like scaling
    score = 100.0 / (1.0 + np.exp(-raw * 3))
    return float(np.clip(score, 0, 100))


class PersonalizedStressClassifier:
    """Simple personalized stress classifier using z-score features.

    Can be extended to logistic regression / random forest as data grows.
    """

    def __init__(self, baseline: PersonalBaseline):
        self.baseline = baseline
        self.threshold = 50.0  # Default threshold, tuned per subject

    def predict_score(self, features: dict, motion_intensity: float = 0.0,
                      signal_quality: float = 1.0) -> float:
        return compute_stress_score(features, self.baseline, motion_intensity, signal_quality)

    def predict(self, features: dict, motion_intensity: float = 0.0,
                signal_quality: float = 1.0) -> int:
        score = self.predict_score(features, motion_intensity, signal_quality)
        return int(score >= self.threshold)

    def set_threshold(self, threshold: float):
        self.threshold = threshold


def evaluate_stress_detection(
    scores: np.ndarray,
    labels: np.ndarray,
    threshold: float = 50.0,
) -> dict:
    """Evaluate stress classifier performance.

    Returns F1, precision, recall, false alarm rate.
    """
    predictions = (scores >= threshold).astype(int)

    tp = np.sum((predictions == 1) & (labels == 1))
    fp = np.sum((predictions == 1) & (labels == 0))
    fn = np.sum((predictions == 0) & (labels == 1))
    tn = np.sum((predictions == 0) & (labels == 0))

    precision = tp / (tp + fp + 1e-9)
    recall = tp / (tp + fn + 1e-9)
    f1 = 2 * precision * recall / (precision + recall + 1e-9)

    # False alarm rate during rest
    rest_labels = labels == 0
    false_alarms = np.sum((predictions == 1) & rest_labels)
    total_rest = np.sum(rest_labels)
    false_alarm_rate = false_alarms / (total_rest + 1e-9)

    return {
        "f1": float(f1),
        "precision": float(precision),
        "recall": float(recall),
        "accuracy": float((tp + tn) / (len(labels) + 1e-9)),
        "false_alarm_rate": float(false_alarm_rate),
        "confusion_matrix": {"tp": int(tp), "fp": int(fp), "fn": int(fn), "tn": int(tn)},
    }
