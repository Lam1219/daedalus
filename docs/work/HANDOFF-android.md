# Handoff: home pilot on Android phone + Wear OS watch

Branch `android-home-pilot`, cut from `feature/home-pilot` at 0812fae. Not pushed. This is a code handoff, not the stage done: nothing has run on a real phone or watch.

## Built

Everything sits behind **study mode**, off by default. With it off, both apps take the same code paths as before; the only shared changes are a new Profile row, a pilot screen and the build reading `daedalus-shared/`.

- **Shared Kotlin (`daedalus-shared/src/main/java/com/medicina/shared/pilot/`).** `daedalus-shared` wasn't compiled into either app before. Both `app/build.gradle.kts` files now add it as a source directory.
  - `Pilot.kt`: a line-for-line port of `pilot.py` (pilot-1.0). SplitMix64 uses `ULong` wrapping arithmetic. It also has `choosePlace`, `shouldAskCheckin`, `shouldRemindPocket`, the constants, and `dayIndex` (whole local calendar days via `java.time`).
  - `BeatRunner.kt`: `PressPlanner` picks who plays the drawn place. `BeatRunner` enforces "every press gets a beat". `BeatClock` puts the phone's beat start on the watch's clock.
  - `PressRecord.kt`: the press-file JSON writer, plus `CheckinDay` for the daily limit.
  - `MotionRing.kt`: a 120 s in-memory ring.
  - `ClickBeatPlayer.kt`: an AudioTrack click track with drift-free scheduling (`ClickSchedule`) and headphone detection (`AudioRoutes`).
  - `PilotSettings.kt` and `PilotLink.kt`: the settings and the watch–phone message formats.
- **B3, place per press (watch).** An "I'm stuck" tap on the watch passes its own tap time (`EXTRA_PRESS_MS`). In study mode, `HapticService` hands that walking cue to `pilot/PilotPresses.begin()`, which:
  - takes the next press index;
  - computes `day_index`;
  - reads the watch's earbuds and the phone's last-known earbuds and steps;
  - draws the place.
  The walking screen shows the place in small text ("On your wrist", "In your earbuds", "Phone in your pocket").
- **B2 / B9, players.**
  - Wrist: the existing motor thread. `beat_start_ms` is taken right after the first `vibrate()` call.
  - Earbuds on the watch: the watch plays the clicks itself.
  - Earbuds on the phone, or pocket: the watch sends `/pilot/beat start` and the phone's `PilotPhoneService` plays them. Pocket is 60 ms pulses at full amplitude, alarm usage, each one timed from the first. Earbuds is the same click player as the watch.
- **Never silence.** `BeatRunner` falls back to the wrist at once in three cases:
  - a player reports failure (no headphones, send failed, phone service off);
  - a player doesn't confirm its first click or pulse within 1 s (the phone is also told to stop);
  - a player is lost mid-beat (earbuds removed, audio error, phone reports failure).
  `place` records where the beat started. Extra fields `place_drawn`, `player`, `beat_start_source`, `fallback_reason` and `fallback_ms` record what happened.
- **B4, buffer.** In study mode `StandWatchService` runs all day. It registers the accelerometer and gyroscope at 50 Hz with a 10 s max report latency, preferring the wake-up variants, into the ring. One row is written per accelerometer sample, with the latest gyroscope value.
  - At press + 60 s the watch flushes the sensors, waits 1.5 s, and copies [press − 30 s, press + 60 s] out of the ring.
  - The file is written once the beat has stopped and the check-in is settled, as `filesDir/pilot/pilot_<code>_<index>_<press_ms>.json`.
  - It's sent to the phone over ChannelClient (`/pilot/file/…`), and the watch deletes its copy after the phone's ack.
- **Battery log.** Every 15 min, `battery_<code>_<yyyyMMdd>.csv` gets:
  - battery %;
  - whether the watch is on its charger;
  - the ring's sample total, which should grow by about 45,000 per 15 min and proves the sensors kept delivering.
  A header line records whether the sensors are wake-up variants and their FIFO sizes. Past days' files go to the phone with the press files.
- **B5, check-in.** When the beat stops, a 2×2 screen of large buttons shows the four answers. It closes unanswered after 30 s (`checkin: null`).
  - At most 6 are asked per local day, counted when shown (`CheckinDay`). The 7th press is logged without asking.
  - If the watch's app isn't on screen (for example the beat stopped at the 10-minute limit), a full-screen notification opens it.
- **B6.** `HapticService.sessionLimitMs()` gives every cue mode 10 minutes in study mode. Guards and everything outside study mode keep the hour.
- **B10, pocket reminder.**
  - After a detected stand-up, if study mode and the pocket reminder are on and no cue is running, the watch queries the phone.
  - The phone flushes its step counter and accelerometer, then replies with its last step and whether it moved in the last minute.
  - If `should_remind_pocket` says so, a gentle 40 ms tap, and "Phone in your front pocket?". With the stand-up prompt on, that is a line added to "Pause before walking"; with it off, it's a screen of its own.
  - No reply within 3 s counts as not carried.
  - The last reminder time is persisted, so the reminder comes at most once an hour.
- **Phone.**
  - Profile → **Home pilot** (`PilotScreen`) has:
    - the study switch, which can't be turned on until the code and seed are valid;
    - the pilot code (letters, digits and hyphen only, not a name);
    - the seed (0 to 2^31 − 1);
    - the enrolment date (date picker);
    - the pocket reminder switch.
  - Code, seed and date lock while study mode is on. Settings go to the watch on every change and whenever the app opens.
  - Turning study mode on asks for "physical activity" and notifications.
  - "Export pilot files" zips every pilot file and opens the share sheet. "Delete pilot files" asks for confirmation first.
  - `PilotPhoneService` (foreground, `health` type) restarts after boot or an update (`PilotBootReceiver`).
  - Pilot files never enter the session list, CloudBackup or Supabase. No new network calls.
- **Strings.** New English and French strings on both apps (`pilot_*`). The iOS string catalogs aren't regenerated here; that belongs to the iOS build, from these Android strings.

## Choices where the brief was silent (with reasons)

1. **Phone status is pushed, not asked for at the press.**
   - The phone pushes earbud changes, and fresh steps at most every 15 s, while walking.
   - The watch asks for a fresh status when its app comes on screen, so a press never waits for a round trip. A query at the press would add roughly 0.3–0.7 s to every press, wrist presses included, which works against H1.
   - Cost: the "carried" answer can be up to about 25 s stale (10 s batching plus 15 s throttle). That only matters when the last step is near the edge of the 60 s window.
2. **`beat_start_ms` for phone beats uses an NTP-style round trip, not clock alignment.**
   - The phone reports when it received the request, when the first pulse or click played, and when it replied, all on its own clock.
   - The watch subtracts the phone's processing time and half the remaining round trip (`BeatClock`). It never needs the two wall clocks to agree.
   - `beat_start_source` says `phone_vibration+rtt` or `phone_audio_timestamp+rtt`.
3. **Audio beat start** comes from `AudioTrack.getTimestamp` (when the first frame was presented). If no timestamp arrives within 500 ms, the `play()` call time stands in, tagged `audio_play_call`. Bluetooth latency after the output stage (typically 100–250 ms) may not be included. See "Where the spec needs correcting".
4. **`beat_stop_ms`** is the watch's time of the stop. For phone beats, the phone stops when the message arrives, typically a few hundred ms later.
5. **Mid-beat loss** switches to the wrist but keeps `place` as where the beat started (analysis keys on the start), and records `fallback_ms`.
6. **Pocket reminder as a line on the existing prompt** when the stand-up prompt is on (one screen per stand-up rather than two interruptions in a row), and as a screen of its own when it's off. The reminder setting is the "off with one setting" switch, on the phone.
7. **The phone's "moved in the last minute"** is any accelerometer axis changing by more than 1.5 m/s² within 60 s, at 10 Hz batched. A pocketed phone tilts when its owner stands; one on a table doesn't. The threshold is a first guess.
8. **"Phone carried"** uses TYPE_STEP_COUNTER (wake-up variant when the phone has one), falling back to TYPE_STEP_DETECTOR, with a 10 s max report latency. The first counter event after registering is treated as a running total, not a step. Before the service sees its first step, the phone counts as not carried.
9. **The check-in closes after 30 s.** It counts toward the daily 6 when shown, even if unanswered.
10. **The study cap applies to every cue mode in study mode**, not only presses ("study mode caps a cue at 10 minutes"). Guards keep the hour.
11. **A press inside a running guard session** keeps the existing guard behaviour (wrist, no press file). Guards are a lab tool, not part of the pilot.
12. **The press counter** lives on the watch and resets when the code or seed changes. A watch reinstall resets it too, so the schedule repeats from press 0. That's acceptable because the schedule is stateless, but the owner may want the phone to keep a copy.
13. **A press with fewer than 2 motion samples** (the ring wasn't running) is saved as `…json.nomotion`, outside `analyse()`'s `*.json` glob. `pilot_analysis` raises on empty samples, and one bad file would stop the whole folder.
14. ~~Pilot files are not removed by sign-out or "Delete my data".~~ Reversed in round 001 (#10): "Delete everything" and "Delete account" now delete pilot files too. Plain sign-out still leaves them, so a sign-out can't destroy unexported data.

## Where the spec needs correcting (with platform docs)

- **`pilot_analysis.press_timing` raises on a press with no samples** (`t_ms[-1]` on an empty array). It should skip such presses and count them, or the Android workaround in choice 13 is needed.
- **The audio "beat start" isn't the moment sound reaches the ear over Bluetooth.**
  - `AudioTrack.getTimestamp` reports frames presented at the output ([AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp)).
  - The A2DP or LE Audio codec delay may or may not be added, depending on the device.
  - For H3 (earbuds against wrist), the earbuds' beat may start 100–250 ms later than recorded.
  - Suggest measuring it once per earbud model at the clinic visit (a phone recording the earbud click and the watch tap) and correcting in analysis.
- **Background vibration on Android isn't covered by the documentation.**
  - [VibrationAttributes](https://developer.android.com/reference/android/os/VibrationAttributes) lists `USAGE_ALARM`, and says bypassing interruption settings (`FLAG_BYPASS_INTERRUPTION_POLICY`) is for privileged apps only.
  - That page says nothing about vibrating from the background. AOSP's vibrator service drops vibrations from apps that aren't foreground unless the usage is alarm, ringtone, communication request, hardware feedback or physical emulation. A foreground service counts as foreground.
  - The pocket beat therefore uses a foreground service and `USAGE_ALARM`. Alarm vibration follows the alarm settings rather than the ringer, so silent mode doesn't mute it. Do Not Disturb lets alarms through by default, but a person can change that.
  - **On-device check needed:** pocket beat with the screen off, in silent mode, and in Do Not Disturb.
- **Step-counter batching.** TYPE_STEP_COUNTER is on-change and may be batched for up to the max report latency ([SensorManager.registerListener with maxReportLatencyUs](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,%20android.hardware.Sensor,%20int,%20int))).
  - Non-wake-up sensors don't wake the processor. Their events wait in the FIFO and can be lost when it fills ([Batching](https://source.android.com/docs/core/interaction/sensors/batching)).
  - So the app prefers wake-up variants and flushes on every query. The plan's "counted steps in the last minute" can only be as fresh as the push throttle and batching allow (choice 1).
- **The watch's 30 s before a press needs sensors that keep data while the watch sleeps.**
  - Wake-up accelerometer and gyroscope with batching wake the watch about every 10 s; that's the battery cost to measure.
  - If the watch has only non-wake-up variants, the FIFO size decides how much of the 30 s survives. The battery log header records which case applies and the FIFO size.
- **Plan B2 says "the watch plays to its connected earbuds".** That is built, but nothing is known yet about Galaxy Watch audio latency or whether its speaker takes over when the Buds drop. The player stops on headphone removal and the wrist takes over.

## Decisions this forced, for the owner

1. **Pilot files and account deletion.** Should "Delete my data" and sign-out also delete pilot files? Now they don't, so a sign-out can't destroy unexported pilot data. The pilot screen has its own delete button.
2. **The 10-minute cap for every cue in study mode**, or only for "I'm stuck" presses.
3. **Pocket reminder when the phone is unreachable** (Bluetooth off, phone far away): it reminds, at most hourly. The alternative is to stay quiet.
4. **Bluetooth audio delay** for H3: measure per earbud model and correct, or accept the bias.
5. **The 1 s phone timeout before the wrist takes over.** It costs up to 1 s on a press whose phone is slow. Shorter means more fallbacks to the wrist and fewer earbud and pocket presses.

## Blockers

- **No device or emulator was available.** `adb devices` lists none and there are no AVDs. Nothing below "Validation" has run on hardware.
- **The watch build needs `daedalus-watch/app/libs/samsung-health-sensor-api-1.4.1.aar`** (git-ignored) for the existing `VitalsTracker`. I copied it from the main checkout into this worktree to build. The pilot code doesn't use the Samsung SDK, but the app as a whole still does.
- **There's no `gradlew` script in either project** (only `gradle/wrapper/gradle-wrapper.properties`). I ran the cached Gradle 8.5 with JDK 17 directly.

## Validation actually run

All runs used JDK 17 and Gradle 8.5 from `~/.gradle/wrapper/dists/gradle-8.5-bin/…/bin/gradle`, offline.

- **Watch:** `cd daedalus-watch && gradle assembleDebug test --offline` exited 0.
  - 104 unit tests in each of the debug and release variants, 0 failures. 81 were there before.
  - New: `PilotGoldenTest` (10), `BeatRunnerTest` (8), `PressFileTest` (4), `StudyLimitTest` (1).
  - **Golden vectors:**
    - version, constants and `places_order` match;
    - all 6 splitmix64 rows and all 25 draws match;
    - **all 1008 `choose_place` rows match, 0 mismatches**;
    - every `should_remind_pocket` row and all 9 `should_ask_checkin` rows match.
  - **Plan B3:** over 3,000 presses, each available place gets its share within 3%, and an unavailable place is never drawn on any day from −3 to 25.
  - **Never silence:**
    - each non-wrist player failing goes to the wrist at once;
    - a silent phone goes to the wrist at exactly 1,000 ms and is told to stop, and a late "started" is ignored;
    - mid-beat loss goes to the wrist;
    - every availability × day × press × outcome (ok, fail, silent) combination ends with something playing and a recorded beat start.
  - **Check-in:** 6 asked, the 7th not, and the count resets the next day.
  - **Reminder rule:** covered by the golden rows.
  - **Click schedule:** under 1 frame of drift after 2 minutes at 60, 97, 100, 113 and 140 per minute, and the clicks land where scheduled across chunk borders.
  - **Press-file round trip:** a synthetic freeze goes through `MotionRing` and `PressRecord.toJson()` and is parsed back. The test then runs `python3 -c` with `pilot_analysis.load_press` + `press_timing` + `analyse(folder)` on the written file. Output: `stop_to_beat_s=3.52 press_to_steps_s=4.98`, against the expected 3.5 and 5.0. The test skips the Python step only if python3 or numpy is missing; here it ran.
- **Phone:** `cd daedalus-companion && gradle assembleDebug test --offline` exited 0.
  - 31 unit tests in each of the debug and release variants, 0 failures. New: `PilotPhoneTest` (4): step counter and detector, moved or not moved, and the window expiry.
- **Python:** `cd daedalus_watch_kit && python3 -m pytest -q` gave 246 passed, 5 warnings. No vectors were changed.
- **Existing flows touched.** Checked by unit tests and by reading the study-off paths, not on a device.
  - `StandDetectorTest`, `GuardSessionTest`, `GuardEngineTest` and the golden vectors still pass.
  - With study mode off:
    - `HapticService` takes the same branches (`pilotPress` false, the hour limit, `startHapticThread`);
    - `StandWatchService` registers only the barometer and accelerometer at 10 Hz;
    - it starts and stops on the stand-up setting alone and shows the same prompt.
  - Medication reminders weren't touched; the pilot has its own boot receiver.

## Written, not run (manual device checks)

| What | Check on real devices |
|---|---|
| Wrist press | Study on, day 21+ (or no earbuds or phone): press "I'm stuck". Pulses start at once, the screen says "On your wrist", Stop shows the check-in, and the file reaches the phone (Profile → Home pilot shows 1 press) about 60 s after the press. |
| Earbuds on the watch (B2) | Pair Galaxy Buds to the watch, day 0, press index 1. Clicks in the Buds at the pace. Take a Bud case out mid-beat: the wrist takes over, and the file has `fallback_reason: headphones_removed`. |
| Earbuds on the phone (B2) | Buds on the phone only. Clicks start within 1 s of the press, music ducks, Stop on the watch stops them. Phone out of range: the wrist starts within 1 s. |
| Pocket beat (B9) | Phone locked, screen off, in a front trouser pocket, also in silent mode and Do Not Disturb. The pulses are felt through fabric at the pace and stop with the watch. |
| Phone carried | Walk 20 s with the phone, stand still, press within 30 s: pocket is available. Phone on a table for 2 minutes: pocket is never drawn (`phone_carried: false`). |
| Pocket reminder (B10) | Sit 1 min with the phone on a table, stand: tap plus "Phone in your front pocket?". Repeat within the hour: nothing. Repeat with the phone in the pocket: nothing. Repeat with the stand-up prompt on: the line appears on "Pause before walking". |
| Check-in | Answer one: the file has it. Leave one: it closes after 30 s, `checkin: null`. Seventh press of the day: no question. Let a beat run 10 min with the screen off: the check-in opens over the watch face. |
| 10-minute cap (B6) | Study on: a press stops by itself at 10:00. Study off: the walking cue still runs to 60 min. |
| Motion buffer (B4) | In the file, `t_ms` covers about 30 s before to 60 s after the press, at about 50 Hz with no gaps longer than 100 ms, after the watch has been asleep for 10+ min. |
| Battery (14 h) | Study on all day: read `battery_<code>_<day>.csv` from the export. The percent drop per hour, and `ring_samples_total` growing by about 45,000 every 15 min. The header line says whether wake-up sensors were used. Compare one day with study off. |
| Settings sync | Change the pocket reminder on the phone with the watch connected: "Sent to the watch". Watch disconnected: the message says so, and reopening the app with the watch back resends the settings. |
| Export | "Export pilot files" opens the share sheet with one zip. Unzip it and run `pilot_analysis.analyse(folder)`. |
| Phone permission refused | Refuse "physical activity": the pilot screen says why, the phone service doesn't start, and earbud-on-phone and pocket presses fall back to the wrist. |

## Round 001 fixes

Branch brought up to date with `feature/home-pilot` at c361c93 (fast-forward). No Python was touched. The fixes are in commit 06c5a65. Tests live in `daedalus-watch/.../pilot/RoundOneFixesTest.kt`, one or more per number.

How "fails before" holds:
- **Fail as behaviour on the old code:** #6, #7 (watch-audio wait), #9, #12 and #13. For #12 the old ring held a fixed 6,000 samples, so 150 s at 100 Hz came back short. For #7 the old runner fell back to the wrist at 1 s.
- **Fail to compile on the old code** (the API is new): #5, #7 (play-call start, Bluetooth flag), #8, #11, #14 and #24.

| # | What changed | Test |
|---|---|---|
| 4 | `PilotPresses.begin` holds a partial wake lock (`Daedalus:PilotCapture`, 2 min timeout) from the press until the +60 s capture is read, so the Handler isn't paused by deep sleep. Chosen over an exact alarm: one lock, no new permission. | Not unit-testable (wake lock). Device check: press, screen off, file has motion to press +60 s. |
| 12 | `MotionRing` keeps samples by age (150 s) and grows its arrays, instead of a fixed count sized for exactly 50 Hz. | `n12_ringKeeps150sEvenWhenTheSensorDeliversTwiceAsFast` |
| 5 | A `pending_<press>.txt` (`PendingPress`) is saved at the press, when the player confirms and at the beat stop. It's deleted once the press file is written. All pilot file writes go through one serial executor, so a late pending save can't outlive the final write. At the next start (app screen, motion-ring start or boot), orphans become ordinary press files: whatever motion the ring still holds (usually none), `checkin: null` and `recovered: true`. | `n5_pendingPressRoundTripsAndBecomesARecoveredPressFile` |
| 6 | The watch check-in has "I'm stuck" first, above the four answers (the screen scrolls on small watches). A new press closes the open check-in, unanswered. | `n6_checkinScreenHasImStuckFirst` (layout) |
| 7 | `beat_start_ms` is null when the only source is a play() call (`audio_play_call`, on the watch or the phone); the press still counts as played there. `ClickBeatPlayer` adds `_bt` to the source when routed to Bluetooth, and each press file has `bt_latency_unknown` (true for Bluetooth audio). Watch audio gets 2.5 s to confirm and the phone keeps 1 s; both still fall back to the wrist. | `n7_aPlayCallTimeIsNotABeatStart`, `n7_bluetoothAudioIsFlaggedInThePressFile`, `n7_watchAudioGets2_5sToConfirmAndStillFallsBack` |
| 8 | "Earbuds" means LE audio headsets, wired and USB headsets, or an A2DP device whose Bluetooth class is headphones or wearable headset. Speakers, car audio, hands-free kits, hi-fi, TVs and uncategorised sinks are excluded. Reading the class needs BLUETOOTH_CONNECT (Android 12+): the watch asks for it in study mode, the phone when study mode is turned on. Without it, a Bluetooth sink doesn't count, so the press gets the wrist. | `n8_onlyHeadphoneBluetoothClassesCountAsEarbuds` |
| 9 | Watch manifest: `dataExtractionRules` and `fullBackupContent` exclude `files/pilot/` and `pilot.xml` (cloud backup and device transfer). The watch's other data keeps its backup. | `n9_watchPilotFilesAndSettingsAreExcludedFromBackup` |
| 10 | `CloudBackup.deleteEverything` and `deleteAccount` call `PilotFiles.deleteAll`. Sign-out alone still keeps them. | No unit test: both need a signed-in Supabase session. Checked by reading; device check below. |
| 11 | A pilot press creates no ordinary session CSV, and no extra sensor recording or vitals tracker. The press file is its record. This was the smaller change: one branch in `HapticService`, against teaching `CloudBackup` about study mode. Cues outside study mode, and non-press cues in study mode, still write their CSV. | `n11_pilotPressWritesNoSessionCsv` |
| 13 | `WatchBootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED) calls `StandWatchService.refresh` and, in study mode, recovers pending presses. | `n13_watchRestartsAfterABoot` (manifest) |
| 14 | `PilotFileWriter`: write to `.part`, rename, copy if the rename fails, one retry, then log and return null. Everything runs in `runCatching`, so it never crashes. It never replaces an existing press file (a clash gets `-2`, `-3`…). A failed write leaves the pending file for the next start. | `n14_writerKeepsBothOnAClashAndFailsWithoutThrowing` |
| 24 | Pause and resume reach the beat's player: watch audio plays silence while keeping time, and the phone gets `pause` / `resume` and stops its pulses or silences its clicks. The Android walking screen has no pause button, so today this only takes effect when a pause is sent to the service. | `n24_pauseAndResumeReachThePlayer` |

`BeatRunnerTest.noDrawEverEndsInSilence` now advances 2.5 s (the longest confirm wait) instead of 1 s.

**Validation re-run:**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 116 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 31 tests per variant, 0 failures.
- The press-file round trip through Python `pilot_analysis` still passes (inside `PressFileTest`).

**New device checks:**
- Press with the screen off, then leave the watch alone for 2 minutes: the file has motion up to about press +60 s (#4).
- Kill the app mid-beat (`adb shell am force-stop`), then reopen it: a `recovered: true` press file appears (#5).
- Reboot the watch in study mode: the battery log shows the ring again (#13).
- A Bluetooth speaker connected to the watch: the earbuds place is never drawn (#8).
- "Delete everything" on the phone: the press count on the pilot screen goes to 0 (#10).

## Commit range

`0812fae..HEAD` on `android-home-pilot` (the commits after 0812fae: Wear OS, phone, then this handoff with the B6 test).
Round 001 fixes: `c361c93..HEAD` (06c5a65 the fixes, then the handoff update).
