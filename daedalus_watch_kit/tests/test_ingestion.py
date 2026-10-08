"""
Tests for data ingestion module.

POC Section 9 (File Structure) and Section 12, Stage 1 (Ingestion).
Tests: metadata loading, parquet/csv loading, completeness reporting.
"""

import json
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from daedalus_watch_kit.ingestion import (
    load_session_metadata,
    load_watch_imu,
    load_watch_hr,
    load_ecg_reference,
    load_phone_events,
    load_manual_annotations,
    compute_completeness,
    data_completeness_report,
    ingest_session,
)


class TestSessionMetadata:
    """Tests for session metadata loading."""

    def test_load_valid_metadata(self, tmp_path):
        """Should load valid session_metadata.json."""
        meta = {
            "subject_id": "sub-001",
            "session_id": "sess-001",
            "device_model": "Samsung Galaxy Watch5 Pro R920",
            "watch_side": "left",
            "session_start_epoch_ms": 1760000000000,
            "session_end_epoch_ms": 1760003600000,
            "protocol_version": "v0.1",
            "cohort": "healthy_control",
            "clinician_present": False,
            "notes": "",
        }
        (tmp_path / "session_metadata.json").write_text(json.dumps(meta))

        result = load_session_metadata(tmp_path)
        assert result.subject_id == "sub-001"
        assert result.session_id == "sess-001"
        assert result.cohort == "healthy_control"

    def test_missing_metadata_raises(self, tmp_path):
        """Missing metadata file should raise FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            load_session_metadata(tmp_path)


class TestParquetLoading:
    """Tests for loading parquet data files."""

    def _create_imu_parquet(self, path: Path):
        df = pd.DataFrame({
            "epoch_ms": [1000, 1020, 1040],
            "elapsed_ms": [0, 20, 40],
            "ax": [0.1, 0.2, 0.1], "ay": [0.0, 0.1, 0.0], "az": [9.8, 9.8, 9.8],
            "gx": [0.0, 0.0, 0.0], "gy": [0.0, 0.0, 0.0], "gz": [0.0, 0.0, 0.0],
            "motion_intensity": [0.1, 0.2, 0.1],
        })
        df.to_parquet(path / "watch_imu.parquet")

    def _create_hr_parquet(self, path: Path):
        df = pd.DataFrame({
            "epoch_ms": [1000, 2000],
            "hr_bpm": [72.0, 74.0],
            "hrv_rmssd_ms": [50.0, 55.0],
            "hr_quality": [0.9, 0.8],
            "source": ["sensor", "sensor"],
        })
        df.to_parquet(path / "watch_hr.parquet")

    def _create_ecg_parquet(self, path: Path):
        df = pd.DataFrame({
            "epoch_ms": [1000, 2000],
            "ecg_raw": [0.0, 0.1],
            "rr_ms": [800.0, 810.0],
            "hr_bpm": [75.0, 74.0],
            "beat_quality": [1.0, 1.0],
        })
        df.to_parquet(path / "ecg_reference.parquet")

    def test_load_imu(self, tmp_path):
        """Should load watch_imu.parquet with correct columns."""
        self._create_imu_parquet(tmp_path)
        df = load_watch_imu(tmp_path)
        assert len(df) == 3
        assert "ax" in df.columns
        assert "gx" in df.columns

    def test_missing_imu_raises(self, tmp_path):
        """Missing IMU file should raise FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            load_watch_imu(tmp_path)

    def test_load_hr(self, tmp_path):
        """Should load watch_hr.parquet."""
        self._create_hr_parquet(tmp_path)
        df = load_watch_hr(tmp_path)
        assert len(df) == 2
        assert "hr_bpm" in df.columns

    def test_load_ecg(self, tmp_path):
        """Should load ecg_reference.parquet."""
        self._create_ecg_parquet(tmp_path)
        df = load_ecg_reference(tmp_path)
        assert len(df) == 2
        assert "rr_ms" in df.columns


class TestCSVLoading:
    """Tests for loading CSV event/annotation files."""

    def _create_events_csv(self, path: Path):
        df = pd.DataFrame({
            "epoch_ms": [1000, 2000, 3000],
            "event_name": ["REST_START", "STRESS_TASK_START", "REST_START"],
            "source": ["manual", "manual", "manual"],
            "notes": ["", "", ""],
        })
        df.to_csv(path / "phone_events.csv", index=False)

    def _create_annotations_csv(self, path: Path):
        df = pd.DataFrame({
            "start_epoch_ms": [1000, 2000],
            "end_epoch_ms": [1500, 2500],
            "activity": ["rest", "stress"],
            "tremor_present": [False, False],
            "fog_present": [False, False],
            "stress_level": [2.0, 8.0],
            "signal_quality": [0.9, 0.7],
            "annotator_id": ["ann-01", "ann-01"],
        })
        df.to_csv(path / "manual_annotations.csv", index=False)

    def test_load_events(self, tmp_path):
        """Should load phone_events.csv."""
        self._create_events_csv(tmp_path)
        df = load_phone_events(tmp_path)
        assert len(df) == 3
        assert "event_name" in df.columns

    def test_load_annotations(self, tmp_path):
        """Should load manual_annotations.csv."""
        self._create_annotations_csv(tmp_path)
        df = load_manual_annotations(tmp_path)
        assert len(df) == 2
        assert "activity" in df.columns


class TestCompleteness:
    """Tests for data completeness computation."""

    def test_full_completeness(self):
        """DataFrame with no nulls should be 100% complete."""
        df = pd.DataFrame({"epoch_ms": [1, 2, 3, 4, 5]})
        assert compute_completeness(df) == 100.0

    def test_partial_completeness(self):
        """DataFrame with nulls should reflect correct percentage."""
        df = pd.DataFrame({"epoch_ms": [1, np.nan, 3, np.nan, 5]})
        assert compute_completeness(df) == 60.0

    def test_empty_dataframe(self):
        """Empty DataFrame should be 0% complete."""
        df = pd.DataFrame({"epoch_ms": []})
        assert compute_completeness(df) == 0.0

    def test_completeness_report(self):
        """Report should include all available dataframes."""
        imu_df = pd.DataFrame({"epoch_ms": [1, 2, 3]})
        hr_df = pd.DataFrame({"epoch_ms": [1, np.nan, 3]})
        ecg_df = pd.DataFrame({"epoch_ms": [1, 2]})

        report = data_completeness_report(imu_df=imu_df, hr_df=hr_df, ecg_df=ecg_df)

        assert report["imu_completeness_pct"] == 100.0
        assert report["hr_completeness_pct"] == pytest.approx(66.67, abs=0.1)
        assert report["ecg_completeness_pct"] == 100.0


class TestIngestSession:
    """Integration tests for full session ingestion."""

    def test_full_ingestion(self, tmp_path):
        """Should ingest all available data from a session directory."""
        # Create metadata
        meta = {
            "subject_id": "sub-001", "session_id": "sess-001",
            "device_model": "Samsung Galaxy Watch5 Pro R920",
            "watch_side": "left", "session_start_epoch_ms": 1760000000000,
            "session_end_epoch_ms": 1760003600000, "protocol_version": "v0.1",
            "cohort": "healthy_control", "clinician_present": False,
            "ecg_device": "Cycplus H2", "notes": "",
        }
        (tmp_path / "session_metadata.json").write_text(json.dumps(meta))

        # Create IMU data
        imu_df = pd.DataFrame({
            "epoch_ms": [1000, 1020], "elapsed_ms": [0, 20],
            "ax": [0.1, 0.2], "ay": [0.0, 0.1], "az": [9.8, 9.8],
            "gx": [0.0, 0.0], "gy": [0.0, 0.0], "gz": [0.0, 0.0],
            "motion_intensity": [0.1, 0.2],
        })
        imu_df.to_parquet(tmp_path / "watch_imu.parquet")

        # Create HR data
        hr_df = pd.DataFrame({
            "epoch_ms": [1000, 2000], "hr_bpm": [72.0, 74.0],
            "hrv_rmssd_ms": [50.0, 55.0], "hr_quality": [0.9, 0.8],
            "source": ["sensor", "sensor"],
        })
        hr_df.to_parquet(tmp_path / "watch_hr.parquet")

        # Create ECG data (Cycplus H2 — RR intervals, no raw ECG waveform)
        ecg_df = pd.DataFrame({
            "epoch_ms": [1000, 2000], "ecg_raw": [np.nan, np.nan],
            "rr_ms": [800.0, 810.0], "hr_bpm": [75.0, 74.0],
            "beat_quality": [1.0, 1.0],
        })
        ecg_df.to_parquet(tmp_path / "ecg_reference.parquet")

        # Create events
        events_df = pd.DataFrame({
            "epoch_ms": [1000], "event_name": ["REST_START"],
            "source": ["manual"], "notes": [""],
        })
        events_df.to_csv(tmp_path / "phone_events.csv", index=False)

        # Create annotations
        ann_df = pd.DataFrame({
            "start_epoch_ms": [1000], "end_epoch_ms": [1500],
            "activity": ["rest"], "tremor_present": [False],
            "fog_present": [False], "stress_level": [2.0],
            "signal_quality": [0.9], "annotator_id": ["ann-01"],
        })
        ann_df.to_csv(tmp_path / "manual_annotations.csv", index=False)

        result = ingest_session(tmp_path)

        assert result["metadata"].subject_id == "sub-001"
        assert result["imu"] is not None
        assert result["hr"] is not None
        assert result["ecg"] is not None
        assert result["events"] is not None
        assert result["annotations"] is not None
        assert "completeness" in result

    def test_partial_ingestion(self, tmp_path):
        """Should handle missing optional files gracefully."""
        meta = {
            "subject_id": "sub-002", "session_id": "sess-001",
            "device_model": "Samsung Galaxy Watch5 Pro R920",
            "watch_side": "right", "session_start_epoch_ms": 1760000000000,
            "session_end_epoch_ms": 1760003600000, "protocol_version": "v0.1",
            "cohort": "healthy_control", "clinician_present": False, "notes": "",
        }
        (tmp_path / "session_metadata.json").write_text(json.dumps(meta))
        # No data files — only metadata

        result = ingest_session(tmp_path)

        assert result["metadata"].subject_id == "sub-002"
        assert result["imu"] is None
        assert result["hr"] is None
        assert result["ecg"] is None
