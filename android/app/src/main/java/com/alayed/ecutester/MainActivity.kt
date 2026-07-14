package com.alayed.ecutester

import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.alayed.ecutester.ui.RpmDialView

/**
 * M1 vertical slice (docs/ANDROID_MIGRATION.md §9): connect to /ws, decode rpm,
 * drive the RPM needle at 60 fps. A corner meter shows FPS / WS-per-sec / status
 * so the on-TV pass/fail is readable without devtools (mirrors web/js/diag.js).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Production default = the device SoftAP. For the Android TV emulator +
        // tools/sim_server.py use "ws://10.0.2.2:8090/ws"; for a real box against a
        // laptop sim use the laptop's LAN IP (bind sim_server to 0.0.0.0 first).
        private const val WS_URL = "ws://10.10.10.10/ws"
    }

    private lateinit var dial: RpmDialView
    private lateinit var meter: TextView
    private lateinit var socket: EcuSocket
    private lateinit var mapper: LiveMapper

    // FPS meter
    private var frameCount = 0
    private var lastFpsNs = 0L
    private var fps = 0
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        goImmersive()

        dial = findViewById(R.id.rpm_dial)
        meter = findViewById(R.id.meter)

        mapper = LiveMapper { t -> dial.setRpm(t.rpm) }
        socket = EcuSocket(
            url = WS_URL,
            onFrame = { mapper.onFrame(it) },
            onStatus = { st -> connected = st == "connected"; updateMeter() },
        )
        socket.connect()

        // FPS via Choreographer — the real painted rate on this display.
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCount++
                if (lastFpsNs == 0L) lastFpsNs = frameTimeNanos
                val dt = frameTimeNanos - lastFpsNs
                if (dt >= 500_000_000L) {          // update twice a second
                    fps = (frameCount * 1_000_000_000L / dt).toInt()
                    frameCount = 0; lastFpsNs = frameTimeNanos
                    updateMeter()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun updateMeter() {
        val age = if (mapper.lastTelemetryMs == 0L) "--"
        else (System.currentTimeMillis() - mapper.lastTelemetryMs).toString()
        meter.text = "FPS $fps · ${if (connected) "LINK" else "no link"} · WS ${mapper.frames} · age ${age}ms"
    }

    override fun onDestroy() {
        socket.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}
