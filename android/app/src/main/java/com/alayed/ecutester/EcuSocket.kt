package com.alayed.ecutester

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket link to the device — Kotlin port of web/js/websocket.js.
 *
 * Owns the socket, decodes each binary frame via [Protocol], and hands frames +
 * status to callbacks ON THE MAIN THREAD (OkHttp delivers on its own dispatcher;
 * the UI must not be touched off-thread). Auto-reconnects with 500 ms -> 5 s
 * backoff, same as the web client.
 */
class EcuSocket(
    private val url: String,
    private val onFrame: (Protocol.Frame) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)      // server doesn't expect pings
        .retryOnConnectionFailure(true)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var retryMs = 500L
    private var closed = false

    fun connect() {
        closed = false
        post("connecting")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryMs = 500
                post("connected")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = Protocol.decodeFrame(bytes.toByteArray()) ?: return  // dropped
                main.post { onFrame(frame) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post("disconnected"); scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post("disconnected"); scheduleReconnect()
            }
        })
    }

    fun send(bytes: ByteArray): Boolean = ws?.send(ByteString.of(*bytes)) ?: false

    fun close() {
        closed = true
        ws?.close(1000, null)
        ws = null
    }

    private fun scheduleReconnect() {
        if (closed) return
        val delay = retryMs
        retryMs = (retryMs * 2).coerceAtMost(5000)
        main.postDelayed({ if (!closed) connect() }, delay)
    }

    private fun post(status: String) = main.post { onStatus(status) }
}
