"""pilot_analysis.py: timings around a press, and the H1 / H3 / H12 verdicts."""

import json

import numpy as np
import pytest

from daedalus_watch_kit import pilot
from daedalus_watch_kit.pilot_analysis import (
    PressTiming, analyse, load_press, press_timing, report, verdicts,
)
from daedalus_watch_kit.stream_features import FS

G = 9.80665
T0 = 1_794_570_000_000


def press_file(walk_until_s, resume_at_s, press_at_s, beat_delay_s=0.5, place="wrist",
               checkin="helped", participant="P01", index=0, seconds=90, seed=0):
    """A synthetic press: walking at 100 steps/min, a freeze (standing still), walking again.
    Times are seconds from the start of the 90 s buffer."""
    rng = np.random.default_rng(seed)
    n = int(seconds * FS)
    t = np.arange(n) / FS
    walking = (t < walk_until_s) | (t >= resume_at_s)
    step_hz, stride_hz = 100 / 60, 100 / 120
    bump = 2.0 * (np.sin(2 * np.pi * step_hz * t) + 0.5 * np.sin(2 * np.pi * stride_hz * t)) * walking
    acc = np.vstack([0.3 * bump, 0 * t, G + bump]) + rng.normal(0, 0.02, (3, n))
    swing = np.radians(60) * np.sqrt(2) * np.sin(2 * np.pi * stride_hz * t) * walking
    gyr = np.vstack([0 * t, swing, 0 * t]) + rng.normal(0, 0.002, (3, n))
    t_ms = (T0 + t * 1000).astype(np.int64)
    return {
        "version": pilot.VERSION, "participant": participant, "press_index": index, "day_index": 10,
        "place": place, "earbuds_connected": True, "phone_carried": True, "pace_spm": 100,
        "press_ms": int(T0 + press_at_s * 1000), "beat_start_ms": int(T0 + (press_at_s + beat_delay_s) * 1000),
        "beat_stop_ms": int(T0 + (resume_at_s + 3) * 1000), "checkin": checkin,
        "samples": {"t_ms": t_ms.tolist(), "acc": acc.tolist(), "gyr": gyr.tolist()},
    }


def test_timings_of_a_freeze_then_press_then_walking():
    # Walks until 25 s, frozen, presses at 28 s, beat at 28.5 s, walks again from 33 s.
    t = press_timing(press_file(walk_until_s=25, resume_at_s=33, press_at_s=28))
    assert t.stop_to_beat_s == pytest.approx(3.5, abs=0.5)
    assert t.press_to_steps_s == pytest.approx(5.0, abs=0.5)


@pytest.mark.parametrize("walk_until, resume, press", [(25, 30, 26), (20, 40, 22), (28, 31, 29)])
def test_timings_track_the_true_values(walk_until, resume, press):
    t = press_timing(press_file(walk_until, resume, press))
    assert t.stop_to_beat_s == pytest.approx(press + 0.5 - walk_until, abs=0.5)
    assert t.press_to_steps_s == pytest.approx(resume - press, abs=0.5)


def test_no_walking_before_the_press_gives_no_stop_time():
    t = press_timing(press_file(walk_until_s=0, resume_at_s=40, press_at_s=30))
    assert t.stop_to_beat_s is None
    assert t.press_to_steps_s is not None


def test_never_walking_again_gives_no_resume_time():
    t = press_timing(press_file(walk_until_s=25, resume_at_s=999, press_at_s=28))
    assert t.press_to_steps_s is None


def test_load_press_refuses_other_versions(tmp_path):
    f = tmp_path / "p.json"
    f.write_text(json.dumps({"version": "pilot-0.9"}))
    with pytest.raises(ValueError):
        load_press(f)


def T(person, place, steps, stop=2.0, checkin="helped", i=0):
    return PressTiming(person, i, place, checkin, stop, steps)


def test_h1_fails_when_the_beat_arrives_late():
    ts = [T(f"P{p}", "wrist", 5.0, stop=6.0) for p in range(4) for _ in range(3)]
    assert verdicts(ts).h1_fails is True


def test_h1_fails_when_most_say_already_moving():
    ts = [T(f"P{p}", "wrist", 5.0, stop=1.0, checkin="already_moving") for p in range(4)]
    assert verdicts(ts).h1_fails is True


def test_h1_holds_when_fast_and_helpful():
    ts = [T(f"P{p}", "wrist", 5.0, stop=2.0) for p in range(4)]
    assert verdicts(ts).h1_fails is False


def test_h3_and_h12_count_people_not_presses():
    ts = []
    for p in range(5):
        ts += [T(f"P{p}", "wrist", 6.0) for _ in range(3)]
        ts += [T(f"P{p}", "earbuds", 4.0 if p < 3 else 8.0) for _ in range(3)]
        ts += [T(f"P{p}", "pocket", 4.0 if p < 2 else 8.0) for _ in range(3)]
    # One heavy presser who prefers the pocket can't swing the people count.
    ts += [T("P0", "pocket", 1.0) for _ in range(50)]
    v = verdicts(ts)
    assert v.h3_earbuds_faster == (3, 5) and v.h3_sound_leads is True
    assert v.h12_pocket_faster == (2, 5) and v.h12_build_device is False


def test_h12_needs_the_pocket_to_be_felt():
    ts = []
    for p in range(3):
        ts += [T(f"P{p}", "wrist", 6.0) for _ in range(3)]
        ts += [T(f"P{p}", "pocket", 4.0, checkin="not_felt" if i == 0 else "helped", i=i) for i in range(3)]
    v = verdicts(ts)
    assert v.h12_pocket_faster == (3, 3)
    assert v.h12_pocket_not_felt_share == pytest.approx(1 / 3)
    assert v.h12_build_device is False


def test_people_with_too_few_presses_are_left_out_of_comparisons():
    ts = [T("P0", "wrist", 6.0), T("P0", "earbuds", 1.0)]
    assert verdicts(ts).h3_earbuds_faster == (0, 0)
    assert verdicts(ts).h3_sound_leads is None


def test_analyse_folder_and_report(tmp_path):
    for i in range(2):
        (tmp_path / f"P01_{i}.json").write_text(json.dumps(
            press_file(25, 33, 28, index=i, seed=i)))
    timings, v = analyse(tmp_path)
    assert len(timings) == 2 and v.people == 1
    text = report(v)
    assert "H1" in text and "H12" in text and "H2  description only" in text


def test_press_file_without_rotation_still_times_walking():
    d = press_file(25, 33, 28)
    d["samples"]["gyr"] = None
    t = press_timing(d)
    assert t.stop_to_beat_s == pytest.approx(3.5, abs=0.5)
    assert t.press_to_steps_s == pytest.approx(5.0, abs=0.5)


def test_press_without_motion_keeps_its_checkin_but_no_timings():
    d = press_file(25, 33, 28, checkin="already_moving")
    d["samples"] = {"t_ms": [], "acc": [[], [], []], "gyr": None}
    t = press_timing(d)
    assert (t.stop_to_beat_s, t.press_to_steps_s, t.checkin) == (None, None, "already_moving")


def test_unconfirmed_beat_start_is_not_timed_as_instant():
    d = press_file(25, 33, 28)
    d["beat_start_ms"] = None
    t = press_timing(d)
    assert t.stop_to_beat_s is None and t.press_to_steps_s is None


def test_standing_long_before_the_press_is_not_counted_as_beat_delay():
    # Review 001 #1: walked to a counter, stood 20 s, then froze trying to start and pressed.
    t = press_timing(press_file(walk_until_s=8, resume_at_s=33, press_at_s=28))
    assert t.stop_to_beat_s is None
    assert t.press_to_steps_s == pytest.approx(5.0, abs=0.5)


def test_pocket_not_felt_share_is_per_person():
    # Review 001 #2: one heavy presser saying "didn't feel it" can't decide H12 alone.
    ts = []
    for p in range(3):
        ts += [T(f"P{p}", "wrist", 6.0) for _ in range(3)]
        ts += [T(f"P{p}", "pocket", 4.0) for _ in range(3)]
    ts += [T("P0", "pocket", 4.0, checkin="not_felt") for _ in range(30)]
    v = verdicts(ts)
    assert v.h12_pocket_not_felt_share == pytest.approx(0.0)
    assert v.h12_build_device is True


def test_unreadable_or_foreign_files_are_skipped_and_named(tmp_path):
    # Review 001 #3
    (tmp_path / "P01_0.json").write_text(json.dumps(press_file(25, 33, 28)))
    (tmp_path / "old.json").write_text(json.dumps({"version": "pilot-0.9"}))
    (tmp_path / "junk.json").write_text("{not json")
    timings, v = analyse(tmp_path)
    assert len(timings) == 1
    assert any("old.json" in n for n in v.notes) and any("junk.json" in n for n in v.notes)


def test_non_object_json_is_skipped(tmp_path):
    # Review 002 #1
    (tmp_path / "P01_0.json").write_text(json.dumps(press_file(25, 33, 28)))
    (tmp_path / "list.json").write_text("[]")
    timings, v = analyse(tmp_path)
    assert len(timings) == 1 and any("list.json" in n for n in v.notes)


@pytest.mark.parametrize("flag", ["motion_partial", "recovered"])
def test_partial_or_recovered_motion_has_no_timings(flag):
    # Review 002 #2: the platforms flag incomplete motion under two names.
    d = press_file(25, 33, 28, checkin="helped")
    d[flag] = True
    t = press_timing(d)
    assert (t.stop_to_beat_s, t.press_to_steps_s, t.checkin) == (None, None, "helped")


def test_duplicate_copies_of_one_press_count_once(tmp_path):
    # Review 002 #3: a re-sent file kept as _2 / -2.
    d = press_file(25, 33, 28)
    (tmp_path / "press_P01_00000_1.json").write_text(json.dumps(d))
    short = dict(d, samples={"t_ms": [], "acc": [[], [], []], "gyr": None})
    (tmp_path / "press_P01_00000_1_2.json").write_text(json.dumps(short))
    timings, v = analyse(tmp_path)
    assert len(timings) == 1 and timings[0].press_to_steps_s is not None
    assert any("duplicate" in n for n in v.notes)


def test_not_felt_counts_only_confirmed_beats():
    # Review 002 #5: a pocket press stopped before the phone confirmed played nothing there.
    ts = []
    for p in range(3):
        ts += [T(f"P{p}", "wrist", 6.0) for _ in range(3)]
        ts += [T(f"P{p}", "pocket", 4.0) for _ in range(3)]
        ts += [PressTiming(f"P{p}", 9, "pocket", "not_felt", None, None, beat_confirmed=False)]
    v = verdicts(ts)
    assert v.h12_pocket_not_felt_share == pytest.approx(0.0)
