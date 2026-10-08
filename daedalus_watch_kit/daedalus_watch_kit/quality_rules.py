"""
Data quality rules and thresholds from the POC document.

See POC Sections 3 (Success Criteria) and 21 (Data Quality Rules).
"""

# --- Session-level rejection thresholds ---
MAX_WATCH_DISCONNECT_PCT = 10.0  # Reject if watch disconnected > 10%
MAX_ECG_MISSING_PCT = 10.0       # Reject if ECG reference missing > 10%
MAX_SYNC_ERROR_MS = 250          # Reject if time sync cannot be corrected < 250 ms
MAX_BATTERY_DRAIN_PCT = 20.0     # Acceptable battery drain for 60-min protocol

# --- Physiologically implausible ranges ---
HR_MIN_BPM = 30.0
HR_MAX_BPM = 220.0
RR_INTERVAL_MIN_MS = 300.0
RR_INTERVAL_MAX_MS = 2000.0

# --- Window-level quality thresholds ---
MAX_MISSING_SAMPLES_PCT = 20.0  # Flag window if missing samples > 20%
PPG_FLAT_THRESHOLD = 0.0        # Flag if PPG amplitude is flat

# --- Signal Quality Index thresholds ---
SQI_GOOD = 0.8
SQI_UNCERTAIN = 0.5

# --- Heart rate validation targets ---
HR_MAE_REST_TARGET_BPM = 3.0
HR_MAE_MOVEMENT_TARGET_BPM = 6.0
HRV_RMSSD_CORRELATION_TARGET = 0.85
HRV_RMSSD_RELATIVE_ERROR_TARGET = 0.15  # 15%
BAD_SEGMENT_DETECTION_SENSITIVITY = 0.85

# --- HRV usable window targets ---
HRV_USABLE_WINDOW_PRECISION = 0.85
HRV_USABLE_WINDOW_RECALL = 0.80

# --- Stress detection targets ---
STRESS_CLASSIFIER_F1_TARGET = 0.70
STRESS_FALSE_TRIGGER_RATE_PER_HOUR = 2.0

# --- Tremor detection targets ---
TREMOR_CLASSIFICATION_F1_TARGET = 0.80
TREMOR_FREQ_ESTIMATION_ERROR_HZ = 0.5
TREMOR_FALSE_POSITIVE_RATE = 0.05  # < 5% of epochs during quiet rest
TREMOR_BAND_LOW_HZ = 3.0
TREMOR_BAND_HIGH_HZ = 8.0

# --- Gait detection targets ---
GAIT_CADENCE_MAE_TARGET = 5.0  # < 5 steps/min
GAIT_DETECTION_F1_TARGET = 0.90

# --- Haptic performance targets ---
HAPTIC_LATENCY_TARGET_MS = 150.0
HAPTIC_JITTER_TARGET_MS = 30.0
HAPTIC_MISSED_PULSE_PCT = 1.0

# --- Haptic pulse parameters ---
HAPTIC_GAIT_PULSE_DURATION_MS = 20
HAPTIC_BREATHING_INHALE_SEC = 4
HAPTIC_BREATHING_EXHALE_SEC = 6
HAPTIC_TREMOR_PULSE_FREQ_HZ = 2.5       # 2-3 Hz range midpoint
HAPTIC_TREMOR_PULSE_DURATION_MS = 15    # 10-20 ms range midpoint


def is_hr_plausible(hr_bpm: float) -> bool:
    """Check if a heart rate value is physiologically plausible."""
    return HR_MIN_BPM <= hr_bpm <= HR_MAX_BPM


def is_rr_interval_plausible(rr_ms: float) -> bool:
    """Check if an RR interval is physiologically plausible."""
    return RR_INTERVAL_MIN_MS <= rr_ms <= RR_INTERVAL_MAX_MS


def classify_sqi(sqi: float) -> str:
    """Classify a Signal Quality Index value."""
    if sqi >= SQI_GOOD:
        return "good"
    elif sqi >= SQI_UNCERTAIN:
        return "uncertain"
    else:
        return "bad"
