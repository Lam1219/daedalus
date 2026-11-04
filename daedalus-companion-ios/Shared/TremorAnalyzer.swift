import Foundation

/// Tremor check (model "tremor-1.0"): finds the rhythm and strength of a resting tremor in about
/// 20 seconds of wrist rotation (gyroscope, rad/s) recorded with the hand at rest.
///
/// Shared by the Apple Watch app (shows the result right away) and the iPhone app (reads it back
/// from the session file for the Journal). TremorAnalyzer.kt is its Android twin: change both
/// together and bump `version`.
///
/// Steps: drop the first second (the start buzz), resample evenly at 50 Hz, remove each axis's
/// mean, apply a Hann window, then sum the three axes' power spectra from 0.5 to 12 Hz.
/// - Too much slow movement (0.5–2.5 Hz) → `.moved`.
/// - A clear peak in the tremor band (3–8 Hz) with enough power → `.tremor`, with its frequency
///   and a strength level from the band's RMS rotation speed.
/// - Otherwise → `.none`.
///
/// Thresholds are provisional: they are to be set in the healthy-volunteer study. This is a
/// wellness trend, never a diagnosis.
enum TremorAnalyzer {
    static let version = "tremor-1.0"
    static let mode = "tremor"
    /// How long the watch records for one check.
    static let checkDuration: TimeInterval = 20

    private static let settleMs: Int64 = 1_000
    private static let fs = 50.0
    private static let minDurationS = 10.0
    private static let maxGapMs: Int64 = 250
    private static let fMin = 0.5
    private static let fMax = 12.0
    private static let df = 0.05
    private static let bandLow = 3.0
    private static let bandHigh = 8.0
    private static let slowHigh = 2.5
    private static let peakExcludeHz = 1.0
    private static let radToDeg = 180.0 / Double.pi

    /// Thresholds in degrees per second (RMS).
    static let minTremorDps = 1.5
    static let moderateDps = 10.0
    static let strongDps = 40.0
    static let maxSlowDps = 20.0
    /// Peak power over the mean power elsewhere in 0.5–12 Hz.
    static let minProminence = 6.0

    enum Outcome: String { case tremor, none, moved, incomplete }
    enum Strength: String { case light, moderate, strong }

    struct Result: Equatable {
        var outcome: Outcome
        /// Peak frequency in Hz, to one decimal; only for `.tremor`.
        var frequencyHz: Double? = nil
        var strength: Strength? = nil
        /// RMS rotation speed in the 3–8 Hz band, degrees per second.
        var bandDps: Double = 0
        var prominence: Double = 0
        var version: String = TremorAnalyzer.version
    }

    /// `timesMs` wall-clock milliseconds, increasing; `gx`, `gy`, `gz` rotation rate in rad/s.
    /// Samples need not be evenly spaced.
    static func analyze(timesMs: [Int64], gx: [Double], gy: [Double], gz: [Double]) -> Result {
        let n0 = min(timesMs.count, gx.count, gy.count, gz.count)
        guard n0 >= 2 else { return Result(outcome: .incomplete) }
        let start = timesMs[0] + settleMs
        let end = timesMs[n0 - 1]
        let durationS = Double(end - start) / 1000
        guard durationS >= minDurationS else { return Result(outcome: .incomplete) }
        for i in 1..<n0 where timesMs[i] - timesMs[i - 1] > maxGapMs && timesMs[i] > start {
            return Result(outcome: .incomplete)
        }

        // Resample each axis evenly at 50 Hz (linear interpolation).
        let n = Int(durationS * fs) + 1
        var axes = [[Double]](repeating: [Double](repeating: 0, count: n), count: 3)
        var j = 0
        for k in 0..<n {
            let t = Double(start) + Double(k) * 1000 / fs
            while j < n0 - 2 && Double(timesMs[j + 1]) < t { j += 1 }
            let t0 = Double(timesMs[j]), t1 = Double(timesMs[j + 1])
            let f = t1 == t0 ? 0 : min(max((t - t0) / (t1 - t0), 0), 1)
            axes[0][k] = gx[j] + f * (gx[j + 1] - gx[j])
            axes[1][k] = gy[j] + f * (gy[j + 1] - gy[j])
            axes[2][k] = gz[j] + f * (gz[j + 1] - gz[j])
        }

        // Hann window; its power sum normalises the spectrum so band power is in (rad/s)².
        let window = (0..<n).map { 0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n - 1)) }
        let windowPower = window.reduce(0) { $0 + $1 * $1 }
        let signals = axes.map { a -> [Double] in
            let mean = a.reduce(0, +) / Double(n)
            return (0..<n).map { (a[$0] - mean) * window[$0] }
        }

        // One-sided power spectral density, summed over the three axes.
        let bins = Int(((fMax - fMin) / df).rounded()) + 1
        let freqs = (0..<bins).map { fMin + Double($0) * df }
        var psd = [Double](repeating: 0, count: bins)
        for b in 0..<bins {
            let w = 2 * Double.pi * freqs[b] / fs
            let cw = cos(w), sw = sin(w)
            var total = 0.0
            for x in signals {
                // Rotate a phasor instead of calling sin/cos per sample.
                var c = 1.0, s = 0.0, re = 0.0, im = 0.0
                for v in x {
                    re += v * c
                    im -= v * s
                    let c2 = c * cw - s * sw
                    s = s * cw + c * sw
                    c = c2
                }
                total += re * re + im * im
            }
            psd[b] = 2 * total / (fs * windowPower)
        }

        func bandPower(_ low: Double, _ high: Double) -> Double {
            var p = 0.0
            for b in 0..<bins where freqs[b] >= low - 1e-9 && freqs[b] <= high + 1e-9 { p += psd[b] * df }
            return p
        }

        let slowDps = bandPower(fMin, slowHigh).squareRoot() * radToDeg
        let bandDps = bandPower(bandLow, bandHigh).squareRoot() * radToDeg

        var peak = -1
        for b in 0..<bins where freqs[b] >= bandLow - 1e-9 && freqs[b] <= bandHigh + 1e-9 {
            if peak < 0 || psd[b] > psd[peak] { peak = b }
        }
        var restSum = 0.0, restCount = 0
        for b in 0..<bins where abs(freqs[b] - freqs[peak]) > peakExcludeHz {
            restSum += psd[b]
            restCount += 1
        }
        let prominence = restCount == 0 || restSum <= 0 ? 0 : psd[peak] / (restSum / Double(restCount))

        if slowDps > maxSlowDps { return Result(outcome: .moved, bandDps: bandDps, prominence: prominence) }
        if bandDps < minTremorDps || prominence < minProminence {
            return Result(outcome: .none, bandDps: bandDps, prominence: prominence)
        }

        // Parabolic interpolation around the peak for a finer frequency.
        var hz = freqs[peak]
        if peak > 0 && peak < bins - 1 {
            let a = psd[peak - 1], b = psd[peak], c = psd[peak + 1]
            let denom = a - 2 * b + c
            if denom != 0 { hz += min(max(0.5 * (a - c) / denom, -0.5), 0.5) * df }
        }
        let strength: Strength = bandDps >= strongDps ? .strong : bandDps >= moderateDps ? .moderate : .light
        return Result(outcome: .tremor, frequencyHz: (hz * 10).rounded() / 10, strength: strength,
                      bandDps: bandDps, prominence: prominence)
    }

    /// Analyses a session CSV (columns `timestamp_ms` and `gx,gy,gz`, as both watches write them).
    /// Rows that don't parse are skipped.
    static func analyze(csv text: String) -> Result {
        var lines = text.split(whereSeparator: \.isNewline).makeIterator()
        guard let headerLine = lines.next() else { return Result(outcome: .incomplete) }
        let header = headerLine.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        guard let iT = header.firstIndex(of: "timestamp_ms"), let iX = header.firstIndex(of: "gx"),
              let iY = header.firstIndex(of: "gy"), let iZ = header.firstIndex(of: "gz")
        else { return Result(outcome: .incomplete) }
        let need = max(iT, iX, iY, iZ)
        var t: [Int64] = [], x: [Double] = [], y: [Double] = [], z: [Double] = []
        while let line = lines.next() {
            let cells = line.split(separator: ",", omittingEmptySubsequences: false)
            guard cells.count > need,
                  let time = Int64(cells[iT].trimmingCharacters(in: .whitespaces)),
                  let vx = Double(cells[iX].trimmingCharacters(in: .whitespaces)),
                  let vy = Double(cells[iY].trimmingCharacters(in: .whitespaces)),
                  let vz = Double(cells[iZ].trimmingCharacters(in: .whitespaces))
            else { continue }
            // Keep times strictly increasing (two rows in the same millisecond add nothing).
            if let last = t.last, time <= last { continue }
            t.append(time); x.append(vx); y.append(vy); z.append(vz)
        }
        return analyze(timesMs: t, gx: x, gy: y, gz: z)
    }
}
