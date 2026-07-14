package com.alayed.ecutester

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.system.exitProcess

/**
 * Cluster host: immersive fullscreen, WebSocket link, and a corner FPS/link meter.
 * The view work lives in Dashboard; this wires the socket + timers to it.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_HOST = "10.10.10.10"
    }

    /** Host[:port], mirroring the web ?device= override. `--es host <h>` intent
     *  extra (persisted) wins, else the last value, else the device SoftAP. */
    private fun resolveWsUrl(): String {
        val prefs = getSharedPreferences("ecu", MODE_PRIVATE)
        intent?.getStringExtra("host")?.let { prefs.edit().putString("host", it).apply() }
        val host = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        return "ws://$host/ws"
    }

    private lateinit var dashboard: Dashboard
    private lateinit var meter: TextView
    private lateinit var socket: EcuSocket
    private lateinit var mapper: LiveMapper
    private val ui = Handler(Looper.getMainLooper())

    private var frameCount = 0
    private var lastFpsNs = 0L
    private var fps = 0
    private var connected = false
    private var hostLabel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRestart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        goImmersive()

        val root = findViewById<View>(android.R.id.content)
        dashboard = Dashboard(root)
        meter = findViewById(R.id.meter)

        val wsUrl = resolveWsUrl()
        hostLabel = wsUrl.removePrefix("ws://").removeSuffix("/ws")

        mapper = LiveMapper { t -> dashboard.applyTelemetry(t) }
        socket = EcuSocket(
            url = wsUrl,
            onFrame = { mapper.onFrame(it) },
            onStatus = { st ->
                connected = st == "connected"
                dashboard.setConnected(connected)
                if (connected) dashboard.resetUptime()
                updateMeter()
            },
        )
        socket.connect()

        // uptime 1 Hz
        ui.post(object : Runnable {
            override fun run() { dashboard.tickUptime(); ui.postDelayed(this, 1000) }
        })

        // FPS via the real painted cadence
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCount++
                if (lastFpsNs == 0L) lastFpsNs = frameTimeNanos
                val dt = frameTimeNanos - lastFpsNs
                if (dt >= 500_000_000L) {
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

    override fun onResume() {
        super.onResume()
        setupKiosk()
    }

    override fun onDestroy() { socket.close(); super.onDestroy() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    /* ---- kiosk ---- */

    // Restart the dashboard ~1.5 s after any uncaught crash — an unattended kiosk
    // must not die to a blank screen. (Belt-and-suspenders with BootReceiver.)
    private fun installCrashRestart() {
        val ctx = applicationContext
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e("ECU", "uncaught — restarting", e)
            val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            (ctx.getSystemService(ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    private var lockTaskTried = false

    // Full unattended lock (no exit) only when the app is device owner — set once with
    //   adb shell dpm set-device-owner com.alayed.ecutester/.EcuDeviceAdminReceiver
    // Without device owner this is a no-op, so a dev/phone build is unaffected.
    private fun setupKiosk() {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
        if (dpm.isDeviceOwnerApp(packageName)) {
            val admin = ComponentName(this, EcuDeviceAdminReceiver::class.java)
            runCatching { dpm.setLockTaskPackages(admin, arrayOf(packageName)) }
        }
        if (!lockTaskTried && dpm.isLockTaskPermitted(packageName)) {
            lockTaskTried = true
            runCatching { startLockTask() }
        }
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
