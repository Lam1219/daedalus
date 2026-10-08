"""
Tests for windowing module.

POC Section 12, Stage 3: Windowing.
Tests: window creation, overlap, activity labeling, standard configs.
"""

import numpy as np
import pandas as pd
import pytest

from daedalus_watch_kit.windowing import (
    WindowConfig,
    WINDOW_CONFIGS,
    create_windows,
    create_all_windows,
)


class TestWindowConfig:
    """Tests for window configuration."""

    def test_window_length_ms(self):
        """Should convert seconds to milliseconds."""
        config = WindowConfig("test", window_length_sec=10)
        assert config.window_length_ms == 10000

    def test_step_ms_with_overlap(self):
        """Step should account for overlap percentage."""
        config = WindowConfig("test", window_length_sec=10, overlap_pct=50)
        assert config.step_ms == 5000  # 10000 * 0.5

    def test_step_ms_no_overlap(self):
        """Step should equal window length with no overlap."""
        config = WindowConfig("test", window_length_sec=300, overlap_pct=0)
        assert config.step_ms == 300000


class TestWindowCreation:
    """Tests for window creation from time series."""

    def _create_sample_df(self, n_samples=1000, sample_rate=50):
        """Create a sample time series DataFrame."""
        timestamps = np.arange(0, n_samples * (1000 / sample_rate), 1000 / sample_rate).astype(int)
        return pd.DataFrame({
            "epoch_ms": timestamps,
            "value": np.random.randn(n_samples),
        })

    def test_creates_windows(self):
        """Should create windows from a time series."""
        df = self._create_sample_df(1000, 50)  # 20 seconds at 50 Hz
        config = WindowConfig("test", window_length_sec=5, overlap_pct=50)

        windows = create_windows(df, config, subject_id="sub-001", session_id="sess-001")

        assert len(windows) > 0
        assert "window_start_ms" in windows.columns
        assert "window_end_ms" in windows.columns
        assert "sample_count" in windows.columns

    def test_window_properties(self):
        """Windows should have correct properties."""
        df = self._create_sample_df(1000, 50)
        config = WindowConfig("test", window_length_sec=5, overlap_pct=0)

        windows = create_windows(df, config, subject_id="sub-001", session_id="sess-001")

        # Check window duration
        duration = windows["window_end_ms"].iloc[0] - windows["window_start_ms"].iloc[0]
        assert duration == 5000  # 5 seconds

        # Check subject/session IDs
        assert windows["subject_id"].iloc[0] == "sub-001"
        assert windows["session_id"].iloc[0] == "sess-001"

    def test_overlapping_windows(self):
        """50% overlap should create more windows than non-overlapping."""
        df = self._create_sample_df(1000, 50)

        config_overlap = WindowConfig("test", window_length_sec=5, overlap_pct=50)
        config_no_overlap = WindowConfig("test", window_length_sec=5, overlap_pct=0)

        windows_overlap = create_windows(df, config_overlap)
        windows_no_overlap = create_windows(df, config_no_overlap)

        assert len(windows_overlap) > len(windows_no_overlap)

    def test_empty_dataframe(self):
        """Empty DataFrame should return empty windows."""
        df = pd.DataFrame({"epoch_ms": [], "value": []})
        config = WindowConfig("test", window_length_sec=5)
        windows = create_windows(df, config)
        assert len(windows) == 0

    def test_window_sample_indices(self):
        """Each window should reference correct sample indices."""
        df = self._create_sample_df(500, 50)  # 10 seconds
        config = WindowConfig("test", window_length_sec=5, overlap_pct=0)

        windows = create_windows(df, config)

        # First window should have samples from the first 5 seconds
        first_window = windows.iloc[0]
        indices = first_window["sample_indices"]
        assert len(indices) > 0
        # All indices should point to valid rows
        assert max(indices) < len(df)


class TestActivityLabeling:
    """Tests for activity label assignment from events."""

    def test_labels_from_events(self):
        """Windows should be labeled based on event timestamps."""
        df = pd.DataFrame({
            "epoch_ms": [1000, 2000, 3000, 4000, 5000, 6000],
            "value": [1, 2, 3, 4, 5, 6],
        })
        events = pd.DataFrame({
            "epoch_ms": [1500],
            "event_name": ["REST_START"],
        })
        config = WindowConfig("test", window_length_sec=2, overlap_pct=0)

        windows = create_windows(df, config, event_labels=events)

        # First window (1000-3000) contains REST_START
        assert len(windows) > 0
        assert windows.iloc[0]["activity_label"] == "REST"

    def test_unknown_without_events(self):
        """Windows without events should be labeled 'unknown'."""
        df = pd.DataFrame({
            "epoch_ms": [1000, 2000, 3000, 4000, 5000, 6000],
            "value": [1, 2, 3, 4, 5, 6],
        })
        config = WindowConfig("test", window_length_sec=2, overlap_pct=0)

        windows = create_windows(df, config, event_labels=None)

        assert len(windows) > 0
        assert windows.iloc[0]["activity_label"] == "unknown"


class TestStandardWindowConfigs:
    """Tests for the predefined window configurations."""

    def test_all_configs_exist(self):
        """All standard configs should be defined."""
        expected = {"signal_quality", "hrv_dynamic", "hrv_standard", "tremor", "gait", "stress"}
        assert set(WINDOW_CONFIGS.keys()) == expected

    def test_signal_quality_config(self):
        """Signal quality windows should be 10 sec with 50% overlap."""
        config = WINDOW_CONFIGS["signal_quality"]
        assert config.window_length_sec == 10
        assert config.overlap_pct == 50

    def test_tremor_config(self):
        """Tremor windows should be 2 sec with 50% overlap."""
        config = WINDOW_CONFIGS["tremor"]
        assert config.window_length_sec == 2
        assert config.overlap_pct == 50

    def test_gait_config(self):
        """Gait windows should be 4 sec with 50% overlap."""
        config = WINDOW_CONFIGS["gait"]
        assert config.window_length_sec == 4
        assert config.overlap_pct == 50

    def test_hrv_standard_config(self):
        """Standard HRV windows should be 5 min with no overlap."""
        config = WINDOW_CONFIGS["hrv_standard"]
        assert config.window_length_sec == 300
        assert config.overlap_pct == 0


class TestCreateAllWindows:
    """Tests for creating all standard windows at once."""

    def test_all_window_types_created(self):
        """Should create windows for all standard purposes."""
        df = pd.DataFrame({
            "epoch_ms": np.arange(0, 300000, 20).astype(int),  # 5 min at 50 Hz
            "value": np.random.randn(15000),
        })
        all_windows = create_all_windows(df, subject_id="sub-001", session_id="sess-001")

        assert set(all_windows.keys()) == set(WINDOW_CONFIGS.keys())
