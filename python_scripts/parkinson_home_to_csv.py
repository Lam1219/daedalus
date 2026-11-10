"""Convert the Parkinson@Home validation study (Radboud) wrist data and video labels to CSV.

Input: the dataset folder as downloaded and unpacked:

    dataset/
      sensor_data/phys_cur_PD_merged.mat      MATLAB 7.3 (HDF5): struct array `phys`
      sensor_data/phys_cur_HC_merged.mat        fields id, LW, RW, peakstart, peakend
      video_annotations/labels_PD_phys_tremor.mat   MATLAB 5: struct array `labels`
      video_annotations/labels_HC_phys.mat          with MATLAB tables inside

Output (default dataset/csv/):

    subjects.csv          one row per subject: group, tremor side, part start/end times
    labels_mobility.csv   video-annotated mobility states (sitting, walking, ...), both parts
    labels_tremor.csv     video-annotated tremor of the most affected side (PD with tremor only)
    sensors/<id>_<LW|RW>.csv   wrist IMU, one file per wrist

All times are seconds on the sensor clock (time 0 = start of the merged sensor recording),
so a label row selects sensor rows with  start_s <= timestamp_ms / 1000 < end_s.

Sensor CSV columns match the Daedalus watch session files (timestamp_ms, ax, ay, az, gx, gy, gz):
  - by default in the watches' units: acceleration in m/s², rotation rate in rad/s,
    so TremorAnalyzer.analyzeCsv and daedalus_watch_kit read them as they are;
  - with --raw-units: as recorded, acceleration in g, rotation rate in deg/s.

The sensors record at 200 Hz. By default the CSVs are resampled to 50 Hz (anti-aliased),
the rate both watches use; --rate 200 keeps the original samples (about 4x the disk space,
roughly 17 GB in total).

How the labels are stored in the .mat files (checked against every subject):
each annotation table has Start (1-based sample index at 200 Hz) and Duration (samples),
counted from that part's own start time (e.g. premed_tremorstart, in seconds).

Codes (from the study's video annotation protocol, v6):
  mobility: 1 sitting, 2 standing, 3 walking, 4 turning, 5.1/5.2 stairs up/down, 6 lying,
            7.x postural transitions, 8 cross-trainer, 9 cycling, 10 running, 11 scooter,
            12 car, 13 push-ups, 99 unknown
  tremor:   0 none, 1 slight/mild (<3 cm), 2 moderate (3-10 cm), 3 severe (>10 cm),
            96 periodic arm activity at tremor-like frequency, 97 arm activity with tremor visible,
            98 arm activity without tremor, 99 not assessable

PD part 1 is before the first medication intake of the day (OFF), part 2 after it (ON).
For healthy controls both parts are labelled for mobility only.

The data is covered by a Data Use Agreement: keep the output out of git (dataset/ is ignored)
and off shared drives.

Usage:
    python_scripts/.venv/bin/python python_scripts/parkinson_home_to_csv.py
    python_scripts/.venv/bin/python python_scripts/parkinson_home_to_csv.py --labels-only
    python_scripts/.venv/bin/python python_scripts/parkinson_home_to_csv.py --subjects pd02 hc01 --rate 200

Requirements: python_scripts/requirements-dataset.txt (mat-io decodes the MATLAB tables).
"""

from __future__ import annotations

import argparse
import csv
import gzip
import sys
import time
from pathlib import Path

import h5py
import numpy as np
import pandas as pd
from scipy.signal import resample_poly

try:
    import matio
except ImportError:  # pragma: no cover
    sys.exit("mat-io is needed to read the label tables: pip install -r python_scripts/requirements-dataset.txt")

NATIVE_HZ = 200
G = 9.80665
DEG_TO_RAD = np.pi / 180.0

MOBILITY = {
    "1": "sitting", "2": "standing", "3": "walking", "4": "turning",
    "5.1": "stairs_up", "5.2": "stairs_down", "6": "lying",
    "7.1": "sit_to_stand", "7.2": "stand_to_sit", "7.3": "sit_to_stand_low", "7.4": "stand_to_sit_low",
    "7.5": "lie_to_stand", "7.6": "stand_to_lie", "7.7": "sit_to_lie", "7.8": "lie_to_sit",
    "8": "crosstrainer", "9": "cycling", "10": "running", "11": "scooter", "12": "car",
    "13": "pushups", "99": "unknown",
}
TREMOR = {
    "0": "none", "1": "slight_mild", "2": "moderate", "3": "severe",
    "96": "periodic_arm_activity", "97": "arm_activity_with_tremor",
    "98": "arm_activity_no_tremor", "99": "not_assessable",
}

# (table field, start field, end field, part, medication state) per group
PD_PARTS = [
    ("premed", "premedstart", "premedend", 1, "OFF"),
    ("postmed", "postmedstart", "postmedend", 2, "ON"),
]
PD_TREMOR_PARTS = [
    ("premed_tremor", "premed_tremorstart", "premed_tremorend", 1, "OFF"),
    ("postmed_tremor", "postmed_tremorstart", "postmed_tremorend", 2, "ON"),
]
HC_PARTS = [
    ("pre", "prestart", "preend", 1, ""),
    ("post", "poststart", "postend", 2, ""),
]


# ---------------------------------------------------------------- labels

def _scalar(v) -> float | None:
    """A MATLAB scalar as float; None when the field is empty."""
    try:
        a = np.asarray(v, dtype=float).ravel()
    except (TypeError, ValueError):
        return None
    return round(float(a[0]), 3) if a.size else None


def _text(v) -> str:
    """A MATLAB char array, string or categorical as str ('' when empty)."""
    if isinstance(v, str):
        return v
    if not isinstance(v, (np.ndarray, pd.Categorical)):
        return ""  # None or matio's empty placeholder
    if isinstance(v, pd.Categorical):
        return str(v.ravel()[0]) if len(v.ravel()) else ""
    a = np.asarray(v, dtype=object).ravel()
    return str(a[0]) if a.size else ""


def _code(c) -> str:
    """Codes come as numbers (1, 7.1) or strings; normalise to the protocol's spelling."""
    s = str(c).strip()
    try:
        f = float(s)
    except ValueError:
        return s
    return str(int(f)) if f.is_integer() else f"{f:g}"


def _table_rows(entry, table, start_field, end_field) -> list[tuple[float, float, str]]:
    """(start_s, end_s, code) rows of one annotation table; [] when the table is absent."""
    df = entry[table]
    t0 = _scalar(entry[start_field])
    if not isinstance(df, pd.DataFrame) or df.empty or t0 is None:
        return []
    rows = []
    for start, dur, code in zip(df["Start"].to_numpy(float), df["Duration"].to_numpy(float), df["Code"]):
        s = t0 + (start - 1) / NATIVE_HZ
        rows.append((round(s, 3), round(s + dur / NATIVE_HZ, 3), _code(code)))
    t1 = _scalar(entry[end_field])
    if t1 is not None and rows and abs(rows[-1][1] - t1) > 1.0:
        print(f"  warning: {_text(entry['id'])} {table} ends at {rows[-1][1]:.1f} s, part ends at {t1:.1f} s")
    return rows


def _labels(path: Path):
    return matio.load_from_mat(str(path))["labels"].ravel()


def convert_labels(data_dir: Path, out_dir: Path, sync: dict[str, tuple[float | None, float | None]]):
    ann = data_dir / "video_annotations"
    subjects, mobility, tremor = [], [], []

    for group, fname, parts, tremor_parts in [
        ("PD", "labels_PD_phys_tremor.mat", PD_PARTS, PD_TREMOR_PARTS),
        ("HC", "labels_HC_phys.mat", HC_PARTS, []),
    ]:
        for e in _labels(ann / fname):
            sid = _text(e["id"])
            side = _text(e["tremorside"]) if "tremorside" in e.dtype.names else ""
            row = {"subject_id": sid, "group": group, "tremor_side": side}
            n_tremor = 0
            for table, sf, ef, part, med in parts:
                row[f"part{part}_start_s"] = _scalar(e[sf])
                row[f"part{part}_end_s"] = _scalar(e[ef])
                for s, en, code in _table_rows(e, table, sf, ef):
                    mobility.append([sid, part, med, s, en, code, MOBILITY.get(code, "")])
            for table, sf, ef, part, med in tremor_parts:
                for s, en, code in _table_rows(e, table, sf, ef):
                    tremor.append([sid, part, med, side, s, en, code, TREMOR.get(code, "")])
                    n_tremor += 1
            row["tremor_annotated"] = n_tremor > 0
            row["sync_start_s"], row["sync_end_s"] = sync.get(sid, (None, None))
            subjects.append(row)

    out_dir.mkdir(parents=True, exist_ok=True)
    cols = ["subject_id", "group", "tremor_side", "tremor_annotated",
            "part1_start_s", "part1_end_s", "part2_start_s", "part2_end_s", "sync_start_s", "sync_end_s"]
    pd.DataFrame(subjects)[cols].to_csv(out_dir / "subjects.csv", index=False)
    pd.DataFrame(mobility, columns=["subject_id", "part", "med_state", "start_s", "end_s", "code", "label"]) \
        .to_csv(out_dir / "labels_mobility.csv", index=False)
    pd.DataFrame(tremor, columns=["subject_id", "part", "med_state", "side", "start_s", "end_s", "code", "label"]) \
        .to_csv(out_dir / "labels_tremor.csv", index=False)
    print(f"labels: {len(subjects)} subjects, {len(mobility)} mobility rows, {len(tremor)} tremor rows")


# ---------------------------------------------------------------- sensors

def _sensor_files(data_dir: Path):
    sd = data_dir / "sensor_data"
    return [sd / "phys_cur_PD_merged.mat", sd / "phys_cur_HC_merged.mat"]


def _deref_text(f, ref) -> str:
    return "".join(map(chr, f[ref][()].ravel()))


def read_sync(data_dir: Path) -> dict[str, tuple[float | None, float | None]]:
    """peakstart / peakend (synchronisation impacts) per subject, seconds."""
    out = {}
    for path in _sensor_files(data_dir):
        with h5py.File(path, "r") as f:
            p = f["phys"]
            for i in range(p["id"].shape[0]):
                sid = _deref_text(f, p["id"][i, 0])
                vals = []
                for k in ("peakstart", "peakend"):
                    v = f[p[k][i, 0]][()]
                    vals.append(_scalar(v) if np.asarray(v).dtype != np.uint64 else None)
                out[sid] = tuple(vals)
    return out


def _wrist(f, ref, rate: int, raw_units: bool) -> np.ndarray:
    """One wrist as an (n, 7) array: time ms, ax, ay, az, gx, gy, gz."""
    w = f[ref]
    acc = w["accel"][()]   # (4, n): t [s], x, y, z [g]
    gyr = w["gyro"][()]    # (4, m): t [s], x, y, z [deg/s]
    t = acc[0]
    # accel and gyro can differ by a sample; put the gyro on the accelerometer's clock.
    g = np.vstack([np.interp(t, gyr[0], gyr[k]) for k in (1, 2, 3)])
    a = acc[1:4]
    if rate != NATIVE_HZ:
        dt = np.median(np.diff(t[: 10_000]))
        if abs(dt - 1 / NATIVE_HZ) > 1e-4:
            raise ValueError(f"unexpected sample interval {dt:.5f} s")
        up, down = rate, NATIVE_HZ
        a = resample_poly(a, up, down, axis=1)
        g = resample_poly(g, up, down, axis=1)
        t = t[0] + np.arange(a.shape[1]) / rate
    if not raw_units:
        a = a * G
        g = g * DEG_TO_RAD
    return np.column_stack([np.round(t * 1000).astype(np.int64), a.T, g.T])


def _write_csv(path: Path, data: np.ndarray, raw_units: bool, compress: bool):
    header = ["timestamp_ms", "ax", "ay", "az", "gx", "gy", "gz"]
    opener = (lambda p: gzip.open(p, "wt", newline="", compresslevel=5)) if compress else \
        (lambda p: open(p, "w", newline=""))
    fmt_a = "%.5f" if raw_units else "%.4f"   # g vs m/s²
    fmt_g = "%.3f" if raw_units else "%.5f"   # deg/s vs rad/s
    with opener(path) as fh:
        csv.writer(fh).writerow(header)
        np.savetxt(fh, data, delimiter=",", fmt=["%d"] + [fmt_a] * 3 + [fmt_g] * 3)


def convert_sensors(data_dir: Path, out_dir: Path, rate: int, raw_units: bool, compress: bool,
                    only: set[str] | None, overwrite: bool):
    sensors = out_dir / "sensors"
    sensors.mkdir(parents=True, exist_ok=True)
    ext = ".csv.gz" if compress else ".csv"
    for path in _sensor_files(data_dir):
        with h5py.File(path, "r") as f:
            p = f["phys"]
            for i in range(p["id"].shape[0]):
                sid = _deref_text(f, p["id"][i, 0])
                if only and sid not in only:
                    continue
                for side in ("LW", "RW"):
                    target = sensors / f"{sid}_{side}{ext}"
                    if target.exists() and not overwrite:
                        print(f"  {target.name} exists, skipped")
                        continue
                    t0 = time.time()
                    data = _wrist(f, p[side][i, 0], rate, raw_units)
                    tmp = target.with_name(target.name + ".part")
                    _write_csv(tmp, data, raw_units, compress)
                    tmp.rename(target)
                    hours = (data[-1, 0] - data[0, 0]) / 3.6e6
                    print(f"  {target.name}: {len(data):,} rows, {hours:.1f} h, {time.time() - t0:.0f} s")


# ---------------------------------------------------------------- main

def main():
    repo = Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--data", type=Path, default=repo / "dataset", help="unpacked dataset folder")
    ap.add_argument("--out", type=Path, default=None, help="output folder (default <data>/csv)")
    ap.add_argument("--rate", type=int, default=50, choices=[25, 50, 100, 200],
                    help="sensor CSV rate in Hz (default 50, as the watches; 200 = original samples)")
    ap.add_argument("--raw-units", action="store_true", help="keep g and deg/s instead of m/s² and rad/s")
    ap.add_argument("--gzip", action="store_true", help="write sensors/*.csv.gz")
    ap.add_argument("--subjects", nargs="*", help="only these subject ids (e.g. pd02 hc01)")
    ap.add_argument("--labels-only", action="store_true", help="write subjects and labels, skip sensors")
    ap.add_argument("--overwrite", action="store_true", help="rewrite sensor files that already exist")
    args = ap.parse_args()

    out = args.out or args.data / "csv"
    print(f"reading {args.data}, writing {out}")
    sync = read_sync(args.data)
    convert_labels(args.data, out, sync)
    if not args.labels_only:
        convert_sensors(args.data, out, args.rate, args.raw_units, args.gzip,
                        set(args.subjects) if args.subjects else None, args.overwrite)
    print("done")


if __name__ == "__main__":
    main()
