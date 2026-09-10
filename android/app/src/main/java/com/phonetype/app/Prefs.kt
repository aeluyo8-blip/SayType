package com.phonetype.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "phone_type"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_PIN = "pin"
    private const val K_SEQ = "seq"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun host(c: Context): String = sp(c).getString(K_HOST, "") ?: ""
    fun port(c: Context): Int = sp(c).getInt(K_PORT, 8787)
    fun pin(c: Context): String = sp(c).getString(K_PIN, "") ?: ""

    fun save(c: Context, host: String, port: Int, pin: String) {
        sp(c).edit()
            .putString(K_HOST, host.trim())
            .putInt(K_PORT, port)
            .putString(K_PIN, pin.trim())
            .apply()
    }

    fun nextSeq(c: Context): Int {
        val ed = sp(c).edit()
        val v = sp(c).getInt(K_SEQ, 0) + 1
        ed.putInt(K_SEQ, v).apply()
        return v
    }
}
