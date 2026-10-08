package com.medicina.daedaluscompanion.wear

import android.util.Log
import com.google.android.gms.wearable.*
import com.medicina.daedaluscompanion.wear.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "SessionSyncListener"

/**
 * Receives session sync messages from the watch:
 * - /session/start → session started
 * - /session/stop → session stopped, file incoming
 * - /session/file → file chunk received
 */
class SessionSyncListener : WearableListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message received: path=${messageEvent.path}, size=${messageEvent.data.size}")

        when (messageEvent.path) {
            "/session/start" -> {
                val mode = String(messageEvent.data)
                Log.i(TAG, "Session START received: mode=$mode")
                SessionState.startSession(mode)
                Log.i(TAG, "SessionState.isSessionActive = ${SessionState.isSessionActive}")
            }
            "/session/stop" -> {
                val data = String(messageEvent.data)
                Log.i(TAG, "Session STOP received: $data")
                val parts = data.split("|")
                if (parts.size >= 3) {
                    val mode = parts[0]
                    val fileName = parts[1]
                    val fileSize = parts[2].toLongOrNull() ?: 0
                    SessionState.stopSession(mode, fileName, fileSize)
                    Log.i(TAG, "Session stopped: $mode, $fileName ($fileSize bytes)")
                    Log.i(TAG, "SessionState.isSessionActive = ${SessionState.isSessionActive}")
                } else {
                    Log.e(TAG, "Invalid stop message format: $data")
                }
            }
            "/session/file" -> {
                val dataStr = String(messageEvent.data)
                val parts = dataStr.split("|", limit = 4)
                Log.d(TAG, "File chunk received: ${parts.size} parts")
                if (parts.size >= 4) {
                    val fileName = parts[0]
                    val chunkIndex = parts[1].toIntOrNull() ?: 0
                    val totalSize = parts[2].toLongOrNull() ?: 0
                    val chunkData = android.util.Base64.decode(parts[3], android.util.Base64.NO_WRAP)

                    Log.d(TAG, "Chunk $chunkIndex: ${chunkData.size} bytes, total expected: $totalSize")
                    SessionState.receiveFileChunk(fileName, chunkIndex, chunkData)
                    Log.d(TAG, "Chunk stored. Total received: ${SessionState.getTotalReceived()}")
                } else {
                    Log.e(TAG, "Invalid file chunk format: $dataStr")
                }
            }
        }
    }
}

/**
 * Singleton that tracks session state on the phone side.
 */
object SessionState {
    private var activeSession: ActiveSession? = null
    private val sessionsDir by lazy { File(android.os.Environment.getExternalStorageDirectory(), "Daedalus/sessions") }

    data class ActiveSession(
        val mode: String,
        val startTime: Long,
        val fileName: String = "",
        val fileSize: Long = 0,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val isComplete: Boolean = false,
    )

    @Volatile
    var isSessionActive: Boolean = false
        private set

    fun startSession(mode: String) {
        activeSession = ActiveSession(mode = mode, startTime = System.currentTimeMillis())
        isSessionActive = true
    }

    fun stopSession(mode: String, fileName: String, fileSize: Long) {
        activeSession = activeSession?.copy(
            fileName = fileName,
            fileSize = fileSize,
            isComplete = true
        )
        isSessionActive = false
    }

    fun receiveFileChunk(fileName: String, chunkIndex: Int, data: ByteArray) {
        activeSession?.chunks?.put(chunkIndex, data)

        // Check if all chunks received (simple heuristic: check if we have enough data)
        val totalReceived = activeSession?.chunks?.values?.sumOf { it.size } ?: 0
        if (totalReceived >= (activeSession?.fileSize ?: Long.MAX_VALUE)) {
            // Reassemble file
            reassembleFile(fileName)
        }
    }

    private fun reassembleFile(fileName: String) {
        val session = activeSession ?: return
        if (!sessionsDir.exists()) sessionsDir.mkdirs()

        val outputFile = File(sessionsDir, fileName)
        outputFile.outputStream().use { out ->
            var index = 0
            while (session.chunks.containsKey(index)) {
                out.write(session.chunks[index]!!)
                index++
            }
        }

        Log.i(TAG, "File reassembled: ${outputFile.absolutePath}")
    }

    fun getActiveSessionMode(): String? = activeSession?.mode
    fun isSessionComplete(): Boolean = activeSession?.isComplete == true

    fun getTotalReceived(): Int {
        return activeSession?.chunks?.values?.sumOf { it.size } ?: 0
    }
}
