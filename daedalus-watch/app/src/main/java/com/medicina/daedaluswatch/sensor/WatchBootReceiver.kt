package com.medicina.daedaluswatch.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.medicina.daedaluswatch.pilot.PilotPresses
import com.medicina.daedaluswatch.pilot.PilotStore

/**
 * Round 001, #13: after a reboot or an app update in study mode, start the motion ring and the
 * stand-up check again, and finish any press a reboot interrupted (study mode only: round 002, #6).
 */
class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        onBoot(intent.action, PilotStore.active(context),
            restart = { StandWatchService.refresh(context) },
            recover = { PilotPresses.recoverOrphans(context) })
    }

    companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

        /**
         * Round 002, #6: only while the pilot study is active. With the study switch off, a boot
         * does nothing new: the stand-up prompt starts as before this branch, from the app.
         */
        fun onBoot(action: String?, pilotActive: Boolean, restart: () -> Unit, recover: () -> Unit) {
            if (action !in ACTIONS) return
            if (pilotActive) restart()
            // Round 003, #7: unfinished presses are finished whatever the switch says; this only
            // writes files and starts nothing.
            recover()
        }
    }
}
