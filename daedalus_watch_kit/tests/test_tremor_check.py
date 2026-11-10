"""tremor-1.0 Python twin: the same cases as TremorAnalyzerTest.kt."""

import numpy as np
import pytest

from daedalus_watch_kit.tremor_check import analyze

DEG = np.pi / 180


def record(hz, dps, seconds=20.0, sway_dps=0.0, noise_dps=0.2, seed=1):
    rng = np.random.default_rng(seed)
    t = [0.0]
    while t[-1] < seconds * 1000:
        t.append(t[-1] + 20 + rng.uniform(-4, 4))
    t = np.array(t[:-1])
    s = t / 1000
    tremor = 0 if hz is None else dps * DEG * np.sin(2 * np.pi * (hz or 0) * s)
    sway = sway_dps * DEG * np.sqrt(2) * np.sin(2 * np.pi * 0.8 * s)
    noise = lambda: rng.normal(0, noise_dps * DEG, len(t))
    return analyze(t, tremor + sway + noise(), 0.9 * tremor + noise(), noise())


def test_tremor_found_with_frequency():
    r = record(5.0, 6.0)
    assert r.outcome == "tremor"
    assert r.frequency_hz == pytest.approx(5.0, abs=0.1)
    assert r.strength == "light"


def test_strength_levels():
    assert record(4.5, 20.0).strength == "moderate"
    assert record(4.5, 60.0).strength == "strong"


def test_no_tremor():
    assert record(None, 0.0).outcome == "none"


def test_moved():
    assert record(None, 0.0, sway_dps=40).outcome == "moved"


def test_incomplete():
    assert record(5.0, 6.0, seconds=8).outcome == "incomplete"
