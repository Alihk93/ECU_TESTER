package com.alayed.ecutester.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * CKP / CMP1 / CMP2 oscilloscope — a parametric standing display, ported from
 * web/app.js. Three clean square waves whose frequency tracks RPM; redrawn ONLY
 * when the RPM-frequency bucket changes (no continuous loop) — the single biggest
 * FPS lever on the web side, kept here.
 */
class ScopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private data class Lane(val duty: Float, val amp: Float, val freqBase: Float, val freqRpm: Float)
    private val lanes = arrayOf(
        Lane(0.50f, 0.84f, 9f, 20f),   // CKP
        Lane(0.30f, 0.62f, 3f, 7f),    // CMP1
        Lane(0.46f, 0.74f, 5f, 11f),   // CMP2
    )

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3fe7e1"); style = Paint.Style.STROKE
        strokeWidth = 2f; strokeJoin = Paint.Join.MITER
    }

    private var rpm = 0
    private var bucket = -1

    /** Safe to call at telemetry rate — only invalidates on a frequency-bucket change. */
    fun setRpm(v: Int) {
        rpm = v.coerceIn(0, 8000)
        val b = Math.round(rpmFactor() * 24)
        if (b != bucket) { bucket = b; invalidate() }
    }

    private fun rpmFactor() = (rpm / 8000f).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val laneH = h / 3f; val f = rpmFactor()
        for (i in 0..2) {
            val L = lanes[i]
            strokeSquare(canvas, w, L.freqBase + f * L.freqRpm, L.duty, L.amp,
                i * laneH + laneH / 2f, laneH * 0.36f)
        }
    }

    private fun strokeSquare(c: Canvas, w: Float, cycles: Float, duty: Float, ampFrac: Float, yMid: Float, halfMax: Float) {
        val half = ampFrac * halfMax; val yHi = yMid - half; val yLo = yMid + half
        val period = w / max(0.5f, cycles)
        val p = Path()
        p.moveTo(0f, yLo)
        var x = 0f
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
