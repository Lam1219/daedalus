package com.medicina.daedaluscompanion.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The spectrum both tremor checks use: the 20 s check ([TremorAnalyzer], tremor-1.0) and the
 * streaming features of automatic cues (stream-features-1.0, Galaxy Watch only).
 *
 * Same maths as daedalus_watch_kit (tremor_check.py, stream_features.py) and Spectrum.swift:
 * remove each axis's mean, apply a Hann window, then a one-sided power spectral density at the
 * given frequencies, summed over the axes. Integrating it over a band gives the band's mean-square
 * value, so sqrt(band power) is an RMS in the input's units. Change all copies together.
 */
object Spectrum {
    /** Hann window of [n] samples. */
    fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    /** Each axis minus its mean, times [window]. */
    fun prepare(axes: List<DoubleArray>, window: DoubleArray): List<DoubleArray> = axes.map { a ->
        val mean = a.average()
        DoubleArray(a.size) { (a[it] - mean) * window[it] }
    }

    /**
     * One-sided PSD of the prepared [signals] (all the same length as [window]) at [freqs] Hz,
     * summed over the signals: 2 |X(f)|² / (fs · Σw²).
     */
    fun psd(signals: List<DoubleArray>, fs: Double, window: DoubleArray, freqs: DoubleArray): DoubleArray {
        val windowPower = window.sumOf { it * it }
        return DoubleArray(freqs.size) { b ->
            val w = 2 * PI * freqs[b] / fs
            val cw = cos(w)
            val sw = sin(w)
            var total = 0.0
            for (x in signals) {
                // Rotate a phasor instead of calling sin/cos per sample.
                var c = 1.0
                var s = 0.0
                var re = 0.0
                var im = 0.0
                for (v in x) {
                    re += v * c
                    im -= v * s
                    val c2 = c * cw - s * sw
                    s = s * cw + c * sw
                    c = c2
                }
                total += re * re + im * im
            }
            2 * total / (fs * windowPower)
        }
    }
}
