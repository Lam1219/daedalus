"""Home pilot analysis (docs/product/plan.md, B7): timings around each "I'm stuck" press, and the
H1, H3 and H12 verdicts against the plan's thresholds.

One press file is JSON, written by the watch app (version pilot-1.0):

    {
      "version": "pilot-1.0",
      "participant": "P03",            # pilot code, never a name
      "press_index": 12, "day_index": 9,
      "place": "wrist" | "earbuds" | "pocket",
      "earbuds_connected": true, "phone_carried": true,
      "pace_spm": 104,
      "press_ms": 1794570000000,       # epoch ms
      "beat_start_ms": 1794570000400,  # when the beat actually began playing, or null if never confirmed
      "beat_stop_ms": 1794570009000,   # null if the app stopped before it could write this
      "checkin": "helped" | "not_helped" | "already_moving" | "not_felt" | null,
      "motion_partial": false,         # optional, iOS: the background recorder never covered the window
      "recovered": false,              # optional, Android: rebuilt after the app died mid-press
                                       # either flag true: the press keeps its check-in, no timings
      "samples": {                     # the watch's motion, from 30 s before to 60 s after the press
        "t_ms": [...],                 # epoch ms, about 50 Hz
        "acc": [[...], [...], [...]],  # m/s², gravity included
        "gyr": [[...], [...], [...]]   # rad/s, or null when the watch can't supply rotation
      }                                # (Apple Watch's background recorder is accelerometer only).
                                       # Walking is judged from acceleration alone; only "turning"
                                       # needs rotation, and turning counts as walking here.
    }

Timings come from the kit's walking classifier (stream_features), one window a second:
- walking stopped: the end of the last walking (or turning) window that ends at or before the press,
  less 1 s (STOP_LAG_MS: the window still reads as walking for about a second after steps stop),
  counted only if it is at most 10 s before the press (MAX_STOP_BEFORE_PRESS_MS);
- steady steps again: the first run of 3 walking windows that ends at least 2 s after the beat
  started (so the window holds mostly post-beat motion), dated 2 s before that window's end, the
  middle of a 4 s gait window.
Both are estimates. The filmed clinic walk checks them against video (plan § How it runs).
"""

from __future__ import annotations

import json
import statistics
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Optional

import numpy as np

from . import pilot
from .stream_features import Context, compute_features, resample_uniform

WALKING = {Context.WALKING, Context.TURNING}
RESUME_RUN = 3                 # consecutive walking windows that count as steady steps
GAIT_HALF_MS = 2000            # half the 4 s gait window
# A 4 s gait window still reads as walking for about 1 s after the steps stop, so the last
# walking window ends about 1 s late. Measured on synthetic walks (tests); the filmed walk re-checks it.
STOP_LAG_MS = 1000
GAIT_WIN_MS = 4000              # less motion than one gait window can't be timed
# Walking must have stopped at most this long before the press to count for H1. Longer means the
# person stood still first, then froze trying to start: that freeze began when they tried to
# start, which the motion can't date, so the press has no H1 timing (review 001 #1).
MAX_STOP_BEFORE_PRESS_MS = 10_000
MIN_PRESSES_PER_PLACE = 3      # a person counts for a place comparison with at least this many

# Thresholds from docs/product/plan.md § Measures.
H1_MAX_MEDIAN_S = 4.0
H1_MAX_ALREADY_MOVING = 0.5
H12_MAX_NOT_FELT = 0.2         # "reliably felt": fewer than 1 in 5 pocket check-ins say "didn't feel it"


@dataclass
class PressTiming:
    participant: str
    press_index: int
    place: str
    checkin: Optional[str]
    stop_to_beat_s: Optional[float]   # walking stopped -> beat started (H1)
    press_to_steps_s: Optional[float]  # press -> steady steps again (H2 description, H3, H12)
    beat_confirmed: bool = True        # a beat start was measured; else nothing is known to have played


def load_press(path: Path) -> dict:
    d = json.loads(Path(path).read_text())
    if not isinstance(d, dict):
        raise ValueError(f"{path}: not a press file (top level is not an object)")
    if d.get("version") != pilot.VERSION:
        raise ValueError(f"{path}: version {d.get('version')!r}, expected {pilot.VERSION}")
    return d


def press_timing(d: dict) -> PressTiming:
    """Timings for one press. A press with too little motion, or with no confirmed beat start,
    keeps its check-in but has no timings (None), rather than being dropped or guessed.
    So does a press whose motion the app flagged as incomplete: `motion_partial` (iOS, the
    background recorder never caught up) or `recovered` (Android, rebuilt after the app died)
    (review 002 #2)."""
    s = d.get("samples") or {}
    t_raw = s.get("t_ms") or []
    beat = d.get("beat_start_ms")
    confirmed = beat is not None
    if (d.get("motion_partial") or d.get("recovered")
            or len(t_raw) < 2 or (t_raw[-1] - t_raw[0]) < GAIT_WIN_MS):
        return PressTiming(d["participant"], d["press_index"], d["place"], d.get("checkin"),
                           None, None, confirmed)
    t = np.asarray(t_raw, dtype=float)
    acc = np.asarray(s["acc"], dtype=float)
    gyr = np.zeros_like(acc) if s.get("gyr") is None else np.asarray(s["gyr"], dtype=float)
    tu, ax, ay, az, gx, gy, gz = resample_uniform(t, *acc, *gyr)
    windows = compute_features(tu, np.vstack([ax, ay, az]), np.vstack([gx, gy, gz]))
    walking = [w.context in WALKING for w in windows]
    ends = [w.t_end_ms for w in windows]

    press = d["press_ms"]
    if beat is None:
        return PressTiming(d["participant"], d["press_index"], d["place"], d.get("checkin"), None, None)
    stop_ms = None
    for e, wk in zip(ends, walking):
        if e <= press and wk:
            stop_ms = e - STOP_LAG_MS
    if stop_ms is not None and press - stop_ms > MAX_STOP_BEFORE_PRESS_MS:
        stop_ms = None
    stop_to_beat = None if stop_ms is None else max(0.0, (beat - stop_ms) / 1000.0)

    resume_ms = None
    for k in range(len(windows) - RESUME_RUN + 1):
        if ends[k] >= beat + GAIT_HALF_MS and all(walking[k:k + RESUME_RUN]):
            resume_ms = ends[k] - GAIT_HALF_MS
            break
    press_to_steps = None if resume_ms is None else max(0.0, (resume_ms - press) / 1000.0)

    return PressTiming(d["participant"], d["press_index"], d["place"], d.get("checkin"),
                       stop_to_beat, press_to_steps, confirmed)


@dataclass
class Verdicts:
    people: int
    presses: int
    h1_median_stop_to_beat_s: Optional[float]
    h1_already_moving_share: Optional[float]
    h1_fails: Optional[bool]
    h3_earbuds_faster: tuple[int, int]      # (people faster with earbuds, people compared)
    h3_sound_leads: Optional[bool]
    h12_pocket_faster: tuple[int, int]
    h12_pocket_not_felt_share: Optional[float]
    h12_build_device: Optional[bool]
    h2_median_press_to_steps_s: Optional[float]   # description only, no verdict
    notes: list[str] = field(default_factory=list)


def _median(xs: Iterable[float]) -> Optional[float]:
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def _faster_than_wrist(by_person: dict, place: str) -> tuple[int, int]:
    faster = compared = 0
    for times in by_person.values():
        a = [t.press_to_steps_s for t in times if t.place == place and t.press_to_steps_s is not None]
        w = [t.press_to_steps_s for t in times if t.place == "wrist" and t.press_to_steps_s is not None]
        if len(a) >= MIN_PRESSES_PER_PLACE and len(w) >= MIN_PRESSES_PER_PLACE:
            compared += 1
            faster += statistics.median(a) < statistics.median(w)
    return faster, compared


def verdicts(timings: list[PressTiming]) -> Verdicts:
    """Medians are taken per person first, then across people, so a frequent presser can't
    outweigh everyone else."""
    by_person: dict[str, list[PressTiming]] = {}
    for t in timings:
        by_person.setdefault(t.participant, []).append(t)
    notes = []

    stop_meds = [_median(t.stop_to_beat_s for t in ts) for ts in by_person.values()]
    h1_med = _median(stop_meds)
    shares = []
    for ts in by_person.values():
        answered = [t for t in ts if t.checkin]
        if answered:
            shares.append(sum(t.checkin == pilot.CheckIn.ALREADY_MOVING.value for t in answered) / len(answered))
    h1_share = _median(shares)
    h1_fails = None
    if h1_med is not None or h1_share is not None:
        h1_fails = bool((h1_med is not None and h1_med > H1_MAX_MEDIAN_S)
                        or (h1_share is not None and h1_share > H1_MAX_ALREADY_MOVING))
    else:
        notes.append("H1: no press had walking before it or a check-in answer")

    ear = _faster_than_wrist(by_person, "earbuds")
    pocket = _faster_than_wrist(by_person, "pocket")
    h3 = None if ear[1] == 0 else ear[0] > ear[1] / 2

    felt_shares = []
    for ts in by_person.values():
        # Only presses whose beat was confirmed: one stopped before the phone answered played
        # nothing in the pocket, so its "didn't feel it" says nothing about the pocket (review 002 #5).
        answers = [t.checkin for t in ts if t.place == "pocket" and t.checkin and t.beat_confirmed]
        if answers:
            felt_shares.append(sum(c == pilot.CheckIn.NOT_FELT.value for c in answers) / len(answers))
    not_felt = _median(felt_shares)
    h12 = None
    if pocket[1] and not_felt is not None:
        h12 = pocket[0] > pocket[1] / 2 and not_felt < H12_MAX_NOT_FELT
    elif pocket[1]:
        notes.append("H12: no pocket check-ins, so 'reliably felt' is unknown")

    h2 = _median(_median(t.press_to_steps_s for t in ts) for ts in by_person.values())
    return Verdicts(len(by_person), len(timings), h1_med, h1_share, h1_fails, ear, h3,
                    pocket, not_felt, h12, h2, notes)


def analyse(folder: Path) -> tuple[list[PressTiming], Verdicts]:
    """Every press file in the folder. A file that isn't a pilot-1.0 press file is skipped and
    named in the report notes, so one stray file can't stop the analysis (review 001 #3).
    A press that arrived twice (both apps keep a re-sent copy as _2 / -2 rather than overwrite)
    counts once: the copy with the most motion samples is kept (review 002 #3)."""
    kept: dict[tuple, tuple[int, str, dict]] = {}
    notes = []
    for f in sorted(Path(folder).glob("*.json")):
        try:
            d = load_press(f)
            key = (d["participant"], d["press_index"], d["press_ms"])
        except (ValueError, KeyError, TypeError) as e:  # json.JSONDecodeError is a ValueError
            notes.append(f"skipped {f.name}: {e}")
            continue
        n = len((d.get("samples") or {}).get("t_ms") or [])
        if key in kept:
            old_n, old_name, _ = kept[key]
            drop = f.name if n <= old_n else old_name
            notes.append(f"duplicate of press {key[0]} #{key[1]}: ignored {drop}")
            if n <= old_n:
                continue
        kept[key] = (n, f.name, d)
    timings = []
    for _, name, d in kept.values():
        try:
            timings.append(press_timing(d))
        except (ValueError, KeyError, TypeError) as e:
            notes.append(f"skipped {name}: {e}")
    v = verdicts(timings)
    v.notes.extend(notes)
    return timings, v


def report(v: Verdicts) -> str:
    def s(x, unit=""):
        return "n/a" if x is None else (f"{x:.1f}{unit}" if isinstance(x, float) else str(x))
    lines = [
        f"Home pilot ({pilot.VERSION}): {v.people} people, {v.presses} presses",
        "",
        f"H1  median seconds from walking stopping to the beat starting: {s(v.h1_median_stop_to_beat_s, ' s')}"
        f" (fails above {H1_MAX_MEDIAN_S:.0f} s)",
        f"    share of check-ins saying 'already moving': {s(v.h1_already_moving_share)}"
        f" (fails above {H1_MAX_ALREADY_MOVING})",
        f"    verdict: {'FAILS: cue before the freeze; bring automatic detection forward' if v.h1_fails else 'holds' if v.h1_fails is False else 'n/a'}",
        "",
        f"H3  earbuds faster than wrist for {v.h3_earbuds_faster[0]} of {v.h3_earbuds_faster[1]} people:"
        f" {'sound leads' if v.h3_sound_leads else 'wrist stays' if v.h3_sound_leads is False else 'n/a'}",
        f"H12 pocket faster than wrist for {v.h12_pocket_faster[0]} of {v.h12_pocket_faster[1]} people;"
        f" 'didn't feel it' share {s(v.h12_pocket_not_felt_share)} (must be under {H12_MAX_NOT_FELT}):"
        f" {'design a belt device' if v.h12_build_device else 'no extra hardware' if v.h12_build_device is False else 'n/a'}",
        "",
        f"H2  description only: median seconds from press to steady steps {s(v.h2_median_press_to_steps_s, ' s')}",
    ]
    lines += [f"note: {n}" for n in v.notes]
    return "\n".join(lines)
