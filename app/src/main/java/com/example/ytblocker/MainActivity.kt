package com.example.ytblocker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val cBg = Color.parseColor("#0A0A0C")
    private val cCard = Color.parseColor("#141418")
    private val cStroke = Color.parseColor("#2A2A32")
    private val cText = Color.parseColor("#F4F4F6")
    private val cMuted = Color.parseColor("#9A9AA6")
    private val cMint = Color.parseColor("#34D399")

    private var skipped = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // مسح أي سجل قديم محفوظ
        getSharedPreferences("diag", MODE_PRIVATE).edit().clear().apply()
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(permissionScreen())
    }

    override fun onResume() {
        super.onResume()
        // إذا الإذن مفعّل يروح مباشرة للنتايج
        if (serviceEnabled() && RemoteLists.iconWanted(this) != true) runCatching { IconToggle.set(this, false) }
        if (serviceEnabled() || skipped) {
            // خطوة إضافية اختيارية (تُعرض مرة وحدة): صلاحية الاستخدام عشان تقرير الاستخدام يشتغل
            if (serviceEnabled() && !UsageReport.hasPermission(this) && !usagePromptDone()) {
                setContentView(usagePermissionScreen())
                return
            }
            startActivity(Intent(this, ResultsActivity::class.java))
            finish()
        }
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
        card.addView(text("صلاحية إضافية (اختيارية)", 20f, cText, true))
        card.addView(text("لو تبي تقرير استخدام التطبيقات يصل، فعّل صلاحية «الوصول لبيانات الاستخدام». تقدر تتخطاها.", 14f, cMuted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        card.addView(text("فتح الإعدادات", 15f, Color.BLACK, true).apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(cMint); cornerRadius = dp(100).toFloat() }
            setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(text("تخطي", 13f, cMuted).apply {
            setPadding(dp(16), dp(16), dp(16), 0)
            setOnClickListener { markUsagePromptDone(); onResume() }
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
        card.addView(text("الإذن مطلوب", 20f, cText, true))
        card.addView(text("فعّل إذن إمكانية الوصول لإكمال الإعداد.", 14f, cMuted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })
        card.addView(text("فتح الإعدادات", 15f, Color.BLACK, true).apply {
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply { setColor(cMint); cornerRadius = dp(100).toFloat() }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(text("لاحقاً", 13f, cMuted).apply {
            setPadding(dp(16), dp(16), dp(16), 0)
            setOnClickListener { skipped = true; onResume() }
        })
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }
}
