package com.example.ytblocker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    // ───────── الألوان ─────────
    private val cBg = Color.parseColor("#0A0A0C")
    private val cCard = Color.parseColor("#141418")
    private val cCardAlt = Color.parseColor("#1C1C22")
    private val cStroke = Color.parseColor("#2A2A32")
    private val cText = Color.parseColor("#F4F4F6")
    private val cMuted = Color.parseColor("#9A9AA6")
    private val cMint = Color.parseColor("#34D399")
    private val cBlue = Color.parseColor("#60A5FA")
    private val cAmber = Color.parseColor("#FBBF24")
    private val cRose = Color.parseColor("#FB7185")

    private data class Entry(val ts: String, val phone: String, val type: String, val kind: String, val channel: String, val label: String, val where: String = "", val matched: String = "", val thumb: String = "", val src: String = "")

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var filter = "الكل"
    private var shown = 100
    private var techOpen = false
    private var unlocked = false
    private val expanded = mutableSetOf<String>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "activitylog") runOnUiThread { rebuild(keepScroll = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("diag", Context.MODE_PRIVATE)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            isVerticalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
    }

    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() {
            if (unlocked) {
                refreshDevicesSilently()
                if (sel != null && tab != 1) { loadTozyActivity(rebuildAfter = true); loadSearchLog(rebuildAfter = true) }
            }
            refreshHandler.postDelayed(this, 8000)
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (!unlocked) {
            if (!Control.hasPin(this)) showSetPin(first = true) else showPinGate()
        } else {
            setContentView(scroll)
            if (devicesList == null) devicesList = Sync.cachedDevices(this)
            if (tozyActivity == null) tozyActivity = Sync.cachedActivity(this)
            rebuild(keepScroll = false)
            refreshDevicesSilently()
            if (usageDays == null) loadUsage(rebuildAfter = true)
            if (searchEntries == null) loadSearchLog(rebuildAfter = true)
            loadTozyActivity(rebuildAfter = true)
            DeviceWatch.start(applicationContext)
            DeviceWatch.check(this)
            refreshHandler.removeCallbacks(refreshLoop)
            refreshHandler.postDelayed(refreshLoop, 8000)
        }
    }

    // ───────── سجل نشاط جوال الولد (Tozy) ─────────
    private var tozyActivity: List<Sync.ActivityEntry>? = null
    private var tozyActivityLoading = false

    private fun loadTozyActivity(rebuildAfter: Boolean) {
        if (tozyActivityLoading) return
        tozyActivityLoading = true
        Sync.pullActivity(this) { entries, _ ->
            runOnUiThread {
                tozyActivityLoading = false
                val changed = entries != null && (entries.size != (tozyActivity?.size ?: -1) || entries.firstOrNull()?.ts != tozyActivity?.firstOrNull()?.ts)
                if (entries != null) tozyActivity = entries
                if (rebuildAfter && changed && !(sel != null && tab == 1)) rebuild(keepScroll = true)
            }
        }
    }

    // رابط أو كلمة بحث؟ تخمين بسيط: فيه نقطة وبدون مسافات = رابط
    private fun looksLikeLink(q: String): Boolean {
        val t = q.trim()
        return (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")) ||
            (!t.contains(" ") && t.contains(".") && t.length > 4)
    }

    // يدمج: السجل المحلي (هذا الجهاز) + نشاط جوال الولد (Tozy عبر المزامنة) + البحث/الروابط (Tozy)
    private fun combinedEntries(): List<Entry> {
        val d = sel
        val local = if (d == null) readEntries().map { it.copy(src = "loc") } else emptyList()
        val remote = (tozyActivity ?: emptyList()).filter { d == null || it.device.isEmpty() || it.device == d.id }.map { a ->
            Entry(a.ts, a.deviceName.ifBlank { "Tozy" }, a.type, a.kind, a.channel, a.label, a.where, a.matched, a.thumb, "act")
        }
        val searchAndLinks = (searchEntries ?: emptyList()).filter { d == null || it.device.isEmpty() || it.device == d.id }.map { s ->
            val type = if (looksLikeLink(s.query)) "رابط" else "بحث"
            val devicePhone = if (s.deviceName.isNotBlank()) "${s.deviceName} · ${s.label}" else s.label
            Entry(s.ts, devicePhone, type, "", s.app, s.query, src = "sl")
        }
        return (local + remote + searchAndLinks).sortedByDescending { it.ts }
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshLoop)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    // ───────── شاشة الرمز السري ─────────
    private fun gateCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(26), dp(22), dp(26))
        background = bg(cCard, 22f, cStroke)
    }

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(cMuted); setTextColor(cText)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        gravity = Gravity.CENTER
        textSize = 20f
        background = bg(cBg, 14f, cStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun gateButton(t: String, color: Int, onClick: () -> Unit) = tv(t, 15f, Color.BLACK, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = bg(color, 100f)
        setOnClickListener { onClick() }
    }

    private fun showSetPin(first: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val c = gateCard()
        c.addView(tv(if (first) "أهلاً، حددي رمز دخول لتطبيق 5al" else "رمز جديد", 18f, cText, true))
        c.addView(tv("رقم من 4 خانات يطلبه التطبيق كل ما تفتحينه", 13f, cMuted).apply { setPadding(0, dp(8), 0, dp(18)) })
        val p1 = pinField("الرمز (4 أرقام)"); c.addView(p1, lp(MATCH, WRAP))
        val p2 = pinField("تأكيد الرمز"); c.addView(p2, lp(MATCH, WRAP, 14))
        c.addView(gateButton("حفظ", cMint) {
            val a = p1.text.toString(); val b = p2.text.toString()
            when {
                a.length != 4 -> Toast.makeText(this, "الرمز لازم يكون 4 أرقام", Toast.LENGTH_SHORT).show()
                a != b -> Toast.makeText(this, "الرمزين غير متطابقين", Toast.LENGTH_SHORT).show()
                else -> {
                    Control.setPin(this, a)
                    Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                    unlocked = true; setContentView(scroll); rebuild(keepScroll = false)
                }
            }
        }, lp(MATCH, WRAP, 18))
        root.addView(c, lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun showPinGate() {
        val remaining = Control.lockRemainingSeconds(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val c = gateCard()
        c.addView(tv("5al", 18f, cText, true))
        if (remaining > 0) {
            c.addView(tv("محاولات كثيرة، حاولي بعد $remaining ثانية", 13f, cRose).apply { setPadding(0, dp(10), 0, dp(10)) })
            root.addView(c, lp(MATCH, WRAP))
            setContentView(root)
            c.postDelayed({ if (!unlocked) showPinGate() }, 1000)
            return
        }
        c.addView(tv("أدخلي الرمز السري", 13f, cMuted).apply { setPadding(0, dp(8), 0, dp(18)) })
        val p = pinField("الرمز"); c.addView(p, lp(MATCH, WRAP))
        c.addView(gateButton("دخول", cMint) {
            if (Control.checkPin(this, p.text.toString())) {
                Control.resetFails(this)
                unlocked = true; setContentView(scroll); rebuild(keepScroll = false)
            } else {
                val n = Control.registerFail(this)
                Toast.makeText(this, "رمز غير صحيح", Toast.LENGTH_SHORT).show()
                if (n >= 3) showPinGate() else p.text.clear()
            }
        }, lp(MATCH, WRAP, 18))
        root.addView(c, lp(MATCH, WRAP))
        setContentView(root)
    }

    // ───────── أدوات مساعدة ─────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density
    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun bg(fill: Int, radius: Float, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radius)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun pill(text: String, color: Int, filled: Boolean = false, size: Float = 12f): TextView =
        tv(text, size, if (filled) Color.BLACK else color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = bg(if (filled) color else alpha(color, 40), 100f)
        }

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun typeColor(t: String) = when (t) {
        "مشاهدة" -> cBlue
        "إخفاء" -> cAmber
        "حجب" -> cRose
        "كان مشترك" -> cAmber
        "ألغيت الاشتراك" -> cMint
        "تعذر الإلغاء" -> cRose
        "انتهى الفحص", "بدأ الفحص" -> cBlue
        else -> cMuted
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val full = "$packageName/${BlockerService::class.java.name}"
        val short = "$packageName/.BlockerService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun readEntries(): List<Entry> {
        val raw = prefs.getString("activitylog", "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }.map { line ->
            val p6 = line.split(" | ", limit = 6)
            if (p6.size == 6) {
                val type = when (p6[2]) { "تغطية" -> "إخفاء"; "رجوع" -> "حجب"; else -> p6[2] }
                Entry(p6[0], p6[1], type, p6[3], p6[4], p6[5])
            } else {
                // سطور قديمة بصيغة سابقة (4 حقول بدون نوع المحتوى والقناة)
                val p4 = line.split(" | ", limit = 4)
                if (p4.size == 4) {
                    val type = when (p4[2]) { "تغطية" -> "إخفاء"; "رجوع" -> "حجب"; else -> p4[2] }
                    Entry(p4[0], p4[1], type, "", "", p4[3])
                } else Entry("", "", "", "", "", line)
            }
        }
    }

    // ───────── بناء الشاشة ─────────
    // ───────── التنقل: الأجهزة ← صفحة الجهاز (نظرة عامة / الحجب / السجلات) ─────────
    private var sel: Sync.DeviceEntry? = null
    private var tab = 0
    private var advOpen = false

    private fun openDevice(d: Sync.DeviceEntry?) {
        sel = d; tab = 0
        Control.dev = d?.id ?: ""
        usageDays = null; usageLoading = false
        rebuild(keepScroll = false)
        scroll.post { scroll.scrollTo(0, 0) }
        if (d != null) loadUsage(rebuildAfter = true)
        if (d != null) Sync.loadIcons(this, d.id) { runOnUiThread { if (sel?.id == d.id && tab != 1) rebuild(keepScroll = true) } }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (sel != null) openDevice(null) else super.onBackPressed() }

    private fun rebuild(keepScroll: Boolean) {
        val y = scroll.scrollY
        content.removeAllViews()
        val d = sel
        Control.dev = d?.id ?: ""
        if (d == null) buildHome() else buildDevice(d)
        if (keepScroll) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun buildHome() {
        content.addView(header())
        content.addView(tv("أجهزة الأولاد", 20f, cText, true), lp(MATCH, WRAP, 22))
        content.addView(tv("اضغطي على أي جهاز لإدارته. كل جهاز له إعداداته وقوائمه المنفصلة.", 13f, cMuted).apply { setPadding(0, dp(3), 0, 0) })
        val list = devicesList
        if (list == null) {
            if (!devicesLoading) scroll.post { loadDevices(true) }
            content.addView(tv(if (devicesLoading) "جاري تحميل الأجهزة..." else devicesStatus, 13f, cMuted), lp(MATCH, WRAP, 16))
        } else if (list.isEmpty()) {
            content.addView(tv(devicesStatus, 13f, cMuted), lp(MATCH, WRAP, 16))
        } else {
            val shown = DeviceWatch.dedupe(list)
            val off = shown.filter { DeviceWatch.isOffline(it) }
            if (off.isNotEmpty()) content.addView(tv("⚠️ منقطع: " + off.joinToString("، ") { it.name }, 12.5f, cRose, true), lp(MATCH, WRAP, 12))
            shown.forEach { content.addView(deviceRow(it), lp(MATCH, WRAP, 12)) }
            if (list.size > shown.size) content.addView(tv("حذف ${list.size - shown.size} جهاز مكرر/قديم", 12.5f, cRose, true).apply {
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener { removeStaleDevices(list, shown) }
            }, lp(MATCH, WRAP, 6))
        }
        content.addView(pill(if (devicesLoading) "..." else "تحديث الأجهزة", cBlue).apply {
            setOnClickListener { if (!devicesLoading) loadDevices(true) }
        }, lp(WRAP, WRAP, 14))

        content.addView(tv(if (advOpen) "إعدادات متقدمة ▲" else "إعدادات متقدمة ▼", 13.5f, cMuted, true).apply {
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { advOpen = !advOpen; rebuild(keepScroll = true) }
        }, lp(MATCH, WRAP, 22))
        if (advOpen) {
            val controlOnly = Control.isControlOnly(this)
            content.addView(syncCard(), lp(MATCH, WRAP, 6))
            if (!controlOnly) {
                content.addView(statusCard(), lp(MATCH, WRAP, 14))
                content.addView(controlCard(), lp(MATCH, WRAP, 14))
                content.addView(techCard(), lp(MATCH, WRAP, 14))
            }
        }
    }

    private fun agoText(lastSeen: Long): String {
        val ago = (System.currentTimeMillis() - lastSeen) / 60000
        return when { lastSeen == 0L -> ""; ago < 2 -> "متصل الآن"; ago < 60 -> "آخر ظهور: قبل $ago د"; else -> "آخر ظهور: قبل ${ago / 60} س" }
    }

    private fun deviceSwitch(d: Sync.DeviceEntry) = Switch(this).apply {
        isChecked = d.blocking
        thumbTintList = null
        setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
            Sync.pushDeviceBlocking(this@MainActivity, d.id, v) { ok, msg ->
                runOnUiThread {
                    if (!ok) Toast.makeText(this@MainActivity, msg.ifEmpty { "فشل التحديث" }, Toast.LENGTH_LONG).show()
                    loadDevices(true)
                }
            }
        })
    }

    // ───────── قوائم جاهزة: تحجب فئة كاملة بضغطة وحدة ─────────
    private fun presetsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 18f, cStroke)
        }
        card.addView(tv("قوائم جاهزة", 15f, cText, true))
        card.addView(tv("شغّلي الفئة وتنحجب كلماتها كلها مرة وحدة", 12f, cMuted).apply { setPadding(0, dp(4), 0, dp(2)) })
        Presets.ALL.forEach { (title, kws) ->
            val names = Control.getNames(this)
            val on = kws.all { k -> names.any { it.equals(k, true) } }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(title, 13.5f, cText, true))
            col.addView(tv("${kws.size} كلمة", 11.5f, cMuted))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(Switch(this).apply {
                isChecked = on
                thumbTintList = null
                setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
                    val cur = Control.getNames(this@MainActivity)
                    if (v) Control.setNames(this@MainActivity, cur + kws.filter { k -> cur.none { it.equals(k, true) } })
                    else Control.setNames(this@MainActivity, cur.filterNot { n -> kws.any { it.equals(n, true) } })
                    card.post { rebuild(keepScroll = true) }
                })
            })
            card.addView(row, lp(MATCH, WRAP, 10))
        }
        return card
    }

    private fun renameDevice(d: Sync.DeviceEntry) {
        val et = EditText(this).apply { setText(d.name); setSelection(text.length); setPadding(dp(20), dp(14), dp(20), dp(14)) }
        AlertDialog.Builder(this).setTitle("اسم الجهاز").setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                Sync.renameDevice(this, d.id, et.text.toString().trim()) { ok, msg ->
                    runOnUiThread {
                        if (!ok) Toast.makeText(this, msg.ifEmpty { "فشل" }, Toast.LENGTH_LONG).show()
                        sel = null
                        rebuild(keepScroll = false)
                        loadDevices(true)
                    }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun removeStaleDevices(all: List<Sync.DeviceEntry>, shown: List<Sync.DeviceEntry>) {
        val keep = shown.map { it.id }.toSet()
        val stale = all.filter { it.id !in keep }
        var left = stale.size
        stale.forEach { d ->
            Sync.removeDevice(this, d.id) { _, _ ->
                runOnUiThread { left--; if (left == 0) loadDevices(true) }
            }
        }
    }

    private fun dangerBtn(): TextView = tv("🗑", 14f, cRose).apply {
        gravity = Gravity.CENTER
        minWidth = dp(34); minHeight = dp(34)
        background = bg(alpha(cRose, 36), 10f)
    }

    private var appQuery = ""

    private fun deviceAppsCard(d: Sync.DeviceEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        card.addView(tv("تطبيقات هذا الجهاز (${d.apps.size})", 15f, cText, true))
        if (d.apps.isEmpty()) {
            card.addView(tv("ما وصلت القائمة بعد. افتحي Tozy على جوال الولد مرة، وبعدها حدّثي الأجهزة", 12.5f, cMuted).apply { setPadding(0, dp(6), 0, 0) })
            return card
        }
        card.addView(tv("اختاري لكل تطبيق: مسموح، أو يُفحص محتواه، أو ممنوع بالكامل، أو «سجل» (يسجّل كل شي يشاهده ويبحث عنه بالنشاط بدون حجب)", 12f, cMuted).apply { setPadding(0, dp(3), 0, 0) })
        val et = EditText(this).apply {
            hint = "ابحثي باسم التطبيق"
            setText(appQuery)
            textSize = 14f
            setTextColor(cText); setHintTextColor(cMuted)
            setSingleLine(true)
            background = bg(cBg, 12f, cStroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(et, lp(MATCH, WRAP, 10))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        fun chip(text: String, on: Boolean, color: Int, click: () -> Unit) = tv(text, 12f, if (on) Color.BLACK else cMuted, true).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = bg(if (on) color else cCard, 100f, if (on) color else cStroke)
            setOnClickListener { click() }
        }
        fun render() {
            box.removeAllViews()
            val full = Control.getFullBlockApps(this@MainActivity, d.id)
            val scan = Control.getScanApps(this@MainActivity, d.id)
            val logOnly = Control.getLogApps(this@MainActivity, d.id)
            val q = appQuery.trim().lowercase()
            val shown = d.apps.filter { q.isEmpty() || it.second.lowercase().contains(q) || it.first.lowercase().contains(q) }
                .sortedWith(compareBy({ if (it.first in full) 0 else if (it.first in scan) 1 else if (it.first in logOnly) 2 else 3 }, { it.second.lowercase() }))
            if (shown.isEmpty()) box.addView(tv("ما لقيت تطبيق بهذا الاسم", 12.5f, cMuted), lp(MATCH, WRAP, 10))
            shown.take(80).forEach { (pkg, label) ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                Sync.iconFor(d.id, pkg)?.let { bmp -> row.addView(ImageView(this@MainActivity).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }) }
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(tv(label.ifEmpty { pkg }, 13.5f, cText, true))
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                // 0 مسموح · 1 يُفحص · 2 ممنوع · 3 سجل فقط (يسجّل كل شي بدون حجب)
                val st = if (pkg in full) 2 else if (pkg in scan) 1 else if (pkg in logOnly) 3 else 0
                fun setState(n: Int) {
                    val f0 = Control.getFullBlockApps(this@MainActivity, d.id); val s0 = Control.getScanApps(this@MainActivity, d.id)
                    val l0 = Control.getLogApps(this@MainActivity, d.id)
                    val f1 = if (n == 2) f0 + pkg else f0 - pkg
                    val s1 = if (n == 1) s0 + pkg else s0 - pkg
                    val l1 = if (n == 3) l0 + pkg else l0 - pkg
                    if (f1 != f0) Control.setFullBlockApps(this@MainActivity, f1)
                    if (s1 != s0) Control.setScanApps(this@MainActivity, s1)
                    if (l1 != l0) Control.setLogApps(this@MainActivity, l1)
                    render()
                }
                val seg = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; background = bg(cBg, 100f, cStroke) }
                listOf("مسموح" to cMuted, "يُفحص" to cBlue, "ممنوع" to cRose, "سجل" to cAmber).forEachIndexed { i, (label2, color) ->
                    seg.addView(tv(label2, 11.5f, if (st == i) Color.BLACK else cMuted, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(9), dp(7), dp(9), dp(7))
                        if (st == i) background = bg(if (i == 0) cMint else color, 100f)
                        setOnClickListener { setState(i) }
                    })
                }
                row.addView(seg)
                box.addView(row, lp(MATCH, WRAP, 10))
            }
            if (shown.size > 80) box.addView(tv("اكتبي في البحث لتضييق النتائج (${shown.size} تطبيق)", 12f, cMuted), lp(MATCH, WRAP, 10))
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { appQuery = s?.toString() ?: ""; render() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        render()
        return card
    }

    private fun deviceRow(d: Sync.DeviceEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
            setOnClickListener { openDevice(d) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(d.name + " ✏️", 17f, cText, true).apply { setOnClickListener { renameDevice(d) } })
        val off = DeviceWatch.isOffline(d)
        val st = listOf((if (off) "منقطع، " else "") + agoText(d.lastSeen), if (d.blocking) "الحجب شغّال" else "الحجب موقوف").filter { it.isNotEmpty() }.joinToString(" · ")
        col.addView(tv(st, 12f, if (off) cRose else cMuted).apply { setPadding(0, dp(2), 0, 0) })
        top.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(deviceSwitch(d))
        card.addView(top)
        val nFull = Control.getFullBlockApps(this, d.id).size
        val nScan = Control.getScanApps(this, d.id).size
        val nLog = Control.getLogApps(this, d.id).size
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(tv("$nFull ممنوعة · $nScan يُفحص محتواها" + (if (nLog > 0) " · $nLog سجل فقط" else ""), 12f, cMuted), LinearLayout.LayoutParams(0, WRAP, 1f))
        bottom.addView(tv("‹", 24f, cMuted, true))
        card.addView(bottom, lp(MATCH, WRAP, 10))
        return card
    }

    // ───────── حالة الجهاز: شغّال؟ إمكانية الوصول؟ اللقطات؟ وزر طلب التفعيل ─────────
    private val statusMap = HashMap<String, Sync.DeviceStatus?>()
    private val statusAt = HashMap<String, Long>()

    private fun maybeLoadStatus(d: Sync.DeviceEntry, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - (statusAt[d.id] ?: 0L) < 10_000) return
        statusAt[d.id] = now
        Sync.pullStatus(this, d.id) { s -> runOnUiThread { statusMap[d.id] = s; if (sel?.id == d.id) rebuild(keepScroll = true) } }
    }

    private fun deviceStatusCard(d: Sync.DeviceEntry): View {
        maybeLoadStatus(d)
        val s = statusMap[d.id]
        val fresh = s != null && System.currentTimeMillis() - s.at < 120_000
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = bg(cCard, 20f, cStroke)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("حالة الجهاز", 15f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(pill("تحديث", cBlue).apply { setOnClickListener { maybeLoadStatus(d, force = true) } })
        card.addView(head)

        fun row(name: String, state: String, color: Int, reqType: String? = null) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(3)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(name, 13.5f, cText, true))
            col.addView(tv(state, 12.5f, color, true))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (reqType != null) {
                val asked = s?.requests?.contains(reqType) == true
                r.addView(pill(if (asked) "تم الطلب ✓" else "اطلبي تفعيلها", if (asked) cMuted else cMint).apply {
                    if (!asked) setOnClickListener {
                        Sync.requestFeature(this@MainActivity, d.id, reqType) { ok ->
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, if (ok) "وصل الطلب لجهازه" else "تعذّر إرسال الطلب", Toast.LENGTH_SHORT).show()
                                if (ok) maybeLoadStatus(d, force = true)
                            }
                        }
                    }
                })
            }
            card.addView(r)
        }

        val off = if (fresh) false else DeviceWatch.isOffline(d)
        val whereTxt = if (!off && fresh && s!!.app.isNotBlank()) " · داخل ${s.app}" else ""
        row("الجهاز", if (off) "منقطع (${agoText(d.lastSeen).ifBlank { "بدون اتصال" }})" else "شغّال$whereTxt", if (off) cRose else cMint)
        if (s == null || !fresh) {
            card.addView(tv(if (s == null) "ما وصلت حالة الخصائص بعد (تظهر بعد تحديث Tozy على الجهاز)" else "آخر حالة قديمة، اضغطي تحديث", 12f, cMuted).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            row("إمكانية الوصول", if (s.acc) "شغّالة" else "مطفية", if (s.acc) cMint else cRose, if (s.acc) null else "acc")
            row("لقطات الشاشة", when { !s.acc -> "لا تعمل (إمكانية الوصول مطفية)"; s.shot == true -> "شغّالة"; s.shot == false -> "لا تعمل"; else -> "لم تُجرَّب بعد" },
                if (s.shot == true) cMint else if (s.shot == null && s.acc) cMuted else cRose, if (s.acc && s.shot != false) null else "shot")
            row("الإشعارات", if (s.notif) "شغّالة" else "مطفية", if (s.notif) cMint else cRose, if (s.notif) null else "notif")
            row("استثناء توفير البطارية", if (s.battery) "مفعّل" else "غير مفعّل (ممكن يوقف التطبيق)", if (s.battery) cMint else cRose, if (s.battery) null else "battery")
        }
        return card
    }

    private fun buildDevice(d: Sync.DeviceEntry) {
        content.addView(tv("‹ كل الأجهزة", 14f, cBlue, true).apply {
            setPadding(0, dp(4), dp(12), dp(10))
            setOnClickListener { openDevice(null) }
        })
        content.addView(tv(d.name + " ✏️", 24f, cText, true).apply { setOnClickListener { renameDevice(d) } })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = bg(cCard, 20f, cStroke)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("الحجب على هذا الجهاز", 15f, cText, true))
        col.addView(tv(agoText(d.lastSeen).ifEmpty { if (d.blocking) "شغّال" else "موقوف" }, 12f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(deviceSwitch(d))
        content.addView(card, lp(MATCH, WRAP, 14))

        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = bg(cCard, 20f, cStroke)
        }
        val logCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logCol.addView(tv("قراءة السجل من هذا الجهاز", 15f, cText, true))
        logCol.addView(tv(if (d.logging) "شغّالة: يُسجَّل نشاط الجهاز" else "موقوفة: ما يُسجَّل ولا يُرفع أي نشاط", 12f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        logCard.addView(logCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        logCard.addView(Switch(this).apply {
            isChecked = d.logging
            thumbTintList = null
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
                Sync.setDeviceLogging(this@MainActivity, d.id, v) { ok, msg ->
                    runOnUiThread {
                        if (!ok) Toast.makeText(this@MainActivity, msg.ifEmpty { "فشل التحديث" }, Toast.LENGTH_LONG).show()
                        loadDevices(true)
                        if (ok) { sel = null; rebuild(keepScroll = false) }
                    }
                }
            })
        })
        content.addView(logCard, lp(MATCH, WRAP, 10))

        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("اليوم", "الممنوعات", "السجل", "اللقطات").forEachIndexed { i, name ->
            seg.addView(tv(name, 12.5f, if (tab == i) Color.BLACK else cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = bg(if (tab == i) cMint else cCard, 100f, if (tab == i) cMint else cStroke)
                setOnClickListener { tab = i; rebuild(keepScroll = false) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        content.addView(seg, lp(MATCH, WRAP, 14))

        val entries = combinedEntries()
        when (tab) {
            0 -> {
                content.addView(pill("👁  شاهد شاشته الآن", cBlue).apply {
                    setOnClickListener { startActivity(Intent(this@MainActivity, ScreenViewActivity::class.java).putExtra("device", d.id)) }
                }, lp(WRAP, WRAP, 4))
                content.addView(deviceStatusCard(d), lp(MATCH, WRAP, 14))
                content.addView(statsRow(entries), lp(MATCH, WRAP, 14))
                content.addView(usageCard(), lp(MATCH, WRAP, 14))
                content.addView(tv("آخر النشاط", 15f, cText, true), lp(MATCH, WRAP, 22))
                if (entries.isEmpty()) content.addView(tv("ما في نشاط من هذا الجهاز بعد", 12.5f, cMuted), lp(MATCH, WRAP, 8))
                entries.take(5).forEach { content.addView(entryCard(it), lp(MATCH, WRAP, 10)) }
            }
            1 -> {
                content.addView(deviceAppsCard(d), lp(MATCH, WRAP, 14))
                content.addView(blockedListCard(), lp(MATCH, WRAP, 14))
                content.addView(presetsCard(), lp(MATCH, WRAP, 14))
                content.addView(warningCard(), lp(MATCH, WRAP, 14))
            }
            2 -> {
                content.addView(pill("الأرشيف الكامل (محفوظ للأبد)", cBlue).apply { setOnClickListener { showArchive() } }, lp(WRAP, WRAP, 4))
                content.addView(searchLogCard(), lp(MATCH, WRAP, 12))
                content.addView(logTitle(entries.size), lp(MATCH, WRAP, 26))
                content.addView(filters(), lp(MATCH, WRAP, 12))
                addLog(entries)
            }
            else -> shotsTab(entries)
        }
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.BLACK)
                setStroke(dp(1), cStroke)
            }
        }
        logoBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_fg)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(60), dp(60)))
        row.addView(logoBox, LinearLayout.LayoutParams(dp(60), dp(60)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("5al", 26f, cText, true))
        col.addView(tv("يخفي القنوات المحظورة من يوتيوب ويسجّل كل ما تشاهده", 13f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        return row
    }

    private fun statusCard(): View {
        val on = serviceEnabled()
        val accent = if (on) cMint else cRose
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(alpha(accent, 46), cCard)
            ).apply {
                cornerRadius = dpf(22f)
                setStroke(dp(1), alpha(accent, 110))
            }
        }
        card.addView(pill(if (on) "●  الحماية مفعّلة" else "●  الحماية متوقفة", accent), LinearLayout.LayoutParams(WRAP, WRAP))
        card.addView(
            tv(
                if (on) "5al يراقب يوتيوب الآن، ويخفي القنوات المحظورة ويسجّل ما تفتحه من فيديوهات."
                else "لتشغيل الحماية: افتح إعدادات إمكانية الوصول، اختر «5al» ثم شغّله.",
                14f, cText
            ).apply { setLineSpacing(dpf(3f), 1f) },
            lp(MATCH, WRAP, 12)
        )
        card.addView(
            tv(if (on) "إعدادات الخدمة" else "تشغيل الحماية", 15f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = bg(accent, 100f)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
            lp(MATCH, WRAP, 16)
        )
        return card
    }

    // ───────── التحكم: تشغيل/إيقاف الحجب + تغيير الرمز ─────────
    private fun controlCard(): View {
        val on = Control.isBlockingEnabled(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("تفعيل الحجب", 14.5f, cText, true))
        col.addView(tv("إيقافه يوقف إخفاء القنوات مؤقتاً بدون حذف القائمة", 12f, cMuted).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = on
            thumbTintList = null
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v -> Control.setBlockingEnabled(this@MainActivity, v) })
        })
        card.addView(row)
        card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 14))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col2.addView(tv("إلغاء الاشتراك تلقائياً", 14.5f, cText, true))
        col2.addView(tv("لما تنفتح قناة محظورة: يتحقق هل أنت مشترك، يسجّلها بالسجل، ويلغي الاشتراك", 12f, cMuted).apply { setPadding(0, dp(3), 0, 0) })
        row2.addView(col2, LinearLayout.LayoutParams(0, WRAP, 1f))
        row2.addView(Switch(this).apply {
            isChecked = Control.isUnsubEnabled(this@MainActivity)
            thumbTintList = null
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
                Control.setUnsubEnabled(this@MainActivity, v)
                if (v) Control.clearUnsubDone(this@MainActivity) // نعيد الفحص من الصفر
            })
        })
        card.addView(row2, lp(MATCH, WRAP, 14))
        card.addView(
            tv("فحص كل الاشتراكات وإلغاء المحظور", 13.5f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = bg(cAmber, 100f)
                setOnClickListener {
                    if (!serviceEnabled()) {
                        Toast.makeText(this@MainActivity, "فعّل خدمة الوصول أول", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    Control.setScanActive(this@MainActivity, true)
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/feed/channels"))
                    i.setPackage("com.google.android.youtube")
                    try { startActivity(i) } catch (e: Exception) {
                        Control.setScanActive(this@MainActivity, false)
                        Toast.makeText(this@MainActivity, "ما قدرت أفتح يوتيوب", Toast.LENGTH_LONG).show()
                    }
                }
            },
            lp(MATCH, WRAP, 14)
        )
        card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 14))
        card.addView(
            tv("تغيير الرمز السري", 13.5f, cBlue, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = bg(alpha(cBlue, 30), 100f)
                setOnClickListener { showSetPin(first = false) }
            },
            lp(MATCH, WRAP, 14)
        )
        return card
    }

    // ───────── مزامنة مع تطبيق Tozy ─────────
    private fun syncField(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(cText)
        setHintTextColor(cMuted)
        textSize = 13f
        setSingleLine()
        background = bg(cCardAlt, 12f, cStroke)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun syncCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        val coRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val coCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        coCol.addView(tv("وضع التحكم فقط", 14f, cText, true))
        coCol.addView(tv("مشغّل = هذا الجهاز يتحكم بالقوائم بدون ما يحجب عليه؛ مطفي = يحجب هنا أيضاً", 12f, cMuted).apply { setPadding(0, dp(3), 0, 0) })
        coRow.addView(coCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        coRow.addView(Switch(this).apply {
            isChecked = Control.isControlOnly(this@MainActivity)
            thumbTintList = null
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
                Control.setControlOnly(this@MainActivity, v)
                rebuild(keepScroll = true)
            })
        })
        card.addView(coRow, lp(MATCH, WRAP, 4))
        card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 12))
        card.addView(tv("المزامنة مع Tozy", 14.5f, cText, true))
        card.addView(tv("أي تغيير تسويه بالقوائم أو التفعيل يوصل تطبيق Tozy تلقائياً", 12f, cMuted).apply { setPadding(0, dp(3), 0, dp(10)) })
        val fUrl = syncField("رابط الوسيط", Sync.url(this))
        val fPair = syncField("كود الربط", Sync.pair(this))
        val fKey = syncField("المفتاح السري", Sync.key(this), secret = true)
        card.addView(fUrl, lp(MATCH, WRAP, 12))
        card.addView(fPair, lp(MATCH, WRAP, 8))
        card.addView(fKey, lp(MATCH, WRAP, 8))
        card.addView(tv(Sync.lastStatus(this), 11.5f, cMuted), lp(MATCH, WRAP, 8))
        card.addView(
            tv("حفظ ومزامنة الحين", 13.5f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = bg(cMint, 100f)
                setOnClickListener {
                    Sync.save(this@MainActivity, fUrl.text.toString(), fPair.text.toString(), fKey.text.toString())
                    Sync.push(this@MainActivity) { ok, msg ->
                        runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
                    }
                }
            },
            lp(MATCH, WRAP, 12)
        )
        return card
    }

    // ───────── القنوات والأسماء المحظورة ─────────
    private fun blockedListCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        card.addView(tv("القنوات والأسماء المحظورة", 15f, cText, true))

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(listBox, lp(MATCH, WRAP, 10))

        val items = Control.getHandles(this).map { "@" to it } +
            Control.getNames(this).map { "اسم" to it } +
            Control.getExact(this).map { "كلمة" to it }
        if (items.isEmpty()) {
            listBox.addView(tv("القائمة فاضية", 12.5f, cMuted))
        }
        items.forEach { (tagType, value) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(pill(tagType, cMuted, size = 10.5f))
            row.addView(tv(value, 13f, cText), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
            row.addView(dangerBtn().apply {
                setOnClickListener {
                    when (tagType) {
                        "@" -> Control.setHandles(this@MainActivity, Control.getHandles(this@MainActivity) - value)
                        "اسم" -> Control.setNames(this@MainActivity, Control.getNames(this@MainActivity) - value)
                        else -> Control.setExact(this@MainActivity, Control.getExact(this@MainActivity) - value)
                    }
                    rebuild(keepScroll = true)
                }
            })
            listBox.addView(row, lp(MATCH, WRAP, 8))
        }

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addField = EditText(this).apply {
            hint = "أضيفي معرّف @ أو اسم قناة"
            setHintTextColor(cMuted); setTextColor(cText)
            background = bg(cBg, 14f, cStroke)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 14f
        }
        addRow.addView(addField, LinearLayout.LayoutParams(0, WRAP, 1f))
        addRow.addView(
            tv("إضافة", 13.5f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = bg(cMint, 100f)
                setOnClickListener {
                    val v = addField.text.toString().trim()
                    if (v.isNotEmpty()) {
                        if (v.startsWith("@")) Control.setHandles(this@MainActivity, Control.getHandles(this@MainActivity) + v)
                        else Control.setNames(this@MainActivity, Control.getNames(this@MainActivity) + v)
                        rebuild(keepScroll = true)
                    }
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) }
        )
        card.addView(addRow, lp(MATCH, WRAP, 14))
        return card
    }

    // ───────── تطبيقات تُحجب بالكامل، وتطبيقات يُفحص بداخلها نص القوائم ─────────
    private fun resolveAppLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { Control.friendlyLabel(pkg) ?: "" }

    private fun appIdRow(pkg: String, onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = resolveAppLabel(pkg)
        if (label.isNotEmpty()) col.addView(tv(label, 13.5f, cText, true))
        col.addView(tv(pkg, 11.5f, cMuted))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(dangerBtn().apply {
            setOnClickListener { onRemove() }
        })
        return row
    }

    private fun appIdAddRow(hint: String, onAdd: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val field = EditText(this).apply {
            this.hint = hint
            setHintTextColor(cMuted); setTextColor(cText)
            background = bg(cBg, 14f, cStroke)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 13f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        row.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            tv("إضافة", 13.5f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = bg(cMint, 100f)
                setOnClickListener {
                    val v = field.text.toString().trim()
                    if (v.isEmpty()) return@setOnClickListener
                    // اكتبي اسم التطبيق (عربي أو إنجليزي)؛ لو ما لقيناه بالقاموس ونفس الوقت يشبه معرّف حزمة (فيه نقطة وبدون مسافات) نضيفه مباشرة
                    val known = Control.resolveAppByName(v)
                    when {
                        known != null && Control.isDangerousPackage(known.pkg) ->
                            Toast.makeText(this@MainActivity, "ما تقدرين تضيفين هذا — يخرب شاشة جوال الولد", Toast.LENGTH_LONG).show()
                        known != null -> {
                            onAdd(known.pkg)
                            Toast.makeText(this@MainActivity, "تمت إضافة ${known.label}", Toast.LENGTH_SHORT).show()
                            field.text.clear()
                        }
                        v.contains(".") && !v.contains(" ") && Control.isDangerousPackage(v) ->
                            Toast.makeText(this@MainActivity, "ما تقدرين تضيفين هذا المعرّف — يبدو أنه شاشة الهوم أو تطبيق نظام، وحجبه يخرب تنقّل الجهاز", Toast.LENGTH_LONG).show()
                        v.contains(".") && !v.contains(" ") -> { onAdd(v); field.text.clear() }
                        else -> {
                            // اسم غير معروف عندنا: نحفظه كما هو، وTozy يدوّر عليه بين التطبيقات المثبتة على جوال الولد
                            onAdd(v)
                            Toast.makeText(this@MainActivity, "تمت إضافة «$v». Tozy يدوّر عليه بالاسم في جوال الولد", Toast.LENGTH_LONG).show()
                            field.text.clear()
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) }
        )
        return row
    }

    private fun warningCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        card.addView(tv("رسالة تحذير عند الحجب", 15f, cText, true))
        card.addView(tv("تطلع له فوراً كل ما حجبنا فيديو أو تطبيق. اتركيها فاضية لو ما تبين أي رسالة.", 12f, cMuted).apply { setPadding(0, dp(3), 0, dp(10)) })
        val field = EditText(this).apply {
            setText(Control.getWarningMessage(this@MainActivity))
            hint = "مثلاً: إذا شفت هذا تنحذف الألعاب أسبوع كامل"
            setTextColor(cText)
            setHintTextColor(cMuted)
            textSize = 13f
            minLines = 2
            gravity = Gravity.TOP
            background = bg(cCardAlt, 12f, cStroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(field, lp(MATCH, WRAP, 4))
        card.addView(
            tv("حفظ ومزامنة", 13.5f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = bg(cMint, 100f)
                setOnClickListener {
                    Control.setWarningMessage(this@MainActivity, field.text.toString().trim())
                    Sync.push(this@MainActivity) { ok, msg ->
                        runOnUiThread { Toast.makeText(this@MainActivity, if (ok) "تمت المزامنة" else msg, Toast.LENGTH_LONG).show() }
                    }
                }
            },
            lp(MATCH, WRAP, 12)
        )
        return card
    }

    private fun appsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        card.addView(tv("تطبيقات تُحجب بالكامل", 15f, cText, true))
        card.addView(tv("أول ما تنفتح، ترجع للشاشة الرئيسية مباشرة", 12f, cMuted).apply { setPadding(0, dp(3), 0, dp(10)) })
        val fullBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(fullBox)
        val full = Control.getFullBlockApps(this)
        if (full.isEmpty()) fullBox.addView(tv("القائمة فاضية", 12.5f, cMuted))
        full.forEach { pkg ->
            fullBox.addView(appIdRow(pkg) {
                Control.setFullBlockApps(this@MainActivity, Control.getFullBlockApps(this@MainActivity) - pkg)
                rebuild(keepScroll = true)
            }, lp(MATCH, WRAP, 8))
        }
        card.addView(appIdAddRow("اكتبي اسم التطبيق (مثلاً: سناب شات)") { v ->
            Control.setFullBlockApps(this@MainActivity, Control.getFullBlockApps(this@MainActivity) + v)
            rebuild(keepScroll = true)
        }, lp(MATCH, WRAP, 12))

        card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 16))
        card.addView(tv("تطبيقات يُفحص محتواها", 15f, cText, true).apply { setPadding(0, dp(2), 0, 0) })
        card.addView(tv("يبحث داخلها عن نفس القنوات/الأسماء المحظورة أعلاه ويخفيها (زي يوتيوب)", 12f, cMuted).apply { setPadding(0, dp(3), 0, dp(10)) })
        val scanBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(scanBox)
        val scan = Control.getScanApps(this)
        if (scan.isEmpty()) scanBox.addView(tv("القائمة فاضية", 12.5f, cMuted))
        scan.forEach { pkg ->
            scanBox.addView(appIdRow(pkg) {
                Control.setScanApps(this@MainActivity, Control.getScanApps(this@MainActivity) - pkg)
                rebuild(keepScroll = true)
            }, lp(MATCH, WRAP, 8))
        }
        card.addView(appIdAddRow("اكتبي اسم التطبيق (مثلاً: سناب شات)") { v ->
            Control.setScanApps(this@MainActivity, Control.getScanApps(this@MainActivity) + v)
            rebuild(keepScroll = true)
        }, lp(MATCH, WRAP, 12))

        card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 16))
        card.addView(tv("تطبيقات يُسجَّل بحثها", 15f, cText, true).apply { setPadding(0, dp(2), 0, 0) })
        card.addView(tv("يسجّل كلمة البحث فقط من صندوق البحث داخل هذي التطبيقات (مو أي حقل نص ثاني بالتطبيق)", 12f, cMuted).apply { setPadding(0, dp(3), 0, dp(10)) })
        val searchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(searchBox)
        val search = Control.getSearchApps(this)
        if (search.isEmpty()) searchBox.addView(tv("القائمة فاضية", 12.5f, cMuted))
        search.forEach { pkg ->
            searchBox.addView(appIdRow(pkg) {
                Control.setSearchApps(this@MainActivity, Control.getSearchApps(this@MainActivity) - pkg)
                rebuild(keepScroll = true)
            }, lp(MATCH, WRAP, 8))
        }
        card.addView(appIdAddRow("اكتبي اسم التطبيق (مثلاً: سناب شات)") { v ->
            Control.setSearchApps(this@MainActivity, Control.getSearchApps(this@MainActivity) + v)
            rebuild(keepScroll = true)
        }, lp(MATCH, WRAP, 12))
        return card
    }

    // ───────── تقرير استخدام التطبيقات (يرفعه Tozy تلقائياً) ─────────
    private var usageDays: List<Sync.UsageDay>? = null
    private var usageStatus = "اضغطي تحديث لجلب آخر تقرير"
    private var usageLoading = false

    private var searchEntries: List<Sync.SearchEntry>? = null
    private var searchStatus = "اضغطي تحديث لجلب آخر سجل بحث"
    private var searchLoading = false

    private fun loadSearchLog(rebuildAfter: Boolean) {
        if (searchLoading) return
        searchLoading = true
        Sync.pullSearchLog(this) { entries, msg ->
            runOnUiThread {
                searchLoading = false
                val changed = entries != null && (entries.size != (searchEntries?.size ?: -1) || entries.firstOrNull()?.ts != searchEntries?.firstOrNull()?.ts)
                if (entries != null) searchEntries = entries
                searchStatus = msg
                if (rebuildAfter && changed && !(sel != null && tab == 1)) rebuild(keepScroll = true)
            }
        }
    }

    // ───────── سجل البحث (آخر كلمات البحث اللي رفعها Tozy من صناديق البحث المحدَّدة) ─────────
    private fun searchLogCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("سجل البحث", 15f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(pill(if (searchLoading) "..." else "تحديث", cBlue).apply {
            setOnClickListener { loadSearchLog(rebuildAfter = true) }
        })
        card.addView(head)
        card.addView(
            tv("آخر كلمات البحث والروابط داخل التطبيقات المحددة أعلاه فقط (يوتيوب، كروم، جوجل، أو أي تطبيق أضفتيه)", 12f, cMuted)
                .apply { setPadding(0, dp(4), 0, dp(10)) }
        )

        val entries = searchEntries?.filter { sel == null || it.device.isEmpty() || it.device == sel!!.id }
        if (entries == null) {
            card.addView(tv(searchStatus, 12.5f, cMuted))
        } else if (entries.isEmpty()) {
            card.addView(tv("ما في عمليات بحث مسجّلة بعد", 12.5f, cMuted))
        } else {
            entries.take(60).forEach { s ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(tv(s.query, 13.5f, cText).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                meta.addView(pill(s.label, cMuted, size = 10.5f))
                if (s.deviceName.isNotBlank()) {
                    meta.addView(pill(s.deviceName, cBlue, size = 10.5f), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
                }
                val time = if (s.ts.length >= 16) s.ts.substring(11, 16) else s.ts
                meta.addView(tv(time, 11f, cMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                col.addView(meta, lp(MATCH, WRAP, 4))
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                card.addView(row, lp(MATCH, WRAP, 12))
                card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 10))
            }
        }
        return card
    }

    private fun loadUsage(rebuildAfter: Boolean) {
        if (usageLoading) return
        usageLoading = true
        Sync.pullUsage(this, sel?.id ?: "") { days, msg ->
            runOnUiThread {
                usageLoading = false
                usageDays = days
                usageStatus = msg
                if (rebuildAfter) rebuild(keepScroll = true)
            }
        }
    }

    // ───────── أجهزة Tozy المسجّلة: تشغيل/إيقاف الحجب لكل جهاز لحاله ─────────
    private var devicesList: List<Sync.DeviceEntry>? = null
    private var devicesStatus = "اضغط تحديث لعرض الأجهزة المسجّلة"
    private var devicesLoading = false

    private fun showArchive() {
        Toast.makeText(this, "جاري تحميل الأرشيف...", Toast.LENGTH_SHORT).show()
        Sync.pullDays(this) { days, msg ->
            runOnUiThread {
                if (days == null || days.isEmpty()) {
                    Toast.makeText(this, msg.ifEmpty { "ما في أرشيف بعد" }, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setTitle("أيام الأرشيف (${days.size})")
                        .setItems(days.toTypedArray()) { _, i -> showArchiveDay(days[i]) }
                        .setNegativeButton("إغلاق", null).show()
                }
            }
        }
    }

    private fun showArchiveDay(day: String) {
        Sync.pullDay(this, day) { list, msg ->
            runOnUiThread {
                val devId = sel?.id
                val items = (list ?: emptyList()).filter { devId == null || it.device.isEmpty() || it.device == devId }
                val lines = items.map { (if (it.ts.length >= 16) it.ts.substring(11, 16) else "") + "   " + (if (it.type == "تطبيق") "📱 " else "") + it.label }
                val body = TextView(this).apply {
                    text = if (lines.isEmpty()) msg.ifEmpty { "ما في نشاط" } else lines.joinToString("\n\n")
                    textSize = 13f
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    setTextIsSelectable(true)
                }
                AlertDialog.Builder(this).setTitle("$day (${items.size})")
                    .setView(ScrollView(this).apply { addView(body) })
                    .setPositiveButton("إغلاق", null).show()
            }
        }
    }

    private fun devSig(l: List<Sync.DeviceEntry>?) = l?.joinToString("|") { "${it.id}${it.name}${it.lastSeen / 60000}${it.blocking}${it.logging}${it.apps.size}" } ?: ""

    private fun refreshDevicesSilently() {
        if (devicesLoading) return
        Sync.pullDevices(this) { list, msg ->
            runOnUiThread {
                val changed = list != null && devSig(list) != devSig(devicesList)
                if (list != null) devicesList = list
                devicesStatus = msg
                sel = sel?.let { cur -> list?.firstOrNull { it.id == cur.id } ?: cur }
                if (changed && unlocked && !(sel != null && tab == 1)) rebuild(keepScroll = true)
            }
        }
    }

    private fun loadDevices(rebuildAfter: Boolean) {
        devicesLoading = true
        if (rebuildAfter) rebuild(keepScroll = true)
        Sync.pullDevices(this) { list, msg ->
            runOnUiThread {
                devicesLoading = false
                devicesList = list ?: devicesList
                devicesStatus = msg
                sel = sel?.let { cur -> list?.firstOrNull { it.id == cur.id } ?: cur }
                if (rebuildAfter) rebuild(keepScroll = true)
            }
        }
    }

    private fun devicesCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("أجهزة Tozy", 14.5f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(
            tv(if (devicesLoading) "..." else "تحديث", 12.5f, Color.BLACK, true).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = bg(cMint, 100f)
                setOnClickListener { if (!devicesLoading) loadDevices(true) }
            }
        )
        card.addView(head, lp(MATCH, WRAP))
        card.addView(tv("شغّلي/أوقفي الحجب لكل جهاز لحاله، بدون ما تحتاجين تفتحي خدمة إمكانية الوصول", 12f, cMuted).apply { setPadding(0, dp(4), 0, dp(10)) })

        val list = devicesList
        if (list == null || list.isEmpty()) {
            card.addView(tv(devicesStatus, 12.5f, cMuted), lp(MATCH, WRAP))
        } else {
            list.forEachIndexed { idx, d ->
                if (idx > 0) card.addView(View(this).apply { setBackgroundColor(cStroke) }, lp(MATCH, 1, 10))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(tv(d.name, 13.5f, cText, true))
                val ago = (System.currentTimeMillis() - d.lastSeen) / 60000
                val agoText = when { d.lastSeen == 0L -> ""; ago < 2 -> "آخر ظهور: الحين"; ago < 60 -> "آخر ظهور: قبل $ago د"; else -> "آخر ظهور: قبل ${ago / 60} س" }
                if (agoText.isNotEmpty()) col.addView(tv(agoText, 11.5f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(Switch(this).apply {
                    isChecked = d.blocking
                    thumbTintList = null
                    setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
                        Sync.pushDeviceBlocking(this@MainActivity, d.id, v) { ok, msg ->
                            runOnUiThread {
                                if (!ok) Toast.makeText(this@MainActivity, msg.ifEmpty { "فشل التحديث" }, Toast.LENGTH_LONG).show()
                                loadDevices(true)
                            }
                        }
                    })
                })
                card.addView(row, lp(MATCH, WRAP, if (idx == 0) 0 else 2))
            }
        }
        return card
    }

    private fun usageCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bg(cCard, 20f, cStroke)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("تقرير استخدام الجوال", 15f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(pill(if (usageLoading) "..." else "تحديث", cBlue).apply {
            setOnClickListener { loadUsage(rebuildAfter = true) }
        })
        card.addView(head)
        card.addView(tv("كم دقيقة قضى بكل تطبيق، يوم بيوم (يحتاج Tozy يكون مفعّل وصلاحية الاستخدام موافق عليها)", 12f, cMuted).apply { setPadding(0, dp(4), 0, dp(10)) })

        val days = usageDays
        if (days == null) {
            card.addView(tv(usageStatus, 12.5f, cMuted))
        } else if (days.isEmpty()) {
            card.addView(tv("ما وصل تقرير استخدام بعد", 12.5f, cMuted))
        } else {
            days.take(7).forEachIndexed { i, d ->
                card.addView(tv(d.date, 12.5f, cMuted, true), lp(MATCH, WRAP, if (i == 0) 2 else 14))
                if (d.apps.isEmpty()) {
                    card.addView(tv("لا يوجد استخدام مسجّل", 12f, cMuted), lp(MATCH, WRAP, 4))
                } else {
                    d.apps.take(8).forEach { (_, label, minutes) ->
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        row.addView(tv(label, 13f, cText), LinearLayout.LayoutParams(0, WRAP, 1f))
                        row.addView(tv("$minutes د", 12.5f, cBlue, true))
                        card.addView(row, lp(MATCH, WRAP, 6))
                    }
                }
            }
        }
        return card
    }

    private fun todayPrefix(daysAgo: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private fun statsRow(entries: List<Entry>): View {
        val today = todayPrefix()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf("مشاهدة" to "مشاهدات اليوم", "إخفاء" to "بطاقات مخفية", "حجب" to "فيديوهات محجوبة")
        items.forEachIndexed { i, (type, title) ->
            val n = entries.count { it.type == type && it.ts.startsWith(today) }
            val c = typeColor(type)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(14), dp(8), dp(14))
                background = bg(alpha(c, 28), 18f, alpha(c, 70))
            }
            box.addView(tv(n.toString(), 24f, c, true).apply { gravity = Gravity.CENTER })
            box.addView(tv(title, 11f, cMuted).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            row.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        return row
    }

    private fun logTitle(total: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(tv("سجل النشاط", 18f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(pill("$total", cMuted))
        if (total > 0) {
            row.addView(pill("مسح", cRose).apply {
                setOnClickListener { confirmClear() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        return row
    }

    // نوع المحتوى الفعلي للعنصر (القديم بدون نوع محفوظ يُحسب فيديو)
    private fun effectiveKind(e: Entry) = if (e.kind == "شورت") "شورت" else "فيديو"

    private fun filters(): View {
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("الكل", "فيديوهات", "شورتس", "المحجوبة", "البحث", "الروابط", "التطبيقات").forEachIndexed { i, name ->
            val selected = filter == name
            val c = when (name) {
                "الكل" -> cText
                "شورتس" -> cRose
                "المحجوبة" -> cAmber
                "البحث" -> Color.parseColor("#C084FC")
                "الروابط" -> cMint
                "التطبيقات" -> Color.parseColor("#A78BFA")
                else -> cBlue
            }
            val chip = tv(name, 13f, if (selected) Color.BLACK else cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = if (selected) bg(c, 100f) else bg(cCardAlt, 100f, cStroke)
                setOnClickListener { filter = name; shown = 100; rebuild(keepScroll = true) }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) })
        }
        hs.addView(row)
        return hs
    }

    private fun addLog(all: List<Entry>) {
        val list = when (filter) {
            "الكل" -> all
            "المحجوبة" -> all.filter { it.type == "حجب" }
            "البحث" -> all.filter { it.type == "بحث" }
            "الروابط" -> all.filter { it.type == "رابط" }
            "التطبيقات" -> all.filter { it.type == "تطبيق" }
            "فيديوهات", "شورتس" -> all.filter { it.type != "بحث" && it.type != "رابط" && it.type != "تطبيق" && effectiveKind(it) == (if (filter == "شورتس") "شورت" else "فيديو") }
            else -> all
        }
        if (list.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(34), dp(20), dp(34))
                background = bg(cCard, 20f, cStroke)
            }
            empty.addView(tv("لا يوجد نشاط بعد", 16f, cText, true).apply { gravity = Gravity.CENTER })
            empty.addView(
                tv("افتح يوتيوب وشاهد أي فيديو، وسيظهر هنا فورًا.", 13f, cMuted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                }
            )
            content.addView(empty, lp(MATCH, WRAP, 14))
            return
        }
        val today = todayPrefix()
        val yesterday = todayPrefix(1)
        var lastDay = ""
        list.take(shown).forEach { e ->
            val day = if (e.ts.length >= 10) e.ts.substring(0, 10) else ""
            if (day != lastDay) {
                lastDay = day
                val name = when (day) { today -> "اليوم"; yesterday -> "أمس"; "" -> "—"; else -> day }
                content.addView(tv(name, 12f, cMuted, true), lp(MATCH, WRAP, 18))
            }
            content.addView(entryCard(e), lp(MATCH, WRAP, 8))
        }
        if (list.size > shown) {
            content.addView(
                tv("عرض المزيد (${list.size - shown})", 14f, cText, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = bg(cCardAlt, 100f, cStroke)
                    setOnClickListener { shown += 100; rebuild(keepScroll = true) }
                },
                lp(MATCH, WRAP, 16)
            )
        }
    }

    // عنوان بسيط بدل المصطلحات التقنية
    private fun simpleStatus(e: Entry): Pair<String, Int> = when (e.type) {
        "مشاهدة" -> "تمت مشاهدته" to cBlue
        "فحص" -> "تطبيق يُفحص" to cBlue
        "سجل" -> "سجل فقط" to cAmber
        "إخفاء" -> "مخفي تلقائياً" to cAmber
        "حجب" -> "تم إغلاقه (محظور)" to cRose
        "كان مشترك" -> "كنت مشترك بالقناة" to cAmber
        "ألغيت الاشتراك" -> "تم إلغاء الاشتراك" to cMint
        "غير مشترك" -> "ما كنت مشترك" to cMuted
        "تعذر الإلغاء" -> "تعذّر إلغاء الاشتراك" to cRose
        "تعذر التحقق" -> "ما قدرنا نتحقق من الاشتراك" to cMuted
        "بدأ الفحص" -> "بدأ فحص الاشتراكات" to cBlue
        "انتهى الفحص" -> "انتهى فحص الاشتراكات" to cBlue
        "بحث" -> "كلمة بحث" to Color.parseColor("#C084FC")
        "رابط" -> "رابط مفتوح" to cMint
        "تطبيق" -> "تطبيق مفتوح" to Color.parseColor("#A78BFA")
        else -> (e.type.ifBlank { "—" }) to cMuted
    }

    // ───────── جمل السجل: وش صار بالضبط بكلام مفهوم ─────────
    private fun appNameOf(pkg: String): String =
        (sel?.apps?.firstOrNull { it.first == pkg }?.second ?: "").ifBlank { if (pkg == "com.google.android.youtube") "يوتيوب" else pkg.substringAfterLast('.') }

    private fun humanText(e: Entry): Pair<String, String> {
        val name = e.matched.trim()
        val nameTxt = if (name.isNotEmpty()) "الاسم الممنوع: «$name»" else ""
        return when (e.type) {
            "مشاهدة" -> {
                val p = e.label.split(" — ", limit = 2)
                "شاهد فيديو «${p[0]}»" to (p.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { "قناة «$it» · في يوتيوب" } ?: "في يوتيوب")
            }
            "حجب" -> "حاول يفتح فيديو من قناة ممنوعة" to listOf(nameTxt.ifEmpty { "«${e.label}»" }, "انحجب وخرج منه").joinToString(" · ")
            "إخفاء" -> {
                val inSuggest = e.where.contains("اقتراحات")
                (if (inSuggest) "ظهر في اقتراحات البحث اسم ممنوع وانخفى عنه" else "ظهر له فيديو ممنوع وانخفى") to
                    listOf(e.where, nameTxt, if (nameTxt.isEmpty()) "«${e.label}»" else "").filter { it.isNotBlank() }.joinToString(" · ")
            }
            "بحث ممنوع" -> "كتب كلمة ممنوعة في البحث: «${e.label}»" to listOf(nameTxt, "في ${appNameOf(e.channel)}").filter { it.isNotBlank() }.joinToString(" · ")
            "بحث" -> "كتب في البحث: «${e.label}»" to "في ${appNameOf(e.channel)}"
            "رابط" -> "فتح رابطًا: «${e.label}»" to "في ${appNameOf(e.channel)}"
            "تطبيق" -> "فتح تطبيقًا ممنوعًا: ${e.label}" to "خرج منه مباشرة"
            "فحص" -> "يستخدم ${e.label}" to "تطبيق يُفحص"
            "سجل" -> "يستخدم ${e.label}" to "سجل فقط (بدون حجب)"
            else -> e.label.ifBlank { "—" } to ""
        }
    }

    // ───────── تبويب اللقطات: كل صور الشاشة اللي رفعها الجهاز (من نشاطه) ─────────
    private var shotsShown = 40
    private var shotsRebuildPending = false

    // كل صورة تنزل كانت تعيد بناء الشاشة كلها (20 صورة = 20 وميض)؛ الحين نجمّعها بإعادة بناء وحدة
    private var thumbsRebuildPending = false
    private fun thumbsRebuildLater() {
        if (thumbsRebuildPending) return
        thumbsRebuildPending = true
        refreshHandler.postDelayed({
            thumbsRebuildPending = false
            if (sel != null && tab != 1 && tab != 3) rebuild(keepScroll = true)
        }, 600)
    }

    private fun shotsRebuildLater() {
        if (shotsRebuildPending) return
        shotsRebuildPending = true
        refreshHandler.postDelayed({
            shotsRebuildPending = false
            if (sel != null && tab == 3) rebuild(keepScroll = true)
        }, 400)
    }

    private fun shotsTab(entries: List<Entry>) {
        val shots = entries.filter { it.thumb.isNotEmpty() }
        content.addView(tv("لقطات من شاشة هذا الجهاز", 15f, cText, true), lp(MATCH, WRAP, 4))
        content.addView(tv("تُلتقط لحظة مشاهدة أو حجب أو بحث ممنوع.", 12f, cMuted), lp(MATCH, WRAP, 4))
        if (shots.isEmpty()) {
            content.addView(tv("ما في لقطات من هذا الجهاز بعد", 12.5f, cMuted), lp(MATCH, WRAP, 12))
            return
        }
        shots.take(shotsShown).chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, e ->
                row.addView(shotTile(e), LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(10) })
            content.addView(row, lp(MATCH, WRAP, 10))
        }
        if (shots.size > shotsShown) {
            content.addView(pill("عرض المزيد", cBlue).apply { setOnClickListener { shotsShown += 40; rebuild(keepScroll = true) } }, lp(WRAP, WRAP, 14))
        }
    }

    private fun shotTile(e: Entry): View {
        val bmp = Sync.thumbCached(e.thumb)
        val tileIv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = false }
        // لما تنزل الصورة نحدّث هذي الخانة فقط (بدل إعادة بناء التبويب كله، وهذا اللي كان يخلي اللقطات تومض وتتلخبط)
        if (bmp == null) Sync.loadThumb(this, e.thumb) { runOnUiThread { Sync.thumbCached(e.thumb)?.let { tileIv.setImageBitmap(it) } } }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            background = bg(cCard, 16f, cStroke)
        }
        val box = FrameLayout(this).apply { background = bg(cCardAlt, 12f) }
        if (bmp != null) tileIv.setImageBitmap(bmp)
        box.addView(tileIv, FrameLayout.LayoutParams(MATCH, MATCH))
        if (bmp == null) box.addView(tv("…", 18f, cMuted).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(trashBtn(e).apply { background = bg(Color.argb(160, 0, 0, 0), 100f) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        // الخانة أطول للقطات الطولية (شورتس/جوال) عشان ما تنقص أو تنضغط
        val portrait = bmp != null && bmp.height > bmp.width
        tile.addView(box, LinearLayout.LayoutParams(MATCH, dp(if (portrait) 230 else 150)))
        val (mainTxt, _) = humanText(e)
        tile.addView(tv(mainTxt, 12f, cText, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        val time = if (e.ts.length >= 16) e.ts.substring(5, 16).replace('T', ' ') else ""
        tile.addView(tv(time, 11f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        tile.setOnClickListener { Sync.thumbCached(e.thumb)?.let { b -> showShot(e, b) } }
        return tile
    }

    private fun showShot(e: Entry, bmp: android.graphics.Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val (mainTxt, detail) = humanText(e)
        AlertDialog.Builder(this).setTitle(mainTxt).setMessage(detail).setView(iv).setPositiveButton("إغلاق", null).show()
    }

    private fun entryKey(e: Entry) = "${e.ts}|${e.label}"

    // ───────── حذف عنصر من السجل (نشاط / بحث / رابط / لقطة) ─────────
    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("حذف هذا العنصر؟")
            .setMessage("يُحذف من السجل (واللقطة اللي معه إن وجدت).")
            .setPositiveButton("حذف") { _, _ -> deleteEntry(e) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun deleteEntry(e: Entry) {
        when (e.src) {
            "act" -> {
                tozyActivity = tozyActivity?.filterNot { it.ts == e.ts && it.label == e.label && it.type == e.type }
                Sync.deleteEntry(this, false, e.ts) { ok ->
                    if (!ok) runOnUiThread { Toast.makeText(this, "ما انحذف من الوسيط، جرّبي مرة ثانية", Toast.LENGTH_SHORT).show(); loadTozyActivity(rebuildAfter = true) }
                }
            }
            "sl" -> {
                searchEntries = searchEntries?.filterNot { it.ts == e.ts && it.query == e.label }
                Sync.deleteEntry(this, true, e.ts) { ok ->
                    if (!ok) runOnUiThread { Toast.makeText(this, "ما انحذف من الوسيط، جرّبي مرة ثانية", Toast.LENGTH_SHORT).show(); loadSearchLog(rebuildAfter = true) }
                }
            }
            else -> {
                val raw = prefs.getString("activitylog", "") ?: ""
                val lines = raw.split("\n").filter { it.isNotBlank() }
                val parsed = readEntries()
                val kept = lines.filterIndexed { i, _ -> !(parsed.getOrNull(i)?.let { it.ts == e.ts && it.label == e.label } ?: false) }
                prefs.edit().putString("activitylog", kept.joinToString("\n")).apply()
            }
        }
        expanded.remove(entryKey(e))
        rebuild(keepScroll = true)
    }

    private fun trashBtn(e: Entry): View = tv("🗑", 15f, cRose, true).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { confirmDelete(e) }
    }

    // يفتح الرابط المسجّل (يضيف https:// لو ناقص)
    private fun openLink(raw: String) {
        val t = raw.trim()
        val u = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
        catch (x: Exception) { Toast.makeText(this, "ما قدرت أفتح الرابط", Toast.LENGTH_SHORT).show() }
    }

    private fun isTargetBlocked(target: String): Boolean {
        val t = target.trim()
        if (t.isEmpty()) return false
        return Control.getHandles(this).any { it.equals(t, true) } ||
            Control.getNames(this).any { it.equals(t, true) } ||
            Control.getExact(this).any { it.equals(t, true) }
    }

    private fun blockTarget(target: String) {
        val t = target.trim()
        if (t.isEmpty()) return
        if (t.startsWith("@")) Control.setHandles(this, Control.getHandles(this) + t)
        else Control.setNames(this, Control.getNames(this) + t)
    }

    private fun unblockTarget(target: String) {
        val t = target.trim()
        Control.setHandles(this, Control.getHandles(this).filterNot { it.equals(t, true) })
        Control.setNames(this, Control.getNames(this).filterNot { it.equals(t, true) })
        Control.setExact(this, Control.getExact(this).filterNot { it.equals(t, true) })
    }

    private fun entryCard(e: Entry): View {
        val isSearchOrLink = e.type == "بحث" || e.type == "رابط" || e.type == "تطبيق" || e.type == "فحص" || e.type == "سجل"
        val kind = effectiveKind(e)
        val kindColor = if (isSearchOrLink) (if (e.type == "رابط") cMint else Color.parseColor("#C084FC")) else if (kind == "شورت") cRose else cBlue
        val (statusText, statusColor) = simpleStatus(e)
        val key = entryKey(e)
        // الهدف القابل للحجب/فك الحجب: اسم القناة إن وجد، وإلا نص العنصر نفسه (غير متاح لعناصر البحث/الروابط)
        val target = if (isSearchOrLink) "" else e.channel.ifBlank { e.label }.trim()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = bg(cCard, 18f, cStroke)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        // أيقونة بدل الصورة المصغّرة (التطبيق ما يقدر ياخذ صورة الفيديو الحقيقية)
        val iconBox = FrameLayout(this).apply {
            background = bg(alpha(kindColor, 40), 14f)
        }
        val icon = when {
            e.type == "بحث" -> "🔍"
            e.type == "رابط" -> "🔗"
            e.type == "تطبيق" || e.type == "فحص" || e.type == "سجل" -> "📱"
            kind == "شورت" -> "⚡"
            else -> "▶"
        }
        val srcPkg = when {
            e.type == "تطبيق" || e.type == "فحص" || e.type == "سجل" -> e.channel
            e.type == "بحث" || e.type == "رابط" -> ""
            else -> "com.google.android.youtube"
        }
        val srcBmp = if (srcPkg.isNotEmpty()) Sync.iconFor(sel?.id ?: "", srcPkg) else null
        if (srcBmp != null) iconBox.addView(ImageView(this).apply { setImageBitmap(srcBmp); setPadding(dp(7), dp(7), dp(7), dp(7)) })
        else iconBox.addView(tv(icon, 18f, kindColor, true).apply { gravity = Gravity.CENTER })
        val thumbBmp = if (e.thumb.isNotEmpty()) Sync.thumbCached(e.thumb) else null
        if (e.thumb.isNotEmpty() && thumbBmp == null) {
            Sync.loadThumb(this, e.thumb) { runOnUiThread { thumbsRebuildLater() } }
        }
        if (thumbBmp != null) {
            iconBox.removeAllViews()
            iconBox.addView(ImageView(this).apply { setImageBitmap(thumbBmp); scaleType = ImageView.ScaleType.CENTER_CROP })
            row.addView(iconBox, LinearLayout.LayoutParams(dp(96), dp(54)))
        } else row.addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (mainTxt, detailTxt) = humanText(e)
        col.addView(tv(mainTxt, 14.5f, if (e.type == "رابط") cMint else cText, true).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dpf(2f), 1f)
            if (e.type == "رابط") {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { openLink(e.label) }
            }
        })
        if (detailTxt.isNotBlank()) col.addView(tv(detailTxt, 12.5f, cMuted).apply { maxLines = 3; setPadding(0, dp(2), 0, 0) })
        val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val time = if (e.ts.length >= 16) e.ts.substring(11, 16) else ""
        metaRow.addView(tv(time, 11.5f, cMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        col.addView(metaRow, lp(MATCH, WRAP, 4))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })

        // السهم: يفتح/يقفل قائمة تحكم سريعة
        val isOpen = expanded.contains(key)
        row.addView(
            tv(if (isOpen) "▲" else "▼", 13f, cMuted, true).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    if (isOpen) expanded.remove(key) else expanded.add(key)
                    rebuild(keepScroll = true)
                }
            }
        )
        row.addView(trashBtn(e))
        card.addView(row)

        if (isOpen) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(4))
            }
            if (target.isNotBlank()) {
                val blocked = isTargetBlocked(target)
                box.addView(
                    tv(if (blocked) "فك الحجب عن: $target" else "حجب: $target", 13f, Color.BLACK, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(14), dp(11), dp(14), dp(11))
                        background = bg(if (blocked) cMint else cRose, 100f)
                        setOnClickListener {
                            if (blocked) unblockTarget(target) else blockTarget(target)
                            Toast.makeText(
                                this@MainActivity,
                                if (blocked) "تم فك الحجب" else "تم الحجب، راح يختفي القادم",
                                Toast.LENGTH_SHORT
                            ).show()
                            rebuild(keepScroll = true)
                        }
                    },
                    lp(MATCH, WRAP, 2)
                )
            } else {
                val msg = if (isSearchOrLink) "ما فيه حجب لعناصر البحث والروابط" else "ما قدرنا نحدد اسم القناة لهذا العنصر"
                box.addView(tv(msg, 12f, cMuted), lp(MATCH, WRAP, 2))
            }
            box.addView(
                tv("باقي الخيارات (عدم مهم، عدم الاقتراح) تصير لحظة ظهور الفيديو بيوتيوب نفسه، وما نقدر نفعّلها من السجل بعد ما يفوت وقتها.", 11f, cMuted).apply {
                    setPadding(0, dp(10), 0, 0)
                    setLineSpacing(dpf(2f), 1f)
                },
                lp(MATCH, WRAP, 2)
            )
            card.addView(box, lp(MATCH, WRAP, 2))
        }

        if (e.phone.isNotBlank()) {
            card.addView(tv(e.phone, 11f, alpha(cMuted, 170)), lp(MATCH, WRAP, 6))
        }
        return card
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("مسح السجل؟")
            .setMessage("سيتم حذف كل الأنشطة المسجّلة من هذا الجهاز.")
            .setPositiveButton("مسح") { _, _ ->
                prefs.edit().remove("activitylog").apply()
                rebuild(keepScroll = false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ───────── تفاصيل للمطوّر (مطويّة افتراضيًا) ─────────
    private fun techText(): String {
        fun g(k: String, d: String = "-") = prefs.getString(k, d) ?: d
        return "الحالة:\n" + g("status", "الخدمة لم تستقبل شيئًا بعد (هل هي مفعّلة؟)") +
            "\n\nالفيديو المعروض:\n" + g("watching") +
            "\n\nالمطابقات:\n" + g("matches") +
            "\n\nأزرار:\n" + g("controls") +
            "\n\nالمشغّل المصغّر:\n" + g("mini") +
            "\n\nالعناصر:\n" + g("ids", "")
    }

    private fun techCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(cCard, 18f, cStroke)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { techOpen = !techOpen; rebuild(keepScroll = true) }
        }
        head.addView(tv("تفاصيل تقنية", 14f, cMuted, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(tv(if (techOpen) "▲" else "▼", 12f, cMuted))
        card.addView(head)
        if (techOpen) {
            val body = techText()
            card.addView(
                tv(body, 11f, cMuted).apply {
                    typeface = Typeface.MONOSPACE
                    textDirection = View.TEXT_DIRECTION_LTR
                    setTextIsSelectable(true)
                },
                lp(MATCH, WRAP, 12)
            )
            card.addView(
                pill("نسخ التفاصيل", cBlue).apply {
                    setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("5al", body))
                        Toast.makeText(this@MainActivity, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }
                },
                lp(WRAP, WRAP, 12)
            )
        }
        return card
    }
}
