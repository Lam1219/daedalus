package com.medicina.daedaluswatch.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.medicina.daedaluswatch.R

/**
 * "Pause before walking" (design "Watch · Stand-up prompt"), shown after a detected stand-up.
 *
 * Home pilot (B10): with [EXTRA_POCKET] it also says "Phone in your front pocket?". When the
 * stand-up prompt is off ([EXTRA_POCKET_ONLY]) that question is the whole screen.
 */
class StandPromptActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_stand_prompt)
        findViewById<View>(R.id.btn_stand_ready).setOnClickListener { finish() }
        applyPocket(intent)
        // Close on its own if left unanswered.
        handler.postDelayed({ finish() }, 60_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyPocket(intent)
    }

    private fun applyPocket(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_POCKET, false) != true) return
        if (intent.getBooleanExtra(EXTRA_POCKET_ONLY, false)) {
            findViewById<TextView>(R.id.tv_stand_title).setText(R.string.pilot_pocket_title)
            findViewById<TextView>(R.id.tv_stand_sub).setText(R.string.pilot_pocket_sub)
        } else {
            findViewById<View>(R.id.tv_stand_pocket).visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_POCKET = "pocket"
        const val EXTRA_POCKET_ONLY = "pocket_only"
        @Volatile var isVisible = false
            private set
    }
}
