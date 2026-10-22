package com.medicina.daedaluswatch.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Which permission unlocks the heart-rate sensor. Wear OS 5 and later (API 34+) gate it with
 * the health permission; Wear OS 4 and earlier use BODY_SENSORS.
 */
object HeartRatePermission {
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

    val name: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) READ_HEART_RATE else Manifest.permission.BODY_SENSORS

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
}
