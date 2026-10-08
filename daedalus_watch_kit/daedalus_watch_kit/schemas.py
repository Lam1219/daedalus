"""
Data schema definitions for the Daedalus watch data pipeline.

See POC Section 9: File Structure and Data Schema.
"""

from dataclasses import dataclass, field
from typing import Optional
import json


@dataclass
class SessionMetadata:
    """Schema for session_metadata.json."""
    subject_id: str
    session_id: str
    device_model: str = "Samsung Galaxy Watch5 Pro R920"
    firmware_version: str = "unknown"
    watch_side: str = "left"
    phone_model: str = ""
    ecg_device: str = "Cycplus H2"
    session_start_epoch_ms: int = 0
    session_end_epoch_ms: int = 0
    protocol_version: str = "v0.1"
    cohort: str = "healthy_control"
    clinician_present: bool = False
    notes: str = ""

    def to_dict(self) -> dict:
        return {
            "subject_id": self.subject_id,
            "session_id": self.session_id,
            "device_model": self.device_model,
            "firmware_version": self.firmware_version,
            "watch_side": self.watch_side,
            "phone_model": self.phone_model,
            "ecg_device": self.ecg_device,
            "session_start_epoch_ms": self.session_start_epoch_ms,
            "session_end_epoch_ms": self.session_end_epoch_ms,
            "protocol_version": self.protocol_version,
            "cohort": self.cohort,
            "clinician_present": self.clinician_present,
            "notes": self.notes,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "SessionMetadata":
        return cls(**{k: d.get(k, cls.__dataclass_fields__[k].default) for k in cls.__dataclass_fields__})

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2)

    @classmethod
    def from_json(cls, json_str: str) -> "SessionMetadata":
        return cls.from_dict(json.loads(json_str))


# --- Parquet column definitions (documented as type annotations) ---

# watch_imu.parquet columns:
#   epoch_ms (int64)     - Converted timestamp
#   elapsed_ms (int64)   - Device elapsed time
#   ax (float)           - Acceleration x
#   ay (float)           - Acceleration y
#   az (float)           - Acceleration z
#   gx (float)           - Gyro x
#   gy (float)           - Gyro y
#   gz (float)           - Gyro z
#   motion_intensity (float) - Optional online feature

# watch_hr.parquet columns:
#   epoch_ms (int64)     - Timestamp
#   hr_bpm (float)       - Heart rate
#   hrv_rmssd_ms (float) - If available
#   hr_quality (float)   - If available
#   source (string)      - Samsung Health / sensor

# ecg_reference.parquet columns (Cycplus H2 chest strap — RR intervals + HR, no raw ECG):
#   epoch_ms (int64)     - Timestamp
#   ecg_raw (float)      - Always NaN for Cycplus H2 (no raw waveform)
#   rr_ms (float)        - RR interval (primary reference signal)
#   hr_bpm (float)       - Chest-strap-derived HR
#   beat_quality (float) - Quality flag (BLE connection confidence)

# phone_events.csv columns:
#   epoch_ms (int64)     - Timestamp
#   event_name (string)  - Event label
#   source (string)      - manual/auto
#   notes (string)       - Optional

# manual_annotations.csv columns:
#   start_epoch_ms (int64) - Segment start
#   end_epoch_ms (int64)   - Segment end
#   activity (string)      - rest/walk/tremor
#   tremor_present (bool)  - Tremor label
#   fog_present (bool)     - Freezing label
#   stress_level (float)   - 0-10
#   signal_quality (float) - good/bad
#   annotator_id (string)  - Annotator

IMU_COLUMNS = ["epoch_ms", "elapsed_ms", "ax", "ay", "az", "gx", "gy", "gz", "motion_intensity"]
HR_COLUMNS = ["epoch_ms", "hr_bpm", "hrv_rmssd_ms", "hr_quality", "source"]
ECG_COLUMNS = ["epoch_ms", "ecg_raw", "rr_ms", "hr_bpm", "beat_quality"]
EVENT_COLUMNS = ["epoch_ms", "event_name", "source", "notes"]
ANNOTATION_COLUMNS = [
    "start_epoch_ms", "end_epoch_ms", "activity",
    "tremor_present", "fog_present", "stress_level",
    "signal_quality", "annotator_id",
]
