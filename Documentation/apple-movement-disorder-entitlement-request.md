# Apple Movement Disorder API: entitlement request (draft)

## What it involves

- **It's a web form, not an email.** Sign in as the Account Holder or an Admin of the Apple Developer team (SFQ29D84T9) and fill in the Movement Disorder Entitlement Request: https://developer.apple.com/contact/request/movement-disorder-api-entitlement/
- **Apple reviews it.** There is no published turnaround; plan for weeks.
- **If Apple approves it:**
  - Apple adds the capability to the App ID. We enable it on `com.medicina.daedaluscompanion.watchkitapp` (the API runs on the watch) and regenerate the provisioning profiles.
  - We add the entitlement to the watch target in `project.yml`, using the exact key Apple gives with the approval.
- **The motion usage text must say the feature is for people diagnosed with a movement disorder.** Apple says the API "is not designed to collect data from users who have not been diagnosed with a movement disorder". Suggested watch `NSMotionUsageDescription`:
  > Daedalus records wrist movement during cue sessions, and, if you have been diagnosed with Parkinson's and turn on tremor tracking, notes tremor through the day for your journal.
- **What we would get:** per-minute tremor and dyskinesia percentages, processed by Apple in batches and kept for 7 days. It is not real-time, so it is for the Journal's "tremor minutes" only and never triggers a cue.

The form's exact fields aren't public, and they only show after signing in. Below are answers to the questions such forms usually ask; adjust them to the fields you see.

---

## Draft answers

**Organization:** Medicina (legal entity name as in the developer account)

**Apps:** Daedalus (iPhone, `com.medicina.daedaluscompanion`) and Daedalus for Apple Watch (`com.medicina.daedaluscompanion.watchkitapp`)

**Contact:** (Account Holder name, role, email)

**Describe your app.**
Daedalus is a companion for people living with Parkinson's disease, available on Apple Watch and iPhone (and on Galaxy Watch and Android). On the watch it gives rhythmic vibration cues: a walking pace when someone feels stuck, a breathing pacer, fine-motor and calm rhythms. On the phone it keeps a private daily journal of cues, medication doses and check-ins, which the person can share with their care team. The app is a wellness tool: it makes no diagnosis or treatment claims.

**How will you use the Movement Disorder API?**
To give people who have been diagnosed with Parkinson's disease a daily view of their tremor and dyskinesia in the app's journal, next to their medication times and check-ins. They and their care team can then see patterns between visits, such as tremor returning before the next dose. The feature:
- is off by default;
- is turned on only by a person who confirms they have a Parkinson's diagnosis;
- can be turned off at any time.

We will read `CMTremorResult` and `CMDyskineticSymptomResult` and show daily and hourly summaries. We will not use the results to diagnose, to change medication, or to trigger real-time actions.

**Who are your users? Are they diagnosed with a movement disorder?**
Adults diagnosed with Parkinson's disease. The movement-disorder feature is offered only to people who state in the app that they have a diagnosis. It is first used in a pilot study run with a neurology clinic partner (name and ethics approval to add when available).

**Data handling and privacy.**
- **Where it stays:** results stay on the person's devices.
- **With an account:** they are backed up in Canada (Supabase, Canadian region). Only the account owner can see them, and they can delete them from the app.
- **Sharing with the care team:** happens only when the person asks, and needs their consent each time.
- **Research use:** off unless the person opts in.
- **No resale or advertising:** the data is never sold or used for advertising.
- **Compliance:** PIPEDA and Quebec Law 25, plus Apple's HealthKit and health-data guidelines.

**Is there a clinical or research partner?**
(Name of the neurology team or clinical adviser and the study, if confirmed. If not yet confirmed, say "a pilot with a neurology clinic is in preparation".)

**Planned release:** first in the supervised pilot (TestFlight), then on the App Store once the pilot has validated the journal summaries.

---

## Sources
- [CMMovementDisorderManager (Apple)](https://developer.apple.com/documentation/coremotion/cmmovementdisordermanager)
- [Getting movement disorder symptom data (Apple)](https://developer.apple.com/documentation/coremotion/getting-movement-disorder-symptom-data)
