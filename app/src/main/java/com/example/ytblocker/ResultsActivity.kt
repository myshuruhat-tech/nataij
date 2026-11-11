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
        val id: String,
        val slug: String,
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
        League("307", "Saudi Pro League"),
        League("2", "UEFA Champions League"),
        League("39", "Premier League"),
        League("140", "LaLiga"),
        League("135", "Serie A"),
        League("78", "Bundesliga"),
        League("61", "Ligue 1"),
        League("100", "World Cup"),
        League("101", "World Cup Qualifiers (UEFA)"),
        League("102", "Copa América"),
        League("103", "International Friendly"),
        League("104", "UEFA Nations League"),
        League("105", "Africa Cup of Nations"),
        League("106", "AFC Asian Cup"),
        League("107", "MLS"),
        League("108", "Primeira Liga"),
        League("109", "AFC Champions League"),
        League("110", "Liga MX"),
        League("111", "Categoría Primera A"),
        League("112", "Championship"),
        League("113", "La Liga 2"),
        League("114", "Ligue 2")
    )

    // ألوان
    private val cBg = Color.parseColor("#0A0A0C")
    private val cCard = Color.parseColor("#141418")
    private val cCardAlt = Color.parseColor("#1C1C22")
    private val cStroke = Color.parseColor("#2A2A32")
    private val cText = Color.parseColor("#F4F4F6")
    private val cMuted = Color.parseColor("#9A9AA6")
    private val cMint = Color.parseColor("#FFFFFF")
    private val cBlue = Color.parseColor("#C7C7CF")
    private val cLive = Color.parseColor("#FFFFFF")

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
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(scroll)
        GoalAlarm.scheduleRepeating(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 701) } catch (e: Exception) { }
        }
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
        "alhilal" to "Al-Hilal", "alnassr" to "Al-Nassr", "alittihad" to "Al-Ittihad", "alahli" to "Al-Ahli",
        "alshabab" to "Al-Shabab", "alettifaq" to "Al-Ettifaq", "alfateh" to "Al-Fateh", "altaawoun" to "Al-Taawoun",
        "alfayha" to "Al-Fayha", "alraed" to "Al-Raed", "damac" to "Damac", "alkhaleej" to "Al-Khaleej",
        "alriyadh" to "Al-Riyadh", "alokhdood" to "Al-Okhdood", "alqadsiah" to "Al-Qadsiah", "alkholood" to "Al-Kholood",
        "neom" to "Neom", "alhazem" to "Al-Hazem", "alwehda" to "Al-Wehda", "alorobah" to "Al-Orobah",
        "realmadrid" to "Real Madrid", "barcelona" to "Barcelona", "atleticomadrid" to "Atletico Madrid",
        "sevilla" to "Sevilla", "valencia" to "Valencia", "realsociedad" to "Real Sociedad",
        "villarreal" to "Villarreal", "athleticclub" to "Athletic Bilbao",
        "manchestercity" to "Manchester City", "manchesterunited" to "Manchester United",
        "liverpool" to "Liverpool", "arsenal" to "Arsenal", "chelsea" to "Chelsea",
        "tottenham" to "Tottenham", "newcastle" to "Newcastle", "astonvilla" to "Aston Villa",
        "bayernmunich" to "Bayern Munich", "borussiadortmund" to "Dortmund",
        "bayerleverkusen" to "Leverkusen", "rbleipzig" to "RB Leipzig",
        "juventus" to "Juventus", "intermilan" to "Inter Milan", "internazionale" to "Inter Milan",
        "acmilan" to "AC Milan", "napoli" to "Napoli", "roma" to "Roma", "lazio" to "Lazio", "atalanta" to "Atalanta",
        "parissaintgermain" to "Paris Saint-Germain", "psg" to "Paris Saint-Germain",
        "marseille" to "Marseille", "monaco" to "Monaco", "lyon" to "Lyon",
        "bayern" to "Bayern Munich", "inter" to "Inter Milan"
    )

    private fun ar(name: String): String = name // English UI now: ESPN's own team name is used as-is

    // ───────── التحميل ─────────
    private fun dateParam(): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private var errorSub = "Check your internet, then try again."

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
            var sub = "Check your internet, then try again."
            try {
                grouped = parse(httpGet("$WORKER_URL/fixtures?date=$date&tz=$tz"))
            } catch (e: ApiError) {
                sub = when (e.code) {
                    "plan_date" -> "This day isn't available on the free plan."
                    "quota" -> "Daily request limit reached. Try again later."
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
                "CANC" -> "Cancelled"
                "ABD" -> "Abandoned"
                "SUSP", "INT" -> "Suspended"
                "HT" -> "Half-time"
                "BT" -> "Break (ET)"
                "P" -> "● Penalties"
                "AET" -> "FT (AET)"
                "PEN" -> "FT (Pens)"
                else -> when (state) {
                    "in" -> "● LIVE  $elapsed"
                    "post" -> "FT"
                    else -> SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
                }
            }
            fun str(k: String) = if (m.isNull(k)) "0" else m.optInt(k).toString()
            list.add(
                Match(
                    m.optString("id"), m.optString("slug"),
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
            loading -> content.addView(message("Loading matches…", null), lp(MATCH, WRAP, 18))
            failedAll -> content.addView(message("Couldn't load results", errorSub), lp(MATCH, WRAP, 18))
            else -> addMatches()
        }
        content.addView(
            tv("Data from ESPN and may lag a few moments behind the live broadcast.", 11f, alpha(cMuted, 170)).apply { gravity = Gravity.CENTER },
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
        col.addView(tv("Results", 26f, cText, true))
        val today = SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date())
        col.addView(tv(today, 13f, cMuted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })

        row.addView(pill("Refresh", cMint).apply { setOnClickListener { load(silent = false) } })
        return row
    }

    private fun dayChips(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-1 to "Yesterday", 0 to "Today", 1 to "Tomorrow", 2 to "Day after").forEachIndexed { i, (off, name) ->
            val sel = dayOffset == off
            val chip = tv(name, 11.5f, if (sel) Color.BLACK else cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(9), dp(4), dp(9))
                setSingleLine(true)
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
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val all = listOf<String?>(null) + leagues.map { it.slug }
        all.forEachIndexed { i, slug ->
            val name = if (slug == null) "All Leagues" else leagues.first { it.slug == slug }.name
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
                pill("Retry", cMint).apply { setOnClickListener { load(silent = false) } },
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
        if (!any) content.addView(message("No matches", "No matches today for the selected leagues."), lp(MATCH, WRAP, 18))
    }

    // صف مضغوط أفقي بأسلوب FotMob: فريق — شعار — وقت/نتيجة — شعار — فريق
    private fun matchCard(m: Match): View {
        val statusColor = when (m.state) {
            "in" -> cLive
            "post" -> cMuted
            else -> cBlue
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bg(cCard, 14f, if (m.state == "in") alpha(cLive, 110) else cStroke)
            isClickable = m.id.isNotBlank() && m.slug.isNotBlank()
            if (isClickable) setOnClickListener {
                val i = android.content.Intent(this@ResultsActivity, MatchActivity::class.java)
                i.putExtra(MatchActivity.EXTRA_ID, m.id)
                i.putExtra(MatchActivity.EXTRA_SLUG, m.slug)
                i.putExtra(MatchActivity.EXTRA_HOME, m.homeName)
                i.putExtra(MatchActivity.EXTRA_AWAY, m.awayName)
                i.putExtra(MatchActivity.EXTRA_STATUS, m.statusText)
                startActivity(i)
            }
        }

        row.addView(crest(m.homeLogo), LinearLayout.LayoutParams(dp(26), dp(26)))
        row.addView(
            tv(m.homeName, 13.5f, cText, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) }
        )

        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        if (m.state == "pre") {
            center.addView(tv(m.statusText, 13f, statusColor, true))
        } else {
            center.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(tv(m.homeScore, 15f, cText, true))
                    addView(tv("-", 13f, cMuted).apply { setPadding(dp(5), 0, dp(5), 0) })
                    addView(tv(m.awayScore, 15f, cText, true))
                }
            )
            center.addView(tv(m.statusText, 10.5f, statusColor, true).apply { setPadding(0, dp(2), 0, 0) })
        }
        row.addView(center, LinearLayout.LayoutParams(dp(74), WRAP).apply { marginStart = dp(6); marginEnd = dp(6) })

        row.addView(
            tv(m.awayName, 13.5f, cText, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.END },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) }
        )
        row.addView(crest(m.awayLogo), LinearLayout.LayoutParams(dp(26), dp(26)))

        if (m.homeName.isNotBlank() && m.homeName != "—") row.addView(followStar(m.homeName), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        return row
    }

    private fun crest(logo: String?): View {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (logo != null) loadLogo(logo, iv)
        return iv
    }

    private fun followStar(team: String): View {
        val followed = GoalAlarm.isFollowed(this, team)
        return tv(if (followed) "★" else "☆", 15f, if (followed) cMint else cMuted).apply {
            isClickable = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                val now = GoalAlarm.toggleFollow(this@ResultsActivity, team)
                text = if (now) "★" else "☆"
                setTextColor(if (now) cMint else cMuted)
            }
        }
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
