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

    @ObservationIgnored private let health = HKHealthStore()
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var builder: HKLiveWorkoutBuilder?
    @ObservationIgnored private var cueTask: Task<Void, Never>?
    @ObservationIgnored private var timeoutTask: Task<Void, Never>?
    @ObservationIgnored private var resultTask: Task<Void, Never>?

    /// A session the person forgets to stop ends on its own after this long.
    static let maxSessionDuration: Duration = .seconds(60 * 60)
    /// How long a tremor check's result stays on screen without Done.
    static let resultDuration: Duration = .seconds(2 * 60)
    @ObservationIgnored private let recorder = SessionRecorder()

    var isRunning: Bool { mode != nil }
    var isTremorCheck: Bool { mode == TremorAnalyzer.mode }

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

    func start(mode: String, rate: Int? = nil) {
        if isRunning { stop() }
        self.mode = mode
        self.rate = rate ?? savedRate(mode)
        paused = false
        heartRate = nil
        clearTremorResult()
        let tremor = mode == TremorAnalyzer.mode
        tremorEndsAt = tremor ? Date.now.addingTimeInterval(TremorAnalyzer.checkDuration) : nil
        recorder.start(mode: mode, everySample: tremor)
        startWorkout(walking: mode == "walking")
        if tremor { WKInterfaceDevice.current().play(.start) }
        startCue()
        startTimeout()
        PhoneLink.shared.sendStatus()
    }

    func stop() {
        guard let mode else { return }
        timeoutTask?.cancel(); timeoutTask = nil
        cueTask?.cancel(); cueTask = nil
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

    func togglePause() {
        paused.toggle()
        if paused { cueTask?.cancel(); cueTask = nil } else { startCue() }
    }

    /// Stops the session after `maxSessionDuration`, or a tremor check after `checkDuration`
    /// (the workout keeps the app running meanwhile).
    private func startTimeout() {
        timeoutTask?.cancel()
        let limit: Duration = isTremorCheck ? .seconds(TremorAnalyzer.checkDuration) : Self.maxSessionDuration
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
        guard let mode, mode != TremorAnalyzer.mode else { return }
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
                    device.play(haptic)
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
/// time, and keeps the rotation in memory for `TremorAnalyzer`.
final class SessionRecorder {
    /// Rotation samples kept for the tremor check (times in wall-clock ms, rad/s).
    struct Motion { var timesMs: [Int64] = [], gx: [Double] = [], gy: [Double] = [], gz: [Double] = [] }

    var heartRate: Int?
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

    static var sessionsDir: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func start(mode: String, everySample: Bool = false) {
        let url = Self.sessionsDir.appendingPathComponent(CueDefaults.sessionFileName(mode: mode, start: .now))
        FileManager.default.createFile(atPath: url.path, contents: Data(CueDefaults.csvHeader.utf8))
        self.url = url
        handle = try? FileHandle(forWritingTo: url)
        handle?.seekToEndOfFile()
        heartRate = nil
        lastWrite = .distantPast
        motion = Motion()

        guard motionManager.isDeviceMotionAvailable else { return }
        motionManager.deviceMotionUpdateInterval = 1.0 / 50
        motionManager.startDeviceMotionUpdates(to: queue) { [weak self] data, _ in
            guard let self, let data else { return }
            let r = data.rotationRate
            let timeMs: Int64
            if everySample {
                // The sample's own time (seconds since boot) as wall-clock ms.
                let at = Date.now.timeIntervalSince1970 - (ProcessInfo.processInfo.systemUptime - data.timestamp)
                timeMs = Int64((at * 1000).rounded())
                self.motion.timesMs.append(timeMs)
                self.motion.gx.append(r.x); self.motion.gy.append(r.y); self.motion.gz.append(r.z)
            } else {
                let now = Date.now
                guard now.timeIntervalSince(self.lastWrite) >= 0.1 else { return }
                self.lastWrite = now
                timeMs = Int64(now.timeIntervalSince1970 * 1000)
            }
            let g = 9.80665
            let a = data.userAcceleration, gr = data.gravity
            let row = String(
                format: "%lld,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,,\n",
                timeMs, self.heartRate ?? 0,
                (a.x + gr.x) * g, (a.y + gr.y) * g, (a.z + gr.z) * g, r.x, r.y, r.z
            )
            self.handle?.write(Data(row.utf8))
        }
    }

    /// Stops recording; returns the finished file.
    func stop() -> URL? {
        motionManager.stopDeviceMotionUpdates()
        queue.waitUntilAllOperationsAreFinished()
        try? handle?.close()
        handle = nil
        defer { url = nil }
        return url
    }
}
