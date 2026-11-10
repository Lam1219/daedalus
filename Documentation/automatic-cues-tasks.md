# Automatic cues: implementation tasks

Built from [automatic-cues-design.md](automatic-cues-design.md) (decisions of 2026-11-10). Each task names its files, what "done" means, and what it waits for. Milestones follow the design's phases 0 → 3.

## Done today (2026-11-10)

- [x] Research and design: `Documentation/automatic-cues-design.md`.
- [x] Decisions recorded:
  - FoG cues start automatically; tremor cues ask first with Fine motor.
  - PADS is dropped.
  - Guard length is set after the battery test.
  - The pocket phone is added as a second FoG detector, with a stand-up reminder.
- [x] Parkinson@Home converter: `python_scripts/parkinson_home_to_csv.py` and `requirements-dataset.txt`, with a venv in `python_scripts/.venv`. All 49 subjects are converted to `dataset/csv/` (50 Hz). Label alignment was checked on pd18 (AUC 0.90 at zero shift).
- [x] `.gitignore`: `dataset/` (covered by the DUA) and `python_scripts/.venv/`.
- [x] Draft of the Apple entitlement request: `Documentation/apple-movement-disorder-entitlement-request.md`.

## Actions outside the code (you)

| # | Action | Unblocks |
|---|---|---|
| A1 | Submit the Movement Disorder entitlement form (Account Holder or Admin of team SFQ29D84T9) | M6 tremor minutes on iPhone |
| A2 | Ask Radboud for (a) the FoG video annotations and (b) the trouser-pocket phone recordings of the same visits | M1 FoG evaluation; M5 pocket evaluation |
| A3 | Record healthy-volunteer negatives on both watches: typing, brushing teeth, car ride, shaking a hand on purpose, cane or walker, plus instructed tremor-like motion. Use the existing session files, about 5 min per activity; walking with the phone in a pocket from now on | M1 false-alarm testing, M5 |
| A4 | Clinical adviser review of the watch wording ("Shaking noticed · Start fine motor cue?", "Walking cue on", pocket reminder) | M4 release |

## M0 — Housekeeping

- **T0. Commit today's work** on a new branch `feature/automatic-cues`, cut from `feature/session-timeout` because the tremor check it builds on hasn't been merged to `main` yet.
  - Commit the design docs, task list, converter, requirements and `.gitignore`.
  - The untracked PDFs in the repo root are not part of this.

## M1 — Offline reference (Python, `daedalus_watch_kit`) · phase 0

The Python version is the reference: Kotlin and Swift must match it on shared test vectors.

- **T1. Streaming features.** `daedalus_watch_kit/stream_features.py`.
  - **What it computes:** 2 s windows with a 1 s hop at 50 Hz: `tremorDps`, `peakHz`, `prominence`, `slowDps`, gravity and its change, `cadence` / `stepRegularity` (4 s), `armSwingDps`, `freezeIndex`. Plus the context state (`STILL`, `REST_TREMOR_POSSIBLE`, `ACTIVE_HAND`, `WALKING`, `TURNING`).
  - **Reuse:** the DFT is the one in `tremor.py` / tremor-1.0, so no new maths.
  - **Done when:** `tests/test_stream_features.py` passes on synthetic signals (pure 5 Hz rotation, walking at 100 steps/min, still).
- **T2. Tremor detector** `tremor_stream.py` (`tremor-stream-1.0`).
  - **Logic:** per-window positive rule, episode start (3 of 4) and end (≤ 1 of 6), personal threshold *T* from calibration, feedback steps (+15 % / −10 %).
  - **Done when:** tests pass for episode boundaries and threshold updates.
  - **Waits for:** T1.
- **T3. FoG detector** `fog.py` (`fog-1.0`).
  - **Logic:** walking state, freeze candidate (cadence lost and (freeze index high, or arm swing collapsed)), no posture change, stillness-cancel at 3 s, the turning rule.
  - **Done when:** tests pass on synthetic walk → tremble-in-place → walk, and on walk → voluntary stop.
  - **Waits for:** T1. Can run in parallel with T2.
- **T4. Evaluation on Parkinson@Home.** `python_scripts/evaluate_tremor_parkinson_home.py`.
  - **Labels per second:**
    - Tremor codes 1–3 count as positive.
    - Code 0 counts as negative.
    - Codes 96–98 count as "active hand"; they must not prompt.
    - Code 99 is excluded.
  - **Wrists:** the most affected wrist is the positive side; the other wrist and the healthy controls give negatives.
  - **Time at rest:** free-living sitting time from `labels_mobility.csv` gives the false events per hour at rest.
  - **Threshold tuning:** leave one subject out.
  - **Output:** F1, false events per hour, and onset delay, split by OFF / ON, written to a report in `dataset/reports/` (git-ignored).
  - **Waits for:** T2.
- **T5. FoG evaluation.**
  - **Data:** the same harness on the A2 FoG labels if Radboud provides them; until then, on the A3 recordings (false events per hour of walking only).
  - **Waits for:** T3, and A2 or A3.
- **T6. Golden test vectors.** `daedalus_watch_kit/golden/*.json`.
  - **What:** about 10 short IMU snippets (synthetic, plus A3 recordings, never Parkinson@Home data, because of the DUA) with the expected features, window decisions and episodes.
  - **Done when:** a script regenerates them and the Python tests check them.
  - **Waits for:** T2, T3.

**Gate M1:** tremor F1 > 0.80 and < 2 false events per hour at rest on Parkinson@Home. FoG numbers are reported; their targets are set from them.

### Status 2026-11-10

T0–T4 are done: `stream_features.py`, `tremor_stream.py` and `fog.py` with tests, 172 kit tests passing. NumPy 2 compatibility was fixed in `gait.py`, `hrv.py` and `tremor.py`.

**T4 result (leave one subject out, population threshold, no personal calibration):**

| Measure | Result | Gate |
|---|---|---|
| Event F1, tremor subjects (8, most affected wrist) | **0.77**: precision 0.89, recall 0.67 | > 0.80, not met |
| Event F1, all 49 subjects | 0.68: false episodes in controls lower precision | |
| False episodes per hour at rest | **0.54** over 102 h of sitting or lying (controls 0.33, PD without tremor 0.44) | < 2, met |
| Onset delay | median 4.5 s | |

**What limits recall:** slight tremor. pd07's labelled tremor is about 1.7 °/s, far below the population threshold of 6 °/s, and gets 0 of 12 bouts. pd02 and pd22 are similar. This is what the personal threshold (calibration plus feedback) is for, but Parkinson@Home has no calibration rest to test it on.

**Fixed along the way:** slight tremor was classed as "still" and excluded. The tremor rule now accepts still windows.

**Calibration test (`python_scripts/evaluate_tremor_calibration.py`):**
- **No calibrated threshold beat the population value.** Calibration from 20 s of rest scored 0.73 against 0.77 for the population threshold, and the bounded and found-tremor variants 0.75.
- **Capped "Not shaking" feedback is kept.** It cut false episodes at rest in PD without tremor from 0.45 to 0.24 per hour, for 0.02 of F1.
- **Code updated:** the v1 threshold is the population value, and feedback is capped at 0.5–1.5 × the starting threshold (design 2.4).

**4 s tremor window (`--tremor-window 4`):** not better.

| Window | Event F1, tremor subjects | False/h at rest, controls | Onset delay |
|---|---|---|---|
| 2 s | 0.77 | 0.33 | 4.5 s |
| 4 s | 0.75 | 0.18 | 5.5 s |

The 2 s window stays the default; 4 s is kept as an option (`tremor_window_s`).
- **Weak tremor is limited by the threshold, not the window.** pd07 (about 1.7 °/s) still gets 0 bouts: no threshold that keeps false alarms under 2 per hour reaches it.

**Label check (`python_scripts/inspect_false_tremor_episodes.py`; T 6 °/s, prominence 16, scored in-sample):**

*Tremor subjects (affected wrist).* 34 false episodes out of about 312; event F1 0.79 in-sample (0.77 leave-one-out).

| Likely cause | Count | What it looks like |
|---|---|---|
| Boundary | 13 | Starts within 30 s of a labelled bout (median 19 s): tremor before or after the marked edges |
| Same rhythm, label "none" | 11 | At the person's own tremor frequency (±0.75 Hz) with a clear peak: possibly a tremor not marked |
| Arm activity (96/98) | 4 | Strong, about 38 °/s |
| Other | 6 | |

*PD without tremor labels.* 43 false episodes, 28 of them in three people.
- **pd17 and pd08 had a rest tremor in the OFF clinical exam.** pd17 scored 1–2 on the arms, pd08 scored 3 on the left arm. Their episodes are mostly on the left wrist, which matches.
- **Their videos weren't annotated for tremor** because the 15 min screening saw none.
- **pd24 had a right-leg rest tremor**, but its episodes are on the left wrist, so it stays unexplained.

*Healthy controls.* 35 false episodes. These are strong rhythmic movements (median 21 °/s, 4–5 Hz), and only 37 % happen while sitting. Voluntary rhythmic movements pass the rest gate, which a stricter rest gate could address.

*If the likely-mislabelled episodes were real:*

| Change | Result |
|---|---|
| The 24 boundary and same-rhythm episodes counted as real (tremor subjects) | precision 0.89 → 0.97, F1 0.79 → 0.82 |
| pd17 and pd08 left out (PD without tremor labels) | false episodes at rest 0.44 → 0.32 per hour |

**Conclusion:**
- **Label gaps hide part of the precision, but recall is the real limit.** Even with perfect precision, F1 would be 0.83 at the current recall of 0.71.
- **Reporting:** report the gate on the labels as they are (0.77), with the corrected figures as a sensitivity analysis. Don't replace the score.

**Rest gate** (`--rest-gate`, leave one subject out). Slow movement ≤ 0.5× the tremor band, and 4 s slow rotation ≤ 8 °/s. Now the default.

| | Without gate | With gate |
|---|---|---|
| False episodes/h at rest, healthy controls | 0.33 (35 episodes) | **0.08** (13 episodes) |
| False episodes/h at rest, all | 0.54 | 0.40 |
| Event F1, tremor subjects (leave one out) | 0.77 | 0.74–0.77, depending on the run |
| Event F1, best setting in-sample | 0.71 | 0.72 |

**Personal threshold learned from history** (`tremor_learning.py`, `evaluate_tremor_learning.py`). Learned without labels from one part of the visit, scored on the other.
- **It finds the right people.**
  - No healthy control got a personal threshold (0 of 80 wrist-parts).
  - All 8 tremor subjects did, as did pd08, pd17 and pd24, whose clinical exams showed rest tremor.
- **F1 doesn't move (0.77 against 0.77).** About 2 h of one medication state is too short a history. pd07's tremor is almost all OFF (475 s against 104 s ON), so its threshold learned while OFF is scored on the ON part, and the other way round.
- **The real test is shadow mode**, with weeks of history across both states. It's part of the phase 1 gate.

**Trained model vs rules** (`python_scripts/evaluate_tremor_model.py`): gradient-boosted trees (200 trees, depth 4), one per held-out subject, with the decision cut-off chosen on the other subjects. Scored with the same episode logic.

| Model | Event F1, tremor subjects | Precision | Recall | False/h at rest, PD without tremor labels | False/h at rest, controls |
|---|---|---|---|---|---|
| Rules, v1 defaults* | **0.77** | 0.91 | **0.67** | 0.39 | **0.05** |
| Trees on the rule's features | 0.71 | 0.90 | 0.59 | 0.48 | 0.26 |
| Trees + engineered features (ratios, 4 s history) | 0.73 | 0.92 | 0.61 | **0.17** | 0.13 |

\*The rule defaults were tuned on all subjects, so they're slightly optimistic. The leave-one-out tuned rules scored 0.74–0.77, which is still at least as good as the trees.

- **No model beats the rules.** With 8 tremor subjects, the trees learn less than the hand-set rules encode.
- **The engineered model does reduce false alarms** in PD without tremor labels, but at the cost of recall.
- **It relies most on short history:** mean prominence and median strength over the last 4 windows. The episode logic already uses that history.
- **Decision:** keep the rules for v1. Retry a model (T28) once shadow mode and our own studies give many more labelled people.

**T5, FoG without freeze labels** (`python_scripts/evaluate_fog_parkinson_home.py`). Parkinson@Home has video-labelled walking, turning, standing and sitting for every subject, both wrists.

*Walking detection from the wrist (per second):*

| Group | Precision | Recall | Hours of walking |
|---|---|---|---|
| PD | 0.96 | 0.56 | 22 |
| Controls | 0.95 | 0.62 | 13 |

Cadence is about 107–109 steps/min.
- **What this means:** walking that is called walking is right, but about 40 % of walking seconds are missed (short bouts, the 4 s gait window, hands busy).
- **Effect on freeze alarms:** these drop-outs are the main source of false candidates, since half the candidates come while the video still shows walking.

*This cohort barely freezes.* Only pd01 scored freezing (MDS-UPDRS 3.11) in the OFF exam, and candidate rates don't follow that score (Spearman −0.31). So PD candidates here are almost all false alarms, and parkinsonian gait triggers more of them than healthy gait.

| Freeze index, arm-swing share | Candidates per hour of walking, PD | Controls |
|---|---|---|
| 2, 0.3 (first defaults) | 6.3 | 2.5 |
| **4, 0.2 (new defaults)** | **1.7** (fog-1.1: 2.0) | **0.6** |
| 8, 0 (shuffling route off) | 0.6 | 0 |

Sensitivity can't be measured until labelled freezes exist:
- Radboud's FoG annotations (A2);
- our own walking protocol with video;
- a dataset of freezers.

Next for FoG:
- Reduce candidates caused by walking-detection drop-outs, for example by requiring the stop to last 2 windows in all cases.
- Then retune the thresholds on labelled freezes.

**T6, golden test vectors** (`daedalus_watch_kit/golden/`):
- **The 10 synthetic cases:** still, a rest tremor episode, slight tremor with a personal threshold, voluntary rhythm rejected by the rest gate, walking, walking while turning, a walk ending in trembling (freeze candidate), a walk ending in a voluntary stop, a posture change, and uneven timestamps.
- **What each file holds:** the input samples (rounded to 6 decimals), the settings, and the expected per-second features, contexts, tremor episodes and freeze candidates, with a 1e-6 tolerance.
- **Regenerating:** `make_golden.py` regenerates the files, and `tests/test_golden.py` fails when they drift from the code.
- **Next:** the Kotlin (T8) and Swift (T9) ports read the same files.

**T7–T10, on-device ports (done 2026-11-10):**
- **T7:** `Spectrum` (Kotlin in both Android apps, Swift in `Shared/`) is now the one Hann window and PSD used by the 20 s check and the streaming features. tremor-1.0 results are unchanged.
- **T8, Galaxy Watch:** `StreamFeatures.kt`, `TremorStream.kt`, `FogDetector.kt`. `GoldenVectorsTest` covers all 10 cases plus streaming against batch, and `DetectorRulesTest` covers the fog-1.1 rules.
- **T9, Apple Watch:** `StreamFeatures.swift`, `TremorStream.swift`, `FogDetector.swift`, and a `SharedTests` macOS test target. Run it with `xcodebuild test -project Daedalus.xcodeproj -scheme SharedTests -destination 'platform=macOS'`.
- **Match with Python:** both ports agree within 4e-9.
- **T10:** the automatic-cue event labels are in every copy, and their `notes` format is documented with the labels. `test_auto_cue_labels.py` checks all copies.
- **fog-1.1**, from the port reviews:
  - a still second resets a run of freeze seconds;
  - walking that resumes within the candidate window continues the same walk.

  With these, false alarms at the defaults are 2.0 per hour of walking in PD (was 1.7) and 0.6 in controls (unchanged).
- **Feedback bounds:** they can no longer invert for an extreme starting threshold.
- **Noted, not changed:**
  - with the optional 4 s tremor window, episode starts are reported 2 s late;
  - `FogParams.cadence_spm` is unused (kept for the person's walking-cue rate);
  - the walk's window list isn't capped (at most 3,600 per hour, fine on the watch).
- **Next:** M3, guard sessions in shadow mode (T11–T17).

**Where the tremor gate stands:** F1 0.77 leave-one-out on the labels as they are, 0.82 if the likely label gaps are counted.
- **Ship:** the v1 rule as it stands (population threshold, rest gate, capped feedback).
- **Shadow mode:** measure the learned threshold there.
- **Radboud:** ask whether pd17, pd08 and pd24 can be annotated for tremor.
- **Then T5–T6.**

## M2 — On-device core · Kotlin and Swift

- **T7. Shared spectral function.** Split `TremorAnalyzer` (3 copies: Galaxy Watch, Android phone, iOS `Shared/`) so the 20 s check and the stream use one function.
  - **Done when:** the existing tremor tests still pass and the outputs are unchanged, so it stays `tremor-1.0`.
- **T8. Kotlin streaming core.** In the watch app `sensor/`: `StreamFeatures.kt`, `TremorStream.kt`, `FogDetector.kt`.
  - **Done when:** unit tests read the T6 golden vectors and match within tolerance.
  - **Waits for:** T6, T7.
- **T9. Swift streaming core.** The same in `daedalus-companion-ios/Shared/`.
  - There's no iOS test target yet: add a `SharedTests` XCTest target in `project.yml` that reads the same golden JSON.
  - **Waits for:** T6, T7. Can run in parallel with T8.
- **T10. New event labels in every copy:**
  - **Labels:** `AUTO_TREMOR_*`, `AUTO_FOG_CANDIDATE`, `AUTO_CUE_*`, `GUARD_*`, `POCKET_*`.
  - **Copies:** `daedalus-shared/.../EventLabels.kt`, the phone's `model/EventLabels.kt`, the watch's `model/DataModels.kt`, `daedalus_watch_kit/event_labels.py`, and the Swift side.
  - **Event fields:** type, confidence, start, end, action, model version, threshold.
  - **Can start now.**

## M3 — Guard sessions in shadow mode · phase 1

- **T11. Galaxy Watch guard.**
  - **Modes:** `walk_guard` and `rest_guard` in `HapticService` (foreground service, 50 Hz acc and gyro).
  - **Behaviour:** runs T8 every second and writes events to the session file. No cue and no prompt.
  - **Ring buffer:** keeps the 10 s before "I'm stuck".
  - **Battery:** logs the battery level every 5 min.
  - **Ends:** after 1 h, for now.
- **T12. Apple Watch guard.** The same in `CueController`, under `HKWorkoutSession`: walking for the walk guard, mind-and-body for the rest guard.
- **T13. Watch screens.** "Walk with guard" and "Rest guard" on both watch homes, with a guard-running state and "I'm stuck" still on top.
  - **Strings:** EN/FR in the Android files, then regenerate the iOS catalogs.
- **T14. Phone read-back.**
  - **Reading:** both phone apps read the auto events from session files, as they do for the tremor check.
  - **Cues screen:** "Coming soon" becomes the switches "Walking guard" and "Shaking prompts" (shadow), plus quiet hours.
  - **Journal:** shows the episodes with "Was this right?".
- **T15. Calibration.** Extend the tremor check to: rest 20 s → arms extended 10 s → walk 30 s.
  - **Stores:** the person's tremor Hz, cadence and arm-swing baseline, on the phone. It doesn't store the tremor threshold (see the calibration test).
  - **Sends:** them to the watch with the cue rates.
- **T16. Learning from feedback.** Feedback from the watch and the Journal adjusts *T* (+15 % / −10 %, kept within 0.5–1.5 × the starting value). Each change is logged.
- **T17. Battery test.** A 1 h walk guard and a 1 h rest guard on each watch, three times each.
  - **Output:** a short report that decides the guard length (decision 5).

**Gate M3:** ≥ 2 weeks of shadow use in the pilot, with false events within budget.

## M4 — Cueing v1 · phase 2

- **T18. Tremor prompt.** Short double tap and "Shaking noticed · Start fine motor cue?" with Start, Not now and Not shaking.
  - **Timing:** times out after 10 s. Fine motor runs for 2 min, or until the episode ends + 30 s.
  - **Cooldowns:** 10 min; 30 min after two "Not now" in a row.
  - **Never:** during quiet hours, while charging, or during another cue.
- **T19. Automatic FoG cue.** The walking cue at the person's rate, as "I'm stuck" does.
  - **Stops:** after 10 s of walking, after 60 s, or on Stop.
  - **Repeats:** 20 s cooldown. Two quick stops silence it for 10 min.
- **T20. "Did this help?"** After automatic cues; the answers feed T16.

## M5 — Pocket phone · phase 2b

- **T21. Android phone pocket detector.**
  - **Detection:** a foreground service runs `fog-pocket-1.0` with orientation-free signals and thigh pitch.
  - **Pocket check:** every 10 s, from step-rhythm agreement with the watch, a steady thigh angle, and proximity and light.
  - **Shared logic:** the Python reference goes in `daedalus_watch_kit/fog_pocket.py` first.
- **T22. Phone → watch score.**
  - **Transport:** Data Layer `MessageClient` messages, plus the fusion rules on the watch (OR and AND-lite, logged side by side in shadow).
  - **Sitting:** a "sitting" state from the pocket cancels candidates.
  - **Fallback:** the phone vibrates the rhythm in the pocket if the watch is out of reach.
- **T23. Pocket reminder** (Galaxy Watch first).
  - **Trigger:** on stand-up (`StandDetector`), check the pocket state, then check again after 5–10 steps.
  - **Message:** "Phone in your pocket? It helps your walking cue", sharing a buzz with "Pause before walking".
  - **Limits:** 30 min, 3 per day, "Not today", and a "no pocket" setting.
  - **Unreachable phone:** "Your phone isn't with you".
- **T24. iPhone spike.** Does a walking workout mirrored from the watch (`startMirroringToCompanionDevice`) keep `CMMotionManager` at 50 Hz in the background, screen locked, for 1 h?
  - **Output:** a one-page result. Then T25, or the iPhone stays watch-only.
  - **Independent:** can run any time.
- **T25. iPhone pocket detector and reminder** on the Apple Watch, if T24 passes.

## M6 — Later

- **T26. Tremor minutes on iPhone** from `CMMovementDisorderManager`, off by default and only after a diagnosis is confirmed. Needs A1.
- **T27. Tremor minutes on Galaxy Watch** from a batched 50 Hz background detector.
- **T28. Model v2.** A small classifier on the consented labels, run with LiteRT and Core ML. It ships only if it beats v1 on the full evaluation set.

## Order and parallel work

```
T0 ─ T1 ─┬─ T2 ─ T4 ──────────┐
         └─ T3 ─ T5           ├─ T6 ─┬─ T8 (Kotlin) ─┬─ T11 ─┐
T10 (any time)                │      └─ T9 (Swift) ──┴─ T12 ─┼─ T13 ─ T14 ─ T15 ─ T16 ─ T17 ─ M4 ─ M5
T7 (any time, before T8/T9) ──┘                                │
T24 iPhone spike (any time) ───────────────────────────────────┘
```

Suggested first slice: **T0 → T1 → T2 → T4.** This tells us within days whether the tremor rule reaches the M1 gate on real patient data, before any app code changes.
