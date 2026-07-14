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
        // Production default = the device SoftAP.
        private const val DEFAULT_HOST = "10.10.10.10"
    }

    /**
     * Host[:port] to connect to, mirroring the web `?device=` override. Precedence:
     *   1. an `--es host <h>` intent extra (persisted, so a relaunch keeps it)
     *   2. the last persisted value
     *   3. DEFAULT_HOST (the device SoftAP)
     * Dev examples (no rebuild):
     *   adb shell am start -n com.alayed.ecutester/.MainActivity --es host 192.168.1.50:8090
     *   adb shell am start -n com.alayed.ecutester/.MainActivity --es host 10.10.10.10
     * Emulator + tools/sim_server.py: use host 10.0.2.2:8090.
     */
    private fun resolveWsUrl(): String {
        val prefs = getSharedPreferences("ecu", MODE_PRIVATE)
        intent?.getStringExtra("host")?.let { prefs.edit().putString("host", it).apply() }
        val host = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        return "ws://$host/ws"
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
    private var hostLabel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        goImmersive()

        dial = findViewById(R.id.rpm_dial)
        meter = findViewById(R.id.meter)

        val wsUrl = resolveWsUrl()
        hostLabel = wsUrl.removePrefix("ws://").removeSuffix("/ws")
        mapper = LiveMapper { t -> dial.setRpm(t.rpm) }
        socket = EcuSocket(
            url = wsUrl,
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
        meter.text = "FPS $fps · ${if (connected) "LINK" else "no link"} $hostLabel · WS ${mapper.frames} · age ${age}ms"
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
