package com.phonetype.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal WebSocket client for the phone-type PC service.
 * Protocol: hello+pin → welcome; then text → ack/error.
 * Generation token prevents a stale socket from wiping a newer session.
 * Auto-reconnect with exponential backoff (1s→2s→4s→8s→16s, cap 30s);
 * disabled by user disconnect or a bad_pin rejection.
 */
class WsManager(
    private val onStatus: (String) -> Unit,
    private val onAck: (seq: Int?, ok: Boolean, detail: String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val wsRef = AtomicReference<WebSocket?>(null)
    private val authed = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val autoReconnect = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private var lastHost = ""
    private var lastPort = 0
    private var lastPin = ""

    @Volatile
    private var rttStartNs = 0L

    @Volatile
    private var rttCallback: ((ms: Long) -> Unit)? = null

    /** 用户点击后主动测一次 RTT；未连接返回 -1 */
    fun measureRtt(cb: (ms: Long) -> Unit) {
        val ws = wsRef.get()
        if (ws == null || !authed.get()) {
            cb(-1L)
            return
        }
        rttStartNs = System.nanoTime()
        rttCallback = cb
        val ok = ws.send(JSONObject().put("type", "ping").toString())
        if (!ok) {
            rttCallback = null
            cb(-1L)
            return
        }
        // 3s 无 pong 视为失败
        reconnectHandler.postDelayed({
            if (rttCallback === cb) {
                rttCallback = null
                cb(-1L)
            }
        }, 3000)
    }

    fun isConnected(): Boolean = wsRef.get() != null && authed.get()

    fun connect(host: String, port: Int, pin: String) {
        disconnect()
        if (host.isBlank() || pin.isBlank()) {
            onStatus("请填写 IP 和 PIN")
            return
        }
        lastHost = host
        lastPort = port
        lastPin = pin
        autoReconnect.set(true)
        dial()
    }

    fun sendText(text: String, seq: Int): Boolean {
        val ws = wsRef.get() ?: return false
        if (!authed.get()) return false
        val payload = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("seq", seq)
            .toString()
        return ws.send(payload)
    }

    fun disconnect() {
        autoReconnect.set(false)
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempts.set(0)
        generation.incrementAndGet()
        authed.set(false)
        wsRef.getAndSet(null)?.close(1000, "bye")
    }

    private fun dial() {
        val gen = generation.incrementAndGet()
        val failures = reconnectAttempts.get()
        onStatus(if (failures == 0) "连接中…" else "重连中(第${failures + 1}次)…")
        val req = Request.Builder().url("ws://$lastHost:$lastPort").build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            private fun isCurrent(): Boolean = generation.get() == gen

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) {
                    webSocket.close(1000, "stale")
                    return
                }
                wsRef.set(webSocket)
                webSocket.send(JSONObject().put("type", "hello").put("pin", lastPin).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) return
                handle(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent()) return
                cleanupIfSame(webSocket)
                onStatus("已断开 ($code)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                cleanupIfSame(webSocket)
                onStatus("连接失败: ${t.message ?: "unknown"}")
                scheduleReconnect()
            }
        })
        wsRef.set(ws)
    }

    private fun scheduleReconnect() {
        if (!autoReconnect.get()) return
        val attempt = reconnectAttempts.incrementAndGet()
        val delayMs = (1000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectHandler.postDelayed({
            if (autoReconnect.get()) dial()
        }, delayMs)
    }

    private fun cleanupIfSame(webSocket: WebSocket) {
        wsRef.compareAndSet(webSocket, null)
        if (wsRef.get() == null) {
            authed.set(false)
        }
    }

    private fun handle(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            onStatus("协议错误")
            return
        }
        when (msg.optString("type")) {
            "welcome" -> {
                authed.set(true)
                reconnectAttempts.set(0)
                onStatus("已连接")
            }
            "ack" -> {
                val seq = if (msg.has("seq") && !msg.isNull("seq")) msg.optInt("seq") else null
                onAck(seq, msg.optBoolean("ok", false), msg.optString("method", ""))
            }
            "error" -> {
                val code = msg.optString("code", "unknown")
                val seq = if (msg.has("seq") && !msg.isNull("seq")) msg.optInt("seq") else null
                onAck(seq, false, code)
                if (code == "bad_pin") {
                    onStatus("PIN 错误")
                    disconnect()
                } else if (code == "not_hello") {
                    onStatus("协议错误：未握手")
                }
            }
            "pong" -> {
                val cb = rttCallback
                if (cb != null && rttStartNs > 0L) {
                    rttCallback = null
                    val ms = (System.nanoTime() - rttStartNs) / 1_000_000L
                    cb(ms)
                }
            }
            else -> Unit
        }
    }
}
