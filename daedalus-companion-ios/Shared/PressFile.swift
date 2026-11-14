import Foundation

/// One home-pilot press file (B4, B5): the JSON format in the docstring of
/// daedalus_watch_kit/daedalus_watch_kit/pilot_analysis.py, which `pilot_analysis.analyse` reads.
/// Times are epoch ms on the watch's clock. Apple Watch's background recorder is accelerometer
/// only, so `gyr` is null in files from this app.
struct PressRecord: Sendable {
    var participant: String
    var pressIndex: Int64
    var dayIndex: Int
    var place: Pilot.Place
    var earbudsConnected: Bool
    var phoneCarried: Bool
    var paceSpm: Int
    var pressMs: Int64
    var beatStartMs: Int64?
    var beatStopMs: Int64?
    var checkin: Pilot.CheckIn?
    var samples = PressSamples()
    /// 18: the background recorder hadn't reached press + 60 s when the file had to be written.
    var motionPartial = false
    /// Round 006, as Android: the place the rule drew (`place` is where the beat started), why
    /// and when the wrist took over (both nil when it didn't).
    var placeDrawn: Pilot.Place?
    var fallbackReason: String?
    var fallbackMs: Int64?

    /// The beat's outcome: where it started, when, what was drawn, and any fallback.
    mutating func apply(_ beat: PressBeat) {
        place = beat.place
        placeDrawn = beat.placeDrawn
        beatStartMs = beat.beatStartMs
        fallbackReason = beat.fallbackReason
        fallbackMs = beat.fallbackMs
    }

    /// "press_<participant>_<index>_<press_ms>.json" (15), as on Android: the index is zero-padded
    /// so files sort in press order, and press_ms keeps two presses with the same index apart
    /// (a reinstall restarts the counter).
    var fileName: String {
        let safe = participant.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return String(format: "press_%@_%05lld_%lld.json", safe.isEmpty ? "P" : safe, pressIndex, pressMs)
    }

    /// The metadata only (no samples): kept on disk while a press waits for its motion.
    var metadata: [String: Any] {
        var d: [String: Any] = [
            "version": Pilot.version, "participant": participant,
            "press_index": NSNumber(value: pressIndex), "day_index": dayIndex,
            "place": place.rawValue, "earbuds_connected": earbudsConnected, "phone_carried": phoneCarried,
            "pace_spm": paceSpm, "press_ms": NSNumber(value: pressMs),
        ]
        d["beat_start_ms"] = beatStartMs.map { NSNumber(value: $0) } ?? NSNull()
        d["beat_stop_ms"] = beatStopMs.map { NSNumber(value: $0) } ?? NSNull()
        d["checkin"] = checkin?.rawValue ?? NSNull()
        d["motion_partial"] = motionPartial
        d["place_drawn"] = (placeDrawn ?? place).rawValue
        d["fallback_reason"] = fallbackReason ?? NSNull()
        d["fallback_ms"] = fallbackMs.map { NSNumber(value: $0) } ?? NSNull()
        return d
    }

    func json() throws -> Data {
        var d = metadata
        d["samples"] = [
            "t_ms": samples.tMs.map { NSNumber(value: $0) },
            "acc": [samples.ax, samples.ay, samples.az],
            "gyr": samples.gyr.map { [$0.x, $0.y, $0.z] } ?? NSNull(),
        ] as [String: Any]
        return try JSONSerialization.data(withJSONObject: d, options: [.sortedKeys])
    }

    init(participant: String, pressIndex: Int64, dayIndex: Int, place: Pilot.Place, earbudsConnected: Bool,
         phoneCarried: Bool, paceSpm: Int, pressMs: Int64, beatStartMs: Int64? = nil, beatStopMs: Int64? = nil,
         checkin: Pilot.CheckIn? = nil, samples: PressSamples = PressSamples()) {
        self.participant = participant; self.pressIndex = pressIndex; self.dayIndex = dayIndex
        self.place = place; self.earbudsConnected = earbudsConnected; self.phoneCarried = phoneCarried
        self.paceSpm = paceSpm; self.pressMs = pressMs; self.beatStartMs = beatStartMs
        self.beatStopMs = beatStopMs; self.checkin = checkin; self.samples = samples
    }

    /// Reads a press file or its metadata (samples optional).
    init?(json data: Data) {
        guard let d = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              d["version"] as? String == Pilot.version,
              let participant = d["participant"] as? String,
              let pressIndex = (d["press_index"] as? NSNumber)?.int64Value,
              let dayIndex = (d["day_index"] as? NSNumber)?.intValue,
              let place = (d["place"] as? String).flatMap(Pilot.Place.init(rawValue:)),
              let pressMs = (d["press_ms"] as? NSNumber)?.int64Value
        else { return nil }
        self.init(participant: participant, pressIndex: pressIndex, dayIndex: dayIndex, place: place,
                  earbudsConnected: d["earbuds_connected"] as? Bool ?? false,
                  phoneCarried: d["phone_carried"] as? Bool ?? false,
                  paceSpm: (d["pace_spm"] as? NSNumber)?.intValue ?? 0, pressMs: pressMs,
                  beatStartMs: (d["beat_start_ms"] as? NSNumber)?.int64Value,
                  beatStopMs: (d["beat_stop_ms"] as? NSNumber)?.int64Value,
                  checkin: (d["checkin"] as? String).flatMap(Pilot.CheckIn.init(rawValue:)))
        motionPartial = d["motion_partial"] as? Bool ?? false
        placeDrawn = (d["place_drawn"] as? String).flatMap(Pilot.Place.init(rawValue:))
        fallbackReason = d["fallback_reason"] as? String
        fallbackMs = (d["fallback_ms"] as? NSNumber)?.int64Value
        if let s = d["samples"] as? [String: Any], let t = s["t_ms"] as? [NSNumber], let acc = s["acc"] as? [[Double]], acc.count == 3 {
            samples = PressSamples(tMs: t.map(\.int64Value), ax: acc[0], ay: acc[1], az: acc[2])
            if let g = s["gyr"] as? [[Double]], g.count == 3 { samples.gyr = (g[0], g[1], g[2]) }
        }
    }
}

/// Motion around a press: acceleration in m/s² (gravity included), times in epoch ms, sorted.
struct PressSamples: Sendable {
    var tMs: [Int64] = []
    var ax: [Double] = [], ay: [Double] = [], az: [Double] = []
    var gyr: (x: [Double], y: [Double], z: [Double])?

    init(tMs: [Int64] = [], ax: [Double] = [], ay: [Double] = [], az: [Double] = []) {
        self.tMs = tMs; self.ax = ax; self.ay = ay; self.az = az
    }

    struct Sample: Sendable { var tMs: Int64; var ax, ay, az: Double }

    /// The window [press - 30 s, press + 60 s] from the background recorder's samples, with live
    /// samples filling only the time after the recorder's last sample (the recorder can lag behind
    /// the present). Accelerometer only, so `gyr` stays nil.
    static func merge(recorded: [Sample], live: [Sample], pressMs: Int64) -> PressSamples {
        let from = pressMs - Int64(Pilot.bufferBeforeS) * 1000, to = pressMs + Int64(Pilot.bufferAfterS) * 1000
        let inWindow = { (s: Sample) in s.tMs >= from && s.tMs <= to }
        var all = recorded.filter(inWindow).sorted { $0.tMs < $1.tMs }
        let lastRecorded = all.last?.tMs ?? Int64.min
        all += live.filter { inWindow($0) && $0.tMs > lastRecorded }.sorted { $0.tMs < $1.tMs }
        var out = PressSamples()
        var previous = Int64.min
        for s in all where s.tMs > previous {   // strictly increasing times
            out.tMs.append(s.tMs); out.ax.append(s.ax); out.ay.append(s.ay); out.az.append(s.az)
            previous = s.tMs
        }
        return out
    }
}
