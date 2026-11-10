"""The automatic-cue event labels must be the same in every copy (Kotlin, Swift, Python)."""

from pathlib import Path

import pytest

from daedalus_watch_kit.event_labels import ALL_EVENTS, AUTO_CUE_EVENTS

REPO = Path(__file__).resolve().parents[2]
COPIES = [
    "daedalus-shared/src/main/java/com/medicina/shared/EventLabels.kt",
    "daedalus-companion/app/src/main/java/com/medicina/daedaluscompanion/model/EventLabels.kt",
    "daedalus-watch/app/src/main/java/com/medicina/daedaluswatch/model/DataModels.kt",
    "daedalus-companion-ios/Shared/AutoCueEvents.swift",
]


def test_auto_labels_are_in_all_events():
    assert set(AUTO_CUE_EVENTS) <= set(ALL_EVENTS)
    assert len(AUTO_CUE_EVENTS) == len(set(AUTO_CUE_EVENTS)) == 15


@pytest.mark.parametrize("path", COPIES)
def test_every_copy_has_every_label(path):
    text = (REPO / path).read_text()
    missing = [n for n in AUTO_CUE_EVENTS if f'"{n}"' not in text]
    assert not missing, f"{path} lacks {missing}"
