package com.alayed.ecutester

import android.view.View
import android.widget.TextView
import com.alayed.ecutester.ui.RpmDialView

/**
 * View controller for the cluster — the native equivalent of web/app.js. Holds the
 * inflated view references and maps decoded telemetry onto them. Grows one panel at
 * a time; right now: top-bar readouts + device status + uptime + the RPM dial.
 */
class Dashboard(private val root: View) {

    private val devicePanel = root.findViewById<View>(R.id.device_panel)
    private val deviceLed = root.findViewById<View>(R.id.device_led)
    private val deviceStatus = root.findViewById<TextView>(R.id.device_status)
    private val uptime = root.findViewById<TextView>(R.id.uptime)
    private val voltage = root.findViewById<TextView>(R.id.voltage)
    private val current = root.findViewById<TextView>(R.id.current)

    // RPM dial is added programmatically into the gauges panel (built in a later step);
    // guarded null-safe until then.
    private var dial: RpmDialView? = null

    fun attachDial(d: RpmDialView) { dial = d }

    private var lastV = ""
    private var lastA = ""

    fun applyTelemetry(t: Protocol.Telemetry) {
        dial?.setRpm(t.rpm)
        val v = fmt2(t.ecuV / 1000.0)
        if (v != lastV) { lastV = v; voltage.text = v }
        // current has no protocol v1 field — stays 0.00 (like the web)
        if (lastA != "0.00") { lastA = "0.00"; current.text = "0.00" }
    }

    fun setConnected(on: Boolean) {
        deviceStatus.text = if (on) "CONNECTED" else "DISCONNECTED"
        val c = if (on) R.color.led_green else R.color.led_red
        deviceStatus.setTextColor(root.resources.getColor(c, null))
        deviceLed.setBackgroundResource(if (on) R.drawable.bd_led_green else R.drawable.bd_led_red)
    }

    private var uptimeSec = 0
    fun resetUptime() { uptimeSec = 0 }
    fun tickUptime() {
        uptimeSec++
        val h = (uptimeSec / 3600) % 100
        val m = (uptimeSec / 60) % 60
        val s = uptimeSec % 60
        uptime.text = "%02d:%02d:%02d".format(h, m, s)
    }

    private fun fmt2(x: Double) = "%.2f".format(x)
}
