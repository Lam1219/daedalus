package com.medicina.daedaluswatch.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.medicina.daedaluswatch.pilot.PilotPresses
import com.medicina.daedaluswatch.pilot.PilotStore
import com.medicina.daedaluswatch.pilot.pilotFolderExists

/**
 * After a reboot or an app update:
 * - in study mode, start the motion ring and the stand-up check again (round 001 #13, round 002 #6);
 * - whatever the switch says, finish presses a reboot interrupted, which only writes files
 *   (round 003, #7), and only when this watch has a pilot folder (round 004, #4).
 * With the study off and no pilot folder, a boot does nothing at all.
 */
class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        onBoot(intent.action, PilotStore.active(context), pilotFolderExists(context),
            restart = { StandWatchService.refresh(context) },
            recover = { PilotPresses.recoverOrphans(context) })
    }

    companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

        /** See the class comment. [recover] is the only thing that touches PilotPresses. */
        fun onBoot(action: String?, pilotActive: Boolean, folderExists: Boolean, restart: () -> Unit, recover: () -> Unit) {
            if (action !in ACTIONS) return
            if (pilotActive) restart()
            if (folderExists) recover()
        }
    }
}
