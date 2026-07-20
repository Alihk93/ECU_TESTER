package com.alayed.ecutester.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Procedural "PS2-style" sound bed for the intro — native port of the Web Audio
 * synth in ecu-intro.jsx. Each cue is rendered once to a PCM buffer at construction
 * and played on demand via a short-lived AudioTrack (overlap-capable). All maths
 * mirror the source oscillator/envelope design; nothing is sample-based.
 */
class IntroAudio {

    var enabled = true

    private val sr = 22050
    private val buffers = HashMap<String, ShortArray>()
    private val active = ArrayList<AudioTrack>()

    init {
        buffers["ambient"] = ambient()
        buffers["whoosh"] = whoosh()
        buffers["thump"] = thump()
        buffers["blip1"] = blip(620f)
        buffers["blip2"] = blip(830f)
        buffers["impact"] = impact()
        buffers["ripple"] = ripple()
        buffers["chime"] = chime()
        buffers["click"] = click()
    }

    fun play(name: String) {
        if (!enabled) return
        val buf = buffers[name] ?: return
        prune()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        track.play()
        active.add(track)
    }

    private fun prune() {
        val it = active.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.playState == AudioTrack.PLAYSTATE_STOPPED ||
                t.playbackHeadPosition >= (t.bufferSizeInFrames)) {
                runCatching { t.release() }; it.remove()
            }
        }
    }

    fun release() {
        active.forEach { runCatching { it.pause(); it.flush(); it.release() } }
        active.clear()
    }

    /* ---------------- generators (units: seconds, amp 0..1) ---------------- */
    private fun buf(seconds: Float) = ShortArray((sr * seconds).toInt())
    private fun put(out: ShortArray, i: Int, v: Float) {
        if (i < 0 || i >= out.size) return
        val s = (v * 32767f).coerceIn(-32767f, 32767f) + out[i]
        out[i] = s.coerceIn(-32767f, 32767f).toInt().toShort()
    }
    // exponential attack/decay envelope like the web _env (ramp to peak, decay to ~0)
    private fun env(t: Float, dur: Float, atk: Float, peak: Float): Float {
        if (t < 0f || t > dur) return 0f
        return if (t < atk) peak * (t / atk)
        else peak * exp(-4f * (t - atk) / (dur - atk))
    }

    private fun thump(): ShortArray {
        val out = buf(0.5f); var ph = 0.0
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val f = 95.0 * (40.0 / 95.0).pow((t / 0.4).coerceAtMost(1.0))
            ph += 2 * PI * f / sr
            put(out, i, (sin(ph) * env(t, 0.45f, 0.02f, 0.4f)).toFloat())
        }
        return out
    }

    private fun blip(freq: Float): ShortArray {
        val out = buf(0.2f)
        for (i in out.indices) {
            val t = i / sr.toFloat()
            put(out, i, sin(2 * PI * freq * t).toFloat() * env(t, 0.18f, 0.02f, 0.09f))
        }
        return out
    }

    private fun impact(): ShortArray {
        val out = buf(0.7f); var ph = 0.0
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val f = 150.0 * (46.0 / 150.0).pow((t / 0.55).coerceAtMost(1.0))
            ph += 2 * PI * f / sr
            var v = (sin(ph) * env(t, 0.65f, 0.01f, 0.45f)).toFloat()
            if (t < 0.15f) v += (Math.random().toFloat() * 2 - 1) * env(t, 0.15f, 0.005f, 0.15f) // hp-ish noise crack
            put(out, i, v)
        }
        return out
    }

    private fun ripple(): ShortArray {
        val out = buf(1.2f)
        for (k in 0 until 11) {
            val t0 = k * 0.09f; val freq = 880f + k * 74f
            var i = (t0 * sr).toInt()
            while (i < out.size) {
                val t = i / sr.toFloat() - t0
                if (t > 0.16f) break
                put(out, i, sin(2 * PI * freq * t).toFloat() * env(t, 0.16f, 0.015f, 0.07f)); i++
            }
        }
        return out
    }

    private fun chime(): ShortArray {
        val out = buf(2.0f); val notes = floatArrayOf(1046.5f, 1318.5f, 1568f, 2093f)
        for (n in notes.indices) {
            val t0 = n * 0.13f
            for (det in floatArrayOf(1f, 1.004f)) {
                val freq = notes[n] * det
                var i = (t0 * sr).toInt()
                while (i < out.size) {
                    val t = i / sr.toFloat() - t0
                    put(out, i, sin(2 * PI * freq * t).toFloat() * env(t, 1.7f, 0.02f, 0.06f)); i++
                }
            }
        }
        return out
    }

    private fun click(): ShortArray {
        val out = buf(0.08f)
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val saw = (2f * ((1500f * t) % 1f) - 1f)               // triangle-ish
            put(out, i, saw * env(t, 0.07f, 0.005f, 0.12f))
        }
        return out
    }

    // resonant-bandpass-swept noise (state-variable filter), 160 -> 1500 Hz
    private fun whoosh(): ShortArray {
        val out = buf(1.3f); var low = 0f; var band = 0f; val q = 0.9f
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val fc = 160f * (1500f / 160f).pow((t / 1.1f).coerceAtMost(1f))
            val f = 2f * sin(PI.toFloat() * fc / sr)
            val input = Math.random().toFloat() * 2 - 1
            low += f * band
            val high = input - low - q * band
            band += f * high
            put(out, i, band * env(t, 1.3f, 0.45f, 0.5f))
        }
        return out
    }

    // low drone bed with a slow swell (110 Hz + partials through a soft lowpass)
    private fun ambient(): ShortArray {
        val dur = 6.5f; val out = buf(dur); var lp = 0f
        val parts = arrayOf(110f to 1f, 110.8f to 0.8f, 221.5f to 0.22f, 330f to 0.08f)
        for (i in out.indices) {
            val t = i / sr.toFloat()
            var v = 0f
            for ((fr, a) in parts) v += sin(2 * PI * fr * t).toFloat() * a
            v /= 2.1f
            lp += 0.06f * (v - lp)                                  // one-pole lowpass ~ warm bed
            val swell = when {                                     // rise 2s, hold, fall
                t < 2f -> t / 2f
                t > dur - 1.5f -> ((dur - t) / 1.5f).coerceAtLeast(0f)
                else -> 1f
            }
            put(out, i, lp * 0.09f * swell)
        }
        return out
    }
}
