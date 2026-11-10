"""Streaming tremor detector, tremor-stream-1.0 (Documentation/automatic-cues-design.md, 2.4).

It consumes one stream_features.WindowFeatures per second and reports tremor episodes. It does
not cue: the watch decides what to do with an episode (in v1, ask first with Fine motor).

Window positive when all hold:
  1. context is REST_TREMOR_POSSIBLE or STILL (hand at rest, no posture change, not walking).
     A slight tremor (1-2 deg/s) stays under the STILL motion limit, so STILL must count;
  2. peak_hz is inside the accepted band: 3.5-7.5 Hz, or the person's tremor Hz ± 1.5 Hz;
  3. prominence >= MIN_PROMINENCE;
  4. tremor_dps >= the person's threshold T;
  5. the hand is quiet around the tremor (rest gate; float("inf") turns a limit off):
     slow_dps / tremor_dps <= max_slow_ratio (slow movement small next to the tremor), and
     arm_swing_dps <= max_slow4_dps (little 0.5-2 Hz rotation over the last 4 s).
     Voluntary rhythmic movements in healthy controls fail these: their slow movement is
     about 2-4 x that of rest tremor (Parkinson@Home, 2026-11-10).

Episode start: START_K positives in the last START_N windows, whose peak frequencies stay within
±FREQ_TOLERANCE_HZ of their median. Episode end: at most END_K positives in the last END_N windows.

The thresholds are provisional; T4 (python_scripts/evaluate_tremor_parkinson_home.py) tunes them.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field, replace
from typing import Iterable, Optional

import numpy as np

from .stream_features import Context, WindowFeatures

VERSION = "tremor-stream-1.0"

BAND_LOW_HZ = 3.5
BAND_HIGH_HZ = 7.5
PERSONAL_BAND_HZ = 1.5
# Tuned on Parkinson@Home (T4, 2026-11-10): most leave-one-out folds chose T 6 deg/s,
# prominence 16 (the top of the grid tried; F1 is flat from 10 to 16).
MIN_PROMINENCE = 16.0
FREQ_TOLERANCE_HZ = 1.0
START_K, START_N = 3, 4
END_K, END_N = 1, 6

# Rest gate. Tuned on Parkinson@Home (2026-11-10): it cuts false episodes at rest in healthy
# controls from 0.33 to 0.08 per hour for about 0.02 of event F1 on tremor subjects.
MAX_SLOW_RATIO = 0.5
MAX_SLOW4_DPS = 8.0

# Personal threshold T, deg/s RMS in 3-8 Hz over a 2 s window.
T_MIN = 1.5
T_MAX = 40.0
T_DEFAULT = 6.0            # before calibration: the population value from T4
REST_MULTIPLIER = 3.0       # T = max(T_MIN, 3 x the 90th percentile at calibration rest)
CALIBRATION_TREMOR_SHARE = 0.5
FALSE_STEP = 1.15           # "Not shaking"
MISSED_STEP = 0.90          # "I'm shaking" with no detection
FEEDBACK_FLOOR = 0.5        # feedback keeps T within 0.5-1.5 x its starting value
FEEDBACK_CEILING = 1.5

# tremor-1.0 strength levels, deg/s.
MODERATE_DPS = 10.0
STRONG_DPS = 40.0


@dataclass
class TremorParams:
    threshold_dps: float = T_DEFAULT
    person_hz: Optional[float] = None
    min_prominence: float = MIN_PROMINENCE
    max_slow_ratio: float = MAX_SLOW_RATIO
    max_slow4_dps: float = MAX_SLOW4_DPS

    def band(self) -> tuple[float, float]:
        if self.person_hz is None:
            return BAND_LOW_HZ, BAND_HIGH_HZ
        return (max(BAND_LOW_HZ, self.person_hz - PERSONAL_BAND_HZ),
                min(BAND_HIGH_HZ, self.person_hz + PERSONAL_BAND_HZ))


# Not used to set T in v1. On Parkinson@Home, a T from 20 s of calibration rest did worse than
# the population T (event F1 0.73 vs 0.77, more false episodes): 20 s is too short a sample of a
# tremor that comes and goes. Kept for the shadow-mode study, where weeks of rest windows replace
# the 20 s. See python_scripts/evaluate_tremor_calibration.py.
def calibrate(rest_windows: Iterable[WindowFeatures], tremor_band_dps: Optional[float] = None,
              tremor_hz: Optional[float] = None, rest_multiplier: float = REST_MULTIPLIER,
              tremor_share: float = CALIBRATION_TREMOR_SHARE,
              min_prominence: float = MIN_PROMINENCE) -> TremorParams:
    """Personal settings from the calibration rest (20 s, hand on the lap).

    tremor_band_dps and tremor_hz come from the tremor-1.0 check when it found a tremor
    (Result.bandDps, Result.frequencyHz).
    """
    values = [w.tremor_dps for w in rest_windows if w.context in (Context.REST_TREMOR_POSSIBLE, Context.STILL)]
    t = T_DEFAULT if not values else max(T_MIN, rest_multiplier * float(np.percentile(values, 90)))
    if tremor_band_dps is not None:
        t = min(t, tremor_share * tremor_band_dps)
    return TremorParams(threshold_dps=float(np.clip(t, T_MIN, T_MAX)), person_hz=tremor_hz,
                        min_prominence=min_prominence)


def after_feedback(params: TremorParams, false_positive: bool, base_dps: float = T_DEFAULT) -> TremorParams:
    """'Not shaking' raises T by 15 %; a missed tremor lowers it by 10 %.

    T stays within FEEDBACK_FLOOR-FEEDBACK_CEILING x base_dps (the threshold before any
    feedback). Without the ceiling, "Not shaking" ratchets T up and recall collapses
    (calibration evaluation, 2026-11-10).
    """
    step = FALSE_STEP if false_positive else MISSED_STEP
    lo = max(T_MIN, FEEDBACK_FLOOR * base_dps)
    hi = min(T_MAX, FEEDBACK_CEILING * base_dps)
    t = float(np.clip(params.threshold_dps * step, lo, hi))
    return replace(params, threshold_dps=t)


def window_positive(w: WindowFeatures, p: TremorParams) -> bool:
    lo, hi = p.band()
    return (
        w.context in (Context.REST_TREMOR_POSSIBLE, Context.STILL)
        and lo - 1e-9 <= w.peak_hz <= hi + 1e-9
        and w.prominence >= p.min_prominence
        and w.tremor_dps >= p.threshold_dps
        and w.slow_dps <= p.max_slow_ratio * w.tremor_dps
        and w.arm_swing_dps <= p.max_slow4_dps
    )


def strength(dps: float) -> str:
    if dps >= STRONG_DPS:
        return "strong"
    if dps >= MODERATE_DPS:
        return "moderate"
    return "light"


@dataclass
class TremorEpisode:
    start_ms: int           # end of the first positive window in the starting run, minus the 2 s window
    detected_ms: int        # when the episode was confirmed: the watch would prompt here
    end_ms: Optional[int] = None
    hz: float = 0.0
    peak_dps: float = 0.0
    strength: str = "light"
    confidence: float = 0.0
    threshold_dps: float = 0.0
    version: str = VERSION
    _hz: list = field(default_factory=list, repr=False)
    _dps: list = field(default_factory=list, repr=False)


class TremorDetector:
    """Feed WindowFeatures in time order with update(); it returns events as they happen."""

    WINDOW_MS = 2000

    def __init__(self, params: Optional[TremorParams] = None):
        self.params = params or TremorParams()
        self._recent: deque[tuple[bool, WindowFeatures]] = deque(maxlen=max(START_N, END_N))
        self.episode: Optional[TremorEpisode] = None

    def update(self, w: WindowFeatures) -> list[tuple[str, TremorEpisode]]:
        """Returns [] or a list of ("start", episode) / ("end", episode) events."""
        pos = window_positive(w, self.params)
        self._recent.append((pos, w))
        events: list[tuple[str, TremorEpisode]] = []

        if self.episode is None:
            last = list(self._recent)[-START_N:]
            hits = [x for ok, x in last if ok]
            if len(hits) >= START_K and pos:
                hz = np.array([x.peak_hz for x in hits])
                if np.all(np.abs(hz - np.median(hz)) <= FREQ_TOLERANCE_HZ + 1e-9):
                    first = hits[0]
                    ep = TremorEpisode(start_ms=first.t_end_ms - self.WINDOW_MS, detected_ms=w.t_end_ms,
                                       threshold_dps=self.params.threshold_dps)
                    for x in hits:
                        ep._hz.append(x.peak_hz)
                        ep._dps.append(x.tremor_dps)
                    self.episode = ep
                    self._refresh(ep)
                    events.append(("start", ep))
        else:
            ep = self.episode
            if pos:
                ep._hz.append(w.peak_hz)
                ep._dps.append(w.tremor_dps)
                self._refresh(ep)
            last = list(self._recent)[-END_N:]
            if len(last) == END_N and sum(ok for ok, _ in last) <= END_K:
                # The episode ended after the last positive window.
                last_pos = [x for ok, x in self._recent if ok]
                ep.end_ms = last_pos[-1].t_end_ms if last_pos else w.t_end_ms
                self.episode = None
                events.append(("end", ep))
        return events

    def _refresh(self, ep: TremorEpisode):
        ep.hz = round(float(np.median(ep._hz)), 1)
        ep.peak_dps = float(np.max(ep._dps))
        ep.strength = strength(float(np.median(ep._dps)))
        # Margin over threshold, mapped to 0-1: 0.5 at T, 0.83 at 3 x T, towards 1 above.
        ratio = float(np.median(ep._dps)) / max(self.params.threshold_dps, 1e-9)
        ep.confidence = round(1 - 0.5 / ratio if ratio >= 1 else 0.5 * ratio, 3)

    def finish(self, t_end_ms: int) -> list[tuple[str, TremorEpisode]]:
        """Close an open episode at the end of a recording."""
        if self.episode is None:
            return []
        ep = self.episode
        ep.end_ms = t_end_ms
        self.episode = None
        return [("end", ep)]


def detect(windows: Iterable[WindowFeatures], params: Optional[TremorParams] = None) -> list[TremorEpisode]:
    """All finished episodes of a recording."""
    det = TremorDetector(params)
    out: list[TremorEpisode] = []
    last_t = 0
    for w in windows:
        last_t = w.t_end_ms
        out += [ep for kind, ep in det.update(w) if kind == "end"]
    out += [ep for _, ep in det.finish(last_t)]
    return out
