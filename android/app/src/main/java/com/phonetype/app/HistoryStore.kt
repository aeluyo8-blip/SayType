package com.phonetype.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 发送记录 / 笔记，JSON 文件存储（无 Room 依赖） */
object HistoryStore {
    enum class Kind { SENT, NOTE }

    data class Entry(
        val id: Long,
        val kind: Kind,
        val text: String,
        val atMs: Long,
        val ok: Boolean,
    )

    private fun file(c: Context): File = File(c.filesDir, "history.json")

    fun add(c: Context, kind: Kind, text: String, ok: Boolean = true) {
        val arr = readAll(c)
        val item = JSONObject()
            .put("id", System.currentTimeMillis())
            .put("kind", if (kind == Kind.SENT) "sent" else "note")
            .put("text", text)
            .put("at", System.currentTimeMillis())
            .put("ok", ok)
        arr.put(item)
        write(c, arr)
    }

    fun list(c: Context, kind: Kind, limit: Int = 200): List<Entry> {
        val arr = readAll(c)
        val out = ArrayList<Entry>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val k = if (o.optString("kind") == "note") Kind.NOTE else Kind.SENT
            if (k != kind) continue
            out.add(
                Entry(
                    id = o.optLong("id"),
                    kind = k,
                    text = o.optString("text"),
                    atMs = o.optLong("at"),
                    ok = o.optBoolean("ok", true),
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    fun clear(c: Context, kind: Kind) {
        val arr = readAll(c)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = if (o.optString("kind") == "note") Kind.NOTE else Kind.SENT
            if (k != kind) kept.put(o)
        }
        write(c, kept)
    }

    private fun readAll(c: Context): JSONArray {
        val f = file(c)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun write(c: Context, arr: JSONArray) {
        try {
            file(c).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
