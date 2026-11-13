package com.medicina.shared.pilot

/**
 * B4: the motion of the last [keepMs] in memory (accelerometer m/s² with gravity, gyroscope
 * rad/s), at about 50 Hz. Nothing is written to disk until a press asks for its 90 s.
 * Samples are kept by age, not by count (round 001, #12): a sensor that delivers faster than
 * asked, or two listeners on one sensor, can't push the 30 s before a press out of the ring.
 * The arrays grow when needed and never shrink. Thread-safe: the sensor thread adds, a press reads.
 */
class MotionRing(val keepMs: Long = DEFAULT_KEEP_MS, initialCapacity: Int = 160 * 50) {
    private var cap = maxOf(16, initialCapacity)
    private var t = LongArray(cap)
    private var v = Array(6) { FloatArray(cap) }
    private var head = 0 // oldest
    private var size = 0

    /** Samples added since the start (battery log: proves the sensors kept delivering). */
    @Volatile var added = 0L
        private set

    private fun at(k: Int) = (head + k) % cap

    @Synchronized
    fun add(tMs: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        // Batched samples arrive in order; drop one that goes back in time (clock change mid-batch).
        if (size > 0 && tMs < t[at(size - 1)]) return
        // Forget what is older than keepMs before the newest sample.
        while (size > 0 && t[head] < tMs - keepMs) { head = (head + 1) % cap; size-- }
        if (size == cap) grow()
        val i = at(size)
        t[i] = tMs
        v[0][i] = ax; v[1][i] = ay; v[2][i] = az
        v[3][i] = gx; v[4][i] = gy; v[5][i] = gz
        size++
        added++
    }

    private fun grow() {
        val n = cap * 2
        val nt = LongArray(n)
        val nv = Array(6) { FloatArray(n) }
        for (k in 0 until size) {
            val i = at(k)
            nt[k] = t[i]
            for (c in 0 until 6) nv[c][k] = v[c][i]
        }
        t = nt; v = nv; cap = n; head = 0
    }

    /** Samples with fromMs <= t <= toMs, oldest first. [hasGyro] false writes gyr as null. */
    @Synchronized
    fun snapshot(fromMs: Long, toMs: Long, hasGyro: Boolean = true): MotionSamples {
        val idx = ArrayList<Int>()
        for (k in 0 until size) {
            val i = at(k)
            if (t[i] in fromMs..toMs) idx.add(i)
        }
        val n = idx.size
        val tt = LongArray(n) { t[idx[it]] }
        val acc = Array(3) { c -> FloatArray(n) { v[c][idx[it]] } }
        val gyr = if (hasGyro) Array(3) { c -> FloatArray(n) { v[c + 3][idx[it]] } } else null
        return MotionSamples(tt, acc, gyr)
    }

    @Synchronized
    fun clear() { head = 0; size = 0 }

    companion object {
        /** 150 s: the 30 s before a press, the 60 s after, and a minute of margin for late captures. */
        const val DEFAULT_KEEP_MS = 150_000L
    }
}

class MotionSamples(val tMs: LongArray, val acc: Array<FloatArray>, val gyr: Array<FloatArray>?) {
    val size get() = tMs.size

    companion object {
        val EMPTY = MotionSamples(LongArray(0), Array(3) { FloatArray(0) }, Array(3) { FloatArray(0) })
    }
}
