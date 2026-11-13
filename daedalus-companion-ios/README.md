# Daedalus for iPhone and Apple Watch

SwiftUI apps with the same design, wording (EN/FR) and Supabase backend as the Android apps.

- **Daedalus** (iPhone, iOS 17+): sign-in, first sign-in setup, Today (vitals from Apple Health),
  Cues, Journal, Meds with dose reminders, Profile, and Your data (backup and deletion).
- **DaedalusWatch** (Apple Watch, watchOS 10+, ships inside the iPhone app): "I'm stuck",
  walking, breathing, fine-motor and calm cues. Each cue runs as a workout session (keeps going
  with the wrist down, live heart rate; nothing is saved to Apple Health) and records a session
  CSV in the Android watch's format, sent to the iPhone and backed up.

## Build

1. Install XcodeGen (`brew install xcodegen`), then generate the project:

   ```bash
   cd daedalus-companion-ios && xcodegen
   ```

2. Sign-in needs `../daedalus-companion/supabase.properties` (shared with Android, not
   committed). A build step writes it into `Daedalus/App/SupabaseConfig.generated.swift`
   (git-ignored). Without it the app builds and sign-in shows as not set up.
3. Open `Daedalus.xcodeproj`, pick your team under Signing & Capabilities for both targets,
   and run the **Daedalus** scheme. The watch app installs with it.

## Strings

`Localizable.xcstrings` files are generated from the Android `strings.xml` files plus
`tools/ios_strings.json` and `tools/watch_strings.json`:

```bash
python3 tools/make_strings.py
```

## Home pilot (study mode)

Off by default. Profile → Home pilot on the iPhone turns it on and sets the pilot code, seed,
start date and pocket reminder times; the watch gets them over WatchConnectivity. Then each
"I'm stuck" press on the watch plays its beat on the wrist or, through the iPhone, in earbuds
(`Shared/Pilot.swift`, a port of `daedalus_watch_kit/daedalus_watch_kit/pilot.py`), asks the
one-tap check-in, and writes one press file (`Shared/PressFile.swift`) that goes to the iPhone's
`Documents/pilot` folder. Press files are never backed up; "Export pilot files" shares them.
The pocket beat isn't offered on iPhone (Core Haptics doesn't play in the background).
See `HANDOFF-ios.md` at the repository root for what was checked and what needs real devices.

## Testing notes

- Shared tests (signal code, golden vectors, pilot rules) on the Mac:
  `xcodebuild test -project Daedalus.xcodeproj -scheme SharedTests -destination 'platform=macOS'`.
  With `TEST_RUNNER_PILOT_PRESS_OUT=<folder>` it also writes a sample press file there for
  `pilot_analysis.load_press` and `press_timing` to read.

- Haptics, heart rate and wrist motion only work on a real Apple Watch.
- Session files go from watch to iPhone with WatchConnectivity file transfers, which don't
  arrive between simulators; test that on real devices.
