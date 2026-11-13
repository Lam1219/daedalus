package com.medicina.shared.pilot

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Click timing without drift: beat k starts at frame floor(k · rate · 60 / bpm), computed from k
 * each time rather than by adding a rounded period, so the error never grows past one frame.
 */
object ClickSchedule {
    const val SAMPLE_RATE = 48_000
    /** 20 ms click: a 1.5 kHz tone that fades fast, easy to hear over street noise, not harsh. */
    const val CLICK_FRAMES = SAMPLE_RATE / 50

    fun beatFrame(k: Long, bpm: Int, rate: Int = SAMPLE_RATE): Long = k * rate * 60L / bpm

    val click: ShortArray by lazy {
        ShortArray(CLICK_FRAMES) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            (sin(2 * PI * 1500.0 * t) * exp(-t / 0.004) * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Fills [out] with frames [from, from + out.size) of the click track. */
    fun fill(out: ShortArray, from: Long, bpm: Int, rate: Int = SAMPLE_RATE) {
        out.fill(0)
        val to = from + out.size
        // First beat whose click overlaps [from, to).
        var k = maxOf(0L, (from - CLICK_FRAMES) * bpm / (rate * 60L))
        while (true) {
            val start = beatFrame(k, bpm, rate)
            if (start >= to) break
            for (j in 0 until CLICK_FRAMES) {
                val f = start + j
                if (f in from until to) out[(f - from).toInt()] = click[j]
            }
            k++
        }
    }
}

/**
 * Round 003, #4: what to do while waiting for the output to show the first click as presented.
 * A timestamp means it played; none within the wait means the audio may be silent, so the
 * caller treats it as lost and the wrist takes over. No press stays silent because audio was
 * written but never presented.
 */
object FirstClick {
    enum class Outcome { WAIT, MEASURED, LOST }

    const val WAIT_MS = 2_000L

    fun decide(presented: Boolean, waitedMs: Long, waitMs: Long = WAIT_MS): Outcome = when {
        presented -> Outcome.MEASURED
        waitedMs > waitMs -> Outcome.LOST
        else -> Outcome.WAIT
    }
}

/**
 * Earbuds or headphones on this device's audio outputs (round 001, #8). A Bluetooth A2DP sink can
 * be a speaker, a car or a TV, so it counts only when its Bluetooth device class says headphones or
 * a wearable headset. LE audio headsets, wired and USB headsets count. Without permission to read
 * Bluetooth device classes (Android 12+: BLUETOOTH_CONNECT), a Bluetooth sink doesn't count, so the
 * earbuds place is never drawn by mistake (the press still gets its beat on the wrist).
 */
object AudioRoutes {
    private val ALWAYS_HEADPHONES = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Bluetooth device classes that are worn on or in the ears. */
    fun isHeadphoneClass(deviceClass: Int): Boolean = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET

    private fun isEarbuds(context: Context, d: AudioDeviceInfo): Boolean = when (d.type) {
        in ALWAYS_HEADPHONES -> true
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> bluetoothClass(context, d.address)?.let { isHeadphoneClass(it) } ?: false
        else -> false
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothClass(context: Context, address: String?): Int? = runCatching {
        if (address.isNullOrEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return null
        adapter.getRemoteDevice(address).bluetoothClass?.deviceClass
    }.getOrNull()

    fun headphonesConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isEarbuds(context, it) }
    }

    private val BLUETOOTH_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
    }

    fun isBluetooth(d: AudioDeviceInfo?): Boolean = d != null && d.type in BLUETOOTH_TYPES
}

/**
 * B1/B2: plays the click track at the person's pace through the current audio route (their
 * earbuds), ducking other audio. Reports when the first click actually reached the output, from
 * AudioTrack.getTimestamp (the frame being presented now and when), so beat_start_ms is the click,
 * not the request. Bluetooth adds its own delay after that point (typically 100-250 ms) that
 * Android doesn't report reliably: see the handoff.
 */
class ClickBeatPlayer(private val context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    /** Paused by the person: the track keeps time but plays silence (round 001, #24). */
    @Volatile var paused = false
    private var thread: Thread? = null
    private var focus: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null

    /**
     * Starts the clicks. [onStarted] is called twice (round 002, #8):
     * - at once when the first frames are written and the track is playing: (null, "audio_written");
     * - then with the measured start of the first click from the output timestamp,
     *   (ms, "audio_timestamp"), or, if none arrives within [TIMESTAMP_WAIT_MS], (null, "audio_no_timestamp").
     * The play() call time is never reported as the start. Sources end in "_bt" over Bluetooth.
     * [onFailed] is called if audio can't start; [onLost] if the headphones go away mid-beat.
     */
    fun start(bpm: Int, onStarted: (Long?, String) -> Unit, onFailed: (String) -> Unit, onLost: (String) -> Unit = {}) {
        if (running) return
        if (!AudioRoutes.headphonesConnected(context)) { onFailed("no_headphones"); return }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(ClickSchedule.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(ClickSchedule.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, ClickSchedule.SAMPLE_RATE / 10 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            onFailed("audio_track: ${e.message}"); return
        }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            .also { am.requestAudioFocus(it) } // ducks music; the clicks play even if focus is refused

        running = true
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (running && !AudioRoutes.headphonesConnected(context)) {
                    Log.w(TAG, "Headphones gone mid-beat")
                    stop()
                    onLost("headphones_removed")
                }
            }
        }.also { am.registerAudioDeviceCallback(it, main) }

        thread = Thread {
            val chunk = ShortArray(ClickSchedule.SAMPLE_RATE / 100) // 10 ms
            var frame = 0L
            var confirmed = false
            var measured = false
            val ts = AudioTimestamp()
            val playCalledMs = System.currentTimeMillis()
            try {
                track.play()
                while (running) {
                    ClickSchedule.fill(chunk, frame, bpm)
                    if (paused) chunk.fill(0)
                    val n = track.write(chunk, 0, chunk.size) // blocks while the buffer is full: real-time pacing
                    if (n < 0) throw IllegalStateException("write error $n")
                    frame += n
                    if (!confirmed && n > 0 && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        // Playing: confirm now, so the 1 s safety net never waits for a timestamp.
                        confirmed = true
                        onStarted(null, "audio_written" + btSuffix(track))
                    }
                    if (confirmed && !measured) {
                        val presented = track.getTimestamp(ts) && ts.framePosition > 0
                        when (FirstClick.decide(presented, System.currentTimeMillis() - playCalledMs)) {
                            FirstClick.Outcome.MEASURED -> {
                                // Frame 0 (the first click) was presented framePosition frames before ts.nanoTime.
                                val ageMs = (System.nanoTime() - ts.nanoTime) / 1_000_000
                                val start = System.currentTimeMillis() - ageMs - ts.framePosition * 1000 / ClickSchedule.SAMPLE_RATE
                                measured = true
                                onStarted(start, "audio_timestamp" + btSuffix(track))
                            }
                            FirstClick.Outcome.LOST -> {
                                // Written but never shown as presented: maybe silent. No start time, the
                                // file says why, and the wrist takes over (round 003, #4).
                                measured = true
                                onStarted(null, "audio_no_timestamp" + btSuffix(track))
                                stop()
                                onLost("audio_no_timestamp")
                            }
                            FirstClick.Outcome.WAIT -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Click track stopped: ${e.message}")
                if (!confirmed) onFailed("audio_write: ${e.message}")
                else if (running) { running = false; onLost("audio_error") }
            } finally {
                runCatching { track.pause(); track.flush(); track.stop() }
                track.release()
            }
        }.apply { name = "ClickBeat"; priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        deviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    val isPlaying get() = running

    /** "_bt" when the clicks go to a Bluetooth device, whose codec delay the timestamp may not include. */
    private fun btSuffix(track: AudioTrack) = if (AudioRoutes.isBluetooth(track.routedDevice)) "_bt" else ""

    private companion object {
        const val TAG = "ClickBeatPlayer"
        /** How long the output has to report a timestamp for the first click (round 002, #8). */
        const val TIMESTAMP_WAIT_MS = 2_000L
    }
}
