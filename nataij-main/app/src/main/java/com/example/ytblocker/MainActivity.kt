package com.example.ytblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val NOTIF_REQ = 4402

    private val cBg = Color.parseColor("#0A0A0C")
    private val cCard = Color.parseColor("#141418")
    private val cStroke = Color.parseColor("#2A2A32")
    private val cText = Color.parseColor("#F4F4F6")
    private val cMuted = Color.parseColor("#9A9AA6")
    private val cMint = Color.parseColor("#34D399")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusJob.schedule(this)
        getSharedPreferences("diag", MODE_PRIVATE).edit().clear().apply()
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(permissionScreen())
    }

    override fun onResume() {
        super.onResume()
        if (!serviceEnabled()) {
            // الإذن مو مفعّل — شاشة الطلب بدون أي زر تخطي
            setContentView(permissionScreen())
            return
        }
        // صلاحية الإشعارات (لازمة عشان يطلع إشعار "طلب مشاهدة شاشة") — مطلوبة وقت التشغيل من أندرويد 13
        if (!notifGranted()) {
            setContentView(notifPermissionScreen())
            return
        }
        // صلاحية الاستخدام (تُعرض مرة وحدة)
        if (!UsageReport.hasPermission(this) && !usagePromptDone()) {
            setContentView(usagePermissionScreen())
            return
        }
        // أندرويد 10 وما دون: إذن تسجيل الشاشة عشان الصور المصغّرة (يُطلب مرة لكل تشغيل)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ThumbProjection.active && !ThumbProjection.skipped) {
            setContentView(projectionScreen())
            return
        }
        startActivity(Intent(this, ResultsActivity::class.java))
        finish()
    }

    private fun notifGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_REQ) onResume() // نعيد الفحص سواء وافق أو رفض
    }

    private fun notifPermissionScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(26))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), cStroke)
            }
        }
        card.addView(text("Notifications permission required", 20f, cText, true))
        card.addView(text("Needed so screen-watch requests can reach this device.", 14f, cMuted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        card.addView(text("Allow", 15f, Color.BLACK, true).apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(cMint); cornerRadius = dp(100).toFloat() }
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun usagePrefs() = getSharedPreferences("remote", MODE_PRIVATE)
    private fun usagePromptDone() = usagePrefs().getBoolean("usage_prompt_done", false)
    private fun markUsagePromptDone() = usagePrefs().edit().putBoolean("usage_prompt_done", true).apply()

    private fun usagePermissionScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(26))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), cStroke)
            }
        }
        card.addView(text("Additional permission (optional)", 20f, cText, true))
        card.addView(text("To enable app usage reports, grant the \"Usage access\" permission. You can skip this.", 14f, cMuted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        card.addView(text("Open settings", 15f, Color.BLACK, true).apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(cMint); cornerRadius = dp(100).toFloat() }
            setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(text("Skip", 13f, cMuted).apply {
            setPadding(dp(16), dp(16), dp(16), 0)
            setOnClickListener { markUsagePromptDone(); onResume() }
        })
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private val PROJ_REQ = 4403

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJ_REQ) {
            ThumbProjection.skipped = true
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ThumbProjectionService::class.java).putExtra("resultCode", resultCode).putExtra("data", data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        }
    }

    private fun projectionScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(26))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), cStroke)
            }
        }
        card.addView(text("Screen thumbnails (optional)", 20f, cText, true))
        card.addView(text("On this Android version, thumbnails need the screen capture permission. Tap Allow and confirm the system prompt. It must be repeated after each restart.", 14f, cMuted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        card.addView(text("Allow", 15f, Color.BLACK, true).apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(cMint); cornerRadius = dp(100).toFloat() }
            setOnClickListener {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                startActivityForResult(pm.createScreenCaptureIntent(), PROJ_REQ)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(text("Skip", 13f, cMuted).apply {
            setPadding(dp(16), dp(16), dp(16), 0)
            setOnClickListener { ThumbProjection.skipped = true; onResume() }
        })
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val full = "$packageName/${BlockerService::class.java.name}"
        val short = "$packageName/.BlockerService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun permissionScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(26))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), cStroke)
            }
        }
        card.addView(text("Permission required", 20f, cText, true))
        card.addView(text("Enable the accessibility permission to finish setup.", 14f, cMuted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        card.addView(text("Open settings", 15f, Color.BLACK, true).apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(cMint); cornerRadius = dp(100).toFloat() }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }
}
