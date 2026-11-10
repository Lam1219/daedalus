"""The golden vectors (golden/*.json) must match what the Python reference computes now.

If this fails after a deliberate change, regenerate them (golden/make_golden.py), bump the
module VERSION, and update the Kotlin and Swift ports to match.
"""

import importlib.util
import json
import math
from pathlib import Path

import pytest

from daedalus_watch_kit import stream_features as sf
from daedalus_watch_kit import tremor_stream as ts

GOLDEN = Path(__file__).resolve().parents[1] / "golden"
spec = importlib.util.spec_from_file_location("make_golden", GOLDEN / "make_golden.py")
make_golden = importlib.util.module_from_spec(spec)
spec.loader.exec_module(make_golden)

FRESH = make_golden.all_payloads()
FILES = sorted(GOLDEN.glob("*.json"))


def close(a, b, tol, path="$"):
    if isinstance(a, dict):
        assert a.keys() == b.keys(), path
        for k in a:
            close(a[k], b[k], tol, f"{path}.{k}")
    elif isinstance(a, list):
        assert len(a) == len(b), f"{path}: {len(a)} vs {len(b)} items"
        for i, (x, y) in enumerate(zip(a, b)):
            close(x, y, tol, f"{path}[{i}]")
    elif isinstance(a, float) or isinstance(b, float):
        assert math.isclose(a, b, rel_tol=tol["relative"], abs_tol=tol["absolute"]), f"{path}: {a} vs {b}"
    else:
        assert a == b, f"{path}: {a!r} vs {b!r}"


def test_every_case_has_a_file():
    assert {f.stem for f in FILES} == set(FRESH)


@pytest.mark.parametrize("path", FILES, ids=lambda p: p.stem)
def test_file_matches_reference(path):
    stored = json.loads(path.read_text())
    close(stored, FRESH[path.stem], stored["tolerance"])


def test_files_name_current_versions():
    for f in FILES:
        v = json.loads(f.read_text())["versions"]
        assert v["features"] == sf.VERSION and v["tremor"] == ts.VERSION


def test_cases_cover_the_outcomes():
    e = {k: p["expected"] for k, p in FRESH.items()}
    assert len(e["rest_tremor_episode"]["tremor_episodes"]) == 1
    assert len(e["slight_tremor_personal_threshold"]["tremor_episodes"]) == 1
    assert e["voluntary_rhythm_rejected"]["tremor_episodes"] == []
    assert len(e["walk_then_tremble"]["fog_candidates"]) == 1
    assert e["walk_then_stop"]["fog_candidates"] == []
    assert any(w["context"] == "turning" for w in e["walking_turn"]["windows"])
    assert FRESH["jittered_timestamps"]["resample"] is True
    assert len(e["jittered_timestamps"]["tremor_episodes"]) == 1
