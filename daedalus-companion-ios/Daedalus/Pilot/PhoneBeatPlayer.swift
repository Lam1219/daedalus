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
@MainActor
final class PhoneBeatPlayer {
    static let shared = PhoneBeatPlayer()

    /// Called when the beat stopped on its own (earbuds removed, an interruption such as a call).
    var onLost: (() -> Void)?
    private(set) var isPlaying = false

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var observers: [NSObjectProtocol] = []

    static let earbudPorts: Set<AVAudioSession.Port> = [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP, .headphones]

    static var earbudsConnected: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { earbudPorts.contains($0.portType) }
    }

    /// Starts the beat; returns when the first click reaches the ear (epoch ms, iPhone clock),
    /// or nil when it can't play to earbuds.
    func start(pace: Int) -> Int64? {
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
        player.scheduleBuffer(buffer, at: nil, options: .loops)
        // Start a little ahead so the first click isn't clipped, at a known host time.
        let lead = 0.05
        player.play(at: AVAudioTime(hostTime: mach_absolute_time() + AVAudioTime.hostTime(forSeconds: lead)))
        // The click reaches the ear after the output latency (large for Bluetooth: often 0.1–0.25 s).
        let latency = session.outputLatency + session.ioBufferDuration
        let firstClickMs = Int64(((Date.now.timeIntervalSince1970 + lead + latency) * 1000).rounded())

        self.engine = engine
        self.player = player
        isPlaying = true
        observe()
        return firstClickMs
    }

    func stop() { stop(notify: false) }

    private func stop(notify: Bool) {
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        player?.stop()
        engine?.stop()
        player = nil
        engine = nil
        let was = isPlaying
        isPlaying = false
        if was { deactivate() }
        if was && notify { onLost?() }
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
