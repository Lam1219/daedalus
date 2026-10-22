package com.medicina.daedaluscompanion.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import com.medicina.daedaluscompanion.data.CloudBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "WatchDataListener"

/**
 * WearableListenerService that receives session data from the watch via the DataLayer API:
 * session start/stop control messages and the recorded session CSV file (sent redundantly via
 * DataClient, MessageClient, and ChannelClient by the watch, whichever succeeds first).
 */
class WatchDataListener : WearableListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val dataItems = dataEvents.mapNotNull { event ->
            if (event.type == DataEvent.TYPE_CHANGED) event.dataItem.freeze() else null
        }
        serviceScope.launch {
            for (dataItem in dataItems) {
                processDataItem(dataItem)
            }
        }
    }

    private suspend fun processDataItem(dataItem: DataItem) {
        val path = dataItem.uri.path ?: return
        Log.i(TAG, "Data event received: $path")

        val dataMap = try { DataMapItem.fromDataItem(dataItem).dataMap } catch (e: Exception) { null }

        var fileName = dataMap?.getString("fileName")
            ?: dataMap?.getString("name")
            ?: dataMap?.getString("file_name")
            ?: dataMap?.getString("filename")

        if (fileName.isNullOrEmpty() || !fileName.contains(".")) {
            val lastSegment = path.substringAfterLast("/")
            if (lastSegment.contains("session") || lastSegment.endsWith(".csv")) {
                fileName = lastSegment
            }
        }

        val mode = dataMap?.getString("mode")
            ?: if (!fileName.isNullOrEmpty()) extractModeFromFileName(fileName) else "unknown"

        if (fileName.isNullOrEmpty()) {
            fileName = "session_${mode}_${System.currentTimeMillis()}.csv"
        }

        var fileData: ByteArray? = dataMap?.getByteArray("fileData")
            ?: dataMap?.getByteArray("data")
            ?: dataMap?.getByteArray("bytes")
            ?: dataMap?.getByteArray("file")
            ?: dataItem.data

        if (fileData == null || fileData.isEmpty()) {
            // Try Asset / DataItemAsset extraction
            val asset: Asset? = dataMap?.getAsset("fileAsset")
                ?: dataMap?.getAsset("asset")
                ?: dataMap?.getAsset("file")

            val dataItemAsset: DataItemAsset? = if (asset == null) dataItem.assets.values.firstOrNull() else null

            if (asset != null) {
                try {
                    val fdResponse = Wearable.getDataClient(applicationContext).getFdForAsset(asset).await()
                    fileData = fdResponse.inputStream.use { it.readBytes() }
                    Log.i(TAG, "Read ${fileData.size} bytes from Asset for $fileName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read Asset for $fileName: ${e.message}", e)
                }
            } else if (dataItemAsset != null) {
                try {
                    val fdResponse = Wearable.getDataClient(applicationContext).getFdForAsset(dataItemAsset).await()
                    fileData = fdResponse.inputStream.use { it.readBytes() }
                    Log.i(TAG, "Read ${fileData.size} bytes from DataItemAsset for $fileName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read DataItemAsset for $fileName: ${e.message}", e)
                }
            }
        }

        if (fileData != null && fileData.isNotEmpty()) {
            Log.i(TAG, "Successfully extracted session file from DataItem: $fileName (${fileData.size} bytes)")
            saveSessionFile(fileName, fileData)
            SessionState.stopSession(mode, fileName, fileData.size.toLong())
        } else {
            Log.w(TAG, "DataItem at $path did not contain file content or asset")
        }
    }

    private fun saveSessionFile(fileName: String, data: ByteArray) {
        // Single canonical location — must match HomeScreen.kt's sessionsDir().
        val sessionsDir = File(applicationContext.getExternalFilesDir(null), "daedalus/sessions")
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        File(sessionsDir, fileName).writeBytes(data)

        Log.i(TAG, "Session file saved to ${sessionsDir.absolutePath}")
        // Back it up to the signed-in account (no-op for guests).
        CloudBackup.syncAsync(applicationContext)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        Log.i(TAG, "Channel opened: path=${channel.path}")
        val fileName = channel.path.substringAfterLast("/")
        if (fileName.endsWith(".csv") || fileName.contains("session")) {
            serviceScope.launch {
                receiveChannelFile(channel, fileName)
            }
        }
    }

    private suspend fun receiveChannelFile(channel: ChannelClient.Channel, fileName: String) {
        val channelClient = Wearable.getChannelClient(this)
        try {
            val inputStream = channelClient.getInputStream(channel).await()
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            channelClient.close(channel).await()

            val mode = extractModeFromFileName(fileName)
            Log.i(TAG, "Received session file via ChannelClient: $fileName (${fileBytes.size} bytes)")
            saveSessionFile(fileName, fileBytes)
            SessionState.stopSession(mode, fileName, fileBytes.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed receiving channel file $fileName: ${e.message}", e)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message received: path=${messageEvent.path}, size=${messageEvent.data.size}")

        when {
            messageEvent.path.startsWith("/session/file") || messageEvent.path.startsWith("/session_file") || messageEvent.path == "/session" -> {
                handleSessionFileMessage(messageEvent)
            }
            messageEvent.path == "/session/control" || messageEvent.path == "/session_control" -> {
                handleSessionControlMessage(messageEvent)
            }
        }
    }

    private fun handleSessionFileMessage(messageEvent: MessageEvent) {
        val data = messageEvent.data
        if (data.isEmpty()) return

        // Format 1: mode|fileName|fileSize|fileData
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

            if (fileData.isNotEmpty()) {
                Log.i(TAG, "Received session file via binary message: $fileName (${fileData.size} bytes, mode=$mode)")
                saveSessionFile(fileName, fileData)
                SessionState.stopSession(mode, fileName, fileData.size.toLong())
                return
            }
        }

        // Format 2: fileName|chunkIndex|totalSize|base64Data (chunked message)
        val dataStr = String(data, Charsets.UTF_8)
        val chunkParts = dataStr.split("|", limit = 4)
        if (chunkParts.size >= 4 && chunkParts[1].toIntOrNull() != null) {
            val fileName = chunkParts[0]
            val chunkIndex = chunkParts[1].toIntOrNull() ?: 0
            val chunkData = try { android.util.Base64.decode(chunkParts[3], android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
            if (chunkData != null) {
                SessionState.receiveFileChunk(applicationContext, fileName, chunkIndex, chunkData)
                return
            }
        }

        // Format 3: fileName|fileContent
        if (dataStr.contains("|")) {
            val fileName = dataStr.substringBefore("|")
            val content = dataStr.substringAfter("|")
            if (fileName.endsWith(".csv") || fileName.contains("session")) {
                val mode = extractModeFromFileName(fileName)
                val contentBytes = content.toByteArray(Charsets.UTF_8)
                Log.i(TAG, "Received session file via text message: $fileName (${contentBytes.size} bytes)")
                saveSessionFile(fileName, contentBytes)
                SessionState.stopSession(mode, fileName, contentBytes.size.toLong())
                return
            }
        }

        // Format 4: Path has fileName, data is file bytes directly
        val pathFileName = messageEvent.path.substringAfterLast("/")
        if (pathFileName.endsWith(".csv") || pathFileName.contains("session_")) {
            val mode = extractModeFromFileName(pathFileName)
            Log.i(TAG, "Received session file via path message: $pathFileName (${data.size} bytes)")
            saveSessionFile(pathFileName, data)
            SessionState.stopSession(mode, pathFileName, data.size.toLong())
            return
        }
    }

    private fun handleSessionControlMessage(messageEvent: MessageEvent) {
        val dataStr = String(messageEvent.data, Charsets.UTF_8)
        val parts = dataStr.split("|")
        when (parts.firstOrNull()) {
            "start" -> {
                val mode = parts.getOrNull(1) ?: "unknown"
                SessionState.startSession(mode)
            }
            "complete", "stop" -> {
                val mode = parts.getOrNull(1) ?: "unknown"
                val fileName = parts.getOrNull(2) ?: ""
                val fileSize = parts.getOrNull(3)?.toLongOrNull() ?: 0
                SessionState.stopSession(mode, fileName, fileSize)
            }
        }
    }

    private fun extractModeFromFileName(fileName: String): String {
        val parts = fileName.split("_")
        return if (parts.size >= 2) parts[1] else "unknown"
    }
}

/**
 * DataLayer client for sending session commands from the phone to the watch.
 */
class WatchDataClient(context: Context) {

    private val nodeClient = Wearable.getNodeClient(context)
    private val messageClient = Wearable.getMessageClient(context)

    /** Check if a watch is connected */
    suspend fun isConnected(): Boolean {
        val nodes = nodeClient.connectedNodes.await()
        return nodes.isNotEmpty()
    }

    /** Connect to watch (triggers node discovery) */
    suspend fun connect() {
        // Wear OS connections are automatic when paired.
        // This method just verifies connectivity.
        if (!isConnected()) {
            Log.w(TAG, "No watch connected. Ensure watch is paired in Wear OS app.")
        }
    }

    /** Disconnect from watch (no-op for Wear OS) */
    fun disconnect() {
        // Wear OS manages connections automatically.
        Log.i(TAG, "Watch disconnect requested (Wear OS manages connections)")
    }

    /** Send session start command to the watch, with the pulse rate chosen on the phone. */
    suspend fun sendSessionStart(mode: String, bpm: Int) {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch found")
            return
        }

        val controlBytes = "start|$mode|$bpm".toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, "/session/control", controlBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending start command to watch ${node.displayName}: ${e.message}")
            }
        }
        Log.i(TAG, "Sent session start command ($mode @ $bpm) to ${nodes.size} watch node(s)")
    }

    /**
     * Tell the watch the pulse rate for a mode. The watch saves it (used by "I'm stuck")
     * and restarts a running cue of that mode at the new rate.
     */
    suspend fun sendCueRate(mode: String, bpm: Int) {
        val nodes = nodeClient.connectedNodes.await()
        val bytes = "rate|$mode|$bpm".toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, "/session/control", bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending cue rate to watch ${node.displayName}: ${e.message}")
            }
        }
    }

    /** Turn automatic stand-up detection on the watch on or off. */
    suspend fun sendStandPrompt(enabled: Boolean) {
        val nodes = nodeClient.connectedNodes.await()
        val bytes = "pref|stand|${if (enabled) 1 else 0}".toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, "/session/control", bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending stand-up setting to watch ${node.displayName}: ${e.message}")
            }
        }
    }

    /** Send session stop command to the watch */
    suspend fun sendSessionStop() {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch found")
            return
        }

        val stopControlBytes = "stop".toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, "/session/control", stopControlBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending stop command to watch ${node.displayName}: ${e.message}")
            }
        }
        Log.i(TAG, "Sent session stop command to ${nodes.size} watch node(s)")
    }
}

// Extension function for await on Tasks (self-contained, no external dependency)
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }
