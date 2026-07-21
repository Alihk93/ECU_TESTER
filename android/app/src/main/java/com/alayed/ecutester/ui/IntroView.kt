package com.alayed.ecutester.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.alayed.ecutester.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * ECU Tester cinematic intro — native Canvas port of the Claude Design handoff
 * (ecu-intro.jsx). All art is one composite image (R.drawable.intro_bg); each
 * "element" is a feathered-ellipse crop of that image, revealed/scaled/brightened
 * per scene. Five scenes on a fixed 1920x1080 stage, scaled-to-fit like the
 * dashboard's StageLayout:
 *   Power On (1.4) → Cars (2.4) → Emblem (2.4) → Marques (1.8) → Showtime (1.4)
 * After the last scene it rests on the interactive end state (SETTING / ENTER).
 *
 * Blur (cars focus-pull) and glow are approximated cheaply (a downscaled copy /
 * additive radial) — everything else mirrors the source math exactly.
 */
class IntroView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Fired when the animation reaches the interactive end state. */
    var onReady: (() -> Unit)? = null
    /** ENTER pressed/clicked. */
    var onEnter: (() -> Unit)? = null
    /** SETTING pressed/clicked. */
    var onSettings: (() -> Unit)? = null
    /** Which end-state button is focused: "enter" or "setting". */
    var focus = "enter"; private set

    private val W = 1920f
    private val H = 1080f

    // Global playback speed: the source is ~9.4 s; 1.6x plays it in ~5.9 s. Scaling
    // the master clock (not the per-scene numbers) keeps every reveal + audio cue in
    // proportion, so nothing gets cut off.
    private val SPEED = 1.6f

    // scene names + durations (seconds, in animation-time) — mirrors window.OM_SCENES
    private val durs = floatArrayOf(1.4f, 2.4f, 2.4f, 1.8f, 1.4f)
    private val starts = FloatArray(durs.size + 1).also {
        for (i in durs.indices) it[i + 1] = it[i] + durs[i]
    }
    private val total get() = starts.last()

    private val bmp: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.intro_bg)
    // heavily downscaled copy → cheap real blur when upscaled (cars focus-pull)
    private val blurBmp: Bitmap = Bitmap.createScaledBitmap(bmp, 384, 216, true)
    private val stage = RectF(0f, 0f, W, H)

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    private var startNs = 0L
    private var ready = false
    private var interactive = false

    // procedural sound bed; built off the main thread (generates PCM buffers).
    @Volatile private var audio: IntroAudio? = null
    private val firedCues = HashSet<String>()
    // per-scene (localTime -> cue), ported from useCues() in ecu-intro.jsx
    private val cueTable: Array<Array<Pair<Float, String>>> = arrayOf(
        arrayOf(0.05f to "ambient"),
        arrayOf(0.02f to "whoosh", 1.55f to "thump"),
        arrayOf(0.25f to "blip1", 0.62f to "blip2", 1.38f to "impact"),
        arrayOf(0.05f to "ripple"),
        arrayOf(0.45f to "chime"),
    )

    // NOTE: time the animation with System.nanoTime() only — Choreographer's
    // frameTimeNanos is a different clock base on some devices, and mixing the two
    // makes elapsed time negative (the whole intro renders black). Choreographer is
    // used purely to pace invalidate().
    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // Custom view with no onMeasure would measure to 0x0 (blank screen). Fill the
    // space the parent offers.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startNs = System.nanoTime(); ready = false; interactive = false
        firedCues.clear()
        if (audio == null) Thread { audio = IntroAudio() }.start()
        Choreographer.getInstance().postFrameCallback(ticker)
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(ticker)
        audio?.release(); audio = null
        super.onDetachedFromWindow()
    }

    /* ---------------- geometry (fractions of the 1920x1080 frame) ---------------- */
    private data class Rgn(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val feather: Float = 0.74f)
    private class RP(
        val scale: Float = 1f, val opacity: Float = 1f, val bright: Float = 1f,
        val blur: Float = 0f, val glow: Float = 0f, val dx: Float = 0f, val dy: Float = 0f,
    )

    private val circL = Rgn(0.11f, 0.22f, 0.15f, 0.27f)
    private val circR = Rgn(0.89f, 0.26f, 0.14f, 0.30f)
    private val carsR = Rgn(0.5f, 0.48f, 0.37f, 0.17f, 0.78f)
    private val plateR = Rgn(0.5f, 0.155f, 0.31f, 0.16f, 0.8f)
    private val textR = Rgn(0.487f, 0.895f, 0.29f, 0.08f)
    // reveal regions centered on the actual buttons (generous rx/ry so the feathered
    // mask covers the whole button, not just its middle)
    private val setR = Rgn(0.106f, 0.925f, 0.10f, 0.062f)
    private val entR = Rgn(0.894f, 0.925f, 0.105f, 0.062f)
    // tight button bounds (l,t,r,b fractions), measured from the art — for the focus
    // ring, which must hug the baked button, not the wider reveal ellipse.
    private val setBtnBox = floatArrayOf(0.023f, 0.879f, 0.189f, 0.971f)
    private val entBtnBox = floatArrayOf(0.817f, 0.879f, 0.971f, 0.971f)
    // No brand-logo row in the current intro art (2026 redesign replaced it with the
    // circuit-board pattern). Empty -> the Marques scene reveals nothing (frame()'s
    // logoFn loop is a no-op), so no phantom emblem pops over the background.
    private val logos = emptyArray<Rgn>()

    /* ---------------- easing ---------------- */
    private fun cl(v: Float, a: Float, b: Float) = max(a, min(b, v))
    private fun seg(t: Float, t0: Float, t1: Float, e: ((Float) -> Float)? = null): Float {
        val p = cl((t - t0) / (t1 - t0), 0f, 1f); return e?.invoke(p) ?: p
    }
    private val outCubic = { p: Float -> 1f - Math.pow((1f - p).toDouble(), 3.0).toFloat() }
    private val outQuint = { p: Float -> 1f - Math.pow((1f - p).toDouble(), 5.0).toFloat() }

    /* ---------------- one masked region of the composite ---------------- */
    private fun region(c: Canvas, r: Rgn, p: RP) {
        if (p.opacity <= 0f) return
        val ox = r.cx * W; val oy = r.cy * H

        // glow: cheap additive blue halo behind the element
        if (p.glow > 0f) {
            val gr = max(r.rx * W, r.ry * H) * (1.15f + p.scale * 0.1f)
            fx.reset(); fx.isAntiAlias = true
            fx.shader = RadialGradient(
                ox, oy, gr,
                intArrayOf(Color.argb((min(p.glow, 12f) / 12f * 150).toInt(), 96, 190, 255), 0),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(ox, oy, gr, fx)
            fx.shader = null
        }

        val layer = c.saveLayerAlpha(0f, 0f, W, H, (cl(p.opacity, 0f, 1f) * 255).toInt())
        c.save()
        c.translate(p.dx, p.dy)
        c.scale(p.scale, p.scale, ox, oy)

        imgPaint.colorFilter = brightness(p.bright)
        if (p.blur > 0f) {                       // focus-pull: blurry under, sharp over
            imgPaint.alpha = 255
            c.drawBitmap(blurBmp, null, stage, imgPaint)
            imgPaint.alpha = (cl(1f - p.blur / 18f, 0f, 1f) * 255).toInt()
            c.drawBitmap(bmp, null, stage, imgPaint)
            imgPaint.alpha = 255
        } else {
            c.drawBitmap(bmp, null, stage, imgPaint)
        }
        imgPaint.colorFilter = null

        // feathered elliptical mask (DST_IN) — skip when rx==0 (full frame)
        if (r.rx > 0f) {
            val rad = r.rx * W
            val g = RadialGradient(
                ox, oy, rad,
                intArrayOf(Color.BLACK, Color.BLACK, 0),
                floatArrayOf(0f, r.feather, 1f), Shader.TileMode.CLAMP,
            )
            val m = Matrix(); m.setScale(1f, (r.ry * H) / rad, ox, oy); g.setLocalMatrix(m)
            maskPaint.shader = g; maskPaint.xfermode = dstIn
            c.drawRect(0f, 0f, W, H, maskPaint)
            maskPaint.shader = null; maskPaint.xfermode = null
        }
        c.restore()
        c.restoreToCount(layer)
    }

    private var lastBright = -1f
    private var brightFilter: ColorMatrixColorFilter? = null
    private fun brightness(b: Float): ColorMatrixColorFilter {
        if (b != lastBright) {
            lastBright = b
            brightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
        }
        return brightFilter!!
    }

    /* ---------------- full frame compositor (z-ordered like Frame() in the jsx) ---------------- */
    private fun frame(
        c: Canvas, bg: Float, circuits: Float = 0f, groundGlow: Float = 0f,
        cars: RP? = null, plate: RP? = null, streak: Float = 0f,
        logoFn: ((Int) -> RP)? = null, text: RP? = null, setBtn: RP? = null, entBtn: RP? = null,
        full: Float = 0f, gleam: Float = 0f, flash: Float = 0f, vig: Float = 0.85f, bars: Float = 0.04f,
    ) {
        c.drawColor(Color.rgb(2, 4, 7))
        region(c, Rgn(0.5f, 0.5f, 0f, 0f), RP(bright = bg))                  // bg (full, dark)
        if (circuits > 0f) {
            region(c, circL, RP(opacity = circuits, bright = 1.35f, glow = 7f))
            region(c, circR, RP(opacity = circuits, bright = 1.35f, glow = 7f))
        }
        if (groundGlow > 0f) radial(c, 0.5f, 0.55f, 0.32f, 0.19f, Color.argb((groundGlow * 76).toInt(), 120, 200, 255))
        cars?.let { region(c, carsR, it) }
        plate?.let { region(c, plateR, it) }
        if (streak > 0f && streak < 1f) {
            val x = (-0.14f + 1.28f * streak) * W
            fx.reset(); fx.isAntiAlias = true
            fx.shader = android.graphics.LinearGradient(
                x, 0f, x + 240f, 0f, 0, Color.argb(230, 140, 220, 255), Shader.TileMode.CLAMP)
            val rr = RectF(x, H * 0.15f, x + 240f, H * 0.15f + 5f)
            c.drawRoundRect(rr, 3f, 3f, fx); fx.shader = null
        }
        logoFn?.let { for (i in logos.indices) region(c, logos[i], it(i)) }
        text?.let { region(c, textR, it) }
        setBtn?.let { region(c, setR, it) }
        entBtn?.let { region(c, entR, it) }
        if (full > 0f) region(c, Rgn(0.5f, 0.5f, 0f, 0f), RP(opacity = full))  // full bright composite
        if (gleam > 0f && gleam < 1f) {
            val x = (0.18f + 0.54f * gleam) * W
            fx.reset(); fx.isAntiAlias = true; fx.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            fx.shader = android.graphics.LinearGradient(
                x, 0f, x + 0.09f * W, 0f,
                intArrayOf(0, Color.argb(90, 210, 240, 255), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(x, 0f, x + 0.09f * W, H * 0.31f, fx); fx.shader = null; fx.xfermode = null
        }
        if (flash > 0f) {
            fx.reset(); fx.isAntiAlias = true; fx.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            fx.alpha = (cl(flash, 0f, 1f) * 255).toInt()
            fx.shader = RadialGradient(
                0.5f * W, 0.16f * H, 0.6f * W,
                intArrayOf(Color.argb(242, 200, 235, 255), Color.argb(64, 90, 160, 255), 0),
                floatArrayOf(0f, 0.55f, 0.75f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fx); fx.shader = null; fx.xfermode = null; fx.alpha = 255
        }
        if (vig > 0f) {
            fx.reset(); fx.isAntiAlias = true
            fx.shader = RadialGradient(
                0.5f * W, 0.42f * H, 1.2f * W,
                intArrayOf(0, Color.argb((cl(vig, 0f, 1f) * 224).toInt(), 0, 0, 0)),
                floatArrayOf(0.46f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fx); fx.shader = null
        }
        if (bars > 0f) {
            fx.reset(); fx.color = Color.BLACK
            c.drawRect(0f, 0f, W, bars * H, fx)
            c.drawRect(0f, H - bars * H, W, H, fx)
        }
    }

    private fun radial(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        fx.reset(); fx.isAntiAlias = true
        val rad = rx * W
        val g = RadialGradient(cx * W, cy * H, rad, intArrayOf(color, 0), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
        val m = Matrix(); m.setScale(1f, (ry * H) / rad, cx * W, cy * H); g.setLocalMatrix(m)
        fx.shader = g; c.drawRect(0f, 0f, W, H, fx); fx.shader = null
    }

    /* ---------------- scenes (ported 1:1 from ecu-intro.jsx) ---------------- */
    private fun powerOn(c: Canvas, t: Float) =
        frame(c, bg = 0.04f + 0.08f * seg(t, 0.15f, 1.3f), circuits = 0.9f * seg(t, 0.3f, 1.2f))

    private fun cars(c: Canvas, t: Float) {
        val pc = seg(t, 0f, 1.9f, outCubic)
        frame(c, bg = 0.12f, circuits = 0.9f, groundGlow = 0.5f * seg(t, 1.5f, 2.1f),
            cars = RP(scale = 0.5f + 0.5f * pc, blur = 18f * (1f - pc), bright = 0.25f + 0.75f * pc, opacity = seg(t, 0f, 0.55f)))
    }

    private fun emblem(c: Canvas, t: Float) {
        val pp = seg(t, 0.35f, 1.5f, outQuint)
        val flash = 0.5f * seg(t, 1.3f, 1.48f) * (1f - seg(t, 1.48f, 2.1f))
        frame(c, bg = 0.12f, circuits = 0.9f, groundGlow = 0.5f, cars = RP(), streak = seg(t, 0.05f, 1.05f),
            plate = RP(opacity = seg(t, 0.35f, 0.95f), scale = 1.16f - 0.16f * pp, bright = 0.7f + 0.3f * pp, glow = 10f * (1f - pp)),
            flash = flash, gleam = seg(t, 1.5f, 2.25f))
    }

    private fun marques(c: Canvas, t: Float) {
        frame(c, bg = 0.12f, circuits = 0.9f, groundGlow = 0.5f, cars = RP(), plate = RP(),
            logoFn = { i ->
                val st = 0.08f + i * 0.09f; val pi = seg(t, st, st + 0.42f, outCubic)
                RP(opacity = seg(t, st, st + 0.16f), scale = 0.35f + 0.65f * pi, bright = 1f + 0.6f * (1f - pi), glow = 9f * pi * (1f - pi) * 4f)
            })
    }

    private fun showtime(c: Canvas, t: Float) {
        val ot = seg(t, 0.05f, 0.6f, outCubic); val ob = seg(t, 0.25f, 0.9f, outCubic)
        val full = seg(t, 0.55f, 1.2f)
        frame(c, bg = 0.12f, circuits = 0.9f, groundGlow = 0.5f * (1f - full),
            bars = 0.04f * (1f - seg(t, 0.1f, 0.95f)), vig = 0.85f * (1f - seg(t, 0.2f, 1.0f)),
            cars = RP(), plate = RP(), logoFn = { RP(opacity = 1f) },
            text = RP(opacity = ot, dy = 26f * (1f - ot)),
            setBtn = RP(opacity = ob, dx = -90f * (1f - ob)), entBtn = RP(opacity = ob, dx = 90f * (1f - ob)),
            full = full)
        if (full >= 0.9f && !interactive) { interactive = true; ready = true; onReady?.invoke() }
        if (interactive) focusRing(c, t)
    }

    /* ---------------- interactive end state: pulsing focus ring ---------------- */
    private fun focusRing(c: Canvas, tGlobal: Float) {
        val b = if (focus == "setting") setBtnBox else entBtnBox
        val rect = RectF(b[0] * W, b[1] * H, b[2] * W, b[3] * H)
        val pulse = 0.5f + 0.5f * sin(tGlobal * 2f * PI.toFloat() / 1.6f)
        fx.reset(); fx.isAntiAlias = true; fx.style = Paint.Style.STROKE; fx.strokeWidth = 3f
        fx.color = Color.argb((180 + 60 * pulse).toInt(), 70, 230, 160)
        fx.maskFilter = android.graphics.BlurMaskFilter(10f + 14f * pulse, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(rect, 24f, 24f, fx)
        fx.maskFilter = null
        fx.color = Color.argb(220, 70, 230, 160)
        c.drawRoundRect(rect, 24f, 24f, fx)
        fx.style = Paint.Style.FILL
    }

    /* ---------------- draw loop ---------------- */
    override fun onDraw(canvas: Canvas) {
        val t = if (startNs == 0L) 0f else (System.nanoTime() - startNs) / 1e9f * SPEED
        val clamped = min(t, total - 0.001f)          // rest on the end state
        var i = 0
        while (i < durs.size - 1 && clamped >= starts[i + 1]) i++
        val local = clamped - starts[i]

        // fire this scene's audio cues once, as localTime crosses each. Only mark
        // fired once the (background-built) engine actually plays it, so an early
        // cue isn't dropped while the buffers are still generating.
        for ((at, name) in cueTable[i]) {
            val key = "$i:$name"
            if (local >= at && key !in firedCues) audio?.let { it.play(name); firedCues.add(key) }
        }

        val s = min(width / W, height / H)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate((width - W * s) / 2f, (height - H * s) / 2f)
        canvas.scale(s, s)
        canvas.clipRect(0f, 0f, W, H)
        when (i) {
            0 -> powerOn(canvas, local)
            1 -> cars(canvas, local)
            2 -> emblem(canvas, local)
            3 -> marques(canvas, local)
            else -> showtime(canvas, local)
        }
        canvas.restore()
    }

    /* ---------------- input ---------------- */
    private fun skipToEnd() { startNs = System.nanoTime() - (((total - 0.05f) / SPEED) * 1e9f).toLong() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!interactive) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { skipToEnd(); return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { focus = "setting"; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { focus = "enter"; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                { audio?.play("click"); if (focus == "enter") onEnter?.invoke() else onSettings?.invoke(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        if (!interactive) { skipToEnd(); return true }
        val s = min(width / W, height / H)
        val fx0 = (e.x - (width - W * s) / 2f) / s / W
        val fy0 = (e.y - (height - H * s) / 2f) / s / H
        fun hit(r: Rgn) = kotlin.math.abs(fx0 - r.cx) < r.rx * 0.9f && kotlin.math.abs(fy0 - r.cy) < r.ry * 0.9f
        when {
            hit(entR) -> { audio?.play("click"); focus = "enter"; onEnter?.invoke() }
            hit(setR) -> { audio?.play("click"); focus = "setting"; onSettings?.invoke() }
        }
        return true
    }
}
