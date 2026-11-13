package com.medicina.daedaluswatch.pilot

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.medicina.shared.pilot.BeatClock
import com.medicina.shared.pilot.BeatMessage
import com.medicina.shared.pilot.PilotLink
import com.medicina.shared.pilot.Player
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PilotPhoneLink"

/** The watch's side of the pilot messages: beats played by the phone, and status queries. */
object PilotPhoneLink {
    private class Pending(val sentAtMs: Long, val onStarted: (Long?, String) -> Unit, val onFailed: (String) -> Unit)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Asks the phone to play [player]; [onStarted] gets the first click or pulse on the watch's clock. */
    fun startBeat(c: Context, pressId: String, player: Player, bpm: Int, maxMs: Long, onStarted: (Long?, String) -> Unit, onFailed: (String) -> Unit) {
        val sentAt = System.currentTimeMillis()
        pending[pressId] = Pending(sentAt, onStarted, onFailed)
        send(c, PilotLink.BEAT_PATH, BeatMessage.Start(pressId, player, bpm, maxMs).encode()) { reason ->
            pending.remove(pressId)?.onFailed?.invoke(reason)
        }
    }

    fun stopBeat(c: Context, pressId: String) {
        pending.remove(pressId)
        send(c, PilotLink.BEAT_PATH, BeatMessage.Stop(pressId).encode()) {}
    }

    /** Round 001, #24: the person paused or resumed the cue; the phone's beat follows. */
    fun pauseBeat(c: Context, pressId: String, paused: Boolean) {
        send(c, PilotLink.BEAT_PATH, BeatMessage.Pause(pressId, paused).encode()) {}
    }

    /** Asks the phone for fresh earbuds, steps and movement; the reply arrives on STATUS_PATH. */
    fun query(c: Context, queryId: String = System.currentTimeMillis().toString()) {
        send(c, PilotLink.QUERY_PATH, queryId) {}
    }

    /** A beat reply from the phone (WatchMessageListener). */
    fun onBeatMessage(m: BeatMessage, receivedAtMs: Long) {
        when (m) {
            is BeatMessage.Started -> {
                val p = pending[m.pressId] ?: return
                // startMs < 0: playing, start not measured yet (a second "started" may follow).
                val t = if (m.startMs < 0) null else BeatClock.beatStartOnWatch(p.sentAtMs, receivedAtMs, m.receivedMs, m.startMs, m.sentMs)
                p.onStarted(t, "phone_${m.source}+rtt")
            }
            is BeatMessage.Failed -> pending[m.pressId]?.onFailed?.invoke("phone_${m.reason}")
            else -> Unit
        }
    }

    private fun send(c: Context, path: String, payload: String, onFail: (String) -> Unit) {
        val app = c.applicationContext
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val messages = Wearable.getMessageClient(app)
        fun to(node: String) {
            messages.sendMessage(node, path, bytes)
                .addOnFailureListener { Log.w(TAG, "$path failed: ${it.message}"); onFail("send_failed") }
        }
        val known = PilotStore.phoneNodeId
        if (known != null) return to(known)
        Wearable.getNodeClient(app).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                if (node == null) onFail("phone_unreachable") else { PilotStore.phoneNodeId = node.id; to(node.id) }
            }
            .addOnFailureListener { onFail("phone_unreachable") }
    }
}
