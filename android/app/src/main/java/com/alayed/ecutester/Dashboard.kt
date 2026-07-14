package com.alayed.ecutester

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.graphics.Typeface
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
    private val uptime = root.findViewById<TextView>(R.id.uptime)
    private val voltage = root.findViewById<TextView>(R.id.voltage)
    private val current = root.findViewById<TextView>(R.id.current)

    // gauges + scope (from XML)
    private val dial = root.findViewById<RpmDialView>(R.id.rpm_dial)
    private val scope = root.findViewById<ScopeView>(R.id.scope_view)
    private val canScope = root.findViewById<ScopeView>(R.id.can_scope)

    // built cells
    private class StatusCell(val box: View, val img: ImageView, val red: Boolean)
    private val statusCells = HashMap<String, StatusCell>()
    private val iacLeds = ArrayList<View>()

    // fans: eased angular velocity (soft start-up to top speed, coast-down to stop)
    private class Fan(val img: ImageView) { var on = false; var angle = 0f; var speed = 0f }
    private val fans = HashMap<String, Fan>()
    private var fanLoopScheduled = false
    private var fanLast = 0L

    private class Indicator(val label: TextView, val circle: View, val img: ImageView, val ring: Int)
    private val indicators = HashMap<String, Indicator>()

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
    }

    /* ---------------- helpers ---------------- */
    private fun llp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h).apply { this.weight = weight }

    private fun color(hex: String) = Color.parseColor(hex)

    /* ---------------- status grid ---------------- */
    private fun buildStatusGrid() {
        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val grid = root.findViewById<LinearLayout>(R.id.status_grid)

        // Both fans share ONE bordered box (client feedback): fanBox has the border,
        // the two fan sub-cells inside are borderless.
        val fanBox = LinearLayout(root.context)
        fanBox.orientation = LinearLayout.HORIZONTAL
        fanBox.setBackgroundResource(R.drawable.bd_fan)
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

        // imo / hip / iac — separate bordered cells
        data class Cfg(val key: String, val img: Int, val border: Int, val weight: Float, val red: Boolean, val leds: Boolean)
        val cfgs = listOf(
            Cfg("imo", R.drawable.imo2, R.drawable.bd_red, 1f, true, false),
            Cfg("hip", R.drawable.hip, R.drawable.bd_red, 1f, true, false),
            Cfg("iac", R.drawable.iac, R.drawable.bd_iac, 2f, false, true),
        )
        for (c in cfgs) {
            val box = FrameLayout(root.context)
            box.setBackgroundResource(c.border)
            box.setPadding(8, 8, 8, 8)
            grid.addView(box, llp(0, mp, c.weight).apply { marginStart = 12 })

            val img = ImageView(root.context)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(c.img)
            box.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                if (c.leds) bottomMargin = 26
            })
            statusCells[c.key] = StatusCell(box, img, c.red)

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
        data class Cfg(val name: String, val green: Boolean, val img: Int)
        val cfgs = listOf(
            Cfg("BAT-ON", false, R.drawable.bat),
            Cfg("SW-ON", false, R.drawable.swon2),
            Cfg("MRC+", true, R.drawable.relay_nc),
            Cfg("MRC-", false, R.drawable.relay_no),
            Cfg("ETC-ON", false, R.drawable.relay_no),
            Cfg("ST-OFF", true, R.drawable.relay_nc),
            Cfg("PFC-OFF", true, R.drawable.relay_nc),
        )
        // Equal weighted columns (never overflow the panel) + a wrap_content label
        // sized to fit its ~69px column at 12px — full names show, no clip, no
        // overflow. (The web fits these at 15px in Chrome; Saira runs a touch wider.)
        for (c in cfgs) {
            val col = LinearLayout(root.context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            host.addView(col, llp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val label = TextView(root.context)
            label.text = c.name
            label.setSingleLine()          // one line, no hyphen break
            label.gravity = Gravity.CENTER
            label.setTypeface(label.typeface, Typeface.BOLD)
            label.setTextColor(if (c.green) color("#55e06a") else color("#ff4a3d"))
            // Auto-size: fill the column width and shrink the label to the largest
            // size that fits (8–14px). Avoids both hyphen-wrap clipping and
            // overflow-into-neighbours, whatever the font metrics.
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                label, 8, 14, 1, android.util.TypedValue.COMPLEX_UNIT_PX)
            col.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val circle = FrameLayout(root.context)
            val ring = if (c.green) R.drawable.bd_indic_circle_green else R.drawable.bd_indic_circle_red
            circle.setBackgroundResource(ring)
            col.addView(circle, LinearLayout.LayoutParams(56, 56).apply { topMargin = 6 })

            val img = ImageView(root.context)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(c.img)
            circle.addView(img, FrameLayout.LayoutParams(44, 44, Gravity.CENTER))

            indicators[c.name] = Indicator(label, circle, img, ring)
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

    /** Fire a single flash on each channel's rising edge (fired-since-last-frame).
     *  One spark per firing event; faster (shorter) flash as RPM climbs. */
    private fun setBankActivity(kind: String, byteVal: Int) {
        val arr = banks[kind] ?: return
        val dur = (420L - (currentRpm / 8000f * 200L).toLong()).coerceIn(200L, 420L)
        for (i in 0 until 8) {
            val active = (byteVal ushr i) and 1 == 1
            val b = arr[i]
            if (active && !b.lastActive) {
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
        val v = "%.2f".format(t.ecuV / 1000.0)
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

        setIndicator("BAT-ON", t.status(Protocol.ST_BATTERY))
        setIndicator("SW-ON", t.status(Protocol.ST_SWITCH))
        setIndicator("MRC+", t.status(Protocol.ST_MRC_P))
        setIndicator("MRC-", t.status(Protocol.ST_MRC_N))
        setIndicator("ETC-ON", t.status(Protocol.ST_ETC))
        setIndicator("ST-OFF", t.status(Protocol.ST_START))
        setIndicator("PFC-OFF", t.status(Protocol.ST_FUEL_PUMP))

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
        // red cells (imo/hip): dim when inactive
        c.img.alpha = if (on) 1f else 0.4f
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
                    val target = if (f.on) 300f else 0f          // deg/sec top speed
                    if (f.speed < target) f.speed = minOf(target, f.speed + 220f * dt)  // spin-up ~1.4 s
                    else if (f.speed > target) f.speed = maxOf(target, f.speed - 140f * dt) // coast ~2.1 s
                    if (f.speed > 0.05f) { f.angle = (f.angle + f.speed * dt) % 360f; f.img.rotation = f.angle }
                    if (f.on || f.speed > 0.05f) active = true
                }
                if (active) Choreographer.getInstance().postFrameCallback(this)
                else fanLoopScheduled = false
            }
        })
    }

    private fun setIndicator(name: String, on: Boolean) {
        val ind = indicators[name] ?: return
        ind.img.alpha = if (on) 1f else 0.45f
        ind.label.alpha = if (on) 1f else 0.5f
        ind.circle.setBackgroundResource(if (on) ind.ring else R.drawable.bd_indic_circle_off)
    }

    fun setConnected(on: Boolean) {
        deviceStatus.text = if (on) "CONNECTED" else "DISCONNECTED"
        deviceStatus.setTextColor(res.getColor(if (on) R.color.led_green else R.color.led_red, null))
        deviceLed.setBackgroundResource(if (on) R.drawable.bd_led_green else R.drawable.bd_led_red)
    }

    private var uptimeSec = 0
    fun resetUptime() { uptimeSec = 0 }
    fun tickUptime() {
        uptimeSec++
        val h = (uptimeSec / 3600) % 100; val m = (uptimeSec / 60) % 60; val s = uptimeSec % 60
        uptime.text = "%02d:%02d:%02d".format(h, m, s)
    }
}
