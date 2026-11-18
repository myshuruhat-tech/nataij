package com.example.ytblocker

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// يرفع للوسيط ملخص دقائق الاستخدام لكل تطبيق لهذا اليوم، عشان شاشة أمي تشوفه.
// يحتاج صلاحية "الوصول لبيانات الاستخدام" (اختيارية، تُطلب من MainActivity)
object UsageReport {

    fun hasPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = try {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } catch (e: Exception) { AppOpsManager.MODE_IGNORED }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    // يرفع فورًا بخيط منفصل؛ ما يسوي شي لو الصلاحية مو ممنوحة
    fun push(ctx0: Context) {
        val ctx = ctx0.applicationContext
        if (!hasPermission(ctx)) return
        Thread {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val start = startOfToday()
                val end = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return@Thread
                val merged = stats
                    .filter { it.totalTimeInForeground > 0 && it.packageName != ctx.packageName }
                    .groupBy { it.packageName }
                    .map { (pkg, list) -> pkg to list.sumOf { it.totalTimeInForeground } }
                    .sortedByDescending { it.second }
                    .take(40)
                if (merged.isEmpty()) return@Thread
                val arr = JSONArray()
                merged.forEach { (pkg, ms) ->
                    arr.put(JSONObject().put("pkg", pkg).put("label", label(ctx, pkg)).put("minutes", ms / 60000))
                }
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val body = JSONObject().put("date", date).put("apps", arr)
                val c = URL("${RemoteLists.URL_BASE}/usage?pair=${RemoteLists.PAIR}&device=${DeviceSync.deviceId(ctx)}").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) { }
        }.start()
    }
}
