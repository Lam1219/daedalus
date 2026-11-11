# Automatic cues: tremor and freezing of gait — research and design

Status: design for review, nothing built yet · 2026-11-10

This document completes the research started from the PADS-based draft and proposes how Daedalus will detect a tremor or a freeze on the watch and start a cue by itself. It follows the PRD ("Next: automatic cueing"): version 1 is signal processing with per-person settings, version 2 is a small on-device classifier trained on data we collect with consent.

---

## 1. What the research says

### 1.1 Review of the PADS-based draft

| Claim in the draft | What we found | Consequence |
|---|---|---|
| PADS: 2× Apple Watch Series 4, acc (g) + gyro (rad/s), 100 Hz, 11 tasks | **Confirmed.** 469 subjects (PD, differential diagnoses incl. essential tremor, healthy), 5,159 recordings, tasks of 10–20 s. | Good reference for what PD wrist motion looks like per task. |
| PADS can train a tremor detector | **No.** Labels are *one diagnosis per subject* (plus questionnaires). There are no per-window or per-episode tremor labels. | PADS trains "does this person have PD?", not "is a tremor happening now?". A PD-vs-healthy model would fire all day for every user. It cannot be the global baseline for cueing. |
| "SVM > 92 % F1", "LASSO as per Sun et al." | **Not found.** The published baseline (Varghese et al., *npj Parkinson's Disease* 2024) reports balanced accuracy 91 % PD vs healthy, 72 % PD vs differential diagnoses. No Sun et al. LASSO paper on PADS. | Don't cite these figures. |
| Licence | **CC BY-NC-SA 4.0 — non-commercial.** | Daedalus has a subscription business case. We can use PADS for research analysis, but should not ship thresholds or weights derived from it without legal advice or permission from the authors. |
| 100 Hz is "critical" | **Not needed.** Tremor sits at 3–8 Hz (PD rest tremor ≈ 4–6 Hz). 50 Hz gives a 25 Hz Nyquist limit. Apple's own tremor API uses 50 Hz accelerometer data. | Keep the 50 Hz both watches already record; downsample PADS to 50 Hz. |
| 2–3 min calibration with 4 tasks, LASSO per user, fine-tune a CNN head on the watch | **Too little data to train on.** 50 s of data gives ~50 windows, mostly without tremor: PD tremor comes and goes, and is suppressed by movement. Kinetic tasks (drinking, finger-to-nose) are the moments we must *not* cue. | Calibrate *thresholds*, not model weights. Keep learning from feedback over days. |
| Neural Engine / NNAPI | NNAPI is deprecated on Android 15; Core ML on the watch mostly runs on the CPU. | Irrelevant to v1: a spectral detector costs well under 1 ms per window. v2 uses LiteRT and Core ML on the CPU. |
| Context label (rest / postural / kinetic) | **Useful.** PADS tasks are time-stamped, so a rest/postural/kinetic context model *can* be trained on PADS (research use). | Context is the key to avoiding false cues: v1 does it with rules, v2 may learn it. |
| Freezing of gait | **Not covered** by the draft. | See 1.3. |

### 1.2 Tremor: data and methods

- **Datasets with time-resolved tremor labels:**
  - **Parkinson@Home tremor dataset (Radboud).** Both wrists, 200 Hz acc + gyro, 25 PD + 24 controls, ≥ 1 h unscripted activity at home, ON and OFF medication, video-annotated tremor. Access by Data Use Agreement. **This is the best match for training and testing episode detection.**
  - **PD-BioStampRC21** (forearms, accelerometer only) and **MJFF Levodopa Response Study** (GENEActiv wrist). Labels are per task (MDS-UPDRS), so coarser.
  - **BEAT-PD.** Labels are diary-level only.
- **Apple Movement Disorder API (`CMMovementDisorderManager`).**
  - Gives per-minute tremor percentages (none / slight / mild / moderate / strong) and dyskinesia.
  - Needs an Apple entitlement.
  - **Not real-time.** Results come in periodic batches, so it **cannot trigger a cue**. It can feed an all-day "tremor minutes" log in the Journal on iPhone.
- **Method in the literature and in products.** A spectral peak in about 3–7 Hz plus a band-power ratio, gated on the arm being at rest. Context matters more than frequency to tell PD from essential tremor (PD at rest; ET postural or kinetic). We already have this core in `TremorAnalyzer` (tremor-1.0).

### 1.3 Freezing of gait from the wrist

- **Freeze index (Moore 2008).** Power at 3–8 Hz over power at 0.5–3 Hz, measured on the shank. The Daphnet dataset (Bächlin 2010) uses shank, thigh and belt sensors, none on the wrist.
- **Wrist results.** FoG at the wrist is detectable but weaker than at the leg:

  | Study | Setup | Result |
  |---|---|---|
  | Mazilu 2015 | 11 PD, both wrists, per-subject models | 90 % hit rate, 83 % specificity; the wrist keeps latency but gives more false detections |
  | DeepFoG | 11 PD, one wrist | 83 % / 88 %, leave-one-subject-out |
  | EMBC 2024 | wrist accelerometer, subject-independent | 0.80 / 0.81 |
  | Borzì 2025 | multi-dataset, wrist | ~0.61 / 0.61 (abstract only) |

- **No validated consumer-smartwatch FoG detector exists.** The only shipped Apple Watch FoG cue (h2o "Foggy") is started by the user.
- **Predicting a freeze before it starts** is unproven from the wrist. We make no prediction claim.
- **Expected false positives:** stopping on purpose, turning, standing, gesturing, carrying something.
- **A phone in the trouser pocket** sits at the thigh/hip and should help (Parkinson@Home recorded one). We found no head-to-head numbers.

### 1.4 Does on-demand cueing help?

- **DeFOG RCT (Zoetewei 2024, n = 63, 4 weeks at home).** On-demand cueing improved FoG *while worn* (d ≈ 0.6), but left no carry-over effect without the device.
- **Small studies** suggest on-demand cueing is as good as or better than continuous cueing, with less habituation. **Haptic cueing evidence is weak to limited** in recent reviews.
- **No evidence-based target for cue latency was found.** Systems use 0.5–2 s windows.

### 1.5 What this means

1. **Tremor.** The signal is easy. The hard parts are **context** (don't cue while drinking or typing), **false-alarm rate**, and the fact that the benefit of a vibration cue on tremor is itself a hypothesis. A rule-based v1 on top of tremor-1.0 is sound.
2. **FoG.** Wrist-only detection is the weak link: expect roughly 0.6–0.9 sensitivity and specificity. Detect only during walking, keep "I'm stuck" as the primary path, and position automatic FoG cueing as experimental.
3. **Labelled data from our own users** (with consent) is the real asset. PADS can't provide it. "I'm stuck" presses are free FoG labels.
4. **Platform asymmetry is real.**
   - Apple Watch gives third-party apps real-time motion only inside a workout session.
   - Wear OS can run a batched sensor service in the background, as stand-up detection already does.

---

## 2. Design

### 2.1 Principles

- **"I'm stuck" always works** and always wins over any automatic logic.
- **Cue only when the event is seen in consecutive windows**, pause after each cue, never in quiet hours (PRD).
- **Same algorithm, same constants, same test vectors** in Kotlin (watch + phone copy), Swift (Shared) and the Python reference in `daedalus_watch_kit`, as for tremor-1.0. Every logged event carries the model version.
- **Wellness wording**, no diagnosis words: "Shaking noticed · Calm cue?" / "Walking cue on", never "tremor detected" in a clinical sense. Check this against the PRD tone ("Tremor detected · Grounding cue on") with the clinical adviser.
- **Raw motion leaves the watch only in session files** the person already keeps, and reaches the backend only with research consent. Monitoring sends counts only (PRD).

### 2.2 Where it runs: "Guard" sessions

Real-time detection runs inside an **armed session**, the same mechanism that keeps cues alive today:

| | Apple Watch | Galaxy Watch (Wear OS) |
|---|---|---|
| How it stays alive | `HKWorkoutSession` (walking or mind-and-body), as `CueController` does now | Foreground service, as `HapticService` / `StandWatchService` do now |
| Sensor | `CMMotionManager` device motion at 50 Hz | `SensorManager` acc + gyro at 20 000 µs, `maxReportLatency` ≈ 1 s |
| Started by | "Walk with guard" on the watch home, or from the phone's Cues screen | same |
| Ends | Stop, or automatically after 1 h (current cap; to revisit after battery tests) | same |

- **Walking guard:** FoG detector, plus the tremor detector when the person stops and rests.
- **Rest guard:** tremor detector only, e.g. while reading or watching TV.
- **All-day passive logging** (no cues) is a separate, later feature:
  - On iPhone it comes from `CMMovementDisorderManager`, if Apple grants the entitlement.
  - On Galaxy Watch it comes from a batched 50 Hz background service running the same tremor detector.
  - Both feed "tremor minutes" in the Journal. This is out of scope for v1, but the event format below covers it.

Why not always-on cueing? On Apple Watch it isn't possible without a workout session running all day, which drains the battery and risks App Review. Keeping both platforms on armed sessions gives parity (PRD risk "features differ between platforms").

### 2.3 Shared signal pipeline

Every 1 s (hop), on the last 2 s (100 samples at 50 Hz, as in the PRD), evenly resampled like tremor-1.0:

| Feature | Definition | Used by |
|---|---|---|
| `tremorDps` | RMS gyro rotation in 3–8 Hz, °/s | tremor |
| `peakHz`, `prominence` | spectral peak in 3–8 Hz; peak power / mean power elsewhere in 0.5–12 Hz | tremor |
| `slowDps` | RMS gyro rotation in 0.5–2.5 Hz (voluntary movement) | tremor gate, FoG |
| `gravity` | low-pass acc direction (wrist orientation) and its change over the window | context (hand raised, posture change) |
| `cadence`, `stepRegularity` | autocorrelation of band-passed acc magnitude (0.5–3 Hz) over the last **4 s**, 70–140 steps/min | walking state, FoG |
| `armSwingDps` | RMS gyro around the forearm swing axis, 0.5–2 Hz | walking state, FoG |
| `freezeIndex` | acc power 3–8 Hz / 0.5–3 Hz (Moore), on acc magnitude | FoG |

The DFT is evaluated only at the bins needed (0.5 Hz steps over 2 s), using the phasor loop already in `TremorAnalyzer`. That is a few thousand multiply-adds per second, which is negligible.

A small **context state** is derived per window: `STILL`, `REST_TREMOR_POSSIBLE` (low slow movement, wrist roughly level or in the lap), `ACTIVE_HAND` (slow movement high, or hand raised toward the face), `WALKING`, `TURNING` (high yaw rate while walking).

### 2.4 Tremor detector (tremor-stream-1.0)

**Window positive when all hold:**
1. Context is `REST_TREMOR_POSSIBLE` (not walking, `slowDps` < personal limit, no posture change).
2. `peakHz` is in 3.5–7.5 Hz and `prominence` ≥ 6 (tremor-1.0 value).
3. `tremorDps` ≥ the personal threshold *T* (below).

**Episode:**
- **Start:** 3 positives in the last 4 windows, with `peakHz` stable within ±1 Hz. That is about 4 s, above the PRD minimum of 2 in a row; shadow mode tunes the count between 2 and 4.
- **End:** ≤ 1 positive in the last 6 windows.
- **Logged with:** start, end, median Hz and strength (Light / Moderate / Strong, tremor-1.0 levels).

**Action (v1: ask first):**
- A short double tap plus the watch screen "Shaking noticed · Start fine motor cue?", with **Start** and **Not now** buttons. It disappears after 10 s, which is logged as "ignored".
- **Start** runs the Fine motor cue at the person's own rate for 2 min or until the episode ends + 30 s.
- **Not now** means "this was a tremor, but no cue now". A separate small "Not shaking" link records a false positive.
- **Cooldown:** 10 min after any prompt; 30 min after two "Not now" in a row. No prompt in quiet hours, while charging, or while another cue runs.

Why ask first: a tremor isn't an emergency, and the PRD's false-alarm budget (< 2 per hour at rest) is easier to hold if the person filters.

**Threshold *T* (revised 2026-11-10 after testing on Parkinson@Home):**
- **Start:** the population value, *T* = 6 °/s with prominence ≥ 16 (T4).
- **Calibration doesn't set *T*.** We simulated calibrations at 6 random quiet-sitting moments per recording. Setting *T* from 20 s of rest did worse than the population value:

  | Threshold | Event F1, tremor subjects | False episodes/h at rest, PD without tremor |
  |---|---|---|
  | Population value | 0.77 | 0.45 |
  | From 20 s of rest | 0.73 | 0.87 |
  | Population, lowered by a tremor found at calibration | 0.75 | |
  | Rest-based, kept within 0.5–1.5 × the population value | 0.75 | |

  A 20 s sample of a tremor that comes and goes is too noisy:
  - tremor-like motion during the rest raises *T* above the person's tremor;
  - a very quiet rest drops *T* into the noise.

  The calibration's tremor check still stores the person's typical frequency for the Journal.
- **Feedback:**
  - Each "Not shaking" raises *T* by 15 %. Each "I'm shaking" with no detection lowers it by 10 %.
  - *T* always stays within 0.5–1.5 × its starting value. Without that ceiling, "Not shaking" ratchets *T* up and recall falls to 0.21.
  - With the ceiling, answering half the false episodes cut false episodes at rest (PD without tremor) from 0.45 to 0.24 per hour, for 0.02 of F1.
- **Rest gate (added 2026-11-10).** A window counts only if the slow movement (0.5–2.5 Hz) is at most 0.5× the tremor-band strength, and the 0.5–2 Hz rotation over the last 4 s is at most 8 °/s.
  - **Why:** voluntary rhythmic movements in healthy controls carry 2–4× more slow movement than rest tremor.
  - **Effect:** false episodes at rest in controls fell from 0.33 to 0.08 per hour, for about 0.02 of F1 on tremor subjects.
- **Personal threshold from shadow-mode history (`tremor_learning.py`).** It needs no labels.
  - **Candidates:** rest windows with a clear 3.5–7.5 Hz peak.
  - **Evidence:** if at least 2–5 minutes of them share one rhythm (at least 40 % within ±0.75 Hz of the most common frequency), *T* drops to the 25th percentile of their strength, never below 1.5 °/s nor above the population value. The band also narrows to the person's rhythm.
  - **Tested on Parkinson@Home:** learned from one part of the visit, scored on the other.
  - **Who gets a personal threshold:** it finds the right people. No healthy control did (0 of 80 wrist-parts). All 8 tremor subjects did, as did pd08, pd17 and pd24, whose clinical exams showed rest tremor even though their videos weren't annotated.
  - **Scores:** unchanged (event F1 0.77). About 2 h of one medication state is too short a history: pd07's tremor is almost all OFF (475 s against 104 s ON), so learning from one state doesn't help detection in the other.
  - **Shadow mode** gives weeks of history across both states. Measuring the gain there is part of the phase 1 gate.

All three steps are deterministic rules that can be explained and reproduced. There is no on-device training in v1.

### 2.5 Freezing-of-gait detector (fog-1.0)

It runs only in a walking guard.

- **Walking state:** `cadence` found with `stepRegularity` ≥ 0.5 and `armSwingDps` above a floor, for ≥ 4 s. The person's walking-cue rate (already stored per person) gives an expected cadence.
- **Freeze candidate:** right after a walking state, within 2 s, all of:
  1. Cadence lost (regularity < 0.3).
  2. One of:
     - `freezeIndex` above the personal threshold, which catches trembling in place;
     - or `armSwingDps` collapses to < 30 % of the walking median while the wrist is *not* fully still, which catches shuffling or small steps.
  3. No posture change (gravity stable, so the person did not sit down) and the hand is not raised to the face.
- **Voluntary stop vs akinetic freeze:** a stop with the wrist fully still for > 3 s is treated as a voluntary stop and cancels the candidate. Akinetic freezes look the same from the wrist. This is a known blind spot; "I'm stuck" covers it.
- **Turning:** turning is a common freeze trigger, so turning windows are *not* excluded, but the cadence-lost condition must last 2 windows instead of 1.

**Action (v1: start automatically):**
- The walking cue starts at the person's own rate, exactly as "I'm stuck" does. The screen shows "Walking cue on · Stop".
- **It stops** when walking resumes for 10 s, after 60 s, or on Stop.
- **Cooldown:** 20 s before the detector can fire again. If two auto-cues are stopped within 3 s in one guard session (probably false), the detector goes quiet for 10 min.

Why automatic: during a freeze the person can least reach for a button, which is the PRD hypothesis. A false start costs a few seconds of rhythm while walking. The PRD's open question ("start on their own or ask first?") is answered here as: **FoG automatic, tremor ask-first**. This is the main decision to confirm.

**Phone in pocket:** a second detector on the phone, added after watch-only fog-1.0 (see 2.9).

### 2.6 Feedback and labels

| Source | Label it gives |
|---|---|
| "I'm stuck" during a walking guard | FoG positive. The onset is roughly 1–3 s before the press; the 10 s before the press are kept as a ring buffer in the session file |
| Auto FoG cue stopped < 3 s | probable false positive |
| Auto FoG cue followed by walking resuming within 10 s | probable true positive (weak) |
| Tremor prompt: Start / Not now / Not shaking / ignored | positive / positive / negative / unknown |
| Tremor check result (existing) | rest-tremor present or absent, with Hz |
| Research protocol blocks (POC §7 Blocks 6–7) with video | gold labels |
| Optional phone follow-up ("Your watch noticed shaking at 14:02 · Was it?"), at most once a day | positive / negative |

These feed the per-person thresholds (v1) and, with research consent, the training set for v2.

### 2.7 Events and file format

New labels in `EventLabels` (shared Kotlin, Swift, Python):

- `AUTO_TREMOR_START`, `AUTO_TREMOR_END`
- `AUTO_FOG_CANDIDATE`
- `AUTO_CUE_PROMPT`, `AUTO_CUE_ACCEPTED`, `AUTO_CUE_DECLINED`, `AUTO_CUE_FALSE`, `AUTO_CUE_IGNORED`
- `AUTO_CUE_START`, `AUTO_CUE_END`
- `GUARD_START`, `GUARD_END`, `GUARD_BATTERY`
- `POCKET_STATE` (in pocket / not / phone unreachable), `POCKET_REMINDER`, `POCKET_REMINDER_DISMISSED`

Each event carries the PRD fields:
- type, confidence (0–1, from margin over threshold), start, end, action taken, model version (`tremor-stream-1.0`, `fog-1.1`), and the threshold in force.

**Where the events go (decided 2026-11-11).** A guard session writes its events into its own session CSV, not a separate file. That keeps one file per session for the transfer to the phone and for the phone's session lists.
- The guard header adds two trailing columns:
  `timestamp_ms,heart_rate_bpm,ax,ay,az,gx,gy,gz,breathing_rate_brpm,spo2_percent,event,event_notes`
- **Sample rows** leave `event` and `event_notes` empty.
- **Event rows** fill only `timestamp_ms`, `event` and `event_notes`; every other cell is blank.
- **Notes format:** `event_notes` holds `key=value` pairs separated by `;`. It never contains a comma.
- **Keys and number formats** are fixed by the guard engine (`guard.py`, guard-1.0).
- **Compatibility:** readers find columns by header name, so tremor-check and cue files, which have no event columns, read as before.

The phone reads them from the session file like the tremor check, for the Cues card, the Journal and the shared report.

### 2.8 Phone screens

- **Cues → Automatic cues.** Replace "Coming soon" with:
  - a "Walking guard" switch (enables the FoG detector during walking guards);
  - a "Shaking prompts" switch;
  - quiet hours;
  - a link to calibration.

  The Breathing row stays "Coming soon". Both switches start in **shadow mode** (below) until the person's first week of data is in.
- **Calibration.** Reuse the tremor check (rest, 20 s), then add 10 s arms extended and a 30 s normal walk. This sets the typical tremor Hz, the walking cadence and the arm-swing baseline, but not the tremor threshold (see 2.4). It takes about 1 min, and the person can repeat it.
- **Journal.** Show auto episodes alongside manual cues, with "Was this right?" on recent ones.

### 2.9 Phone in the pocket (fog-pocket-1.0)

A phone in a trouser pocket sits on the thigh, which is close to where the freeze index was designed to work.

**Published results:**

| Study | Setup | Result |
|---|---|---|
| Kim 2015 | phone accelerometer and gyroscope | 84 % sensitivity in the trouser pocket (86 % at the waist) |
| Kim 2018 | CNN, 32 patients, trouser pocket | 93.8 % / 90.1 % |
| Capecci 2016 | 20 patients, timed up-and-go tests; placement not confirmed | 87.6 % / 95.0 % (70 % / 84 % for the plain Moore–Bächlin index) |

These are all better than the wrist results.

**What the pocket adds:**
- **Thigh angle:** horizontal when sitting, vertical when standing. This tells "sat down" from "stopped".
- **Leg trembling:** 3–8 Hz trembling in place is seen directly.
- **Step rhythm:** it is measured at the leg, not inferred from arm swing.

**Signals.** The phone's orientation in the pocket is unknown, so the detector uses only orientation-free signals:
- acceleration magnitude;
- vertical acceleration, projected on the low-passed gravity vector;
- rotation-rate magnitude;
- thigh pitch, from gravity.

From these it computes:
- the freeze index (3–8 / 0.5–3 Hz) on vertical acceleration, over 2 s windows with a 1 s hop, as on the watch;
- cadence by autocorrelation;
- a sitting/standing state from thigh pitch.

**Is the phone in a pocket?** Checked every 10 s. All three must hold:
1. The phone's step rhythm matches the watch's (cadence within 5 steps/min while walking).
2. The thigh angle is steady between steps.
3. Where available, the proximity sensor is covered and light is low. This is Android only; iOS reads proximity only in the foreground.

When the check fails (bag, hand, table, no pockets), the watch detector works alone. The check result is logged with each event.

**Combining with the watch.** The watch stays the master and plays the cue:
- The phone sends its per-window freeze score over the existing link: the Wearable Data Layer `MessageClient` on Android, `WCSession.sendMessage` on iOS. Expect a few hundred milliseconds.
- **Two candidate rules, to be chosen in shadow mode:**
  - *OR*: either a strong pocket score or the watch rule fires the cue. This gives the most sensitivity.
  - *AND-lite*: the watch rule fires only if the pocket score agrees. The pocket alone fires only above a high threshold. This gives fewer false cues.
- **Sitting:** a pocket "sitting" state cancels any freeze candidate.
- **Watch out of reach:** the phone vibrates in the pocket with the walking rhythm as a fallback cue.

**Running in the background:**

| | Android phone | iPhone |
|---|---|---|
| Sensors with the screen off | Foreground service, acc + gyro at 50 Hz, as on the Galaxy Watch | **Unknown; needs a prototype.** iOS stops motion updates for suspended apps. Candidate: the watch's walking workout mirrored to the iPhone (`startMirroringToCompanionDevice`, iOS 17+), which wakes the iPhone app in the background for the session. Check that `CMMotionManager` keeps delivering 50 Hz there and what App Review accepts |
| Order | First | After the prototype works |

**Reminder to keep the phone in the pocket.** The aim is to make the pocket the habit, without nagging.

- **When.** The watch sees the person stand up while freeze cues are on. On the Galaxy Watch, stand-up detection already runs in the background (`StandDetector`). On Apple Watch it runs only inside a guard session.
- **Check first.** After the stand-up, the watch asks the phone whether it is in a pocket. It looks again after the first 5–10 steps, since many people pocket the phone as they start walking.
- **Remind only if not.** If the phone isn't in a pocket, the watch shows one short buzz and "Phone in your pocket? It helps your walking cue." The screen has **OK** and **Not today**.
  - This joins the existing "Pause before walking" prompt when both are due, so there is one buzz, not two.
- **Phone not reachable.** If the watch can't reach the phone (left in another room), it shows "Your phone isn't with you". The watch detector carries on alone.
- **Limits:**
  - At most once per 30 min, and 3 times a day.
  - "Not today" silences it until tomorrow.
  - None in quiet hours or during a cue.
  - A setting "I don't carry my phone in a pocket" turns it off for good.
- **Logged:** each reminder and the pocket state that follows, so we learn how often people keep the phone in a pocket and whether the reminder helps.

**Data.** We have no pocket data yet. The Parkinson@Home study recorded a trouser-pocket phone, but the downloaded set contains wrist sensors only. Ask Radboud for the pocket-phone recordings together with the FoG annotations. Our own walking protocol (POC §7 Block 7) should record the phone in a pocket from now on.

---

## 3. Plan

| Phase | What | Exit gate |
|---|---|---|
| **0. Offline reference** | Python `tremor_stream.py` and `fog.py` in `daedalus_watch_kit`. Evaluated on our healthy-volunteer recordings: negatives (typing, brushing teeth, car ride, shaking a hand on purpose, cane or walker) and instructed tremor-like motion. Also on Parkinson@Home (converted CSVs: tremor episodes against the free-living mobility states, both wrists, OFF and ON medication). Golden test vectors exported for Kotlin and Swift unit tests. | Tremor F1 > 0.80 and < 2 false events/h at rest (PRD). FoG: report sensitivity, false events per hour of walking, onset-to-cue delay. FoG has no gate yet; its target is set from these numbers |
| **1. Shadow mode** | Detectors run inside guard sessions on both watches and log events, with **no cue and no prompt**. Thresholds adapt from "I'm stuck" presses, tremor checks and phone follow-ups. Battery logged per session. | ≥ 2 weeks of real use in the pilot; false-event rate within budget; battery cost of a 1 h guard measured on both watches |
| **2. Cueing v1** | Tremor ask-first, FoG automatic, behind the switches above. "Did this help?" after cues (PRD). | Pilot feedback; Gate C (beats placebo) per PRD roadmap |
| **2b. Pocket phone** | fog-pocket-1.0 on Android first: foreground service, pocket check, score sent to the watch, pocket reminder. iPhone after a background-motion prototype (workout mirroring). Runs in shadow mode first, like phase 1. | Pocket check agrees with reality in testing; choose between the OR and AND-lite rules on shadow data |
| **3. Model v2** | A small classifier (gradient-boosted trees on the features above, or a 1D-CNN on 2 s windows) trained on consented labels. LiteRT on Wear OS, Core ML on watchOS. Per-person thresholds stay on top. | Ships only if it beats v1 on the full evaluation set (PRD) |

Implementation order inside phases 0–1:
1. Python reference and test vectors.
2. Refactor `TremorAnalyzer` so the batch check and the streaming window share one spectral function (all three copies, version bump).
3. Streaming feature extractor plus both detectors on Galaxy Watch, then Apple Watch.
4. Guard sessions.
5. Events and phone read-back.
6. Phone switches and calibration.

---

## 4. Decisions (2026-11-10)

1. **Ask-first vs automatic: decided.** FoG cues start automatically, tremor cues ask first.
2. **PADS: not used.** It is out of the plan, including offline evaluation; Parkinson@Home replaces it.
3. **Parkinson@Home: obtained.** The downloaded set has both wrists (Physilog 4, 200 Hz), 25 PD and 24 controls, video-annotated mobility states and tremor (8 PD with tremor annotations, most affected side).
   - It does **not** contain the freezing-of-gait annotations described in its protocol. Ask Radboud whether they can be shared.
   - `python_scripts/parkinson_home_to_csv.py` converts it to CSV in the watch session format. The output stays in the git-ignored `dataset/` folder.
   - **Alignment check on pd18:** a 3–8 Hz / 0.5–3 Hz gyroscope power ratio separates tremor from no-tremor seconds with AUC 0.90 at zero time shift, falling off at ±5 s.
4. **Apple entitlement: request it.** `CMMovementDisorderManager` is for all-day tremor logging on iPhone (later feature, no cueing). It is requested through Apple's web form; see `apple-movement-disorder-entitlement-request.md`.
5. **Guard length:** sessions run longer than 1 h once battery use has been measured (phase 1).
6. **Cue for a tremor: Fine motor.** The tremor prompt reads "Shaking noticed · Start fine motor cue?".
7. **Phone in the pocket: added** as a second FoG detector (section 2.9). The watch reminds the person to pocket the phone when they stand up and it isn't in a pocket.

## References

- PADS: https://physionet.org/content/parkinsons-disease-smartwatch/1.0.0/ — Varghese et al., *npj Parkinson's Disease* 10:9 (2024), https://doi.org/10.1038/s41531-023-00625-7
- Parkinson@Home tremor dataset: https://data.ru.nl/collections/di/dcmn/DSC_pdtremor_t0000359a_966
- Apple Movement Disorder API: https://developer.apple.com/documentation/coremotion/getting-movement-disorder-symptom-data
- Mazilu et al. 2015, FoG from wrist movement: https://userpages.umbc.edu/~nroy/courses/shhasp18/papers/Gait%20Wrist%20and%20Sensors%20Detecting%20Freezing%20of%20Gait%20in%20Parkinsons%20Disease%20from%20Wrist%20Movement.pdf
- DeepFoG (wrist): https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8185568/
- Subject-independent wrist FoG, EMBC 2024: https://doi.org/10.1109/embc53108.2024.10781946
- Borzì et al. 2025, *J Parkinson's Dis*: https://journals.sagepub.com/doi/10.1177/1877718X241302766
- DeFOG RCT, Zoetewei et al., *Mov Disord* 2024: https://cris.unibo.it/handle/11585/1006287
- Haptic cue meta-analysis: https://research.usq.edu.au/item/zz6xz/effectiveness-of-haptic-cues-on-gait-in-people-with-parkinson-s-disease-a-systematic-review-and-meta-analysis
- Moore et al. 2008 (freeze index); Bächlin et al. 2010 (Daphnet) — classic references, not re-fetched.

Some figures above come from abstracts because the full texts were blocked: Borzì 2025, the 276/114/79 PADS group split, and DeFOG sensor placement. Check them before quoting externally.
