"""Would a trained model beat the tremor rules? Gradient-boosted trees vs tremor-stream-1.0.

Same data, labels, episode logic and scoring as evaluate_tremor_parkinson_home.py. Only the
per-window decision changes: a classifier's probability >= a cut-off instead of the rule.

Models (scikit-learn HistGradientBoostingClassifier, small: 200 trees, depth 4, so it could
run on a watch through LiteRT / Core ML or as plain if-else code):
  rule features   the 13 window features of stream_features + the context
  + engineered    adds ratios (slow / tremor, band share, acc per rotation), log strength, and
                  causal history over the last 4 windows (median strength, rhythm spread,
                  mean prominence, share of rest windows)

Training: windows labelled tremor (codes 1-3) or not (0, 96, 98, healthy controls,
PD without tremor labels), all negatives of tremor subjects plus 1 in 10 of the others.
Leave one subject out: one model per held-out subject.

The cut-off: out-of-fold probabilities of the other subjects pick it (best event F1 with
< 2 false episodes per hour at rest). Those probabilities come from models that saw the
held-out subject, a small leak in the cut-off only; the model never sees the held-out
subject's windows.

Usage:
    python_scripts/.venv/bin/python python_scripts/evaluate_tremor_model.py [--jobs 7]
"""

from __future__ import annotations

import argparse
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_tremor_parkinson_home as ev  # noqa: E402
from daedalus_watch_kit import tremor_stream as ts  # noqa: E402

BASE = ["tremor_dps", "peak_hz", "prominence", "slow_dps", "motion_dps", "mean_rotation_dps", "acc_std",
        "gravity_change_deg", "freeze_index", "cadence_spm", "step_regularity", "arm_swing_dps"]
CONTEXTS = ["still", "rest", "active", "walking", "turning"]
CUTOFFS = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]
NEG_SAMPLE = 0.1
SEED = 0


def engineer(df: pd.DataFrame, extra: bool) -> pd.DataFrame:
    X = df[BASE].copy()
    for c in CONTEXTS:
        X[f"ctx_{c}"] = (df.context == c).astype(float)
    if not extra:
        return X
    eps = 1e-6
    X["slow_ratio"] = df.slow_dps / (df.tremor_dps + eps)
    X["slow4_ratio"] = df.arm_swing_dps / (df.tremor_dps + eps)
    X["band_share"] = (df.tremor_dps / (df.motion_dps + eps)) ** 2
    X["acc_per_rotation"] = df.acc_std / (df.tremor_dps + eps)
    X["log_tremor"] = np.log10(df.tremor_dps + 0.1)
    roll = df[["tremor_dps", "peak_hz", "prominence"]].rolling(4, min_periods=1)
    X["tremor_med4"] = roll.tremor_dps.median()
    X["hz_std4"] = df.peak_hz.rolling(4, min_periods=1).std().fillna(0)
    X["prom_mean4"] = roll.prominence.mean()
    X["rest_share4"] = df.context.isin(["rest", "still"]).astype(float).rolling(4, min_periods=1).mean()
    return X


def tasks():
    subj = pd.read_csv(ev.DATA / "subjects.csv")
    out = []
    for r in subj.itertuples():
        if r.tremor_annotated:
            out.append((r.subject_id, "RW" if r.tremor_side == "right" else "LW", r.group, True))
        else:
            out += [(r.subject_id, s, r.group, False) for s in ("LW", "RW")]
    return out


def load(task):
    rec = ev.Recording(*task)
    return task, rec.df, rec.truth


def fit_predict(args):
    held, data, extra = args
    from sklearn.ensemble import HistGradientBoostingClassifier
    rng = np.random.default_rng(SEED)
    Xs, ys = [], []
    for (sid, side, group, ann), X, y in data:
        if sid == held:
            continue
        m = y != ev.EXCL
        keep = m & ((y == ev.POS) | ann | (rng.random(len(y)) < NEG_SAMPLE))
        Xs.append(X[keep])
        ys.append((y[keep] == ev.POS).astype(int))
    model = HistGradientBoostingClassifier(max_iter=200, max_depth=4, learning_rate=0.1,
                                           l2_regularization=1.0, random_state=SEED)
    model.fit(pd.concat(Xs), np.concatenate(ys))
    return held, {(t[0], t[1]): model.predict_proba(X)[:, 1] for t, X, _ in data if t[0] == held}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=7)
    args = ap.parse_args()
    all_tasks = tasks()
    t0 = time.time()
    with ProcessPoolExecutor(args.jobs) as ex:
        loaded = list(ex.map(load, all_tasks))
    recs = {(t[0], t[1]): ev.Recording.__new__(ev.Recording) for t, _, _ in loaded}
    for t in all_tasks:
        r = recs[(t[0], t[1])]
        r.__dict__.update(ev.Recording(*t).__dict__)
    print(f"loaded {len(loaded)} recordings ({time.time() - t0:.0f} s)")
    subjects = sorted({t[0] for t in all_tasks})
    groups = {
        "tremor subjects": lambda t: t[3],
        "healthy controls": lambda t: t[2] == "HC",
        "PD without tremor labels": lambda t: t[2] == "PD" and not t[3],
        "all": lambda t: True,
    }
    params = ts.TremorParams()   # only used for its band/prominence in the rule baseline
    rows, per_subject = [], {}

    # Rule baseline at the defaults (tuned on all subjects in T4, so slightly optimistic).
    rule = {(t[0], t[1]): recs[(t[0], t[1])].score(params) for t in all_tasks}
    for g, sel in groups.items():
        s = ev.summary(ev.total([rule[(t[0], t[1])] for t in all_tasks if sel(t)]))
        rows.append(dict(model="rules (v1 defaults)", group=g, **pick(s)))
    per_subject["rules"] = {t[0]: ev.summary(rule[(t[0], t[1])])["event_f1"] for t in all_tasks if t[3]}

    importances = None
    for extra in (False, True):
        name = "boosted trees + engineered" if extra else "boosted trees, rule features"
        data = [(t, engineer(df, extra), y) for t, df, y in loaded]
        with ProcessPoolExecutor(args.jobs) as ex:
            probs = {}
            for held, p in ex.map(fit_predict, [(s, data, extra) for s in subjects]):
                probs.update(p)
        print(f"  {name}: {len(subjects)} models ({time.time() - t0:.0f} s)", flush=True)
        # Scores at every cut-off, per recording.
        res = {(t[0], t[1]): {c: recs[(t[0], t[1])].score(params, positive=probs[(t[0], t[1])] >= c)
                              for c in CUTOFFS} for t in all_tasks}
        held = []
        for sid in subjects:
            train = [res[(t[0], t[1])] for t in all_tasks if t[0] != sid]
            c = ev.choose(train, CUTOFFS)
            held += [(t, c) for t in all_tasks if t[0] == sid]
        for g, sel in groups.items():
            s = ev.summary(ev.total([res[(t[0], t[1])][c] for t, c in held if sel(t)]))
            rows.append(dict(model=name, group=g, **pick(s)))
        per_subject[name] = {t[0]: ev.summary(res[(t[0], t[1])][c])["event_f1"] for t, c in held if t[3]}
        if extra:
            importances = permutation_summary(data, subjects)

    table = pd.DataFrame(rows)
    stamp = time.strftime("%Y%m%d_%H%M")
    ev.OUT.mkdir(parents=True, exist_ok=True)
    table.to_csv(ev.OUT / f"tremor_model_{stamp}.csv", index=False)
    pd.set_option("display.width", 200)
    for g in groups:
        print(f"\n[{g}]")
        print(table[table.group == g].drop(columns="group").round(3).to_string(index=False))
    print("\nEvent F1 per tremor subject:")
    print(pd.DataFrame(per_subject).round(2).to_string())
    if importances is not None:
        print("\nMost useful features (permutation, one model trained on all but pd18, scored on pd18 + controls):")
        print(importances.head(12).round(4).to_string())
    print(f"\nreports in {ev.OUT}")


def pick(s):
    return {k: s[k] for k in ("event_precision", "event_recall", "event_f1", "false_per_hour_rest",
                              "onset_delay_median_s", "window_f1")}


def permutation_summary(data, subjects):
    """A rough view of which features the model leans on (window-level average precision)."""
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.inspection import permutation_importance
    rng = np.random.default_rng(SEED)
    held = "pd18"
    Xs, ys, Xt, yt = [], [], [], []
    for (sid, side, group, ann), X, y in data:
        m = y != ev.EXCL
        if sid == held or (group == "HC" and sid in ("hc01", "hc02", "hc03")):
            Xt.append(X[m]); yt.append((y[m] == ev.POS).astype(int))
            continue
        keep = m & ((y == ev.POS) | ann | (rng.random(len(y)) < NEG_SAMPLE))
        Xs.append(X[keep]); ys.append((y[keep] == ev.POS).astype(int))
    model = HistGradientBoostingClassifier(max_iter=200, max_depth=4, learning_rate=0.1,
                                           l2_regularization=1.0, random_state=SEED)
    model.fit(pd.concat(Xs), np.concatenate(ys))
    Xt, yt = pd.concat(Xt), np.concatenate(yt)
    sample = rng.choice(len(yt), size=min(40000, len(yt)), replace=False)
    r = permutation_importance(model, Xt.iloc[sample], yt[sample], scoring="average_precision",
                               n_repeats=3, random_state=SEED)
    return pd.Series(r.importances_mean, index=Xt.columns).sort_values(ascending=False)


if __name__ == "__main__":
    main()
