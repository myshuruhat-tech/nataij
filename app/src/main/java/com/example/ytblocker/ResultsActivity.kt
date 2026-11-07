package com.example.ytblocker

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class ResultsActivity : Activity() {

    companion object {
        // رابط الـ Worker من dash.cloudflare.com (بدون / في الآخر). غيّره بعد النشر.
        const val WORKER_URL = "https://nataij-api.myshuruhat.workers.dev"
    }

    private data class League(val slug: String, val name: String)

    private data class Match(
        val league: String,
        val timeMs: Long,
        val state: String,        // pre / in / post
        val statusText: String,
        val homeName: String,
        val awayName: String,
        val homeScore: String,
        val awayScore: String,
        val homeLogo: String?,
        val awayLogo: String?
    )

    private val leagues = listOf(
        League("307", "الدوري السعودي"),
        League("2", "دوري أبطال أوروبا"),
        League("39", "الدوري الإنجليزي"),
        League("140", "الدوري الإسباني"),
        League("135", "الدوري الإيطالي"),
        League("78", "الدوري الألماني"),
        League("61", "الدوري الفرنسي")
    )

    // ألوان
    private val cBg = Color.parseColor("#0A0A0C")
    private val cCard = Color.parseColor("#141418")
    private val cCardAlt = Color.parseColor("#1C1C22")
    private val cStroke = Color.parseColor("#2A2A32")
    private val cText = Color.parseColor("#F4F4F6")
    private val cMuted = Color.parseColor("#9A9AA6")
    private val cMint = Color.parseColor("#34D399")
    private val cBlue = Color.parseColor("#60A5FA")
    private val cLive = Color.parseColor("#F87171")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(6)
    private val logoCache = HashMap<String, Bitmap>()

    private var dayOffset = 0
    private var leagueFilter: String? = null
    private var loading = false
    private var failedAll = false
    private var reqId = 0
    private var results: Map<String, List<Match>> = emptyMap()

    private val tick = Runnable { load(silent = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        load(silent = false)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ───────── أدوات ─────────
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

    private fun pill(text: String, color: Int, size: Float = 12f): TextView =
        tv(text, size, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = bg(alpha(color, 40), 100f)
        }

    private fun lp(w: Int, h: Int, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    // ───────── أسماء الفرق بالعربي ─────────
    private val arNames = listOf(
        "alhilal" to "الهلال", "alnassr" to "النصر", "alittihad" to "الاتحاد", "alahli" to "الأهلي",
        "alshabab" to "الشباب", "alettifaq" to "الاتفاق", "alfateh" to "الفتح", "altaawoun" to "التعاون",
        "alfayha" to "الفيحاء", "alraed" to "الرائد", "damac" to "ضمك", "alkhaleej" to "الخليج",
        "alriyadh" to "الرياض", "alokhdood" to "الأخدود", "alqadsiah" to "القادسية", "alkholood" to "الخلود",
        "neom" to "نيوم", "alhazem" to "الحزم", "alwehda" to "الوحدة", "alorobah" to "العروبة",
        "realmadrid" to "ريال مدريد", "barcelona" to "برشلونة", "atleticomadrid" to "أتلتيكو مدريد",
        "sevilla" to "إشبيلية", "valencia" to "فالنسيا", "realsociedad" to "ريال سوسيداد",
        "villarreal" to "فياريال", "athleticclub" to "أتلتيك بلباو",
        "manchestercity" to "مانشستر سيتي", "manchesterunited" to "مانشستر يونايتد",
        "liverpool" to "ليفربول", "arsenal" to "أرسنال", "chelsea" to "تشيلسي",
        "tottenham" to "توتنهام", "newcastle" to "نيوكاسل", "astonvilla" to "أستون فيلا",
        "bayernmunich" to "بايرن ميونخ", "borussiadortmund" to "دورتموند",
        "bayerleverkusen" to "ليفركوزن", "rbleipzig" to "لايبزيغ",
        "juventus" to "يوفنتوس", "intermilan" to "إنتر ميلان", "internazionale" to "إنتر ميلان",
        "acmilan" to "ميلان", "napoli" to "نابولي", "roma" to "روما", "lazio" to "لاتسيو", "atalanta" to "أتالانتا",
        "parissaintgermain" to "باريس سان جيرمان", "psg" to "باريس سان جيرمان",
        "marseille" to "مرسيليا", "monaco" to "موناكو", "lyon" to "ليون",
        "bayern" to "بايرن ميونخ", "inter" to "إنتر ميلان"
    )

    private fun ar(name: String): String {
        val key = name.lowercase(Locale.US).filter { it in 'a'..'z' }
        return arNames.firstOrNull { key.contains(it.first) }?.second ?: name
    }

    // ───────── التحميل ─────────
    private fun dateParam(): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private var errorSub = "تأكد من الإنترنت ثم أعد المحاولة."

    private fun load(silent: Boolean) {
        handler.removeCallbacks(tick)
        val id = ++reqId
        if (!silent) {
            loading = true
            failedAll = false
            render()
        }
        val date = dateParam()
        val tz = java.net.URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
        pool.execute {
            var grouped: Map<String, List<Match>>? = null
            var sub = "تأكد من الإنترنت ثم أعد المحاولة."
            try {
                grouped = parse(httpGet("$WORKER_URL/fixtures?date=$date&tz=$tz"))
            } catch (e: ApiError) {
                sub = when (e.code) {
                    "plan_date" -> "هذا اليوم غير متاح في الخطة المجانية."
                    "quota" -> "انتهى الحد اليومي للطلبات. جرّب لاحقًا."
                    else -> sub
                }
            } catch (e: Exception) {
            }
            val fresh = grouped
            runOnUiThread {
                if (id != reqId) return@runOnUiThread
                loading = false
                // التحديث التلقائي الفاشل ما يمسح النتائج المعروضة
                failedAll = fresh == null && !(silent && results.isNotEmpty())
                errorSub = sub
                if (fresh != null) results = fresh
                render()
                val anyLive = results.values.any { list -> list.any { it.state == "in" } }
                if (anyLive || failedAll) handler.postDelayed(tick, 30_000)
            }
        }
    }

    private class ApiError(val code: String) : RuntimeException(code)

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "GET"
        try {
            if (c.responseCode != 200) {
                val code = try {
                    JSONObject(c.errorStream?.bufferedReader()?.use { it.readText() } ?: "").optString("error", "http")
                } catch (e: Exception) { "http" }
                throw ApiError(code)
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String): Map<String, List<Match>> {
        val arr = JSONObject(body).optJSONArray("matches") ?: throw ApiError("bad")
        val names = leagues.associate { it.slug to it.name }
        val out = LinkedHashMap<String, MutableList<Match>>()
        leagues.forEach { out[it.slug] = mutableListOf() }
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val lid = m.optInt("lid").toString()
            val list = out[lid] ?: continue
            val state = m.optString("state", "pre")
            val short = m.optString("short")
            val t = m.optLong("ts")
            val elapsed = if (m.isNull("elapsed")) "" else "${m.optInt("elapsed")}'"
            val status = when (short) {
                "PST" -> "مؤجلة"
                "CANC" -> "ملغاة"
                "ABD" -> "متوقفة"
                "SUSP", "INT" -> "متوقفة مؤقتًا"
                "HT" -> "استراحة"
                "BT" -> "استراحة الوقت الإضافي"
                "P" -> "● ركلات الترجيح"
                "AET" -> "انتهت (وقت إضافي)"
                "PEN" -> "انتهت (ركلات ترجيح)"
                else -> when (state) {
                    "in" -> "● مباشر  $elapsed"
                    "post" -> "انتهت"
                    else -> SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
                }
            }
            fun str(k: String) = if (m.isNull(k)) "0" else m.optInt(k).toString()
            list.add(
                Match(
                    names[lid] ?: "", t, state, status,
                    ar(m.optString("home", "—")), ar(m.optString("away", "—")),
                    str("hs"), str("as"),
                    m.optString("hl").ifBlank { null }, m.optString("al").ifBlank { null }
                )
            )
        }
        return out
    }

    // ───────── العرض ─────────
    private fun render() {
        val y = scroll.scrollY
        content.removeAllViews()
        content.addView(header())
        content.addView(dayChips(), lp(MATCH, WRAP, 18))
        content.addView(leagueChips(), lp(MATCH, WRAP, 10))

        when {
            loading -> content.addView(message("جاري تحميل المباريات…", null), lp(MATCH, WRAP, 18))
            failedAll -> content.addView(message("تعذّر تحميل النتائج", errorSub), lp(MATCH, WRAP, 18))
            else -> addMatches()
        }
        content.addView(
            tv("البيانات من API-Football وقد تتأخر لحظات عن البث المباشر.", 11f, alpha(cMuted, 170)).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP, 28)
        )
        scroll.post { scroll.scrollTo(0, y) }
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
        }, FrameLayout.LayoutParams(dp(54), dp(54)))
        row.addView(logoBox, LinearLayout.LayoutParams(dp(54), dp(54)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("نتايج", 26f, cText, true))
        val today = SimpleDateFormat("EEEE d MMMM", Locale("ar")).format(Date())
        col.addView(tv(today, 13f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })

        row.addView(pill("تحديث", cMint).apply { setOnClickListener { load(silent = false) } })
        return row
    }

    private fun dayChips(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-1 to "أمس", 0 to "اليوم", 1 to "غدًا", 2 to "بعد غد").forEachIndexed { i, (off, name) ->
            val sel = dayOffset == off
            val chip = tv(name, 13f, if (sel) Color.BLACK else cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = if (sel) bg(cMint, 100f) else bg(cCardAlt, 100f, cStroke)
                setOnClickListener { dayOffset = off; load(silent = false) }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        return row
    }

    private fun leagueChips(): View {
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val all = listOf<String?>(null) + leagues.map { it.slug }
        all.forEachIndexed { i, slug ->
            val name = if (slug == null) "كل الدوريات" else leagues.first { it.slug == slug }.name
            val sel = leagueFilter == slug
            val chip = tv(name, 12f, if (sel) Color.BLACK else cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = if (sel) bg(cBlue, 100f) else bg(cCardAlt, 100f, cStroke)
                setOnClickListener { leagueFilter = slug; render() }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) })
        }
        hs.addView(row)
        return hs
    }

    private fun message(title: String, sub: String?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(34), dp(20), dp(34))
            background = bg(cCard, 20f, cStroke)
        }
        box.addView(tv(title, 16f, cText, true).apply { gravity = Gravity.CENTER })
        if (sub != null) box.addView(tv(sub, 13f, cMuted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        if (failedAll) {
            box.addView(
                pill("إعادة المحاولة", cMint).apply { setOnClickListener { load(silent = false) } },
                lp(WRAP, WRAP, 14)
            )
        }
        return box
    }

    private fun addMatches() {
        var any = false
        leagues.filter { leagueFilter == null || it.slug == leagueFilter }.forEach { lg ->
            val list = (results[lg.slug] ?: emptyList())
                .sortedWith(compareBy<Match>({ if (it.state == "in") 0 else 1 }, { it.timeMs }))
            if (list.isEmpty()) return@forEach
            any = true
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(tv(lg.name, 15f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(pill("${list.size}", cMuted, 11f))
            content.addView(head, lp(MATCH, WRAP, 22))
            list.forEach { content.addView(matchCard(it), lp(MATCH, WRAP, 8)) }
        }
        if (!any) content.addView(message("لا توجد مباريات", "لا يوجد مباريات في هذا اليوم للدوريات المختارة."), lp(MATCH, WRAP, 18))
    }

    private fun matchCard(m: Match): View {
        val statusColor = when (m.state) {
            "in" -> cLive
            "post" -> cMuted
            else -> cBlue
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = bg(cCard, 18f, if (m.state == "in") alpha(cLive, 110) else cStroke)
        }
        card.addView(pill(m.statusText, statusColor, 11f), LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(team(m.homeName, m.homeLogo), LinearLayout.LayoutParams(0, WRAP, 1f))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (m.state == "pre") {
            center.addView(tv("VS", 14f, cMuted, true))
        } else {
            center.addView(tv(m.homeScore, 26f, cText, true))
            center.addView(tv("-", 20f, cMuted).apply { setPadding(dp(8), 0, dp(8), 0) })
            center.addView(tv(m.awayScore, 26f, cText, true))
        }
        row.addView(center, LinearLayout.LayoutParams(dp(96), WRAP))
        row.addView(team(m.awayName, m.awayLogo), LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(row, lp(MATCH, WRAP, 12))
        return card
    }

    private fun team(name: String, logo: String?): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        col.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
        if (logo != null) loadLogo(logo, iv)
        col.addView(
            tv(name, 13f, cText, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            lp(MATCH, WRAP, 6)
        )
        return col
    }

    private fun loadLogo(url: String, iv: ImageView) {
        logoCache[url]?.let { iv.setImageBitmap(it); return }
        pool.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@execute
                logoCache[url] = bmp
                runOnUiThread { iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
            }
        }
    }
}
