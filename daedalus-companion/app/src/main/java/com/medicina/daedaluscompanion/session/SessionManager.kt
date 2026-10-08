package com.medicina.daedaluscompanion.session

import android.content.Context
import android.util.Log
import com.medicina.daedaluscompanion.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "SessionManager"
private const val FLUSH_INTERVAL_SAMPLES = 100
private const val CSV_QUOTE = '"'

/**
 * Manages session lifecycle on the phone side.
 *
 * Responsibilities:
 * - Start/end sessions
 * - Log events from phone UI and watch
 * - Store Cycplus H2 RR interval data
 * - Export session folder matching daedalus_watch_kit schema
 * - Zip for transfer to laptop
 */
class SessionManager private constructor(private val context: Context) {

    private val baseDir = File(context.getExternalFilesDir(null), "daedalus/data/raw")
    private lateinit var sessionDir: File
    private lateinit var metadata: SessionMetadata

    private lateinit var eventsWriter: BufferedWriter
    private lateinit var ecgWriter: BufferedWriter

    private var isRunning = false
    private var ecgSampleCount = 0
    private var lastProcessedEcgIndex = 0  // Track to avoid duplicate logging

    /** Start a new session */
    fun startSession(subjectId: String, sessionId: String) {
        if (isRunning) {
            Log.w(TAG, "Session already running")
            return
        }

        try {
            metadata = SessionMetadata(
                subjectId = subjectId,
                sessionId = sessionId,
                sessionStartEpochMs = System.currentTimeMillis(),
            )

            sessionDir = File(baseDir, "$subjectId/$sessionId")
            if (!sessionDir.exists() && !sessionDir.mkdirs()) {
                throw IOException("Failed to create session directory: $sessionDir")
            }

            val ecgDir = File(sessionDir, "ecg")
            ecgDir.mkdirs()

            // Initialize manual annotation file
            ManualAnnotationManager.getInstance(context).startSession(sessionDir)

            eventsWriter = BufferedWriter(FileWriter(File(sessionDir, "phone_events.csv")))
            eventsWriter.appendLine("epoch_ms,event_name,source,notes")

            ecgWriter = BufferedWriter(FileWriter(File(ecgDir, "ecg_reference.csv")))
            ecgWriter.appendLine("epoch_ms,ecg_raw,rr_ms,hr_bpm,beat_quality")

            File(sessionDir, "session_metadata.json").writeText(metadata.toJson())

            logEvent(EventEntry(
                epochMs = System.currentTimeMillis(),
                eventName = EventLabels.SESSION_START,
                source = "auto",
            ))

            lastProcessedEcgIndex = 0
            isRunning = true
            Log.i(TAG, "Session started: $subjectId/$sessionId")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start session", e)
        }
    }

    /** Log an event */
    fun logEvent(event: EventEntry) {
        if (!this::eventsWriter.isInitialized) return
        try {
            eventsWriter.append(escapeCsv(event.epochMs.toString())).append(',')
            eventsWriter.append(escapeCsv(event.eventName)).append(',')
            eventsWriter.append(escapeCsv(event.source)).append(',')
            eventsWriter.appendLine(escapeCsv(event.notes))
            eventsWriter.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write event", e)
        }
    }

    /** Log an ECG/RR interval sample from the Cycplus H2 */
    fun logEcgSample(sample: EcgSample) {
        if (!this::ecgWriter.isInitialized) return
        try {
            val ecgRawStr = if (sample.ecgRaw.isNaN()) "" else sample.ecgRaw.toString()
            val rrStr = sample.rrMs?.toString() ?: ""
            val hrStr = sample.hrBpm?.toString() ?: ""
            ecgWriter.append(escapeCsv(sample.epochMs.toString())).append(',')
            ecgWriter.append(ecgRawStr).append(',')
            ecgWriter.append(rrStr).append(',')
            ecgWriter.append(hrStr).append(',')
            ecgWriter.appendLine(sample.beatQuality.toString())
            ecgSampleCount++
            if (ecgSampleCount % FLUSH_INTERVAL_SAMPLES == 0) {
                ecgWriter.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write ECG sample", e)
        }
    }

    /** Log multiple ECG samples without duplicates */
    fun logEcgSamples(samples: List<EcgSample>) {
        if (!isRunning || samples.isEmpty()) return
        // Only process new samples since last call
        val newSamples = samples.drop(lastProcessedEcgIndex)
        if (newSamples.isEmpty()) return
        for (sample in newSamples) {
            logEcgSample(sample)
        }
        lastProcessedEcgIndex = samples.size
    }

    /** End the session */
    suspend fun endSession() = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext

        try {
            val endTime = System.currentTimeMillis()
            metadata = metadata.copy(sessionEndEpochMs = endTime)

            File(sessionDir, "session_metadata.json").writeText(metadata.toJson())

            logEvent(EventEntry(
                epochMs = endTime,
                eventName = EventLabels.SESSION_END,
                source = "auto",
            ))

            // Close annotation file
            ManualAnnotationManager.getInstance(context).endSession()

            closeWriter(eventsWriter)
            closeWriter(ecgWriter)

            isRunning = false
            Log.i(TAG, "Session ended: ${sessionDir.absolutePath} (ECG: $ecgSampleCount samples)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to end session", e)
        }
    }

    private fun closeWriter(writer: BufferedWriter?) {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close writer", e)
        }
    }

    /** Export session as a zip file for transfer to laptop */
    suspend fun exportSession(): File? = withContext(Dispatchers.IO) {
        if (metadata == null) {
            Log.w(TAG, "No session data to export")
            return@withContext null
        }

        // Reconstruct session directory path from metadata
        val subjId = metadata!!.subjectId
        val sessId = metadata!!.sessionId
        val sourceDir = File(baseDir, "$subjId/$sessId")
        
        if (!sourceDir.exists()) {
            Log.w(TAG, "Session directory not found: $sourceDir")
            return@withContext null
        }

        val exportDir = File(baseDir.parentFile, "exports")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            Log.e(TAG, "Failed to create export directory")
            return@withContext null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(exportDir, "daedalus_${subjId}_${sessId}_${timestamp}.zip")

        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                addDirectoryToZip(sourceDir, zos, sourceDir.name)
            }
            Log.i(TAG, "Session exported to: ${zipFile.absolutePath}")
            zipFile
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create zip", e)
            null
        }
    }

    private fun addDirectoryToZip(dir: File, zos: ZipOutputStream, basePath: String) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val path = "$basePath/${file.name}"
            if (file.isDirectory) {
                addDirectoryToZip(file, zos, path)
            } else {
                zos.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    fun isRunning(): Boolean = isRunning
    fun getMetadata(): SessionMetadata? = if (this::metadata.isInitialized) metadata else null
    fun getSessionDir(): File? = if (this::sessionDir.isInitialized) sessionDir else null

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
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/** Convert SessionMetadata to JSON */
fun SessionMetadata.toJson(): String {
    // Use simple escaping for the notes field
    val escapedNotes = notes.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{
  "subject_id": "$subjectId",
  "session_id": "$sessionId",
  "device_model": "$deviceModel",
  "firmware_version": "$firmwareVersion",
  "watch_side": "$watchSide",
  "phone_model": "$phoneModel",
  "ecg_device": "$ecgDevice",
  "session_start_epoch_ms": $sessionStartEpochMs,
  "session_end_epoch_ms": $sessionEndEpochMs,
  "protocol_version": "$protocolVersion",
  "cohort": "$cohort",
  "clinician_present": $clinicianPresent,
  "notes": "$escapedNotes"
}"""
}
