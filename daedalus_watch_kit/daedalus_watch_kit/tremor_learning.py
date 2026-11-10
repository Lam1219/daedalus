"""Personal tremor threshold learned from shadow-mode history (tremor-learning-1.0).

In shadow mode the watch logs every 1 s window. This module looks at that history, without
labels, and decides whether the person has a consistent rest tremor the population threshold
misses (a slight tremor, 1-3 deg/s). Only then does it lower T, and never above the population
value. People without tremor keep the population threshold.

Steps:
  1. Candidates: windows at rest (REST_TREMOR_POSSIBLE or STILL) with a clear peak
     (prominence >= CANDIDATE_PROMINENCE) in 3.5-7.5 Hz and tremor_dps >= T_MIN.
  2. The person's rhythm: the most common candidate frequency (0.25 Hz histogram, smoothed over
     ±1 bin). Consistent candidates are those within ±HZ_TOLERANCE of it.
  3. Evidence: minutes of consistent candidates and their share of all candidates. Voluntary
     rhythmic movements in healthy controls add up to well under a minute over a 4 h visit
     (Parkinson@Home), and they spread over frequencies.
  4. If evidence_min >= min_evidence_min and share >= min_share:
       T = clip(quantile_factor x the 25th percentile of the consistent candidates' tremor_dps,
                T_MIN, population T)
     and the accepted band narrows to the person's rhythm ± PERSONAL_BAND_HZ (tremor_stream).
     Otherwise T = population T and the band stays 3.5-7.5 Hz.

The result is a TremorParams to use from then on, plus what it was based on, for the log.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Iterable, Optional

import numpy as np

from .stream_features import Context, WindowFeatures
from .tremor_stream import BAND_HIGH_HZ, BAND_LOW_HZ, T_MIN, TremorParams

VERSION = "tremor-learning-1.0"

CANDIDATE_PROMINENCE = 16.0
HZ_BIN = 0.25
HZ_TOLERANCE = 0.75
MIN_EVIDENCE_MIN = 5.0
MIN_SHARE = 0.5
QUANTILE_FACTOR = 0.8


@dataclass
class Learned:
    params: TremorParams
    personalised: bool
    person_hz: Optional[float]
    evidence_min: float
    share: float
    candidate_dps_p25: Optional[float]
    version: str = VERSION


def learn(history: Iterable[WindowFeatures], population: TremorParams,
          min_evidence_min: float = MIN_EVIDENCE_MIN, min_share: float = MIN_SHARE,
          quantile_factor: float = QUANTILE_FACTOR) -> Learned:
    hz, dps = [], []
    for w in history:
        if (w.context in (Context.REST_TREMOR_POSSIBLE, Context.STILL)
                and BAND_LOW_HZ <= w.peak_hz <= BAND_HIGH_HZ
                and w.prominence >= CANDIDATE_PROMINENCE and w.tremor_dps >= T_MIN):
            hz.append(w.peak_hz)
            dps.append(w.tremor_dps)
    if not hz:
        return Learned(population, False, None, 0.0, 0.0, None)
    hz, dps = np.asarray(hz), np.asarray(dps)

    edges = np.arange(BAND_LOW_HZ, BAND_HIGH_HZ + HZ_BIN, HZ_BIN)
    counts, _ = np.histogram(hz, bins=edges)
    smooth = np.convolve(counts, np.ones(3), mode="same")
    mode = float(edges[int(np.argmax(smooth))] + HZ_BIN / 2)
    near = np.abs(hz - mode) <= HZ_TOLERANCE
    evidence_min = float(near.sum()) / 60.0
    share = float(near.mean())
    p25 = float(np.percentile(dps[near], 25)) if near.any() else None

    if evidence_min >= min_evidence_min and share >= min_share and p25 is not None:
        t = float(np.clip(quantile_factor * p25, T_MIN, population.threshold_dps))
        params = replace(population, threshold_dps=t, person_hz=round(mode, 2))
        return Learned(params, True, round(mode, 2), round(evidence_min, 2), round(share, 3), p25)
    return Learned(population, False, round(mode, 2), round(evidence_min, 2), round(share, 3), p25)
