package com.example.ytblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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

    // القوائم تُحمَّل من Control (تُحدَّث من شاشة أمي) وتُخزَّن مؤقتاً لتفادي قراءة JSON بكل حدث
    private var listsLoadedAt = 0L
    private var handles: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var exact: Set<String> = emptySet()

    private fun refreshListsIfNeeded() {
        val now = System.currentTimeMillis()
        if (listsLoadedAt != 0L && now - listsLoadedAt < 2000) return
        listsLoadedAt = now
        handles = Control.getHandles(this).map { norm(it) }
        names = Control.getNames(this).map { norm(it) }
        exact = Control.getExact(this).map { norm(it) }.toSet()
    }

    private val YT = "com.google.android.youtube"
    private val blockTouch = false // true = يمنع الضغط على المخفي، لكن يمنع السكرول لو بدأ عليه
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val covers = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        DeviceWatch.start(applicationContext)
    }
    private var pending = false
    private val refresh = Runnable { pending = false; update() }
    private var lastBack = 0L
    private var lastCoverKeys = mutableSetOf<String>()
    private val phoneName = (Build.MANUFACTURER + " " + Build.MODEL).trim()

    private fun matches(raw: String): Boolean {
        refreshListsIfNeeded()
        val t = norm(raw)
        return t in exact || handles.any { t.contains(it) } || names.any { t.contains(it) }
    }

    private var evCount = 0
    private var lastPkg = ""

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString()
        evCount++
        if (pkg != packageName) lastPkg = pkg ?: ""
        if (pkg == packageName) return // أحداث الغطاء نفسه
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
        if (Control.isControlOnly(this)) { clearCovers(); return } // هذا الجهاز للتحكم بس، ما يحجب
        if (Control.isScanActive(this)) {
            // وضع فحص الاشتراكات: المؤقّت يتحكم، ما نسوي رجوع ولا تغطية
            clearCovers()
            if (!scanTicking) { scanTicking = true; handler.postDelayed(scanTick, 1500) }
            return
        }
        if (!Control.isBlockingEnabled(this)) {
            // الحجب موقوف من شاشة أمي: نمسح أي غطاء قديم ونكتفي بتسجيل المشاهدة
            clearCovers()
            trackWatching(root)
            return
        }

        // أثناء إلغاء الاشتراك ما نسوي أي رجوع/تغطية (القوائم المنبثقة تفتح وتقفل)
        if (unsubBusy) {
            if (System.currentTimeMillis() - unsubStarted < 15000) return
            unsubBusy = false
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
        var backKey: String? = null
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
                    if (backLabel == null) { backLabel = label; backKey = handles.firstOrNull { nt.contains(it) } }
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
                    if (backLabel == null) {
                        backLabel = label
                        backKey = handles.firstOrNull { nt.contains(it) } ?: names.firstOrNull { nt.contains(it) } ?: exact.firstOrNull { nt == it } ?: nt.take(40)
                    }
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

        // سجل النشاط: نسجّل الرجوع لحظة وقوعه، وبطاقات التغطية الجديدة بس (مو كل تحديث)
        val curCoverKeys = keys.toMutableSet()
        for (i in keys.indices) {
            if (keys[i] !in lastCoverKeys) {
                val lbl = coverLabels.getOrElse(i) { keys[i] }
                addActivity("إخفاء", lbl, if (norm(lbl).startsWith(norm("شورتس"))) "شورت" else "فيديو", lbl)
            }
        }
        lastCoverKeys = curCoverKeys

        saveDiag("ok watchHit=$watchHit watchPage=$watchPage pageLevel=$pageLevel covers=${rects.size}", ids.joinToString("\n"))
        if (matched.isNotEmpty()) {
            getSharedPreferences("diag", Context.MODE_PRIVATE).edit()
                .putString("matches", matched.joinToString("\n")).apply()
        }

        // (أُلغي الكشف بمعرّفات العناصر: كان يطلعك من يوتيوب وانت بالصفحة الرئيسية)
        val openedBlockedVideo = false

        if (pageLevel || watchHit || openedBlockedVideo) {
            // قبل الرجوع: نتحقق من الاشتراك وإذا مشترك نلغيه
            if (backKey != null && handleUnsub(root, backKey!!, backLabel ?: "-")) {
                clearCovers()
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastBack > 3000) {
                lastBack = now
                val bl = backLabel ?: "-"
                addActivity("حجب", bl, if (norm(bl).startsWith(norm("شورتس"))) "شورت" else "فيديو", bl)
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


    // ───────── إلغاء الاشتراك من القنوات المحظورة ─────────
    private var unsubBusy = false
    private var unsubStarted = 0L
    private val unsubTries = mutableMapOf<String, Int>()
    private val plainSubWords = listOf("subscribe", "subscribed", "اشترك", "مشترك", "تم الاشتراك").map { norm(it) }
    private val unsubExact = listOf("unsubscribe", "الغاء الاشتراك").map { norm(it) }

    // null = ما لقينا الزر؛ true = مشترك؛ false = غير مشترك
    private fun findSubButton(roots: List<AccessibilityNodeInfo>): Pair<AccessibilityNodeInfo, Boolean>? {
        var res: Pair<AccessibilityNodeInfo, Boolean>? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || res != null) return
            val raw = n.contentDescription?.toString() ?: n.text?.toString()
            if (raw != null && n.isVisibleToUser) {
                val nt = norm(raw)
                val subscribed = nt.startsWith("unsubscribe from") || nt == "subscribed" || nt.startsWith("subscribed to") ||
                    nt == norm("مشترك") || nt == norm("تم الاشتراك") || nt.startsWith(norm("الغاء الاشتراك من"))
                val notSub = nt == "subscribe" || nt.startsWith("subscribe to") || nt == norm("اشترك") || nt.startsWith(norm("الاشتراك في"))
                if (subscribed || notSub) {
                    // لو الزر يذكر اسم قناة لازم تكون من المحظورة (حماية من وصف فيه @handle لقناة ثانية)
                    val hasName = nt !in plainSubWords
                    if (!hasName || matches(raw)) { res = n to subscribed; return }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        roots.forEach { walk(it) }
        return res
    }

    private fun ytRoots() = windows.mapNotNull { it.root }.filter { it.packageName?.toString() == YT }

    // يرجع true = العملية شغالة/ننتظر، لا ترجع الحين. false = كملنا، ارجع عادي
    private fun handleUnsub(root: AccessibilityNodeInfo, key: String, label: String): Boolean {
        if (!Control.isUnsubEnabled(this)) return false
        if (Control.isUnsubDone(this, key)) return false
        if (unsubBusy) return true
        val kind = if (norm(label).startsWith(norm("شورتس"))) "شورت" else "فيديو"
        val found = findSubButton(listOf(root))
        if (found == null) {
            val tries = unsubTries[key] ?: 0
            if (tries >= 6) {
                unsubTries[key] = 0
                addActivity("تعذر التحقق", label, kind, label)
                return false
            }
            unsubTries[key] = tries + 1
            handler.postDelayed(recheck, 600)
            return true
        }
        unsubTries[key] = 0
        val (btn, subscribed) = found
        if (!subscribed) {
            Control.markUnsubDone(this, key)
            addActivity("غير مشترك", label, kind, label)
            return false
        }
        // مشترك: نسجّل أول، بعدين نلغي
        addActivity("كان مشترك", label, kind, label)
        unsubBusy = true
        unsubStarted = System.currentTimeMillis()
        if (!clickNode(btn)) {
            unsubBusy = false
            addActivity("تعذر الإلغاء", label, kind, label)
            return false
        }
        var attempts = 0
        var clicked = 0
        val step = object : Runnable {
            override fun run() {
                attempts++
                val roots = ytRoots()
                val item = roots.firstNotNullOfOrNull { r -> findExact(r, unsubExact) }
                if (item != null && clicked < 2) {
                    clickNode(item); clicked++
                    handler.postDelayed(this, 700)
                    return
                }
                if (clicked == 0 && attempts < 8) { handler.postDelayed(this, 400); return }
                // تحقق: هل صار الزر "Subscribe"؟
                val after = findSubButton(ytRoots())
                if (after != null && !after.second) {
                    Control.markUnsubDone(this@BlockerService, key)
                    addActivity("ألغيت الاشتراك", label, kind, label)
                } else if (after == null && clicked > 0) {
                    // ما قدرنا نقرأ الزر بعد الضغط؛ نعتبرها تمت لو ضغطنا خيار الإلغاء
                    Control.markUnsubDone(this@BlockerService, key)
                    addActivity("ألغيت الاشتراك", label, kind, label)
                } else {
                    logControls(roots.firstOrNull())
                    addActivity("تعذر الإلغاء", label, kind, label)
                    performGlobalAction(GLOBAL_ACTION_BACK) // نقفل أي قائمة مفتوحة
                }
                unsubBusy = false
                handler.postDelayed(recheck, 900)
            }
        }
        handler.postDelayed(step, 600)
        return true
    }

    private fun findExact(n: AccessibilityNodeInfo?, words: List<String>): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = n.text?.toString() ?: n.contentDescription?.toString()
        if (t != null && n.isVisibleToUser && norm(t) in words) return n
        for (i in 0 until n.childCount) findExact(n.getChild(i), words)?.let { return it }
        return null
    }


    // ───────── فحص صفحة الاشتراكات: يلغي كل القنوات المحظورة مرة وحدة ─────────
    private var scanTicking = false
    private var scanBusy = false
    private var scanInit = false
    private var scanSeen = mutableSetOf<String>()
    private var scanChecked = 0
    private var scanUnsubbed = 0
    private var scanFailed = 0
    private var scanStuck = 0
    private var scanNoYt = 0
    private var scanLastSig = ""
    private var scanLoggedFail = false
    private val subCtlWords = listOf("subscribed", "notification", "unsubscribe", "مشترك", "تم الاشتراك", "اشعار").map { norm(it) }

    private fun finishScan(reason: String) {
        Control.setScanActive(this, false)
        scanTicking = false; scanInit = false; scanBusy = false
        addActivity("انتهى الفحص", "$reason — ألغيت $scanUnsubbed، فشلت $scanFailed، من $scanChecked قناة محظورة", "", "")
        handler.post { android.widget.Toast.makeText(this, "انتهى فحص الاشتراكات: ألغيت $scanUnsubbed", android.widget.Toast.LENGTH_LONG).show() }
    }

    // صف قناة محظورة ما انعالج: يرجع (اسم القناة، زر الاشتراك) أو (اسم، null) لو ما لقينا الزر
    private fun findBlockedRow(root: AccessibilityNodeInfo): Pair<String, AccessibilityNodeInfo?>? {
        val sh = resources.displayMetrics.heightPixels
        var res: Pair<String, AccessibilityNodeInfo?>? = null
        fun ctlIn(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val t = n.contentDescription?.toString() ?: n.text?.toString()
            if (t != null && n.isVisibleToUser) {
                val nt = norm(t)
                if (subCtlWords.any { nt.contains(it) }) {
                    var c: AccessibilityNodeInfo? = n
                    while (c != null && !c.isClickable) c = c.parent
                    if (c != null) return c
                }
            }
            for (i in 0 until n.childCount) ctlIn(n.getChild(i))?.let { return it }
            return null
        }
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || res != null) return
            val t = n.text?.toString()
            if (t != null && n.isVisibleToUser && matches(t)) {
                val key = norm(t).take(40)
                if (key !in scanSeen) {
                    scanSeen.add(key)
                    var a: AccessibilityNodeInfo? = n
                    var depth = 0
                    var ctl: AccessibilityNodeInfo? = null
                    while (a != null && depth < 5 && ctl == null) {
                        val r = Rect(); a.getBoundsInScreen(r)
                        if (depth > 0 && r.height() > sh * 0.35) break
                        ctl = ctlIn(a)
                        a = a.parent; depth++
                    }
                    res = t.take(60).replace("\n", " ") to ctl
                    return
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return res
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestH = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isScrollable && n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.height() > bestH) { best = n; bestH = r.height() }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun screenSig(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 600) return
            val t = n.text?.toString()
            if (t != null && n.isVisibleToUser) sb.append(t.take(20)).append('|')
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun postScanTick(delayMs: Long) { handler.postDelayed(scanTick, delayMs) }

    private val scanTick: Runnable = object : Runnable {
        override fun run() {
            if (!Control.isScanActive(this@BlockerService)) { scanTicking = false; scanInit = false; return }
            if (!scanInit) {
                scanInit = true; scanSeen = mutableSetOf(); scanChecked = 0; scanUnsubbed = 0; scanFailed = 0
                scanStuck = 0; scanNoYt = 0; scanLastSig = ""; scanLoggedFail = false
                addActivity("بدأ الفحص", "فحص صفحة الاشتراكات", "", "")
            }
            if (scanBusy) { handler.postDelayed(this, 700); return }
            val roots = ytRoots()
            val root = roots.firstOrNull()
            if (root == null) {
                if (++scanNoYt > 20) { finishScan("يوتيوب مو ظاهر"); return }
                handler.postDelayed(this, 1000); return
            }
            scanNoYt = 0
            val row = findBlockedRow(root)
            if (row != null) {
                val (label, ctl) = row
                scanChecked++
                if (ctl == null) {
                    scanFailed++
                    addActivity("تعذر الإلغاء", label, "", label)
                    if (!scanLoggedFail) { scanLoggedFail = true; logControls(root) }
                    handler.postDelayed(this, 700); return
                }
                addActivity("كان مشترك", label, "", label)
                scanBusy = true
                if (!clickNode(ctl)) {
                    scanBusy = false; scanFailed++
                    addActivity("تعذر الإلغاء", label, "", label)
                    handler.postDelayed(this, 700); return
                }
                var attempts = 0
                var clicked = 0
                val step = object : Runnable {
                    override fun run() {
                        attempts++
                        val item = ytRoots().firstNotNullOfOrNull { r -> findExact(r, unsubExact) }
                        if (item != null && clicked < 2) {
                            clickNode(item); clicked++
                            handler.postDelayed(this, 800); return
                        }
                        if (clicked == 0 && attempts < 8) { handler.postDelayed(this, 500); return }
                        if (clicked > 0) {
                            scanUnsubbed++
                            addActivity("ألغيت الاشتراك", label, "", label)
                        } else {
                            scanFailed++
                            logControls(ytRoots().firstOrNull())
                            addActivity("تعذر الإلغاء", label, "", label)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        scanBusy = false
                        scanStuck = 0
                        postScanTick(1000)
                    }
                }
                handler.postDelayed(step, 700)
                return
            }
            // ما في صف محظور ظاهر: ننزل بالقائمة
            val sig = screenSig(root)
            if (sig == scanLastSig) scanStuck++ else { scanStuck = 0; scanLastSig = sig }
            if (scanStuck >= 3) { finishScan("وصلنا آخر القائمة"); return }
            val sc = findScrollable(root)
            if (sc == null) { finishScan("ما لقيت قائمة تتحرك (تأكد إنك بصفحة كل الاشتراكات)"); return }
            sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            handler.postDelayed(this, 1200)
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
        getSharedPreferences("diag", Context.MODE_PRIVATE).edit()
            .putString("mini", sb.toString().ifEmpty { "-" }).apply()
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
        getSharedPreferences("diag", Context.MODE_PRIVATE).edit()
            .putString("controls", found.joinToString("\n").ifEmpty { "-" }).apply()
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
        val kind = if (norm(title).startsWith(norm("شورتس"))) "شورت" else "فيديو"
        addActivity("مشاهدة", if (extra.isNullOrBlank()) title else "$title — $extra", kind, extra ?: "")
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
        getSharedPreferences("diag", Context.MODE_PRIVATE).edit().putString("watching", s).apply()
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

    private fun addActivity(type: String, label: String, kind: String = "", channel: String = "") {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safe = { s: String -> s.replace("\n", " ").replace("|", "/") }
        // صيغة جديدة بـ 6 حقول: وقت | جهاز | نوع الحدث | فيديو_او_شورت | القناة | العنوان
        val line = "$ts | $phoneName | $type | $kind | ${safe(channel)} | ${safe(label)}"
        val sp = getSharedPreferences("diag", Context.MODE_PRIVATE)
        val cur = sp.getString("activitylog", "") ?: ""
        val lines = (listOf(line) + cur.split("\n").filter { it.isNotBlank() }).take(500)
        sp.edit().putString("activitylog", lines.joinToString("\n")).apply()
    }

    private fun saveDiag(status: String, ids: String) {
        val sp = getSharedPreferences("diag", Context.MODE_PRIVATE)
        val st = "events=$evCount lastPkg=$lastPkg state=$status"
        val e = sp.edit().putString("status", st)
        if (ids.isNotEmpty() && ids != lastIds) { lastIds = ids; e.putString("ids", ids) }
        e.apply()
    }

    private fun showCovers(rects: List<Rect>) {
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
                val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                val v = View(this).apply {
                    setBackgroundColor(if (dark) Color.rgb(15, 15, 15) else Color.WHITE)
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

    override fun onUnbind(intent: Intent?): Boolean {
        clearCovers()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        clearCovers()
    }
}
