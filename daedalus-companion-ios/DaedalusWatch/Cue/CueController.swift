import CoreMotion
import Foundation
import HealthKit
import Observation
import WatchKit

/// Runs one cue at a time:
/// - a workout session, so the cue keeps going with the wrist down and heart rate is live
///   (the workout is discarded at the end: nothing is saved to Apple Health). Permission is
///   asked before the first one; if watchOS ends the session, a new one is started; after a
///   crash the running cue is recovered (WatchAppDelegate.handleActiveWorkoutRecovery);
/// - taps at the chosen rate (Breathe: a tap to breathe in and one to breathe out);
/// - a session CSV with heart rate and wrist motion, sent to the iPhone when the cue stops.
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
    /// True when no workout could be started (permission not given): the cue then stops when
    /// the wrist goes down, and the cue screens say so.
    private(set) var workoutUnavailable = false

    @ObservationIgnored private let health = HKHealthStore()
    @ObservationIgnored private var session: HKWorkoutSession?
    @ObservationIgnored private var builder: HKLiveWorkoutBuilder?
    @ObservationIgnored private var cueTask: Task<Void, Never>?
    @ObservationIgnored private let recorder = SessionRecorder()
    /// Workouts restarted after watchOS ended one during this cue (capped, so it can't loop).
    @ObservationIgnored private var workoutRestarts = 0
    /// The running cue, kept so it can be picked up again after a crash.
    private static let activeCueKey = "active_cue"

    var isRunning: Bool { mode != nil }

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
        // The same start can arrive twice (message and launch); keep the cue that's running.
        if self.mode == mode { PhoneLink.shared.sendStatus(); return }
        if isRunning { stop() }
        self.mode = mode
        self.rate = rate ?? savedRate(mode)
        paused = false
        heartRate = nil
        workoutUnavailable = false
        workoutRestarts = 0
        UserDefaults.standard.set(["mode": mode, "rate": self.rate], forKey: Self.activeCueKey)
        recorder.start(mode: mode)
        startWorkout(walking: mode == "walking")
        startCue()
        PhoneLink.shared.sendStatus()
    }

    /// After a crash or relaunch during a cue: carries on with the recovered workout session.
    func recover(_ session: HKWorkoutSession) {
        guard !isRunning,
              let saved = UserDefaults.standard.dictionary(forKey: Self.activeCueKey),
              let mode = saved["mode"] as? String
        else { session.end(); return }
        self.mode = mode
        rate = saved["rate"] as? Int ?? savedRate(mode)
        paused = false
        workoutRestarts = 0
        recorder.start(mode: mode)
        attach(session)
        startCue()
        PhoneLink.shared.sendStatus()
    }

    func stop() {
        guard let mode else { return }
        cueTask?.cancel(); cueTask = nil
        endWorkout()
        if let file = recorder.stop() { PhoneLink.shared.send(file: file) }
        UserDefaults.standard.removeObject(forKey: Self.activeCueKey)
        self.mode = nil
        paused = false
        _ = mode
        WKInterfaceDevice.current().play(.stop)
        PhoneLink.shared.sendStatus()
    }

    func togglePause() {
        paused.toggle()
        if paused { cueTask?.cancel(); cueTask = nil } else { startCue() }
    }

    // MARK: Cue rhythm

    private func startCue() {
        cueTask?.cancel()
        guard let mode else { return }
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
                    // Schedule from the start time so the rhythm doesn't drift. After a stall (the
                    // app was suspended), carry on from now instead of playing the missed taps.
                    next = next.advanced(by: .seconds(interval))
                    if next < ContinuousClock.now { next = ContinuousClock.now }
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
        let mode = self.mode
        Task {
            // Ask first (once): without permission there's no workout, and the cue would stop
            // when the wrist goes down.
            if health.authorizationStatus(for: .workoutType()) == .notDetermined { await requestAuthorization() }
            guard self.mode == mode, session == nil else { return }
            guard health.authorizationStatus(for: .workoutType()) == .sharingAuthorized else {
                workoutUnavailable = true
                return
            }
            let config = HKWorkoutConfiguration()
            config.activityType = walking ? .walking : .mindAndBody
            config.locationType = walking ? .outdoor : .indoor
            do {
                let session = try HKWorkoutSession(healthStore: health, configuration: config)
                attach(session)
                let start = Date.now
                session.startActivity(with: start)
                try await builder?.beginCollection(at: start)
            } catch {
                // Without a workout the cue still plays while the screen is on.
                print("Workout session failed: \(error)")
                workoutUnavailable = true
            }
        }
    }

    private func attach(_ session: HKWorkoutSession) {
        let builder = session.associatedWorkoutBuilder()
        builder.dataSource = HKLiveWorkoutDataSource(healthStore: health, workoutConfiguration: session.workoutConfiguration)
        builder.delegate = self
        session.delegate = self
        self.session = session
        self.builder = builder
        workoutUnavailable = false
    }

    /// watchOS ended or failed our workout while the cue was still on: start a new one.
    fileprivate func workoutEnded(_ ended: HKWorkoutSession) {
        guard ended === session, let mode else { return }
        builder?.discardWorkout()
        session = nil
        builder = nil
        guard workoutRestarts < 3 else { workoutUnavailable = true; return }
        workoutRestarts += 1
        startWorkout(walking: mode == "walking")
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

extension CueController: HKWorkoutSessionDelegate {
    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
                                    from fromState: HKWorkoutSessionState, date: Date) {
        guard toState == .ended || toState == .stopped else { return }
        Task { @MainActor in CueController.shared.workoutEnded(workoutSession) }
    }

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        print("Workout session failed: \(error)")
        Task { @MainActor in CueController.shared.workoutEnded(workoutSession) }
    }
}

/// Writes the session CSV: ~10 rows a second with the latest heart rate and wrist motion
/// (acceleration in m/s² including gravity, rotation in rad/s — the Android watch's units).
/// Motion arrives on one serial queue, so rows are never interleaved; heart rate is set from
/// the main actor, so it's behind a lock. A failed write (disk full) stops writing rows
/// instead of crashing.
final class SessionRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _heartRate: Int?
    var heartRate: Int? {
        get { lock.withLock { _heartRate } }
        set { lock.withLock { _heartRate = newValue } }
    }
    private let motion = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1
        q.name = "SessionRecorder"
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

    func start(mode: String) {
        let url = Self.sessionsDir.appendingPathComponent(CueDefaults.sessionFileName(mode: mode, start: .now))
        // Encrypted until the watch is first unlocked; still writable with the wrist down.
        FileManager.default.createFile(atPath: url.path, contents: Data(CueDefaults.csvHeader.utf8),
                                       attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        self.url = url
        handle = try? FileHandle(forWritingTo: url)
        _ = try? handle?.seekToEnd()
        heartRate = nil
        lastWrite = .distantPast

        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 50
        motion.startDeviceMotionUpdates(to: queue) { [weak self] data, _ in
            guard let self, let data else { return }
            let now = Date.now
            guard now.timeIntervalSince(self.lastWrite) >= 0.1 else { return }
            self.lastWrite = now
            let g = 9.80665
            let a = data.userAcceleration, gr = data.gravity, r = data.rotationRate
            let row = String(
                format: "%lld,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,,\n",
                Int64(now.timeIntervalSince1970 * 1000), self.heartRate ?? 0,
                (a.x + gr.x) * g, (a.y + gr.y) * g, (a.z + gr.z) * g, r.x, r.y, r.z
            )
            do {
                try self.handle?.write(contentsOf: Data(row.utf8))
            } catch {
                print("Session file write failed, recording stops: \(error)")
                try? self.handle?.close()
                self.handle = nil
            }
        }
    }

    /// Stops recording; returns the finished file.
    func stop() -> URL? {
        motion.stopDeviceMotionUpdates()
        queue.waitUntilAllOperationsAreFinished()
        try? handle?.close()
        handle = nil
        defer { url = nil }
        return url
    }
}
