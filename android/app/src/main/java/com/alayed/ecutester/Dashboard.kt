package com.alayed.ecutester

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.alayed.ecutester.ui.MiniGaugeView
import com.alayed.ecutester.ui.RpmDialView
import com.alayed.ecutester.ui.ScopeView
import java.util.Locale

/**
 * View controller for the cluster — the native equivalent of web/app.js. Holds the
 * inflated references, builds the repeating cells (status grid, indicators, mini
 * gauges, banks) in code, and maps decoded telemetry onto everything. Views are
 * authored in the fixed 1920×1080 px space (StageLayout scales), so code-built
 * LayoutParams use raw pixels.
 */
class Dashboard(private val root: View) {

    private val res = root.resources

    // top bar
    private val deviceLed = root.findViewById<View>(R.id.device_led)
    private val deviceStatus = root.findViewById<TextView>(R.id.device_status)
    private val voltage = root.findViewById<TextView>(R.id.voltage)
    private val current = root.findViewById<TextView>(R.id.current)

    // gauges + scope (from XML)
    private val dial = root.findViewById<RpmDialView>(R.id.rpm_dial)
    private val scope = root.findViewById<ScopeView>(R.id.scope_view)
    private val canScope = root.findViewById<ScopeView>(R.id.can_scope)

    // built cells
    private class StatusCell(val box: View, val img: ImageView, val red: Boolean)
    private val statusCells = HashMap<String, StatusCell>()
    private var hipHead: ImageView? = null       // red-hot HIP head overlay (high pressure)
    private var hipOn: Boolean? = null
    private val hipTimer = Handler(Looper.getMainLooper())
    private var hipPressure = false
    private val iacLeds = ArrayList<View>()

    // fans: eased angular velocity (soft start-up to top speed, coast-down to stop)
    private class Fan(val img: ImageView) { var on = false; var angle = 0f; var speed = 0f }
    private val fans = HashMap<String, Fan>()
    private var fanLoopScheduled = false
    private var fanLast = 0L

    private class Indicator(
        val label: TextView, val circle: View, val img: ImageView,
        val base: String,        // text stem (name minus -ON/-OFF)
        val relay: Boolean,      // has the two-contact symbol that flips open/closed
        val fixedText: Boolean,  // MRC+/MRC- keep their text
    )
    private val indicators = HashMap<String, Indicator>()
    private var indicatorsOn = false
    private val indicatorTimer = Handler(Looper.getMainLooper())

    private val gauges = HashMap<String, MiniGaugeView>()

    private class Bank(val overlay: ImageView, val spark: Boolean,
                       var lastActive: Boolean = false, var anim: ObjectAnimator? = null)
    private val banks = HashMap<String, Array<Bank>>()
    private var currentRpm = 0

    init {
        buildStatusGrid()
        buildIndicators()
        buildMiniGauges()
        buildBanks()
        canScope.configureCan()   // green, constant-rate, scrolling
        // CTS/IGF have no protocol v1 field — pinned at 0 like the web
        gauges["CTS"]?.setVolts(0f)
        gauges["IGF"]?.setVolts(0f)
        startHipToggle()
        startIndicatorToggle()
    }

    // Demo: pulse the HIP high-pressure head on/off every 10 s (decoupled from the
    // fuel-pump bit, which the sim holds on). Starts lit, then flips each interval.
    private fun startHipToggle() {
        hipPressure = true
        setHipHead(true)
        hipTimer.postDelayed(object : Runnable {
            override fun run() {
                hipPressure = !hipPressure
                setHipHead(hipPressure)
                hipTimer.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    private fun setHipHead(on: Boolean) {
        if (hipOn == on) return
        hipOn = on
        hipHead?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(300)?.start()
    }

    /* ---------------- helpers ---------------- */
    private fun llp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h).apply { this.weight = weight }

    private fun color(hex: String) = Color.parseColor(hex)

    /* ---------------- status grid ---------------- */
    private fun buildStatusGrid() {
        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val grid = root.findViewById<LinearLayout>(R.id.status_grid)

        // Both fans share ONE box — no outline (client feedback).
        val fanBox = LinearLayout(root.context)
        fanBox.orientation = LinearLayout.HORIZONTAL
        fanBox.setPadding(8, 8, 8, 8)
        grid.addView(fanBox, llp(0, mp, 2f))   // spans what the two fan cells did
        for ((idx, key) in listOf("fan1", "fan2").withIndex()) {
            val cell = FrameLayout(root.context)
            fanBox.addView(cell, llp(0, mp, 1f))
            val img = ImageView(root.context)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(R.drawable.fan)
            cell.addView(img, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            cell.addView(makeBadge((idx + 1).toString()))
            fans[key] = Fan(img)
        }

        // imo / hip / iac — separate cells, no outline (client feedback)
        data class Cfg(val key: String, val img: Int, val weight: Float, val red: Boolean, val leds: Boolean)
        val cfgs = listOf(
            Cfg("imo", R.drawable.imo2, 1f, true, false),
            Cfg("hip", R.drawable.hip, 1f, true, false),
            Cfg("iac", R.drawable.iac, 2f, false, true),
        )
        for (c in cfgs) {
            val box = FrameLayout(root.context)
            box.setPadding(8, 8, 8, 8)
            grid.addView(box, llp(0, mp, c.weight).apply { marginStart = 12 })

            val img = ImageView(root.context)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(c.img)
            // IMO icon reads dull (#F1331D) — tint to the vivid battery red
            if (c.key == "imo") img.setColorFilter(color("#FF4A3D"), android.graphics.PorterDuff.Mode.SRC_IN)
            box.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                if (c.leds) bottomMargin = 26
            })
            statusCells[c.key] = StatusCell(box, img, c.red)

            // HIP: a red-hot "head" overlay (top dome), faded in when high pressure is on.
            if (c.key == "hip") {
                val head = ImageView(root.context)
                head.scaleType = ImageView.ScaleType.FIT_CENTER   // same fit -> aligns to base
                head.setImageBitmap(makeRedHead())
                head.alpha = 0f
                box.addView(head, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                hipHead = head
            }

            if (c.leds) {
                val row = LinearLayout(root.context)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER
                box.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 15, Gravity.BOTTOM))
                for (k in 0 until 4) {
                    val led = View(root.context)
                    led.setBackgroundResource(R.drawable.bd_iac_on)
                    val llp = LinearLayout.LayoutParams(15, 15)
                    if (k > 0) llp.marginStart = 22
                    row.addView(led, llp)
                    iacLeds.add(led)
                }
            }
        }
    }

    // Build the "red-hot head" overlay: the pump image tinted red (MULTIPLY keeps the
    // metallic shading) and masked to just the top dome (the head), fading out at the
    // neck. Same source + FIT_CENTER as the base image, so it aligns exactly.
    private fun makeRedHead(): Bitmap {
        val src = BitmapFactory.decodeResource(res, R.drawable.hip)
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = PorterDuffColorFilter(color("#FF4A3D"), PorterDuff.Mode.MULTIPLY)
        cv.drawBitmap(src, 0f, 0f, p)
        p.colorFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)   // keep only the top dome
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.33f), Shader.TileMode.CLAMP)   // dome/head only, clear of the collar
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // also clear the left-hand fuel fitting ("pip"): keep red only right of ~27%
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 0.24f, 0.27f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return out
    }

    // Plain white number in the top-left corner (no badge box).
    private fun makeBadge(text: String): TextView {
        val b = TextView(root.context)
        b.text = text
        b.setTextColor(color("#ffffff"))
        b.textSize = 13f
        b.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)) // thin digit
        b.includeFontPadding = false
        b.setPadding(0, 0, 0, 0)
        b.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { leftMargin = 5; topMargin = 3 }
        return b
    }

    /* ---------------- indicator row ---------------- */
    private fun buildIndicators() {
        val host = root.findViewById<LinearLayout>(R.id.indicators)
        // base = text stem (name minus -ON/-OFF); relay = flips open/closed.
        // MRC+/MRC- keep their text (base == name). BAT (battery) + SW (key) don't flip.
        data class Cfg(val name: String, val base: String, val relay: Boolean, val img: Int)
        val cfgs = listOf(
            Cfg("BAT-ON", "BAT", false, R.drawable.bat),
            Cfg("SW-ON", "SW", false, R.drawable.swon2),
            Cfg("MRC+", "MRC+", true, R.drawable.relay_no),
            Cfg("MRC-", "MRC-", true, R.drawable.relay_no),
            Cfg("ETC-ON", "ETC", true, R.drawable.relay_no),
            Cfg("ST-OFF", "ST", true, R.drawable.relay_no),
            Cfg("PFC-OFF", "PFC", true, R.drawable.relay_no),
        )
        // Equal weighted columns (never overflow the panel); text/color/symbol are
        // driven by the 10 s startIndicatorToggle (setIndicatorsState), not built here.
        for (c in cfgs) {
            val col = LinearLayout(root.context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            host.addView(col, llp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val label = TextView(root.context)
            label.setSingleLine()          // one row
            label.gravity = Gravity.CENTER
            label.setTypeface(label.typeface, Typeface.BOLD)
            // fit each name to its narrow column on a single line
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                label, 10, 18, 1, android.util.TypedValue.COMPLEX_UNIT_PX)
            col.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val circle = FrameLayout(root.context)
            col.addView(circle, LinearLayout.LayoutParams(56, 56).apply { topMargin = 6 })

            val img = ImageView(root.context)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(c.img)
            circle.addView(img, FrameLayout.LayoutParams(44, 44, Gravity.CENTER))

            indicators[c.name] = Indicator(label, circle, img, c.base, c.relay, c.base == c.name)
        }
    }

    // Demo: toggle all seven indicators together every 10 s (overrides live status).
    //   ON  -> "-ON" text,  red  text+symbol, relay contacts CLOSED (relay_no art)
    //   OFF -> "-OFF" text, green text+symbol, relay contacts OPEN  (relay_nc art)
    // MRC+/MRC- keep their text; BAT/SW don't flip their symbol (not relay contacts).
    private fun startIndicatorToggle() {
        setIndicatorsState(indicatorsOn)
        indicatorTimer.postDelayed(object : Runnable {
            override fun run() {
                indicatorsOn = !indicatorsOn
                setIndicatorsState(indicatorsOn)
                indicatorTimer.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    private fun setIndicatorsState(on: Boolean) {
        val tint = if (on) color("#FF4A3D") else color("#55e06a")
        val ring = if (on) R.drawable.bd_indic_circle_red else R.drawable.bd_indic_circle_green
        for (ind in indicators.values) {
            ind.label.text = if (ind.fixedText) ind.base else ind.base + if (on) "-ON" else "-OFF"
            ind.label.setTextColor(tint)
            if (ind.relay) ind.img.setImageResource(if (on) R.drawable.relay_no else R.drawable.relay_nc)
            ind.img.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            ind.circle.setBackgroundResource(ring)
        }
    }

    /* ---------------- mini gauges ---------------- */
    private fun buildMiniGauges() {
        val host = root.findViewById<LinearLayout>(R.id.mini_gauges)
        val order = listOf("CTS", "MAF", "MAP", "IAT", "5V", "IGF")
        var idx = 0
        for (r in 0 until 3) {
            val row = LinearLayout(root.context)
            row.orientation = LinearLayout.HORIZONTAL
            val rlp = llp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            if (r > 0) rlp.topMargin = 10
            host.addView(row, rlp)
            for (col in 0 until 2) {
                val name = order[idx++]
                val tile = FrameLayout(root.context)   // no outline (client feedback)
                val tlp = llp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                if (col > 0) tlp.marginStart = 10
                row.addView(tile, tlp)

                val g = MiniGaugeView(root.context)
                g.label = name
                tile.addView(g, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    setMargins(6, 8, 6, 8)
                })
                gauges[name] = g
            }
        }
    }

    /* ---------------- output banks ---------------- */
    private fun buildBanks() {
        // sprayDx: px nudge so the spray sits under the nozzle, which isn't centered
        // in the component PNG (injector nozzle +6.6% right, gdi -10.2% left of center).
        data class Cfg(val kind: String, val host: Int, val img: Int, val overlay: Int, val sprayDx: Int)
        val cfgs = listOf(
            Cfg("coil", R.id.bank_coil, R.drawable.coil, R.drawable.spark_bolt, 0),
            Cfg("inj", R.id.bank_inj, R.drawable.injector, R.drawable.spray, 4),
            Cfg("gdi", R.id.bank_gdi, R.drawable.gdi, R.drawable.spray_gdi, -5),
        )
        for (c in cfgs) {
            val host = root.findViewById<LinearLayout>(c.host)
            val grid = LinearLayout(root.context)
            grid.orientation = LinearLayout.VERTICAL
            host.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            val cells = ArrayList<Bank>()
            var n = 1
            for (r in 0 until 2) {
                val row = LinearLayout(root.context)
                row.orientation = LinearLayout.HORIZONTAL
                val rlp = llp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                if (r > 0) rlp.topMargin = 10
                grid.addView(row, rlp)
                for (col in 0 until 4) {
                    val cell = FrameLayout(root.context)
                    val clp = llp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    if (col > 0) clp.marginStart = 10
                    row.addView(cell, clp)

                    // vertical: component image (top, flexible) then a dedicated effect
                    // zone BENEATH it — the spark/spray no longer overlaps the component.
                    val content = LinearLayout(root.context)
                    content.orientation = LinearLayout.VERTICAL
                    cell.addView(content, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                    val img = ImageView(root.context)
                    img.scaleType = ImageView.ScaleType.FIT_CENTER
                    img.setImageResource(c.img)
                    content.addView(img, llp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        setMargins(6, 8, 6, 2)
                    })

                    val fx = FrameLayout(root.context)
                    content.addView(fx, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54))

                    val spark = c.kind == "coil"
                    val overlay = ImageView(root.context)
                    overlay.scaleType = ImageView.ScaleType.FIT_CENTER
                    overlay.setImageResource(c.overlay)
                    overlay.alpha = 0f
                    overlay.translationX = c.sprayDx.toFloat()   // align under the nozzle
                    fx.addView(overlay, FrameLayout.LayoutParams(
                        if (spark) 74 else 92, 52, Gravity.CENTER))

                    cell.addView(makeBadge(n.toString()))
                    cells.add(Bank(overlay, spark))
                    n++
                }
            }
            banks[c.kind] = cells.toTypedArray()
        }
    }

    // spark: a flickery pop; spray: a smoother pulse — one shot per firing event.
    private val sparkKf = arrayOf(
        Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.10f, 1f), Keyframe.ofFloat(0.22f, 0.45f),
        Keyframe.ofFloat(0.36f, 1f), Keyframe.ofFloat(0.55f, 0.3f), Keyframe.ofFloat(0.75f, 0.7f),
        Keyframe.ofFloat(1f, 0f),
    )
    private val sprayKf = arrayOf(
        Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.15f, 1f), Keyframe.ofFloat(0.5f, 0.85f),
        Keyframe.ofFloat(1f, 0f),
    )

    /** Flash on each channel's rising edge, and re-flash while the bit stays set.
     *  The firing bit is latched "fired since last frame"; at idle it toggles
     *  frame-to-frame so the rising edge alone drives one spark per firing. Above
     *  ~2800 rpm a cylinder fires every frame, so the bit is HELD set and the edge
     *  never recurs — without the held-retrigger the spark would fire once then
     *  freeze. Re-flashing when the bit is held AND the prior flash has ended keeps
     *  the flash rate tracking the firing rate up to the frame cap, and never
     *  restarts a flash mid-play (so idle looks exactly as before). */
    private fun setBankActivity(kind: String, byteVal: Int) {
        val arr = banks[kind] ?: return
        val dur = (420L - (currentRpm / 8000f * 200L).toLong()).coerceIn(200L, 420L)
        for (i in 0 until 8) {
            val active = (byteVal ushr i) and 1 == 1
            val b = arr[i]
            if (active && (!b.lastActive || b.anim?.isRunning != true)) {
                b.anim?.cancel()
                b.overlay.alpha = 0f
                val pvh = PropertyValuesHolder.ofKeyframe("alpha", *(if (b.spark) sparkKf else sprayKf))
                val a = ObjectAnimator.ofPropertyValuesHolder(b.overlay, pvh)
                a.duration = dur
                a.start()
                b.anim = a
            }
            b.lastActive = active
        }
    }

    /* ---------------- telemetry ---------------- */
    private var lastV = ""
    fun applyTelemetry(t: Protocol.Telemetry) {
        currentRpm = t.rpm
        dial.setRpm(t.rpm)
        scope.setRpm(t.rpm)
        // Locale.US: an Arabic-locale TV renders %f as Eastern-Arabic digits,
        // which the DSEG7 7-seg font doesn't have (tofu).
        val v = "%.2f".format(Locale.US, t.ecuV / 1000.0)
        if (v != lastV) { lastV = v; voltage.text = v }

        gauges["MAF"]?.setVolts(t.maf / 1000f)
        gauges["MAP"]?.setVolts(t.map / 1000f)
        gauges["IAT"]?.setVolts(t.iat / 1000f)
        gauges["5V"]?.setVolts(t.sensorV / 1000f)

        setBankActivity("coil", t.coils)
        setBankActivity("inj", t.injReg)
        setBankActivity("gdi", t.injGdi)

        setStatusCell("fan1", t.status(Protocol.ST_FAN1))
        setStatusCell("fan2", t.status(Protocol.ST_FAN2))
        setStatusCell("imo", t.status(Protocol.ST_IMMO_P) || t.status(Protocol.ST_IMMO_N))
        setStatusCell("hip", t.status(Protocol.ST_FUEL_PUMP))

        // indicator row is driven by the 10 s startIndicatorToggle demo, not telemetry

        for (i in 0 until 4) iacLeds[i].setBackgroundResource(
            if ((t.iac ushr i) and 1 == 1) R.drawable.bd_iac_on else R.drawable.bd_iac_off
        )
    }

    private fun setStatusCell(key: String, on: Boolean) {
        if (key == "fan1" || key == "fan2") {
            fans[key]?.let { if (it.on != on) { it.on = on; ensureFanLoop() } }
            return
        }
        val c = statusCells[key] ?: return
        // IMMO car stays bright red always; other red cells dim when inactive
        c.img.alpha = if (on || key == "imo") 1f else 0.4f
        // (HIP red-hot head is driven by the 10 s startHipToggle timer, not this bit)
    }

    // Soft start / stop: each fan's speed eases toward top (on) or 0 (off); the loop
    // runs only while a fan is spinning or spinning down, then self-stops.
    private fun ensureFanLoop() {
        if (fanLoopScheduled) return
        fanLoopScheduled = true
        fanLast = 0L
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                val dt = if (fanLast == 0L) 0.016f else (now - fanLast) / 1e9f
                fanLast = now
                var active = false
                for (f in fans.values) {
                    val target = if (f.on) 1100f else 0f         // deg/sec top speed (faster)
                    if (f.speed < target) f.speed = minOf(target, f.speed + 650f * dt)  // brisk spin-up
                    else if (f.speed > target) f.speed = maxOf(target, f.speed - 260f * dt) // coast down
                    if (f.speed > 0.05f) { f.angle = (f.angle + f.speed * dt) % 360f; f.img.rotation = f.angle }
                    if (f.on || f.speed > 0.05f) active = true
                }
                if (active) Choreographer.getInstance().postFrameCallback(this)
                else fanLoopScheduled = false
            }
        })
    }

    fun setConnected(on: Boolean) {
        deviceStatus.text = if (on) "CONNECTED" else "DISCONNECTED"
        deviceStatus.setTextColor(res.getColor(if (on) R.color.led_green else R.color.led_red, null))
        deviceLed.setBackgroundResource(if (on) R.drawable.bd_led_green else R.drawable.bd_led_red)
    }
}
