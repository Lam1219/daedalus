# Daedalus Companion — iOS App

iPhone companion app for the **Daedalus** Parkinson's research POC.
Connects to the Samsung Galaxy Watch5 Pro via BLE and the Cycplus H2 Heart Rate chest strap.

## Architecture

Since the Samsung Galaxy Watch5 Pro runs Wear OS (which cannot pair with iPhones via standard Wear OS APIs), the iOS app uses a **dual BLE** approach:

1. **BLE Peripheral** — The iPhone advertises as a BLE peripheral. The watch (modified to act as BLE Central) connects and sends IMU + HR data.
2. **BLE Central** — The iPhone connects to the Cycplus H2 chest strap to receive RR intervals.

```
Samsung Watch5 Pro ──BLE (custom service)──┐
                                           ├──→ Daedalus Companion (iPhone)
Cycplus H2 ──BLE (Heart Rate Service)──┘   ├──→ Session files (CSV + JSON)
                                           └──→ Share/Export → laptop → daedalus_watch_kit
```

## Building

1. Open `DaedalusCompanion.xcodeproj` in Xcode 15+
2. Select your iPhone as the target device
3. Build & Run (⌘R)

## Requirements

- **iOS 16.0+** (for SwiftUI `.buttonStyle(.bordered)` and modern BLE APIs)
- **iPhone with Bluetooth** (any iPhone 8 or later)
- **Samsung Galaxy Watch5 Pro** with custom BLE Central code (see watch app README)
- **Cycplus H2 Heart Rate** chest strap

## Features (Phase 0)

- **BLE Peripheral**: Advertises custom Daedalus service for watch connection
- **BLE Central**: Connects to Cycplus H2, parses RR intervals from Heart Rate Service
- **Session Management**: Start/stop, event logging, CSV output
- **Live Data Display**: HR from chest strap + watch, connection status
- **Event Markers**: Rest, Stress Task, Sync (Claps)
- **Export**: Zip session folder matching `daedalus_watch_kit` schema, share via iOS share sheet

## French Localization

The app automatically displays in French when the iPhone's system language is set to French. All UI strings are defined in:

- `Resources/en.lproj/Localizable.strings` — English (base)
- `Resources/fr.lproj/Localizable.strings` — French

## Data Output

```
daedalus_sub-001_sess-001_2026-01-01T12-00-00/
├── session_metadata.json
├── watch_imu.csv          ← from Samsung Watch5 Pro via BLE
├── watch_hr.csv           ← from Samsung Watch5 Pro via BLE
├── phone_events.csv       ← events logged on iPhone
└── ecg/
    └── ecg_reference.csv  ← from Cycplus H2 via BLE
```

## BLE Service UUIDs

The custom Daedalus service uses these UUIDs (to be matched on the watch side):

| Characteristic | UUID | Purpose |
|---------------|------|---------|
| Service | `A0000000-0000-0000-0000-000000000001` | Daedalus custom service |
| IMU | `A0000001-0000-0000-0000-000000000001` | Watch IMU data (write) |
| HR | `A0000002-0000-0000-0000-000000000001` | Watch HR data (write) |
| Haptics | `A0000003-0000-0000-0000-000000000001` | Haptic events (write) |
| Events | `A0000004-0000-0000-0000-000000000001` | Event labels (write) |
| Control | `A0000005-0000-0000-0000-000000000001` | Session control (write) |

The watch must be modified to act as a BLE Central and connect to this peripheral service.

## Limitations

- **No Wear OS DataLayer**: iPhones cannot use Wear OS APIs. The watch must implement BLE Central to communicate with this app.
- **One-way data flow**: Watch → iPhone only. Commands from iPhone → watch would require the watch to also expose a BLE peripheral service.
- **Background limitations**: iOS restricts BLE background operations. The app must stay in the foreground during data collection.
