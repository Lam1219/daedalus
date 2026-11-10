"""Personal thresholds learned from history (shadow mode), simulated on Parkinson@Home.

Each visit has two annotated parts (PD: OFF then ON medication). The watch would learn from
its own unlabelled logs, so we learn each person's threshold from one part
(tremor_learning.learn, no labels) and score the other part. Two folds (learn part 1 / score
part 2, and the reverse); the counts are summed.

About 1.5-2.5 h of history per part is far less than the weeks shadow mode will have, so this
is a lower bound on what learning can do. Medication also changes the tremor between parts.

The learning settings (minimum evidence, consistency, quantile factor) are tuned leave one
subject out, as in T4: best event F1 with < 2 false episodes per hour at rest. "No learning"
is in the grid, so it wins if learning doesn't help.

Usage:
    python_scripts/.venv/bin/python python_scripts/evaluate_tremor_learning.py [--jobs 7]
        [--threshold 6] [--prominence 16] [--max-slow-ratio inf] [--max-slow4 inf]
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
from daedalus_watch_kit import tremor_learning as tl  # noqa: E402
from daedalus_watch_kit import tremor_stream as ts  # noqa: E402

EVIDENCE_GRID = [2.0, 5.0, 10.0]
SHARE_GRID = [0.4, 0.6]
FACTOR_GRID = [0.6, 0.8, 1.0]
NO_LEARNING = ("none",)
POPULATION = ts.TremorParams()


def grid():
    return [NO_LEARNING] + list(itertools.product(EVIDENCE_GRID, SHARE_GRID, FACTOR_GRID))


def score_task(args):
    task, population = args
    sid = task[0]
    subj = pd.read_csv(ev.DATA / "subjects.csv").set_index("subject_id").loc[sid]
    parts = [(subj.part1_start_s, subj.part1_end_s), (subj.part2_start_s, subj.part2_end_s)]
    base = ev.Recording(*task)
    results = {key: [] for key in grid()}
    info = []
    for learn_part, test_part in ((0, 1), (1, 0)):
        ls, le = parts[learn_part]
        ts_, te = parts[test_part]
        if any(pd.isna(x) for x in (ls, le, ts_, te)):
            continue
        history = [w for w in base.windows if ls <= w.t_end_ms / 1000.0 - 1 < le]
        rec = ev.Recording.__new__(ev.Recording)
        rec.__dict__.update(base.__dict__)
        rec.exclude(-1e9, ts_)
        rec.exclude(te, 1e12)
        for key in grid():
            if key == NO_LEARNING:
                p = population
            else:
                got = tl.learn(history, population, *key)
                p = got.params
                if key == (5.0, 0.4, 0.8):
                    info.append(dict(learn_part=learn_part + 1, personalised=got.personalised,
                                     threshold=round(p.threshold_dps, 2), person_hz=got.person_hz,
                                     evidence_min=got.evidence_min, share=got.share))
            results[key].append(rec.score(p))
    merged = {k: ev.total(v) for k, v in results.items() if v}
    return task, merged, info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=7)
    ap.add_argument("--threshold", type=float, default=6.0)
    ap.add_argument("--prominence", type=float, default=16.0)
    ap.add_argument("--max-slow-ratio", type=float, default=float("inf"))
    ap.add_argument("--max-slow4", type=float, default=float("inf"))
    args = ap.parse_args()
    population = ts.TremorParams(threshold_dps=args.threshold, min_prominence=args.prominence,
                                 max_slow_ratio=args.max_slow_ratio, max_slow4_dps=args.max_slow4)

    subj = pd.read_csv(ev.DATA / "subjects.csv")
    tasks = []
    for r in subj.itertuples():
        if r.tremor_annotated:
            tasks.append((r.subject_id, "RW" if r.tremor_side == "right" else "LW", r.group, True))
        else:
            tasks += [(r.subject_id, s, r.group, False) for s in ("LW", "RW")]

    t0 = time.time()
    recs = []
    with ProcessPoolExecutor(args.jobs) as ex:
        for task, res, info in ex.map(score_task, [(t, population) for t in tasks]):
            if not res:
                print(f"  {task[0]} {task[1]}: a part has no start or end time, skipped")
                continue
            recs.append(dict(sid=task[0], side=task[1], group=task[2], annotated=task[3], res=res, info=info))
    print(f"scored in {time.time() - t0:.0f} s")

    keys = grid()
    groups = {
        "tremor subjects": lambda r: r["annotated"],
        "healthy controls": lambda r: r["group"] == "HC",
        "PD without tremor labels": lambda r: r["group"] == "PD" and not r["annotated"],
        "all": lambda r: True,
    }
    rows = []
    for name, choose_keys in (("no learning", [NO_LEARNING]), ("learned (tuned leave-one-out)", keys)):
        held = []
        for sid in sorted({r["sid"] for r in recs}):
            key = ev.choose([r["res"] for r in recs if r["sid"] != sid], choose_keys)
            held += [(r, key) for r in recs if r["sid"] == sid]
        for g, sel in groups.items():
            s = ev.summary(ev.total([r["res"][key] for r, key in held if sel(r)]))
            rows.append(dict(strategy=name, group=g, **{k: s[k] for k in (
                "event_precision", "event_recall", "event_f1", "false_per_hour_rest", "onset_delay_median_s")}))
        if name.startswith("learned"):
            picks = sorted({str(k) for _, k in held})
            per_subject = []
            for r, key in held:
                if r["annotated"]:
                    s = ev.summary(r["res"][key])
                    s0 = ev.summary(r["res"][NO_LEARNING])
                    per_subject.append(dict(subject=r["sid"], f1_no_learning=round(s0["event_f1"], 3),
                                            f1_learned=round(s["event_f1"], 3),
                                            recall_no_learning=round(s0["event_recall"], 3),
                                            recall_learned=round(s["event_recall"], 3)))

    learned_info = []
    for r in recs:
        for i in r["info"]:
            learned_info.append(dict(subject=r["sid"], wrist=r["side"], group=r["group"],
                                     tremor_labels=r["annotated"], **i))
    info = pd.DataFrame(learned_info)

    table = pd.DataFrame(rows)
    stamp = time.strftime("%Y%m%d_%H%M")
    ev.OUT.mkdir(parents=True, exist_ok=True)
    table.to_csv(ev.OUT / f"tremor_learning_{stamp}.csv", index=False)
    info.to_csv(ev.OUT / f"tremor_learning_thresholds_{stamp}.csv", index=False)
    pd.set_option("display.width", 200)
    print(f"population: {population}")
    for g in groups:
        print(f"\n[{g}]")
        print(table[table.group == g].drop(columns="group").round(3).to_string(index=False))
    print("\nPer tremor subject:")
    print(pd.DataFrame(per_subject).to_string(index=False))
    print("\nSettings chosen (min evidence min, min share, factor):", picks)
    print("\nWho got a personal threshold (at 5 min, 0.4, 0.8), by group:")
    print(info.groupby(["group", "tremor_labels"]).personalised.agg(["sum", "count"]).to_string())
    print(info[info.personalised].sort_values("threshold").to_string(index=False))
    print(f"\nreports in {ev.OUT}")


if __name__ == "__main__":
    main()
