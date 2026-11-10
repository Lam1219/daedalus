"""Freezing-of-gait detector from the wrist, fog-1.0 (Documentation/automatic-cues-design.md, 2.5).

It consumes one stream_features.WindowFeatures per second during a walking guard and reports
freeze candidates. In v1 the watch starts the walking cue on a candidate; this module does not cue.

  Walking state: WALKING or TURNING windows for >= WALK_MIN_S seconds in a row. The detector
  keeps the walk's median cadence and arm swing.

  Freeze candidate, within CANDIDATE_WINDOW_S windows after walking stops, when all hold:
    1. trembling in place: freeze_index >= the person's threshold; or shuffling: cadence lost
       (step_regularity < LOST_REGULARITY) with arm swing below ARM_SWING_SHARE of the walk's
       median while the wrist is not still. (A steady tremble repeats at stride lags, so
       regularity can't be the test for trembling.)
    2. no posture change (gravity_change_deg < POSTURE_CHANGE_DEG), so the person did not sit down.
  If the walk was turning in its last TURN_LOOKBACK_S seconds, conditions 1-2 must hold for
  2 windows in a row (turns produce brief irregular steps).

  Voluntary stop: the wrist fully still (STILL) for STILL_CANCEL_S windows cancels the pending
  candidate and ends the walk. Akinetic freezes look the same from the wrist; "I'm stuck" covers
  them.

After a candidate the detector waits COOLDOWN_S seconds, and needs a new walking state, before
another one.

Thresholds are provisional, to be tuned on labelled FoG data (design phase 0, task T5).
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Iterable, Optional

import numpy as np

from .stream_features import Context, WindowFeatures

VERSION = "fog-1.0"

WALK_MIN_S = 4
CANDIDATE_WINDOW_S = 3       # windows after the last walking one (the 4 s gait window lags a stop)
LOST_REGULARITY = 0.3
FREEZE_INDEX_DEFAULT = 2.0
ARM_SWING_SHARE = 0.3
POSTURE_CHANGE_DEG = 15.0
TURN_LOOKBACK_S = 3
STILL_CANCEL_S = 3
COOLDOWN_S = 20


@dataclass
class FogParams:
    freeze_index_threshold: float = FREEZE_INDEX_DEFAULT
    cadence_spm: Optional[float] = None       # the person's walking-cue rate, if known


@dataclass
class FogCandidate:
    detected_ms: int
    walk_start_ms: int
    walk_end_ms: int            # end of the last walking window
    walk_cadence_spm: float
    reason: str                 # "freeze_index" or "arm_swing"
    freeze_index: float
    arm_swing_share: float
    turning: bool
    confidence: float
    version: str = VERSION


class FogDetector:
    def __init__(self, params: Optional[FogParams] = None):
        self.params = params or FogParams()
        self._walk: list[WindowFeatures] = []          # current or just-ended walk
        self._walk_end: Optional[WindowFeatures] = None  # last walking window once the walk stopped
        self._since_stop = 0
        self._pending = 0                              # consecutive windows meeting 1-2
        self._still = 0
        self._cooldown_until = -1
        self._recent: deque[WindowFeatures] = deque(maxlen=TURN_LOOKBACK_S)

    @staticmethod
    def _walking(w: WindowFeatures) -> bool:
        return w.context in (Context.WALKING, Context.TURNING)

    def update(self, w: WindowFeatures) -> Optional[FogCandidate]:
        if self._walking(w):
            if self._walk_end is not None:   # walking again after a stop: start a new walk
                self._walk, self._walk_end = [], None
            self._walk.append(w)
            self._recent.append(w)
            self._pending = self._still = self._since_stop = 0
            return None

        # Not walking.
        established = len(self._walk) >= WALK_MIN_S
        if not established:
            self._walk, self._walk_end = [], None
            return None
        if self._walk_end is None:
            self._walk_end = self._walk[-1]
        self._since_stop += 1

        if w.context == Context.STILL:
            self._still += 1
            if self._still >= STILL_CANCEL_S:
                self._reset()
            return None
        self._still = 0

        if self._since_stop > CANDIDATE_WINDOW_S:
            self._reset()
            return None
        if w.t_end_ms < self._cooldown_until:
            return None

        swing = float(np.median([x.arm_swing_dps for x in self._walk]))
        share = w.arm_swing_dps / swing if swing > 0 else 1.0
        trembling = w.freeze_index >= self.params.freeze_index_threshold
        shuffling = w.step_regularity < LOST_REGULARITY and share < ARM_SWING_SHARE
        ok = (trembling or shuffling) and w.gravity_change_deg < POSTURE_CHANGE_DEG
        self._pending = self._pending + 1 if ok else 0
        turning = any(x.context == Context.TURNING for x in self._recent)
        if self._pending < (2 if turning else 1):
            return None

        cand = FogCandidate(
            detected_ms=w.t_end_ms,
            walk_start_ms=self._walk[0].t_end_ms - 4000,
            walk_end_ms=self._walk_end.t_end_ms,
            walk_cadence_spm=round(float(np.median([x.cadence_spm for x in self._walk])), 1),
            reason="freeze_index" if trembling else "arm_swing",
            freeze_index=w.freeze_index,
            arm_swing_share=round(share, 3),
            turning=turning,
            confidence=self._confidence(w.freeze_index, share, trembling),
        )
        self._cooldown_until = w.t_end_ms + COOLDOWN_S * 1000
        self._reset()
        return cand

    def _confidence(self, fi: float, share: float, trembling: bool) -> float:
        if trembling:
            r = fi / max(self.params.freeze_index_threshold, 1e-9)
        else:
            r = ARM_SWING_SHARE / max(share, 1e-3)
        return round(min(1.0, 1 - 0.5 / r) if r >= 1 else 0.5 * r, 3)

    def _reset(self):
        self._walk, self._walk_end = [], None
        self._pending = self._still = self._since_stop = 0
        self._recent.clear()


def detect(windows: Iterable[WindowFeatures], params: Optional[FogParams] = None) -> list[FogCandidate]:
    det = FogDetector(params)
    return [c for w in windows if (c := det.update(w)) is not None]
