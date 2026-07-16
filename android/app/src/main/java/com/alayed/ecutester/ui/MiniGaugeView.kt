package com.alayed.ecutester.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One 0–5 V mini gauge (CTS/MAF/MAP/IAT/5V/IGF). Geometry ported from web/app.js
 * (MG dial in a 140×106 viewBox). Needle glides with a ValueAnimator; the whole
 * tile redraws per frame only while a value settles (cheap on the GPU).
 */
class MiniGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val vbW = 140f
    private val vbH = 106f
    private val cx = 70f; private val cy = 60f; private val r = 46f
    private val start = 200f; private val span = 220f

    var label: String = ""
    private var volts = 0f
    private var displayed = 0f
    private var anim: ValueAnimator? = null

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9298a0"); style = Paint.Style.STROKE
        strokeWidth = 1.2f; strokeCap = Paint.Cap.ROUND
    }
    private val redline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff4a1e"); style = Paint.Style.STROKE
        strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#c07a38") }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#b6bbc0"); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f0f3f5"); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun setVolts(v: Float) {
        volts = v.coerceIn(0f, 5f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(displayed, volts).apply {
            duration = 120; interpolator = DecelerateInterpolator()
            addUpdateListener { displayed = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun pt(rr: Float, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        return floatArrayOf(cx + rr * cos(a).toFloat(), cy - rr * sin(a).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width / vbW, height / vbH)
        canvas.save()
        canvas.translate((width - vbW * s) / 2f, (height - vbH * s) / 2f)
        canvas.scale(s, s)

        // ticks (21, major every 5)
        for (i in 0..20) {
            val deg = start - (i / 20f) * span
            val p1 = pt(r - 1.5f, deg)
            val p2 = pt(r - (if (i % 5 == 0) 8f else 4.5f), deg)
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], tick)
        }

        // red limit arc at the high (5 V) end — like the RPM redline (last ~15%)
        val vRed = 4.25f
        val red = Path()
        var vv = vRed
        var first = true
        while (vv <= 5.0001f) {
            val p = pt(r - 1f, start - (vv / 5f) * span)
            if (first) { red.moveTo(p[0], p[1]); first = false } else red.lineTo(p[0], p[1])
            vv += 0.1f
        }
        canvas.drawPath(red, redline)

        // name + value
        namePaint.textSize = 18f
        canvas.drawText(label, cx, cy - 8f, namePaint)
        valuePaint.textSize = 26f
        // Locale.US: Arabic-locale TVs render %f as Eastern-Arabic digits (see Dashboard.kt)
        canvas.drawText("%.2f".format(Locale.US, displayed), cx, cy + 40f, valuePaint)

        // needle at the current voltage angle
        val a = Math.toRadians((start - (displayed / 5f) * span).toDouble())
        val dx = cos(a).toFloat(); val dy = -sin(a).toFloat()
        val L = 37f; val w = 1.9f; val tail = 5f
        val px = -dy; val py = dx
        val path = Path().apply {
            moveTo(cx + w * px - tail * dx, cy + w * py - tail * dy)
            lineTo(cx + L * dx, cy + L * dy)
            lineTo(cx - w * px - tail * dx, cy - w * py - tail * dy)
            close()
        }
        canvas.drawPath(path, needle)
        canvas.restore()
    }
}
