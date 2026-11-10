"""Why are tremor-stream-1.0's "false" episodes false? (follow-up to T4)

We can't see the videos, so each false episode is described with what the data shows:
  - the video label at its start and over its span (0 none, 96/98 arm activity, ...)
  - how far it is from the nearest labelled tremor
  - its frequency compared with the person's labelled tremor frequency
  - its strength and prominence
  - for tremor subjects, whether the other wrist shows the same rhythm at the same time

and sorted into likely causes, in this order:
  boundary       starts within BOUNDARY_S of a labelled tremor bout: timing of the label edges
  same_rhythm    at the person's own tremor frequency (± SAME_HZ) with a clear peak, label "none":
                 possibly a tremor the annotators didn't mark (slight tremor, or a body part
                 they weren't watching)
  arm_activity   during a 96/98 label (periodic or other arm activity)
  other

Healthy controls get the same description; their episodes show what false alarms look like when
no tremor exists at all.

Settings: the T4 defaults (T 6 deg/s, prominence 16, 2 s window).
Writes dataset/reports/false_tremor_episodes_<stamp>.csv and prints a summary.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_tremor_parkinson_home as ev  # noqa: E402
from daedalus_watch_kit import tremor_stream as ts  # noqa: E402

BOUNDARY_S = 30.0
SAME_HZ = 0.75
PARAMS = ts.TremorParams(threshold_dps=6.0, min_prominence=16.0)


def label_codes(sid, start_s, end_s, lab):
    l = lab[(lab.subject_id == sid) & (lab.end_s > start_s) & (lab.start_s < end_s)]
    return "/".join(sorted(set(l.code.astype(str)))) if len(l) else ""


def main():
    subj = pd.read_csv(ev.DATA / "subjects.csv")
    lab = pd.read_csv(ev.DATA / "labels_tremor.csv", dtype={"code": str})
    rows = []
    for r in subj.itertuples():
        sides = ["RW" if r.tremor_side == "right" else "LW"] if r.tremor_annotated else ["LW", "RW"]
        for side in sides:
            rec = ev.Recording(r.subject_id, side, r.group, bool(r.tremor_annotated))
            pos_times = rec.centres[rec.truth == ev.POS]
            df = rec.df
            # The person's labelled tremor frequency: median peak over positive windows.
            pos_mask = rec.truth == ev.POS
            person_hz = float(df.peak_hz[pos_mask].median()) if pos_mask.any() else np.nan
            other = None
            if r.tremor_annotated:
                other = ev.features_for(r.subject_id, "LW" if side == "RW" else "RW")
            for ep in ts.detect(rec.windows, PARAMS):
                s, e, d = ep.start_ms / 1000, ep.end_ms / 1000, ep.detected_ms / 1000
                lab_at, rest = rec.label_at(d - 1.0)
                if lab_at == ev.EXCL:
                    continue
                if ((pos_times >= s) & (pos_times <= e)).any():
                    continue  # true episode
                span = (df.t_end_ms / 1000 > s + 2) & (df.t_end_ms / 1000 <= e)
                w = df[span]
                dist = float(np.min(np.abs(pos_times - d))) if len(pos_times) else np.inf
                codes = label_codes(r.subject_id, s, e, lab) if r.tremor_annotated else ""
                other_hz = other_dps = np.nan
                if other is not None:
                    o = other[(other.t_end_ms / 1000 > s + 2) & (other.t_end_ms / 1000 <= e)]
                    if len(o):
                        other_hz, other_dps = float(o.peak_hz.median()), float(o.tremor_dps.median())
                same = (not np.isnan(person_hz)) and abs(ep.hz - person_hz) <= SAME_HZ
                if r.tremor_annotated and dist <= BOUNDARY_S:
                    cause = "boundary"
                elif r.tremor_annotated and same and set(codes.split("/")) <= {"0", ""}:
                    cause = "same_rhythm"
                elif r.tremor_annotated and ({"96", "98"} & set(codes.split("/"))):
                    cause = "arm_activity"
                else:
                    cause = "other"
                rows.append(dict(
                    subject=r.subject_id, wrist=side, group=r.group, tremor_subject=bool(r.tremor_annotated),
                    start_s=round(s, 1), duration_s=round(e - s, 1), sitting=bool(rest),
                    labels=codes, nearest_tremor_s=round(dist, 1) if np.isfinite(dist) else None,
                    hz=ep.hz, person_hz=round(person_hz, 2) if not np.isnan(person_hz) else None,
                    median_dps=round(float(w.tremor_dps.median()), 1) if len(w) else None,
                    median_prominence=round(float(w.prominence.median()), 1) if len(w) else None,
                    other_wrist_hz=round(other_hz, 2) if not np.isnan(other_hz) else None,
                    other_wrist_dps=round(other_dps, 1) if not np.isnan(other_dps) else None,
                    cause=cause,
                ))
        print(f"  {r.subject_id} done", flush=True)

    out = pd.DataFrame(rows)
    ev.OUT.mkdir(parents=True, exist_ok=True)
    path = ev.OUT / f"false_tremor_episodes_{time.strftime('%Y%m%d_%H%M')}.csv"
    out.to_csv(path, index=False)

    pd.set_option("display.width", 200)
    t = out[out.tremor_subject]
    print(f"\nFalse episodes in tremor subjects (affected wrist): {len(t)}")
    print(t.cause.value_counts().to_string())
    print("\nBy subject and cause:")
    print(pd.crosstab(t.subject, t.cause, margins=True).to_string())
    print("\nMedians by cause:")
    print(t.groupby("cause")[["duration_s", "median_dps", "median_prominence", "nearest_tremor_s"]]
          .median().round(1).to_string())
    print("\nFrequency gap to the person's tremor, |hz - person_hz|:")
    print((t.hz - t.person_hz).abs().describe().round(2).to_string())
    print("\nOther wrist during same_rhythm episodes (same rhythm within 0.75 Hz?):")
    sr = t[t.cause == "same_rhythm"]
    if len(sr):
        print(f"  {((sr.other_wrist_hz - sr.hz).abs() <= SAME_HZ).mean():.0%} of {len(sr)}")
    c = out[out.group == "HC"]
    print(f"\nHealthy controls: {len(c)} false episodes; median {c.median_dps.median():.1f} deg/s, "
          f"prominence {c.median_prominence.median():.0f}, Hz {c.hz.median():.1f} "
          f"(IQR {c.hz.quantile(.25):.1f}-{c.hz.quantile(.75):.1f}), {c.sitting.mean():.0%} while sitting")
    p = out[(out.group == "PD") & ~out.tremor_subject]
    print(f"PD without tremor labels: {len(p)} false episodes; median {p.median_dps.median():.1f} deg/s, "
          f"Hz {p.hz.median():.1f}, {p.sitting.mean():.0%} while sitting; "
          f"by subject: {p.subject.value_counts().head(5).to_dict()}")
    print(f"\nwritten {path}")


if __name__ == "__main__":
    main()
