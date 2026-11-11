"""Guard engine, guard-1.0: raw watch samples in, shadow-mode events out (design 2.2, 2.7; task M3).

This is what a guard session runs on the watch, as one platform-neutral piece the Galaxy Watch
(Kotlin) and Apple Watch (Swift) port line for line:

  raw samples (uneven times) -> StreamResampler (even 50 Hz grid)
    -> StreamFeatureExtractor (one window a second)
    -> TremorDetector (rest and walk guards) and FogDetector (walk guard)
    -> event rows: (epoch_ms, event_name, notes)

In shadow mode nothing cues: the events are only logged, with action=shadow, for evaluation
and for learning each person's threshold later. The session (service or workout) writes the
rows to the session's events file with source "auto".

Resampling. Sample times on a watch jitter around 20 ms. The resampler puts a grid every
1000 / FS ms from the first sample and interpolates linearly between consecutive samples,
as resample_uniform does for a whole recording (np.interp's formula: a grid time equal to a
sample's time takes that sample's values). A sample with the same time as the previous one (batched sensor delivery)
is skipped. A gap longer than MAX_GAP_MS, or a time that goes backwards, restarts the grid at
that sample and starts a new feature extractor: windows never span a gap. The detectors keep their state across a gap.

Event notes: key=value pairs separated by ';', keys always in the order below, numbers formatted
like Python's f"{x:.Nf}" (correctly rounded, ties to even on the exact binary value), booleans
"true"/"false". The phones parse them by key.

  GUARD_START          type=guard;mode=<walk|rest>;models=<features>,<tremor>,<fog>
  AUTO_TREMOR_START    type=tremor;action=shadow;model=;start_ms=;hz=(1);strength=;confidence=(2);threshold_dps=(2)
  AUTO_TREMOR_END      type=tremor;action=shadow;model=;start_ms=;end_ms=;hz=(1);strength=;peak_dps=(2)
  AUTO_FOG_CANDIDATE   type=fog;action=shadow;model=;walk_start_ms=;walk_end_ms=;reason=;turning=;
                       freeze_index=(2);arm_swing_share=(3);confidence=(2);freeze_index_threshold=(2)
  GUARD_END            type=guard;mode=;windows=<count>;gaps=<count>

(N) = decimals. A tremor episode still open at finish() ends there.

Change all copies together (GuardEngine.kt, GuardEngine.swift) and bump VERSION; the golden
vectors carry the expected events (golden/make_golden.py).
"""

from __future__ import annotations

from typing import Optional

import numpy as np

from . import event_labels as ev
from . import fog
from . import stream_features as sf
from . import tremor_stream as ts

VERSION = "guard-1.0"

MAX_GAP_MS = 250
WALK = "walk"
REST = "rest"


def fmt(x: float, decimals: int) -> str:
    """Python's f"{x:.Nf}": the exact binary value correctly rounded, ties to even."""
    return f"{x:.{decimals}f}"


def _bool(b: bool) -> str:
    return "true" if b else "false"


class StreamResampler:
    """Uneven samples in, an even grid out (see the module notes)."""

    def __init__(self, fs: float = sf.FS, max_gap_ms: float = MAX_GAP_MS):
        self.step = 1000.0 / fs
        self.max_gap_ms = max_gap_ms
        self._prev_t: Optional[float] = None
        self._prev_v: Optional[np.ndarray] = None
        self._t0 = 0.0
        self._k = 0           # index of the next grid point
        self.restarts = 0     # gaps and backward times so far

    def push(self, t_ms: float, values) -> tuple[bool, list[tuple[int, np.ndarray]]]:
        """Returns (restarted, grid samples). restarted = this sample began a new grid."""
        v = np.asarray(values, dtype=float)
        t = float(t_ms)
        restarted = False
        if self._prev_t is not None and t == self._prev_t:
            return False, []          # same time as the previous sample: skipped
        if self._prev_t is not None and (t < self._prev_t or t - self._prev_t > self.max_gap_ms):
            self.restarts += 1
            self._prev_t = None
            restarted = True
        out: list[tuple[int, np.ndarray]] = []
        if self._prev_t is None:
            self._t0, self._k = t, 0
            self._prev_t, self._prev_v = t, v
            out.append((int(t), v.copy()))
            self._k = 1
            return restarted, out
        tp, vp = self._prev_t, self._prev_v
        while True:
            g = self._t0 + self._k * self.step
            if g > t:
                break
            if g == t:
                val = v.copy()
            else:
                slope = (v - vp) / (t - tp)
                val = slope * (g - tp) + vp
            out.append((int(g), val))
            self._k += 1
        self._prev_t, self._prev_v = t, v
        return restarted, out


class GuardEngine:
    """One guard session: push raw samples, collect event rows."""

    def __init__(self, mode: str, tremor_params: Optional[ts.TremorParams] = None,
                 fog_params: Optional[fog.FogParams] = None, tremor_window_s: int = 2):
        if mode not in (WALK, REST):
            raise ValueError("mode must be 'walk' or 'rest'")
        self.mode = mode
        self.tremor_window_s = tremor_window_s
        self.resampler = StreamResampler()
        self.extractor = sf.StreamFeatureExtractor(tremor_window_s)
        self.tremor = ts.TremorDetector(tremor_params)
        self.fog = fog.FogDetector(fog_params) if mode == WALK else None
        self.windows = 0
        self.last_window_ms: Optional[int] = None
        self.events: list[tuple[int, str, str]] = []

    # -------------------------------------------------------------- lifecycle

    def begin(self, t_ms: int) -> list[tuple[int, str, str]]:
        notes = f"type=guard;mode={self.mode};models={sf.VERSION},{ts.VERSION},{fog.VERSION}"
        return self._emit([(int(t_ms), ev.GUARD_START, notes)])

    def push(self, t_ms: float, ax, ay, az, gx, gy, gz) -> list[tuple[int, str, str]]:
        restarted, grid = self.resampler.push(t_ms, (ax, ay, az, gx, gy, gz))
        if restarted:
            self.extractor = sf.StreamFeatureExtractor(self.tremor_window_s)
        rows: list[tuple[int, str, str]] = []
        for gt, v in grid:
            w = self.extractor.push(gt, *v)
            if w is not None:
                rows += self._window(w)
        return self._emit(rows)

    def finish(self, t_ms: int) -> list[tuple[int, str, str]]:
        rows: list[tuple[int, str, str]] = []
        end = self.last_window_ms if self.last_window_ms is not None else int(t_ms)
        for _, ep in self.tremor.finish(end):
            rows.append((int(t_ms), ev.AUTO_TREMOR_END, self._tremor_end_notes(ep)))
        rows.append((int(t_ms), ev.GUARD_END,
                     f"type=guard;mode={self.mode};windows={self.windows};gaps={self.resampler.restarts}"))
        return self._emit(rows)

    # -------------------------------------------------------------- internals

    def _emit(self, rows):
        self.events += rows
        return rows

    def _window(self, w: sf.WindowFeatures) -> list[tuple[int, str, str]]:
        self.windows += 1
        self.last_window_ms = w.t_end_ms
        rows: list[tuple[int, str, str]] = []
        for kind, ep in self.tremor.update(w):
            if kind == "start":
                rows.append((w.t_end_ms, ev.AUTO_TREMOR_START, self._tremor_start_notes(ep)))
            else:
                rows.append((w.t_end_ms, ev.AUTO_TREMOR_END, self._tremor_end_notes(ep)))
        if self.fog is not None:
            c = self.fog.update(w)
            if c is not None:
                rows.append((w.t_end_ms, ev.AUTO_FOG_CANDIDATE, self._fog_notes(c)))
        return rows

    def _tremor_start_notes(self, ep: ts.TremorEpisode) -> str:
        return (f"type=tremor;action=shadow;model={ep.version};start_ms={ep.start_ms};hz={fmt(ep.hz, 1)};"
                f"strength={ep.strength};confidence={fmt(ep.confidence, 2)};"
                f"threshold_dps={fmt(ep.threshold_dps, 2)}")

    def _tremor_end_notes(self, ep: ts.TremorEpisode) -> str:
        return (f"type=tremor;action=shadow;model={ep.version};start_ms={ep.start_ms};end_ms={ep.end_ms};"
                f"hz={fmt(ep.hz, 1)};strength={ep.strength};peak_dps={fmt(ep.peak_dps, 2)}")

    def _fog_notes(self, c: fog.FogCandidate) -> str:
        return (f"type=fog;action=shadow;model={c.version};walk_start_ms={c.walk_start_ms};"
                f"walk_end_ms={c.walk_end_ms};reason={c.reason};turning={_bool(c.turning)};"
                f"freeze_index={fmt(c.freeze_index, 2)};arm_swing_share={fmt(c.arm_swing_share, 3)};"
                f"confidence={fmt(c.confidence, 2)};"
                f"freeze_index_threshold={fmt(self.fog.params.freeze_index_threshold, 2)}")


def run(mode: str, t_ms, acc, gyr, tremor_params=None, fog_params=None) -> list[tuple[int, str, str]]:
    """A whole recording through one guard session: begin at the first sample, finish at the last."""
    eng = GuardEngine(mode, tremor_params, fog_params)
    eng.begin(int(t_ms[0]))
    for i in range(len(t_ms)):
        eng.push(t_ms[i], *acc[:, i], *gyr[:, i])
    eng.finish(int(t_ms[-1]))
    return eng.events
