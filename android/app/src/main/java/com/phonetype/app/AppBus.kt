package com.phonetype.app

/** 全局连接状态总线：Activity 与悬浮球 Service 共用一个 WsManager，回调走 Listener 集合避免闭包绑死组件 */
object AppBus {
    fun interface StatusListener {
        fun onStatus(text: String)
    }

    fun interface AckListener {
        fun onAck(seq: Int?, ok: Boolean, detail: String)
    }

    @Volatile
    var ws: WsManager? = null

    @Volatile
    var lastStatus: String = ""

    @Volatile
    var connectedHost: String = ""

    @Volatile
    var lastRttMs: Int? = null

    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<StatusListener>()
    private val ackListeners = java.util.concurrent.CopyOnWriteArrayList<AckListener>()

    val isConnected: Boolean
        get() = ws?.isConnected() == true

    fun addStatusListener(l: StatusListener) {
        if (!statusListeners.contains(l)) statusListeners.add(l)
    }

    fun removeStatusListener(l: StatusListener) {
        statusListeners.remove(l)
    }

    fun addAckListener(l: AckListener) {
        if (!ackListeners.contains(l)) ackListeners.add(l)
    }

    fun removeAckListener(l: AckListener) {
        ackListeners.remove(l)
    }

    fun publishStatus(text: String) {
        lastStatus = text
        if (text.contains("已连接")) {
            connectedHost = connectedHost.ifBlank { connectedHost }
        }
        statusListeners.forEach { it.onStatus(text) }
    }

    fun publishAck(seq: Int?, ok: Boolean, detail: String) {
        ackListeners.forEach { it.onAck(seq, ok, detail) }
    }

    /** 首次创建时挂上 App 级回调；之后 Activity/Service 只注册 Listener */
    fun ensureWs(host: String, port: Int, pin: String) {
        if (ws != null) return
        ws = WsManager(
            onStatus = { s -> publishStatus(s) },
            onAck = { seq, ok, detail -> publishAck(seq, ok, detail) },
        )
    }
}
