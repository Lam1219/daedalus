# Daedalus POC — Gap Analysis

*Generated from comparing the POC document (28 sections) against all implemented code across 5 projects.*

## Legend
- ✅ **Complete** — Fully implemented and tested
- 🟡 **Partial** — Implemented but incomplete or missing features
- 🔴 **Missing** — Not implemented at all
- ⬜ **Out of Scope** — Explicitly deferred per POC (Phase 2+)

---

## 1. Python Library (daedalus_watch_kit)

| POC Section | Requirement | Status | Notes |
|-------------|------------|--------|-------|
| §11 Library Architecture | Package structure, tests | ✅ | 12 modules, 10 test files, 141 tests passing |
| §12 Stage 1: Ingestion | Load IMU, HR, ECG, events, annotations | ✅ | Full schema validation, completeness report |
| §12 Stage 2: Time Sync | Offset, drift, cross-correlation | ✅ | Offset estimation, linear drift, sync error computation |
| §12 Stage 3: Windowing | 6 window configs with overlap | ✅ | All 6 configs implemented (SQI, HRV, tremor, gait, stress) |
| §12 Stage 4: Signal Quality | HR_SQI, IMU_SQI, bad-segment detection | ✅ | From HR and from PPG variants, classify_bad_segments |
| §13 HRV: IBI Cleaning | Ectopic beat removal, interpolation | ✅ | clean_ibi with physiological range + step-change filters |
| §13 HRV: Time Domain | mean_hr, sdnn, rmssd, pnn50, nn_outlier_rate | ✅ | All 5 features |
| §13 HRV: Frequency Domain | lf_power, hf_power, lf_hf_ratio, total_power | ✅ | Welch + Lomb-Scargle methods |
| §13 HRV: Nonlinear | sample_entropy | 🟡 | sample_entropy implemented; dfa_alpha1 and hrv_triangular_index **missing** |
| §13 HRV: PPG pipeline | Bandpass, baseline removal, motion cancellation | 🔴 | Not implemented — assumes no raw PPG access (per POC assumption) |
| §14 Stress: Personalized Baseline | 5-min rest baseline metrics | ✅ | PersonalBaseline dataclass |
| §14 Stress: Rule-based Score | z-score formula, 0-100 normalization | ✅ | compute_stress_score with configurable weights |
| §14 Stress: Classifier | Logistic regression, RF, XGBoost | 🟡 | PersonalizedStressClassifier with threshold-based binary classification; **ML models not implemented** |
| §14 Stress: Evaluation | F1, false alarm rate, leave-one-out | 🟡 | evaluate_stress_detection returns F1/precision/recall/false_alarm; **cross-validation not implemented** |
| §15 Tremor: Features | band_power, dominant_freq, spectral_entropy, etc. | ✅ | All 7 features computed |
| §15 Tremor: Classification | Rule-based + probability output | ✅ | Both rule-based and probabilistic classification |
| §15 Tremor: STFT | 2-sec window, 50Hz, FFT 128/256, 50% overlap | ✅ | Via scipy.signal.stft |
| §16 Gait: Detection | Peak detection, autocorrelation | ✅ | Both methods implemented |
| §16 Gait: Cadence | 60/dominant_period, 4-sec windows | ✅ | Autocorrelation-based + peak-counting fallback |
| §16 Gait: Regularity | cadence_variability, harmonic_ratio, gait_bout_length | ✅ | All 3 features |
| §16 Gait: Freezing Proxy | Arm swing reduction, variability, context | ✅ | detect_gait_instability_candidate |
| §17 Haptic: Patterns | Gait metronome, breathing, tremor grounding | ✅ | All 3 pattern generators + latency measurement |
| §17 Haptic: Metrics | Latency, jitter, missed pulse | ✅ | compute_haptic_performance with all targets |
| §18 Validation Report | Per-session HTML report | 🔴 | **Not implemented** — no report generation (Bland-Altman, scatter plots, confusion matrices) |
| §21 Data Quality Rules | Session rejection, window flagging | ✅ | All thresholds in quality_rules.py |
| §9 File Schema | Parquet/CSV/JSON schemas | ✅ | schemas.py + matching CSV writers in apps |

---

## 2. Wear OS Watch App (daedalus-watch)

| POC Section | Requirement | Status | Notes |
|-------------|------------|--------|-------|
| §5 Watch App | Wear OS data-collection app | ✅ | Full app with foreground service |
| §5 Start/Stop recording | | ✅ | Via SensorCollectionService |
| §5 Sensor status check | | ✅ | getSensorStatus() debug display |
| §5 Battery logging | 🟡 | Battery level available via BatteryManager but **not actively logged** |
| §5 Local storage | ✅ | CSV files in /data/raw/{subject}/{session}/ |
| §5 Periodic sync to phone | 🟡 | Wear OS DataLayer (Android) + BLE Central (iPhone) — **DataLayer receives but doesn't auto-push** |
| §5 Event markers | ✅ | Sync taps, sync claps buttons |
| §5 Haptic cue engine | ✅ | HapticEngine with all 3 patterns |
| §5 Debug screen | ✅ | Status display with IMU availability, collecting state, vibrator |
| §5 IMU at 50Hz | ✅ | ImuSensorManager at 20000μs (50Hz) |
| §5 HR continuous | ✅ | Health Services API via callbackFlow |
| §5 HRV if exposed | 🟡 | Attempted via Health Services; HRV not confirmed available |
| §5 Raw PPG | ⬜ | Per POC assumption — not expected to be available |
| §5 GPS | ⬜ | Not implemented (medium priority, disabled by default) |
| §5 Haptic event timestamps | ✅ | Logged to watch_haptics.csv |
| §5 Foreground service 60min | ✅ | SensorCollectionService with foreground notification |
| §10 Time Sync: Triple tap | ✅ | SYNC_TAPS event logged |
| §10 Time Sync: Five claps | ✅ | SYNC_CLAPS event logged |
| §7 Block 0: Consent/setup | 🔴 | **No UI for participant metadata collection** |
| §7 Block 2: Rest baseline | 🟡 | Event button exists; **no protocol timer/guidance** |
| §7 Block 3: Paced breathing | 🟡 | Haptic breathing pattern exists; **no guided breathing UI** |
| §7 Block 4: Stress task | 🟡 | Event button exists; **no stress task UI or score collection** |
| §7 Block 5: Movement matrix | 🔴 | **No movement task UI** (10 movement types) |
| §7 Block 6: Tremor tasks | 🔴 | **No tremor task UI** (5 tremor tasks) |
| §7 Block 7: Gait protocol | 🔴 | **No gait task UI** (7 gait tasks) |
| §7 Block 8: Haptic tests | ✅ | Haptic test button + patterns available |
| §7 Block 9: Recovery/questionnaires | 🔴 | **No post-session questionnaire UI** |
| §22 Privacy: Participant IDs only | ✅ | Uses subject_id/session_id, no names |
| §22 Regulatory language | ✅ | No treatment claims in app text |

---

## 3. Android Companion App (daedalus-companion)

| POC Section | Requirement | Status | Notes |
|-------------|------------|--------|-------|
| §5 Phone App | Companion Android app | ✅ | Full app with session management |
| §5 Pair with watch | ✅ | Wear OS DataLayer (WatchDataListener + WatchDataClient) |
| §5 Receive watch streams | ✅ | DataLayer receives IMU/HR/events |
| §5 Record ECG/chest strap | ✅ | ChestStrapBLEManager parses RR intervals from Cycplus H2 |
| §5 Record event labels | ✅ | Event marker buttons + auto-logged events |
| §5 Record session metadata | ✅ | SessionMetadata → session_metadata.json |
| §5 Store files locally | ✅ | CSV files in /Android/data/.../daedalus/data/raw/ |
| §5 Export session package | ✅ | Zip export with all session files |
| §5 Live signal quality debug | 🟡 | HR display shows live data; **no signal quality visualization** |
| §10 NTP sync | ⬜ | Relies on Android system time (auto-NTP) |
| §7 Protocol blocks UI | 🟡 | 3 event buttons (Rest, Stress, Sync); **missing 7 protocol block types** |
| §8 Event Label Dictionary | ✅ | All events defined in shared EventLabels |
| §9 File structure | ✅ | Matches daedalus_watch_kit schema exactly |
| §21 Data quality: reject criteria | 🔴 | **No session validation/rejection logic** |
| §22 Privacy | ✅ | Participant IDs only, no names stored |

---

## 4. iOS Companion App (daedalus-companion-ios)

| POC Section | Requirement | Status | Notes |
|-------------|------------|--------|-------|
| §5 Phone App | iPhone companion for watch | ✅ | SwiftUI app with dual BLE |
| BLE Peripheral (watch→iPhone) | ✅ | WatchBLEManager with custom Daedalus service |
| BLE Central (chest strap→iPhone) | ✅ | ChestStrapBLEManager with Heart Rate Service (0x180D) |
| Session management | ✅ | SessionManager with start/end/export |
| Event markers | ✅ | Rest, Stress, Sync buttons |
| Export to zip | ✅ | Zip via /usr/bin/zip, iOS share sheet |
| French localization | ✅ | Localizable.strings (fr.lproj) |
| Live signal display | ✅ | HR + RR from chest strap, watch HR |
| §7 Protocol blocks UI | 🟡 | 3 event buttons; **missing full protocol flow** |
| §9 File schema match | ✅ | CSV/JSON output matches Python library |

---

## 5. Cross-Cutting / Infrastructure

| POC Section | Requirement | Status | Notes |
|-------------|------------|--------|-------|
| §4 Hardware: Watch5 Pro | ✅ | minSdk 30, Wear OS 3+ |
| §4 Hardware: Cycplus H2 | ✅ | BLE Heart Rate Service parsing |
| §4 Hardware: Video recording | 🔴 | **No video recording integration** (for labeling) |
| §4 Hardware: Nice-to-haves | 🔴 | Foot pod, GAITRite, respiratory belt, metronome, BP cuff — **none implemented** |
| §6 Phase 0: Founder pilot (N=1) | 🟡 | Pipeline works; **no automated validation** |
| §6 Phase 1: Healthy cohort (N=10-20) | 🔴 | **No multi-subject management, no cohort diversity tracking** |
| §6 Phase 2: PD pilot | ⬜ | Out of scope per POC sequencing |
| §19 Validation Report | 🔴 | **No automated report generation** (Bland-Altman, scatter plots, confusion matrices, histograms) |
| §19 HR vs ECG validation | 🔴 | **No comparison logic built** |
| §19 HRV validation | 🔴 | **No correlation analysis built** |
| §19 Signal quality report | 🟡 | signal_quality_report() exists; **no HTML output** |
| §19 Haptic performance report | 🟡 | Metrics computed; **no visualization** |
| §22 Data versioning (DVC) | 🔴 | **Not set up** |
| §22 Encrypted drives | ⬜ | Infrastructure concern, not app code |
| §23 Risk: Battery drain | 🟡 | Foreground service runs; **no battery monitoring/logging** |
| §23 Risk: Haptic latency | ✅ | Measured and logged; trigger-to-onset tracked |
| §24 Minimum Viable Dataset | 🔴 | **No dataset aggregation or summary tools** |
| §25 Investor deliverables | 🔴 | **No demo dashboard, no proof report generator** |
| §26 Day 1-3 checklist | ✅ | All 72-hour tasks covered by implemented code |
| §27 Primary metric: Signal quality | ✅ | HR_SQI and IMU_SQI fully implemented |

---

## Summary by Priority

### Critical Gaps (Block Phase 0 Validation)
1. 🔴 **Validation report generator** (§18) — No automated Bland-Altman plots, scatter plots, confusion matrices, or HTML reports
2. 🔴 **Protocol block UI** (§7 Blocks 2-9) — Only 3 of 10 protocol blocks have UI buttons; no guided flow, timers, or task instructions
3. 🔴 **Video recording integration** (§4) — No camera integration for labeling movement/gait/tremor events
4. 🔴 **Battery monitoring** (§5, §23) — Battery level not logged during sessions

### Important Gaps (Block Phase 1 Scale-Up)
5. 🔴 **Multi-subject management** (§6) — No subject registration, consent tracking, or cohort diversity logging
6. 🔴 **Session validation/rejection** (§21) — No automated criteria checking (IMU completeness >95%, sync error <250ms, etc.)
7. 🔴 **HR vs ECG comparison** (§19) — No statistical comparison logic built into the pipeline
8. 🟡 **ML classifiers** (§14) — Only rule-based stress scoring; no logistic regression, RF, or XGBoost

### Nice-to-Have Gaps
9. 🔴 **Nonlinear HRV features** (§13) — dfa_alpha1 and hrv_triangular_index missing
10. 🔴 **Post-session questionnaires** (§7 Block 9) — No stress/fatigue/comfort score collection UI
11. 🔴 **Data versioning (DVC)** (§22) — Not set up
12. 🔴 **Metronome app integration** (§4) — No external metronome for paced walking/breathing

### Out of Scope (Deferred)
- PD pilot protocol (§20) — Requires clinical approval, out of scope for Phase 0/1
- Raw PPG pipeline (§13) — Per POC assumption, not available to third-party developers
- Freezing of Gait detection (§16) — Explicitly deferred ("too advanced for first proof")
- Speech task (§20) — PD pilot addition
- Investor deliverables (§25) — Post-Phase 1

---

## Effort Estimate to Close Critical Gaps

| Gap | Effort | Priority |
|-----|--------|----------|
| Protocol block UI (watch + phone) | 2-3 weeks | P0 |
| Validation report generator (Python) | 1-2 weeks | P0 |
| Battery monitoring + logging | 2-3 days | P0 |
| Video recording integration | 1 week | P1 |
| Session validation/rejection | 3-5 days | P1 |
| HR vs ECG comparison logic | 3-5 days | P1 |
| ML classifiers (stress) | 1-2 weeks | P2 |
| Multi-subject management | 1 week | P2 |

**Total to close P0 gaps: ~4-6 weeks** (aligns with POC's Week 2-5 timeline)
