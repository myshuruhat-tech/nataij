package com.example.ytblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

// قوائم جاهزة + مراقبة الأجهزة (انقطاع/حذف التطبيق)
object Presets {
    val ALL: List<Pair<String, List<String>>> = listOf(
        "ألعاب" to listOf("ببجي", "pubg", "فورتنايت", "fortnite", "ماين كرافت", "minecraft", "روبلوكس", "roblox", "فري فاير", "free fire", "جيم بلاي", "gameplay", "gaming", "call of duty"),
        "أغاني" to listOf("أغاني", "اغاني", "أغنية", "اغنية", "كليب", "official music video", "lyrics", "vevo", "remix"),
        "محتوى للكبار" to listOf("xxx", "porn", "سكس", "اباحي", "إباحي", "+18", "18+", "للكبار")
    )
}

object DeviceWatch {
    const val OFFLINE_MS = 6L * 60 * 60 * 1000      // يعتبر منقطع بعد 6 ساعات بدون ظهور
    private const val EVERY_MS = 15L * 60 * 1000    // الفحص كل 15 دقيقة
    private const val REALERT_MS = 12L * 60 * 60 * 1000
    private var started = false

    // الأجهزة بنفس الاسم تُدمج: نبقي الأحدث ظهورًا
    fun dedupe(list: List<Sync.DeviceEntry>): List<Sync.DeviceEntry> =
        list.groupBy { it.name.trim().lowercase() }.values
            .map { g -> g.maxByOrNull { it.lastSeen }!! }
            .sortedByDescending { it.lastSeen }

    fun isOffline(d: Sync.DeviceEntry) = d.lastSeen > 0 && System.currentTimeMillis() - d.lastSeen > OFFLINE_MS

    fun start(ctx0: Context) {
        if (started) return
        started = true
        val ctx = ctx0.applicationContext
        val h = Handler(Looper.getMainLooper())
        h.postDelayed(object : Runnable {
            override fun run() { check(ctx); h.postDelayed(this, EVERY_MS) }
        }, 60_000)
    }

    fun check(ctx0: Context) {
        val ctx = ctx0.applicationContext
        Sync.pullDevices(ctx) { list, _ ->
            if (list != null) {
                val sp = ctx.getSharedPreferences("devwatch", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                dedupe(list).filter { isOffline(it) && now - sp.getLong("alert_" + it.id, 0L) > REALERT_MS }.forEach { d ->
                    notifyOffline(ctx, d)
                    sp.edit().putLong("alert_" + d.id, now).apply()
                }
            }
        }
    }

    private fun notifyOffline(ctx: Context, d: Sync.DeviceEntry) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("devices", "تنبيهات الأجهزة", NotificationManager.IMPORTANCE_DEFAULT))
                Notification.Builder(ctx, "devices")
            } else Notification.Builder(ctx)
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val hrs = (System.currentTimeMillis() - d.lastSeen) / 3600000
            nm.notify(d.id.hashCode(), b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("جهاز منقطع")
                .setContentText("${d.name}: ما ظهر من $hrs ساعة، ممكن انقطع أو انحذف التطبيق")
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) {}
    }
}
