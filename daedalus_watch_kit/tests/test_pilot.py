"""pilot.py: the home pilot's place schedule, check-in limit and pocket reminder."""

import importlib.util
import json
from collections import Counter
from pathlib import Path

import pytest

from daedalus_watch_kit import pilot as p
from daedalus_watch_kit.pilot import Place

GOLDEN = Path(__file__).resolve().parents[1] / "golden" / "pilot"
spec = importlib.util.spec_from_file_location("make_pilot_golden", GOLDEN / "make_pilot_golden.py")
make = importlib.util.module_from_spec(spec)
spec.loader.exec_module(make)


def test_splitmix64_matches_the_published_first_output():
    # SplitMix64 seeded with 0 yields 0xe220a8397b1dcdaf first (reference implementation).
    assert p.splitmix64(0) == 0xE220A8397B1DCDAF


def test_golden_file_matches_reference():
    stored = json.loads((GOLDEN / "pilot.json").read_text())
    assert stored == make.payload(), "regenerate with golden/pilot/make_pilot_golden.py and update the ports"


def test_every_press_gets_a_beat():
    for seed in (0, 7, 99):
        for day in range(-2, 25):
            for i in range(50):
                for ear in (False, True):
                    for pocket in (False, True):
                        assert p.choose_place(seed, i, day, ear, pocket) in Place


def test_unavailable_places_are_never_chosen():
    for i in range(500):
        for day in (0, 3, 7, 15):
            assert p.choose_place(5, i, day, False, False) is Place.WRIST
            assert p.choose_place(5, i, day, True, False) is not Place.POCKET
            assert p.choose_place(5, i, day, False, True) is not Place.EARBUDS


def test_randomized_days_draw_equal_shares_within_3_percent():
    for seed in (1, 2024, p.MAX_SEED - 1):
        counts = Counter(p.choose_place(seed, i, 10, True, True) for i in range(3000))
        for place in Place:
            assert abs(counts[place] / 3000 - 1 / 3) < 0.03, (seed, counts)
        two = Counter(p.choose_place(seed, i, 10, True, False) for i in range(1000))
        assert abs(two[Place.WRIST] / 1000 - 0.5) < 0.03


def test_learning_week_rotates_through_available_places():
    got = [p.choose_place(3, i, 2, True, True) for i in range(6)]
    assert got == [Place.WRIST, Place.EARBUDS, Place.POCKET] * 2


def test_outside_the_pilot_the_beat_plays_on_the_wrist():
    assert p.choose_place(3, 5, -1, True, True) is Place.WRIST
    assert p.choose_place(3, 5, p.PILOT_DAYS, True, True) is Place.WRIST


def test_schedule_is_repeatable_from_seed_and_counter():
    a = [p.choose_place(77, i, 9, True, True) for i in range(100)]
    b = [p.choose_place(77, i, 9, True, True) for i in range(100)]
    assert a == b
    assert a != [p.choose_place(78, i, 9, True, True) for i in range(100)]


@pytest.mark.parametrize("seed", [-1, p.MAX_SEED])
def test_seed_out_of_range_is_refused(seed):
    with pytest.raises(ValueError):
        p.choose_place(seed, 0, 10, True, True)


def test_checkin_stops_after_six_a_day():
    assert [p.should_ask_checkin(n) for n in range(8)] == [True] * 6 + [False] * 2


def test_pocket_reminder_rules():
    hour = p.REMINDER_GAP_MS
    base = dict(now_ms=10 * hour, stood_up=True, phone_steps_last_min=0, phone_moved_last_min=False,
                last_reminder_ms=None, beat_playing=False, enabled=True)
    assert p.should_remind_pocket(**base)
    assert not p.should_remind_pocket(**{**base, "phone_steps_last_min": 3})
    assert not p.should_remind_pocket(**{**base, "phone_moved_last_min": True})
    assert not p.should_remind_pocket(**{**base, "stood_up": False})
    assert not p.should_remind_pocket(**{**base, "beat_playing": True})
    assert not p.should_remind_pocket(**{**base, "enabled": False})
    assert not p.should_remind_pocket(**{**base, "last_reminder_ms": 9 * hour + 1})
    assert p.should_remind_pocket(**{**base, "last_reminder_ms": 9 * hour})
