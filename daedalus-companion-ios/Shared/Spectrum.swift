import Foundation

/// The spectrum both tremor checks use: the 20 s check (`TremorAnalyzer`, tremor-1.0) and the
/// streaming features of automatic cues (stream-features-1.0, Apple Watch only).
///
/// Same maths as daedalus_watch_kit (tremor_check.py, stream_features.py) and Spectrum.kt:
/// remove each axis's mean, apply a Hann window, then a one-sided power spectral density at the
/// given frequencies, summed over the axes. Integrating it over a band gives the band's
/// mean-square value, so the square root of band power is an RMS in the input's units.
/// Change all copies together.
enum Spectrum {
    /// Hann window of `n` samples.
    static func hann(_ n: Int) -> [Double] {
        (0..<n).map { 0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n - 1)) }
    }

    /// Each axis minus its mean, times `window`.
    static func prepare(_ axes: [[Double]], window: [Double]) -> [[Double]] {
        axes.map { a in
            let mean = a.reduce(0, +) / Double(a.count)
            return (0..<a.count).map { (a[$0] - mean) * window[$0] }
        }
    }

    /// One-sided PSD of the prepared `signals` (all as long as `window`) at `freqs` Hz, summed
    /// over the signals: 2 |X(f)|² / (fs · Σw²).
    static func psd(_ signals: [[Double]], fs: Double, window: [Double], freqs: [Double]) -> [Double] {
        let windowPower = window.reduce(0) { $0 + $1 * $1 }
        return freqs.map { f in
            let w = 2 * Double.pi * f / fs
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
            return 2 * total / (fs * windowPower)
        }
    }
}
