package com.phonetype.app

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

    fun isConnected(): Boolean = wsRef.get() != null && authed.get()

    fun connect(host: String, port: Int, pin: String) {
        disconnect()
        if (host.isBlank() || pin.isBlank()) {
            onStatus("请填写 IP 和 PIN")
            return
        }
        val gen = generation.incrementAndGet()
        onStatus("连接中…")
        val req = Request.Builder().url("ws://$host:$port").build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            private fun isCurrent(): Boolean = generation.get() == gen

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) {
                    webSocket.close(1000, "stale")
                    return
                }
                wsRef.set(webSocket)
                webSocket.send(JSONObject().put("type", "hello").put("pin", pin).toString())
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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                cleanupIfSame(webSocket)
                onStatus("连接失败: ${t.message ?: "unknown"}")
            }
        })
        wsRef.set(ws)
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
        generation.incrementAndGet()
        authed.set(false)
        wsRef.getAndSet(null)?.close(1000, "bye")
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
            "pong" -> Unit
            else -> Unit
        }
    }
}
