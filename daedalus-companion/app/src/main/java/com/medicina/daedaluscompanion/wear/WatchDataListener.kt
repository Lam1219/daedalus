package com.medicina.daedaluscompanion.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import com.medicina.daedaluscompanion.session.SessionManager
import com.medicina.daedaluscompanion.model.EventEntry
import com.medicina.daedaluscompanion.model.EventLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "WatchDataListener"

/**
 * WearableListenerService that receives data from the watch via the DataLayer API.
 *
 * Receives:
 * - Watch IMU data (CSV rows)
 * - Watch HR data (CSV rows)
 * - Watch haptic events
 * - Session metadata
 */
class WatchDataListener : WearableListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                val path = uri.path ?: continue

                Log.i(TAG, "Data event: $path")

                when {
                    path.startsWith("/session/") -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val mode = dataMap.getString("mode")
                        val fileName = dataMap.getString("fileName")
                        val fileData = dataMap.getByteArray("fileData")

                        if (fileData != null && fileName != null) {
                            Log.i(TAG, "Received session file: $fileName (${fileData.size} bytes)")
                            saveSessionFile(fileName, fileData)
                            SessionState.stopSession(mode ?: "unknown", fileName, fileData.size.toLong())
                        }
                    }
                }
            }
        }
    }

    private fun saveSessionFile(fileName: String, data: ByteArray) {
        val sessionsDir = File(applicationContext.getExternalFilesDir(null), "daedalus/sessions")
        if (!sessionsDir.exists()) sessionsDir.mkdirs()

        val file = File(sessionsDir, fileName)
        file.writeBytes(data)
        Log.i(TAG, "Session file saved: ${file.absolutePath}")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message received: path=${messageEvent.path}, size=${messageEvent.data.size}")

        when (messageEvent.path) {
            "/session/file" -> {
                val data = messageEvent.data
                // Format: mode|fileName|fileSize|fileData
                val pipeIndices = mutableListOf<Int>()
                for (i in data.indices) {
                    if (data[i] == '|'.code.toByte()) {
                        pipeIndices.add(i)
                    }
                    if (pipeIndices.size == 3) break
                }

                if (pipeIndices.size == 3) {
                    val mode = data.sliceArray(0 until pipeIndices[0]).toString(Charsets.UTF_8)
                    val fileName = data.sliceArray((pipeIndices[0] + 1) until pipeIndices[1]).toString(Charsets.UTF_8)
                    val fileData = data.sliceArray((pipeIndices[2] + 1) until data.size)

                    Log.i(TAG, "Received session file: $fileName (${fileData.size} bytes, mode=$mode)")
                    saveSessionFile(fileName, fileData)
                    SessionState.stopSession(mode, fileName, fileData.size.toLong())
                }
            }
            "/event" -> handleEventMessage(messageEvent.data)
            "/session_start" -> handleSessionStart(messageEvent.data)
            "/session_end" -> handleSessionEnd()
            "/request_export" -> handleExportRequest(this)
        }
    }

    private fun handleImuData(data: ByteArray) {
        // Parse CSV row: epoch_ms,elapsed_ms,ax,ay,az,gx,gy,gz,motion_intensity
        val line = String(data)
        // For Phase 0, we just log — full processing happens in the Python library
        Log.d(TAG, "IMU data received: $line")
    }

    private fun handleHrData(data: ByteArray) {
        val line = String(data)
        Log.d(TAG, "HR data received: $line")
    }

    private fun handleHapticData(data: ByteArray) {
        val line = String(data)
        Log.d(TAG, "Haptic data received: $line")
    }

    private fun handleMetadata(data: ByteArray) {
        val json = String(data)
        Log.i(TAG, "Metadata received: $json")
    }

    private fun handleEventMessage(data: ByteArray) {
        val parts = String(data).split("|")
        if (parts.size >= 2) {
            val eventName = parts[0]
            val source = parts.getOrNull(1) ?: "watch"
            val notes = parts.getOrNull(2) ?: ""

            if (EventLabels.ALL_EVENTS.contains(eventName)) {
                SessionManager.getInstance(applicationContext).logEvent(EventEntry(
                    epochMs = System.currentTimeMillis(),
                    eventName = eventName,
                    source = source,
                    notes = notes,
                ))
            }
        }
    }

    private fun handleSessionStart(data: ByteArray) {
        val parts = String(data).split("|")
        if (parts.size >= 2) {
            val subjectId = parts[0]
            val sessionId = parts[1]
            SessionManager.getInstance(applicationContext).startSession(subjectId, sessionId)
        }
    }

    private fun handleSessionEnd() {
        serviceScope.launch {
            SessionManager.getInstance(applicationContext).endSession()
        }
    }

    private fun handleExportRequest(context: Context) {
        serviceScope.launch {
            SessionManager.getInstance(context).exportSession()
        }
    }
}

/**
 * DataLayer client for sending data from the phone to the watch and vice versa.
 */
class WatchDataClient(context: Context) {

    private val nodeClient = Wearable.getNodeClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val dataClient = Wearable.getDataClient(context)

    /** Check if a watch is connected */
    suspend fun isConnected(): Boolean {
        val nodes = nodeClient.connectedNodes.await()
        return nodes.isNotEmpty()
    }

    /** Connect to watch (triggers node discovery) */
    suspend fun connect() {
        // Wear OS connections are automatic when paired.
        // This method just verifies connectivity.
        val connected = isConnected()
        if (!connected) {
            Log.w(TAG, "No watch connected. Ensure watch is paired in Wear OS app.")
        }
    }

    /** Disconnect from watch (no-op for Wear OS) */
    fun disconnect() {
        // Wear OS manages connections automatically.
        Log.i(TAG, "Watch disconnect requested (Wear OS manages connections)")
    }

    /** Send an event to the watch to log */
    suspend fun sendEventToWatch(eventName: String, source: String = "phone", notes: String = "") {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch found")
            return
        }

        val message = "$eventName|$source|$notes"
        for (node in nodes) {
            messageClient.sendMessage(node.id, "/event", message.toByteArray())
        }
    }

    /** Send session start command to the watch */
    suspend fun sendSessionStart(subjectId: String, sessionId: String) {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch found")
            return
        }

        val message = "$subjectId|$sessionId"
        for (node in nodes) {
            messageClient.sendMessage(node.id, "/session_start", message.toByteArray())
        }
    }

    /** Send session stop command to the watch */
    suspend fun sendSessionStop() {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) return
        for (node in nodes) {
            messageClient.sendMessage(node.id, "/session_end", ByteArray(0))
        }
    }

    suspend fun requestExport() {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) return
        for (node in nodes) {
            messageClient.sendMessage(node.id, "/request_export", ByteArray(0))
        }
    }
}

// Extension function for await on Tasks (self-contained, no external dependency)
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }
