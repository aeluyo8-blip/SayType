package com.phonetype.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var statusView: TextView

    private val overlayPerm =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        pinInput = findViewById(R.id.pinInput)
        statusView = findViewById(R.id.statusText)
        val btnPerm = findViewById<Button>(R.id.btnOverlayPerm)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnStart = findViewById<Button>(R.id.btnStartBubble)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnStop = findViewById<Button>(R.id.btnStop)

        hostInput.setText(Prefs.host(this).ifEmpty { guessHost() })
        portInput.setText(Prefs.port(this).toString())
        pinInput.setText(Prefs.pin(this))

        btnPerm.setOnClickListener { requestOverlay() }
        btnSave.setOnClickListener {
            val port = portInput.text.toString().toIntOrNull() ?: 8787
            Prefs.save(
                this,
                hostInput.text.toString(),
                port,
                pinInput.text.toString()
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        btnStart.setOnClickListener {
            saveQuiet()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                requestOverlay()
                return@setOnClickListener
            }
            maybeRequestNotif()
            val i = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        btnConnect.setOnClickListener {
            saveQuiet()
            val i = Intent(this, FloatingBubbleService::class.java)
                .setAction(FloatingBubbleService.ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            Toast.makeText(this, "正在连接 PC…", Toast.LENGTH_SHORT).show()
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingBubbleService::class.java))
            Toast.makeText(this, "已停止悬浮球", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        refreshStatus()
    }

    private fun saveQuiet() {
        val port = portInput.text.toString().toIntOrNull() ?: 8787
        Prefs.save(this, hostInput.text.toString(), port, pinInput.text.toString())
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已有悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPerm.launch(intent)
    }

    private fun maybeRequestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val host = hostInput.text.toString().ifBlank { "—" }
        val port = portInput.text.toString().ifBlank { "—" }
        statusView.text = buildString {
            append("悬浮窗权限: ").append(if (overlay) "已授予" else "未授予").append('\n')
            append("目标: ws://").append(host).append(':').append(port).append('\n')
            append("步骤: 存配置 → 启动悬浮球 → 连接 PC → 点球发送")
        }
    }

    private fun guessHost(): String {
        // Best-effort: leave empty; user fills PC console IP.
        return ""
    }
}
