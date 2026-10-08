# Daedalus Watch — Wear OS App

Wear OS sensor collection app for the **Daedalus** Parkinson's research POC.
Runs on the **Samsung Galaxy Watch5 Pro**.

## Features (Phase 0)

- **IMU collection**: Accelerometer + gyroscope at 50 Hz via Android SensorManager
- **Heart rate**: HR monitoring via Health Services API
- **Haptic engine**: Gait metronome, breathing pacing, tremor grounding, latency test
- **Data logging**: CSV output matching `daedalus_watch_kit` Python schema
- **Foreground service**: Runs continuously during 60-minute protocol

## Building

```bash
cd daedalus-watch
./gradlew assembleDebug
```

## Installing

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or via Android Studio: Run → Select "daedalus-watch" → Deploy to connected watch.

## Screen

| Control | Action |
|---------|--------|
| ▶ Start Recording | Begin sensor logging, create session folder |
| ⏹ Stop Recording | End session, close files |
| 🔔 Sync Taps | Log sync event (tap watch against chest strap 3×) |
| 👏 Sync Claps | Log sync event (clap 5× for time sync) |
| ⚡ Haptic Test | Send single pulse, measure trigger-to-onset latency |

## Data Output

```
/data/user/0/com.medicina.daedaluswatch/files/data/raw/
└── {subject_id}/
    └── {session_id}/
        ├── watch_imu.csv
        ├── watch_hr.csv
        ├── watch_haptics.csv
        ├── phone_events.csv
        └── session_metadata.json
```

## Architecture

```
sensor/
├── SensorManager.kt        # Health Services API (HR/HRV)
├── SensorCollectionService.kt  # Foreground service, orchestrates everything
haptics/
├── HapticEngine.kt         # Vibration patterns + latency measurement
data/
├── SessionDataLogger.kt    # CSV writers, schema-matching output
ui/
├── MainActivity.kt         # Minimal Phase 0 UI
```
