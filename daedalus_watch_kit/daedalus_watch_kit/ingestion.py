"""
Ingestion module — loads raw sensor data from the Daedalus data layout.

Stage 1 of the core processing pipeline (POC Section 12).
"""

from pathlib import Path
from typing import Optional
import json

import pandas as pd

from .schemas import (
    SessionMetadata,
    IMU_COLUMNS, HR_COLUMNS, ECG_COLUMNS, EVENT_COLUMNS, ANNOTATION_COLUMNS,
)


def load_session_metadata(path: Path) -> SessionMetadata:
    """Load session_metadata.json from a session directory."""
    meta_path = path / "session_metadata.json"
    if not meta_path.exists():
        raise FileNotFoundError(f"session_metadata.json not found at {meta_path}")
    with open(meta_path) as f:
        return SessionMetadata.from_dict(json.load(f))


def load_watch_imu(path: Path) -> pd.DataFrame:
    """Load watch_imu.parquet and validate columns."""
    imu_path = path / "watch_imu.parquet"
    if not imu_path.exists():
        raise FileNotFoundError(f"watch_imu.parquet not found at {imu_path}")
    df = pd.read_parquet(imu_path)
    missing = set(IMU_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"watch_imu.parquet missing columns: {missing}")
    return df


def load_watch_hr(path: Path) -> pd.DataFrame:
    """Load watch_hr.parquet and validate columns."""
    hr_path = path / "watch_hr.parquet"
    if not hr_path.exists():
        raise FileNotFoundError(f"watch_hr.parquet not found at {hr_path}")
    df = pd.read_parquet(hr_path)
    missing = set(HR_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"watch_hr.parquet missing columns: {missing}")
    return df


def load_ecg_reference(path: Path) -> pd.DataFrame:
    """Load ecg_reference.parquet and validate columns."""
    ecg_path = path / "ecg_reference.parquet"
    if not ecg_path.exists():
        raise FileNotFoundError(f"ecg_reference.parquet not found at {ecg_path}")
    df = pd.read_parquet(ecg_path)
    missing = set(ECG_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"ecg_reference.parquet missing columns: {missing}")
    return df


def load_phone_events(path: Path) -> pd.DataFrame:
    """Load phone_events.csv and validate columns."""
    events_path = path / "phone_events.csv"
    if not events_path.exists():
        raise FileNotFoundError(f"phone_events.csv not found at {events_path}")
    df = pd.read_csv(events_path)
    missing = set(EVENT_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"phone_events.csv missing columns: {missing}")
    return df


def load_manual_annotations(path: Path) -> pd.DataFrame:
    """Load manual_annotations.csv and validate columns."""
    ann_path = path / "manual_annotations.csv"
    if not ann_path.exists():
        raise FileNotFoundError(f"manual_annotations.csv not found at {ann_path}")
    df = pd.read_csv(ann_path)
    missing = set(ANNOTATION_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"manual_annotations.csv missing columns: {missing}")
    return df


def compute_completeness(df: pd.DataFrame, column: str = "epoch_ms") -> float:
    """Compute data completeness as a percentage of non-null samples."""
    if df.empty:
        return 0.0
    total = len(df)
    valid = df[column].notna().sum()
    return (valid / total) * 100.0


def data_completeness_report(
    imu_df: Optional[pd.DataFrame] = None,
    hr_df: Optional[pd.DataFrame] = None,
    ecg_df: Optional[pd.DataFrame] = None,
) -> dict:
    """Generate a data completeness report for a session."""
    report = {}
    if imu_df is not None:
        report["imu_completeness_pct"] = compute_completeness(imu_df)
        report["imu_sample_count"] = len(imu_df)
    if hr_df is not None:
        report["hr_completeness_pct"] = compute_completeness(hr_df)
        report["hr_sample_count"] = len(hr_df)
    if ecg_df is not None:
        report["ecg_completeness_pct"] = compute_completeness(ecg_df)
        report["ecg_sample_count"] = len(ecg_df)
    return report


def ingest_session(raw_dir: Path) -> dict:
    """Ingest all data from a raw session directory.

    Returns a dict with all loaded dataframes and metadata.
    """
    subject_dir = raw_dir  # e.g. /data/raw/subject_001/session_001
    result = {}
    result["metadata"] = load_session_metadata(subject_dir)

    try:
        result["imu"] = load_watch_imu(subject_dir)
    except FileNotFoundError:
        result["imu"] = None

    try:
        result["hr"] = load_watch_hr(subject_dir)
    except FileNotFoundError:
        result["hr"] = None

    try:
        result["ecg"] = load_ecg_reference(subject_dir)
    except FileNotFoundError:
        result["ecg"] = None

    try:
        result["events"] = load_phone_events(subject_dir)
    except FileNotFoundError:
        result["events"] = None

    try:
        result["annotations"] = load_manual_annotations(subject_dir)
    except FileNotFoundError:
        result["annotations"] = None

    result["completeness"] = data_completeness_report(
        imu_df=result.get("imu"),
        hr_df=result.get("hr"),
        ecg_df=result.get("ecg"),
    )
    return result
