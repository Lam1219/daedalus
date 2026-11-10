"""Golden test vectors for the on-device ports (task T6).

Writes one JSON file per case next to this script. Each holds the input samples, the settings,
and what the Python reference computes: every 1 s window's features and context, the tremor
episodes and the freeze candidates. The Galaxy Watch (Kotlin) and Apple Watch (Swift) tests
read the same files and must match within `tolerance`.

All inputs are synthetic (no Parkinson@Home data: it is under a Data Use Agreement). Inputs are
rounded to 6 decimals before the expected values are computed, so every platform starts from
exactly the same numbers.

Regenerate after any change to stream_features, tremor_stream or fog (and bump their VERSION):
    python_scripts/.venv/bin/python daedalus_watch_kit/golden/make_golden.py
tests/test_golden.py fails while the files are out of date.

File format (all times in ms, acceleration m/s² with gravity, rotation rad/s):
{
  "name", "description",
  "versions": {"features", "tremor", "fog"},
  "resample": true if t_ms is uneven and must go through resample_uniform first,
  "input": {"t_ms": [...], "ax": [...], "ay": [...], "az": [...], "gx": [...], "gy": [...], "gz": [...]},
  "tremor_params": {...}, "fog_params": {...},
  "expected": {
    "windows": [{"t_end_ms", "tremor_dps", ..., "context"}],
    "tremor_episodes": [{"start_ms", "detected_ms", "end_ms", "hz", "strength"}],
    "fog_candidates": [{"detected_ms", "reason", "turning"}]
  },
  "tolerance": {"relative", "absolute"}
}
"""

from __future__ import annotations

import json
import math
from dataclasses import asdict
from pathlib import Path

import numpy as np

from daedalus_watch_kit import fog
from daedalus_watch_kit import stream_features as sf
from daedalus_watch_kit import tremor_stream as ts

HERE = Path(__file__).resolve().parent
G = 9.80665
FS = sf.FS
DIGITS = 6
TOLERANCE = {"relative": 1e-6, "absolute": 1e-6}


# ------------------------------------------------------------------ signal pieces

class Builder:
    """Concatenates segments of 50 Hz motion; the watch lies flat (gravity on z) unless tilted."""

    def __init__(self, seed: int):
        self.rng = np.random.default_rng(seed)
        self.acc: list[np.ndarray] = []
        self.gyr: list[np.ndarray] = []
        self.t0 = 0.0

    def _t(self, seconds):
        n = int(round(seconds * FS))
        t = self.t0 + np.arange(n) / FS
        self.t0 += n / FS
        return t

    def add(self, seconds, acc=None, gyr=None, tilt_deg=None):
        t = self._t(seconds)
        a = np.zeros((3, len(t)))
        if tilt_deg is None:
            a[2] = G
        else:
            ang = np.radians(tilt_deg(t)) if callable(tilt_deg) else np.full(len(t), np.radians(tilt_deg))
            a[0], a[2] = G * np.sin(ang), G * np.cos(ang)
        g = np.zeros((3, len(t)))
        if acc is not None:
            a += acc(t)
        if gyr is not None:
            g += gyr(t)
        a += self.rng.normal(0, 0.02, a.shape)
        g += self.rng.normal(0, 0.002, g.shape)
        self.acc.append(a)
        self.gyr.append(g)
        return self

    def build(self, jitter_ms: float = 0.0):
        a = np.concatenate(self.acc, axis=1)
        g = np.concatenate(self.gyr, axis=1)
        t = np.arange(a.shape[1]) * 1000.0 / FS
        if jitter_ms:
            t = t + self.rng.uniform(-jitter_ms, jitter_ms, len(t))
            t[0] = 0.0
            t = np.maximum.accumulate(t)
        return np.round(t).astype(np.int64), np.round(a, DIGITS), np.round(g, DIGITS)


def tremor(hz, dps, axis_mix=(1.0, 0.6, 0.2)):
    amp = math.radians(dps) * math.sqrt(2) / math.sqrt(sum(m * m for m in axis_mix))
    return lambda t: np.vstack([m * amp * np.sin(2 * np.pi * hz * t + k) for k, m in enumerate(axis_mix)])


def sway(hz, dps):
    amp = math.radians(dps) * math.sqrt(2)
    return lambda t: np.vstack([amp * np.sin(2 * np.pi * hz * t), 0 * t, 0 * t])


def walking_acc(spm, amp=2.0):
    step, stride = spm / 60.0, spm / 120.0
    def f(t):
        b = amp * (np.sin(2 * np.pi * step * t) + 0.5 * np.sin(2 * np.pi * stride * t))
        return np.vstack([0.3 * b, 0 * t, b])
    return f


def walking_gyr(spm, swing_dps=60.0, turn_dps=0.0):
    stride = spm / 120.0
    amp = math.radians(swing_dps) * math.sqrt(2)
    return lambda t: np.vstack([0 * t, amp * np.sin(2 * np.pi * stride * t), 0 * t + math.radians(turn_dps)])


def plus(*fs):
    return lambda t: sum(f(t) for f in fs)


# ------------------------------------------------------------------ cases

def cases():
    yield ("still", "Watch flat and still for 10 s: every window STILL, no tremor, no freeze.",
           Builder(1).add(10).build(), ts.TremorParams(), fog.FogParams())

    yield ("rest_tremor_episode",
           "4 s quiet, 14 s of 5 Hz rest tremor at 8 deg/s, 8 s quiet: one tremor episode.",
           Builder(2).add(4).add(14, gyr=tremor(5.0, 8.0)).add(8).build(), ts.TremorParams(), fog.FogParams())

    yield ("slight_tremor_personal_threshold",
           "16 s of 4.5 Hz tremor at 2 deg/s with a personal threshold of 1.5 deg/s and the person's "
           "rhythm 4.5 Hz: one episode (the population threshold, 6 deg/s, would miss it).",
           Builder(3).add(3).add(16, gyr=tremor(4.5, 2.0)).add(7).build(),
           ts.TremorParams(threshold_dps=1.5, person_hz=4.5), fog.FogParams())

    yield ("voluntary_rhythm_rejected",
           "5 Hz rotation at 12 deg/s on top of 0.8 Hz arm movement at 10 deg/s for 14 s: the context "
           "stays rest, but the rest gate (slow movement > 0.5 x tremor) rejects every window; no episode.",
           Builder(4).add(2).add(14, gyr=plus(tremor(5.0, 12.0), sway(0.8, 10.0))).add(4).build(),
           ts.TremorParams(), fog.FogParams())

    yield ("walking", "15 s walking at 100 steps/min with 60 deg/s arm swing: WALKING, cadence about 100.",
           Builder(5).add(15, acc=walking_acc(100), gyr=walking_gyr(100)).build(), ts.TremorParams(), fog.FogParams())

    yield ("walking_turn",
           "12 s walking at 96 steps/min while turning at 40 deg/s: TURNING context.",
           Builder(6).add(12, acc=walking_acc(96), gyr=walking_gyr(96, turn_dps=40.0)).build(),
           ts.TremorParams(), fog.FogParams())

    tremble = lambda t: np.vstack([0 * t, 0 * t, 1.0 * np.sin(2 * np.pi * 6.0 * t)])
    yield ("walk_then_tremble",
           "12 s walking at 100 steps/min, then 6 s trembling in place at 6 Hz: one freeze candidate "
           "(freeze index).",
           Builder(7).add(12, acc=walking_acc(100), gyr=walking_gyr(100))
           .add(6, acc=tremble, gyr=tremor(6.0, 4.0)).build(), ts.TremorParams(), fog.FogParams())

    yield ("walk_then_stop",
           "12 s walking, then 8 s standing with a still wrist: a voluntary stop, no candidate.",
           Builder(8).add(12, acc=walking_acc(100), gyr=walking_gyr(100)).add(8).build(),
           ts.TremorParams(), fog.FogParams())

    yield ("posture_change",
           "4 s still, the wrist tilts 70 degrees over 1 s, 5 s still: ACTIVE during the tilt.",
           Builder(9).add(4).add(1, tilt_deg=lambda t: 70 * (t - t[0]) / max(t[-1] - t[0], 1e-9),
                                 gyr=lambda t: np.vstack([0 * t, 0 * t + math.radians(70), 0 * t]))
           .add(5, tilt_deg=70.0).build(), ts.TremorParams(), fog.FogParams())

    yield ("jittered_timestamps",
           "Uneven sample times (±4 ms) around 50 Hz, 6 Hz tremor at 10 deg/s for 14 s: resample "
           "first, then one episode.",
           Builder(10).add(3).add(14, gyr=tremor(6.0, 10.0)).add(6).build(jitter_ms=4.0),
           ts.TremorParams(), fog.FogParams())


# ------------------------------------------------------------------ expected values

def _clean(x):
    if isinstance(x, float):
        if math.isinf(x):
            return "inf" if x > 0 else "-inf"
        return round(x, 9)
    if isinstance(x, dict):
        return {k: _clean(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [_clean(v) for v in x]
    if hasattr(x, "value"):
        return x.value
    return x


def expected(t_ms, acc, gyr, tp: ts.TremorParams, fp: fog.FogParams):
    resample = bool(np.any(np.diff(t_ms) != 1000 / FS))
    if resample:
        t_u, *ch = sf.resample_uniform(t_ms, *acc, *gyr)
        acc_u, gyr_u = np.vstack(ch[:3]), np.vstack(ch[3:])
    else:
        t_u, acc_u, gyr_u = t_ms, acc, gyr
    windows = sf.compute_features(t_u, acc_u, gyr_u)
    eps = ts.detect(windows, tp)
    cands = fog.detect(windows, fp)
    return resample, dict(
        windows=[w.to_dict() for w in windows],
        tremor_episodes=[dict(start_ms=e.start_ms, detected_ms=e.detected_ms, end_ms=e.end_ms,
                              hz=e.hz, strength=e.strength) for e in eps],
        fog_candidates=[dict(detected_ms=c.detected_ms, reason=c.reason, turning=c.turning) for c in cands],
    )


def payload(name, description, data, tp, fp):
    t_ms, acc, gyr = data
    resample, exp = expected(t_ms, acc, gyr, tp, fp)
    return _clean(dict(
        name=name, description=description,
        versions=dict(features=sf.VERSION, tremor=ts.VERSION, fog=fog.VERSION),
        resample=resample,
        input=dict(t_ms=t_ms.tolist(), ax=acc[0].tolist(), ay=acc[1].tolist(), az=acc[2].tolist(),
                   gx=gyr[0].tolist(), gy=gyr[1].tolist(), gz=gyr[2].tolist()),
        tremor_params=asdict(tp), fog_params=asdict(fp),
        expected=exp, tolerance=TOLERANCE,
    ))


def all_payloads():
    return {name: payload(name, d, data, tp, fp) for name, d, data, tp, fp in cases()}


def main():
    for name, p in all_payloads().items():
        (HERE / f"{name}.json").write_text(json.dumps(p, separators=(",", ":")) + "\n")
        e = p["expected"]
        ctx = sorted({w["context"] for w in e["windows"]})
        print(f"{name:34s} {len(e['windows']):3d} windows  contexts {ctx}  "
              f"tremor episodes {len(e['tremor_episodes'])}  fog candidates {len(e['fog_candidates'])}")


if __name__ == "__main__":
    main()
