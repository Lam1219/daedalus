import Foundation

/// Streaming motion features for automatic cues (model "stream-features-1.0",
/// Documentation/automatic-cues-design.md, 2.3). Apple Watch only for now; the iPhone app
/// compiles it because it shares the Shared folder.
///
/// Port of daedalus_watch_kit/stream_features.py, the reference: the Galaxy Watch
/// (StreamFeatures.kt) and this file must compute the same numbers on the same samples, checked
/// by the golden vectors in daedalus_watch_kit/golden (SharedTests). Change all copies together
/// and bump `version`.
///
/// Every `hop` samples (1 s at 50 Hz) a window is computed from the latest samples:
///
/// - 2 s (100 samples), the tremor and freeze-index window: `tremorDps` (RMS rotation in 3–8 Hz,
///   three gyroscope axes summed), `peakHz` (highest power in 3–8 Hz, interpolated),
///   `prominence` (peak power over the mean power in 0.5–12 Hz more than 1 Hz away), `slowDps`
///   (0.5–2.5 Hz), `motionDps` (0.5–12 Hz), `meanRotationDps` (size of the mean rotation
///   vector), `accStd` (population standard deviation of the acceleration magnitude),
///   `gravityChangeDeg` (angle between this window's and the previous window's mean
///   acceleration direction), `freezeIndex` (acceleration-magnitude power 3.5–8 Hz / 0.5–3 Hz).
/// - 4 s (200 samples), the gait window: `cadenceSpm` (from the stride period, autocorrelation
///   of the acceleration magnitude), `stepRegularity` (autocorrelation at that period, 0–1) and
///   `armSwingDps` (0.5–2 Hz rotation). They stay 0 until 4 s of samples exist.
///
/// Input units are the session units: acceleration in m/s² with gravity, rotation in rad/s,
/// evenly spaced at 50 Hz (`resampleUniform` first if they are not). Every feature is axis-free
/// (magnitudes, sums over axes, angles), since wrist axes differ between watches and wrists.
/// Every spectrum is `Spectrum.psd` at the DFT bin frequencies k·fs/n.
enum StreamFeatures {
    static let version = "stream-features-1.0"

    static let fs = 50.0
    static let hop = 50          // samples, 1 s
    static let win = 100         // samples, 2 s
    static let gaitWin = 200     // samples, 4 s
    static let radToDeg = 180.0 / Double.pi

    /// Stride (two steps) period search range: 35–70 strides/min = 70–140 steps/min.
    static let strideLagMin = Int((fs * 60 / 70).rounded())   // 43 samples
    static let strideLagMax = Int((fs * 60 / 35).rounded())   // 86 samples

    // Context thresholds (provisional; tuned on Parkinson@Home and the volunteer recordings).
    static let walkRegularity = 0.5
    static let walkAccStd = 0.5            // m/s²
    /// Walking puts most acceleration power below 3 Hz; trembling doesn't.
    static let walkMaxFreezeIndex = 1.0
    static let stillMotionDps = 3.0
    static let stillAccStd = 0.15          // m/s²
    /// Same limit as tremor-1.0's "moved" rule.
    static let restSlowDps = 20.0
    static let postureChangeDeg = 15.0
    static let turnMeanRotationDps = 25.0

    // Hann windows and DFT bin frequencies of the two window lengths.
    static let hann2 = Spectrum.hann(win)
    static let hann4 = Spectrum.hann(gaitWin)
    static let freqs2 = (0...win / 2).map { Double($0) * fs / Double(win) }
    static let freqs4 = (0...gaitWin / 2).map { Double($0) * fs / Double(gaitWin) }

    // MARK: - Helpers

    /// Linear interpolation of each channel onto an even `fs` grid from `timesMs[0]`, like
    /// numpy.interp (values clamp at the ends). Grid times are truncated to whole milliseconds.
    static func resampleUniform(timesMs: [Int64], channels: [[Double]], fs: Double = fs) -> (timesMs: [Int64], channels: [[Double]]) {
        guard let first = timesMs.first, let last = timesMs.last else { return ([], channels.map { _ in [] }) }
        let t = timesMs.map(Double.init)
        let n = Int(Double(last - first) / 1000.0 * fs) + 1
        let grid = (0..<n).map { Double(first) + Double($0) * 1000.0 / fs }
        var out = channels.map { _ in [Double](repeating: 0, count: n) }
        var j = 0
        let m = t.count
        for (k, x) in grid.enumerated() {
            // Largest j with t[j] <= x (numpy's search: t[j] <= x < t[j + 1]).
            while j + 1 < m && t[j + 1] <= x { j += 1 }
            for (c, fp) in channels.enumerated() {
                if x < t[0] {
                    out[c][k] = fp[0]
                } else if j >= m - 1 {
                    out[c][k] = fp[m - 1]
                } else if x == t[j] {
                    out[c][k] = fp[j]
                } else {
                    let slope = (fp[j + 1] - fp[j]) / (t[j + 1] - t[j])
                    out[c][k] = slope * (x - t[j]) + fp[j]
                }
            }
        }
        return (grid.map { Int64($0) }, out)
    }

    /// One-sided PSD of the axes, summed over them (`Spectrum`), at the window's bin frequencies.
    private static func psd(_ axes: [[Double]]) -> (freqs: [Double], psd: [Double]) {
        let n = axes[0].count
        let (window, freqs) = n == win ? (hann2, freqs2) : (hann4, freqs4)
        let prepared = Spectrum.prepare(axes, window: window)
        return (freqs, Spectrum.psd(prepared, fs: fs, window: window, freqs: freqs))
    }

    /// Band power: the psd summed over `low`–`high` Hz (inclusive), times the bin width.
    private static func band(_ freqs: [Double], _ p: [Double], _ low: Double, _ high: Double) -> Double {
        let df = freqs[1] - freqs[0]
        var s = 0.0
        for k in 0..<freqs.count where freqs[k] >= low - 1e-9 && freqs[k] <= high + 1e-9 { s += p[k] }
        return s * df
    }

    /// Normalised autocorrelation of a demeaned signal at one lag (unbiased by overlap length).
    private static func autocorr(_ x: [Double], _ lag: Int) -> Double {
        var ab = 0.0, aa = 0.0, bb = 0.0
        for i in 0..<(x.count - lag) {
            let a = x[i], b = x[i + lag]
            ab += a * b; aa += a * a; bb += b * b
        }
        let den = (aa * bb).squareRoot()
        return den > 0 ? ab / den : 0
    }

    private static func mean(_ x: [Double]) -> Double { x.reduce(0, +) / Double(x.count) }

    private static func magnitude(_ acc: [[Double]]) -> [Double] {
        let x = acc[0], y = acc[1], z = acc[2]
        return (0..<x.count).map { i -> Double in
            let s: Double = x[i] * x[i] + y[i] * y[i] + z[i] * z[i]
            return s.squareRoot()
        }
    }

    // MARK: - One window

    /// Features of one hop. `acc2`, `gyr2`: three axes of the last 2 s (100 samples); `acc4`,
    /// `gyr4`: the last 4 s (200 samples), or nil before 4 s exist. Returns the features and this
    /// window's gravity direction, passed back as `prevGravity` next time.
    ///
    /// `tremorWindowS` 4 takes tremorDps, peakHz and prominence from the last 4 s (0.25 Hz bins,
    /// for weak tremor) once 4 s exist; the context features stay on 2 s either way.
    static func windowFeatures(acc2: [[Double]], gyr2: [[Double]], acc4: [[Double]]?, gyr4: [[Double]]?,
                               prevGravity: [Double]?, tEndMs: Int64,
                               tremorWindowS: Int = 2) -> (features: WindowFeatures, gravity: [Double]) {
        precondition(tremorWindowS == 2 || tremorWindowS == 4, "tremorWindowS must be 2 or 4")

        // Gyroscope spectrum over 2 s, three axes summed: voluntary movement and context.
        var (f, p) = psd(gyr2)
        let slowDps = band(f, p, 0.5, 2.5).squareRoot() * radToDeg
        let motionDps = band(f, p, 0.5, 12.0).squareRoot() * radToDeg

        // Tremor spectrum: the same 2 s, or the last 4 s. The 4 s gyroscope spectrum also gives
        // the arm swing below.
        var gyr4Spectrum: (freqs: [Double], psd: [Double])?
        if let gyr4 { gyr4Spectrum = psd(gyr4) }
        if tremorWindowS == 4, let s = gyr4Spectrum { (f, p) = s }
        let tremorDps = band(f, p, 3.0, 8.0).squareRoot() * radToDeg

        var k = -1
        for i in 0..<f.count where f[i] >= 3.0 - 1e-9 && f[i] <= 8.0 + 1e-9 {
            if k < 0 || p[i] > p[k] { k = i }
        }
        var peakHz = f[k]
        if k > 0 && k < p.count - 1 {
            let a = p[k - 1], b = p[k], c = p[k + 1]
            let den = a - 2 * b + c
            if den != 0 { peakHz += min(max(0.5 * (a - c) / den, -0.5), 0.5) * (f[1] - f[0]) }
        }
        var restSum = 0.0, restCount = 0
        for i in 0..<f.count where f[i] >= 0.5 - 1e-9 && f[i] <= 12.0 + 1e-9 && abs(f[i] - f[k]) > 1.0 + 1e-9 {
            restSum += p[i]
            restCount += 1
        }
        let meanRest = restCount > 0 ? restSum / Double(restCount) : 0
        let prominence = meanRest > 0 ? p[k] / meanRest : 0

        let gyrMean = gyr2.map(mean)
        let meanRotationDps = (gyrMean[0] * gyrMean[0] + gyrMean[1] * gyrMean[1] + gyrMean[2] * gyrMean[2]).squareRoot() * radToDeg

        // Acceleration magnitude: steps, freeze index, stillness.
        let mag2 = magnitude(acc2)
        let magMean = mean(mag2)
        let accStd = (mag2.reduce(0) { $0 + ($1 - magMean) * ($1 - magMean) } / Double(mag2.count)).squareRoot()
        let (fa, pa) = psd([mag2])
        let low = band(fa, pa, 0.5, 3.0)
        let high = band(fa, pa, 3.0 + 0.5, 8.0)  // bins are 0.5 Hz apart: 3.5–8 Hz, so 3.0 Hz is not counted twice
        let freezeIndex = low > 0 ? high / low : 0

        var gravity = acc2.map(mean)
        let norm = (gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).squareRoot()
        if norm > 0 { gravity = gravity.map { $0 / norm } }
        var gravityChangeDeg = 0.0
        if let prev = prevGravity {
            let dot = gravity[0] * prev[0] + gravity[1] * prev[1] + gravity[2] * prev[2]
            gravityChangeDeg = acos(min(max(dot, -1), 1)) * radToDeg
        }

        // Gait window, 4 s.
        var cadenceSpm = 0.0, stepRegularity = 0.0, armSwingDps = 0.0
        if let acc4, let s = gyr4Spectrum {
            let mag4 = magnitude(acc4)
            let m4 = mean(mag4)
            let x = mag4.map { $0 - m4 }
            var bestLag = 0, bestR = -1.0
            for lag in strideLagMin...strideLagMax {
                let r = autocorr(x, lag)
                if r > bestR { bestLag = lag; bestR = r }
            }
            stepRegularity = max(bestR, 0)
            cadenceSpm = bestLag != 0 ? 2 * 60.0 * fs / Double(bestLag) : 0
            armSwingDps = band(s.freqs, s.psd, 0.5, 2.0).squareRoot() * radToDeg
        }

        var w = WindowFeatures(tEndMs: tEndMs, tremorDps: tremorDps, peakHz: peakHz, prominence: prominence,
                               slowDps: slowDps, motionDps: motionDps, meanRotationDps: meanRotationDps,
                               accStd: accStd, gravityChangeDeg: gravityChangeDeg, freezeIndex: freezeIndex,
                               cadenceSpm: cadenceSpm, stepRegularity: stepRegularity, armSwingDps: armSwingDps,
                               context: .still)
        w.context = classifyContext(w)
        return (w, gravity)
    }

    /// Rule-based context, in priority order: walking, posture change, still, rest, active.
    static func classifyContext(_ f: WindowFeatures) -> Context {
        // A steady 3–8 Hz tremble also repeats at stride lags, so regularity alone isn't walking.
        let walking = f.stepRegularity >= walkRegularity && f.accStd >= walkAccStd
            && f.freezeIndex < walkMaxFreezeIndex
        if walking { return f.meanRotationDps >= turnMeanRotationDps ? .turning : .walking }
        if f.gravityChangeDeg >= postureChangeDeg { return .activeHand }
        if f.motionDps < stillMotionDps && f.accStd < stillAccStd { return .still }
        if f.slowDps < restSlowDps { return .restTremorPossible }
        return .activeHand
    }

    // MARK: - Batch

    /// All windows of an evenly sampled 50 Hz recording. `acc` and `gyr` are three axes of n
    /// samples. Gives the same windows as pushing every sample through `Extractor`.
    static func computeFeatures(timesMs: [Int64], acc: [[Double]], gyr: [[Double]],
                                tremorWindowS: Int = 2) -> [WindowFeatures] {
        let n = acc[0].count
        var out: [WindowFeatures] = []
        var prev: [Double]?
        var end = win
        while end <= n {
            let g = end >= gaitWin
            func slice(_ x: [[Double]], _ len: Int) -> [[Double]] { x.map { Array($0[(end - len)..<end]) } }
            let r = windowFeatures(acc2: slice(acc, win), gyr2: slice(gyr, win),
                                   acc4: g ? slice(acc, gaitWin) : nil, gyr4: g ? slice(gyr, gaitWin) : nil,
                                   prevGravity: prev, tEndMs: timesMs[end - 1], tremorWindowS: tremorWindowS)
            out.append(r.features)
            prev = r.gravity
            end += hop
        }
        return out
    }

    // MARK: - Streaming

    /// Push evenly spaced 50 Hz samples one at a time; a `WindowFeatures` comes out every second.
    ///
    /// A 4 s ring buffer; pushing a sample only writes into it. The first window comes out once
    /// 2 s of samples exist; gait features stay 0 until 4 s exist.
    struct Extractor {
        let tremorWindowS: Int
        private var acc = [[Double]](repeating: [Double](repeating: 0, count: gaitWin), count: 3)
        private var gyr = [[Double]](repeating: [Double](repeating: 0, count: gaitWin), count: 3)
        private var head = 0      // index of the next write, the oldest sample once full
        private var n = 0
        private var prevGravity: [Double]?

        init(tremorWindowS: Int = 2) {
            precondition(tremorWindowS == 2 || tremorWindowS == 4, "tremorWindowS must be 2 or 4")
            self.tremorWindowS = tremorWindowS
        }

        mutating func push(tMs: Int64, ax: Double, ay: Double, az: Double,
                           gx: Double, gy: Double, gz: Double) -> WindowFeatures? {
            acc[0][head] = ax; acc[1][head] = ay; acc[2][head] = az
            gyr[0][head] = gx; gyr[1][head] = gy; gyr[2][head] = gz
            head = (head + 1) % gaitWin
            n += 1
            guard n >= win, (n - win) % hop == 0 else { return nil }
            let full = n >= gaitWin
            let r = StreamFeatures.windowFeatures(
                acc2: latest(acc, win), gyr2: latest(gyr, win),
                acc4: full ? latest(acc, gaitWin) : nil, gyr4: full ? latest(gyr, gaitWin) : nil,
                prevGravity: prevGravity, tEndMs: tMs, tremorWindowS: tremorWindowS)
            prevGravity = r.gravity
            return r.features
        }

        /// The last `len` samples of each axis, oldest first.
        private func latest(_ ring: [[Double]], _ len: Int) -> [[Double]] {
            let start = (head - len + gaitWin) % gaitWin
            return ring.map { a in (0..<len).map { a[(start + $0) % gaitWin] } }
        }
    }
}

/// What the wrist is doing in one window. Raw values match the Python reference.
enum Context: String {
    case still = "still"
    /// Hand at rest or holding a posture: tremor can be judged.
    case restTremorPossible = "rest"
    /// Voluntary hand or arm movement, or a posture change.
    case activeHand = "active"
    case walking = "walking"
    /// Walking and turning.
    case turning = "turning"
}

/// One second's features (see `StreamFeatures`). Rotations in deg/s, acceleration in m/s².
struct WindowFeatures: Equatable {
    var tEndMs: Int64
    var tremorDps: Double
    var peakHz: Double
    var prominence: Double
    var slowDps: Double
    var motionDps: Double
    var meanRotationDps: Double
    var accStd: Double
    var gravityChangeDeg: Double
    var freezeIndex: Double
    var cadenceSpm: Double
    var stepRegularity: Double
    var armSwingDps: Double
    var context: Context
}
