package com.medicina.daedaluswatch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.medicina.daedaluswatch.R
import kotlin.math.min

/** Walking cue ring (design "Watch · Walking cue"): track, a sage arc from the top, and a dot that pulses on each beat. */
class RingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = 5f * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = context.getColor(R.color.watch_surface)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = context.getColor(R.color.watch_sage)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.watch_sage) }
    private val oval = RectF()

    /** 0..1 — how far the arc runs round from the top. */
    var progress = 0.17f
        set(value) { field = value; invalidate() }

    /** 0..1 — beat emphasis; the dot grows with it. */
    var beat = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - resources.getDimension(R.dimen.ring_inset)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, track)
        canvas.drawArc(oval, -90f, 360f * progress, false, arc)
        canvas.drawCircle(cx, cy - r, (6f + 2.5f * beat) * density, dot)
    }
}

/** Breathing cue circles (design "Watch · Breathing cue"): three sage rings that grow on the inhale. */
class BreathView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val sage = context.getColor(R.color.watch_sage)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 0 = fully out, 1 = fully in. */
    var fullness = 0.5f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - 10f * density
        val scale = 0.82f + 0.18f * fullness
        listOf(75f to 0.08f, 56f to 0.14f, 38f to 0.22f).forEach { (radius, alpha) ->
            paint.color = sage
            paint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(cx, cy, radius * density * scale, paint)
        }
    }
}
