package com.medicina.daedaluswatch.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import com.medicina.daedaluswatch.R

/** "Pause before walking" (design "Watch · Stand-up prompt"), shown after a detected stand-up. */
class StandPromptActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_stand_prompt)
        findViewById<View>(R.id.btn_stand_ready).setOnClickListener { finish() }
        // Close on its own if left unanswered.
        handler.postDelayed({ finish() }, 60_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
