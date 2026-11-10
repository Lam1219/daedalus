"""Evaluate tremor-stream-1.0 on Parkinson@Home (task T4, Documentation/automatic-cues-tasks.md).

Reads the CSVs written by parkinson_home_to_csv.py (dataset/csv/) and writes a report to
dataset/reports/ (git-ignored, like all derived patient data).

What counts as what (per 1 s window, judged at the window's centre):
  - Tremor subjects (tremor annotated; most affected wrist only, since the labels describe that side):
      codes 1-3 tremor                              -> positive
      code 0 no tremor, 96 / 98 arm activity        -> negative
      code 97 (activity with tremor visible), 99 (not assessable), unlabelled time -> excluded
  - Healthy controls, both wrists, inside the annotated parts            -> negative
  - PD without tremor annotations (screened, no tremor seen), both wrists -> negative, reported apart

Metrics:
  - Episodes. A tremor bout is a run of positive labels (gaps <= 3 s merged).
      recall    = bouts of >= MIN_BOUT_S overlapped by a detected episode
      precision = detected episodes overlapping any positive label, among episodes that started
                  in assessable time
      Event F1 combines the two (the PRD target: F1 > 0.80).
  - False episodes per hour at rest: episodes that start at a negative time while the person
    is sitting or lying (mobility codes 1 and 6), per hour of such time.
  - Onset delay: detection time minus bout start, for bouts where the episode starts inside them.
  - Per-window precision, recall and F1, as a secondary view.

Tuning: threshold T (deg/s) and minimum prominence over a grid, leave one subject out. Each
fold picks the setting with the best event F1 on the other subjects among those with < 2 false
episodes per hour at rest, then scores the held-out subject; counts are summed over folds.
Parkinson@Home has no calibration rest, so T is a population value here. On the watch it is
personal (calibration and feedback), which this evaluation can't show.

Usage:
    python_scripts/.venv/bin/python python_scripts/evaluate_tremor_parkinson_home.py [--jobs 6]
"""

from __future__ import annotations

import argparse
import itertools
import json
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from daedalus_watch_kit import stream_features as sf
from daedalus_watch_kit import tremor_stream as ts

REPO = Path(__file__).resolve().parents[1]
DATA = REPO / "dataset" / "csv"
OUT = REPO / "dataset" / "reports"
CACHE = OUT / "features"

T_GRID = [1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0, 20.0]
PROM_GRID = [4.0, 6.0, 8.0, 10.0, 12.0, 16.0]
# Rest gate grid, used with --rest-gate: (max_slow_ratio, max_slow4_dps); inf = off.
INF = float("inf")
RATIO_GRID = [INF, 0.5, 0.35, 0.25, 0.18]
SLOW4_GRID = [INF, 8.0, 5.0, 3.0]
REST_GATE = False
FALSE_PER_HOUR_LIMIT = 2.0
MIN_BOUT_S = 6.0
MERGE_GAP_S = 3.0
REST_CODES = {"1", "6"}         # sitting, lying
POS, NEG, EXCL = 1, 0, -1


# ---------------------------------------------------------------- features (cached)

TREMOR_WINDOW_S = 2    # set from --tremor-window; 2 or 4


def features_for(sid: str, side: str) -> pd.DataFrame:
    CACHE.mkdir(parents=True, exist_ok=True)
    suffix = "" if TREMOR_WINDOW_S == 2 else f"_tw{TREMOR_WINDOW_S}"
    cache = CACHE / f"{sid}_{side}_{sf.VERSION}{suffix}.pkl"
    if cache.exists():
        return pd.read_pickle(cache)
    s = pd.read_csv(DATA / "sensors" / f"{sid}_{side}.csv")
    acc = s[["ax", "ay", "az"]].to_numpy().T
    gyr = s[["gx", "gy", "gz"]].to_numpy().T
    df = sf.features_frame(sf.compute_features(s["timestamp_ms"].to_numpy(), acc, gyr, TREMOR_WINDOW_S))
    df.to_pickle(cache)
    return df


def to_windows(df: pd.DataFrame) -> list[sf.WindowFeatures]:
    rows = df.to_dict("records")
    for r in rows:
        r["context"] = sf.Context(r["context"])
    return [sf.WindowFeatures(**r) for r in rows]


# ---------------------------------------------------------------- labels per window

def interval_lookup(times_s: np.ndarray, starts, ends, values, default):
    """Value of the interval containing each time (intervals sorted, non-overlapping)."""
    out = np.full(len(times_s), default, dtype=object)
    starts, ends, values = np.asarray(starts), np.asarray(ends), np.asarray(values, dtype=object)
    if len(starts) == 0:
        return out
    idx = np.searchsorted(starts, times_s, side="right") - 1
    ok = (idx >= 0) & (times_s < ends[np.clip(idx, 0, None)])
    out[ok] = values[idx[ok]]
    return out


def window_truth(centres_s, sid, group, tremor_annotated, tremor_lab, mob_lab, subj):
    """Per-window truth (POS/NEG/EXCL) and whether the person is sitting or lying."""
    m = mob_lab[mob_lab.subject_id == sid].sort_values("start_s")
    mob = interval_lookup(centres_s, m.start_s, m.end_s, m.code.astype(str), "")
    resting = np.isin(mob.astype(str), list(REST_CODES))
    if tremor_annotated:
        t = tremor_lab[tremor_lab.subject_id == sid].sort_values("start_s")
        code = interval_lookup(centres_s, t.start_s, t.end_s, t.code.astype(str), "")
        truth = np.full(len(centres_s), EXCL)
        truth[np.isin(code.astype(str), ["1", "2", "3"])] = POS
        truth[np.isin(code.astype(str), ["0", "96", "98"])] = NEG
    else:
        # Inside the two annotated parts of the visit.
        r = subj.loc[sid]
        inside = ((centres_s >= r.part1_start_s) & (centres_s < r.part1_end_s)) | \
                 ((centres_s >= r.part2_start_s) & (centres_s < r.part2_end_s))
        truth = np.where(inside, NEG, EXCL)
    return truth, resting


def bouts(centres_s, truth):
    """Runs of positive windows as (start_s, end_s), gaps <= MERGE_GAP_S merged."""
    pos = centres_s[truth == POS]
    if len(pos) == 0:
        return []
    out = []
    s = e = pos[0]
    for x in pos[1:]:
        if x - e <= MERGE_GAP_S + 1:
            e = x
        else:
            out.append((s - 0.5, e + 0.5))
            s = e = x
    out.append((s - 0.5, e + 0.5))
    return out


# ---------------------------------------------------------------- scoring one recording

class Recording:
    """One wrist recording with its features and per-window truth, ready to score."""

    def __init__(self, sid, side, group, tremor_annotated):
        self.sid, self.side, self.group, self.annotated = sid, side, group, tremor_annotated
        subj = pd.read_csv(DATA / "subjects.csv").set_index("subject_id")
        tremor_lab = pd.read_csv(DATA / "labels_tremor.csv", dtype={"code": str})
        mob_lab = pd.read_csv(DATA / "labels_mobility.csv", dtype={"code": str})
        self.df = features_for(sid, side)
        self.windows = to_windows(self.df)
        self.centres = self.df.t_end_ms.to_numpy() / 1000.0 - 1.0
        self.truth, self.resting = window_truth(self.centres, sid, group, tremor_annotated,
                                                tremor_lab, mob_lab, subj)

    def exclude(self, start_s, end_s):
        """Leave a span out of scoring (e.g. the calibration rest)."""
        self.truth = self.truth.copy()
        self.truth[(self.centres >= start_s) & (self.centres < end_s)] = EXCL

    def label_at(self, t_s):
        i = int(np.clip(np.searchsorted(self.centres, t_s), 0, len(self.centres) - 1))
        return self.truth[i], self.resting[i]

    def score(self, params: ts.TremorParams, episodes=None, positive=None) -> dict:
        """Counts for one setting.

        episodes: precomputed TremorEpisodes (e.g. with feedback). positive: per-window decisions
        from a classifier, replacing the rule for both the episodes and the window metrics.
        """
        truth, centres = self.truth, self.centres
        long_bouts = [b for b in bouts(centres, truth) if b[1] - b[0] >= MIN_BOUT_S]
        pos_times = centres[truth == POS]
        if positive is None:
            positive = np.array([ts.window_positive(w, params) for w in self.windows])
            eps = ts.detect(self.windows, params) if episodes is None else episodes
        else:
            positive = np.asarray(positive, dtype=bool)
            eps = ts.detect(self.windows, params, positive) if episodes is None else episodes
        ep_iv = [(e.start_ms / 1000.0, e.end_ms / 1000.0, e.detected_ms / 1000.0) for e in eps]

        n_eval = n_true = n_false_rest = n_false = 0
        for s, e, d in ep_iv:
            lab, rest = self.label_at(d - 1.0)
            if lab == EXCL:
                continue
            n_eval += 1
            if ((pos_times >= s) & (pos_times <= e)).any():
                n_true += 1
            else:
                n_false += 1
                if rest and lab == NEG:
                    n_false_rest += 1
        hit, delays = 0, []
        for bs, be in long_bouts:
            over = [(s, e, d) for s, e, d in ep_iv if s <= be and e >= bs]
            if over:
                hit += 1
                inside = [d - bs for s, e, d in over if bs <= d <= be]
                if inside:
                    delays.append(min(inside))
        return dict(
            episodes=n_eval, true_episodes=n_true, false_episodes=n_false, false_rest=n_false_rest,
            bouts=len(long_bouts), bouts_hit=hit, delays=delays,
            win_tp=int((positive & (truth == POS)).sum()), win_fp=int((positive & (truth == NEG)).sum()),
            win_fn=int((~positive & (truth == POS)).sum()),
            rest_hours=float(((truth == NEG) & self.resting).sum()) / 3600.0,
            neg_hours=float((truth == NEG).sum()) / 3600.0,
            eval_hours=float((truth != EXCL).sum()) / 3600.0,
        )


def settings():
    """The grid as {key: TremorParams}; keys are (T, prominence[, max_slow_ratio, max_slow4_dps])."""
    if not REST_GATE:
        return {(T, prom): ts.TremorParams(threshold_dps=T, min_prominence=prom)
                for T, prom in itertools.product(T_GRID, PROM_GRID)}
    return {(T, prom, r, a): ts.TremorParams(threshold_dps=T, min_prominence=prom, max_slow_ratio=r,
                                             max_slow4_dps=a)
            for T, prom, r, a in itertools.product(T_GRID, PROM_GRID, RATIO_GRID, SLOW4_GRID)}


def score_recording(task):
    rec = Recording(*task)
    results = {key: rec.score(p) for key, p in settings().items()}
    return rec.sid, rec.side, rec.group, rec.annotated, results


# ---------------------------------------------------------------- aggregation

def total(items):
    """Sum the counts of several per-recording results (one setting each)."""
    out = dict(episodes=0, true_episodes=0, false_episodes=0, false_rest=0, bouts=0, bouts_hit=0,
               delays=[], win_tp=0, win_fp=0, win_fn=0, rest_hours=0.0, neg_hours=0.0, eval_hours=0.0)
    for x in items:
        for k in out:
            out[k] = out[k] + x[k]
    return out


def summary(t):
    prec = t["true_episodes"] / t["episodes"] if t["episodes"] else float("nan")
    rec = t["bouts_hit"] / t["bouts"] if t["bouts"] else float("nan")
    f1 = 2 * prec * rec / (prec + rec) if prec + rec > 0 else 0.0
    wp = t["win_tp"] / (t["win_tp"] + t["win_fp"]) if t["win_tp"] + t["win_fp"] else float("nan")
    wr = t["win_tp"] / (t["win_tp"] + t["win_fn"]) if t["win_tp"] + t["win_fn"] else float("nan")
    wf1 = 2 * wp * wr / (wp + wr) if wp + wr > 0 else 0.0
    return dict(
        event_precision=prec, event_recall=rec, event_f1=f1,
        false_per_hour_rest=t["false_rest"] / t["rest_hours"] if t["rest_hours"] else float("nan"),
        false_per_hour=t["false_episodes"] / t["neg_hours"] if t["neg_hours"] else float("nan"),
        onset_delay_median_s=float(np.median(t["delays"])) if t["delays"] else float("nan"),
        window_precision=wp, window_recall=wr, window_f1=wf1,
        bouts=t["bouts"], bouts_hit=t["bouts_hit"], episodes=t["episodes"],
        rest_hours=round(t["rest_hours"], 2), negative_hours=round(t["neg_hours"], 2),
    )


def choose(train, grid):
    best, best_key = None, None
    for key in grid:
        s = summary(total([t[key] for t in train]))
        if not (s["false_per_hour_rest"] < FALSE_PER_HOUR_LIMIT):
            continue
        if best is None or s["event_f1"] > best["event_f1"]:
            best, best_key = s, key
    return best_key or max(grid)  # nothing meets the limit: the strictest setting


def _init_worker(tremor_window_s, prom_grid, t_grid, rest_gate):
    global TREMOR_WINDOW_S, PROM_GRID, T_GRID, REST_GATE
    TREMOR_WINDOW_S, PROM_GRID, T_GRID, REST_GATE = tremor_window_s, prom_grid, t_grid, rest_gate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=6)
    ap.add_argument("--tremor-window", type=int, default=2, choices=[2, 4],
                    help="seconds of gyroscope for the tremor spectrum")
    ap.add_argument("--prominence", type=float, nargs="*", help="prominence grid (default PROM_GRID)")
    ap.add_argument("--threshold", type=float, nargs="*", help="threshold grid in deg/s (default T_GRID)")
    ap.add_argument("--rest-gate", action="store_true", help="also tune the rest gate (RATIO_GRID x SLOW4_GRID)")
    args = ap.parse_args()
    global TREMOR_WINDOW_S, PROM_GRID, T_GRID, REST_GATE
    TREMOR_WINDOW_S = args.tremor_window
    REST_GATE = args.rest_gate
    if args.prominence:
        PROM_GRID = args.prominence
    if args.threshold:
        T_GRID = args.threshold
    subj = pd.read_csv(DATA / "subjects.csv")
    tasks = []
    for r in subj.itertuples():
        if r.tremor_annotated:
            tasks.append((r.subject_id, "RW" if r.tremor_side == "right" else "LW", r.group, True))
        else:
            tasks += [(r.subject_id, side, r.group, False) for side in ("LW", "RW")]
    t0 = time.time()
    with ProcessPoolExecutor(args.jobs, initializer=_init_worker,
                             initargs=(TREMOR_WINDOW_S, PROM_GRID, T_GRID, REST_GATE)) as ex:
        recs = []
        for sid, side, group, ann, res in ex.map(score_recording, tasks):
            recs.append(dict(sid=sid, side=side, group=group, annotated=ann, res=res))
            print(f"  {sid} {side} done ({time.time() - t0:.0f} s)", flush=True)

    grid = list(settings())
    subjects = sorted({r["sid"] for r in recs})
    held = []
    picks = {}
    for sid in subjects:
        train = [r["res"] for r in recs if r["sid"] != sid]
        key = choose(train, grid)
        picks[sid] = key
        for r in recs:
            if r["sid"] == sid:
                held.append(dict(r, key=key))

    def held_total(sel):
        return total([r["res"][r["key"]] for r in held if sel(r)])

    groups = {
        "all": lambda r: True,
        "tremor_subjects_affected_wrist": lambda r: r["annotated"],
        "healthy_controls": lambda r: r["group"] == "HC",
        "pd_without_tremor_annotations": lambda r: r["group"] == "PD" and not r["annotated"],
    }
    loso = {name: summary(held_total(sel)) for name, sel in groups.items()}

    # In-sample view: every setting on all subjects (to see the trade-off; optimistic).
    table = []
    for key in grid:
        s_all = summary(total([r["res"][key] for r in recs]))
        s_hc = summary(total([r["res"][key] for r in recs if r["group"] == "HC"]))
        gate = dict(max_slow_ratio=key[2], max_slow4_dps=key[3]) if len(key) == 4 else {}
        table.append(dict(threshold_dps=key[0], min_prominence=key[1], **gate, **{k: s_all[k] for k in (
            "event_precision", "event_recall", "event_f1", "false_per_hour_rest", "false_per_hour",
            "onset_delay_median_s", "window_f1")}, hc_false_per_hour=s_hc["false_per_hour"]))
    table = pd.DataFrame(table).sort_values("event_f1", ascending=False)

    per_subject = []
    for r in held:
        if r["annotated"]:
            s = summary(r["res"][r["key"]])
            per_subject.append(dict(subject=r["sid"], wrist=r["side"], setting=str(r["key"]), **{k: s[k] for k in (
                                        "bouts", "bouts_hit", "episodes", "event_precision", "event_recall",
                                        "event_f1", "false_per_hour_rest", "onset_delay_median_s")}))

    OUT.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M") + f"_tw{TREMOR_WINDOW_S}" + ("_gate" if REST_GATE else "")
    table.to_csv(OUT / f"tremor_stream_grid_{stamp}.csv", index=False)
    pd.DataFrame(per_subject).to_csv(OUT / f"tremor_stream_per_subject_{stamp}.csv", index=False)
    report = dict(version=ts.VERSION, features=sf.VERSION, tremor_window_s=TREMOR_WINDOW_S,
                  leave_one_subject_out=loso,
                  picks={k: list(v) for k, v in picks.items()},
                  gate=dict(event_f1_target=0.80, false_per_hour_rest_target=FALSE_PER_HOUR_LIMIT))
    (OUT / f"tremor_stream_report_{stamp}.json").write_text(json.dumps(report, indent=2, default=float))

    pd.set_option("display.width", 200)
    print("\nLeave-one-subject-out:")
    print(pd.DataFrame(loso).T[["event_precision", "event_recall", "event_f1", "false_per_hour_rest",
                                "false_per_hour", "onset_delay_median_s", "window_f1", "bouts",
                                "episodes", "rest_hours", "negative_hours"]].round(3).to_string())
    print("\nPer tremor subject (held out):")
    print(pd.DataFrame(per_subject).round(3).to_string(index=False))
    print("\nBest settings in-sample (top 8):")
    print(table.head(8).round(3).to_string(index=False))
    print(f"\nreports in {OUT}")


if __name__ == "__main__":
    main()
