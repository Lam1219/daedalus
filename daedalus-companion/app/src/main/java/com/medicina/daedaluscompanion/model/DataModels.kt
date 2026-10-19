package com.medicina.daedaluscompanion.model

/** Single RR interval sample from Cycplus H2 chest strap */
data class EcgSample(
    val epochMs: Long,
    val rrMs: Float? = null,
    val hrBpm: Float? = null,
    val beatQuality: Float = 1f,
) {
    val ecgRaw: Float = Float.NaN
}
