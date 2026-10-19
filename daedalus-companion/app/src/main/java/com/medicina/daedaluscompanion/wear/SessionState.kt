package com.medicina.daedaluscompanion.wear

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "SessionState"

/**
 * Singleton that tracks session state on the phone side, driven entirely by acks from the
 * watch (via WatchDataListener) rather than optimistic updates from the UI.
 */
object SessionState {
    private var activeSession: ActiveSession? = null

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

    fun receiveFileChunk(context: Context, fileName: String, chunkIndex: Int, data: ByteArray) {
        activeSession?.chunks?.put(chunkIndex, data)

        val totalReceived = activeSession?.chunks?.values?.sumOf { it.size } ?: 0
        if (totalReceived >= (activeSession?.fileSize ?: Long.MAX_VALUE)) {
            reassembleFile(context, fileName)
        }
    }

    fun reassembleFile(context: Context, fileName: String) {
        val session = activeSession ?: return
        val targetDirs = listOf(
            File(context.filesDir, "sessions"),
            File(context.filesDir, "daedalus/sessions"),
            File(context.getExternalFilesDir(null), "daedalus/sessions"),
            File(context.getExternalFilesDir(null), "sessions")
        )

        var mainFile: File? = null
        for (dir in targetDirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, fileName)
                outputFile.outputStream().use { out ->
                    var index = 0
                    while (session.chunks.containsKey(index)) {
                        val chunk = session.chunks[index] ?: break
                        out.write(chunk)
                        index++
                    }
                }
                if (mainFile == null) mainFile = outputFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed reassembling file in ${dir.absolutePath}: ${e.message}")
            }
        }

        val size = mainFile?.length() ?: 0L
        Log.i(TAG, "File reassembled: ${mainFile?.absolutePath} ($size bytes)")
        stopSession(session.mode, fileName, size)
    }

    fun getActiveSessionMode(): String? = activeSession?.mode
}
