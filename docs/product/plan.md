# Plan: home field test and interviews (D7)

Written 2026-11-13 from the accepted proposal (`docs/product/proposal.html`, direction A), and revised the same day. The owner's point: freezing can't be planned, so a clinic course with three trials per condition can't be relied on. This plan covers only what the test needs. New features stay paused until it reports (D7).

## Why the design changed

- **Freezes can't be scheduled.** A clinic visit may produce none, so it can't be the test.
- **Most freezes last 1–10 seconds** ([APDA](https://www.apdaparkinson.org/what-is-parkinsons/symptoms/freezing-of-gait/); [review, PMC6238568](https://pmc.ncbi.nlm.nih.gov/articles/PMC6238568/)). If pressing and starting the beat takes 3–5 s, many freezes end before the beat starts. The first question is therefore not only *can* they press, but whether the beat arrives *in time*.
- **So the test goes to where the freezes happen.** People use the prototype at home for three weeks. Every press is a trial, the watch measures the timings, and each press randomly gets one of the conditions. That gives dozens of real freezes per person instead of three staged ones.

## Key assumptions for the owner to check first

1. **This is research, so it needs an ethics board.** Randomizing what people get at home and recording their motion goes beyond a usability session. Use the partner clinic's research ethics board, or a commercial one. Plan 6–10 weeks for approval. **You decide** which board.
2. **Only frequent freezers take part:** people who say they freeze at least once a day. With rare freezers three weeks yields too few events. This favours people later in the disease, which is the segment anyway.
3. **The control condition.** Showing that the beat helps (H2) needs presses that get no beat. Two options:
   - **(a) Recommended:** 1 press in 4, chosen at random, gives a single short buzz and no beat. The person uses their own trick and can press again at once to get the beat. This is the business case's planned sham, and it's the cleaner comparison.
   - **(b) Gentler:** a first week where presses only log the moment and give no beat, then two weeks with the beat. This is weaker, because people change over time.

   **You decide.** The ethics board may decide for you.
4. **On iPhone, the phone plays the sound.** On Apple Watch, sound to earbuds needs a headphone picker each time, and connected earbuds may silence the watch's own vibration. So the earbud beat plays from the phone in the pocket, to earbuds paired with it. On Wear OS the watch plays it, which gets checked on the device first.
5. **The beat starts from the app's own screen, which stays on the watch face.** A one-press start from the watch face isn't built. People keep Daedalus as the app that shows when they raise their wrist, a watch setting on both platforms. The time from raising the wrist to pressing is part of what's measured.
6. **Devices are lent.** Participants without a compatible watch get a loaner (an Apple Watch with iPhone, or a Galaxy Watch). The business case budgets CAD 15–30k for devices. **You decide** whether to lend devices or recruit only watch owners. Owners-only is cheaper but slower to recruit (H4).
7. **12 people, so 10 finish.** Within-person comparisons over many presses make 10 enough to see a clear direction. They are not a powered trial, and H2 and H3 come out as a direction, not proof.

## How it runs

**One clinic visit (about 90 minutes), with the physio:**
- Consent, set the person's pace (the physio counts their steps over 30 s and enters it), and practise pressing.
- **Pressing drill:** while standing still and counting backwards, the person presses when asked. This gives each person's fastest press time, a floor for H1.
- **Optional filmed walk, best in the morning before the first dose:** sit, walk, doorway, turn. Any freeze that happens is filmed. The video checks that the watch's timings (walking stops, press, steps resume) agree with what is seen. It isn't the test, and no freeze is needed for the visit to succeed.

**Three weeks at home:**
- **Week 1 (learning):** every press gives the beat, alternating wrist and earbuds, so people get used to both.
- **Weeks 2–3 (randomized):** each press randomly gets the wrist beat, the earbud beat (only when earbuds are connected), or the control from assumption 3.
- **After each beat stops, one tap on the watch:** "It helped", "It didn't help" or "I was already moving". At most 6 questions a day; after that, presses are logged without asking.
- **A weekly 5-minute call with the physio:** problems, anything annoying or embarrassing, and the "very disappointed" question in week 3.

**What the watch records around each press:** motion from 30 s before to 60 s after the press, kept in a rolling buffer so it isn't recording all day, plus the press time, the condition, when the beat started, when it stopped, and the check-in answer.

## Measures and the results that change the recommendation

| Hypothesis | Measure | Changes the recommendation if |
|---|---|---|
| **H1:** a frozen person can start the beat in time | Seconds from walking stopping to the beat starting (from the motion buffer); share of check-ins answering "I was already moving" | The median is over 4 s, or more than half the check-ins say "already moving" → a press can't reach most freezes. Move to cues that start before a freeze (on standing up, or a beat that runs for the first seconds of every walk) and bring automatic detection forward |
| **H2:** the beat gets people walking sooner | Seconds from press to steady steps, beat against control, within each person | The beat is no faster than the control for 7 of 10 people → the rhythm itself isn't helping. Stop the cue direction (R3) and lead with the dose-and-sleep report |
| **H3:** sound works at least as well as the wrist | The same measure, wrist against earbuds, within each person, plus preference | Earbuds are faster for most people → sound leads, and the wrist is the trigger |
| **H4–H6, H11** | The interviews (below) | As in proposal § 9 |

Also reported, without thresholds: presses per day, freezes the person didn't press for (walking stops with no press, from the buffer where recorded), walker or trolley users' press times, battery per day, and drop-outs.

## What to build (prototype only, behind a "study" switch)

| # | Change | Where | Done when |
|---|---|---|---|
| B1 | **Sound beat on iPhone.** A watch press makes the iPhone play a click at the person's pace through the current route (earbuds), stopping with the watch cue. | `DaedalusWatch/Cue/PhoneLink.swift`, `CueController.swift`; a beat player in `Daedalus/` | Clicks start within 1 s of the press, timing drift stays under 20 ms over 2 min (unit test on the scheduler), checked on a real iPhone with AirPods |
| B2 | **Sound beat on Wear OS.** The watch plays to its connected earbuds; without them, the phone plays it. | `daedalus-watch/.../haptics/HapticService.kt`; `sync/` | Same, on a Galaxy Watch with Galaxy Buds |
| B3 | **Random condition per press.** Week 1 alternates; weeks 2–3 draw wrist, earbuds or control, with the schedule seeded per person and logged. The control is a single buzz. | `CueController` / `HapticService`, with the schedule shared from Python golden vectors | Over 1,000 simulated presses the draw matches the planned shares within 3%, and a press without earbuds never draws "earbuds" |
| B4 | **Motion buffer around each press.** Keeps 30 s of motion rolling and saves from 30 s before to 60 s after a press, instead of all-day recording. | `SessionRecorder` (iOS), `SessionDataLogger` (Android) | One file per press. Battery over a 14-hour day is measured on both watches before the study starts |
| B5 | **One-tap check-in** after the beat stops, at most 6 a day. | Watch cue screens on both platforms | Its answer is saved in that press's file; the 7th press of the day doesn't ask |
| B6 | **Lift the one-hour session limit for study mode.** The cue still stops on Stop, and after 10 minutes at most. | `CueController.maxSessionDuration`; Android equivalent | A 10-minute beat stops by itself; outside study mode nothing changes |
| B7 | **Analysis.** Reads the press files, finds when walking stopped and when steps resumed (reusing the kit's cadence features), and prints H1–H3 against the thresholds. | `python_scripts/field_test.py`; tests beside the kit's | Golden press files give the expected timings and verdicts. Agreement with the filmed walk within 1 s is reported |
| B8 | **Upload to a separate study store.** Press files go over Wi-Fi to a study bucket, never to the production tables. | Supabase: a separate project or bucket for the study, chosen with you | Nothing from study mode reaches production tables. Setting it up touches Supabase and needs your go |

Not built for the test: a one-press start from the watch face, auto-stop when walking resumes, the calibration walk, Fine motor and Calm behind a setting (D3), the adjustable dose time, the doses-and-sleep screen. They go in the R1 build after the test.

## Interviews (in parallel, no build needed)

20 one-on-one interviews: 8–10 people who freeze with their carers, 5 physios, 5 neurologists. Each opens with "Walk me through your last freeze: how long did it last, and what got you going?" Then show screens 1–7 and the CAD 9.99 price. Record watch ownership (H4), price reaction (H5), whether physios would offer it (H6) and what neurologists make of the doses-and-sleep screen (H11). The guide goes in `docs/product/interview-guide.md` before the first interview. The interviews can start before the ethics approval; the field test can't.

## Timeline (estimates)

| Weeks | What |
|---|---|
| 0–2 | Build B1–B7. Partner clinic signed. Ethics submission. Interviews start |
| 2–10 | Ethics review. Battery test. Dry run with a healthy volunteer. Interviews finish |
| 10–16 | Recruit 12 people; clinic visits; three weeks at home each, overlapping |
| 16–17 | Analysis and readout to the proposal page |

## Tests and checks

- Unit tests for B1–B3, B5–B7 run in the normal gate (AGENTS.md § 1.3).
- B1, B2, B4 and B5 are checked on real devices, since simulators have no haptics or Bluetooth audio.
- **Nothing touches production Supabase or real users' data.** B8 sets up a separate study store only with your go. Sessions with people start only after ethics approval and your go.

## Acceptance

- B1–B8 merged behind the study switch, with a fresh independent review clean.
- Ethics approval in hand.
- Battery over a 14-hour day measured on both watches.
- A dry run with a healthy volunteer produces correct press files.
- After the field test, the proposal page shows H1–H3 results, with hypothesis statuses and the recommendation updated.
