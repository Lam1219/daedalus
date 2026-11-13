"""Writes golden/pilot/pilot.json: the vectors the Kotlin and Swift ports of pilot.py must match.

Run from daedalus_watch_kit/:  python golden/pilot/make_pilot_golden.py
64-bit numbers are written as hex strings, because JSON parsers on the phones lose precision
above 2^53 or 2^63.
"""

import json
from pathlib import Path

from daedalus_watch_kit import pilot as p

OUT = Path(__file__).resolve().parent / "pilot.json"

SEEDS = [0, 1, 42, 123456789, p.MAX_SEED - 1]
DAYS = [-1, 0, 6, 7, 13, 20, 21]


def payload() -> dict:
    splitmix = [{"input": f"{x:016x}", "output": f"{p.splitmix64(x):016x}"}
                for x in [0, 1, 2, 0xFFFFFFFFFFFFFFFF, 0x9E3779B97F4A7C15, 1234567890123]]
    draws = [{"seed": s, "press_index": i, "draw": f"{p.draw(s, i):016x}"}
             for s in SEEDS for i in [0, 1, 2, 99, 100_000]]
    places = []
    for s in SEEDS[:3]:
        for d in DAYS:
            for i in range(12):
                for ear in (False, True):
                    for pocket in (False, True):
                        places.append({"seed": s, "press_index": i, "day_index": d,
                                       "earbuds_connected": ear, "phone_carried": pocket,
                                       "place": p.choose_place(s, i, d, ear, pocket).value})
    hour = p.REMINDER_GAP_MS
    reminders = []
    for enabled in (True, False):
        for stood in (True, False):
            for steps in (0, 5):
                for moved in (False, True):
                    for last in (None, 1_000_000, 1_000_000 + hour - 1, 1_000_000 + hour):
                        for playing in (False, True):
                            now = 1_000_000 + 2 * hour if last == 1_000_000 + hour else 1_000_000 + hour
                            if last == 1_000_000 + hour - 1:
                                now = 1_000_000 + 2 * hour - 2
                            reminders.append({
                                "now_ms": now, "stood_up": stood, "phone_steps_last_min": steps,
                                "phone_moved_last_min": moved, "last_reminder_ms": last,
                                "beat_playing": playing, "enabled": enabled,
                                "remind": p.should_remind_pocket(now, stood, steps, moved, last, playing, enabled),
                            })
    checkins = [{"asked_today": n, "ask": p.should_ask_checkin(n)} for n in range(0, 9)]
    return {
        "version": p.VERSION,
        "places_order": [x.value for x in p.PLACES],
        "constants": {
            "learning_days": p.LEARNING_DAYS, "pilot_days": p.PILOT_DAYS, "max_seed": p.MAX_SEED,
            "max_checkins_per_day": p.MAX_CHECKINS_PER_DAY, "reminder_gap_ms": p.REMINDER_GAP_MS,
            "phone_carried_window_s": p.PHONE_CARRIED_WINDOW_S, "study_max_cue_s": p.STUDY_MAX_CUE_S,
            "buffer_before_s": p.BUFFER_BEFORE_S, "buffer_after_s": p.BUFFER_AFTER_S,
        },
        "splitmix64": splitmix,
        "draws": draws,
        "choose_place": places,
        "should_remind_pocket": reminders,
        "should_ask_checkin": checkins,
    }


if __name__ == "__main__":
    OUT.write_text(json.dumps(payload(), indent=1) + "\n")
    print(f"wrote {OUT}")
