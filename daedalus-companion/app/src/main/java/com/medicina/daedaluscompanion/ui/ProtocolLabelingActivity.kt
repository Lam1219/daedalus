package com.medicina.daedaluscompanion.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.medicina.daedaluscompanion.R
import com.medicina.daedaluscompanion.session.BlockCategory
import com.medicina.daedaluscompanion.session.ManualAnnotationManager
import com.medicina.daedaluscompanion.session.ProtocolBlock
import com.medicina.daedaluscompanion.session.ProtocolBlocks
import com.medicina.daedaluscompanion.session.SessionManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Comprehensive event labeling UI for all 10 protocol blocks.
 * Replaces video recording by letting the user tap Start/Stop for each block.
 *
 * Organized by tabs: Setup | Rest | Breathing | Stress | Movement | Tremor | Gait | Haptic
 */
class ProtocolLabelingActivity : ComponentActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var annotationManager: ManualAnnotationManager

    private var activeBlockStartTime: Long = 0
    private var currentActiveBlock: ProtocolBlock? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protocol_labeling)

        sessionManager = SessionManager.getInstance(this)
        annotationManager = ManualAnnotationManager.getInstance(this)

        // Initialize annotation file when session starts
        sessionManager.getSessionDir()?.let { dir ->
            annotationManager.startSession(dir)
        }

        setupTabs()
        updateSessionStatus()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        val contentContainer = findViewById<android.widget.LinearLayout>(R.id.content_container)

        // Add tabs for each category
        val categories = listOf(
            BlockCategory.SETUP, BlockCategory.SYNC, BlockCategory.REST,
            BlockCategory.BREATHING, BlockCategory.STRESS, BlockCategory.MOVEMENT,
            BlockCategory.TREMOR, BlockCategory.GAIT, BlockCategory.HAPTIC,
            BlockCategory.RECOVERY,
        )

        for (category in categories) {
            val tab = tabLayout.newTab()
            tab.text = category.label
            tabLayout.addTab(tab)
        }

        // Show initial category
        showCategory(BlockCategory.SETUP, contentContainer)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val category = categories[tab.position]
                showCategory(category, contentContainer)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showCategory(category: BlockCategory, container: android.widget.LinearLayout) {
        container.removeAllViews()

        val blocks = ProtocolBlocks.getByCategory(category)
        if (blocks.isEmpty()) return

        for (block in blocks) {
            val blockView = layoutInflater.inflate(R.layout.item_protocol_block, container, false)
            bindBlockView(blockView, block)
            container.addView(blockView)
        }
    }

    private fun bindBlockView(view: android.view.View, block: ProtocolBlock) {
        val tvName = view.findViewById<TextView>(R.id.tv_block_name)
        val tvDesc = view.findViewById<TextView>(R.id.tv_block_desc)
        val tvDuration = view.findViewById<TextView>(R.id.tv_block_duration)
        val btnStart = view.findViewById<Button>(R.id.btn_block_start)
        val btnStop = view.findViewById<Button>(R.id.btn_block_stop)

        tvName.text = block.displayName
        tvDesc.text = block.description
        tvDuration.text = "Target: ${block.defaultDurationSec / 60} min"

        val isThisBlockActive = currentActiveBlock == block

        if (isThisBlockActive) {
            btnStart.isEnabled = false
            btnStart.text = "Started at ${timeFormat.format(Date(activeBlockStartTime))}"
            btnStop.isEnabled = true
            btnStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        } else {
            val anyBlockActive = currentActiveBlock != null
            btnStart.isEnabled = !anyBlockActive
            btnStart.text = if (anyBlockActive) "Another block active" else "▶ Start"
            btnStop.isEnabled = false
        }

        btnStart.setOnClickListener {
            startBlock(block, btnStart, btnStop)
        }

        btnStop.setOnClickListener {
            stopBlock(block, btnStart, btnStop)
        }
    }

    private fun startBlock(block: ProtocolBlock, btnStart: Button, btnStop: Button) {
        if (currentActiveBlock != null) return

        activeBlockStartTime = System.currentTimeMillis()
        currentActiveBlock = block

        // Log the start event
        sessionManager.logEvent(com.medicina.daedaluscompanion.model.EventEntry(
            epochMs = activeBlockStartTime,
            eventName = block.startEvent,
            source = "manual",
            notes = "${block.displayName} - ${block.description}",
        ))

        // Log annotation
        annotationManager.logProtocolBlock(block, activeBlockStartTime)

        btnStart.isEnabled = false
        btnStart.text = "Started at ${timeFormat.format(Date(activeBlockStartTime))}"
        btnStop.isEnabled = true
        btnStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))

        Toast.makeText(this, "${block.displayName} started", Toast.LENGTH_SHORT).show()
    }

    private fun stopBlock(block: ProtocolBlock, btnStart: Button, btnStop: Button) {
        if (currentActiveBlock != block) return

        val endTime = System.currentTimeMillis()

        // Log the end event
        sessionManager.logEvent(com.medicina.daedaluscompanion.model.EventEntry(
            epochMs = endTime,
            eventName = block.endEvent,
            source = "manual",
            notes = "${block.displayName} ended",
        ))

        // Log annotation with actual duration
        annotationManager.logProtocolBlock(block, activeBlockStartTime, endTime)

        val durationSec = (endTime - activeBlockStartTime) / 1000
        Toast.makeText(this, "${block.displayName} stopped (${durationSec}s)", Toast.LENGTH_LONG).show()

        currentActiveBlock = null
        activeBlockStartTime = 0

        btnStart.isEnabled = true
        btnStart.text = "▶ Start"
        btnStop.isEnabled = false

        // Refresh all blocks in current tab
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        val contentContainer = findViewById<android.widget.LinearLayout>(R.id.content_container)
        val selectedCategory = BlockCategory.entries.find { it.ordinal == tabLayout.selectedTabPosition }
            ?: BlockCategory.SETUP
        showCategory(selectedCategory, contentContainer)
    }

    private fun updateSessionStatus() {
        val tvSessionStatus = findViewById<TextView>(R.id.tv_session_status)
        if (sessionManager.isRunning()) {
            val meta = sessionManager.getMetadata()
            tvSessionStatus.text = "Session: ${meta?.subjectId}/${meta?.sessionId}"
        } else {
            tvSessionStatus.text = "No active session"
        }
    }
}

/** Human-readable label for block categories */
val BlockCategory.label: String
    get() = when (this) {
        BlockCategory.SETUP -> "Setup"
        BlockCategory.SYNC -> "Sync"
        BlockCategory.REST -> "Rest"
        BlockCategory.BREATHING -> "Breath"
        BlockCategory.STRESS -> "Stress"
        BlockCategory.MOVEMENT -> "Motion"
        BlockCategory.TREMOR -> "Tremor"
        BlockCategory.GAIT -> "Gait"
        BlockCategory.HAPTIC -> "Haptic"
        BlockCategory.RECOVERY -> "Recovery"
    }
