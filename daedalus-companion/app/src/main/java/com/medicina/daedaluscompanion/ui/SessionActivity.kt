package com.medicina.daedaluscompanion.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.ble.ChestStrapBLEManager
import com.medicina.daedaluscompanion.ble.ConnectionState
import com.medicina.daedaluscompanion.session.SessionManager
import com.medicina.daedaluscompanion.wear.WatchDataClient
import com.medicina.daedaluscompanion.model.EventEntry
import com.medicina.daedaluscompanion.model.EventLabels
import kotlinx.coroutines.launch

/**
 * Minimal Phase 0 UI for the Android companion app.
 *
 * Features:
 * - Connect to Cycplus H2 chest strap (BLE)
 * - Start/Stop session (syncs with watch)
 * - Event marker buttons for protocol blocks
 * - Live signal quality display (HR from chest strap)
 * - Export session to zip
 */
class SessionActivity : ComponentActivity() {

    private lateinit var bleManager: ChestStrapBLEManager
    private lateinit var sessionManager: SessionManager
    private lateinit var watchClient: WatchDataClient

    private var totalEcgSamplesReceived = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            bleManager.scanAndConnect()
        } else {
            Toast.makeText(this, getString(R.string.toast_ble_permission), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)

        bleManager = ChestStrapBLEManager(this)
        sessionManager = SessionManager.getInstance(this)
        watchClient = WatchDataClient(this)

        val btnConnectBle = findViewById<Button>(R.id.btn_connect_ble)
        val btnStartSession = findViewById<Button>(R.id.btn_start)
        val btnStopSession = findViewById<Button>(R.id.btn_stop)
        val btnExport = findViewById<Button>(R.id.btn_export)
        val btnLabeling = findViewById<Button>(R.id.btn_labeling)
        val tvConnection = findViewById<TextView>(R.id.tv_connection)
        val tvSession = findViewById<TextView>(R.id.tv_session)
        val tvHr = findViewById<TextView>(R.id.tv_hr)
        val btnRest = findViewById<Button>(R.id.btn_rest)
        val btnStress = findViewById<Button>(R.id.btn_stress)
        val btnSync = findViewById<Button>(R.id.btn_sync)

        btnConnectBle.setOnClickListener { checkBlePermissions() }

        btnStartSession.setOnClickListener {
            val subjectId = "sub-001"
            val sessionId = "sess-${System.currentTimeMillis() % 10000}"
            sessionManager.startSession(subjectId, sessionId)
            totalEcgSamplesReceived = 0
            lifecycleScope.launch {
                watchClient.sendSessionStart(subjectId, sessionId)
            }
            tvSession.text = getString(R.string.session_active_format, subjectId, sessionId)
            Toast.makeText(this, getString(R.string.toast_session_started), Toast.LENGTH_SHORT).show()
        }

        btnStopSession.setOnClickListener {
            lifecycleScope.launch {
                sessionManager.endSession()
                watchClient.sendSessionStop()
            }
            tvSession.text = getString(R.string.session_idle)
            Toast.makeText(this, getString(R.string.toast_session_stopped), Toast.LENGTH_SHORT).show()
        }

        btnExport.setOnClickListener {
            lifecycleScope.launch {
                val zipFile = sessionManager.exportSession()
                if (zipFile != null) {
                    Toast.makeText(this@SessionActivity,
                        getString(R.string.toast_exported, zipFile.name),
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SessionActivity,
                        getString(R.string.toast_no_session),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnLabeling.setOnClickListener {
            startActivity(Intent(this, ProtocolLabelingActivity::class.java))
        }

        btnRest.setOnClickListener {
            logAndSendEvent(EventLabels.REST_START)
        }

        btnStress.setOnClickListener {
            logAndSendEvent(EventLabels.STRESS_TASK_START)
        }

        btnSync.setOnClickListener {
            logAndSendEvent(EventLabels.SYNC_CLAPS)
            Toast.makeText(this, getString(R.string.toast_sync_event), Toast.LENGTH_SHORT).show()
        }

        // Observe BLE connection state
        lifecycleScope.launch {
            bleManager.connectionState.collect { state ->
                val statusRes = when (state) {
                    ConnectionState.DISCONNECTED -> R.string.status_disconnected
                    ConnectionState.SCANNING -> R.string.status_scanning
                    ConnectionState.CONNECTING -> R.string.status_connecting
                    ConnectionState.CONNECTED -> R.string.status_connected
                    ConnectionState.ERROR -> R.string.status_error
                }
                tvConnection.text = getString(statusRes)
            }
        }

        // Observe HR data — use indexed tracking to avoid duplicate logging
        lifecycleScope.launch {
            bleManager.ecgSamples.collect { samples ->
                if (samples.isNotEmpty()) {
                    val latest = samples.last()
                    val hrStr = latest.hrBpm?.let { getString(R.string.hr_format, it.toInt()) }
                        ?: getString(R.string.hr_display)
                    val rrStr = latest.rrMs?.let { getString(R.string.rr_format, it) } ?: ""
                    val samplesStr = getString(R.string.samples_format, samples.size)
                    tvHr.text = "$hrStr$rrStr $samplesStr"
                }

                // Log only new samples (avoid duplicates)
                if (sessionManager.isRunning() && samples.size > totalEcgSamplesReceived) {
                    val newCount = samples.size - totalEcgSamplesReceived
                    val newSamples = samples.takeLast(newCount)
                    sessionManager.logEcgSamples(newSamples)
                    totalEcgSamplesReceived = samples.size
                }
            }
        }
    }

    private fun logAndSendEvent(eventLabel: String) {
        sessionManager.logEvent(EventEntry(
            epochMs = System.currentTimeMillis(),
            eventName = eventLabel,
            source = "manual",
        ))
        lifecycleScope.launch {
            watchClient.sendEventToWatch(eventLabel)
        }
    }

    private fun checkBlePermissions() {
        val permissions = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isEmpty()) {
            bleManager.scanAndConnect()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
