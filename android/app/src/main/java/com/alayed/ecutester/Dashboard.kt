package com.alayed.ecutester

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
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

    // built cells
    private class StatusCell(val box: View, val img: ImageView, val red: Boolean)
    private val statusCells = HashMap<String, StatusCell>()
    private val fanSpin = HashMap<String, ObjectAnimator>()
    private val iacLeds = ArrayList<View>()

    private class Indicator(val label: TextView, val circle: View, val img: ImageView, val ring: Int)
    private val indicators = HashMap<String, Indicator>()

    private val gauges = HashMap<String, MiniGaugeView>()

    private class Bank(val overlay: ImageView, val cell: View, var anim: ValueAnimator? = null)
    private val banks = HashMap<String, Array<Bank>>()

    init {
        buildStatusGrid()
        buildIndicators()
        buildMiniGauges()
        buildBanks()
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
        val grid = root.findViewById<LinearLayout>(R.id.status_grid)
        data class Cfg(val key: String, val img: Int, val border: Int, val weight: Float, val badge: String?, val red: Boolean, val leds: Boolean)
        val cfgs = listOf(
            Cfg("fan1", R.drawable.fan, R.drawable.bd_fan, 1f, "1", false, false),
            Cfg("fan2", R.drawable.fan, R.drawable.bd_fan, 1f, "2", false, false),
            Cfg("imo", R.drawable.imo2, R.drawable.bd_red, 1f, null, true, false),
            Cfg("hip", R.drawable.hip, R.drawable.bd_red, 1f, null, true, false),
            Cfg("iac", R.drawable.iac, R.drawable.bd_iac, 2f, null, false, true),
        )
        for ((i, c) in cfgs.withIndex()) {
            val box = FrameLayout(root.context)
            box.setBackgroundResource(c.border)
            box.setPadding(8, 8, 8, 8)
            val lp = llp(0, LinearLayout.LayoutParams.MATCH_PARENT, c.weight)
            if (i > 0) lp.marginStart = 12
            grid.addView(box, lp)

            val img = ImageView(root.context)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageResource(c.img)
            box.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                if (c.leds) bottomMargin = 26
            })

            if (c.badge != null) box.addView(makeBadge(c.badge))
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
            if (c.key == "fan1" || c.key == "fan2") {
                val spin = ObjectAnimator.ofFloat(img, "rotation", 0f, 360f)
                spin.duration = 1150; spin.repeatCount = ValueAnimator.INFINITE
                spin.interpolator = android.view.animation.LinearInterpolator()
                fanSpin[c.key] = spin
            }
        }
    }

    private fun makeBadge(text: String): TextView {
        val b = TextView(root.context)
        b.text = text
        b.setBackgroundResource(R.drawable.bd_badge)
        b.setTextColor(color("#04101f"))
        b.textSize = 14f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.layoutParams = FrameLayout.LayoutParams(22, 22, Gravity.TOP or Gravity.START)
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
        for (c in cfgs) {
            val col = LinearLayout(root.context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            host.addView(col, llp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val label = TextView(root.context)
            label.text = c.name
            label.textSize = 15f
            label.setTypeface(label.typeface, Typeface.BOLD)
            label.setTextColor(if (c.green) color("#55e06a") else color("#ff4a3d"))
            col.addView(label)

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
                val tile = FrameLayout(root.context)
                tile.setBackgroundResource(R.drawable.bd_mg)
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
        data class Cfg(val kind: String, val host: Int, val img: Int, val overlay: Int)
        val cfgs = listOf(
            Cfg("coil", R.id.bank_coil, R.drawable.coil, R.drawable.spark_lit),
            Cfg("inj", R.id.bank_inj, R.drawable.injector, R.drawable.spray),
            Cfg("gdi", R.id.bank_gdi, R.drawable.gdi, R.drawable.spray_gdi),
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

                    val img = ImageView(root.context)
                    img.scaleType = ImageView.ScaleType.FIT_CENTER
                    img.setImageResource(c.img)
                    cell.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                        setMargins(6, 6, 6, 24)
                    })
                    cell.addView(makeBadge(n.toString()))

                    val overlay = ImageView(root.context)
                    overlay.scaleType = ImageView.ScaleType.FIT_CENTER
                    overlay.setImageResource(c.overlay)
                    overlay.alpha = 0f
                    cell.addView(overlay, FrameLayout.LayoutParams(70, 60, Gravity.CENTER).apply { topMargin = -6 })

                    cells.add(Bank(overlay, cell))
                    n++
                }
            }
            banks[c.kind] = cells.toTypedArray()
        }
    }

    private fun setBankActivity(kind: String, byteVal: Int) {
        val arr = banks[kind] ?: return
        for (i in 0 until 8) {
            val active = (byteVal ushr i) and 1 == 1
            val b = arr[i]
            if (active && b.anim == null) {
                val a = ObjectAnimator.ofFloat(b.overlay, "alpha", 0f, 1f, 0.2f, 0.75f, 0f)
                a.duration = 900; a.repeatCount = ValueAnimator.INFINITE
                a.start(); b.anim = a
            } else if (!active && b.anim != null) {
                b.anim?.cancel(); b.anim = null; b.overlay.alpha = 0f
            }
        }
    }

    /* ---------------- telemetry ---------------- */
    private var lastV = ""
    fun applyTelemetry(t: Protocol.Telemetry) {
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
        val c = statusCells[key] ?: return
        if (key == "fan1" || key == "fan2") {
            val spin = fanSpin[key] ?: return
            if (on && !spin.isStarted) spin.start()
            else if (!on && spin.isStarted) { spin.cancel(); c.img.rotation = 0f }
            return
        }
        // red cells (imo/hip): dim when inactive
        c.img.alpha = if (on) 1f else 0.4f
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
