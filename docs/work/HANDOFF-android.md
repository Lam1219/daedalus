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
3. **Audio beat start** comes from `AudioTrack.getTimestamp` (when the first frame was presented), recorded even if it arrives after the player confirmed. If none arrives within 2 s, `beat_start_ms` is null with source `audio_no_timestamp`. The `play()` call time is never used (round 002, #8). Bluetooth latency after the output stage (typically 100–250 ms) may not be included; `bt_latency_unknown` flags it. See "Where the spec needs correcting".
4. **`beat_stop_ms`** is the watch's time of the stop. For phone beats, the phone stops when the message arrives, typically a few hundred ms later.
5. **Mid-beat loss** switches to the wrist but keeps `place` as where the beat started (analysis keys on the start), and records `fallback_ms`.
6. **Pocket reminder as a line on the existing prompt** when the stand-up prompt is on (one screen per stand-up rather than two interruptions in a row), and as a screen of its own when it's off. The reminder setting is the "off with one setting" switch, on the phone.
7. **The phone's "moved in the last minute"** is any accelerometer axis changing by more than 1.5 m/s² within 60 s, at 10 Hz batched. A pocketed phone tilts when its owner stands; one on a table doesn't. The threshold is a first guess.
8. **"Phone carried"** uses TYPE_STEP_COUNTER (wake-up variant when the phone has one), falling back to TYPE_STEP_DETECTOR, with a 10 s max report latency. The first counter event after registering is treated as a running total, not a step. Before the service sees its first step, the phone counts as not carried.
9. **The check-in closes after 30 s.** It counts toward the daily 6 when shown, even if unanswered.
10. **The study cap applies to every cue mode in study mode**, not only presses ("study mode caps a cue at 10 minutes"). Guards keep the hour.
11. **A press inside a running guard session** keeps the existing guard behaviour (wrist, no press file). Guards are a lab tool, not part of the pilot.
12. **The press counter** lives on the watch and resets when the code or seed changes. A watch reinstall resets it too, so the schedule repeats from press 0. That's acceptable because the schedule is stateless, but the owner may want the phone to keep a copy.
13. **A press with little or no motion** (the ring wasn't running) is written as an ordinary `pilot_….json` press file with empty or short samples. Since ca72dd9 no code writes `.json.nomotion`; `pilot_analysis` keeps such a press's check-in and leaves its timings empty. (Corrected in round 002.)
14. ~~Pilot files are not removed by sign-out or "Delete my data".~~ Reversed in round 001 (#10): "Delete everything" and "Delete account" now delete pilot files too. Plain sign-out still leaves them, so a sign-out can't destroy unexported data.

## Where the spec needs correcting (with platform docs)

- ~~`pilot_analysis.press_timing` raises on a press with no samples.~~ Fixed in Python (ca72dd9): such presses keep their check-in and get no timings.
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
| Install (update both apps together) | Install the phone and watch builds from the same commit, at the same visit; **both must be `0.1.0-pilot7` or later**. To read the version: on the phone, Settings → Apps → Daedalus → App info, version at the bottom; on the Galaxy Watch, Settings → Apps → Daedalus (or the Galaxy Wearable app → Watch settings → Apps → Daedalus). Both must show the same `pilotN`. **Then the off/on test** (round 007, #1): turn study mode off on the phone and check that "Home pilot on" disappears from the watch's ongoing notification; turn it on and check that it comes back. If it doesn't come back, the watch app is too old: older watch builds are turned off by the phone and reject its "on". Update the watch app and repeat. **Participants checked on pilot5 or earlier must be checked again** after the update (round 007, #6): the earbuds check is now stored for a code and seed, and an old "checked" value counts as not checked. |
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

## Round 002 fixes

`feature/home-pilot` (ca786c1) merged first (fast-forward). No Python touched. Tests are in `daedalus-watch/.../pilot/RoundTwoFixesTest.kt`.

| # | What changed | Test |
|---|---|---|
| 8 | Back to **1 s** before the wrist takes over, for every player, watch audio included (the 2.5 s from round 001 is gone). `ClickBeatPlayer` confirms as soon as its first frames are written and the track is playing (`audio_written`), so the safety net never waits for a timestamp. It then reports the start of the first click from `AudioTrack.getTimestamp` when one arrives within 2 s (`audio_timestamp`), else null with `audio_no_timestamp`. `BeatRunner` records that measured start even when it comes after the confirmation; only the first measurement counts. The phone does the same: a first `started` with start −1 (playing), then a second with the measured start, converted with the same round-trip rule. The `play()` call time is never used. `_bt` and `bt_latency_unknown` are unchanged. | `n8_watchAudioThatNeverPlaysFallsBackAfterOneSecond`, `n8_confirmationWithoutATimeStopsTheSafetyNetAndTheMeasuredStartIsRecordedLater`, `n8_noTimestampMeansNullStartAndTheSourceSaysWhy` |
| 4 | `PilotFileWriter` no longer deletes the target before renaming. `File.renameTo` uses rename(2) on Android, which replaces the target atomically on the same file system, and `.part` files sit in the same folder as their target. If a rename is refused, the content is written over the target in place, so the old file is still there until the new content is. | `n4_replacingAPendingFileNeverLeavesItMissing`: a rename spy checks the old file is still present at the rename. Checked against the old code: with the delete put back, this test fails. |
| 6 | `WatchBootReceiver` does nothing unless the pilot study is active (`PilotStore.active`). With the study switch off, a boot behaves as before this branch: stand-up detection starts when the app opens, as it always did. | `n6_bootRestartsOnlyWhileThePilotStudyIsActive` |
| 7 | Accepted with no code change. A recovered press keeps `recovered: true`, and the Python analysis gives it no timings. | `n7_aRecoveredPressSaysSo` pins the field. |
| Doc | Choice 13 no longer describes `.json.nomotion`, which no code writes. Choice 3 and the matching "spec needs correcting" item now describe the round-002 audio timing. | — |

Two round-001 tests (`n7_aPlayCallTimeIsNotABeatStart` and the 2.5 s watch-audio test) were replaced by the round-002 tests above. `BeatRunnerTest.noDrawEverEndsInSilence` now advances 1 s.

**Validation:**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 120 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 31 tests per variant, 0 failures.

**Device check added:** Galaxy Buds on the watch. Within 1 s of the press the clicks play with no fallback, and the press file's `beat_start_source` is `audio_timestamp_bt` (or `audio_no_timestamp_bt` with a null start).

## Round 003 fixes

`feature/home-pilot` (fcbd67f) merged first (fast-forward). No Python touched. Tests are in `daedalus-watch/.../pilot/RoundThreeFixesTest.kt`.

| # | What changed | Test |
|---|---|---|
| 4 | If the output shows no first click as presented within 2 s, the click player reports a null start (`audio_no_timestamp`) and then treats the audio as lost. The wrist takes over and `fallback_ms` / `fallback_reason` are recorded. The decision is `FirstClick.decide`. On the phone, "lost" is sent as `failed` after the confirmation, which the watch treats as lost. No press can stay silent because audio was written but never presented. | `n4_noTimestampWithinTheWaitIsLost` (new decision), `n4_losingTheAudioAfterTheWaitPutsTheBeatOnTheWristAndRecordsWhen` |
| 5 | `BeatRunner` never records a start whose source contains `audio_play_call`, whichever app sent it. A later real timestamp still counts. "Update both apps together" is now in the device checks (install row). | `n5_aPlayCallSourceNeverGivesAStart`. Checked against the old code: with the guard removed, it fails. |
| 7 | `recoverOrphans` runs on app start and on boot whatever the study switch says. It only writes files and never starts the motion ring or a service. With the switch off, a boot does nothing else. | `n7_bootRecoversPressesWithTheStudyOff`; round 002's `n6_…` now expects "recover" (and no restart) with the study off. |
| 6 | Accepted, no change: a missing beat start is recorded as null, never guessed; a missing start isn't a wrong one. | — |
| 8 | Accepted, no change: `PilotFileWriter` keeps its copy-in-place path for a refused rename, but refusals aren't expected on internal storage. | — |

**Validation:**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 124 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 31 tests per variant, 0 failures.

## Round 004: earbuds paused

Owner decision (2026-11-13). Round 004 found new problems in the Android earbuds start and confirm logic for the fourth round running (#7–#9). The earbuds place on Android is therefore **paused** behind a per-participant setting rather than patched again. #7–#9 are not fixed; that code is paused. `feature/home-pilot` (06801fb) merged first. No Python or iOS touched.

**The setting.**
- Phone, Profile → Home pilot: **"Earbuds checked for this participant"**. Off by default.
- Like the code, seed and date, it can only change while study mode is off.
- It travels to the watch as a sixth settings field. An older phone's five-field message means "not checked".
- **Off:** "earbuds connected" is false for drawing, on the watch (`PressPlanner`) and in the phone's status. The earbuds place is never drawn, no Android audio player starts for a pilot press, and press files say `earbuds_connected: false`.
- **On:** draws and players are exactly as before.

**Earbuds device check** (rewritten in rounds 006 and 007). The physio runs it at the clinic visit before the participant's earbuds can be used.
- It runs under a **test code**, so the participant's own press numbers and data never contain check presses.
- The setting is tied to one saved code and seed. The screen always says which: "Earbuds checked for P05" or "Earbuds not checked".

1. On the pilot screen, with study mode off, type the test code **`CHECK`** and any seed (for example `1`), and tap **Save code and seed**. Set the enrolment date to today, so presses rotate through the places (learning week).
2. Turn **Earbuds checked** on. The line reads "Earbuds checked for CHECK". Turn study mode on.
3. **Pairing 1, earbuds paired to the watch** (phone's Bluetooth audio off). Make presses until 5 have been drawn to earbuds; the watch's walking screen says "In your earbuds", skip the others. Each of the 5 must:
   - be heard in the earbuds within 1 s of the press;
   - never be cut to the wrist while the clicks are audible;
   - have, in its press file (Export pilot files), a measured `beat_start_ms` with `beat_start_source` starting `audio_timestamp`, not null.
4. **Pairing 2, earbuds paired to the phone** (unpaired from the watch). Repeat step 3: 5 presses, same three checks, with sources starting `phone_audio_timestamp`.
5. **Pass = both pairings pass**: all 10 presses meet all three checks. One setting covers both, so if either fails the participant isn't checked.
6. Turn study mode off. Tap **Delete pilot files**. It deletes the check's files on the phone and, over the watch link, on the watch: press, pending and battery files. That message was added in round 007; before it, the watch couldn't delete its pilot files from the phone.
7. Type the **real** participant code and seed and tap **Save code and seed**. Saving a new code or seed starts the watch's press numbers at 0, and the check under `CHECK` doesn't count for the real code: the line reads "Earbuds not checked".
8. **Only on a pass:** turn **Earbuds checked** on. The line must read "Earbuds checked for <real code>". The switch is free whenever study mode is off and the shown code and seed are the saved ones, which is how a check run under `CHECK` is carried to the real participant. On a fail, leave it off.
9. Set the real enrolment date and turn study mode on.

**An interrupted check** (battery, a call, a failed pairing) leaves the real participant "not checked": nothing was ever recorded for the real code and seed. Start again at step 1, or skip the earbuds for this participant.

**Round 004 #3.** The comments in `ClickBeatPlayer.start` and `WatchBootReceiver` now say what the code does (the 2 s wait ends in a loss and the wrist takes over; what a boot does in each case). The unused `TIMESTAMP_WAIT_MS` is gone.

**Round 004 #4.**
- `MainActivity` and `WatchBootReceiver` check that `filesDir/pilot` exists (`pilotFolderExists`, kept outside `PilotPresses`) before touching `PilotPresses`.
- With no pilot folder and study mode off, app start and boot create and run nothing pilot-related.
- The watch screen only looks at `PilotPresses` when study mode is on or a pilot folder exists.

**Tests (`RoundFourEarbudsPausedTest`).**
- With the setting off, no draw over days −1 to 22 × 200 presses × every availability yields earbuds or an audio player, even with earbuds connected. I checked that this fails with the gate removed.
- With it on, every draw equals `Pilot.choosePlace` as before.
- The setting round-trips and an old five-field message decodes as off.
- With no pilot folder and the study off, boot touches nothing; with a folder, it only recovers.
- The folder check counts only a directory.
- Older tests were updated: the boot tests pass the folder flag, and `BeatRunnerTest`'s earbuds-player test turns the setting on.
- `MainActivity`'s folder check isn't reachable by a unit test (it's in `onCreate` and `onResume`).

**Validation.**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 129 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 31 tests per variant, 0 failures.

## Round 005 fixes

`feature/home-pilot` (1959773) merged first (fast-forward). No Python or iOS touched. Tests: `RoundFiveFixesTest` in both `daedalus-companion` and `daedalus-watch`.

| # | What changed | Test |
|---|---|---|
| 4 | `PilotSettings.withIdentity(code, seed)` turns "Earbuds checked" off whenever the code or seed differs; the same code and seed keep it. The pilot screen applies it on every edit of the code or seed field and again in `turnOn()` (both paths). The earbuds-check procedure's step 4 now says "turn the setting off", and step 5 orders the edits so the result is set after the real code and seed. | `n4_aNewCodeOrSeedTurnsEarbudsCheckedOff` (phone) |
| 5 | The phone refuses `PHONE_AUDIO` for a pilot press unless its own "Earbuds checked" is on (`PilotPhoneRules.mayPlay`). It answers `failed\|earbuds_not_checked` at once, so the watch's wrist takes over at once, well within the 1 s. Both apps' version names are now `0.1.0-pilot5`, and the install row of the device checks says how the physio reads the version on each device. | `n5_thePhoneRefusesEarbudAudioUntilChecked` (phone); `n5_phoneAndWatchShowTheSamePilotBuild` (watch: both build files show the same pilot build, 5 or later) |
| 6 | `PilotPresses.begin` sets `PilotFlags.pressStarted`, a volatile flag the watch screen reads every frame with its own `pilotOn`. A press that starts while the screen's flag is stale (study turned on with the app open) still shows its check-in. No preferences are read per frame. | `n6_aPressMakesTheScreenReadThePresses`, `n6_beginSetsTheFlag` |
| 7 | Added the requested tests. `onBoot(pilotActive = true, folderExists = false)` restarts without recovery. The phone's earbuds-status expression is now the pure `PilotPhoneRules.earbudsForStatus`, used by both the service and the listener. The refusal and the clearing are covered by #4 and #5. | `n7_bootWithTheStudyOnAndNoFolderRestartsWithoutRecovery` (watch), `n7_earbudsStatusNeedsTheCheckAndHeadphones` (phone) |

How "fails before" holds:
- **Fail as behaviour on the old code:** `n6_beginSetsTheFlag` and the pilot-build test.
- **Fail to compile** (the API is new): #4, #5's refusal, #6's flag and #7's status function.
- **Passes on the old code:** `onBoot(true, false)`. That behaviour already existed; it was asked for as a test, not a fix.

**Validation:**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 133 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 34 tests per variant, 0 failures.

## Round 006 fixes

`feature/home-pilot` (e0ec09b) merged first (fast-forward). No Python or iOS touched. Tests: `daedalus-companion/.../pilot/RoundSixFixesTest.kt`, plus updated round-004 and round-005 tests.

| # | What changed | Test |
|---|---|---|
| A | **Earbuds checked for one participant.** `PilotSettings.earbudsCheckedFor` holds `"<code>\|<seed>"`, and earbuds count only while it equals the current code and seed. This replaced the round-005 flag and its clear-on-edit logic, which is removed. The code and seed fields no longer save on each keystroke: only **Save code and seed** or turning study mode on saves them, and only if both are valid (`PilotPhoneRules.confirmIdentity`), so the watch never gets a half-typed code or a seed of −1. The pilot screen shows a plain line, "Earbuds checked for P05" or "Earbuds not checked". The switch works only while study mode is off and the shown code and seed are the saved ones. On the wire the check travels as `code:seed`; an older phone's `1` counts as not checked. The earbuds device check was rewritten to match (real code first, then the check; turn it off on a fail). | `a_earbudsCountOnlyForTheCodeAndSeedTheyWereCheckedFor`, `a_codeAndSeedSaveOnlyWhenValid`, `a_theStatusLineSaysWhoTheEarbudsWereCheckedFor`, `a_theCheckTravelsToTheWatchAndOldValuesDontCount` |
| 5 | Study on is sent as `2`. A watch build from before round 004 (and rounds 004–005) accepts only `0` and `1`, so it rejects the settings and never turns study mode on. New watches read `1` and `2` as on. The `PilotPhoneRules` comment now says what is and isn't guarded. The install row says: if the watch doesn't show "Home pilot on", update the watch app. Both apps' versions are now `0.1.0-pilot6`, and the install row asks for pilot6 or later. | `n5_studyOnIsSentAs2WhichOldWatchesReject` |
| 6 | `refusal(player, settings)` keeps `not_a_phone_player` for non-phone players and `earbuds_not_checked` for unchecked earbuds. `startBeat` stops the previous phone beat before refusing or starting. | `n6_refusalsKeepTheirReasons`, `n6_aRefusedStartStopsThePreviousBeatFirst` |
| 8 | The procedure says both pairings, watch and phone, must pass (10 presses) or the one setting stays off. | Handoff only. |

How "fails before" holds:
- **Fail as behaviour on the old code:** A's "another code or seed" and the old `1` value, #5's `2` on the wire, and #6's stop-before-refuse order.
- **Fail to compile** (the API is new): the rest of A and #6's refusal reasons.

**Validation:**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 133 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 40 tests per variant, 0 failures.

## Round 007 fixes

`feature/home-pilot` (cb05ba2) merged first (fast-forward). No Python or iOS touched. Tests: `RoundSevenFixesTest` in both `daedalus-companion` and `daedalus-watch`.

| # | What changed | Test |
|---|---|---|
| 1 (serious) | With study on, the phone sends two settings messages in order, each awaited: an "off" copy marked `precursor` in a seventh field, then `2\|…` (`PilotSettings.wireMessages`). A watch from before round 006 accepts the "off" (it reads five or six fields), turns study mode off and the motion ring with it, then rejects the "on". It can't stay on with its old earbuds behaviour. A current watch ignores the precursor and applies the "on", so its motion ring isn't wiped on every resend. A pilot6 watch applies both (off then on): correct, but it loses the ring once per resend. The `PilotPhoneRules` comment says exactly what is and isn't guarded. The install row now has the off/on test. Both apps now read `0.1.0-pilot7`. | `n1_anOlderWatchThatWasOnEndsUpOff` (simulates the old watch's decode), `n1_aCurrentWatchIgnoresThePrecursorAndEndsUpOn`, `n1_thePhoneSendsEveryMessageInOrder` (phone); `n1_theWatchIgnoresThePrecursorOff` (watch) |
| 4 | The earbuds device check runs under the test code `CHECK`. "Delete pilot files" on the phone now also sends `/pilot/delete`, and the watch deletes every file in its pilot folder (`deletePilotFiles`); before this, the watch had no way to do it. Saving the real code and seed restarts the press numbers. "Earbuds checked" is then turned on for the real code only on a pass. The switch is free while study is off and the saved code shows, which is how the check carries over. An interrupted check leaves the real participant not checked, because nothing was recorded for its code and seed. | `n4_deleteReachesTheWatch` (phone), `n4_theWatchDeletesItsPilotFilesOnRequest` (watch) |
| 5 | The pilot screen compares the typed seed as a number (`PilotPhoneRules.isUnsaved`), so "042" counts as the saved 42. | `n5_leadingZerosCountAsSaved` |
| 6 | The install row says participants checked on pilot5 or earlier must be checked again after the update. | Handoff only. |

How "fails before" holds:
- **Fail as behaviour on the old code:** #1's simulation (the old code sent only `2|…`, so the old watch stayed on), the delete-reaches-the-watch check and the precursor check on the watch.
- **Fail to compile** (the API is new): `isUnsaved` and `deletePilotFiles`.

**Validation:**
- Watch: `gradle assembleDebug test --offline` in `daedalus-watch` exited 0; 135 tests per variant, 0 failures.
- Phone: `gradle assembleDebug test --offline` in `daedalus-companion` exited 0; 45 tests per variant, 0 failures.

## Commit range

`0812fae..HEAD` on `android-home-pilot` (the commits after 0812fae: Wear OS, phone, then this handoff with the B6 test).
Round 001 fixes: `c361c93..HEAD` (06c5a65 the fixes, then the handoff update).
Round 002 fixes: `ca786c1..HEAD`.
Round 003 fixes: `fcbd67f..HEAD`.
Round 004 (earbuds paused): `06801fb..HEAD`.
Round 005 fixes: `1959773..HEAD`.
Round 006 fixes: `e0ec09b..HEAD`.
Round 007 fixes: `cb05ba2..HEAD`.
