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


def test_unconfirmed_pocket_press_with_full_motion_is_not_counted_as_felt_or_not(tmp_path):
    # Review 003 #1: goes through press_timing / analyse, not a hand-built PressTiming.
    d = press_file(25, 33, 28, place="pocket", checkin="not_felt")
    d["beat_start_ms"] = None
    assert press_timing(d).beat_confirmed is False
    for p in range(3):
        for i in range(3):
            (tmp_path / f"w{p}{i}.json").write_text(json.dumps(press_file(25, 33, 28, place="wrist", participant=f"P{p}", index=i, seed=i)))
            (tmp_path / f"k{p}{i}.json").write_text(json.dumps(press_file(25, 31, 28, place="pocket", participant=f"P{p}", index=10 + i, seed=i)))
        bad = dict(d, participant=f"P{p}", press_index=99, press_ms=d["press_ms"] + p)
        (tmp_path / f"u{p}.json").write_text(json.dumps(bad))
    _, v = analyse(tmp_path)
    assert v.h12_pocket_not_felt_share == pytest.approx(0.0)


def test_non_object_samples_is_skipped(tmp_path):
    # Review 003 #2
    (tmp_path / "ok.json").write_text(json.dumps(press_file(25, 33, 28)))
    d = press_file(25, 33, 28, index=1)
    d["samples"] = []
    (tmp_path / "bad.json").write_text(json.dumps(d))
    timings, v = analyse(tmp_path)
    assert len(timings) == 1 and any("bad.json" in n for n in v.notes)


def test_report_counts_null_starts_recovered_and_partial_per_place(tmp_path):
    # Review 004 #2: the plan's reopen triggers must be readable from the report.
    for i in range(4):
        d = press_file(25, 33, 28, place="earbuds", index=i, seed=i)
        if i < 2:
            d["beat_start_ms"] = None
        if i == 3:
            d["recovered"] = True
        (tmp_path / f"e{i}.json").write_text(json.dumps(d))
    _, v = analyse(tmp_path)
    assert v.data_quality["earbuds"] == {"presses": 4, "null_start": 2, "recovered": 1, "motion_partial": 0, "fell_back": 0}
    assert "earbuds: 4 presses, 2 with no beat start, 1 recovered, 0 partial, 0 fell back" in report(v)


def test_non_string_participant_or_place_is_skipped_not_fatal(tmp_path):
    # Review 005 #1
    (tmp_path / "ok.json").write_text(json.dumps(press_file(25, 33, 28)))
    d = press_file(25, 33, 28, index=1)
    d["place"] = None
    (tmp_path / "noplace.json").write_text(json.dumps(d))
    d2 = press_file(25, 33, 28, index=2)
    d2["participant"] = 7
    (tmp_path / "numeric.json").write_text(json.dumps(d2))
    timings, v = analyse(tmp_path)
    assert len(timings) == 1
    assert any("noplace.json" in n for n in v.notes) and any("numeric.json" in n for n in v.notes)


def test_per_person_counts_and_motion_partial(tmp_path):
    # Review 005 #2: the per-person row feeds the plan's 5%-recovered trigger.
    for i in range(3):
        d = press_file(25, 33, 28, participant="P02", index=i, seed=i)
        if i == 0:
            d["motion_partial"] = True
        if i == 1:
            d["recovered"] = True
        (tmp_path / f"p{i}.json").write_text(json.dumps(d))
    _, v = analyse(tmp_path)
    assert v.data_quality_by_person["P02"] == {"presses": 3, "null_start": 0, "recovered": 1, "motion_partial": 1, "fell_back": 0}
    assert v.data_quality["wrist"]["motion_partial"] == 1


def test_fallback_presses_leave_the_place_comparisons_and_are_counted(tmp_path):
    # Review 006 #1: an earbuds press that fell back to the wrist before starting must not count
    # as a wrist press (its time includes the wait for the player).
    for p in range(3):
        for i in range(3):
            (tmp_path / f"w{p}{i}.json").write_text(json.dumps(
                press_file(25, 33, 28, place="wrist", participant=f"P{p}", index=i, seed=i)))
            fb = press_file(25, 40, 28, place="wrist", participant=f"P{p}", index=10 + i, seed=i)
            fb["place_drawn"] = "earbuds"
            fb["fallback_reason"] = "no_reply"
            (tmp_path / f"f{p}{i}.json").write_text(json.dumps(fb))
        for i in range(3):
            (tmp_path / f"e{p}{i}.json").write_text(json.dumps(
                press_file(25, 36, 28, place="earbuds", participant=f"P{p}", index=20 + i, seed=i)))
    timings, v = analyse(tmp_path)
    assert len([t for t in timings if t.fell_back]) == 9
    assert v.data_quality["wrist"]["fell_back"] == 9
    # Wrist is 5 s on real wrist presses; earbuds 8 s. Counting the 12 s fallbacks as wrist would
    # make earbuds look faster (median wrist 8.5 s). Without them, earbuds are slower for everyone.
    assert v.h3_earbuds_faster == (0, 3)


def test_counts_per_person_per_place(tmp_path):
    # Review 006 #2: the plan's trigger is "null earbud starts over 10% of THEIR earbud presses".
    for i in range(4):
        d = press_file(25, 33, 28, place="earbuds", participant="P07", index=i, seed=i)
        if i == 0:
            d["beat_start_ms"] = None
        (tmp_path / f"e{i}.json").write_text(json.dumps(d))
    _, v = analyse(tmp_path)
    assert v.data_quality_by_person_place["P07"]["earbuds"]["null_start"] == 1
    assert v.data_quality_by_person_place["P07"]["earbuds"]["presses"] == 4


@pytest.mark.parametrize("field,value", [("press_index", [1]), ("press_ms", {"a": 1}), ("press_index", 1.5)])
def test_non_integer_press_index_or_time_is_skipped(tmp_path, field, value):
    # Review 006 #3
    (tmp_path / "ok.json").write_text(json.dumps(press_file(25, 33, 28)))
    d = press_file(25, 33, 28, index=1)
    d[field] = value
    (tmp_path / "bad.json").write_text(json.dumps(d))
    timings, v = analyse(tmp_path)
    assert len(timings) == 1 and any("bad.json" in n for n in v.notes)


def test_counts_by_place_drawn_carry_the_earbuds_trigger(tmp_path):
    # Review 007 #2: an earbuds press that fell back before starting is written as place "wrist",
    # place_drawn "earbuds". The trigger needs it counted against earbuds.
    ok = press_file(25, 33, 28, place="earbuds", participant="P05", index=0)
    ok["place_drawn"] = "earbuds"
    fb = press_file(25, 33, 28, place="wrist", participant="P05", index=1, seed=1)
    fb["place_drawn"] = "earbuds"
    fb["fallback_reason"] = "no_reply"
    nul = press_file(25, 33, 28, place="earbuds", participant="P05", index=2, seed=2)
    nul["place_drawn"] = "earbuds"
    nul["beat_start_ms"] = None
    for i, d in enumerate((ok, fb, nul)):
        (tmp_path / f"p{i}.json").write_text(json.dumps(d))
    _, v = analyse(tmp_path)
    e = v.data_quality_by_person_drawn["P05"]["earbuds"]
    assert (e["drawn"], e["started_there"], e["fell_back_before_start"], e["null_start"]) == (3, 2, 1, 1)
    text = report(v)
    assert "P05 drew earbuds 3 times" in text
    assert "reopen" not in text   # review 008 #7: Android earbuds are cut from the pilot; no trigger line


@pytest.mark.parametrize("loss_after_resume_s, excluded", [(35, False), (-2, True)])
def test_mid_beat_loss_excludes_only_before_steps_resumed(loss_after_resume_s, excluded):
    # Review 007 #3: walking again at 33 s; earbuds drop at 68 s (kept) or 31 s (excluded).
    d = press_file(25, 33, 28, place="earbuds")
    d["place_drawn"] = "earbuds"
    d["fallback_reason"] = "earbuds_lost"
    d["fallback_ms"] = T0 + (33 + loss_after_resume_s) * 1000
    assert press_timing(d).fell_back is excluded


def test_unknown_drawn_place_stays_unknown():
    # Review 008 #6: a null place_drawn (an iOS file recovered from an older build) isn't guessed
    # from where the beat played, and doesn't count as a fallback before the start.
    d = press_file(25, 33, 28, place="wrist")
    d["place_drawn"] = None
    t = press_timing(d)
    assert t.place_drawn == "unknown"
    assert t.fell_back_before_start is False


def test_device_check_files_are_skipped(tmp_path):
    # Review 008 #4: files under the reserved test code CHECK are never a participant.
    (tmp_path / "ok.json").write_text(json.dumps(press_file(25, 33, 28)))
    (tmp_path / "chk.json").write_text(json.dumps(press_file(25, 33, 28, participant="CHECK", index=1)))
    timings, v = analyse(tmp_path)
    assert {t.participant for t in timings} == {"P01"} and v.people == 1
    assert any("chk.json" in n for n in v.notes)
