package com.alayed.ecutester

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.view.WindowManager
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
    private lateinit var socket: EcuSocket
    private lateinit var mapper: LiveMapper
    private lateinit var demo: DemoDriver
    private var liveStarted = false
    private val ui = Handler(Looper.getMainLooper())

    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRestart()
        // kiosk display: show over the keyguard and wake the screen, so the
        // dashboard is always visible even on a locked/asleep device.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        goImmersive()

        val root = findViewById<View>(android.R.id.content)
        dashboard = Dashboard(root)

        val wsUrl = resolveWsUrl()

        // Free-run a demo until the first real frame (parity with the web dashboard),
        // so the display looks alive when the ESP32 is off/booting.
        demo = DemoDriver { t -> dashboard.applyTelemetry(t) }
        demo.start()

        mapper = LiveMapper { t ->
            if (!liveStarted) { liveStarted = true; demo.stop() }
            dashboard.applyTelemetry(t)
        }
        socket = EcuSocket(
            url = wsUrl,
            onFrame = { mapper.onFrame(it) },
            onStatus = { st ->
                connected = st == "connected"
                dashboard.setConnected(connected)
            },
        )
        socket.connect()

        // Back button -> return to the intro splash.
        findViewById<View>(R.id.back_btn).setOnClickListener {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Stale-stream watchdog (parity with web live.js): TV Wi-Fi power save /
        // an AP reboot can kill the TCP stream with NO close event — OkHttp then
        // never fires onFailure (pings are off) and the dashboard would freeze at
        // the last frame still showing CONNECTED. If telemetry goes stale while
        // the link claims to be up, cycle the socket; backoff reconnect takes over.
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (connected && mapper.lastTelemetryMs != 0L &&
                    System.currentTimeMillis() - mapper.lastTelemetryMs > 2500) {
                    mapper.markStalled()
                    socket.cycle()
                }
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onResume() {
        super.onResume()
        setupKiosk()
    }

    override fun onDestroy() { demo.stop(); socket.close(); super.onDestroy() }

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
