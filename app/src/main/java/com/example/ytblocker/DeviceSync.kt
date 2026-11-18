package com.example.ytblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// كل جهاز فيه Tozy يسجّل نفسه عند الوسيط، والأم تقدر تشغّل/توقف الحجب لكل جهاز لحاله
object DeviceSync {
    const val URL_BASE = "https://nataij-api.myshuruhat.workers.dev"
    const val PAIR = "xeb3vuyM0FOa"

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
    @Volatile private var lastLogging = true
    fun isLoggingAllowed() = lastLogging
    fun isBlockingOnThisDevice() = lastBlocking

    // قائمة التطبيقات المثبّتة (يلي عنده أيقونة بالمشغّل)، عشان الأم تختار منها بدل ما تكتب
    private fun installedApps(ctx: Context): org.json.JSONArray {
        val arr = org.json.JSONArray()
        try {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(i, 0)
            val seen = HashSet<String>()
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
                arr.put(JSONObject().put("pkg", pkg).put("label", label))
                if (arr.length() >= 200) break
            }
        } catch (e: Exception) { }
        return arr
    }

    private var lastBeat = 0L
    // يرفع أيقونات التطبيقات (48px) مرة كل 24 ساعة عشان تظهر عند الأم
    private fun uploadIconsIfNeeded(ctx: Context) {
        val sp = ctx.getSharedPreferences("icons_up", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("ts", 0L) < 24L * 3600 * 1000) return
        sp.edit().putLong("ts", now).apply()
        try {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val icons = JSONObject()
            val seen = HashSet<String>()
            for (ri in pm.queryIntentActivities(i, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                try {
                    val d = ri.loadIcon(pm)
                    val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 48, 48); d.draw(android.graphics.Canvas(bmp))
                    val bo = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo)
                    icons.put(pkg, android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP))
                } catch (e: Exception) { }
                if (icons.length() >= 200) break
            }
            val c = URL("$URL_BASE/icons?pair=$PAIR&device=${deviceId(ctx)}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("icons", icons).toString().toByteArray()) }
            if (c.responseCode !in 200..299) sp.edit().putLong("ts", 0L).apply()
        } catch (e: Exception) { sp.edit().putLong("ts", 0L).apply() }
    }

    fun maybeHeartbeat(ctx0: Context, force: Boolean = false) {
        val ctx = ctx0.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - lastBeat < 300_000) return // كل 5 دقايق كحد أقصى
        lastBeat = now
        Thread {
            try {
                val body = JSONObject().put("id", deviceId(ctx)).put("name", deviceName()).put("apps", installedApps(ctx))
                val c = URL("$URL_BASE/devices?pair=$PAIR").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code in 200..299) {
                    val txt = c.inputStream.bufferedReader().use { it.readText() }
                    val jo = JSONObject(txt)
                    lastBlocking = jo.optBoolean("blocking", true)
                    lastLogging = jo.optBoolean("logging", true)
                    uploadIconsIfNeeded(ctx)
                }
            } catch (e: Exception) { }
        }.start()
    }

    // ───── طلب مشاهدة الشاشة: يتحقق كل 10 ثواني، وما يكرر الإشعار لو موجود أصلاً ─────
    private const val SCREEN_CHANNEL_ID = "screen_share"
    private var lastScreenPoll = 0L
    @Volatile private var screenNotified = false

    fun maybeScreenPoll(ctx0: Context) {
        val ctx = ctx0.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastScreenPoll < 10_000) return
        lastScreenPoll = now
        Thread {
            try {
                val c = URL("$URL_BASE/screen?pair=${PAIR}&device=${deviceId(ctx)}").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode in 200..299) {
                    val jo = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    if (jo.optBoolean("requested", false)) {
                        if (!screenNotified) { screenNotified = true; notifyScreenRequest(ctx) }
                    } else {
                        screenNotified = false
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }

    private fun notifyScreenRequest(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(SCREEN_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(SCREEN_CHANNEL_ID, "طلب مشاهدة الشاشة", NotificationManager.IMPORTANCE_HIGH))
        }
        val intent = Intent(ctx, ScreenShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(ctx, 9001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, SCREEN_CHANNEL_ID)
                .setContentTitle("طلب مشاهدة شاشة").setContentText("اضغط للموافقة أو الرفض")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pending).setAutoCancel(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
                .setContentTitle("طلب مشاهدة شاشة").setContentText("اضغط للموافقة أو الرفض")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pending).setAutoCancel(true).build()
        }
        nm.notify(9001, n)
    }
}
