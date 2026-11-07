package com.example.ytblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockerService : AccessibilityService() {

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[\\u064B-\\u065F\\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا")
        .replace("ى", "ي").replace("ة", "ه")
        .replace(Regex("['’‘]"), "")
        .replace(Regex("[٠-٩]")) { (it.value[0] - '٠').toString() }
        .replace(Regex("\\s+"), " ").trim()

    // handles: فريدة، contains
    private val handles = listOf(
        "@d7oomy999", "@POWRibrahim", "@FRS9", "@FFearFFul", "@mjrmgames909",
        "@POWR_Rayan", "@iiGOATGamingg", "@POWR_ROB", "@xBear1", "@ABS11",
        "@Abo_treek1", "@Topz", "@Abunoo7", "@POWR_Rakan", "@Siiu-Team",
        "@POWR-Esports", "@SHoNgxxBoNg", "@xbear-plus", "@sxb3654",
        "@FRLplus", "@25KLOoODE", "@POWR.Kmstka", "@MSKplusss", "@Moskooo"
    ).map { norm(it) }

    // أسماء طويلة: contains
    private val names = listOf(
        "d7oomy_999", "Ibrahim | ابو خليل", "Faris | فارس", "FFearFFul",
        "MjrmGames", "POWR Rayan", "iiGOATGaming", "POWR ROB",
        "ابو عابد الشمري", "Turki | ابو تريك", "TOPZ توبز", "ابو نوح",
        "POWR Rakan", "POWR eSports", "SHoNgxBoNg", "خلودي 25",
        "كمستكا", "MOSKOO",
        // كتابات عربية وأجزاء من الأسماء (تظهر داخل عناوين المقاطع والبثوث)
        "d7oomy", "دحومي", "mjrm", "مجرم قيمز", "مجرم جيمز", "iigoat",
        "باور ريان", "ريان باور", "باور روب", "روب باور",
        "باور راكان", "راكان باور", "باور ابراهيم", "ابراهيم باور",
        "باور اسبورت", "باور ايسبورت", "باور اي سبورت",
        "شونج بونج", "توبز", "ابو تريك"
    ).map { norm(it) }

    // أسماء قصيرة: مطابقة تامة
    private val exact = listOf("ABS", "SXB", "F R S", "FRL+", "MSK+").map { norm(it) }.toSet()
    // الأسماء القصيرة تنطبق كلمة مستقلة داخل العنوان (مو جزء من كلمة ثانية)
    private val shortWords = (exact + listOf("FRS", "TOPZ").map { norm(it) }).map {
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])")
    }

    private val YT = "com.google.android.youtube"
    private val blockTouch = false // true = يمنع الضغط على المخفي، لكن يمنع السكرول لو بدأ عليه
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val covers = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val refresh = Runnable { pending = false; update() }
    private var lastBack = 0L
    private var lastCoverKeys = mutableSetOf<String>()

    private fun matches(raw: String): Boolean {
        val t = norm(raw)
        return t in exact || shortWords.any { it.containsMatchIn(t) } || handles.any { t.contains(it) } || names.any { t.contains(it) }
    }

    // كيك: التطبيق الرئيسي (جوال وتلفزيون) وتطبيق Go Live
    private val blockedApps = setOf("com.kick.mobile", "com.kick.streaming")
    private var lastAppBlock = 0L

    private var evCount = 0
    private var lastPkg = ""

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString()
        evCount++
        if (pkg != packageName) lastPkg = pkg ?: ""
        if (pkg == packageName) return // أحداث الغطاء نفسه
        // تطبيقات ممنوعة بالكامل: يرجّع للشاشة الرئيسية أول ما تنفتح
        if (pkg != null && pkg in blockedApps) {
            val now = System.currentTimeMillis()
            if (now - lastAppBlock > 900) {
                lastAppBlock = now
                // رجوع أول (يقفل صفحة كيك)، بعدها الشاشة الرئيسية عشان يطلع من التطبيق كلياً
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 150)
            }
            return
        }
        if (pkg == YT && e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) handleClick(e)
        // أحداث الكيبورد/النظام ما تمسح الأغطية (كانت تمسحها بعد البحث)؛ update() يمسحها لو يوتيوب ما عاد ظاهر
        if (pkg != YT && covers.isEmpty()) return
        // throttle بدل debounce: التحديث يصير حتى لو يوتيوب يرسل أحداث بلا توقف
        if (!pending) { pending = true; handler.postDelayed(refresh, 80) }
    }

    private val watchKeys = listOf("watch_player", "watch_while", "player_view", "player_fragment", "watch_list")
    private val viewWords = listOf(" views", " view", " likes", " like", "مشاهده", "مشاهدات", "اعجاب").map { norm(it) }
    private var lastIds = ""

    private fun update() {
        val root = windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == YT }
            ?: rootInActiveWindow
        if (root == null || root.packageName?.toString() != YT) {
            saveDiag("no-youtube-window", "")
            clearCovers(); return
        }

        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() != YT) { clearCovers(); return }

        val rects = mutableListOf<Rect>()
        val menus = mutableListOf<AccessibilityNodeInfo?>()
        val keys = mutableListOf<String>()
        val ids = sortedSetOf<String>()
        var pageLevel = false
        var watchPage = false
        var watchHit = false
        var backLabel: String? = null
        val matched = mutableListOf<String>()
        val coverLabels = mutableListOf<String>()
        val screenH = resources.displayMetrics.heightPixels
        val screenRect = Rect(0, 0, resources.displayMetrics.widthPixels, screenH)

        fun collect(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.viewIdResourceName?.let { id ->
                ids.add(id)
                if (watchKeys.any { id.contains(it) }) watchPage = true
            }
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (t != null && matches(t)) {
                val nt = norm(t)
                val label = t.take(60).replace("\n", " ")
                // الـ @handle ما يظهر في الصفحة الرئيسية، فظهوره = فيديو/شورتس/قناة مفتوحة
                if (handles.any { nt.contains(it) }) {
                    watchHit = true
                    if (backLabel == null) backLabel = label
                    matched.add("BACK(handle) | $label")
                    return
                }
                var card: AccessibilityNodeInfo? = n
                while (card != null && !card.isClickable) card = card.parent
                if (card == null) {
                    // ما في عنصر قابل للضغط: نغطي النص نفسه فقط، بدون رجوع
                    val r0 = Rect()
                    n.getBoundsInScreen(r0)
                    if (!r0.isEmpty) { rects.add(r0); menus.add(null); keys.add(nt.take(40)) }
                    matched.add("TEXTONLY ${r0.toShortString()} | $label")
                    return
                }
                val r = Rect()
                card.getBoundsInScreen(r)
                if (r.height() > screenH * 0.7) {
                    pageLevel = true
                    if (backLabel == null) backLabel = label
                    matched.add("PAGELEVEL ${r.toShortString()} | $label")
                } else if (!r.isEmpty && Rect.intersects(r, screenRect)) {
                    rects.add(r)
                    menus.add(findMenu(card, r.height()))
                    keys.add(nt.take(40))
                    coverLabels.add(label)
                    matched.add("COVER ${r.toShortString()} menu=${menus.last() != null} | $label")
                } else {
                    matched.add("SKIP(offscreen/empty) ${r.toShortString()} | $label")
                }
                return
            }
            for (i in 0 until n.childCount) collect(n.getChild(i))
        }
        collect(root)

        // الفيديو/البث/الشورتس المعروض حالياً: لو عنوانه فيه اسم ممنوع نطلع منه
        val playing = detectWatching(root)
        if (playing != null && (matches(playing.first) || (playing.second?.let { matches(it) } == true))) {
            watchHit = true
            if (backLabel == null) backLabel = playing.first.take(60)
        }

        // سجل النشاط: نسجّل الرجوع لحظة وقوعه، وبطاقات التغطية الجديدة بس (مو كل تحديث)
        val curCoverKeys = keys.toMutableSet()
        for (i in keys.indices) {
            if (keys[i] !in lastCoverKeys) addActivity("إخفاء", coverLabels.getOrElse(i) { keys[i] })
        }
        lastCoverKeys = curCoverKeys

        saveDiag("ok watchHit=$watchHit watchPage=$watchPage pageLevel=$pageLevel covers=${rects.size}", ids.joinToString("\n"))

        // (أُلغي الكشف بمعرّفات العناصر: كان يطلعك من يوتيوب وانت بالصفحة الرئيسية)
        val openedBlockedVideo = false

        if (pageLevel || watchHit || openedBlockedVideo) {
            val now = System.currentTimeMillis()
            if (now - lastBack > 3000) {
                lastBack = now
                addActivity("حجب", backLabel ?: "-")
                // لو كان في مشغّل مصغّر قبل الرجوع (فيديو ثاني يشتغل) ما نلمسه
                val hadMini = findNode(root, closeWords, true) != null
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (!hadMini && (watchHit || openedBlockedVideo)) stopMiniPlayer()
            }
            clearCovers()
            return
        }
        trackWatching(root)
        showCovers(rects)

        // ضغط تلقائي على ⋮ ← "Don't recommend channel"
        if (autoDontRecommend && !busy && System.currentTimeMillis() - lastAuto > 2000) {
            val i = keys.indices.firstOrNull { menus[it] != null && keys[it] !in skipKeys }
            if (i != null) autoDontRecommend(menus[i]!!, keys[i])
        }
    }

    private val autoDontRecommend = true // false = غطاء فقط بدون ضغط تلقائي
    private var busy = false
    private var lastAuto = 0L
    private val skipKeys = mutableSetOf<String>()

    private val menuWords = listOf(
        "action menu", "more options", "المزيد من الخيارات", "قائمه الاجراءات"
    ).map { norm(it) }
    private val dontWords = listOf(
        "dont recommend channel", "عدم اقتراح هذه القناه", "عدم اقتراح القناه"
    ).map { norm(it) }

    private val notInterestedWords = listOf(
        "not interested", "غير مهتم", "لست مهتما", "لست مهتم"
    ).map { norm(it) }

    private fun findMenu(card: AccessibilityNodeInfo, cardH: Int): AccessibilityNodeInfo? {
        var a: AccessibilityNodeInfo? = card
        var depth = 0
        while (a != null && depth < 4) {
            val r = Rect()
            a.getBoundsInScreen(r)
            if (depth > 0 && r.height() > cardH * 1.5) break
            findNode(a, menuWords)?.let { return it }
            a = a.parent
            depth++
        }
        return null
    }

    private fun autoDontRecommend(menu: AccessibilityNodeInfo, key: String) {
        busy = true
        lastAuto = System.currentTimeMillis()
        if (!clickNode(menu)) {
            skipKeys.add(key); busy = false; return
        }
        var attempts = 0
        val r = object : Runnable {
            override fun run() {
                attempts++
                val roots = windows.mapNotNull { it.root }.filter { it.packageName?.toString() == YT }
                // الأفضلية لـ "Don't recommend channel"، وإذا ما ظهر بعد محاولتين نضغط "Not interested"
                val node = roots.firstNotNullOfOrNull { findNode(it, dontWords) }
                    ?: if (attempts >= 2) roots.firstNotNullOfOrNull { findNode(it, notInterestedWords) } else null
                if (node != null) {
                    clickNode(node)
                    skipKeys.add(key)
                    handler.postDelayed({ busy = false }, 1200)
                    return
                }
                if (attempts < 5) {
                    handler.postDelayed(this, 300)
                } else {
                    // ما لقينا الخيار: نسجّل محتوى القائمة ونسكّرها
                    logControls(roots.firstOrNull())
                    skipKeys.add(key)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({ busy = false }, 800)
                }
            }
        }
        handler.postDelayed(r, 400)
    }

    private val closeWords = listOf(
        "close miniplayer", "close mini player", "close mini-player",
        "اغلاق المشغل المصغر", "اغلاق المشغل الصغير"
    ).map { norm(it) }

    private fun findNode(n: AccessibilityNodeInfo?, words: List<String>, visibleOnly: Boolean = false): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = n.contentDescription?.toString() ?: n.text?.toString()
        if (t != null) {
            val nt = norm(t)
            if (words.any { nt.contains(it) } && (!visibleOnly || n.isVisibleToUser)) return n
        }
        for (i in 0 until n.childCount) findNode(n.getChild(i), words, visibleOnly)?.let { return it }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // بعد الرجوع يوتيوب يصغّر الفيديو ويكمل صوته. الاستراتيجيات بالترتيب:
    // 1) اسم الزر (closeWords) أو معرّف فيه mini+close  2) أقرب عنصر قابل للضغط يمين زر pause/play
    // 3) ضغطة بالإحداثيات يمين زر pause  4) سحب المشغّل المصغّر لليمين
    private fun stopMiniPlayer() {
        var attempts = 0
        var acted = false
        val r = object : Runnable {
            override fun run() {
                attempts++
                val root = windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == YT }
                if (root == null) {
                    if (attempts < 6) handler.postDelayed(this, 400)
                    return
                }
                if (attempts == 2) logMini(root) // دايم نسجّل شكل أسفل الشاشة بعد الرجوع
                val named = findNode(root, closeWords, true) ?: findCloseById(root)
                val pause = findMiniPause(root)
                if (named == null && pause == null) {
                    // ما في مشغّل مصغّر: إما انغلق (نجح) أو ما ظهر بعد
                    if (!acted && attempts < 6) handler.postDelayed(this, 400)
                    else if (!acted) logMini(root)
                    return
                }
                acted = true
                when {
                    named != null && attempts <= 2 -> clickNode(named)
                    pause != null && attempts <= 4 -> {
                        val btn = rightmostClickable(root, pause)
                        if (btn != null) clickNode(btn)
                        else if (attempts >= 3) {
                            val b = Rect(); pause.getBoundsInScreen(b)
                            tapAt(minOf(b.right + b.width() * 0.9f, resources.displayMetrics.widthPixels - 4f), b.centerY().toFloat())
                        }
                    }
                    pause != null && attempts <= 6 -> {
                        val b = Rect(); pause.getBoundsInScreen(b)
                        tapAt(minOf(b.right + b.width() * 0.9f, resources.displayMetrics.widthPixels - 4f), b.centerY().toFloat())
                    }
                    pause != null -> swipeAway(pause)
                }
                if (attempts < 9) handler.postDelayed(this, 700) else logMini(root)
            }
        }
        handler.postDelayed(r, 400)
    }

    private fun findCloseById(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val id = n.viewIdResourceName?.lowercase()
            if (id != null && id.contains("close") && id.contains("mini") && n.isVisibleToUser) { found = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found
    }

    // زر pause/play للمشغّل المصغّر: صغير وبأسفل الشاشة (يستبعد بطاقات الفيديو الكبيرة)
    private fun findMiniPause(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val t = n.contentDescription?.toString() ?: n.text?.toString()
            if (t != null) {
                val nt = norm(t)
                if (nt.contains("pause") || nt == "play video" || nt == "play") {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (!r.isEmpty && r.top > dm.heightPixels * 0.55 && r.width() < dm.widthPixels * 0.3 &&
                        r.height() < dm.heightPixels * 0.15) { found = n; return }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found
    }

    // أبعد عنصر قابل للضغط على يمين زر pause وبنفس الصف (غالباً زر X)
    private fun rightmostClickable(root: AccessibilityNodeInfo, pause: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val p = Rect(); pause.getBoundsInScreen(p)
        val sw = resources.displayMetrics.widthPixels
        var best: AccessibilityNodeInfo? = null
        var bestLeft = -1
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable && n != pause) {
                val r = Rect(); n.getBoundsInScreen(r)
                val cy = r.centerY()
                if (!r.isEmpty && r.left >= p.right - 4 && cy >= p.top - p.height() / 2 &&
                    cy <= p.bottom + p.height() / 2 && r.width() < sw * 0.3 && r.left > bestLeft) {
                    best = n; bestLeft = r.left
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(g, null, null)
    }

    private fun swipeAway(pause: AccessibilityNodeInfo) {
        val b = Rect(); pause.getBoundsInScreen(b)
        val w = resources.displayMetrics.widthPixels.toFloat()
        val y = b.centerY().toFloat()
        val path = Path().apply { moveTo(w * 0.35f, y); lineTo(w * 0.98f, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 180)).build()
        dispatchGesture(g, null, null)
    }

    // يسجّل كل عنصر بأسفل الشاشة (آخر 40%): الصنف، المعرّف، الوصف، النص، قابل للضغط، الحدود
    private fun logMini(root: AccessibilityNodeInfo?) {
        val sb = StringBuilder()
        val h = resources.displayMetrics.heightPixels
        var cnt = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || cnt > 60) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (!r.isEmpty && r.top >= h * 0.6 && (n.isClickable || n.contentDescription != null || n.text != null)) {
                cnt++
                sb.append(n.className?.toString()?.substringAfterLast('.') ?: "-")
                    .append(" id=").append(n.viewIdResourceName?.substringAfter(":id/") ?: "-")
                    .append(" d=").append(n.contentDescription?.toString()?.take(40) ?: "-")
                    .append(" t=").append(n.text?.toString()?.take(30) ?: "-")
                    .append(if (n.isClickable) " CLK " else " ")
                    .append(r.toShortString()).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
    }

    // للتشخيص: أسماء الأزرار اللي فيها mini/close/pause
    private fun logControls(root: AccessibilityNodeInfo?) {
        val found = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found.size > 40) return
            val t = n.contentDescription?.toString() ?: n.text?.toString()
            if (t != null) {
                val l = t.lowercase()
                if (l.contains("mini") || l.contains("close") || l.contains("pause") || l.contains("recommend") || l.contains("interested") || l.contains("report") || l.contains("المصغر") || l.contains("اغلاق") || l.contains("إغلاق"))
                    found.add(t.take(50))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
    }

    // ───────── سجل المشاهدة: أي فيديو يُفتح (حتى غير المحظور) ─────────
    private val sepRegex = Regex("\\s[-–—]\\s")
    private val cardHints = listOf(
        "play video", "play short", "views", "minute", "second", "hour",
        "تشغيل الفيديو", "مشاهده", "مشاهدات", "دقيقه", "دقائق", "دقيقتان", "ثانيه", "ثواني", "ثوان", "ساعه", "ساعات"
    ).map { norm(it) }

    private var lastWatchKey = ""
    private var lastWatchTime = 0L
    private var lastDetect = 0L
    private var candKey = ""
    private var candSince = 0L
    private var lastWatchDiag = ""
    private val recheck = Runnable { if (!pending) { pending = true; handler.post(refresh) } }

    // بطاقة فيديو في يوتيوب: وصفها يكون "العنوان - المدة - القناة - المشاهدات ..."
    private fun looksLikeVideoCard(d: String): Boolean {
        if (!sepRegex.containsMatchIn(d)) return false
        val nd = norm(d)
        return cardHints.any { nd.contains(it) }
    }

    private fun parseCard(d: String): Pair<String, String?> {
        val parts = d.split(sepRegex).map { it.trim() }.filter { it.isNotEmpty() }
        val title = (parts.firstOrNull() ?: d).take(110)
        val channel = parts.drop(1).firstOrNull { p ->
            val np = norm(p)
            p.none { it.isDigit() } && !np.contains("play") && !np.contains("تشغيل") &&
                !np.contains("views") && !np.contains("مشاهد") && p.length in 2..40
        }
        return title to channel
    }

    private fun handleClick(e: AccessibilityEvent) {
        var n: AccessibilityNodeInfo? = e.source
        var hops = 0
        while (n != null && hops < 6) {
            val d = n.contentDescription?.toString()
            if (d != null && looksLikeVideoCard(d)) {
                // القنوات المحظورة تُسجَّل كـ "حجب" من مسار الحجب نفسه
                if (!matches(d)) {
                    val (title, channel) = parseCard(d)
                    logWatch(title, channel)
                }
                return
            }
            n = n.parent
            hops++
        }
    }

    private fun logWatch(title: String, extra: String?) {
        val key = norm(title).take(60)
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val same = key == lastWatchKey && now - lastWatchTime < 10 * 60_000
        lastWatchKey = key
        lastWatchTime = now
        if (same) return
        addActivity("مشاهدة", if (extra.isNullOrBlank()) title else "$title — $extra")
    }

    // اكتشاف الفيديو المعروض حاليا من الشاشة نفسها (للتشغيل التلقائي والشورتس والروابط الخارجية)
    private fun trackWatching(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastDetect < 600) return
        lastDetect = now
        val found = detectWatching(root)
        if (found == null) {
            candKey = ""
            saveWatchDiag("لا يوجد فيديو معروض")
            return
        }
        val key = norm(found.first).take(60)
        if (key != candKey) {
            candKey = key
            candSince = now
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, 1300)
        } else if (now - candSince >= 1200) {
            logWatch(found.first, found.second)
        }
        saveWatchDiag(found.first + (found.second?.let { " | $it" } ?: ""))
    }

    private fun saveWatchDiag(s: String) {
        if (s == lastWatchDiag) return
        lastWatchDiag = s
    }

    private fun detectWatching(root: AccessibilityNodeInfo): Pair<String, String?>? {
        val dm = resources.displayMetrics
        val h = dm.heightPixels
        val w = dm.widthPixels
        var playerBottom = -1
        var reel = false
        val handlesFound = mutableListOf<String>()
        val reelTexts = mutableListOf<String>()
        val titles = mutableListOf<Pair<Int, String>>()

        fun walk(n: AccessibilityNodeInfo?, depth: Int, inCard: Boolean) {
            if (n == null || depth > 40) return
            val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase()
            val text = n.text?.toString()
            val card = inCard || (n.contentDescription?.toString()?.let { looksLikeVideoCard(it) } == true)
            if (id != null) {
                val r = Rect()
                if (id.contains("reel") || id.contains("shorts")) {
                    n.getBoundsInScreen(r)
                    if (n.isVisibleToUser && r.height() > h * 0.6) reel = true
                    if (!text.isNullOrBlank()) reelTexts.add(text.trim())
                }
                if (id.contains("watch_player") || id.contains("player_view")) {
                    n.getBoundsInScreen(r)
                    if (n.isVisibleToUser && r.width() > w * 0.5 && r.height() > h * 0.15) {
                        playerBottom = maxOf(playerBottom, r.bottom)
                    }
                }
                if (!card && id.contains("title") && !id.contains("subtitle") &&
                    !text.isNullOrBlank() && text.trim().length >= 3 && n.isVisibleToUser) {
                    n.getBoundsInScreen(r)
                    titles.add(r.top to text.trim())
                }
            }
            if (!text.isNullOrBlank() && text.startsWith("@") && text.length < 40 && n.isVisibleToUser) {
                handlesFound.add(text.trim())
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, card)
        }
        walk(root, 0, false)

        if (reel) {
            val handle = handlesFound.firstOrNull()
            val caption = reelTexts.firstOrNull { !it.startsWith("@") && it.length >= 8 && it.any { c -> !c.isDigit() } }
            val head = caption?.take(90) ?: handle ?: ""
            return ("شورتس" + if (head.isNotEmpty()) " · $head" else "") to (if (caption != null) handle else null)
        }
        if (playerBottom > 0) {
            val t = titles
                .filter { it.first >= playerBottom - 8 && it.first <= playerBottom + h * 0.40 }
                .minByOrNull { it.first }
            if (t != null) return t.second.take(110) to null
        }
        return null
    }

    private fun addActivity(type: String, label: String) {
        // لا يتم تسجيل أي نشاط
    }

    private fun saveDiag(status: String, ids: String) {
        // لا يتم حفظ أي بيانات
    }

    // لون الغطاء لازم يطابق ثيم يوتيوب نفسه (مو ثيم الجوال)، لأن يوتيوب له إعداد مستقل
    private var ytDark: Boolean? = null
    private var lastThemeCheck = 0L

    private fun systemDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun coverColor() = if (ytDark ?: systemDark()) Color.rgb(15, 15, 15) else Color.WHITE

    private fun detectYtTheme() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        if (now - lastThemeCheck < 3000) return
        lastThemeCheck = now
        val b = Rect()
        val yt = windows.firstOrNull { it.root?.packageName?.toString() == YT }
        if (yt != null) yt.getBoundsInScreen(b)
        else b.set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val margin = (12 * resources.displayMetrics.density).toInt()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    if (bmp != null) {
                        // نقطة بخلفية شريط يوتيوب السفلي (أقصى اليسار، بدون أيقونات)
                        val px = bmp.getPixel(3.coerceIn(0, bmp.width - 1), (b.bottom - margin).coerceIn(0, bmp.height - 1))
                        val lum = (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)) / 255.0
                        val dark = lum < 0.5
                        if (dark != ytDark) {
                            ytDark = dark
                            covers.forEach { it.setBackgroundColor(coverColor()) }
                        }
                    }
                } catch (_: Throwable) { }
            }
            override fun onFailure(errorCode: Int) { }
        })
    }

    private fun showCovers(rects: List<Rect>) {
        detectYtTheme()
        while (covers.size > rects.size) {
            runCatching { wm.removeView(covers.removeAt(covers.lastIndex)) }
        }
        rects.forEachIndexed { i, r ->
            val lp = WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (blockTouch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = r.left
                y = r.top
            }
            if (i < covers.size) {
                wm.updateViewLayout(covers[i], lp)
            } else {
                // نفس لون خلفية يوتيوب عشان يبان فراغ
                val v = View(this).apply {
                    setBackgroundColor(coverColor())
                }
                wm.addView(v, lp)
                covers.add(v)
            }
        }
    }

    private fun clearCovers() {
        handler.removeCallbacks(refresh)
        pending = false
        covers.forEach { runCatching { wm.removeView(it) } }
        covers.clear()
    }

    // أول ما يتفعّل إذن إمكانية الوصول: تختفي الأيقونة
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { IconToggle.set(this, false) }
    }

    // فتح التطبيق وهو مخفي: صوت-، صوت-، صوت+، صوت+، صوت- خلال 4 ثواني
    private val keySeq = ArrayDeque<Pair<Int, Long>>()
    private val openPattern = listOf(
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val now = System.currentTimeMillis()
        keySeq.addLast(code to now)
        while (keySeq.size > openPattern.size) keySeq.removeFirst()
        while (keySeq.isNotEmpty() && now - keySeq.first().second > 4000) keySeq.removeFirst()
        if (keySeq.map { it.first } == openPattern) {
            keySeq.clear()
            runCatching { IconToggle.set(this, true) }
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return false // الصوت يشتغل عادي
    }

    // لو الإذن انطفى: ترجع الأيقونة عشان ما ينقفل عليك التطبيق
    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { IconToggle.set(this, true) }
        clearCovers()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        clearCovers()
    }
}
