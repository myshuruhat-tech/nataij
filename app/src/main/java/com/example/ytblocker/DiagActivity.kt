package com.example.ytblocker

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/** شاشة تشخيص: حالة الصلاحيات + آخر 30 سطر من السجل + اختبار اتصال بالـ Worker. */
class DiagActivity : Activity() {

    private val devicesUrl =
        "https://nataij-api.myshuruhat.workers.dev/devices?pair=xeb3vuyM0FOa"

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.log(this, "DiagActivity opened")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        statusView = TextView(this).apply { textSize = 14f }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(btn("تحديث") { refresh() })
        row.addView(btn("اختبار اتصال") { testConnection() })
        row.addView(btn("مسح") { DiagLog.clear(this); refresh() })

        root.addView(statusView)
        root.addView(row)
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().contains(packageName)
        val notif = try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .areNotificationsEnabled()
        } catch (_: Throwable) { null }
        val battery = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
        } catch (_: Throwable) { null }

        statusView.text = "Accessibility مفعّل بالإعدادات: $enabled\n" +
            "الإشعارات مسموحة: $notif\n" +
            "مستثنى من تحسين البطارية: $battery"
        logView.text = DiagLog.tail(this, 30)
    }

    private fun testConnection() {
        DiagLog.log(this, "test: GET /devices")
        Thread {
            val result = try {
                val c = URL(devicesUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText().orEmpty().take(300)
                "test: HTTP $code $body"
            } catch (t: Throwable) {
                "test: فشل ${t.javaClass.simpleName}: ${t.message}"
            }
            DiagLog.log(this, result)
            runOnUiThread { refresh() }
        }.start()
    }
}
