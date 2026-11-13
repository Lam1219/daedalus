import CoreMotion
import Foundation
import HealthKit
import Observation
import WatchKit

/// Runs one cue at a time:
/// - a workout session, so the cue keeps going with the wrist down and heart rate is live
///   (the workout is discarded at the end: nothing is saved to Apple Health);
/// - taps at the chosen rate (Breathe: a tap to breathe in and one to breathe out);
/// - a session CSV with heart rate and wrist motion, sent to the iPhone when the cue stops.
///
/// The tremor check ("tremor") runs the same way but plays no rhythm: a tap at the start and
/// one at the end, every motion sample recorded, and it stops on its own after
/// `TremorAnalyzer.checkDuration`, then shows the result until Done.
///
/// A guard ("walk_guard", "rest_guard"; automatic cues in shadow mode, see `GuardRows`) runs the
/// same way with no rhythm: a tap at the start, every motion sample recorded and pushed through
/// `GuardEngine`, whose events go into the session CSV, plus a battery reading every 5 minutes.
/// "I'm stuck" during a guard (`start(mode: "walking")`, from the watch or the iPhone) starts the
/// walking rhythm inside the guard, which keeps recording; `stopGuardCue()` stops just that rhythm,
/// `stop()` the whole guard.
@MainActor
@Observable
final class CueController: NSObject {
    static let shared = CueController()

    enum BreathPhase { case inhale, exhale }

    private(set) var mode: String?
    private(set) var rate = 80
    private(set) var paused = false
    private(set) var heartRate: Int?
    private(set) var breathPhase: BreathPhase = .inhale
    /// Increments on every pulse (drives the walking ring animation).
    private(set) var beat = 0
    /// The latest tremor check's result (cleared by Done, a new cue, or after `resultDuration`).
    private(set) var lastTremorResult: TremorAnalyzer.Result?
    private(set) var lastTremorAt: Date?
    /// When the running tremor check stops on its own.
    private(set) var tremorEndsAt: Date?
    /// During a guard: "I'm stuck" started the walking rhythm inside it.
    private(set) var guardCueActive = false
    /// Home pilot: this walking cue is a study press (`PilotController`).
    private(set) var studyPress = false
    /// Home pilot: the beat plays on the iPhone (earbuds), so the wrist stays silent.
    @ObservationIgnored private var wristSilent = false

    @ObservationIgnored private let health = HKHealthStore()
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var builder: HKLiveWorkoutBuilder?
    @ObservationIgnored private var cueTask: Task<Void, Never>?
    @ObservationIgnored private var timeoutTask: Task<Void, Never>?
    @ObservationIgnored private var resultTask: Task<Void, Never>?
    @ObservationIgnored private var batteryTask: Task<Void, Never>?
    /// Whether this guard turned battery monitoring on (and so turns it off again).
    @ObservationIgnored private var enabledBatteryMonitoring = false

    /// A session the person forgets to stop ends on its own after this long.
    static let maxSessionDuration: Duration = .seconds(60 * 60)
    /// How long a tremor check's result stays on screen without Done.
    static let resultDuration: Duration = .seconds(2 * 60)
    @ObservationIgnored private let recorder = SessionRecorder()

    var isRunning: Bool { mode != nil }
    var isTremorCheck: Bool { mode == TremorAnalyzer.mode }
    var isGuard: Bool { GuardRows.isGuard(mode) }
    /// The running guard's engine mode, or nil when no guard runs.
    var guardMode: GuardEngine.Mode? { mode.flatMap { GuardRows.isGuard($0) ? GuardRows.engineMode($0) : nil } }

    /// Rates set on the iPhone (Cues screen); "I'm stuck" starts at the person's own rhythm.
    func savedRate(_ mode: String) -> Int {
        (UserDefaults.standard.dictionary(forKey: WatchKey.rates) as? [String: Int])?[mode] ?? CueDefaults.rates[mode] ?? 80
    }

    func requestAuthorization() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        try? await health.requestAuthorization(
            toShare: [HKObjectType.workoutType()],
            read: [HKQuantityType(.heartRate), HKQuantityType(.respiratoryRate)]
        )
    }

    // MARK: Start / stop

    /// `press`: "I'm stuck" pressed on the watch. In study mode (home pilot) that press draws the
    /// place its beat plays in; otherwise it is the same as any start.
    func start(mode: String, rate: Int? = nil, press: Bool = false) {
        // "I'm stuck" during a guard: the walking rhythm starts inside the guard, which keeps recording.
        if isGuard && mode == "walking" {
            startGuardCue(rate: rate ?? savedRate(mode))
            return
        }
        if isRunning { stop() }
        self.mode = mode
        self.rate = rate ?? savedRate(mode)
        paused = false
        heartRate = nil
        guardCueActive = false
        clearTremorResult()
        let tremor = mode == TremorAnalyzer.mode
        let guarding = GuardRows.isGuard(mode)
        tremorEndsAt = tremor ? Date.now.addingTimeInterval(TremorAnalyzer.checkDuration) : nil
        let pilot = PilotController.shared
        studyPress = press && mode == "walking" && pilot.isStudy
        wristSilent = false
        if studyPress {
            let ring = pilot.live
            recorder.onSample = { ring.append($0) }
            wristSilent = !pilot.beginPress(pace: self.rate)
        } else {
            recorder.onSample = nil
        }
        recorder.start(mode: mode, everySample: tremor || guarding,
                       guardMode: guarding ? GuardRows.engineMode(mode) : nil)
        startWorkout(walking: mode == "walking" || mode == GuardRows.walkGuard)
        // No rhythm in the tremor check or a guard: one tap to say it started.
        if tremor || guarding { WKInterfaceDevice.current().play(.start) }
        if guarding { startBatteryLog() }
        startCue()
        startTimeout()
        PhoneLink.shared.sendStatus()
    }

    func stop() {
        guard let mode else { return }
        timeoutTask?.cancel(); timeoutTask = nil
        cueTask?.cancel(); cueTask = nil
        if GuardRows.isGuard(mode) {
            // Before the file is closed: the end of an "I'm stuck" rhythm, the last battery reading,
            // then (in recorder.stop) an open tremor episode's end and GUARD_END.
            if guardCueActive { recorder.write(GuardRows.stuckCueEnd(tMs: Self.nowMs())) }
            guardCueActive = false
            stopBatteryLog()
        }
        endWorkout()
        let file = recorder.stop()
        let tremor = mode == TremorAnalyzer.mode
        if tremor {
            let m = recorder.motion
            lastTremorResult = TremorAnalyzer.analyze(timesMs: m.timesMs, gx: m.gx, gy: m.gy, gz: m.gz)
            lastTremorAt = .now
            tremorEndsAt = nil
            resultTask = Task { [weak self] in
                try? await Task.sleep(for: Self.resultDuration)
                guard !Task.isCancelled else { return }
                self?.clearTremorResult()
            }
        }
        if let file { PhoneLink.shared.send(file: file) }
        if studyPress {
            studyPress = false
            wristSilent = false
            recorder.onSample = nil
            PilotController.shared.endPress()
        }
        self.mode = nil
        paused = false
        WKInterfaceDevice.current().play(tremor ? .success : .stop)
        PhoneLink.shared.sendStatus()
    }

    /// Done on the result screen (also when a new cue starts).
    func clearTremorResult() {
        resultTask?.cancel(); resultTask = nil
        lastTremorResult = nil
        lastTremorAt = nil
    }

    /// During a guard: stops only the "I'm stuck" walking rhythm; the guard keeps recording.
    func stopGuardCue() {
        guard isGuard, guardCueActive else { return }
        guardCueActive = false
        cueTask?.cancel(); cueTask = nil
        paused = false
        recorder.write(GuardRows.stuckCueEnd(tMs: Self.nowMs()))
        WKInterfaceDevice.current().play(.stop)
    }

    /// "I'm stuck" during a guard: the walking rhythm at the person's rate, inside the guard.
    private func startGuardCue(rate: Int) {
        guard isGuard, !guardCueActive else { return }
        self.rate = rate
        paused = false
        guardCueActive = true
        recorder.write(GuardRows.stuckCueStart(tMs: Self.nowMs(), bpm: rate))
        startCue()
    }

    private static func nowMs() -> Int64 { Int64(Date.now.timeIntervalSince1970 * 1000) }

    /// Home pilot: the iPhone couldn't play (or keep) the sound beat, so the wrist plays at once.
    func fallBackToWrist() {
        guard studyPress, wristSilent, mode == "walking" else { return }
        wristSilent = false
        if !paused { startCue() }
    }

    // MARK: Guard battery log

    /// GUARD_BATTERY at the start and every `GuardRows.batteryInterval` (the last one is in `stop`).
    private func startBatteryLog() {
        let device = WKInterfaceDevice.current()
        enabledBatteryMonitoring = !device.isBatteryMonitoringEnabled
        device.isBatteryMonitoringEnabled = true
        logBattery()
        batteryTask?.cancel()
        batteryTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: GuardRows.batteryInterval)
                guard !Task.isCancelled else { return }
                self?.logBattery()
            }
        }
    }

    private func stopBatteryLog() {
        batteryTask?.cancel(); batteryTask = nil
        logBattery()
        if enabledBatteryMonitoring { WKInterfaceDevice.current().isBatteryMonitoringEnabled = false }
        enabledBatteryMonitoring = false
    }

    private func logBattery() {
        let device = WKInterfaceDevice.current()
        let charging = device.batteryState == .charging || device.batteryState == .full
        recorder.write(GuardRows.battery(tMs: Self.nowMs(), level: device.batteryLevel, charging: charging))
    }

    func togglePause() {
        // A guard has no rhythm of its own to pause (the "I'm stuck" rhythm stops with stopGuardCue).
        guard !isGuard else { return }
        paused.toggle()
        if paused { cueTask?.cancel(); cueTask = nil } else { startCue() }
    }

    /// Stops the session after `maxSessionDuration`, or a tremor check after `checkDuration`
    /// (the workout keeps the app running meanwhile).
    private func startTimeout() {
        timeoutTask?.cancel()
        // Home pilot (B6): in study mode a walking beat stops by itself after 10 minutes.
        let study = mode == "walking" && PilotController.shared.isStudy
        let limit: Duration = isTremorCheck ? .seconds(TremorAnalyzer.checkDuration)
            : study ? .seconds(Pilot.studyMaxCueS) : Self.maxSessionDuration
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: limit)
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    // MARK: Cue rhythm

    private func startCue() {
        cueTask?.cancel()
        // The tremor check plays no rhythm: taps would shake the wrist it measures.
        // A guard plays one only while "I'm stuck" started it: the walking rhythm.
        guard var mode, mode != TremorAnalyzer.mode else { return }
        if GuardRows.isGuard(mode) {
            guard guardCueActive else { return }
            mode = "walking"
        }
        let device = WKInterfaceDevice.current()
        if mode == "rest" {
            // Breathing pacer: breathe in for 40% of each breath, out for 60% (6/min = in 4 s · out 6 s).
            let cycle = 60.0 / Double(max(rate, 1))
            cueTask = Task { [weak self] in
                while !Task.isCancelled {
                    self?.breathPhase = .inhale
                    device.play(.directionUp)
                    try? await Task.sleep(for: .seconds(cycle * 0.4))
                    if Task.isCancelled { break }
                    self?.breathPhase = .exhale
                    device.play(.directionDown)
                    try? await Task.sleep(for: .seconds(cycle * 0.6))
                }
            }
        } else {
            let interval = 60.0 / Double(max(rate, 1))
            // Walking: a crisp tap per step. Fine motor and calm: a softer, steady pulse.
            let haptic: WKHapticType = mode == "walking" ? .click : .directionUp
            cueTask = Task { [weak self] in
                var next = ContinuousClock.now
                while !Task.isCancelled {
                    if let self, self.studyPress {
                        // Home pilot: silent while the iPhone plays it; the first wrist pulse is the beat start.
                        if !self.wristSilent {
                            PilotController.shared.wristBeatPlayed(atMs: Self.nowMs())
                            device.play(haptic)
                        }
                    } else {
                        device.play(haptic)
                    }
                    self?.beat += 1
                    // Schedule from the start time so the rhythm doesn't drift.
                    next = next.advanced(by: .seconds(interval))
                    try? await Task.sleep(until: next, clock: .continuous)
                }
            }
        }
    }

    var breathRhythm: (inhale: Int, exhale: Int) {
        let cycle = 60.0 / Double(max(rate, 1))
        return (Int((cycle * 0.4).rounded()), Int((cycle * 0.6).rounded()))
    }

    // MARK: Workout session

    private func startWorkout(walking: Bool) {
        let config = HKWorkoutConfiguration()
        config.activityType = walking ? .walking : .mindAndBody
        config.locationType = walking ? .outdoor : .indoor
        do {
            let session = try HKWorkoutSession(healthStore: health, configuration: config)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: health, workoutConfiguration: config)
            builder.delegate = self
            self.session = session
            self.builder = builder
            let start = Date.now
            session.startActivity(with: start)
            builder.beginCollection(withStart: start) { _, _ in }
        } catch {
            // Without a workout the cue still plays while the screen is on.
            print("Workout session failed: \(error)")
        }
    }

    private func endWorkout() {
        guard let session, let builder else { return }
        session.end()
        builder.endCollection(withEnd: .now) { _, _ in
            // Daedalus doesn't write to Apple Health.
            builder.discardWorkout()
        }
        self.session = nil
        self.builder = nil
    }

    fileprivate func updateHeartRate(_ bpm: Int) {
        heartRate = bpm
        recorder.heartRate = bpm
    }
}

extension CueController: HKLiveWorkoutBuilderDelegate {
    nonisolated func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        let type = HKQuantityType(.heartRate)
        guard collectedTypes.contains(type),
              let value = workoutBuilder.statistics(for: type)?.mostRecentQuantity()?.doubleValue(for: .count().unitDivided(by: .minute()))
        else { return }
        Task { @MainActor in CueController.shared.updateHeartRate(Int(value.rounded())) }
    }

    nonisolated func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
}

/// Writes the session CSV: ~10 rows a second with the latest heart rate and wrist motion
/// (acceleration in m/s² including gravity, rotation in rad/s — the Android watch's units).
/// For the tremor check it writes every sample (50 a second), stamped with the sample's own
/// time, and keeps the rotation in memory for `TremorAnalyzer`. A guard writes every sample the
/// same way, pushes it through `GuardEngine` and writes the engine's events after it
/// (`GuardRows`); the engine lives on the motion queue, so samples and events stay in order.
final class SessionRecorder {
    /// Rotation samples kept for the tremor check (times in wall-clock ms, rad/s).
    struct Motion { var timesMs: [Int64] = [], gx: [Double] = [], gy: [Double] = [], gz: [Double] = [] }

    var heartRate: Int?
    /// Home pilot: every motion sample's acceleration (m/s², wall-clock ms), on the motion queue.
    var onSample: ((PressSamples.Sample) -> Void)?
    /// Filled while recording every sample; read after `stop()`.
    private(set) var motion = Motion()
    private let motionManager = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        // One sample at a time: the handler appends to `motion` and writes to the file.
        q.maxConcurrentOperationCount = 1
        return q
    }()
    private var handle: FileHandle?
    private var url: URL?
    private var lastWrite = Date.distantPast
    /// The running guard's engine (touched only on `queue`, or after the queue has drained).
    private var guardEngine: GuardEngine?

    static var sessionsDir: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// `guardMode` makes this a guard session: the guard header, and every sample through the engine
    /// (with `everySample`).
    func start(mode: String, everySample: Bool = false, guardMode: GuardEngine.Mode? = nil) {
        let url = Self.sessionsDir.appendingPathComponent(CueDefaults.sessionFileName(mode: mode, start: .now))
        let guarding = guardMode != nil
        FileManager.default.createFile(atPath: url.path, contents: Data((guarding ? GuardRows.header : CueDefaults.csvHeader).utf8))
        self.url = url
        handle = try? FileHandle(forWritingTo: url)
        handle?.seekToEndOfFile()
        heartRate = nil
        lastWrite = .distantPast
        motion = Motion()
        guardEngine = guardMode.map { GuardEngine(mode: $0) }
        if var engine = guardEngine {
            // Motion updates haven't started yet: nothing else touches the engine or the file.
            write(rows: engine.begin(tMs: Int64(Date.now.timeIntervalSince1970 * 1000)))
            guardEngine = engine
        }

        guard motionManager.isDeviceMotionAvailable else { return }
        motionManager.deviceMotionUpdateInterval = 1.0 / 50
        let tap = onSample
        motionManager.startDeviceMotionUpdates(to: queue) { [weak self] data, _ in
            guard let self, let data else { return }
            if let tap {
                let at = Date.now.timeIntervalSince1970 - (ProcessInfo.processInfo.systemUptime - data.timestamp)
                let a = data.userAcceleration, gr = data.gravity, g = 9.80665
                tap(PressSamples.Sample(tMs: Int64((at * 1000).rounded()),
                                        ax: (a.x + gr.x) * g, ay: (a.y + gr.y) * g, az: (a.z + gr.z) * g))
            }
            let r = data.rotationRate
            let timeMs: Int64
            if everySample {
                // The sample's own time (seconds since boot) as wall-clock ms.
                let at = Date.now.timeIntervalSince1970 - (ProcessInfo.processInfo.systemUptime - data.timestamp)
                timeMs = Int64((at * 1000).rounded())
                if !guarding {
                    // Kept for TremorAnalyzer (the tremor check only; a guard can last an hour).
                    self.motion.timesMs.append(timeMs)
                    self.motion.gx.append(r.x); self.motion.gy.append(r.y); self.motion.gz.append(r.z)
                }
            } else {
                let now = Date.now
                guard now.timeIntervalSince(self.lastWrite) >= 0.1 else { return }
                self.lastWrite = now
                timeMs = Int64(now.timeIntervalSince1970 * 1000)
            }
            let g = 9.80665
            let a = data.userAcceleration, gr = data.gravity
            let ax = (a.x + gr.x) * g, ay = (a.y + gr.y) * g, az = (a.z + gr.z) * g
            let row = GuardRows.sampleRow(tMs: timeMs, heartRate: self.heartRate, ax: ax, ay: ay, az: az,
                                          gx: r.x, gy: r.y, gz: r.z, guardSession: guarding)
            self.handle?.write(Data(row.utf8))
            if var engine = self.guardEngine {
                self.guardEngine = nil   // keep the engine uniquely referenced while it mutates
                let events = engine.push(tMs: Double(timeMs), ax: ax, ay: ay, az: az, gx: r.x, gy: r.y, gz: r.z)
                self.guardEngine = engine
                self.write(rows: events)
            }
        }
    }

    /// An event row in a guard session, in order with the samples (on the motion queue).
    func write(_ event: GuardEvent) {
        queue.addOperation { [weak self] in
            guard let self, self.guardEngine != nil else { return }
            self.write(rows: [event])
        }
    }

    private func write(rows: [GuardEvent]) {
        for e in rows { handle?.write(Data(GuardRows.eventRow(e).utf8)) }
    }

    /// Stops recording; returns the finished file. A guard writes the engine's last rows
    /// (an open tremor episode's end, GUARD_END) before the file is closed.
    func stop() -> URL? {
        motionManager.stopDeviceMotionUpdates()
        queue.waitUntilAllOperationsAreFinished()
        if var engine = guardEngine {
            guardEngine = nil
            write(rows: engine.finish(tMs: Int64(Date.now.timeIntervalSince1970 * 1000)))
        }
        try? handle?.close()
        handle = nil
        defer { url = nil }
        return url
    }
}
