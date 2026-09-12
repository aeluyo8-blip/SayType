package com.phonetype.app

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.animation.doOnEnd
import kotlin.math.abs

/**
 * 冰透玻璃 S 悬浮球 + 输入面板。
 * 与 MainActivity 共用 AppBus.ws。
 * P0-1：面板打开时隐藏球。
 * P0-1b：收起面板后约 2s 无操作自动贴边。
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var docked = false
    private var dockedLeft = true
    private var idleRunnable: Runnable? = null
    private val idleDelayMs = 2000L

    private val density: Float get() = resources.displayMetrics.density
    private val screenW: Int get() = resources.displayMetrics.widthPixels

    private val pendingText = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private val busStatus = AppBus.StatusListener { s ->
        mainHandler.post { updatePanelStatus(s) }
    }
    private val busAck = AppBus.AckListener { seq, ok, detail ->
        mainHandler.post {
            val sent = seq?.let { pendingText.remove(it) }
            if (sent != null) {
                // 以 ack 结果为准写入历史，避免注入失败仍记成功
                HistoryStore.add(this, HistoryStore.Kind.SENT, sent, ok)
            }
            val label = when {
                ok -> getString(R.string.sent_ok)
                detail == "inject_failed" -> "电脑注入失败"
                detail == "bad_pin" -> "PIN 错误"
                else -> "发送失败: $detail"
            }
            updatePanelStatus(label)
        }
    }

    private val configCallback = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            mainHandler.post { reclampBubbleAfterRotate() }
        }
        override fun onLowMemory() {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        registerComponentCallbacks(configCallback)
        AppBus.addStatusListener(busStatus)
        AppBus.addAckListener(busAck)
        ensureChannel()
        startForeground(NOTI_ID, buildNotification("悬浮球运行中"))
        addBubble()
    }

    private fun reclampBubbleAfterRotate() {
        val v = bubbleView ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        val sz = fullSize()
        if (docked) {
            val targetX = if (dockedLeft) (-8 * density).toInt()
            else screenW - dockW() + (8 * density).toInt()
            params.x = targetX
            params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - dockH()).coerceAtLeast(0))
            params.width = dockW()
            params.height = dockH()
        } else {
            params.x = params.x.coerceIn(0, (screenW - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0))
            if (params.width < sz / 2) {
                // 异常尺寸时恢复完整球
                applyBallSize(v, params, full = true)
                return
            }
        }
        try {
            wm.updateViewLayout(v, params)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = Prefs.host(this)
                val port = Prefs.port(this)
                val pin = Prefs.pin(this)
                AppBus.ensureWs(host, port, pin)
                AppBus.ws?.connect(host, port, pin)
            }
            ACTION_OPEN_PANEL -> {
                undockImmediate()
                showPanel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppBus.removeStatusListener(busStatus)
        AppBus.removeAckListener(busAck)
        unregisterComponentCallbacks(configCallback)
        cancelIdleDock()
        cancelMorph()
        removeOverlay(panelView)
        removeOverlay(bubbleView)
        panelView = null
        bubbleView = null
        super.onDestroy()
    }

    private fun cancelIdleDock() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun scheduleIdleDock() {
        cancelIdleDock()
        if (!Prefs.autoDock(this)) return
        if (docked || panelView != null || bubbleView == null) return
        val r = Runnable {
            idleRunnable = null
            val v = bubbleView ?: return@Runnable
            if (panelView != null || docked) return@Runnable
            val params = v.layoutParams as WindowManager.LayoutParams
            dock(params.x + v.width / 2 <= screenW / 2)
        }
        idleRunnable = r
        mainHandler.postDelayed(r, idleDelayMs)
    }

    @SuppressLint("InflateParams")
    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleView != null) return

        val v = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        val size = (56 * density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - (16 * density).toInt()
            y = (280 * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var wasDockedOnDown = false

        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelIdleDock()
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    wasDockedOnDown = docked
                    if (docked) {
                        // 贴边态按下：平滑展开为完整球，本手势 UP 不直接开面板
                        cancelMorph()
                        docked = false
                        val pad = (16 * density).toInt()
                        val sz = fullSize()
                        val nx = (if (dockedLeft) pad else screenW - sz - pad)
                        animateMorph(nx, sz, sz, 1f, toDocked = false, durationMs = 220)
                    }
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            wm.updateViewLayout(v, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (!moved) {
                        if (wasDockedOnDown) {
                            // 贴边点击 → 完整球（不直接开面板）
                            undockImmediate()
                            scheduleIdleDock()
                        } else {
                            showPanel()
                        }
                    } else {
                        val left = params.x + v.width / 2 <= screenW / 2
                        dock(left)
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(v, params)
        bubbleView = v
    }

    private fun applyBallSize(v: View, params: WindowManager.LayoutParams, full: Boolean) {
        val iv = v as? android.widget.ImageView
        if (full) {
            val size = (56 * density).toInt()
            params.width = size
            params.height = size
            iv?.setImageResource(R.drawable.ic_ball_s_photo)
            iv?.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        } else {
            val w = (14 * density).toInt()
            val h = (36 * density).toInt()
            params.width = w
            params.height = h
            iv?.setImageResource(R.drawable.ic_ball_docked_photo)
            iv?.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        try {
            wm.updateViewLayout(v, params)
        } catch (_: Exception) {
        }
    }

    private var morphAnim: ValueAnimator? = null

    private fun cancelMorph() {
        morphAnim?.cancel()
        morphAnim = null
    }

    /**
     * 位置 + 尺寸 + 透明度一起插值，避免「先换尺寸再平移」的跳变。
     * 贴边用缓出；展开用轻微回弹。
     */
    private fun animateMorph(
        targetX: Int,
        targetW: Int,
        targetH: Int,
        targetAlpha: Float,
        toDocked: Boolean,
        durationMs: Long,
    ) {
        val v = bubbleView ?: return
        cancelMorph()
        val params = v.layoutParams as WindowManager.LayoutParams
        val fromX = params.x
        val fromW = params.width
        val fromH = params.height
        val fromA = v.alpha

        val iv = v as? android.widget.ImageView
        if (toDocked) {
            iv?.setImageResource(R.drawable.ic_ball_docked_photo)
            iv?.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        } else {
            iv?.setImageResource(R.drawable.ic_ball_s_photo)
            iv?.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

        val interpolator: android.animation.TimeInterpolator =
            if (toDocked) {
                android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            } else {
                // 轻微 overshoot，像弹簧收回
                android.view.animation.OvershootInterpolator(0.75f)
            }

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = durationMs
            this.interpolator = interpolator
        }
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.x = (fromX + (targetX - fromX) * t).toInt()
            lp.width = (fromW + (targetW - fromW) * t).toInt().coerceAtLeast(1)
            lp.height = (fromH + (targetH - fromH) * t).toInt().coerceAtLeast(1)
            v.alpha = fromA + (targetAlpha - fromA) * t
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
        anim.doOnEnd {
            if (morphAnim === anim) morphAnim = null
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.x = targetX
            lp.width = targetW
            lp.height = targetH
            v.alpha = targetAlpha
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
        morphAnim = anim
        anim.start()
    }

    private fun fullSize(): Int = (56 * density).toInt()
    private fun dockW(): Int = (14 * density).toInt()
    private fun dockH(): Int = (36 * density).toInt()

    private fun dock(sideLeft: Boolean) {
        val v = bubbleView ?: return
        dockedLeft = sideLeft
        docked = true
        cancelIdleDock()
        val targetX = if (sideLeft) (-8 * density).toInt()
        else screenW - dockW() + (8 * density).toInt()
        animateMorph(
            targetX = targetX,
            targetW = dockW(),
            targetH = dockH(),
            targetAlpha = 0.85f,
            toDocked = true,
            durationMs = 340,
        )
    }

    private fun undockImmediate() {
        undockAnimated(durationMs = 280)
    }

    private fun undockAnimated(durationMs: Long = 280) {
        val v = bubbleView ?: return
        cancelMorph()
        docked = false
        val pad = (16 * density).toInt()
        val size = fullSize()
        val targetX = if (dockedLeft) pad else screenW - size - pad
        animateMorph(
            targetX = targetX,
            targetW = size,
            targetH = size,
            targetAlpha = 1f,
            toDocked = false,
            durationMs = durationMs,
        )
    }

    private fun hideBubbleForPanel() {
        cancelMorph()
        cancelIdleDock()
        bubbleView?.let { v ->
            val a = ValueAnimator.ofFloat(v.alpha, 0f).apply {
                duration = 120
                interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
            }
            a.addUpdateListener {
                v.alpha = it.animatedValue as Float
            }
            a.doOnEnd {
                v.visibility = View.GONE
                v.alpha = 1f
            }
            morphAnim = a
            a.start()
        }
    }

    private fun restoreBubbleAfterPanel() {
        bubbleView?.let { v ->
            v.visibility = View.VISIBLE
            v.alpha = 0f
            val a = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            }
            a.addUpdateListener { v.alpha = it.animatedValue as Float }
            a.start()
        }
        if (docked) undockAnimated(300)
        scheduleIdleDock()
    }

    @SuppressLint("InflateParams")
    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        if (panelView != null) return
        cancelIdleDock()
        hideBubbleForPanel()

        val themed = ContextThemeWrapper(this, R.style.Theme_PhoneType)
        // 全屏 scrim + 面板：第一次点外部只收起，不把点击传给底层 App
        val host = LayoutInflater.from(themed).inflate(R.layout.view_panel_host, null)
        val card = host.findViewById<View>(R.id.panelCard) ?: host
        val input = card.findViewById<EditText>(R.id.inputText)
        val status = card.findViewById<TextView>(R.id.statusText)
        status.text = if (AppBus.isConnected) getString(R.string.connected) else getString(R.string.not_connected)

        // scrim 点击 = 关面板；不往子 View 传
        host.setOnClickListener { closePanel() }
        // 面板本体消费点击，避免穿透 scrim
        card.setOnClickListener { /* consume */ }

        card.findViewById<View>(R.id.btnClose).setOnClickListener { closePanel() }
        card.findViewById<View>(R.id.btnClear).setOnClickListener { input.setText("") }
        card.findViewById<View>(R.id.btnNote).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.hint_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            HistoryStore.add(this, HistoryStore.Kind.NOTE, text)
            Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
        }
        card.findViewById<View>(R.id.btnSend).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.hint_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val seq = Prefs.nextSeq(this)
            if (AppBus.ws?.sendText(text, seq) != true) {
                Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show()
                status.text = getString(R.string.not_connected_send)
                return@setOnClickListener
            }
            pendingText[seq] = text
            if (Prefs.clearAfterSend(this)) input.setText("")
            status.text = "发送中…"
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 不加 FLAG_NOT_TOUCH_MODAL：整窗吃点击，避免穿透
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // 把面板内容顶到上中部（原布局 padding 基础上再抬高）
        card.setPadding(
            card.paddingLeft,
            (88 * density).toInt(),
            card.paddingRight,
            card.paddingBottom
        )

        wm.addView(host, params)
        panelView = host
        input.requestFocus()
    }

    private fun closePanel() {
        removeOverlay(panelView)
        panelView = null
        restoreBubbleAfterPanel()
    }

    private fun updatePanelStatus(s: String) {
        val host = panelView ?: return
        val card = host.findViewById<View>(R.id.panelCard) ?: host
        card.findViewById<TextView>(R.id.statusText)?.text = s
    }

    private fun removeOverlay(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SayType", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b
            .setContentTitle("SayType")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "saytype_fg"
        const val NOTI_ID = 42
        const val ACTION_CONNECT = "com.phonetype.app.CONNECT"
        const val ACTION_OPEN_PANEL = "com.phonetype.app.OPEN_PANEL"
    }
}
