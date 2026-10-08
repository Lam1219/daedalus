package com.medicina.daedaluswatch.sync

import android.content.Context
import android.net.Uri
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

    companion object {
        const val CHANNEL_PATH = "/session/file"
        const val CONTROL_PATH = "/session/control"
    }

    /** Notify phone that a session has started */
    fun notifySessionStart(mode: String) {
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    val message = "start|$mode".toByteArray()
                    messageClient.sendMessage(node.id, CONTROL_PATH, message)
                }
                Log.i("SessionSync", "Session start notification sent: mode=$mode")
            } catch (e: Exception) {
                Log.e("SessionSync", "Failed to send start notification: ${e.message}")
            }
        }
    }

    /** Transfer session file to phone and delete local copy on success */
    fun transferAndPurgeFile(csvFile: File, mode: String) {
        scope.launch {
            if (!csvFile.exists()) {
                Log.e("SessionSync", "File does not exist: ${csvFile.name}")
                return@launch
            }

            try {
                // Find connected phone node
                val nodes = nodeClient.connectedNodes.await()
                Log.i("SessionSync", "Found ${nodes.size} connected node(s)")

                if (nodes.isEmpty()) {
                    Log.w("SessionSync", "No connected phone nodes — keeping file locally")
                    return@launch
                }

                val phoneNode = nodes.first()
                Log.i("SessionSync", "Sending to node: ${phoneNode.id}, file: ${csvFile.name} (${csvFile.length()} bytes)")

                // Open channel and send file
                val channelPath = "$CHANNEL_PATH/${csvFile.name}"
                val channel = channelClient.openChannel(phoneNode.id, channelPath).await()

                val fileUri = Uri.fromFile(csvFile)
                channelClient.sendFile(channel, fileUri, 0, csvFile.length()).await()

                // Close channel
                channelClient.close(channel).await()

                // SUCCESS — delete local file
                val deleted = csvFile.delete()
                Log.i("SessionSync", "File transferred successfully: ${csvFile.name}, deleted=$deleted")

                // Notify phone that transfer is complete
                val message = "complete|$mode|${csvFile.name}|${csvFile.length()}".toByteArray()
                messageClient.sendMessage(phoneNode.id, CONTROL_PATH, message)

            } catch (e: Exception) {
                Log.e("SessionSync", "File transfer failed: ${e.message}")
                // File is NOT deleted — can retry later
            }
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
