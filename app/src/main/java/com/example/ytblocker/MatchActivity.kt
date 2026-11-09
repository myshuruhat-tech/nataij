package com.example.ytblocker

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MatchActivity : Activity() {

    companion object {
        const val WORKER_URL = ResultsActivity.WORKER_URL
        const val EXTRA_ID = "id"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_HOME = "home"
        const val EXTRA_AWAY = "away"
        const val EXTRA_STATUS = "status"
    }

    private data class Commentary(val time: String, val text: String)
    private data class Starter(val name: String, val num: String, val pos: String)
    private data class Lineup(val team: String, val formation: String?, val starters: List<Starter>)
    private data class StatRow(val label: String, val home: String, val away: String)

    private val cBg = Color.parseColor("#0A0A0C")
    private val cCard = Color.parseColor("#141418")
    private val cCardAlt = Color.parseColor("#1C1C22")
    private val cStroke = Color.parseColor("#2A2A32")
    private val cText = Color.parseColor("#F4F4F6")
    private val cMuted = Color.parseColor("#9A9AA6")
    private val cMint = Color.parseColor("#34D399")
    private val cLive = Color.parseColor("#F87171")
    private val cBlue = Color.parseColor("#60A5FA")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius; if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private var matchId = ""
    private var slug = ""
    private var homeName = ""
    private var awayName = ""
    private var tab = "الملخص"
    private val tabs = listOf("الملخص", "التشكيلة", "الإحصائيات", "التعليق")

    private var commentary: List<Commentary> = emptyList()
    private var lineups: List<Lineup> = emptyList()
    private var stats: List<StatRow> = emptyList()
    private var loadError = false

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        matchId = intent.getStringExtra(EXTRA_ID) ?: ""
        slug = intent.getStringExtra(EXTRA_SLUG) ?: ""
        homeName = intent.getStringExtra(EXTRA_HOME) ?: ""
        awayName = intent.getStringExtra(EXTRA_AWAY) ?: ""
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, lp(MATCH, 0, 0).apply { weight = 1f })
        setContentView(root)
        render()
        load()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun load() {
        pool.execute {
            try {
                val c = URL("$WORKER_URL/match?id=$matchId&slug=$slug").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                val com = o.optJSONArray("commentary")
                val comList = mutableListOf<Commentary>()
                if (com != null) for (i in 0 until com.length()) {
                    val e = com.getJSONObject(i)
                    comList.add(Commentary(e.optString("t"), e.optString("text")))
                }
                val lu = o.optJSONArray("lineups")
                val luList = mutableListOf<Lineup>()
                if (lu != null) for (i in 0 until lu.length()) {
                    val e = lu.getJSONObject(i)
                    val starters = mutableListOf<Starter>()
                    val sArr = e.optJSONArray("starters")
                    if (sArr != null) for (j in 0 until sArr.length()) {
                        val s = sArr.getJSONObject(j)
                        starters.add(Starter(s.optString("name"), s.optString("num"), s.optString("pos")))
                    }
                    luList.add(Lineup(e.optString("team"), if (e.isNull("formation")) null else e.optString("formation"), starters))
                }
                val st = o.optJSONArray("stats")
                val stList = mutableListOf<StatRow>()
                if (st != null) for (i in 0 until st.length()) {
                    val e = st.getJSONObject(i)
                    stList.add(StatRow(e.optString("label"), e.optString("home", "-"), e.optString("away", "-")))
                }
                handler.post {
                    if (destroyed) return@post
                    commentary = comList; lineups = luList; stats = stList; loadError = false
                    render()
                }
            } catch (e: Exception) {
                handler.post { if (!destroyed) { loadError = true; render() } }
            }
        }
        handler.postDelayed({ if (!destroyed) load() }, 20_000)
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv("‹ رجوع", 14f, cMuted, true).apply {
            setPadding(dp(6), dp(10), dp(14), dp(10))
            setOnClickListener { finish() }
        })
        row.addView(tv("$homeName  —  $awayName", 15f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun tabsRow(): View {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, name ->
            val selected = tab == name
            row.addView(
                tv(name, 13.5f, if (selected) Color.BLACK else cMuted, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    background = if (selected) bg(cMint, 100f) else bg(cCardAlt, 100f, cStroke)
                    setOnClickListener { tab = name; render() }
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) }
            )
        }
        hs.addView(row)
        return hs
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(cCard, 18f, cStroke)
    }

    private fun summaryView(): View {
        val c = card()
        val goalsAndCards = commentary.filter {
            val t = it.text.lowercase()
            t.contains("goal") || t.contains("هدف") || t.contains("card") || t.contains("بطاقة") || t.contains("red") || t.contains("yellow")
        }
        if (goalsAndCards.isEmpty()) {
            c.addView(tv(if (loadError) "تعذّر تحميل أحداث المباراة" else "ما فيه أحداث مهمة لين الحين", 13f, cMuted))
        } else {
            goalsAndCards.forEach { e ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(tv(e.time.ifBlank { "•" }, 12f, cMint, true), LinearLayout.LayoutParams(dp(54), WRAP))
                row.addView(tv(e.text, 13f, cText), LinearLayout.LayoutParams(0, WRAP, 1f))
                c.addView(row, lp(MATCH, WRAP, 10))
            }
        }
        return c
    }

    private fun lineupView(): View {
        if (lineups.isEmpty()) return card().apply { addView(tv(if (loadError) "تعذّر تحميل التشكيلة" else "التشكيلة ما بانت بعد", 13f, cMuted)) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lineups.forEach { lu ->
            val c = card()
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            h.addView(tv(lu.team, 14.5f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (lu.formation != null) h.addView(tv(lu.formation, 12f, cMuted))
            c.addView(h)
            lu.starters.forEach { s ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(tv(s.num.ifBlank { "-" }, 11.5f, cMuted), LinearLayout.LayoutParams(dp(28), WRAP))
                row.addView(tv(s.name, 13f, cText), LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(tv(s.pos, 11.5f, cBlue))
                c.addView(row, lp(MATCH, WRAP, 8))
            }
            box.addView(c, lp(MATCH, WRAP, 12))
        }
        return box
    }

    private fun statsView(): View {
        val c = card()
        if (stats.isEmpty()) {
            c.addView(tv(if (loadError) "تعذّر تحميل الإحصائيات" else "الإحصائيات ما بانت بعد", 13f, cMuted))
            return c
        }
        c.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tv(homeName, 12f, cMuted, true), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(tv(awayName, 12f, cMuted, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        )
        stats.forEach { s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(tv(s.home, 13.5f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(tv(s.label, 12f, cMuted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 2f))
            line.addView(tv(s.away, 13.5f, cText, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(line)
            c.addView(row, lp(MATCH, WRAP, 12))
        }
        return c
    }

    private fun commentaryView(): View {
        val c = card()
        if (commentary.isEmpty()) {
            c.addView(tv(if (loadError) "تعذّر تحميل التعليق" else "ما بدأ التعليق بعد", 13f, cMuted))
            return c
        }
        commentary.forEach { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(tv(e.time.ifBlank { "•" }, 12f, cLive, true), LinearLayout.LayoutParams(dp(54), WRAP))
            row.addView(tv(e.text, 13f, cText), LinearLayout.LayoutParams(0, WRAP, 1f))
            c.addView(row, lp(MATCH, WRAP, 10))
        }
        return c
    }

    private fun render() {
        content.removeAllViews()
        content.addView(header())
        content.addView(tabsRow(), lp(MATCH, WRAP, 14))
        val body = when (tab) {
            "التشكيلة" -> lineupView()
            "الإحصائيات" -> statsView()
            "التعليق" -> commentaryView()
            else -> summaryView()
        }
        content.addView(body, lp(MATCH, WRAP, 14))
    }
}
