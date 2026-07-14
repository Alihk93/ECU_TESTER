package com.alayed.ecutester.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * RPM dial — the M1 vertical-slice component (docs/ANDROID_MIGRATION.md §9).
 *
 * Geometry ported from web/app.js: 0 rpm at lower-left (225° clockwise from top),
 * 270° sweep to 8000 rpm, redline 6500–8000. The needle GLIDES to each new value
 * with a short ValueAnimator — on Android's GPU compositor a per-frame vector
 * redraw of a dial this simple is effectively free, so we get the smooth needle
 * the TV browser couldn't afford. The whole face is drawn in onDraw; there is no
 * continuous loop (it animates only while a new value settles).
 */
class RpmDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val vb = 400f                         // design viewBox (matches app.js)
    private var displayedRpm = 0f                 // animated value actually drawn
    private var animator: ValueAnimator? = null

    private val faceBg = paint(Color.parseColor("#22374f"), Paint.Style.FILL)
    private val bezel = paint(Color.parseColor("#8f9aa8"), Paint.Style.FILL)
    private val majorTick = paint(Color.parseColor("#e6ecf2"), Paint.Style.STROKE, 3f)
    private val minorTick = paint(Color.parseColor("#5c6773"), Paint.Style.STROKE, 1.5f)
    private val redline = paint(Color.parseColor("#ff4a1e"), Paint.Style.STROKE, 6f).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val needle = paint(Color.parseColor("#ff5a1e"), Paint.Style.FILL)
    private val hubOuter = paint(Color.parseColor("#15181c"), Paint.Style.FILL)
    private val hubRing = paint(Color.parseColor("#ff5a1e"), Paint.Style.STROKE, 2.5f)
    private val hubInner = paint(Color.parseColor("#ff5a1e"), Paint.Style.FILL)
    private val labelPaint = paint(Color.parseColor("#cdd6e0"), Paint.Style.FILL).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val titlePaint = paint(Color.parseColor("#ff5a1e"), Paint.Style.FILL).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val readingPaint = paint(Color.parseColor("#f0f5fa"), Paint.Style.FILL).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun paint(c: Int, style: Paint.Style, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; this.style = style; strokeWidth = stroke
    }

    /** angle (deg, clockwise from 12 o'clock) for an rpm value — app.js rpmAngle */
    private fun rpmAngle(v: Float) = 225f + (v.coerceIn(0f, 8000f) / 8000f) * 270f

    /** Set the target rpm; the needle glides to it. Safe to call at telemetry rate. */
    fun setRpm(rpm: Int) {
        val target = rpm.coerceIn(0, 8000).toFloat()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedRpm, target).apply {
            duration = 120                        // ~ the coalesced telemetry interval
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedRpm = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val s = size / vb                          // viewBox -> pixels
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(s, s)

        val cx = 200f; val cy = 200f

        // bezel + face
        canvas.drawCircle(cx, cy, 196f, bezel)
        canvas.drawCircle(cx, cy, 184f, faceBg)

        // ticks every 250 rpm, major every 1000, with number labels 0..8
        labelPaint.textSize = 26f
        var v = 0
        while (v <= 8000) {
            val major = v % 1000 == 0
            val a = Math.toRadians(rpmAngle(v.toFloat()).toDouble())
            val ox = cx + 184f * sin(a).toFloat(); val oy = cy - 184f * cos(a).toFloat()
            val ir = if (major) 158f else 171f
            val ix = cx + ir * sin(a).toFloat(); val iy = cy - ir * cos(a).toFloat()
            canvas.drawLine(ox, oy, ix, iy, if (major) majorTick else minorTick)
            if (major) {
                val lr = 132f
                val lx = cx + lr * sin(a).toFloat()
                val ly = cy - lr * cos(a).toFloat() + 9f   // +baseline nudge
                canvas.drawText((v / 1000).toString(), lx, ly, labelPaint)
            }
            v += 250
        }

        // redline arc 6500 -> 8000
        val arc = RectF(cx - 176f, cy - 176f, cx + 176f, cy + 176f)
        val start = rpmAngle(6500f) - 90f          // canvas arcs are CW from +x
        val sweep = rpmAngle(8000f) - rpmAngle(6500f)
        canvas.drawArc(arc, start, sweep, false, redline)

        // needle: base points to 12 o'clock, rotate by rpmAngle(displayedRpm)
        canvas.save()
        canvas.rotate(rpmAngle(displayedRpm), cx, cy)
        val path = android.graphics.Path().apply {
            moveTo(200f, 58f); lineTo(206f, 200f); lineTo(194f, 200f); close()
        }
        canvas.drawPath(path, needle)
        val stem = Paint(needle).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(200f, 200f, 200f, 252f, stem)
        canvas.restore()

        // hub
        canvas.drawCircle(cx, cy, 16f, hubOuter)
        canvas.drawCircle(cx, cy, 16f, hubRing)
        canvas.drawCircle(cx, cy, 6f, hubInner)

        // center text block (title / ×1000 / reading)
        titlePaint.textSize = 30f
        canvas.drawText("RPM", cx, cy + 74f, titlePaint)
        readingPaint.textSize = 23f
        canvas.drawText(commafy(displayedRpm.toInt()), cx, cy + 118f, readingPaint)

        canvas.restore()
    }

    private fun commafy(n: Int): String {
        val s = n.toString(); val sb = StringBuilder(); var c = 0
        for (i in s.length - 1 downTo 0) {
            sb.append(s[i]); c++
            if (c % 3 == 0 && i > 0) sb.append(',')
        }
        return sb.reverse().toString()
    }
}
