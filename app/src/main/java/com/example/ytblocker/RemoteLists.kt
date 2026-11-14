package com.example.ytblocker

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// يجلب قوائم الحجب من الوسيط (اللي تحدّثه شاشة تطبيق الأم) ويحفظ آخر نسخة
object RemoteLists {
    // غير private عشان UsageReport يستخدمهم لنفس الوسيط/كود الربط
    const val URL_BASE = "https://nataij-api.myshuruhat.workers.dev"
    const val PAIR = "xeb3vuyM0FOa"

    class Data(
        val handles: List<String>, val names: List<String>, val exact: List<String>, val blocking: Boolean,
        val fullBlockApps: List<String> = emptyList(), val scanApps: List<String> = emptyList(),
        val searchApps: List<String> = emptyList(), val warningMessage: String = ""
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun load(ctx: Context): Data? {
        val raw = p(ctx).getString("data", null) ?: return null
        return try {
            val o = JSONObject(raw)
            fun l(k: String): List<String> { val a = o.optJSONArray(k) ?: return emptyList(); return (0 until a.length()).map { a.getString(it) } }
            Data(l("handles"), l("names"), l("exact"), o.optBoolean("blocking", true), l("fullBlockApps"), l("scanApps"), l("searchApps"), o.optString("warningMessage", ""))
        } catch (e: Exception) { null }
    }

    fun pull(ctx0: Context, onChanged: () -> Unit) {
        val ctx = ctx0.applicationContext
        Thread {
            try {
                val c = URL("$URL_BASE/blocklist?pair=$PAIR").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode !in 200..299) return@Thread
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                if (o.optBoolean("empty", false)) return@Thread // ما أرسلت الأم شي بعد: نكمل بالقوائم الثابتة
                if (txt == p(ctx).getString("data", null)) return@Thread
                p(ctx).edit().putString("data", txt).apply()
                onChanged()
            } catch (e: Exception) { }
        }.start()
    }
}
