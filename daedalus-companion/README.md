# Daedalus Companion — Android App

Android companion app for the **Daedalus** Parkinson's research POC.
Runs on an Android phone paired with the Samsung Galaxy Watch5 Pro.

## Features (Phase 0)

- **BLE chest strap**: Connect to Cycplus H2 Heart Rate strap, parse RR intervals
- **Watch communication**: DataLayer API for watch ↔ phone sync
- **Session management**: Start/stop sessions, log events, track state
- **Live signal display**: Real-time HR and RR interval from chest strap
- **Event markers**: Protocol block buttons (rest, stress, sync)
- **Session export**: Zip output matching `daedalus_watch_kit` schema for laptop transfer

## Building

```bash
cd daedalus-companion
./gradlew assembleDebug
```

## Installing

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## BLE: Cycplus H2 Chest Strap

The app connects to the Cycplus H2 via Bluetooth Low Energy using the standard
Heart Rate Service (UUID `0x180D`). It parses the Heart Rate Measurement
characteristic (`0x2A37`) per the Bluetooth SIG spec:

- **Flags byte**: bit 0 indicates RR interval presence
- **HR value**: UINT8 or UINT16 BPM
- **RR intervals**: UINT16 in 1/1024 second units

The Cycplus H2 exposes RR intervals (not raw ECG waveform), which is the
primary reference signal for HRV validation.

## Screen

| Control | Action |
|---------|--------|
| Connect Chest Strap | Scan and connect to Cycplus H2 via BLE |
| ▶ Start | Start session + tell watch to start recording |
| ⏹ Stop | End session + tell watch to stop |
| 📦 Export | Zip session folder for laptop transfer |
| 🧘 Rest | Log REST_START event (syncs to watch) |
| 🧠 Stress Task | Log STRESS_TASK_START event |
| 👏 Sync (Claps) | Log SYNC_CLAPS event for time sync |

## Data Flow

```
Samsung Watch5 Pro ──IMU, HR──┐
                              ├──→ Daedalus Companion (phone)
Cycplus H2 (BLE) ──RR, HR──┘  ├──→ Session files (CSV + JSON)
                              └──→ Zip export → laptop → daedalus_watch_kit
```

## Data Output

```
/Android/data/com.medicina.daedaluscompanion/files/daedalus/
├── data/raw/
│   └── {subject_id}/
│       └── {session_id}/
│           ├── phone_events.csv       ← events logged on phone
│           ├── session_metadata.json
│           ├── ecg/
│           │   └── ecg_reference.csv  ← Cycplus H2 RR intervals
│           └── (watch files received via DataLayer)
└── exports/
    └── daedalus_sub-001_sess-001_YYYYMMDD_HHMMSS.zip
```

## Architecture

```
ble/
├── ChestStrapBLEManager.kt   # BLE connection, RR interval parsing
wear/
├── WatchDataListener.kt       # WearableListenerService, DataLayer
session/
├── SessionManager.kt          # Session lifecycle, file writing, zip export
ui/
├── SessionActivity.kt         # Phase 0 UI
```

## Permissions

- `BLUETOOTH`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` — chest strap BLE
- `ACCESS_FINE_LOCATION` — required for BLE scanning on Android 11-
- `WAKE_LOCK` — keep phone awake during session
