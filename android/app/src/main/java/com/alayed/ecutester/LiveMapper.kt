package com.alayed.ecutester

import android.view.Choreographer

/**
 * Maps decoded protocol frames onto the dashboard — Kotlin port of the relevant
 * bits of web/js/live.js. For the M1 slice this is just rpm -> dial, but it keeps
 * live.js's shape so the full rebuild slots in:
 *   - COALESCE bursts (Wi-Fi power-save delivers frames in clumps): keep only the
 *     latest telemetry and apply it once per painted frame (Choreographer), never
 *     faster than the display paints.
 *   - the stale-stream WATCHDOG (live.js parity) ticks in MainActivity and uses
 *     [lastTelemetryMs] / [markStalled] from here.
 *
 * [onTelemetry] runs on the main thread, in a frame callback.
 */
class LiveMapper(private val onTelemetry: (Protocol.Telemetry) -> Unit) {

    @Volatile private var pending: Protocol.Telemetry? = null
    private var scheduled = false

    var lastTelemetryMs: Long = 0L; private set
    var frames: Int = 0; private set

    /** One shot per stall (watchdog in MainActivity): zeroed so the watchdog
     *  doesn't re-fire until the next real frame arrives — mirrors live.js. */
    fun markStalled() { lastTelemetryMs = 0L }

    /** Feed one decoded frame (already on the main thread via EcuSocket). */
    fun onFrame(frame: Protocol.Frame) {
        when (frame) {
            is Protocol.Frame.TelemetryFrame -> {
                frames++
                lastTelemetryMs = System.currentTimeMillis()
                pending = frame.data
                schedule()
            }
            else -> { /* waveform/hello/ack ignored in M1 */ }
        }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback {
            scheduled = false
            pending?.let { onTelemetry(it); pending = null }
        }
    }
}
