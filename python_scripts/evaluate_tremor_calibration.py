"""Does the personal threshold help? Simulated calibration on Parkinson@Home (follow-up to T4).

On the watch, calibration is the 20 s tremor check with the hand on the lap. From it,
tremor_stream.calibrate sets the person's threshold:
    T = max(T_MIN, rest_multiplier x the 90th percentile of tremor_dps at rest)
    capped at tremor_share x the check's band strength if the check found a tremor,
and narrows the frequency band around the tremor's Hz.

Parkinson@Home has no calibration, so we simulate one: we take 20 s while the person sat with a
quiet hand (sitting, >= 80 % of windows still or at rest, assessable tremor labels) and run the
tremor-1.0 check on the raw gyroscope there. Each recording gets N_TRIALS calibrations at
different random moments, because a real calibration may or may not catch the tremor. The
20 s are left out of scoring.

Scenarios:
  calibrated     personal T from calibration only
  + feedback     also "Not shaking" answered on half of the false episodes (T +15 % each, up to
                 1.5 x the starting T); missed-tremor feedback isn't simulated, which is conservative

Result (2026-11-10, 6 calibration moments per recording): no calibration strategy beat the
population threshold (event F1 on tremor subjects 0.77); capped feedback cut false episodes at
rest in PD without tremor from 0.45 to 0.24 per hour for 0.02 of F1.

Settings tuned leave-one-subject-out as in T4: rest_multiplier, tremor_share, min_prominence,
best event F1 under 2 false episodes per hour at rest.

Usage:
    python_scripts/.venv/bin/python python_scripts/evaluate_tremor_calibration.py [--jobs 7] [--trials 6]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_tremor_parkinson_home as ev  # noqa: E402
from daedalus_watch_kit import tremor_check as tc  # noqa: E402
from daedalus_watch_kit import tremor_stream as ts  # noqa: E402
from daedalus_watch_kit.stream_features import Context  # noqa: E402

CAL_S = 20
FEEDBACK_SHARE = 0.5
PROM = 16.0                 # tuned in T4


def _p90(rest):
    v = [w.tremor_dps for w in rest if w.context in (Context.REST_TREMOR_POSSIBLE, Context.STILL)]
    return float(np.percentile(v, 90)) if v else None


def strategy_noise(mult, share):
    """The design as written: T from the rest noise, capped by a found tremor."""
    def f(rest, check):
        found = check.outcome == "tremor"
        return ts.calibrate(rest, check.band_dps if found else None, check.frequency_hz if found else None,
                            rest_multiplier=mult, tremor_share=share, min_prominence=PROM)
    return f


def strategy_population(t_pop, share):
    """Population T; a tremor found at calibration lowers it to share x its strength."""
    def f(rest, check):
        t = t_pop
        hz = None
        if check.outcome == "tremor":
            t = min(t, share * check.band_dps)
            hz = check.frequency_hz
        return ts.TremorParams(threshold_dps=float(np.clip(t, ts.T_MIN, ts.T_MAX)), person_hz=hz,
                               min_prominence=PROM)
    return f


def strategy_bounded(t_pop, mult, share):
    """T from the rest noise but kept within 0.5-1.5 x the population T, then the tremor cap."""
    def f(rest, check):
        p90 = _p90(rest)
        t = t_pop if p90 is None else float(np.clip(mult * p90, 0.5 * t_pop, 1.5 * t_pop))
        hz = None
        if check.outcome == "tremor":
            t = min(t, share * check.band_dps)
            hz = check.frequency_hz
        return ts.TremorParams(threshold_dps=float(np.clip(t, ts.T_MIN, ts.T_MAX)), person_hz=hz,
                               min_prominence=PROM)
    return f


def strategy_fixed(t_pop):
    """No calibration (T4 baseline), scored on the same recordings minus the same 20 s."""
    return lambda rest, check: ts.TremorParams(threshold_dps=t_pop, min_prominence=PROM)


FAMILIES = {
    "population threshold (no calibration)": {("fixed", t): strategy_fixed(t) for t in (4.0, 6.0, 8.0)},
    "calibration as designed (rest noise)": {("noise", m, sh): strategy_noise(m, sh)
                                             for m in (2.0, 3.0, 4.0) for sh in (0.5, 0.8)},
    "population, lowered by a found tremor": {("pop", t, sh): strategy_population(t, sh)
                                              for t in (4.0, 6.0, 8.0) for sh in (0.5, 0.8)},
    "rest noise bounded 0.5-1.5 x population": {("bounded", t, m, sh): strategy_bounded(t, m, sh)
                                                for t in (4.0, 6.0, 8.0) for m in (3.0, 4.0) for sh in (0.5, 0.8)},
}
STRATEGIES = {k: f for fam in FAMILIES.values() for k, f in fam.items()}


def calibration_starts(rec: ev.Recording, n: int, seed: int) -> list[float]:
    """Up to n start times (s) of 20 s quiet sitting with assessable labels."""
    ctx = np.array([w.context.value for w in rec.windows])   # values: numpy mangles str-enum members
    quiet = np.isin(ctx, [Context.REST_TREMOR_POSSIBLE.value, Context.STILL.value])
    ok = quiet & rec.resting & (rec.truth != ev.EXCL)
    # Window i covers centre c_i; a start s needs windows with centres in [s+1, s+CAL_S-1].
    good = []
    for i in range(0, len(ok) - CAL_S, 5):
        span = ok[i:i + CAL_S - 2]
        if len(span) == CAL_S - 2 and span.mean() >= 0.8:
            good.append(rec.centres[i] - 1.0)
    rng = np.random.default_rng(seed)
    if len(good) <= n:
        return good
    return sorted(rng.choice(good, size=n, replace=False).tolist())


def run_check(raw: pd.DataFrame, start_s: float) -> tc.CheckResult:
    t = raw.timestamp_ms.to_numpy()
    m = (t >= start_s * 1000) & (t < (start_s + CAL_S) * 1000)
    return tc.analyze(t[m], raw.gx.to_numpy()[m], raw.gy.to_numpy()[m], raw.gz.to_numpy()[m])


def detect_with_feedback(rec: ev.Recording, params: ts.TremorParams, rng) -> list[ts.TremorEpisode]:
    pos_times = rec.centres[rec.truth == ev.POS]
    det = ts.TremorDetector(params)
    out = []
    for w in rec.windows:
        for kind, ep in det.update(w):
            if kind != "end":
                continue
            out.append(ep)
            s, e = ep.start_ms / 1000.0, ep.end_ms / 1000.0
            false = not ((pos_times >= s) & (pos_times <= e)).any()
            if false and rng.random() < FEEDBACK_SHARE:
                det.params = ts.after_feedback(det.params, false_positive=True, base_dps=params.threshold_dps)
    out += [ep for _, ep in det.finish(rec.windows[-1].t_end_ms)]
    return out


def score_task(args):
    task, trials = args
    sid, side, group, annotated = task
    seed = abs(hash((sid, side))) % (2 ** 32)
    base = ev.Recording(*task)
    raw = pd.read_csv(ev.DATA / "sensors" / f"{sid}_{side}.csv")
    starts = calibration_starts(base, trials, seed)
    out = []   # one dict per trial: {setting: counts}, plus info
    for k, start in enumerate(starts):
        rec = ev.Recording.__new__(ev.Recording)
        rec.__dict__.update(base.__dict__)
        rest = [w for w in rec.windows if start + 2 <= w.t_end_ms / 1000.0 <= start + CAL_S]
        check = run_check(raw, start)
        rec.exclude(start, start + CAL_S)
        found = check.outcome == "tremor"
        res = {key: rec.score(f(rest, check)) for key, f in STRATEGIES.items()}
        out.append(dict(trial=k, start_s=start, check=check, found=found, res=res, rest=rest))
    return sid, side, group, annotated, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=7)
    ap.add_argument("--trials", type=int, default=6)
    args = ap.parse_args()

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
        for sid, side, group, ann, trials in ex.map(score_task, [(t, args.trials) for t in tasks]):
            recs.append(dict(sid=sid, side=side, group=group, annotated=ann, trials=trials))
            print(f"  {sid} {side}: {len(trials)} calibrations, "
                  f"{sum(t['found'] for t in trials)} found a tremor ({time.time() - t0:.0f} s)", flush=True)

    groups = {
        "tremor subjects": lambda r: r["annotated"],
        "healthy controls": lambda r: r["group"] == "HC",
        "PD without tremor": lambda r: r["group"] == "PD" and not r["annotated"],
        "all": lambda r: True,
    }
    subjects = sorted({r["sid"] for r in recs})
    rows, per_subject, chosen = [], [], {}
    rng = np.random.default_rng(7)
    for fam, strategies in FAMILIES.items():
        keys = list(strategies)
        held = []
        for sid in subjects:
            train = [t["res"] for r in recs if r["sid"] != sid for t in r["trials"]]
            key = ev.choose(train, keys)
            chosen.setdefault(fam, set()).add(key)
            held += [(r, key) for r in recs if r["sid"] == sid]
        for g, sel in groups.items():
            s = ev.summary(ev.total([t["res"][key] for r, key in held if sel(r) for t in r["trials"]]))
            rows.append(dict(strategy=fam, group=g, **{k: s[k] for k in (
                "event_precision", "event_recall", "event_f1", "false_per_hour_rest", "onset_delay_median_s")}))
        for r, key in held:
            if r["annotated"]:
                s = ev.summary(ev.total([t["res"][key] for t in r["trials"]]))
                ths = [strategies[key](t["rest"], t["check"]).threshold_dps for t in r["trials"]]
                per_subject.append(dict(strategy=fam, subject=r["sid"],
                                        found_tremor=sum(t["found"] for t in r["trials"]),
                                        threshold_median=round(float(np.median(ths)), 1),
                                        event_recall=s["event_recall"], event_f1=s["event_f1"],
                                        false_per_hour_rest=s["false_per_hour_rest"]))
        # Feedback ("Not shaking" on half the false episodes, capped) at the chosen settings.
        items = {g: [] for g in groups}
        for r, key in held:
            rec0 = ev.Recording(r["sid"], r["side"], r["group"], r["annotated"])
            for t in r["trials"]:
                rec = ev.Recording.__new__(ev.Recording)
                rec.__dict__.update(rec0.__dict__)
                rec.exclude(t["start_s"], t["start_s"] + CAL_S)
                p = strategies[key](t["rest"], t["check"])
                c = rec.score(p, episodes=detect_with_feedback(rec, p, rng))
                for g, sel in groups.items():
                    if sel(r):
                        items[g].append(c)
        for g, v in items.items():
            s = ev.summary(ev.total(v))
            rows.append(dict(strategy=fam + " + feedback", group=g, **{k: s[k] for k in (
                "event_precision", "event_recall", "event_f1", "false_per_hour_rest", "onset_delay_median_s")}))
        print(f"  {fam}: scored ({time.time() - t0:.0f} s)", flush=True)

    table = pd.DataFrame(rows)
    ev.OUT.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M")
    table.to_csv(ev.OUT / f"tremor_calibration_{stamp}.csv", index=False)
    pd.DataFrame(per_subject).to_csv(ev.OUT / f"tremor_calibration_per_subject_{stamp}.csv", index=False)
    pd.set_option("display.width", 220)
    print("\nLeave-one-subject-out, all calibration moments pooled:")
    for g in groups:
        print(f"\n[{g}]")
        print(table[table.group == g].drop(columns="group").round(3).to_string(index=False))
    print("\nPer tremor subject:")
    print(pd.DataFrame(per_subject).pivot(index="subject", columns="strategy", values="event_f1").round(2).to_string())
    print("\nThresholds (median per subject):")
    print(pd.DataFrame(per_subject).pivot(index="subject", columns="strategy", values="threshold_median").to_string())
    print("\nSettings chosen:", {k: sorted(v) for k, v in chosen.items()})
    print(f"reports in {ev.OUT}")


if __name__ == "__main__":
    main()
