package com.phonetype.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var ws: WsManager? = null
    private var statusText: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ws = WsManager(
            onStatus = { s ->
                mainHandler.post {
                    statusText?.text = s
                    Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
                }
            },
            onAck = { seq, ok, detail ->
                mainHandler.post {
                    val label = when {
                        ok -> "已发送${if (seq != null) " #$seq" else ""}"
                        detail == "inject_failed" -> "电脑注入失败"
                        detail == "bad_pin" -> "PIN 错误"
                        else -> "发送失败: $detail"
                    }
                    statusText?.text = label
                    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
                }
            }
        )
        ensureChannel()
        startForeground(NOTI_ID, buildNotification("悬浮球运行中"))
        addBubble()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONNECT) {
            val host = Prefs.host(this)
            val port = Prefs.port(this)
            val pin = Prefs.pin(this)
            ws?.connect(host, port, pin)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ws?.disconnect()
        removeOverlay(bubbleView)
        removeOverlay(panelView)
        bubbleView = null
        panelView = null
        super.onDestroy()
    }

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleView != null) return

        val v = ImageButton(this).apply {
            setImageResource(R.drawable.ic_bubble)
            setBackgroundColor(0x00000000)
            contentDescription = "phone-type"
        }

        val type = overlayType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        v.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        wm.addView(v, params)
        bubbleView = v
    }

    private fun togglePanel() {
        if (panelView != null) {
            removeOverlay(panelView)
            panelView = null
            statusText = null
            return
        }
        showPanel()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        val root = LayoutInflater.from(this).inflate(R.layout.view_input_panel, null)
        statusText = root.findViewById(R.id.statusText)
        val input = root.findViewById<EditText>(R.id.inputText)
        val send = root.findViewById<Button>(R.id.btnSend)
        val clear = root.findViewById<Button>(R.id.btnClear)
        val close = root.findViewById<Button>(R.id.btnClose)

        statusText?.text = if (ws?.isConnected() == true) "已连接" else "未连接"

        send.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "先输入文字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val seq = Prefs.nextSeq(this)
            val ok = ws?.sendText(text, seq) == true
            if (ok) {
                input.setText("")
                statusText?.text = "发送中…"
            } else {
                statusText?.text = "未连接，无法发送"
                Toast.makeText(this, "未连接，请先在主界面连接", Toast.LENGTH_SHORT).show()
            }
        }
        clear.setOnClickListener { input.setText("") }
        close.setOnClickListener {
            removeOverlay(panelView)
            panelView = null
        }

        val type = overlayType()
        // Panel needs focus for IME
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // Bubble starts as FLAG_NOT_FOCUSABLE; panel must be focusable for EditText.
        // Clear NOT_FOCUSABLE if present by using a clean flags set above.

        wm.addView(root, params)
        panelView = root
        input.requestFocus()
    }

    private fun removeOverlay(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "phone-type",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle("phone-type")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "phone_type_fg"
        const val NOTI_ID = 42
        const val ACTION_CONNECT = "com.phonetype.app.CONNECT"
    }
}
