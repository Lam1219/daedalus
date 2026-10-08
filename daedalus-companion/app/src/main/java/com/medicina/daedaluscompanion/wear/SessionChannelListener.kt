package com.medicina.daedaluscompanion.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "SessionChannelListener"

/**
 * Receives session files from the watch via ChannelClient API.
 * Files are streamed directly from watch to phone — no intermediate storage.
 */
class SessionChannelListener : WearableListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        Log.i(TAG, "Channel opened: ${channel.path}")

        if (channel.path.startsWith("/session/file/")) {
            val fileName = channel.path.substringAfter("/session/file/")
            serviceScope.launch {
                receiveSessionFile(channel, fileName)
            }
        }
    }

    private fun receiveSessionFile(channel: ChannelClient.Channel, fileName: String) {
        try {
            val sessionsDir = File(getExternalFilesDir(null), "daedalus/sessions")
            if (!sessionsDir.exists()) sessionsDir.mkdirs()

            val targetFile = File(sessionsDir, fileName)
            val targetUri = androidx.core.content.FileProvider.getUriForFile(
                this@SessionChannelListener,
                "$packageName.fileprovider",
                targetFile
            )

            Wearable.getChannelClient(this@SessionChannelListener)
                .receiveFile(channel, targetUri, false)
                .addOnSuccessListener {
                    Log.i(TAG, "File received: ${targetFile.absolutePath}")
                    SessionState.stopSession("unknown", fileName, targetFile.length())
                    Wearable.getChannelClient(this@SessionChannelListener).close(channel)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to receive file: ${e.message}")
                    targetFile.delete()
                    Wearable.getChannelClient(this@SessionChannelListener).close(channel)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file: ${e.message}")
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message received: path=${messageEvent.path}, size=${messageEvent.data.size}")

        when (messageEvent.path) {
            "/session/control" -> {
                val data = String(messageEvent.data)
                val parts = data.split("|")
                when (parts.firstOrNull()) {
                    "start" -> {
                        val mode = parts.getOrNull(1) ?: "unknown"
                        Log.i(TAG, "Session START: mode=$mode")
                        SessionState.startSession(mode)
                    }
                    "complete" -> {
                        val mode = parts.getOrNull(1) ?: "unknown"
                        val fileName = parts.getOrNull(2) ?: ""
                        val fileSize = parts.getOrNull(3)?.toLongOrNull() ?: 0
                        Log.i(TAG, "Session COMPLETE: $mode, $fileName, $fileSize bytes")
                        SessionState.stopSession(mode, fileName, fileSize)
                    }
                }
            }
        }
    }
}
