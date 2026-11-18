package com.example.ytblocker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// يرسل قوائم الحجب لوسيط Cloudflare عشان تطبيق Tozy يجلبها
object Sync {
    const val DEFAULT_URL = "https://nataij-api.myshuruhat.workers.dev"
    const val DEFAULT_PAIR = "xeb3vuyM0FOa"

    private fun p(ctx: Context) = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
    fun url(ctx: Context) = p(ctx).getString("url", DEFAULT_URL) ?: DEFAULT_URL
    fun pair(ctx: Context) = p(ctx).getString("pair", DEFAULT_PAIR) ?: DEFAULT_PAIR
    fun key(ctx: Context) = p(ctx).getString("key", "") ?: ""
    fun save(ctx: Context, url: String, pair: String, key: String) {
        p(ctx).edit().putString("url", url.trim().trimEnd('/')).putString("pair", pair.trim()).putString("key", key.trim()).apply()
    }
    fun configured(ctx: Context) = key(ctx).isNotBlank() && pair(ctx).isNotBlank() && url(ctx).isNotBlank()

    private val gens = ConcurrentHashMap<String, AtomicLong>()

    private fun arr(l: List<String>): JSONArray { val a = JSONArray(); l.forEach { a.put(it) }; return a }

    // يرسل بعد ثانية ونصف (لو تغيّر شي ثاني قبلها نرسل آخر نسخة بس). onResult يرسل فوراً.
    fun push(ctx0: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        val ctx = ctx0.applicationContext
        if (!configured(ctx)) { onResult?.invoke(false, "عبّي المفتاح السري أول"); return }
        val d = Control.dev // القوائم تنرسل للجهاز اللي كان مفتوح وقت التعديل
        val g = gens.getOrPut(d) { AtomicLong() }
        val my = g.incrementAndGet()
        Thread {
            try {
                if (onResult == null) Thread.sleep(1500)
                if (my != g.get()) return@Thread
                val body = JSONObject()
                    .put("handles", arr(Control.getHandles(ctx, d)))
                    .put("names", arr(Control.getNames(ctx, d)))
                    .put("exact", arr(Control.getExact(ctx, d)))
                    .put("blocking", true) // تشغيل/إيقاف الحجب صار لكل جهاز من /devices
                    .put("fullBlockApps", arr(Control.getFullBlockApps(ctx, d)))
                    .put("scanApps", arr(Control.getScanApps(ctx, d)))
                    .put("searchApps", arr(Control.getSearchApps(ctx, d)))
                    .put("warningMessage", Control.getWarningMessage(ctx, d))
                val devQ = if (d.isBlank()) "" else "&device=$d"
                val c = URL(url(ctx) + "/blocklist?pair=" + pair(ctx) + devQ).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-admin-key", key(ctx))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val ok = code in 200..299
                p(ctx).edit().putString("last", if (ok) "تمت المزامنة " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) else "فشلت المزامنة ($code)").apply()
                onResult?.invoke(ok, if (ok) "تمت المزامنة" else if (code == 401) "المفتاح السري غلط" else "فشلت ($code)")
            } catch (e: Exception) {
                p(ctx).edit().putString("last", "فشلت المزامنة (ما في نت)").apply()
                onResult?.invoke(false, "ما قدرت أتصل بالوسيط")
            }
        }.start()
    }
    fun lastStatus(ctx: Context) = p(ctx).getString("last", "ما تمت مزامنة بعد") ?: ""

    // ───────── سحب سجل عمليات البحث (اللي رفعه Tozy من صناديق البحث المحدَّدة فقط) ─────────
    class SearchEntry(val ts: String, val app: String, val label: String, val query: String, val deviceName: String = "", val device: String = "", val where: String = "", val matched: String = "", val thumb: String = "")

    fun pullSearchLog(ctx0: Context, onResult: (List<SearchEntry>?, String) -> Unit) {
        val ctx = ctx0.applicationContext
        if (!configured(ctx)) { onResult(null, "عبّي المفتاح السري أول"); return }
        Thread {
            try {
                val c = URL(url(ctx) + "/searchlog?pair=" + pair(ctx)).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode !in 200..299) { onResult(null, "فشل الطلب (${c.responseCode})"); return@Thread }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                val arr = o.optJSONArray("entries") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    val app = e.optString("app")
                    SearchEntry(e.optString("ts"), app, e.optString("label").ifBlank { app }, e.optString("q"), e.optString("deviceName"), e.optString("device"), e.optString("where"), e.optString("matched"), e.optString("thumb"))
                }
                onResult(list, if (list.isEmpty()) "ما في عمليات بحث مسجّلة بعد" else "")
            } catch (e: Exception) {
                onResult(null, "ما قدرت أتصل بالوسيط")
            }
        }.start()
    }

    // ───────── سحب سجل النشاط (مشاهدة/إخفاء/حجب) اللي رفعه Tozy من جوال الولد ─────────
    class ActivityEntry(val ts: String, val type: String, val kind: String, val channel: String, val label: String, val deviceName: String = "", val device: String = "", val where: String = "", val matched: String = "", val thumb: String = "")

    fun pullActivity(ctx0: Context, onResult: (List<ActivityEntry>?, String) -> Unit) {
        val ctx = ctx0.applicationContext
        if (!configured(ctx)) { onResult(null, "عبّي المفتاح السري أول"); return }
        Thread {
            try {
                val c = URL(url(ctx) + "/activity?pair=" + pair(ctx)).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode !in 200..299) { onResult(null, "فشل الطلب (${c.responseCode})"); return@Thread }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                cp(ctx).edit().putString("activity", txt).apply()
                val o = JSONObject(txt)
                val arr = o.optJSONArray("entries") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    ActivityEntry(e.optString("ts"), e.optString("type"), e.optString("kind"), e.optString("channel"), e.optString("label"), e.optString("deviceName"), e.optString("device"), e.optString("where"), e.optString("matched"), e.optString("thumb"))
                }
                onResult(list, if (list.isEmpty()) "ما في نشاط من جوال الولد بعد" else "")
            } catch (e: Exception) {
                onResult(null, "ما قدرت أتصل بالوسيط")
            }
        }.start()
    }

    // ───────── سحب تقرير استخدام التطبيقات (اللي رفعه Tozy) لعرضه بتطبيق الأم ─────────
    class UsageDay(val date: String, val apps: List<Triple<String, String, Int>>) // pkg, label, minutes

    fun pullUsage(ctx0: Context, device: String = "", onResult: (List<UsageDay>?, String) -> Unit) {
        val ctx = ctx0.applicationContext
        if (!configured(ctx)) { onResult(null, "عبّي المفتاح السري أول"); return }
        Thread {
            try {
                val c = URL(url(ctx) + "/usage?pair=" + pair(ctx) + (if (device.isBlank()) "" else "&device=$device")).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode !in 200..299) { onResult(null, "فشل الطلب (${c.responseCode})"); return@Thread }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                val days = o.keys().asSequence().map { date ->
                    val day = o.getJSONObject(date)
                    val appsArr = day.optJSONArray("apps") ?: JSONArray()
                    val apps = (0 until appsArr.length()).map { i ->
                        val a = appsArr.getJSONObject(i)
                        Triple(a.optString("pkg"), a.optString("label"), a.optInt("minutes"))
                    }.sortedByDescending { it.third }
                    UsageDay(date, apps)
                }.sortedByDescending { it.date }.toList()
                onResult(days, if (days.isEmpty()) "ما في تقرير استخدام بعد" else "")
            } catch (e: Exception) {
                onResult(null, "ما قدرت أتصل بالوسيط")
            }
        }.start()
    }

    // ───────── الأجهزة: قائمة أجهزة Tozy المسجّلة، وتشغيل/إيقاف الحجب لكل جهاز لحاله ─────────
    data class DeviceEntry(val id: String, val name: String, val blocking: Boolean, val lastSeen: Long, val apps: List<Pair<String, String>> = emptyList(), val logging: Boolean = true)

    fun pullDevices(ctx0: Context, onResult: (List<DeviceEntry>?, String) -> Unit) {
        val ctx = ctx0.applicationContext
        Thread {
            try {
                val c = URL(url(ctx) + "/devices?pair=" + pair(ctx)).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode !in 200..299) { onResult(null, "فشل الطلب (${c.responseCode})"); return@Thread }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                cp(ctx).edit().putString("devices", txt).apply()
                val arr = JSONObject(txt).optJSONArray("devices") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    DeviceEntry(
                        d.optString("id"), d.optString("name", "جهاز"), d.optBoolean("blocking", true), d.optLong("lastSeen", 0L),
                        (d.optJSONArray("apps") ?: JSONArray()).let { ap -> (0 until ap.length()).map { j -> ap.getJSONObject(j).let { o -> o.optString("pkg") to o.optString("label") } } },
                        d.optBoolean("logging", true)
                    )
                }.sortedByDescending { it.lastSeen }
                onResult(list, if (list.isEmpty()) "ما سجّل أي جهاز بعد" else "")
            } catch (e: Exception) { onResult(null, "ما قدرت أتصل بالوسيط") }
        }.start()
    }

    fun pushDeviceBlocking(ctx0: Context, id: String, blocking: Boolean, onResult: (Boolean, String) -> Unit) {
        val ctx = ctx0.applicationContext
        if (!configured(ctx)) { onResult(false, "عبّي المفتاح السري أول"); return }
        Thread {
            try {
                val body = JSONObject().put("id", id).put("blocking", blocking)
                val c = URL(url(ctx) + "/devices?pair=" + pair(ctx)).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-admin-key", key(ctx))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                onResult(code in 200..299, if (code == 401) "المفتاح السري غلط" else if (code !in 200..299) "فشل ($code)" else "")
            } catch (e: Exception) { onResult(false, "ما قدرت أتصل بالوسيط") }
        }.start()
    }

    private fun postDevice(ctx0: Context, body: JSONObject, onResult: (Boolean, String) -> Unit) {
        val ctx = ctx0.applicationContext
        if (!configured(ctx)) { onResult(false, "عبّي المفتاح السري أول"); return }
        Thread {
            try {
                val c = URL(url(ctx) + "/devices?pair=" + pair(ctx)).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-admin-key", key(ctx))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                onResult(code in 200..299, if (code == 401) "المفتاح السري غلط" else if (code !in 200..299) "فشل ($code)" else "")
            } catch (e: Exception) { onResult(false, "ما قدرت أتصل بالوسيط") }
        }.start()
    }

    fun setDeviceLogging(ctx: Context, id: String, on: Boolean, onResult: (Boolean, String) -> Unit) =
        postDevice(ctx, JSONObject().put("id", id).put("logging", on), onResult)

    fun renameDevice(ctx: Context, id: String, name: String, onResult: (Boolean, String) -> Unit) =
        postDevice(ctx, JSONObject().put("id", id).put("name", name), onResult)

    fun removeDevice(ctx: Context, id: String, onResult: (Boolean, String) -> Unit) =
        postDevice(ctx, JSONObject().put("id", id).put("remove", true), onResult)

    // ───────── كاش محلي: يظهر فورًا عند فتح التطبيق ─────────
    private fun cp(ctx: Context) = ctx.applicationContext.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun cachedDevices(ctx: Context): List<DeviceEntry>? = cp(ctx).getString("devices", null)?.let {
        try {
            val arr = JSONObject(it).optJSONArray("devices") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                DeviceEntry(
                    d.optString("id"), d.optString("name", "جهاز"), d.optBoolean("blocking", true), d.optLong("lastSeen", 0L),
                    (d.optJSONArray("apps") ?: JSONArray()).let { ap -> (0 until ap.length()).map { j -> ap.getJSONObject(j).let { o -> o.optString("pkg") to o.optString("label") } } },
                    d.optBoolean("logging", true)
                )
            }.sortedByDescending { it.lastSeen }
        } catch (e: Exception) { null }
    }

    fun cachedActivity(ctx: Context): List<ActivityEntry>? = cp(ctx).getString("activity", null)?.let {
        try { parseEntries(it) } catch (e: Exception) { null }
    }

    private fun parseEntries(txt: String): List<ActivityEntry> {
        val arr = JSONObject(txt).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            ActivityEntry(e.optString("ts"), e.optString("type"), e.optString("kind"), e.optString("channel"), e.optString("label"), e.optString("deviceName"), e.optString("device"), e.optString("where"), e.optString("matched"), e.optString("thumb"))
        }
    }

    // ───────── الأرشيف الكامل (محفوظ على الوركر للأبد) ─────────
    private fun getText(ctx0: Context, path: String): String? {
        val ctx = ctx0.applicationContext
        return try {
            val c = URL(url(ctx) + path).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { null }
    }

    fun pullDays(ctx: Context, onResult: (List<String>?, String) -> Unit) {
        if (!configured(ctx)) { onResult(null, "عبّي المفتاح السري أول"); return }
        Thread {
            val txt = getText(ctx, "/activity?pair=" + pair(ctx) + "&days=1")
            if (txt == null) { onResult(null, "ما قدرت أتصل بالوسيط"); return@Thread }
            try {
                val arr = JSONObject(txt).optJSONArray("days") ?: JSONArray()
                onResult((0 until arr.length()).map { arr.getString(it) }, "")
            } catch (e: Exception) { onResult(null, "رد غير مفهوم") }
        }.start()
    }

    fun pullDay(ctx: Context, day: String, onResult: (List<ActivityEntry>?, String) -> Unit) {
        Thread {
            val txt = getText(ctx, "/activity?pair=" + pair(ctx) + "&day=" + day)
            if (txt == null) { onResult(null, "ما قدرت أتصل بالوسيط"); return@Thread }
            try { onResult(parseEntries(txt), "") } catch (e: Exception) { onResult(null, "رد غير مفهوم") }
        }.start()
    }

    // ───────── الصور المصغّرة للمقاطع المُشاهَدة ─────────
    private val thumbs = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val thumbLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun thumbCached(id: String): android.graphics.Bitmap? = thumbs[id]
    fun loadThumb(ctx0: Context, id: String, onLoaded: () -> Unit) {
        if (id.isEmpty() || thumbs.containsKey(id) || !thumbLoading.add(id)) return
        val ctx = ctx0.applicationContext
        Thread {
            try {
                val c = URL(url(ctx) + "/thumb?pair=" + pair(ctx) + "&id=" + id).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode in 200..299) {
                    val b = c.inputStream.use { it.readBytes() }
                    android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.let { thumbs[id] = it; onLoaded() }
                }
            } catch (e: Exception) { } finally { thumbLoading.remove(id) }
        }.start()
    }

    // ───────── أيقونات تطبيقات الأجهزة ─────────
    private val iconMaps = HashMap<String, Map<String, android.graphics.Bitmap>>()

    fun iconFor(device: String, pkg: String): android.graphics.Bitmap? = iconMaps[device]?.get(pkg)

    private fun decodeIcons(txt: String): Map<String, android.graphics.Bitmap> {
        val o = JSONObject(txt)
        val out = HashMap<String, android.graphics.Bitmap>()
        o.keys().forEach { k ->
            try {
                val b = android.util.Base64.decode(o.getString(k), android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.let { out[k] = it }
            } catch (e: Exception) { }
        }
        return out
    }

    fun loadIcons(ctx0: Context, device: String, onDone: () -> Unit) {
        val ctx = ctx0.applicationContext
        val safe = device.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safe.isEmpty()) return
        Thread {
            val f = java.io.File(ctx.filesDir, "icons_$safe.json")
            if (iconMaps[device] == null && f.exists()) {
                try { iconMaps[device] = decodeIcons(f.readText()); onDone() } catch (e: Exception) { }
            }
            val sp = cp(ctx)
            val last = sp.getLong("icons_ts_$safe", 0L)
            if (iconMaps[device] != null && System.currentTimeMillis() - last < 6L * 3600 * 1000) return@Thread
            val txt = if (configured(ctx)) getText(ctx, "/icons?pair=" + pair(ctx) + "&device=" + safe) else null
            if (txt != null) {
                try {
                    val m = decodeIcons(txt)
                    if (m.isNotEmpty()) {
                        f.writeText(txt)
                        iconMaps[device] = m
                        sp.edit().putLong("icons_ts_$safe", System.currentTimeMillis()).apply()
                        onDone()
                    }
                } catch (e: Exception) { }
            }
        }.start()
    }
}
