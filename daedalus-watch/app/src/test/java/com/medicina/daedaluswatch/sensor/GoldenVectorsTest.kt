package com.medicina.daedaluswatch.sensor

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The golden vectors (the JSON files in daedalus_watch_kit/golden, written by make_golden.py from the Python
 * reference): StreamFeatures, TremorStream and FogDetector must give the same windows, tremor
 * episodes and freeze candidates within each file's tolerance (math.isclose semantics).
 */
@RunWith(Parameterized::class)
class GoldenVectorsTest(private val name: String) {

    companion object {
        private val GOLDEN = File("../../daedalus_watch_kit/golden")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val files = GOLDEN.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
            check(files.size >= 10) { "golden files not found in ${GOLDEN.absolutePath}" }
            return files.map { arrayOf<Any>(it.nameWithoutExtension) }
        }

        private val FEATURES = listOf(
            "tremor_dps", "peak_hz", "prominence", "slow_dps", "motion_dps", "mean_rotation_dps",
            "acc_std", "gravity_change_deg", "freeze_index", "cadence_spm", "step_regularity", "arm_swing_dps",
        )

        /** Largest differences seen, per field: (absolute, relative). */
        private val maxDiff = sortedMapOf<String, DoubleArray>()
        private val maxAt = HashMap<String, String>()

        @AfterClass
        @JvmStatic
        fun printDiffs() {
            println("Largest differences from the Python reference (absolute, relative):")
            for ((k, v) in maxDiff) println("  %-20s %.3e  %.3e  (largest absolute at %s)".format(k, v[0], v[1], maxAt[k]))
        }

        @Suppress("UNCHECKED_CAST")
        fun obj(x: Any?) = x as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        fun arr(x: Any?) = x as List<Any?>
        fun num(x: Any?): Double = when (x) {
            is Double -> x
            "inf" -> Double.POSITIVE_INFINITY
            "-inf" -> Double.NEGATIVE_INFINITY
            else -> error("not a number: $x")
        }
        fun doubles(x: Any?) = arr(x).map { num(it) }.toDoubleArray()
        fun longs(x: Any?) = arr(x).map { num(it).toLong() }.toLongArray()

        /** Samples of a case, resampled when the file says so. */
        fun input(case: Map<String, Any?>): Triple<LongArray, Array<DoubleArray>, Array<DoubleArray>> {
            val inp = obj(case["input"])
            val t = longs(inp["t_ms"])
            val ch = listOf("ax", "ay", "az", "gx", "gy", "gz").map { doubles(inp[it]) }
            if (case["resample"] != true) return Triple(t, ch.subList(0, 3).toTypedArray(), ch.subList(3, 6).toTypedArray())
            val r = StreamFeatures.resampleUniform(t, *ch.toTypedArray())
            return Triple(r.tMs, r.channels.subList(0, 3).toTypedArray(), r.channels.subList(3, 6).toTypedArray())
        }

        fun load(name: String): Map<String, Any?> = obj(Json.parse(File(GOLDEN, "$name.json").readText()))
    }

    private val case = load(name)
    private val tol = obj(case["tolerance"])
    private val rel = num(tol["relative"])
    private val absTol = num(tol["absolute"])

    private fun close(path: String, field: String, expected: Double, actual: Double) {
        val d = abs(expected - actual)
        val scale = max(abs(expected), abs(actual))
        synchronized(maxDiff) {
            val m = maxDiff.getOrPut(field) { DoubleArray(2) }
            if (d > m[0]) {
                m[0] = d
                maxAt[field] = "$name $path, expected $expected"
            }
            if (scale > 0) m[1] = max(m[1], d / scale)
        }
        assertTrue("$name $path: expected $expected, got $actual", d <= max(rel * scale, absTol))
    }

    private fun WindowFeatures.value(field: String): Double = when (field) {
        "tremor_dps" -> tremorDps
        "peak_hz" -> peakHz
        "prominence" -> prominence
        "slow_dps" -> slowDps
        "motion_dps" -> motionDps
        "mean_rotation_dps" -> meanRotationDps
        "acc_std" -> accStd
        "gravity_change_deg" -> gravityChangeDeg
        "freeze_index" -> freezeIndex
        "cadence_spm" -> cadenceSpm
        "step_regularity" -> stepRegularity
        "arm_swing_dps" -> armSwingDps
        else -> error(field)
    }

    @Test fun versionsMatch() {
        val v = obj(case["versions"])
        assertEquals(StreamFeatures.VERSION, v["features"])
        assertEquals(TremorStream.VERSION, v["tremor"])
        assertEquals(FogDetector.VERSION, v["fog"])
    }

    @Test fun matchesPythonReference() {
        val (t, acc, gyr) = input(case)
        val windows = StreamFeatures.computeFeatures(t, acc, gyr)
        val expected = obj(case["expected"])

        val ew = arr(expected["windows"])
        assertEquals("$name window count", ew.size, windows.size)
        for ((i, e) in ew.withIndex()) {
            val m = obj(e)
            val w = windows[i]
            assertEquals("$name windows[$i].t_end_ms", num(m["t_end_ms"]).toLong(), w.tEndMs)
            for (f in FEATURES) close("windows[$i].$f", f, num(m[f]), w.value(f))
            assertEquals("$name windows[$i].context", m["context"], w.context.value)
        }

        val tp = obj(case["tremor_params"])
        val tremorParams = TremorParams(
            thresholdDps = num(tp["threshold_dps"]),
            personHz = tp["person_hz"]?.let { num(it) },
            minProminence = num(tp["min_prominence"]),
            maxSlowRatio = num(tp["max_slow_ratio"]),
            maxSlow4Dps = num(tp["max_slow4_dps"]),
        )
        val episodes = TremorStream.detect(windows, tremorParams)
        val ee = arr(expected["tremor_episodes"])
        assertEquals("$name episode count", ee.size, episodes.size)
        for ((i, e) in ee.withIndex()) {
            val m = obj(e)
            val ep = episodes[i]
            assertEquals("$name episodes[$i].start_ms", num(m["start_ms"]).toLong(), ep.startMs)
            assertEquals("$name episodes[$i].detected_ms", num(m["detected_ms"]).toLong(), ep.detectedMs)
            assertEquals("$name episodes[$i].end_ms", m["end_ms"]?.let { num(it).toLong() }, ep.endMs)
            close("episodes[$i].hz", "episode.hz", num(m["hz"]), ep.hz)
            assertEquals("$name episodes[$i].strength", m["strength"], ep.strength.name.lowercase())
        }

        val fp = obj(case["fog_params"])
        val fogParams = FogParams(
            freezeIndexThreshold = num(fp["freeze_index_threshold"]),
            armSwingShare = num(fp["arm_swing_share"]),
            cadenceSpm = fp["cadence_spm"]?.let { num(it) },
        )
        val candidates = FogDetector.detect(windows, fogParams)
        val ec = arr(expected["fog_candidates"])
        assertEquals("$name candidate count", ec.size, candidates.size)
        for ((i, e) in ec.withIndex()) {
            val m = obj(e)
            val c = candidates[i]
            assertEquals("$name candidates[$i].detected_ms", num(m["detected_ms"]).toLong(), c.detectedMs)
            assertEquals("$name candidates[$i].reason", m["reason"], c.reason.value)
            assertEquals("$name candidates[$i].turning", m["turning"], c.turning)
        }
    }

    /** Pushing samples one at a time gives exactly the batch windows (2 s and 4 s tremor windows). */
    @Test fun streamingMatchesBatch() {
        val (t, acc, gyr) = input(case)
        for (tw in listOf(2, 4)) {
            val batch = StreamFeatures.computeFeatures(t, acc, gyr, tremorWindowS = tw)
            val ex = StreamFeatureExtractor(tremorWindowS = tw)
            val streamed = t.indices.mapNotNull { i ->
                ex.push(t[i], acc[0][i], acc[1][i], acc[2][i], gyr[0][i], gyr[1][i], gyr[2][i])
            }
            assertEquals("$name tremor window $tw s", batch, streamed)
        }
    }
}

/** Minimal JSON reader for the golden files: objects, arrays, strings, numbers, true/false/null. */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        check(p.i == text.length) { "trailing data at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            check(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                check(s[i] == ':') { "expected ':' at $i" }
                i++
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> error("expected ',' or '}' at ${i - 1}")
                }
            }
        }

        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a.add(value())
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return a
                    else -> error("expected ',' or ']' at ${i - 1}")
                }
            }
        }

        fun str(): String {
            check(s[i] == '"') { "expected string at $i" }
            i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("bad escape at $i")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
