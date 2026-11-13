package com.medicina.shared.pilot

/**
 * B4: the last [capacity] motion samples in memory (accelerometer m/s² with gravity, gyroscope
 * rad/s), at about 50 Hz. Nothing is written to disk until a press asks for its 90 s.
 * Thread-safe: the sensor thread adds, the press snapshot reads.
 */
class MotionRing(val capacity: Int = DEFAULT_CAPACITY) {
    private val t = LongArray(capacity)
    private val v = Array(6) { FloatArray(capacity) }
    private var next = 0
    private var size = 0

    /** Samples added since the start (battery log: proves the sensors kept delivering). */
    @Volatile var added = 0L
        private set

    @Synchronized
    fun add(tMs: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        // Batched samples arrive in order; drop one that goes back in time (clock change mid-batch).
        if (size > 0 && tMs < t[(next - 1 + capacity) % capacity]) return
        t[next] = tMs
        v[0][next] = ax; v[1][next] = ay; v[2][next] = az
        v[3][next] = gx; v[4][next] = gy; v[5][next] = gz
        next = (next + 1) % capacity
        if (size < capacity) size++
        added++
    }

    /** Samples with fromMs <= t <= toMs, oldest first. [hasGyro] false writes gyr as null. */
    @Synchronized
    fun snapshot(fromMs: Long, toMs: Long, hasGyro: Boolean = true): MotionSamples {
        val start = (next - size + capacity) % capacity
        val idx = ArrayList<Int>()
        for (k in 0 until size) {
            val i = (start + k) % capacity
            if (t[i] in fromMs..toMs) idx.add(i)
        }
        val n = idx.size
        val tt = LongArray(n) { t[idx[it]] }
        val acc = Array(3) { c -> FloatArray(n) { v[c][idx[it]] } }
        val gyr = if (hasGyro) Array(3) { c -> FloatArray(n) { v[c + 3][idx[it]] } } else null
        return MotionSamples(tt, acc, gyr)
    }

    @Synchronized
    fun clear() { next = 0; size = 0 }

    companion object {
        /** 120 s at 50 Hz: the 30 s before a press, the 60 s after, and room for batching delays. */
        const val DEFAULT_CAPACITY = 120 * 50
    }
}

class MotionSamples(val tMs: LongArray, val acc: Array<FloatArray>, val gyr: Array<FloatArray>?) {
    val size get() = tMs.size
}
