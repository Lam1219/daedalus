"""Home pilot rules (docs/product/plan.md): where each press plays its beat, when to ask the
one-tap check-in, and when to remind the person to pocket their phone.

This module is the reference for the watch and phone apps. The Kotlin and Swift versions must
give the same answers on the vectors in golden/pilot/ (made by golden/pilot/make_pilot_golden.py).
That is why the random draw is a fixed integer recipe (SplitMix64) rather than a library RNG:
it ports line for line with wrapping 64-bit arithmetic.

Rules, all from the owner's decisions of 2026-11-13:
- Every press gets a beat. Nothing here ever returns "no beat" (no ethics board, so no condition
  may withhold help).
- Days 0-6 after enrolment: rotate through the places available at that press.
- Days 7-20: draw at random, in equal shares, among the places available at that press.
- Day 21 on, or before enrolment: the wrist, the product default.
- Wrist is always available. Earbuds only when connected. Pocket only when the phone counted
  steps in the last minute (it is being carried, not lying on a table).
"""

from __future__ import annotations

from enum import Enum
from typing import Optional

VERSION = "pilot-1.0"


class Place(str, Enum):
    WRIST = "wrist"
    EARBUDS = "earbuds"
    POCKET = "pocket"


# Fixed order. The ports must list the places in this same order.
PLACES = (Place.WRIST, Place.EARBUDS, Place.POCKET)

LEARNING_DAYS = 7          # days 0-6
PILOT_DAYS = 21            # days 7-20 are randomized
MAX_SEED = 2**31           # seeds are 0 <= seed < 2^31 so every platform stores them as a plain int

MAX_CHECKINS_PER_DAY = 6
REMINDER_GAP_MS = 60 * 60 * 1000   # at most one pocket reminder an hour
PHONE_CARRIED_WINDOW_S = 60        # "the phone counted steps in the last minute"

STUDY_MAX_CUE_S = 10 * 60          # B6: in study mode a beat stops by itself after 10 minutes
BUFFER_BEFORE_S = 30               # B4: motion kept from 30 s before a press
BUFFER_AFTER_S = 60                # ...to 60 s after it


class CheckIn(str, Enum):
    HELPED = "helped"
    NOT_HELPED = "not_helped"
    ALREADY_MOVING = "already_moving"
    NOT_FELT = "not_felt"


_M64 = (1 << 64) - 1
_GAMMA = 0x9E3779B97F4A7C15


def splitmix64(x: int) -> int:
    """One SplitMix64 output for input x (Steele, Lea and Flood 2014). All arithmetic mod 2^64."""
    z = (x + _GAMMA) & _M64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & _M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & _M64
    return z ^ (z >> 31)


def draw(seed: int, press_index: int) -> int:
    """The 64-bit random number for this person's nth press. Stateless, so a reinstall that keeps
    the seed and the press counter repeats the same schedule."""
    return splitmix64((seed + press_index * _GAMMA) & _M64)


def available_places(earbuds_connected: bool, phone_carried: bool) -> list[Place]:
    out = [Place.WRIST]
    if earbuds_connected:
        out.append(Place.EARBUDS)
    if phone_carried:
        out.append(Place.POCKET)
    return out


def choose_place(seed: int, press_index: int, day_index: int,
                 earbuds_connected: bool, phone_carried: bool) -> Place:
    """Where this press plays its beat.

    seed: the person's seed, 0 <= seed < 2^31, set at enrolment.
    press_index: 0 for the first press after enrolment, counting every press.
    day_index: whole local calendar days since the enrolment day (0 on that day). Each platform
        computes it from local dates, so a time-zone change shifts the day the person lives in.
    """
    if not 0 <= seed < MAX_SEED:
        raise ValueError("seed out of range")
    if press_index < 0:
        raise ValueError("press_index must be >= 0")
    if day_index < 0 or day_index >= PILOT_DAYS:
        return Place.WRIST
    places = available_places(earbuds_connected, phone_carried)
    if day_index < LEARNING_DAYS:
        return places[press_index % len(places)]
    return places[draw(seed, press_index) % len(places)]


def should_ask_checkin(asked_today: int) -> bool:
    """Ask the one-tap check-in after a beat stops unless 6 were already asked today."""
    return asked_today < MAX_CHECKINS_PER_DAY


def should_remind_pocket(now_ms: int, stood_up: bool, phone_steps_last_min: int,
                         phone_moved_last_min: bool, last_reminder_ms: Optional[int],
                         beat_playing: bool, enabled: bool) -> bool:
    """B10: after a stand-up, remind the person to put the phone in the front pocket, but only
    when the phone shows no sign of coming along, never during a beat, and at most hourly."""
    if not enabled or not stood_up or beat_playing:
        return False
    if phone_steps_last_min > 0 or phone_moved_last_min:
        return False
    return last_reminder_ms is None or now_ms - last_reminder_ms >= REMINDER_GAP_MS
