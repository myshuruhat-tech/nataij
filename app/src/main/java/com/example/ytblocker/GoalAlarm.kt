package com.example.ytblocker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// متابعة فريق + إشعار عند هدف: فحص دوري كل ~20 دقيقة (بدون مكتبات خارجية، AlarmManager بس)
object GoalAlarm {
    private const val CHANNEL_ID = "goals"
    private const val REQUEST_CODE = 9001
    private const val INTERVAL_MS = 20 * 60 * 1000L

    fun followedTeams(ctx: Context): Set<String> =
        ctx.getSharedPreferences("follow", Context.MODE_PRIVATE).getStringSet("teams", emptySet()) ?: emptySet()

    fun isFollowed(ctx: Context, team: String) = followedTeams(ctx).contains(team)

    fun toggleFollow(ctx: Context, team: String): Boolean {
        val sp = ctx.getSharedPreferences("follow", Context.MODE_PRIVATE)
        val set = HashSet(sp.getStringSet("teams", emptySet()) ?: emptySet())
        val nowFollowing = if (set.contains(team)) { set.remove(team); false } else { set.add(team); true }
        sp.edit().putStringSet("teams", set).apply()
        if (set.isNotEmpty()) scheduleRepeating(ctx) else cancel(ctx)
        return nowFollowing
    }

    fun scheduleRepeating(ctx: Context) {
        ensureChannel(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx)
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000, INTERVAL_MS, pi)
        } catch (e: Exception) { }
    }

    private fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pendingIntent(ctx)) } catch (e: Exception) { }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, GoalCheckReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "أهداف الفرق المتابَعة", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    fun checkNow(ctx: Context) {
        val teams = followedTeams(ctx)
        if (teams.isEmpty()) return
        try {
            val tz = java.net.URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
            val c = URL("${ResultsActivity.WORKER_URL}/fixtures?date=$date&tz=$tz").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("matches") ?: return
            val sp = ctx.getSharedPreferences("follow_scores", Context.MODE_PRIVATE)
            val edit = sp.edit()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val home = m.optString("home"); val away = m.optString("away")
                val hs = if (m.isNull("hs")) -1 else m.optInt("hs")
                val as_ = if (m.isNull("as")) -1 else m.optInt("as")
                if (home in teams && hs >= 0) checkGoal(ctx, sp, edit, home, hs, away, as_.coerceAtLeast(0))
                if (away in teams && as_ >= 0) checkGoal(ctx, sp, edit, away, as_, home, hs.coerceAtLeast(0))
            }
            edit.apply()
        } catch (e: Exception) { }
    }

    private fun checkGoal(ctx: Context, sp: android.content.SharedPreferences, edit: android.content.SharedPreferences.Editor, team: String, score: Int, opp: String, oppScore: Int) {
        val key = "last_$team"
        val last = sp.getInt(key, -1)
        if (last in 0 until score) notify(ctx, "GOAL for $team!", "$team $score - $oppScore $opp")
        edit.putInt(key, score)
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.btn_star_big_on)
                .setAutoCancel(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.btn_star_big_on)
                .setAutoCancel(true).build()
        }
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}

class GoalCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Thread { GoalAlarm.checkNow(context) }.start()
    }
}
