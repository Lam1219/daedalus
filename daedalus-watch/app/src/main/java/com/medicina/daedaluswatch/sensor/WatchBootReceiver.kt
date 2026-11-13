package com.medicina.daedaluswatch.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.medicina.daedaluswatch.pilot.PilotPresses
import com.medicina.daedaluswatch.pilot.PilotStore

/**
 * Round 001, #13: after a reboot or an app update, start stand-up detection and, in study mode,
 * the motion ring again, and finish any press a reboot interrupted.
 */
class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        StandWatchService.refresh(context)
        if (PilotStore.active(context)) PilotPresses.recoverOrphans(context)
    }

    companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
