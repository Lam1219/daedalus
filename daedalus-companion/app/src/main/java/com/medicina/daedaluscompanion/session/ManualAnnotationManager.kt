package com.medicina.daedaluscompanion.session

import android.content.Context
import android.util.Log
import com.medicina.daedaluscompanion.model.EventEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

private const val TAG = "ManualAnnotationMgr"
private const val CSV_QUOTE = '"'

/**
 * Manages manual annotations during a session.
 * Replaces video recording by letting the user log events, signal quality ratings,
 * and activity labels through the companion app UI.
 *
 * Output: manual_annotations.csv matching daedalus_watch_kit schema.
 */
class ManualAnnotationManager private constructor(private val context: Context) {

    private lateinit var sessionDir: File
    private lateinit var writer: BufferedWriter
    private var isRecording = false

    /** Initialize the annotation file for a new session */
    fun startSession(sessionDirectory: File) {
        sessionDir = sessionDirectory
        try {
            val annotationsDir = File(sessionDir, "annotations")
            annotationsDir.mkdirs()

            writer = BufferedWriter(FileWriter(File(annotationsDir, "manual_annotations.csv")))
            writer.appendLine("start_epoch_ms,end_epoch_ms,activity,tremor_present,fog_present,stress_level,signal_quality,annotator_id")
            isRecording = true
            Log.i(TAG, "Manual annotation file created")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create annotation file", e)
        }
    }

    /** Log a protocol block start/end event */
    fun logProtocolBlock(
        block: ProtocolBlock,
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis(),
        annotatorId: String = "user",
    ) {
        if (!isRecording) return

        // Also log the start event as a standard event
        val event = EventEntry(
            epochMs = startTimeMs,
            eventName = block.startEvent,
            source = "manual",
            notes = block.displayName,
        )

        // Log to annotation file
        try {
            val activityLabel = block.id.replace("block_", "").replace("_", "")
            writer.append(escapeCsv(startTimeMs.toString())).append(',')
            writer.append(escapeCsv(endTimeMs.toString())).append(',')
            writer.append(escapeCsv(activityLabel)).append(',')
            writer.append("false,")  // tremor_present
            writer.append("false,")  // fog_present
            writer.append("0,")      // stress_level (default)
            writer.append("1,")      // signal_quality (default: good)
            writer.appendLine(escapeCsv(annotatorId))
            writer.flush()
            Log.i(TAG, "Annotation logged: ${block.displayName}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write annotation", e)
        }
    }

    /** Log a detailed manual annotation with all fields */
    fun logAnnotation(
        startEpochMs: Long,
        endEpochMs: Long,
        activity: String,
        tremorPresent: Boolean = false,
        fogPresent: Boolean = false,
        stressLevel: Float = 0f,
        signalQuality: Float = 1f,
        annotatorId: String = "user",
    ) {
        if (!isRecording) return

        try {
            writer.append(escapeCsv(startEpochMs.toString())).append(',')
            writer.append(escapeCsv(endEpochMs.toString())).append(',')
            writer.append(escapeCsv(activity)).append(',')
            writer.append(tremorPresent.toString()).append(',')
            writer.append(fogPresent.toString()).append(',')
            writer.append(stressLevel.toString()).append(',')
            writer.append(signalQuality.toString()).append(',')
            writer.appendLine(escapeCsv(annotatorId))
            writer.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write annotation", e)
        }
    }

    /** End annotation recording for the session */
    fun endSession() {
        if (!this::writer.isInitialized) return
        try {
            writer.flush()
            writer.close()
            isRecording = false
            Log.i(TAG, "Manual annotation file closed")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close annotation file", e)
        }
    }

    /** Escape a CSV field value per RFC 4180 */
    private fun escapeCsv(value: String): String {
        if (value.isEmpty()) return value
        val needsQuoting = value.any { it == ',' || it == CSV_QUOTE || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            val escaped = value.replace(CSV_QUOTE.toString(), "$CSV_QUOTE$CSV_QUOTE")
            "$CSV_QUOTE$escaped$CSV_QUOTE"
        } else {
            value
        }
    }

    companion object {
        @Volatile
        private var instance: ManualAnnotationManager? = null

        fun getInstance(context: Context): ManualAnnotationManager {
            return instance ?: synchronized(this) {
                instance ?: ManualAnnotationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
