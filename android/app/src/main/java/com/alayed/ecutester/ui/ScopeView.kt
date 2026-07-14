package com.alayed.ecutester.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.max

/**
 * CKP / CMP1 / CMP2 (and, reconfigured, CAN HI/LO) oscilloscope. Clean square waves
 * that SCROLL smoothly right-to-left like a real scope. Frequency (and scroll speed)
 * track RPM for the crank traces; the CAN traces run at a constant decorative rate.
 *
 * Continuous 60 fps redraw is deliberate here: this is the native app, GPU-composited
 * — the "static, redraw-only-on-bucket-change" rule was a TV-browser (software render)
 * workaround, which no longer applies.
 */
class ScopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    class Lane(val duty: Float, val amp: Float, val freqBase: Float, val freqRpm: Float)

    // default = CKP / CMP1 / CMP2 (cyan, RPM-driven)
    private var lanes = arrayOf(
        Lane(0.50f, 0.84f, 9f, 20f),
        Lane(0.30f, 0.62f, 3f, 7f),
        Lane(0.46f, 0.74f, 5f, 11f),
    )
    private var rpmDriven = true

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3fe7e1"); style = Paint.Style.STROKE
        strokeWidth = 2f; strokeJoin = Paint.Join.MITER
    }

    private var rpm = 0
    private var phase = 0f            // horizontal scroll offset (px)
    private var lastFrameNs = 0L
    private var running = false

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0.016f else (now - lastFrameNs) / 1e9f
            lastFrameNs = now
            phase += pxPerSec() * dt
            val w = width.coerceAtLeast(1)
            if (phase > w * 1000f) phase %= w.toFloat()   // keep it bounded
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 2 green CAN lanes at a constant decorative rate. */
    fun configureCan() {
        lanes = arrayOf(Lane(0.50f, 0.72f, 12f, 0f), Lane(0.40f, 0.72f, 16f, 0f))
        rpmDriven = false
        stroke.color = Color.parseColor("#83cf92")
        invalidate()
    }

    fun setRpm(v: Int) { rpm = v.coerceIn(0, 8000) }

    private fun rpmFactor() = (rpm / 8000f).coerceIn(0f, 1f)

    // scroll speed: crank traces speed up with RPM; CAN is a slow constant crawl
    private fun pxPerSec(): Float {
        val w = width.toFloat()
        return if (rpmDriven) w * (0.12f + rpmFactor() * 0.55f) else w * 0.10f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true; lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(ticker)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && !running) {
            running = true; lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(ticker)
        } else if (visibility != VISIBLE) {
            running = false
            Choreographer.getInstance().removeFrameCallback(ticker)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val laneH = h / lanes.size; val f = rpmFactor()
        for (i in lanes.indices) {
            val L = lanes[i]
            val cycles = if (rpmDriven) L.freqBase + f * L.freqRpm else L.freqBase
            strokeSquare(canvas, w, cycles, L.duty, L.amp, i * laneH + laneH / 2f, laneH * 0.36f)
        }
    }

    private fun strokeSquare(c: Canvas, w: Float, cycles: Float, duty: Float, ampFrac: Float, yMid: Float, halfMax: Float) {
        val half = ampFrac * halfMax; val yHi = yMid - half; val yLo = yMid + half
        val period = w / max(0.5f, cycles)
        val p = Path()
        var x = -(phase % period)          // scrolls left as phase grows
        p.moveTo(x, yLo)
        while (x < w) {
            val xRise = x + period * (1 - duty); val xFall = x + period
            p.lineTo(min(xRise, w), yLo)
            if (xRise < w) p.lineTo(xRise, yHi)
            p.lineTo(min(xFall, w), yHi)
            if (xFall < w) p.lineTo(xFall, yLo)
            x += period
        }
        c.drawPath(p, stroke)
    }

    private fun min(a: Float, b: Float) = if (a < b) a else b
}
