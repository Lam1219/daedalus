"""fog-1.0 on Parkinson@Home without freeze labels (task T5, first part).

The downloaded set has no freezing-of-gait annotations (asked from Radboud, action A2), but
every subject has video-labelled mobility states in the free-living parts: walking, turning,
stairs, standing, sitting, transitions. That allows two checks now:

  1. Walking detection. Is the wrist context WALKING/TURNING when the video says walking,
     turning or stairs? Per-second precision, recall and F1, and the cadence found.
  2. Freeze candidates per hour of walking, and where they happen: what the video shows
     at the moment of detection (standing, sitting down, turning, still walking...).
     In healthy controls every candidate is a false alarm. In PD some may be real freezes,
     which the FoG labels will settle.

Both wrists of every subject are scored as separate recordings (the watch can be on either).
A freeze-index and arm-swing sweep shows the trade-off.

Usage:
    python_scripts/.venv/bin/python python_scripts/evaluate_fog_parkinson_home.py [--jobs 7]
"""

from __future__ import annotations

import argparse
import itertools
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_tremor_parkinson_home as ev  # noqa: E402
from daedalus_watch_kit import fog  # noqa: E402

WALK_CODES = {"3", "4", "5.1", "5.2"}
FI_GRID = [1.0, 2.0, 4.0, 8.0, 16.0]
SWING_GRID = [0.0, 0.2, 0.3]
MOBILITY_NAMES = {"1": "sitting", "2": "standing", "3": "walking", "4": "turning", "5.1": "stairs",
                  "5.2": "stairs", "6": "lying"}


def mobility_name(code: str) -> str:
    if code.startswith("7."):
        return "transition"
    return MOBILITY_NAMES.get(code, "other" if code else "unlabelled")


def score(task):
    sid, side, group = task
    subj = pd.read_csv(ev.DATA / "subjects.csv").set_index("subject_id")
    mob = pd.read_csv(ev.DATA / "labels_mobility.csv", dtype={"code": str})
    m = mob[mob.subject_id == sid].sort_values("start_s")
    df = ev.features_for(sid, side)
    windows = ev.to_windows(df)
    centres = df.t_end_ms.to_numpy() / 1000.0 - 1.0
    code = ev.interval_lookup(centres, m.start_s, m.end_s, m.code.astype(str), "").astype(str)
    r = subj.loc[sid]
    inside = ((centres >= r.part1_start_s) & (centres < r.part1_end_s)) | \
             ((centres >= r.part2_start_s) & (centres < r.part2_end_s))
    labelled = inside & (code != "") & (code != "99")
    walk_true = np.isin(code, list(WALK_CODES)) & labelled
    walk_pred = df.context.isin(["walking", "turning"]).to_numpy() & labelled
    walk_cadence = df.cadence_spm[walk_true & walk_pred]

    out = dict(
        sid=sid, side=side, group=group,
        walk_tp=int((walk_true & walk_pred).sum()), walk_fp=int((~walk_true & walk_pred).sum()),
        walk_fn=int((walk_true & ~walk_pred).sum()),
        walking_hours=float(walk_true.sum()) / 3600.0, labelled_hours=float(labelled.sum()) / 3600.0,
        cadence_median=float(walk_cadence.median()) if len(walk_cadence) else np.nan,
        sweep={}, where=[],
    )
    for fi, sw in itertools.product(FI_GRID, SWING_GRID):
        cands = fog.detect(windows, fog.FogParams(freeze_index_threshold=fi, arm_swing_share=sw))
        kept = []
        for c in cands:
            i = int(np.clip(np.searchsorted(centres, c.detected_ms / 1000.0 - 1.0), 0, len(centres) - 1))
            if not labelled[i]:
                continue
            kept.append(c)
            if (fi, sw) == (fog.FREEZE_INDEX_DEFAULT, fog.ARM_SWING_SHARE):
                before = code[max(0, i - 5):i]
                out["where"].append(dict(
                    sid=sid, side=side, group=group, detected_s=round(c.detected_ms / 1000.0, 1),
                    at=mobility_name(code[i]),
                    walking_before=bool(np.isin(before, list(WALK_CODES)).any()),
                    reason=c.reason, turning=c.turning, freeze_index=round(c.freeze_index, 2),
                    arm_swing_share=c.arm_swing_share))
        out["sweep"][(fi, sw)] = dict(candidates=len(kept), by_reason={
            "freeze_index": sum(c.reason == "freeze_index" for c in kept),
            "arm_swing": sum(c.reason == "arm_swing" for c in kept)})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=7)
    args = ap.parse_args()
    subj = pd.read_csv(ev.DATA / "subjects.csv")
    tasks = [(r.subject_id, s, r.group) for r in subj.itertuples() for s in ("LW", "RW")
             if not any(pd.isna(x) for x in (r.part1_start_s, r.part1_end_s, r.part2_start_s, r.part2_end_s))]
    t0 = time.time()
    with ProcessPoolExecutor(args.jobs) as ex:
        res = list(ex.map(score, tasks))
    print(f"{len(res)} recordings ({time.time() - t0:.0f} s)")

    pd.set_option("display.width", 200)
    print("\n1. Walking detection (per second, against the video's walking / turning / stairs):")
    rows = []
    for g in ("PD", "HC"):
        rs = [r for r in res if r["group"] == g]
        tp, fp, fn = (sum(r[k] for r in rs) for k in ("walk_tp", "walk_fp", "walk_fn"))
        p, rc = tp / (tp + fp), tp / (tp + fn)
        rows.append(dict(group=g, precision=p, recall=rc, f1=2 * p * rc / (p + rc),
                         walking_hours=sum(r["walking_hours"] for r in rs),
                         cadence_median=np.nanmedian([r["cadence_median"] for r in rs])))
    print(pd.DataFrame(rows).round(3).to_string(index=False))

    print("\n2. Freeze candidates per hour of walking (freeze-index threshold x arm-swing share):")
    rows = []
    for key in itertools.product(FI_GRID, SWING_GRID):
        row = dict(freeze_index=key[0], arm_swing_share=key[1])
        for g in ("PD", "HC"):
            rs = [r for r in res if r["group"] == g]
            n = sum(r["sweep"][key]["candidates"] for r in rs)
            h = sum(r["walking_hours"] for r in rs)
            row[f"{g}_per_walk_hour"] = n / h
            row[f"{g}_per_hour"] = n / sum(r["labelled_hours"] for r in rs)
            row[f"{g}_by_freeze_index"] = sum(r["sweep"][key]["by_reason"]["freeze_index"] for r in rs)
            row[f"{g}_by_arm_swing"] = sum(r["sweep"][key]["by_reason"]["arm_swing"] for r in rs)
        rows.append(row)
    sweep = pd.DataFrame(rows)
    print(sweep.round(2).to_string(index=False))

    where = pd.DataFrame([w for r in res for w in r["where"]])
    stamp = time.strftime("%Y%m%d_%H%M")
    ev.OUT.mkdir(parents=True, exist_ok=True)
    sweep.to_csv(ev.OUT / f"fog_sweep_{stamp}.csv", index=False)
    where.to_csv(ev.OUT / f"fog_candidates_{stamp}.csv", index=False)
    if len(where):
        print(f"\n3. Where candidates happen (defaults: freeze index {fog.FREEZE_INDEX_DEFAULT}, "
              f"arm swing {fog.ARM_SWING_SHARE}), video label at detection:")
        print(pd.crosstab(where["at"], where["group"], margins=True).to_string())
        print("\nwalking in the 5 s before:", where.groupby("group").walking_before.mean().round(2).to_dict())
        print("by reason:", pd.crosstab(where.reason, where.group).to_dict())
        print("PD candidates by subject (top 8):", where[where.group == "PD"].sid.value_counts().head(8).to_dict())
    print(f"\nreports in {ev.OUT}")


if __name__ == "__main__":
    main()
