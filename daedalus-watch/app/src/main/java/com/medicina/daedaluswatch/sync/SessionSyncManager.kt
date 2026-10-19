package com.medicina.daedaluswatch.sync

import android.content.Context
import com.medicina.daedaluswatch.haptics.HapticService
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/**
 * Manages session synchronization between watch and phone via Wear OS ChannelClient API.
 * Uses direct file streaming which is more reliable than message-based transfer.
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

        try {
            val fileBytes = csvFile.readBytes()
            var transferSuccess = false

            // 1. Primary: DataClient PutDataMapRequest (synchronizes via Wear OS DataLayer)
            try {
                val putDataMapReq = PutDataMapRequest.create("/session/${csvFile.name}").apply {
                    dataMap.putString("mode", mode)
                    dataMap.putString("fileName", csvFile.name)
                    dataMap.putByteArray("fileData", fileBytes)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }
                val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
                dataClient.putDataItem(putDataReq).await()
                Log.i("SessionSync", "DataClient transfer succeeded for ${csvFile.name}")
                transferSuccess = true
            } catch (e: Exception) {
                Log.w("SessionSync", "DataClient transfer failed for ${csvFile.name}: ${e.message}")
            }

            // 2. MessageClient API (Direct payload message)
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    val phoneNode = nodes.first()
                    val header = "$mode|${csvFile.name}|${fileBytes.size}|".toByteArray(Charsets.UTF_8)
                    val payload = header + fileBytes
                    messageClient.sendMessage(phoneNode.id, "/session/file", payload).await()
                    Log.i("SessionSync", "MessageClient transfer succeeded for ${csvFile.name}")
                    transferSuccess = true
                }
            } catch (e: Exception) {
                Log.w("SessionSync", "MessageClient transfer failed for ${csvFile.name}: ${e.message}")
            }

            // 3. ChannelClient API Stream Fallback
            if (!transferSuccess) {
                try {
                    val nodes = nodeClient.connectedNodes.await()
                    if (nodes.isNotEmpty()) {
                        val phoneNode = nodes.first()
                        val channelPath = "$CHANNEL_PATH/${csvFile.name}"
                        val channel = channelClient.openChannel(phoneNode.id, channelPath).await()
                        val outputStream = channelClient.getOutputStream(channel).await()
                        outputStream.write(fileBytes)
                        outputStream.flush()
                        outputStream.close()
                        channelClient.close(channel).await()
                        Log.i("SessionSync", "ChannelClient transfer succeeded for ${csvFile.name}")
                        transferSuccess = true
                    }
                } catch (e: Exception) {
                    Log.w("SessionSync", "ChannelClient transfer failed for ${csvFile.name}: ${e.message}")
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
                Log.e("SessionSync", "All transfer methods failed for ${csvFile.name} — keeping file for retry")
            }

        } catch (e: Exception) {
            Log.e("SessionSync", "File transfer error for ${csvFile.name}: ${e.message}", e)
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
