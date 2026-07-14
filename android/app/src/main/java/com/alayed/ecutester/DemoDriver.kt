package com.alayed.ecutester

import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Free-running DEMO generator — synthesises plausible telemetry so the dashboard
 * looks alive BEFORE the device link comes up (parity with the web dashboard, which
 * free-runs a demo until the first real frame; useful for a showroom display when
 * the ESP32 is off or still booting). Uses the same signal shapes as the firmware /
 * sim generators, so demo and live look identical. Stopped the instant a real
 * TELEMETRY frame arrives (MainActivity).
 */
class DemoDriver(private val onFrame: (Protocol.Telemetry) -> Unit) {

    private val h = Handler(Looper.getMainLooper())
    private val t0 = System.nanoTime()
    private var running = false
    private var deg = 0.0
    private var prevT = 0.0
    private val fireOrder = intArrayOf(0, 4, 2, 5, 1, 3)   // 1-5-3-6-2-4

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            onFrame(gen())
            h.postDelayed(this, 33)     // ~30 Hz, like the wire
        }
    }

    fun start() {
        if (running) return
        running = true; prevT = 0.0; deg = 0.0
        h.post(tick)
    }

    fun stop() { running = false; h.removeCallbacks(tick) }

    private fun u16(v: Double, hi: Int) = v.coerceIn(0.0, hi.toDouble()).toInt()

    private fun gen(): Protocol.Telemetry {
        val now = (System.nanoTime() - t0) / 1e9
        val dt = (now - prevT).coerceAtLeast(1e-4)
        val lfo = sin(now * 0.25 * 2 * PI)
        val rev = 0.5 - 0.5 * cos(now * 0.08 * 2 * PI)
        val rpm = 820.0 + 120.0 * lfo + 2600.0 * rev * rev

        // integrate crank angle -> latched firing bits since last tick
        val deg1 = deg + rpm / 60.0 * 360.0 * dt
        var fire = 0
        var e = floor(deg / 120.0).toLong() + 1
        while (e * 120.0 <= deg1) { fire = fire or (1 shl fireOrder[(((e % 6) + 6) % 6).toInt()]); e++ }
        deg = deg1; prevT = now

        val mapKpa = 30.0 + rev * 68.0
        val mafGs = 4.0 + rev * 190.0 + (rpm - 820) * 0.01
        val iatC = 28.0 + 4.0 * sin(now * 0.05 * 2 * PI)

        var status = (1 shl Protocol.ST_BATTERY) or (1 shl Protocol.ST_SWITCH) or
            (1 shl Protocol.ST_ETC) or (1 shl Protocol.ST_FUEL_PUMP) or
            (1 shl Protocol.ST_MRC_P) or (1 shl Protocol.ST_MRC_N)
        if (rev > 0.30) status = status or (1 shl Protocol.ST_FAN1)
        if (rev > 0.62) status = status or (1 shl Protocol.ST_FAN2)
        val iac = 1 shl ((((now * 1e6).toLong() / 150000L) % 4L).toInt())

        return Protocol.Telemetry(
            tUs = (now * 1e6).toLong() and 0xFFFFFFFFL,
            rpm = u16(rpm, 8000),
            maf = u16(mafGs / 400.0 * 5000.0, 5000),
            map = u16(mapKpa / 105.0 * 5000.0, 5000),
            iat = u16((120.0 - iatC) / 160.0 * 5000.0, 5000),
            ecuV = u16(13800.0 + 150.0 * sin(now * 1.3 * 2 * PI), 25000),
            sensorV = u16(2500.0 + 1800.0 * sin(now * 0.5 * 2 * PI), 5000),
            coils = fire, injReg = fire, injGdi = fire,
            status = status, iac = iac,
        )
    }
}
