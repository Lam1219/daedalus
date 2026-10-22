package com.medicina.daedaluswatch.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.wearable.Wearable
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.haptics.CueRates
import com.medicina.daedaluswatch.haptics.HapticService
import com.medicina.daedaluswatch.sensor.HeartRatePermission
import com.medicina.daedaluswatch.sensor.StandWatchService
import com.medicina.daedaluswatch.sync.SessionSyncManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.PI

/**
 * Watch screens from the design canvas. Home shows "I'm stuck" and live vitals; while a cue
 * runs, the screen for its mode shows instead (walking ring, breathing pacer, or the
 * steady/grounding cue screen). Modes and rates are chosen on the phone.
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var screenHome: View
    private lateinit var screenWalk: View
    private lateinit var screenBreathe: View
    private lateinit var screenCue: View

    private lateinit var tvHomeStatus: TextView
    private lateinit var dotHomeStatus: View
    private lateinit var tvHomeHr: TextView
    private lateinit var groupHomeBreathing: View
    private lateinit var tvHomeBreathing: TextView
    private lateinit var tvWalkRate: TextView
    private lateinit var ring: RingView
    private lateinit var breath: BreathView
    private lateinit var tvBreathePhase: TextView
    private lateinit var tvBreatheRhythm: TextView
    private lateinit var tvBreatheWhy: TextView
    private lateinit var ivCueIcon: ImageView
    private lateinit var tvCueTitle: TextView
    private lateinit var tvCueSub: TextView
    private lateinit var tvCueTimer: TextView
    private lateinit var btnCuePause: ImageButton

    private var sensorManager: SensorManager? = null
    private var homeHeartRate = 0
    private var phoneConnected = true
    private var lastPhoneCheckMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()
        SessionSyncManager(this).syncAllPendingFiles()
        // Resume stand-up detection if it was switched on from the phone.
        StandWatchService.startIfEnabled(this)

        screenHome = findViewById(R.id.screen_home)
        screenWalk = findViewById(R.id.screen_walk)
        screenBreathe = findViewById(R.id.screen_breathe)
        screenCue = findViewById(R.id.screen_cue)
        tvHomeStatus = findViewById(R.id.tv_home_status)
        dotHomeStatus = findViewById(R.id.dot_home_status)
        tvHomeHr = findViewById(R.id.tv_home_hr)
        groupHomeBreathing = findViewById(R.id.group_home_breathing)
        tvHomeBreathing = findViewById(R.id.tv_home_breathing)
        tvWalkRate = findViewById(R.id.tv_walk_rate)
        ring = findViewById(R.id.ring)
        breath = findViewById(R.id.breath)
        tvBreathePhase = findViewById(R.id.tv_breathe_phase)
        tvBreatheRhythm = findViewById(R.id.tv_breathe_rhythm)
        tvBreatheWhy = findViewById(R.id.tv_breathe_why)
        ivCueIcon = findViewById(R.id.iv_cue_icon)
        tvCueTitle = findViewById(R.id.tv_cue_title)
        tvCueSub = findViewById(R.id.tv_cue_sub)
        tvCueTimer = findViewById(R.id.tv_cue_timer)
        btnCuePause = findViewById(R.id.btn_cue_pause)
        findViewById<View>(R.id.tv_walk_hint).visibility =
            if (resources.getBoolean(R.bool.show_walk_hint)) View.VISIBLE else View.GONE

        // "I'm stuck" starts the walking cue at the rate set on the phone.
        findViewById<View>(R.id.btn_stuck).setOnClickListener { startWalkingCue() }
        findViewById<View>(R.id.btn_walk_stop).setOnClickListener { stopCue() }
        findViewById<View>(R.id.btn_breathe_done).setOnClickListener { stopCue() }
        findViewById<View>(R.id.btn_cue_stop).setOnClickListener { stopCue() }
        btnCuePause.setOnClickListener {
            val action = if (HapticService.isUserPaused) HapticService.ACTION_RESUME else HapticService.ACTION_PAUSE
            startService(Intent(this, HapticService::class.java).setAction(action))
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()
        lastPhoneCheckMs = 0L
        handler.post(frame)
        // Live heart rate for the home screen while it's visible.
        val hr = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hr != null && HeartRatePermission.isGranted(this)) {
            sensorManager?.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(frame)
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE && event.values[0] > 0) homeHeartRate = event.values[0].toInt()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** ~20 fps while visible: picks the screen and animates the ring / breathing circles. */
    private val frame = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 50)
        }
    }

    private fun show(screen: View) {
        listOf(screenHome, screenWalk, screenBreathe, screenCue).forEach {
            it.visibility = if (it === screen) View.VISIBLE else View.GONE
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        if (!HapticService.isServiceRunning) {
            show(screenHome)
            val hr = HapticService.latestHeartRate.takeIf { it > 0 } ?: homeHeartRate
            tvHomeHr.text = if (hr > 0) hr.toString() else getString(R.string.value_none)
            val breathing = HapticService.latestBreathing?.takeIf { it > 0f }
            groupHomeBreathing.visibility = if (breathing != null) View.VISIBLE else View.GONE
            if (breathing != null) tvHomeBreathing.text = Math.round(breathing).toString()
            renderHomeStatus(now)
            return
        }
        val bpm = HapticService.runningBpm
        when (HapticService.runningMode) {
            "rest" -> {
                show(screenBreathe)
                val inhale = HapticService.breathInhale
                val phaseMs = HapticService.breathPhaseMs.coerceAtLeast(1)
                val t = ((now - HapticService.breathPhaseStartMs).toFloat() / phaseMs).coerceIn(0f, 1f)
                val eased = (1 - cos(PI * t).toFloat()) / 2
                breath.fullness = if (inhale) eased else 1 - eased
                val measuring = HapticService.isMeasuringSpo2
                tvBreathePhase.text = getString(if (inhale) R.string.breathe_in else R.string.breathe_out)
                tvBreathePhase.alpha = if (measuring) 0.4f else 1f
                val breathMs = 60_000f / bpm.coerceAtLeast(1)
                val inS = Math.round(breathMs * HapticService.INHALE_SHARE / 1000f)
                val outS = Math.round(breathMs / 1000f) - inS
                tvBreatheRhythm.text = getString(R.string.breathe_rhythm, inS, outS)
                val hr = HapticService.latestHeartRate
                tvBreatheWhy.text = when {
                    measuring -> getString(R.string.breathe_spo2)
                    hr > 0 -> getString(R.string.breathe_hr, hr)
                    else -> ""
                }
            }
            "writing", "stress" -> {
                show(screenCue)
                val calm = HapticService.runningMode == "stress"
                ivCueIcon.setImageResource(if (calm) R.drawable.ic_waves else R.drawable.ic_pen)
                tvCueTitle.text = getString(if (calm) R.string.mode_calm else R.string.mode_fine)
                val paused = HapticService.isUserPaused
                tvCueSub.text = if (paused) getString(R.string.paused)
                    else getString(if (calm) R.string.cue_calm_sub else R.string.cue_fine_sub)
                val elapsed = ((now - HapticService.startedAtMs) / 1000).coerceAtLeast(0)
                tvCueTimer.text = String.format(Locale.getDefault(), "%d:%02d", elapsed / 60, elapsed % 60)
                btnCuePause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
                btnCuePause.contentDescription = getString(if (paused) R.string.cd_resume else R.string.cd_pause)
            }
            else -> {
                show(screenWalk)
                tvWalkRate.text = bpm.toString()
                // Dot swells on each beat, then settles.
                val beatMs = 60_000f / bpm.coerceAtLeast(1)
                val phase = ((now - HapticService.startedAtMs) % beatMs.toLong()) / beatMs
                ring.beat = (1f - phase * 4f).coerceAtLeast(0f)
            }
        }
    }

    /** "Ready" while the phone is reachable, otherwise says so; "I'm stuck" works either way. */
    private fun renderHomeStatus(now: Long) {
        if (now - lastPhoneCheckMs > PHONE_CHECK_MS) {
            lastPhoneCheckMs = now
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes -> phoneConnected = nodes.isNotEmpty() }
                .addOnFailureListener { phoneConnected = false }
        }
        tvHomeStatus.setText(if (phoneConnected) R.string.status_ready else R.string.status_phone_missing)
        val color = getColor(if (phoneConnected) R.color.watch_sage else R.color.watch_muted)
        tvHomeStatus.setTextColor(color)
        dotHomeStatus.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun startWalkingCue() {
        if (HapticService.isServiceRunning) return
        val intent = Intent(this, HapticService::class.java).apply {
            action = HapticService.ACTION_START
            putExtra(HapticService.EXTRA_BPM, CueRates.get(this@MainActivity, "walking"))
            putExtra(HapticService.EXTRA_MODE, "walking")
        }
        startForegroundService(intent)
    }

    private fun stopCue() {
        // ACTION_STOP (not stopService): the service stays in the foreground until the file is sent.
        if (HapticService.isServiceRunning) startService(Intent(this, HapticService::class.java).setAction(HapticService.ACTION_STOP))
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Wear OS 5+ gates heart rate with the health permission (confirmed via
        // `dumpsys sensorservice`); BODY_SENSORS above covers older watches.
        if (HeartRatePermission.name !in permissionsToRequest) permissionsToRequest.add(HeartRatePermission.name)
        val missing = permissionsToRequest.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private companion object {
        const val PHONE_CHECK_MS = 15_000L
    }
}
