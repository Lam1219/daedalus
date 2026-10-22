package com.medicina.daedaluswatch.sync

import android.content.Context
import com.medicina.daedaluswatch.haptics.HapticService
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/**
 * Sends finished session files from the watch to the phone, one file at a time: a ChannelClient
 * stream (any size), then a single message for small files if the phone hasn't confirmed. A file
 * is deleted from the watch only once the phone acknowledges that it saved it (/session/ack);
 * otherwise it stays for the next sync. Data Layer items are not used (they kept full copies of
 * the CSV on both devices forever); old ones are deleted.
 */
class SessionSyncManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelClient = Wearable.getChannelClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val dataClient = Wearable.getDataClient(context)

    init {
        syncAllPendingFiles()
    }

    companion object {
        const val CHANNEL_PATH = "/session/file"
        const val CONTROL_PATH = "/session/control"
        /** Files being sent right now, shared by every instance, so no file is sent twice at once. */
        private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** Message payloads above this go by channel only. */
        private const val MESSAGE_LIMIT = 90_000
        const val ACK_PATH = "/session/ack"
        /** How long to wait for the phone to confirm each attempt. */
        private const val ACK_TIMEOUT_MS = 15_000L
        private val acks = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        /** Called by WatchMessageListener when the phone confirms it saved [fileName]. */
        fun onAck(fileName: String) {
            acks[fileName]?.complete(Unit)
        }
    }

    /** Notify phone that a session has started */
    fun notifySessionStart(mode: String) {
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                Log.i("SessionSync", "Found ${nodes.size} connected phone node(s) for start notification")
                for (node in nodes) {
                    val message = "start|$mode".toByteArray(Charsets.UTF_8)
                    messageClient.sendMessage(node.id, CONTROL_PATH, message).await()
                }
                Log.i("SessionSync", "Session start notification sent: mode=$mode")
            } catch (e: Exception) {
                Log.e("SessionSync", "Failed to send start notification: ${e.message}")
            }
        }
    }

    /** Sync all pending session files in local sessions directory to phone */
    fun syncAllPendingFiles() {
        scope.launch {
            deleteOldDataItems()
            val sessionsDir = File(context.filesDir, "sessions")
            if (!sessionsDir.exists()) return@launch

            // Never send the file a running cue is still writing: sending purges it from the watch,
            // and everything recorded after that would be lost.
            val recording = HapticService.recordingFileName
            val files = sessionsDir.listFiles()?.filter { it.isFile && it.extension == "csv" && it.name != recording } ?: return@launch
            if (files.isEmpty()) return@launch
            
            Log.i("SessionSync", "Found ${files.size} pending session file(s) to transfer")

            for (file in files) {
                val mode = file.name.substringAfter("session_").substringBefore("_")
                transferAndPurgeFileInternal(file, mode)
            }
        }
    }

    /** Transfer session file to phone and delete local copy on success */
    fun transferAndPurgeFile(csvFile: File, mode: String, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                transferAndPurgeFileInternal(csvFile, mode)
            } finally {
                onComplete()
            }
        }
    }

    private suspend fun transferAndPurgeFileInternal(csvFile: File, mode: String) {
        if (!csvFile.exists()) {
            Log.e("SessionSync", "File does not exist: ${csvFile.name}")
            return
        }
        // Another sender (app screen, service, retry) already has this file.
        if (!inFlight.add(csvFile.name)) return

        try {
            val fileBytes = csvFile.readBytes()
            var transferSuccess = false
            val phoneNode = nodeClient.connectedNodes.await().firstOrNull()
            if (phoneNode == null) {
                Log.w("SessionSync", "No phone connected — keeping ${csvFile.name} for later")
                return
            }

            val ack = CompletableDeferred<Unit>().also { acks[csvFile.name] = it }

            // 1. ChannelClient stream: works for any size.
            try {
                val channel = channelClient.openChannel(phoneNode.id, "$CHANNEL_PATH/${csvFile.name}").await()
                channelClient.getOutputStream(channel).await().use { it.write(fileBytes); it.flush() }
                channelClient.close(channel).await()
                Log.i("SessionSync", "ChannelClient sent ${csvFile.name}, waiting for the phone")
            } catch (e: Exception) {
                Log.w("SessionSync", "ChannelClient transfer failed for ${csvFile.name}: ${e.message}")
            }
            transferSuccess = withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } != null

            // 2. Small files only: one message, if the phone hasn't confirmed the channel copy.
            if (!transferSuccess && fileBytes.size <= MESSAGE_LIMIT) {
                try {
                    val header = "$mode|${csvFile.name}|${fileBytes.size}|".toByteArray(Charsets.UTF_8)
                    messageClient.sendMessage(phoneNode.id, "/session/file", header + fileBytes).await()
                    Log.i("SessionSync", "MessageClient sent ${csvFile.name}, waiting for the phone")
                    transferSuccess = withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } != null
                } catch (e: Exception) {
                    Log.w("SessionSync", "MessageClient transfer failed for ${csvFile.name}: ${e.message}")
                }
            }

            if (transferSuccess) {
                // SUCCESS — delete local file from watch
                val deleted = csvFile.delete()
                Log.i("SessionSync", "File transferred successfully and purged: ${csvFile.name}, deleted=$deleted")

                // Notify phone that transfer is complete
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    val message = "complete|$mode|${csvFile.name}|${fileBytes.size}".toByteArray()
                    messageClient.sendMessage(node.id, CONTROL_PATH, message)
                }
            } else {
                Log.e("SessionSync", "Phone didn't confirm ${csvFile.name} — keeping it on the watch for the next sync")
            }

        } catch (e: Exception) {
            Log.e("SessionSync", "File transfer error for ${csvFile.name}: ${e.message}", e)
        } finally {
            acks.remove(csvFile.name)
            inFlight.remove(csvFile.name)
        }
    }

    /** Removes session copies left in the Data Layer by earlier versions (they held the full CSV). */
    private suspend fun deleteOldDataItems() {
        try {
            dataClient.deleteDataItems(android.net.Uri.parse("wear://*/session/"), DataClient.FILTER_PREFIX).await()
        } catch (e: Exception) {
            Log.w("SessionSync", "Couldn't delete old Data Layer items: ${e.message}")
        }
    }
}

/** Extension function to await Wear OS Tasks */
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
        cont.invokeOnCancellation { }
    }
