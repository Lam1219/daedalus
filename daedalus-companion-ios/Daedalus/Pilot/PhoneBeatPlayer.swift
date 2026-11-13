import AVFoundation
import Foundation

/// Home pilot B1: the sound beat, played by the iPhone to the earbuds paired with it, started and
/// stopped by the watch. One period of audio (a short click, then silence) loops on the audio
/// clock, so the pace doesn't drift (`BeatClock`).
///
/// The session is `.playback` with `.mixWithOthers`: a mixable session may be activated while the
/// app is in the background (the watch's message wakes it), and the `audio` background mode keeps
/// it playing with the screen locked. It plays only to earbuds or headphones: if they go away
/// mid-beat it stops at once (never out of the phone's speaker) and the watch takes over.
///
/// Round 001: the beat belongs to one press (21) and only that press's stop, pause or resume
/// reaches it; it stops by itself after the study limit (17); and the start it reports is when the
/// first click actually rendered, plus the output latency (22).
@MainActor
final class PhoneBeatPlayer {
    static let shared = PhoneBeatPlayer()

    /// Called with the press when its beat stopped on its own (earbuds removed, a call).
    var onLost: ((Int64) -> Void)?
    private(set) var isPlaying = false
    /// The press the playing beat belongs to.
    private(set) var playingPress: Int64?

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var observers: [NSObjectProtocol] = []
    private var limitTask: Task<Void, Never>?

    /// How long to wait for the first click to render before giving up (the watch falls back to
    /// the wrist after 1 s anyway).
    static let renderTimeout: Duration = .milliseconds(600)

    static let earbudPorts: Set<AVAudioSession.Port> = [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP, .headphones]

    /// A headphone-type route is up. iOS can't tell earbuds from a Bluetooth speaker, so the
    /// pilot also needs the physio's "Earbuds paired" check (8, `Pilot.earbudsAvailable`).
    static var earbudsConnected: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { earbudPorts.contains($0.portType) }
    }

    /// Starts the beat for `press`; returns when the first click reaches the ear (epoch ms, iPhone
    /// clock), measured once it has rendered, or nil when it can't play to earbuds.
    func start(pace: Int, press: Int64) async -> Int64? {
        stop(notify: false)
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            print("Pilot beat: audio session failed: \(error)")
            return nil
        }
        guard Self.earbudsConnected else { deactivate(); return nil }

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        var rate = engine.outputNode.outputFormat(forBus: 0).sampleRate
        if rate <= 0 { rate = 44_100 }
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1),
              let buffer = Self.clickPeriod(format: format, pace: pace) else { deactivate(); return nil }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch {
            print("Pilot beat: engine failed: \(error)")
            deactivate(); return nil
        }
        Self.loop(buffer, on: player)
        player.play()
        self.engine = engine
        self.player = player
        isPlaying = true
        playingPress = press
        observe()

        // 22: wait until the player's frame 0 (the first click) has rendered, and date it from the
        // render timestamp, not from a prediction.
        guard let firstHost = await Self.firstRenderHostSeconds(player, sampleRate: rate),
              playingPress == press else {
            if playingPress == press { stop(notify: false) }
            return nil
        }
        // The render timestamp is when the buffer leaves for the output hardware; the ear hears it
        // after the output latency (large for Bluetooth: often 0.1–0.25 s).
        let firstClickMs = BeatClock.wallMs(hostSeconds: firstHost,
                                            nowHostSeconds: AVAudioTime.seconds(forHostTime: mach_absolute_time()),
                                            nowWallMs: Int64((Date.now.timeIntervalSince1970 * 1000).rounded()),
                                            latencyS: session.outputLatency)
        // 17: stop by itself at the study limit, even if the watch's stop never arrives.
        limitTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Pilot.phoneBeatLimitS))
            guard !Task.isCancelled, let self, self.playingPress == press else { return }
            self.stop(notify: false)
        }
        return firstClickMs
    }

    /// Schedules the looping period (synchronous: the async variant would wait for a loop that never ends).
    private nonisolated static func loop(_ buffer: AVAudioPCMBuffer, on player: AVAudioPlayerNode) {
        player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
    }

    /// Host time (s) of the player's frame 0, once `lastRenderTime` shows it has rendered.
    private static func firstRenderHostSeconds(_ player: AVAudioPlayerNode, sampleRate: Double) async -> Double? {
        let deadline = ContinuousClock.now.advanced(by: renderTimeout)
        while ContinuousClock.now < deadline {
            if let node = player.lastRenderTime, node.isHostTimeValid,
               let p = player.playerTime(forNodeTime: node), p.isSampleTimeValid, p.sampleTime >= 0 {
                return BeatClock.firstFrameHostSeconds(renderHostSeconds: AVAudioTime.seconds(forHostTime: node.hostTime),
                                                       playerSampleTime: Double(p.sampleTime), sampleRate: sampleRate)
            }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return nil
    }

    /// A stop from the watch for `press` (sent now, or queued and delivered late): stops only that
    /// press's beat (21).
    func stop(press: Int64) {
        if Pilot.phoneShouldStop(playingPress: playingPress, requestedPress: press) { stop(notify: false) }
    }

    /// 24: pause and resume follow the watch.
    func setPaused(_ paused: Bool, press: Int64) {
        guard isPlaying, playingPress == press, let player else { return }
        if paused { player.pause() } else { player.play() }
    }

    /// Stops whatever plays (sign-out, settings off).
    func stop() { stop(notify: false) }

    private func stop(notify: Bool) {
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        limitTask?.cancel(); limitTask = nil
        player?.stop()
        engine?.stop()
        player = nil
        engine = nil
        let was = isPlaying, press = playingPress
        isPlaying = false
        playingPress = nil
        if was { deactivate() }
        if was && notify, let press { onLost?(press) }
    }

    private func deactivate() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func observe() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { _ in
            // Earbuds removed: stop before anything plays from the speaker.
            MainActor.assumeIsolated {
                let p = PhoneBeatPlayer.shared
                if p.isPlaying && !PhoneBeatPlayer.earbudsConnected { p.stop(notify: true) }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue
            MainActor.assumeIsolated { if began { PhoneBeatPlayer.shared.stop(notify: true) } }
        })
        if let engine {
            observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { _ in
                MainActor.assumeIsolated {
                    let p = PhoneBeatPlayer.shared
                    if p.isPlaying && !(p.engine?.isRunning ?? false) { p.stop(notify: true) }
                }
            })
        }
    }

    /// One beat period: a 25 ms click (a decaying 1.5 kHz tone, easy to hear over street noise)
    /// followed by silence, `BeatClock.periodFrames` long.
    static func clickPeriod(format: AVAudioFormat, pace: Int) -> AVAudioPCMBuffer? {
        let frames = BeatClock.periodFrames(sampleRate: format.sampleRate, paceSpm: pace)
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let samples = buffer.floatChannelData?[0] else { return nil }
        buffer.frameLength = AVAudioFrameCount(frames)
        let sr = format.sampleRate
        let clickFrames = min(frames, Int(0.025 * sr))
        for i in 0..<frames {
            if i < clickFrames {
                let t = Double(i) / sr
                samples[i] = Float(0.8 * sin(2 * .pi * 1500 * t) * exp(-t / 0.006))
            } else {
                samples[i] = 0
            }
        }
        return buffer
    }
}
