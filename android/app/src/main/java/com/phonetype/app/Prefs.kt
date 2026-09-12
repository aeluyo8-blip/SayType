package com.phonetype.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "phone_type"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_PIN = "pin"
    private const val K_SEQ = "seq"
    private const val K_BALL = "ball_on"
    private const val K_AUTO_DOCK = "auto_dock"
    private const val K_CLEAR_AFTER = "clear_after"
    private const val K_LAST_TAB = "last_tab"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun host(c: Context): String = sp(c).getString(K_HOST, "") ?: ""
    fun port(c: Context): Int = sp(c).getInt(K_PORT, 8787)
    fun pin(c: Context): String = sp(c).getString(K_PIN, "") ?: ""

    fun isPaired(c: Context): Boolean =
        host(c).isNotBlank() && pin(c).isNotBlank()

    fun save(c: Context, host: String, port: Int, pin: String) {
        sp(c).edit()
            .putString(K_HOST, host.trim())
            .putInt(K_PORT, port)
            .putString(K_PIN, pin.trim())
            .apply()
    }

    fun nextSeq(c: Context): Int {
        val v = sp(c).getInt(K_SEQ, 0) + 1
        sp(c).edit().putInt(K_SEQ, v).apply()
        return v
    }

    fun ballEnabled(c: Context): Boolean = sp(c).getBoolean(K_BALL, false)
    fun setBallEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(K_BALL, on).apply()
    }

    fun autoDock(c: Context): Boolean = sp(c).getBoolean(K_AUTO_DOCK, true)
    fun setAutoDock(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(K_AUTO_DOCK, on).apply()
    }

    fun clearAfterSend(c: Context): Boolean = sp(c).getBoolean(K_CLEAR_AFTER, true)
    fun setClearAfterSend(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(K_CLEAR_AFTER, on).apply()
    }

    /** 1=home 2=history 3=settings；0 表示未记录 */
    fun lastTab(c: Context): Int = sp(c).getInt(K_LAST_TAB, 0)
    fun setLastTab(c: Context, tab: Int) {
        sp(c).edit().putInt(K_LAST_TAB, tab).apply()
    }
}
