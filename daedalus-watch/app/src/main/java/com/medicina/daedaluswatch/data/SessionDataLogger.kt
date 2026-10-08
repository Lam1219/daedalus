package com.medicina.daedaluswatch.data

import android.content.Context
import android.util.Log
import com.medicina.daedaluswatch.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

private const val TAG = "SessionDataLogger"

// Named constants
private const val FLUSH_INTERVAL_SAMPLES = 500
private const val CSV_QUOTE = '"'
private const val CSV_ESCAPE = '"'

/**
 * Writes sensor data to CSV files matching the daedalus_watch_kit schema.
 *
 * Uses BufferedWriter for performance and periodic flush to prevent data loss.
 * CSV fields containing commas, quotes, or newlines are properly escaped.
 */
class SessionDataLogger(context: Context) {

    private val baseDir = File(context.filesDir, "data/raw")

    private lateinit var sessionDir: File
    private lateinit var imuWriter: BufferedWriter
    private lateinit var hrWriter: BufferedWriter
    private lateinit var hapticsWriter: BufferedWriter
    private lateinit var eventsWriter: BufferedWriter

    private var metadata: SessionMetadata? = null
    private var imuSampleCount = 0
    private var hrSampleCount = 0

    /** Initialize a new session directory and open file writers */
    fun startSession(metadata: SessionMetadata) {
        try {
            this.metadata = metadata
            sessionDir = File(baseDir, "${metadata.subjectId}/${metadata.sessionId}")
            if (!sessionDir.exists() && !sessionDir.mkdirs()) {
                throw IOException("Failed to create session directory: $sessionDir")
            }

            imuWriter = BufferedWriter(FileWriter(File(sessionDir, "watch_imu.csv")))
            hrWriter = BufferedWriter(FileWriter(File(sessionDir, "watch_hr.csv")))
            hapticsWriter = BufferedWriter(FileWriter(File(sessionDir, "watch_haptics.csv")))
            eventsWriter = BufferedWriter(FileWriter(File(sessionDir, "phone_events.csv")))

            // Write CSV headers
            imuWriter.appendLine("epoch_ms,elapsed_ms,ax,ay,az,gx,gy,gz,motion_intensity")
            hrWriter.appendLine("epoch_ms,hr_bpm,hrv_rmssd_ms,hr_quality,source")
            hapticsWriter.appendLine("epoch_ms,event_type,pattern_type")
            eventsWriter.appendLine("epoch_ms,event_name,source,notes")

            // Write session metadata
            val metaFile = File(sessionDir, "session_metadata.json")
            metaFile.writeText(metadata.toJson())

            // Log start event
            logEvent(EventEntry(
                epochMs = System.currentTimeMillis(),
                eventName = EventLabels.SESSION_START,
                source = "auto",
            ))

            Log.i(TAG, "Session started: ${sessionDir.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start session", e)
            throw e
        }
    }

    /** Append an IMU sample to the CSV */
    fun logImu(sample: ImuSample) {
        if (!this::imuWriter.isInitialized) return
        try {
            imuWriter.append(escapeCsv(sample.epochMs.toString())).append(',')
            imuWriter.append(escapeCsv(sample.elapsedMs.toString())).append(',')
            imuWriter.append(sample.ax.toString()).append(',')
            imuWriter.append(sample.ay.toString()).append(',')
            imuWriter.append(sample.az.toString()).append(',')
            imuWriter.append(sample.gx.toString()).append(',')
            imuWriter.append(sample.gy.toString()).append(',')
            imuWriter.append(sample.gz.toString()).append(',')
            imuWriter.appendLine(sample.motionIntensity.toString())
            imuSampleCount++
            if (imuSampleCount % FLUSH_INTERVAL_SAMPLES == 0) {
                imuWriter.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write IMU sample", e)
        }
    }

    /** Append an HR sample to the CSV */
    fun logHr(sample: HrSample) {
        if (!this::hrWriter.isInitialized) return
        try {
            hrWriter.append(escapeCsv(sample.epochMs.toString())).append(',')
            hrWriter.append(sample.hrBpm.toString()).append(',')
            hrWriter.append(sample.hrvRmssdMs?.toString() ?: "").append(',')
            hrWriter.append(sample.hrQuality?.toString() ?: "").append(',')
            hrWriter.appendLine(escapeCsv(sample.source))
            hrSampleCount++
            if (hrSampleCount % FLUSH_INTERVAL_SAMPLES == 0) {
                hrWriter.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write HR sample", e)
        }
    }

    /** Append a haptic event to the CSV */
    fun logHaptic(event: HapticEvent) {
        if (!this::hapticsWriter.isInitialized) return
        try {
            hapticsWriter.append(escapeCsv(event.epochMs.toString())).append(',')
            hapticsWriter.append(escapeCsv(event.eventType)).append(',')
            hapticsWriter.appendLine(escapeCsv(event.patternType))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write haptic event", e)
        }
    }

    /** Append an event label to the CSV */
    fun logEvent(event: EventEntry) {
        if (!this::eventsWriter.isInitialized) return
        try {
            eventsWriter.append(escapeCsv(event.epochMs.toString())).append(',')
            eventsWriter.append(escapeCsv(event.eventName)).append(',')
            eventsWriter.append(escapeCsv(event.source)).append(',')
            eventsWriter.appendLine(escapeCsv(event.notes))
            eventsWriter.flush()  // Events are critical — flush immediately
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write event", e)
        }
    }

    /** End the session: update metadata, close writers */
    suspend fun endSession() = withContext(Dispatchers.IO) {
        try {
            val endTime = System.currentTimeMillis()

            // Update metadata with end time
            metadata?.let {
                val updated = it.copy(sessionEndEpochMs = endTime)
                File(sessionDir, "session_metadata.json").writeText(updated.toJson())
            }

            logEvent(EventEntry(
                epochMs = endTime,
                eventName = EventLabels.SESSION_END,
                source = "auto",
            ))

            // Flush and close all writers
            closeWriter(imuWriter)
            closeWriter(hrWriter)
            closeWriter(hapticsWriter)
            closeWriter(eventsWriter)

            Log.i(TAG, "Session ended: ${sessionDir.absolutePath} " +
                    "(IMU: $imuSampleCount, HR: $hrSampleCount samples)")
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

    /** Get the session directory path for sharing with the phone */
    fun getSessionDir(): File? = if (this::sessionDir.isInitialized) sessionDir else null

    /** Batch IMU samples for efficiency */
    fun logImuBatch(samples: List<ImuSample>) {
        if (!this::imuWriter.isInitialized) return
        try {
            val sb = StringBuilder(samples.size * 80)
            for (s in samples) {
                sb.append(s.epochMs).append(',').append(s.elapsedMs).append(',')
                    .append(s.ax).append(',').append(s.ay).append(',').append(s.az).append(',')
                    .append(s.gx).append(',').append(s.gy).append(',').append(s.gz).append(',')
                    .append(s.motionIntensity).append('\n')
            }
            imuWriter.write(sb.toString())
            imuSampleCount += samples.size
            if (imuSampleCount % FLUSH_INTERVAL_SAMPLES == 0) {
                imuWriter.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write IMU batch", e)
        }
    }

    /** Escape a CSV field value per RFC 4180 */
    private fun escapeCsv(value: String): String {
        if (value.isEmpty()) return value
        val needsQuoting = value.any { it == ',' || it == CSV_QUOTE || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            val escaped = value.replace(CSV_QUOTE.toString(), "$CSV_ESCAPE$CSV_ESCAPE")
            "$CSV_QUOTE$escaped$CSV_QUOTE"
        } else {
            value
        }
    }
}

/** Convert SessionMetadata to JSON */
fun com.medicina.daedaluswatch.model.SessionMetadata.toJson(): String {
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
