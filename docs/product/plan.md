# Plan: home pilot and interviews (D7)

Written 2026-11-13 from the accepted proposal (`docs/product/proposal.html`, direction A), and revised twice the same day:
- **Freezes can't be scheduled.** A clinic course with three trials per condition can't be relied on.
- **No ethics board application.** The test therefore runs as a product pilot, with no condition that withholds the beat.
- **A third place for the beat (owner, 2026-11-13).** The phone in the front trouser pocket vibrates the beat at the hip. No study tested the waist, but every positive vibration result cued closer to the legs or trunk than the wrist. This tests the site before any belt device is built. It has a side benefit: a phone carried in the trouser pocket is also where the business case's sizing found freeze detection works best (AUROC 0.85 with the phone against 0.80 for the wrist alone), so the habit helps R2 later.

This plan covers only what the test needs. New features stay paused until it reports (D7).

## Why the design changed

- **Freezes can't be scheduled.** A clinic visit may produce none, so it can't be the test.
- **Most freezes last 1–10 seconds** ([APDA](https://www.apdaparkinson.org/what-is-parkinsons/symptoms/freezing-of-gait/); [review, PMC6238568](https://pmc.ncbi.nlm.nih.gov/articles/PMC6238568/)). If pressing and starting the beat takes 3–5 s, many freezes end before the beat starts. The first question is therefore not only *can* they press, but whether the beat arrives *in time*.
- **So the test goes to where the freezes happen.** People use the prototype at home for three weeks. Every press is a trial, the watch measures the timings, and each press randomly gets one of the conditions. That gives dozens of real freezes per person instead of three staged ones.

## What the pilot can and cannot answer

- **It can answer H1** (does the beat arrive before the freeze ends), **H3** (wrist or earbuds) and **H12** (hip or wrist).
- **It cannot answer H2** (does the beat get people walking sooner than they would without it). Answering H2 needs a condition that gives some presses no beat, and withholding help at random is what makes this research. H2 moves to the R3 controlled trial, which the business case always planned with ethics approval.
- **Its results guide product decisions only.** They can't be published or used as clinical evidence in a Health Canada file, because consent for research and an ethics review can't be added afterwards. If you may want either, an ethics board is needed from the start.

## Key assumptions for the owner to check first

1. **A product pilot by a private company needs no ethics board. This is my reading, not legal advice; ask the regulatory consultant to confirm, at the same time as the Class I question (D4).**
   - **Canada:** the research ethics policy (TCPS 2) binds institutions that take federal research funding, and their staff. A private company evaluating its own product with its users isn't bound, as long as no hospital or university researcher runs it.
   - **Who the partner is:** the physio partner must be a **private practice**, not a hospital or university clinic. Those usually require their own ethics review of anything their staff do with patients beyond normal care.
   - **No academic co-authors and no grant money** tied to the pilot. Either would bring TCPS 2 in.
   - **Apple:** App Store rule 5.1.3 requires an ethics board for apps doing health research. The pilot build goes out as an ad hoc build installed on registered devices (up to 100 Apple Watches), not through the App Store or TestFlight's external review. On Android it uses the Play Console's internal testing track, which has no review. Neither route puts the build in front of App Review.
   - **Health Canada:** as a planned Class I device (D4), supplying it for a pilot should need no investigational testing authorization. Confirm that with the consultant too.
2. **Only frequent freezers take part:** people who say they freeze at least once a day. With rare freezers three weeks yields too few events. This favours people later in the disease, which is the segment anyway.
3. **No control condition.** Every press gives a beat. The only thing that varies is where it plays: wrist, earbuds, or the phone vibrating in the front trouser pocket. Comparing working versions of a feature is ordinary product testing (A/B).
   - **Consent:** written in plain language, separate from the app's terms. It says what is recorded, where it's stored (Canada), who sees it, that the pilot is not a medical treatment, and how to leave and delete the data.
   - **Quebec's Law 25** requires express consent for health data from Quebec residents. It's simplest to recruit outside Quebec for this pilot, or to have the privacy adviser check the consent.
4. **On iPhone, the phone plays the sound.** On Apple Watch, sound to earbuds needs a headphone picker each time, and connected earbuds may silence the watch's own vibration. So the earbud beat plays from the phone in the pocket, to earbuds paired with it. On Wear OS the watch plays it, which gets checked on the device first.
5. **The beat starts from the app's own screen, which stays on the watch face.** A one-press start from the watch face isn't built. People keep Daedalus as the app that shows when they raise their wrist, a watch setting on both platforms. The time from raising the wrist to pressing is part of what's measured.
6. **Devices are lent.** Participants without a compatible watch get a loaner (an Apple Watch with iPhone, or a Galaxy Watch). The business case budgets CAD 15–30k for devices. **You decide** whether to lend devices or recruit only watch owners. Owners-only is cheaper but slower to recruit (H4).
7. **12 people, so 10 finish.** Within-person comparisons over many presses make 10 enough to see a clear direction. They are not a powered trial, and H3 and H12 come out as a direction, not proof. Three places means fewer presses per place, about 10–20 per person in weeks 2–3 at 3–5 presses a day.

## How it runs

**One clinic visit (about 90 minutes), with the physio:**
- Consent, set the person's pace (the physio counts their steps over 30 s and enters it), and practise pressing.
- **Pressing drill:** while standing still and counting backwards, the person presses when asked. This gives each person's fastest press time, a floor for H1.
- **Optional filmed walk, best in the morning before the first dose:** sit, walk, doorway, turn. Any freeze that happens is filmed. The video checks that the watch's timings (walking stops, press, steps resume) agree with what is seen. It isn't the test, and no freeze is needed for the visit to succeed.

**Three weeks at home:**
- **Week 1 (learning):** every press gives the beat, rotating through wrist, earbuds and pocket, so people get used to all three.
- **Every day:** people carry the phone in a front trouser pocket. The physio shows them at the visit. When the watch sees them stand up and the phone isn't moving with them, the watch reminds them (B10).
- **Weeks 2–3:** each press randomly plays the beat in one of the places available at that moment:
  - **wrist:** always available;
  - **earbuds:** only when earbuds are connected;
  - **pocket:** only when the phone counted steps in the last minute, a sign it's being carried and not left on a table.
- **After each beat stops, one tap on the watch:** "It helped", "It didn't help", "I was already moving" or "I didn't feel it". At most 6 questions a day; after that, presses are logged without asking.
- **A weekly 5-minute call with the physio:** problems, anything annoying or embarrassing, and the "very disappointed" question in week 3.

**What the watch records around each press:** motion from 30 s before to 60 s after the press, kept in a rolling buffer so it isn't recording all day, plus the press time, the condition, when the beat started, when it stopped, and the check-in answer.

## Measures and the results that change the recommendation

| Hypothesis | Measure | Changes the recommendation if |
|---|---|---|
| **H1:** a frozen person can start the beat in time | Seconds from walking stopping to the beat starting (from the motion buffer); share of check-ins answering "I was already moving" | The median is over 4 s, or more than half the check-ins say "already moving" → a press can't reach most freezes. Move to cues that start before a freeze (on standing up, or a beat that runs for the first seconds of every walk) and bring automatic detection forward |
| **H2:** the beat gets people walking sooner | Not measured in the pilot; it needs a no-beat condition. Reported only as a description: seconds from press to steady steps, and the check-in answers | — (deferred to the R3 controlled trial) |
| **H3:** sound works at least as well as the wrist | Seconds from press to steady steps, wrist against earbuds, within each person, plus preference | Earbuds are faster for most people → sound leads, and the wrist is the trigger |
| **H12:** a beat felt at the hip works better than at the wrist | The same measure, pocket against wrist, within each person; check-ins answering "didn't feel it"; preference | The pocket is faster for most people and felt reliably → design a simple belt or clip device for R1 or R2, with a pre-certified Bluetooth module. Otherwise no extra hardware |
| **H4–H6, H11** | The interviews (below) | As in proposal § 9 |

Also reported, without thresholds: presses per day, freezes the person didn't press for (walking stops with no press, from the buffer where recorded), walker or trolley users' press times, battery per day, and drop-outs.

## What to build (prototype only, behind a "study" switch)

| # | Change | Where | Done when |
|---|---|---|---|
| B1 | **Sound beat on iPhone.** A watch press makes the iPhone play a click at the person's pace through the current route (earbuds), stopping with the watch cue. | `DaedalusWatch/Cue/PhoneLink.swift`, `CueController.swift`; a beat player in `Daedalus/` | Clicks start within 1 s of the press, timing drift stays under 20 ms over 2 min (unit test on the scheduler), checked on a real iPhone with AirPods |
| B2 | **Sound beat on Wear OS.** The watch plays to its connected earbuds; without them, the phone plays it. | `daedalus-watch/.../haptics/HapticService.kt`; `sync/` | Same, on a Galaxy Watch with Galaxy Buds |
| B3 | **Place per press.** Week 1 rotates; weeks 2–3 draw at random among the places available at that moment (wrist always; earbuds if connected; pocket if the phone counted steps in the last minute), seeded per person and logged. No press ever goes without a beat. | `CueController` / `HapticService`, with the schedule shared from Python golden vectors | Over 1,000 simulated presses each available place is drawn in equal shares within 3%; an unavailable place is never drawn; no draw yields silence |
| B10 | **"Phone in your pocket?" reminder (owner, 2026-11-13).** When the watch detects a stand-up, it asks the phone whether it counted steps or moved in the last minute. If not, the watch shows "Phone in your front pocket?" with a gentle tap, or adds it as a line on the existing "Pause before walking" prompt. At most once an hour, never while a beat is playing, and off with one setting. | `StandDetector` / `StandPromptActivity` (Wear OS); Apple Watch has no stand-up detector yet, so it's ported from the Kotlin one with shared test vectors; phone step count via the existing watch–phone link | Shown after a stand-up with the phone left still; not shown when the phone walked along; never twice within an hour (unit test on the rule). Checked on both watches. **Apple Watch risk:** an app can't watch for stand-ups in the background without a running session. If the battery test (B4) rules out a light all-day session, Apple Watch falls back to the reminder at set times the person chooses, and the plan says so |
| B9 | **Pocket beat.** A watch press makes the phone vibrate the beat at the person's pace, strong pulses, stopping with the watch cue. | iPhone: Core Haptics in `Daedalus/`, beside B1. Android: `Vibrator` from the phone app's foreground service | Felt through trouser fabric at the pace set (checked on a real phone in a pocket). On iPhone, vibration while the phone is locked in a pocket is the main risk: if iOS doesn't allow it, the pocket place runs on Android phones only, and the plan says so |
| B4 | **Motion buffer around each press.** Keeps 30 s of motion rolling and saves from 30 s before to 60 s after a press, instead of all-day recording. | `SessionRecorder` (iOS), `SessionDataLogger` (Android) | One file per press. Battery over a 14-hour day is measured on both watches before the study starts |
| B5 | **One-tap check-in** after the beat stops, at most 6 a day. | Watch cue screens on both platforms | Its answer is saved in that press's file; the 7th press of the day doesn't ask |
| B6 | **Lift the one-hour session limit for study mode.** The cue still stops on Stop, and after 10 minutes at most. | `CueController.maxSessionDuration`; Android equivalent | A 10-minute beat stops by itself; outside study mode nothing changes |
| B7 | **Analysis.** Reads the press files, finds when walking stopped and when steps resumed (reusing the kit's cadence features), and prints H1, H3 and H12 against the thresholds, and H2's description. | `python_scripts/field_test.py`; tests beside the kit's | Golden press files give the expected timings and verdicts. Agreement with the filmed walk within 1 s is reported |
| B8 | **Upload to a separate study store.** Press files go over Wi-Fi to a study bucket, never to the production tables. | Supabase: a separate project or bucket for the study, chosen with you | Nothing from study mode reaches production tables. Setting it up touches Supabase and needs your go |

Not built for the test: a one-press start from the watch face, auto-stop when walking resumes, the calibration walk, Fine motor and Calm behind a setting (D3), the adjustable dose time, the doses-and-sleep screen. They go in the R1 build after the test.

## Interviews (in parallel, no build needed)

20 one-on-one interviews: 8–10 people who freeze with their carers, 5 physios, 5 neurologists. Each opens with "Walk me through your last freeze: how long did it last, and what got you going?" Then show screens 1–7 and the CAD 9.99 price. Record watch ownership (H4), price reaction (H5), whether physios would offer it (H6) and what neurologists make of the doses-and-sleep screen (H11). The guide goes in `docs/product/interview-guide.md` before the first interview. The interviews can start right away.

## Timeline (estimates)

| Weeks | What |
|---|---|
| 0–3 | Build B1–B7, B9 and B10. Sign a private physio practice. The consultant confirms no ethics board and no investigational authorization. Consent sheet written. Interviews start |
| 3–4 | Battery test. Dry run with a healthy volunteer. Ad hoc and internal-testing builds |
| 4–10 | Recruit 12 people; clinic visits; three weeks at home each, overlapping. Interviews finish |
| 10–11 | Analysis and readout to the proposal page |

## Tests and checks

- Unit tests for B1–B3, B5–B7, B9 and B10 run in the normal gate (AGENTS.md § 1.3).
- B1, B2, B4, B5, B9 and B10 are checked on real devices, since simulators have no haptics or Bluetooth audio.
- **Nothing touches production Supabase or real users' data.** B8 sets up a separate study store only with your go. Sessions with people start only after the consultant's confirmation and your go.

## Acceptance

- B1–B10 merged behind the study switch, with a fresh independent review clean.
- The consultant's written confirmation that the pilot needs no ethics board or investigational authorization, and the consent sheet approved by you.
- Battery over a 14-hour day measured on both watches.
- A dry run with a healthy volunteer produces correct press files.
- After the pilot, the proposal page shows H1, H3 and H12 results and H2's description, with hypothesis statuses and the recommendation updated.
