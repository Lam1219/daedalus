package com.medicina.daedaluswatch.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.medicina.daedaluswatch.R
import com.medicina.daedaluswatch.haptics.HapticService

data class HapticMode(
    val name: String,
    val bpm: Int,
    val icon: String,
    val description: String,
)

class MainActivity : ComponentActivity() {

    private val modes = listOf(
        HapticMode("Rest", 70, "🧘", "Calm breathing pace"),
        HapticMode("Walking", 80, "🚶", "Natural walking cadence"),
        HapticMode("Writing", 90, "✍️", "Fine motor activity"),
        HapticMode("Stress", 60, "🧠", "Slow grounding pulses"),
    )

    private var selectedMode: HapticMode? = null
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvMode = findViewById<TextView>(R.id.tv_mode)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val btnRest = findViewById<Button>(R.id.btn_rest)
        val btnWalking = findViewById<Button>(R.id.btn_walking)
        val btnWriting = findViewById<Button>(R.id.btn_writing)
        val btnStress = findViewById<Button>(R.id.btn_stress)
        val btnStartStop = findViewById<Button>(R.id.btn_start_stop)

        btnRest.setOnClickListener { selectMode(modes[0], tvMode, tvStatus, btnRest, btnWalking, btnWriting, btnStress) }
        btnWalking.setOnClickListener { selectMode(modes[1], tvMode, tvStatus, btnRest, btnWalking, btnWriting, btnStress) }
        btnWriting.setOnClickListener { selectMode(modes[2], tvMode, tvStatus, btnRest, btnWalking, btnWriting, btnStress) }
        btnStress.setOnClickListener { selectMode(modes[3], tvMode, tvStatus, btnRest, btnWalking, btnWriting, btnStress) }

        btnStartStop.setOnClickListener {
            if (isRunning) {
                stopHaptic(tvStatus, btnStartStop)
            } else {
                startHaptic(tvStatus, btnStartStop)
            }
        }

        tvStatus.text = "Select a mode and tap Start"
        updateModeButtons(btnRest, btnWalking, btnWriting, btnStress, null)
        btnStartStop.setBackgroundColor(getColor(android.R.color.holo_green_dark))
    }

    private fun selectMode(
        mode: HapticMode,
        tvMode: TextView,
        tvStatus: TextView,
        btnRest: Button,
        btnWalking: Button,
        btnWriting: Button,
        btnStress: Button,
    ) {
        selectedMode = mode
        tvMode.text = "${mode.icon} ${mode.name}\n${mode.bpm} BPM"
        tvStatus.text = "${mode.name} selected — ${mode.bpm} BPM"
        updateModeButtons(btnRest, btnWalking, btnWriting, btnStress, mode)

        // If service is running, restart with new BPM
        if (isRunning) {
            stopService(Intent(this, HapticService::class.java))
            startHapticService(mode)
        }
    }

    private fun updateModeButtons(
        btnRest: Button,
        btnWalking: Button,
        btnWriting: Button,
        btnStress: Button,
        selected: HapticMode?,
    ) {
        val buttons = mapOf(btnRest to modes[0], btnWalking to modes[1], btnWriting to modes[2], btnStress to modes[3])
        for ((button, mode) in buttons) {
            if (mode == selected) {
                button.setBackgroundColor(getColor(android.R.color.holo_blue_dark))
                button.setTextColor(getColor(android.R.color.white))
            } else {
                button.setBackgroundColor(getColor(android.R.color.darker_gray))
                button.setTextColor(getColor(android.R.color.white))
            }
        }
    }

    private fun startHaptic(tvStatus: TextView, btnStartStop: Button) {
        val mode = selectedMode ?: run {
            Toast.makeText(this, "Select a mode first", Toast.LENGTH_SHORT).show()
            return
        }
        startHapticService(mode)
        isRunning = true
        btnStartStop.text = "⏹ Stop"
        btnStartStop.setBackgroundColor(getColor(android.R.color.holo_red_dark))
        tvStatus.text = "▶ ${mode.name} running at ${mode.bpm} BPM"
    }

    private fun startHapticService(mode: HapticMode) {
        val intent = Intent(this, HapticService::class.java).apply {
            action = HapticService.ACTION_START
            putExtra(HapticService.EXTRA_BPM, mode.bpm)
            putExtra(HapticService.EXTRA_MODE, mode.name.lowercase())
        }
        startForegroundService(intent)
    }

    private fun stopHaptic(tvStatus: TextView, btnStartStop: Button) {
        stopService(Intent(this, HapticService::class.java))
        isRunning = false
        btnStartStop.text = "▶ Start"
        btnStartStop.setBackgroundColor(getColor(android.R.color.holo_green_dark))
        val mode = selectedMode
        tvStatus.text = if (mode != null) "⏹ ${mode.name} stopped" else "Select a mode and tap Start"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop service here — let it run in background
    }
}
