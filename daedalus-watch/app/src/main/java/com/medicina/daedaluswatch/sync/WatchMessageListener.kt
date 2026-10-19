package com.medicina.daedaluswatch.sync

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.medicina.daedaluswatch.haptics.CueRates
import com.medicina.daedaluswatch.haptics.HapticService
import com.medicina.daedaluswatch.sensor.StandWatchService

private const val TAG = "WatchMessageListener"

/**
 * Listens for cue control messages from the companion phone app on /session/control:
 * - `start|<mode>|<bpm>` starts a cue at the rate chosen on the phone (bpm optional)
 * - `rate|<mode>|<bpm>`  saves the phone's rate for a mode; a running cue of that mode follows it
 * - `stop` / `complete`  stops the cue
 * - `pref|stand|1|0`     turns automatic stand-up detection on or off
 */
class WatchMessageListener : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message received from phone: path=${messageEvent.path}, size=${messageEvent.data.size}")

        if (messageEvent.path == "/session/control") {
            val dataStr = String(messageEvent.data, Charsets.UTF_8)
            Log.i(TAG, "Received CONTROL command from phone: $dataStr")
            val parts = dataStr.split("|")
            val mode = parts.getOrNull(1)?.lowercase() ?: "stress"
            val bpm = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
            when (parts.firstOrNull()) {
                "start" -> {
                    if (bpm != null) CueRates.set(this, mode, bpm)
                    startHapticService(mode, bpm ?: CueRates.get(this, mode))
                }
                "rate" -> if (bpm != null) {
                    CueRates.set(this, mode, bpm)
                    if (HapticService.isServiceRunning && HapticService.runningMode == mode) {
                        startService(Intent(this, HapticService::class.java).apply {
                            action = HapticService.ACTION_SET_BPM
                            putExtra(HapticService.EXTRA_BPM, bpm)
                            putExtra(HapticService.EXTRA_MODE, mode)
                        })
                    }
                }
                "pref" -> if (parts.getOrNull(1) == "stand") {
                    StandWatchService.setEnabled(this, parts.getOrNull(2) == "1")
                }
                "stop", "complete" -> {
                    stopHapticService()
                }
            }
        }
    }

    private fun startHapticService(mode: String, bpm: Int) {
        if (HapticService.isServiceRunning) {
            Log.w(TAG, "HapticService is already running, ignoring start request")
            return
        }
        val intent = Intent(this, HapticService::class.java).apply {
            action = HapticService.ACTION_START
            putExtra(HapticService.EXTRA_BPM, bpm)
            putExtra(HapticService.EXTRA_MODE, mode)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopHapticService() {
        if (!HapticService.isServiceRunning) {
            Log.w(TAG, "HapticService is not running, ignoring stop request")
            return
        }
        val intent = Intent(this, HapticService::class.java).apply {
            action = HapticService.ACTION_STOP
        }
        startService(intent)
    }
}
