package com.phonetype.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneTypeConfig(val host: String, val port: Int, val pin: String)

fun parsePhoneTypeConfig(raw: String): PhoneTypeConfig? = try {
    val uri = Uri.parse(raw.trim())
    if (!uri.scheme.equals("phonetype", ignoreCase = true)) null
    else {
        val host = uri.host
        val pin = uri.getQueryParameter("pin")
        val port = if (uri.port in 1..65535) uri.port else 8787
        if (host.isNullOrBlank() || pin.isNullOrBlank()) null
        else PhoneTypeConfig(host, port, pin)
    }
} catch (_: Exception) {
    null
}

class MainActivity : AppCompatActivity() {

    private lateinit var container: ViewGroup
    private lateinit var bottomNav: BottomNavigationView
    private var connectView: View? = null
    private var homeView: View? = null
    private var historyView: View? = null
    private var settingsView: View? = null
    private var histSent = true
    /** 防止 selectedItemId 与 onItemSelected 互相触发导致 StackOverflow */
    private var navProgrammatic = false

    private val overlayPerm =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshAll() }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        val cfg = parsePhoneTypeConfig(raw)
        if (cfg == null) {
            Toast.makeText(this, R.string.invalid_qr, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        Prefs.save(this, cfg.host, cfg.port, cfg.pin)
        ensureWs()
        AppBus.ws?.connect(cfg.host, cfg.port, cfg.pin)
        Toast.makeText(this, R.string.imported, Toast.LENGTH_LONG).show()
        showTab(TAB_HOME)
        refreshAll()
    }

    private val busStatus = AppBus.StatusListener { s ->
        runOnUiThread {
            if (s.contains("已连接")) {
                AppBus.connectedHost = Prefs.host(this)
            }
            refreshAll()
        }
    }
    private val busAck = AppBus.AckListener { _, ok, detail ->
        runOnUiThread {
            val label = when {
                ok -> getString(R.string.sent_ok)
                detail == "inject_failed" -> "电脑注入失败"
                detail == "bad_pin" -> "PIN 错误"
                else -> "发送失败: $detail"
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            refreshAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 避免内容顶到状态栏 / 底部手势条
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(android.R.id.content)
        ) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        container = findViewById(R.id.container)
        bottomNav = findViewById(R.id.bottomNav)
        AppBus.addStatusListener(busStatus)
        AppBus.addAckListener(busAck)
        bottomNav.setOnItemSelectedListener { item ->
            if (navProgrammatic) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> { showTab(TAB_HOME); true }
                R.id.nav_history -> { showTab(TAB_HISTORY); true }
                R.id.nav_settings -> { showTab(TAB_SETTINGS); true }
                else -> false
            }
        }
        ensureWs()
        // 旋转/进程重建后回到上次 Tab；未配对仍进 Connect
        if (!Prefs.isPaired(this)) {
            showTab(TAB_CONNECT)
        } else when (Prefs.lastTab(this)) {
            TAB_HISTORY -> showTab(TAB_HISTORY)
            TAB_SETTINGS -> showTab(TAB_SETTINGS)
            else -> showTab(TAB_HOME)
        }
    }

    override fun onDestroy() {
        AppBus.removeStatusListener(busStatus)
        AppBus.removeAckListener(busAck)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun ensureWs() {
        AppBus.ensureWs(Prefs.host(this), Prefs.port(this), Prefs.pin(this))
    }

    private fun inflate(layoutId: Int): View {
        val v = LayoutInflater.from(this).inflate(layoutId, container, false)
        container.addView(v)
        return v
    }

    private fun showOnly(target: View?) {
        listOf(connectView, homeView, historyView, settingsView).forEach {
            it?.visibility = if (it === target) View.VISIBLE else View.GONE
        }
    }

    private fun showTab(tab: Int) {
        bottomNav.visibility = if (tab == TAB_CONNECT) View.GONE else View.VISIBLE
        when (tab) {
            TAB_CONNECT -> {
                if (connectView == null) {
                    connectView = inflate(R.layout.screen_connect).also { bindConnect(it) }
                }
                showOnly(connectView)
            }
            TAB_HOME -> {
                if (homeView == null) {
                    homeView = inflate(R.layout.screen_home).also { bindHome(it) }
                }
                showOnly(homeView)
                selectNav(R.id.nav_home)
                Prefs.setLastTab(this, TAB_HOME)
            }
            TAB_HISTORY -> {
                if (historyView == null) {
                    historyView = inflate(R.layout.screen_history).also { bindHistory(it) }
                }
                showOnly(historyView)
                selectNav(R.id.nav_history)
                Prefs.setLastTab(this, TAB_HISTORY)
            }
            TAB_SETTINGS -> {
                if (settingsView == null) {
                    settingsView = inflate(R.layout.screen_settings).also { bindSettings(it) }
                }
                showOnly(settingsView)
                selectNav(R.id.nav_settings)
                Prefs.setLastTab(this, TAB_SETTINGS)
            }
        }
        refreshAll()
    }

    private fun selectNav(itemId: Int) {
        if (bottomNav.selectedItemId == itemId) return
        navProgrammatic = true
        try {
            bottomNav.selectedItemId = itemId
        } finally {
            navProgrammatic = false
        }
    }

    private fun bindConnect(v: View) {
        v.findViewById<View>(R.id.btnPermGo).setOnClickListener { requestOverlay() }
        v.findViewById<View>(R.id.btnScanPair).setOnClickListener { startScan() }
        v.findViewById<View>(R.id.manualToggle).setOnClickListener {
            val form = v.findViewById<View>(R.id.manualForm)
            form.visibility = if (form.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        v.findViewById<EditText>(R.id.inputHost).setText(Prefs.host(this).ifEmpty { "" })
        v.findViewById<EditText>(R.id.inputPort).setText(Prefs.port(this).toString())
        v.findViewById<EditText>(R.id.inputPin).setText(Prefs.pin(this))
        v.findViewById<View>(R.id.btnSaveConnect).setOnClickListener {
            val host = v.findViewById<EditText>(R.id.inputHost).text.toString().trim()
            val port = v.findViewById<EditText>(R.id.inputPort).text.toString().toIntOrNull() ?: 8787
            val pin = v.findViewById<EditText>(R.id.inputPin).text.toString().trim()
            if (host.isBlank() || pin.isBlank()) {
                Toast.makeText(this, "请填写 IP 和 PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.save(this, host, port, pin)
            ensureWs()
            AppBus.ws?.connect(host, port, pin)
            showTab(TAB_HOME)
        }
    }

    private fun bindHome(v: View) {
        v.findViewById<View>(R.id.btnRepair).setOnClickListener { showTab(TAB_CONNECT) }
        v.findViewById<View>(R.id.btnViewAll).setOnClickListener { showTab(TAB_HISTORY) }
        v.findViewById<View>(R.id.rowPreview).setOnClickListener {
            openPanelPreview()
        }
        // 先同步状态再挂 listener，避免首次 inflate 误触发
        val sw = v.findViewById<MaterialSwitch>(R.id.switchBall)
        sw.isChecked = Prefs.ballEnabled(this)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.setBallEnabled(this, on)
            toggleBubbleService(on)
            refreshAll()
        }
    }

    private fun bindHistory(v: View) {
        v.findViewById<View>(R.id.segSent).setOnClickListener {
            histSent = true
            refreshHistory()
        }
        v.findViewById<View>(R.id.segNotes).setOnClickListener {
            histSent = false
            refreshHistory()
        }
        v.findViewById<View>(R.id.btnClearHist).setOnClickListener {
            HistoryStore.clear(this, if (histSent) HistoryStore.Kind.SENT else HistoryStore.Kind.NOTE)
            refreshHistory()
        }
    }

    private fun bindSettings(v: View) {
        v.findViewById<View>(R.id.btnRepair).setOnClickListener { showTab(TAB_CONNECT) }
        val dock = v.findViewById<MaterialSwitch>(R.id.switchAutoDock)
        dock.isChecked = Prefs.autoDock(this)
        dock.setOnCheckedChangeListener { _, on -> Prefs.setAutoDock(this, on) }
        val clear = v.findViewById<MaterialSwitch>(R.id.switchClearAfter)
        clear.isChecked = Prefs.clearAfterSend(this)
        clear.setOnCheckedChangeListener { _, on -> Prefs.setClearAfterSend(this, on) }
        v.findViewById<TextView>(R.id.aboutVer).text =
            "v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "0.3.0")
    }

    private fun maskPin(pin: String): String = when {
        pin.isBlank() || pin.length < 4 -> "••••••"
        else -> "••" + pin.takeLast(2)
    }

    private fun openPanelPreview() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show()
            requestOverlay()
            return
        }
        maybeRequestNotif()
        if (!Prefs.ballEnabled(this)) {
            Prefs.setBallEnabled(this, true)
            toggleBubbleService(true)
        }
        val i = Intent(this, FloatingBubbleService::class.java)
            .setAction(FloatingBubbleService.ACTION_OPEN_PANEL)
        ContextCompat.startForegroundService(this, i)
    }

    private fun toggleBubbleService(on: Boolean) {
        val i = Intent(this, FloatingBubbleService::class.java)
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show()
                requestOverlay()
                Prefs.setBallEnabled(this, false)
                (homeView?.findViewById<MaterialSwitch>(R.id.switchBall))?.isChecked = false
                return
            }
            maybeRequestNotif()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            if (Prefs.isPaired(this)) {
                i.action = FloatingBubbleService.ACTION_CONNECT
                startForegroundService(i)
            }
            Toast.makeText(this, R.string.bubble_started, Toast.LENGTH_SHORT).show()
        } else {
            stopService(i)
            Toast.makeText(this, R.string.bubble_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScan() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        scanLauncher.launch(opts)
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已有悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        overlayPerm.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun maybeRequestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshAll() {
        refreshConnect()
        refreshHome()
        refreshHistory()
        refreshSettings()
    }

    private fun refreshConnect() {
        val v = connectView ?: return
        val overlay = Settings.canDrawOverlays(this)
        v.findViewById<View>(R.id.permBanner).visibility = if (overlay) View.GONE else View.VISIBLE
        val chip = v.findViewById<TextView>(R.id.chipConn)
        val on = AppBus.isConnected
        chip.text = getString(if (on) R.string.connected else R.string.not_connected)
        chip.setTextColor(getColor(if (on) R.color.ok else R.color.ink_2))
    }

    private fun refreshHome() {
        val v = homeView ?: return
        val on = AppBus.isConnected
        val chip = v.findViewById<TextView>(R.id.chipConn)
        chip.text = getString(if (on) R.string.connected else R.string.not_connected)
        chip.setTextColor(getColor(if (on) R.color.ok else R.color.ink_2))
        v.findViewById<TextView>(R.id.homePcName).text =
            if (Prefs.host(this).isNotBlank()) "PC" else "—"
        val host = Prefs.host(this).ifBlank { "—" }
        val port = Prefs.port(this)
        v.findViewById<TextView>(R.id.homeAddr).text = "$host:$port · ${getString(R.string.same_wifi)}"
        v.findViewById<TextView>(R.id.homeLatency).text = AppBus.lastRttMs?.let { "${it}ms" } ?: "—"
        val ballOn = Prefs.ballEnabled(this)
        v.findViewById<TextView>(R.id.ballStateText).text =
            getString(if (ballOn) R.string.ball_running else R.string.ball_stopped)
        val sw = v.findViewById<MaterialSwitch>(R.id.switchBall)
        // 程序化同步，不触发 listener 业务
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = ballOn
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.setBallEnabled(this, on)
            toggleBubbleService(on)
            refreshAll()
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val recent = HistoryStore.list(this, HistoryStore.Kind.SENT, 3)
        v.findViewById<TextView>(R.id.recentList).text = if (recent.isEmpty()) {
            getString(R.string.empty_sent)
        } else {
            recent.joinToString("\n") { "${fmt.format(Date(it.atMs))}  ${it.text.take(40)}" }
        }
    }

    private fun refreshHistory() {
        val v = historyView ?: return
        val list = v.findViewById<LinearLayout>(R.id.histList)
        val sent = v.findViewById<TextView>(R.id.segSent)
        val notes = v.findViewById<TextView>(R.id.segNotes)
        if (histSent) {
            sent.setBackgroundResource(R.drawable.bg_seg_on)
            sent.setTextColor(getColor(R.color.ink))
            notes.background = null
            notes.setTextColor(getColor(R.color.ink_2))
        } else {
            notes.setBackgroundResource(R.drawable.bg_seg_on)
            notes.setTextColor(getColor(R.color.ink))
            sent.background = null
            sent.setTextColor(getColor(R.color.ink_2))
        }
        val items = HistoryStore.list(this, if (histSent) HistoryStore.Kind.SENT else HistoryStore.Kind.NOTE)
        list.removeAllViews()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                setPadding(0, 24, 0, 0)
                gravity = android.view.Gravity.CENTER
                text = getString(if (histSent) R.string.empty_sent else R.string.empty_notes)
                setTextColor(getColor(R.color.ink_3))
            }
            list.addView(empty)
        } else {
            items.forEach { e ->
                val row = TextView(this).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(28, 20, 28, 20)
                    val mark = if (e.ok) "✓" else "✗"
                    text = "$mark  ${fmt.format(Date(e.atMs))}\n${e.text}"
                    textSize = 14f
                    setTextColor(getColor(if (e.ok) R.color.ink else R.color.err))
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (10 * resources.displayMetrics.density).toInt()
                list.addView(row, lp)
            }
        }
    }

    private fun refreshSettings() {
        val v = settingsView ?: return
        val host = Prefs.host(this).ifBlank { "—" }
        val port = Prefs.port(this)
        val pin = maskPin(Prefs.pin(this))
        v.findViewById<TextView>(R.id.setPcName).text = "PC"
        v.findViewById<TextView>(R.id.setAddr).text = "$host:$port · PIN $pin"
        v.findViewById<MaterialSwitch>(R.id.switchAutoDock).isChecked = Prefs.autoDock(this)
        v.findViewById<MaterialSwitch>(R.id.switchClearAfter).isChecked = Prefs.clearAfterSend(this)
    }

    companion object {
        private const val TAB_CONNECT = 0
        private const val TAB_HOME = 1
        private const val TAB_HISTORY = 2
        private const val TAB_SETTINGS = 3
    }
}
