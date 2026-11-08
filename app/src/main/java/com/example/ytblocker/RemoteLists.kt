package com.example.ytblocker

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// يجلب قوائم الحجب من الوسيط (اللي تحدّثه شاشة تطبيق الأم) ويحفظ آخر نسخة
object RemoteLists {
    private const val URL_BASE = "https://nataij-api.myshuruhat.workers.dev"
    private const val PAIR = "xeb3vuyM0FOa"

    class Data(val handles: List<String>, val names: List<String>, val exact: List<String>, val blocking: Boolean)

    private fun p(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    // true = الأم تبي الأيقونة ظاهرة، false = مخفية، null = ما حددت
    fun iconWanted(ctx: Context): Boolean? = when (p(ctx).getInt("icon", -1)) { 1 -> true; 0 -> false; else -> null }

    fun load(ctx: Context): Data? {
        val raw = p(ctx).getString("data", null) ?: return null
        return try {
            val o = JSONObject(raw)
            fun l(k: String): List<String> { val a = o.optJSONArray(k) ?: return emptyList(); return (0 until a.length()).map { a.getString(it) } }
            Data(l("handles"), l("names"), l("exact"), o.optBoolean("blocking", true))
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
                if (o.has("icon")) {
                    val vis = o.optBoolean("icon", false)
                    p(ctx).edit().putInt("icon", if (vis) 1 else 0).apply()
                    runCatching { IconToggle.set(ctx, vis) }
                }
                if (txt == p(ctx).getString("data", null)) return@Thread
                p(ctx).edit().putString("data", txt).apply()
                onChanged()
            } catch (e: Exception) { }
        }.start()
    }
}
