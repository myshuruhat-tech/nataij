package com.example.ytblocker

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// كل جهاز فيه Tozy يسجّل نفسه عند الوسيط، والأم تقدر تشغّل/توقف الحجب لكل جهاز لحاله
object DeviceSync {
    private const val URL_BASE = "https://nataij-api.myshuruhat.workers.dev"
    private const val PAIR = "xeb3vuyM0FOa"

    private fun p(ctx: Context) = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val cur = p(ctx).getString("id", null)
        if (cur != null) return cur
        val id = UUID.randomUUID().toString().replace("-", "")
        p(ctx).edit().putString("id", id).apply()
        return id
    }

    fun deviceName(): String {
        val model = Build.MODEL ?: "جهاز"
        return if (model.length in 1..40) model else "جهاز Tozy"
    }

    // آخر حالة حجب وصلت لهذا الجهاز بالذات (قبل أول نبض ناجح نفترض الحجب شغال)
    @Volatile private var lastBlocking = true
    fun isBlockingOnThisDevice() = lastBlocking

    private var lastBeat = 0L
    fun maybeHeartbeat(ctx0: Context, force: Boolean = false) {
        val ctx = ctx0.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - lastBeat < 300_000) return // كل 5 دقايق كحد أقصى
        lastBeat = now
        Thread {
            try {
                val body = JSONObject().put("id", deviceId(ctx)).put("name", deviceName())
                val c = URL("$URL_BASE/devices?pair=$PAIR").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode in 200..299) {
                    val txt = c.inputStream.bufferedReader().use { it.readText() }
                    lastBlocking = JSONObject(txt).optBoolean("blocking", true)
                }
            } catch (e: Exception) { }
        }.start()
    }
}
