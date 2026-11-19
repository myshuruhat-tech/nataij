package com.example.ytblocker

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

// إعدادات التحكم: الرمز السري، تشغيل/إيقاف الحجب، وقوائم القنوات المحظورة القابلة للتعديل
object Control {

    private const val PREFS = "control"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val defaultHandles = listOf(
        "@d7oomy999", "@POWRibrahim", "@FRS9", "@FFearFFul", "@mjrmgames909",
        "@POWR_Rayan", "@iiGOATGamingg", "@POWR_ROB", "@xBear1", "@ABS11",
        "@Abo_treek1", "@Topz", "@Abunoo7", "@POWR_Rakan", "@Siiu-Team",
        "@POWR-Esports", "@SHoNgxxBoNg", "@xbear-plus", "@sxb3654",
        "@FRLplus", "@25KLOoODE", "@POWR.Kmstka", "@MSKplusss", "@Moskooo"
    )
    private val defaultNames = listOf(
        "d7oomy_999", "Ibrahim | ابو خليل", "Faris | فارس", "FFearFFul",
        "MjrmGames", "POWR Rayan", "iiGOATGaming", "POWR ROB",
        "ابو عابد الشمري", "Turki | ابو تريك", "TOPZ توبز", "ابو نوح",
        "POWR Rakan", "POWR eSports", "SHoNgxBoNg", "خلودي 25",
        "كمستكا", "MOSKOO"
    )
    private val defaultExact = listOf("ABS", "SXB", "F R S", "FRL+", "MSK+")

    // ───────── تشغيل/إيقاف الحجب ─────────
    fun isBlockingEnabled(ctx: Context) = p(ctx).getBoolean("blocking_enabled", true)
    fun setBlockingEnabled(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("blocking_enabled", v).apply(); Sync.push(ctx) }

    // ───────── إلغاء الاشتراك التلقائي ─────────
    fun isUnsubEnabled(ctx: Context) = p(ctx).getBoolean("unsub_enabled", true)
    fun setUnsubEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("unsub_enabled", v).apply()
    // القنوات اللي انفحصت/انلغى اشتراكها (مفتاح مطبَّع) عشان ما نعيد الفحص كل مرة
    fun isUnsubDone(ctx: Context, key: String): Boolean {
        val raw = p(ctx).getString("unsub_done", "") ?: ""
        return raw.split("\n").contains(key)
    }
    fun markUnsubDone(ctx: Context, key: String) {
        val raw = p(ctx).getString("unsub_done", "") ?: ""
        val l = raw.split("\n").filter { it.isNotBlank() }
        if (key in l) return
        p(ctx).edit().putString("unsub_done", (l + key).joinToString("\n")).apply()
    }
    fun clearUnsubDone(ctx: Context) = p(ctx).edit().remove("unsub_done").apply()

    // وضع "تحكم فقط": التطبيق ما يحجب على هذا الجهاز، بس يتحكم بالقوائم ويزامنها
    fun isControlOnly(ctx: Context) = p(ctx).getBoolean("control_only", false)
    fun setControlOnly(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("control_only", v).apply()

    // فحص صفحة الاشتراكات (يشتغل مرة وحدة لين يخلص أو تلغيه)
    fun isScanActive(ctx: Context) = p(ctx).getBoolean("scan_active", false)
    fun setScanActive(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("scan_active", v).apply()

    // ───────── القوائم المحظورة (لكل جهاز قوائمه المنفصلة) ─────────
    // الجهاز المفتوح حالياً بالتطبيق. فاضي = القوائم العامة القديمة.
    @Volatile var dev: String = ""
    private fun k(key: String, d: String) = if (d.isBlank()) key else "d:$d:$key"

    private fun getList(ctx: Context, key: String, def: List<String>, d: String): List<String> {
        // قوائم هذا الجهاز أول، وإذا ما انحفظ له شي نبدأ من القوائم العامة القديمة
        val raw = p(ctx).getString(k(key, d), null) ?: p(ctx).getString(key, null)
        if (raw == null) { setList(ctx, key, def, d); return def }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { def }
    }
    private fun setList(ctx: Context, key: String, list: List<String>, d: String = dev) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        p(ctx).edit().putString(k(key, d), arr.toString()).apply()
    }

    fun getHandles(ctx: Context, d: String = dev) = getList(ctx, "blocked_handles", defaultHandles, d)
    fun setHandles(ctx: Context, l: List<String>) { setList(ctx, "blocked_handles", l); Sync.push(ctx) }
    fun getNames(ctx: Context, d: String = dev) = getList(ctx, "blocked_names", defaultNames, d)
    fun setNames(ctx: Context, l: List<String>) { setList(ctx, "blocked_names", l); Sync.push(ctx) }
    fun getExact(ctx: Context, d: String = dev) = getList(ctx, "blocked_exact", defaultExact, d)
    fun setExact(ctx: Context, l: List<String>) { setList(ctx, "blocked_exact", l); Sync.push(ctx) }

    // ───────── قاموس أسماء التطبيقات الشائعة (عشان تكتبي الاسم بدل معرّف الحزمة) ─────────
    data class KnownApp(val label: String, val pkg: String, val aliases: List<String>)
    val KNOWN_APPS = listOf(
        KnownApp("يوتيوب", "com.google.android.youtube", listOf("يوتيوب", "youtube")),
        KnownApp("كروم", "com.android.chrome", listOf("كروم", "chrome")),
        KnownApp("سناب شات", "com.snapchat.android", listOf("سناب", "سناب شات", "سنابشات", "snap", "snapchat")),
        KnownApp("تيك توك", "com.zhiliaoapp.musically", listOf("تيكتوك", "تيك توك", "تيك", "tiktok", "tik tok")),
        KnownApp("انستقرام", "com.instagram.android", listOf("انستا", "انستجرام", "انستقرام", "instagram", "insta")),
        KnownApp("اكس (تويتر)", "com.twitter.android", listOf("تويتر", "اكس", "x", "twitter")),
        KnownApp("واتساب", "com.whatsapp", listOf("واتس", "واتساب", "whatsapp", "whats app")),
        KnownApp("تيليجرام", "org.telegram.messenger", listOf("تيليجرام", "تلقرام", "telegram")),
        KnownApp("فيسبوك", "com.facebook.katana", listOf("فيسبوك", "فيس بوك", "فيس", "facebook", "fb")),
        KnownApp("ماسنجر", "com.facebook.orca", listOf("ماسنجر", "messenger")),
        KnownApp("ديسكورد", "com.discord", listOf("ديسكورد", "discord")),
        KnownApp("ريديت", "com.reddit.frontpage", listOf("ريديت", "reddit")),
        KnownApp("بينتيريست", "com.pinterest", listOf("بينتيريست", "pinterest")),
        KnownApp("جوجل", "com.google.android.googlequicksearchbox", listOf("جوجل", "google")),
        KnownApp("كيك", "com.kick.mobile", listOf("كيك", "kick")),
    )
    private fun normName(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    // تدور على تطبيق بالاسم (عربي أو إنجليزي، جزء من الاسم يكفي). ترجع null لو ما لقت شي.
    fun resolveAppByName(query: String): KnownApp? {
        val q = normName(query)
        if (q.isEmpty()) return null
        return KNOWN_APPS.firstOrNull { app -> app.aliases.any { normName(it) == q } }
            ?: KNOWN_APPS.firstOrNull { app -> app.aliases.any { normName(it).contains(q) || q.contains(normName(it)) } }
    }
    // اسم جميل لمعرّف حزمة معروف عندنا (للعرض لو التطبيق مو مثبّت بجوال الأم)
    fun friendlyLabel(pkg: String): String? = KNOWN_APPS.firstOrNull { it.pkg == pkg }?.label

    // ───────── تطبيقات تُحجب بالكامل (زي كيك) وتطبيقات يُفحص بداخلها نص القوائم أعلاه ─────────
    private val defaultFullBlockApps = listOf("com.kick.mobile", "com.kick.streaming")
    private val defaultScanApps = listOf("com.google.android.youtube")

    // حماية: ممنوع تنحجب أو تُفحص هذي الحزم حتى لو انكتب معرّفها بالغلط —
    // حجب شاشة الهوم (launcher) أو النظام يخرب تنقّل الجهاز كله (مثل مشكلة الصفحة ١/٢/٣)
    private val neverTouch = setOf(
        "com.android.launcher", "com.android.launcher2", "com.android.launcher3",
        "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher",
        "com.miui.home", "com.mi.android.globallauncher", "com.huawei.android.launcher",
        "com.oppo.launcher", "com.coloros.launcher", "com.vivo.launcher",
        "com.bbk.launcher2", "net.oneplus.launcher", "com.oneplus.launcher",
        "com.transsion.XOSLauncher", "com.sonyericsson.home", "com.lge.launcher2",
        "com.lge.launcher3", "com.motorola.launcher3", "com.asus.launcher",
        "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer", "com.android.contacts",
        "com.android.documentsui", "com.example.ytblocker"
    )
    fun isDangerousPackage(pkg: String): Boolean {
        val p = pkg.trim().lowercase()
        return p in neverTouch || p.contains("launcher") || p.endsWith(".home")
    }

    fun getFullBlockApps(ctx: Context, d: String = dev) = getList(ctx, "full_block_apps", defaultFullBlockApps, d)
    fun setFullBlockApps(ctx: Context, l: List<String>) { setList(ctx, "full_block_apps", l.filterNot { isDangerousPackage(it) }); Sync.push(ctx) }
    fun getScanApps(ctx: Context, d: String = dev) = getList(ctx, "scan_apps", defaultScanApps, d)
    fun setScanApps(ctx: Context, l: List<String>) { setList(ctx, "scan_apps", l.filterNot { isDangerousPackage(it) }); Sync.push(ctx) }

    // ───────── تطبيقات "سجل فقط": كل فيديو يشاهده وكل بحث يطلع بالنشاط (محجوب أو لا)، بدون أي حجب أو إخفاء ─────────
    fun getLogApps(ctx: Context, d: String = dev) = getList(ctx, "log_apps", emptyList(), d)
    fun setLogApps(ctx: Context, l: List<String>) { setList(ctx, "log_apps", l.filterNot { isDangerousPackage(it) }); Sync.push(ctx) }

    // ───────── تطبيقات يُسجَّل بحثها فقط (صندوق البحث داخلها، مو أي حقل نص) ─────────
    // الافتراضي: يوتيوب + كروم + تطبيق جوجل. أمي تقدر تضيف أي تطبيق ثاني بمعرّف حزمته.
    private val defaultSearchApps = listOf(
        "com.google.android.youtube",
        "com.android.chrome",
        "com.google.android.googlequicksearchbox"
    )
    fun getSearchApps(ctx: Context, d: String = dev) = getList(ctx, "search_apps", defaultSearchApps, d)
    fun setSearchApps(ctx: Context, l: List<String>) { setList(ctx, "search_apps", l); Sync.push(ctx) }

    // رسالة تحذير تطلع لابنك أول ما نرجّعه عن فيديو أو تطبيق محظور
    fun getWarningMessage(ctx: Context, d: String = dev): String =
        p(ctx).getString(k("warning_message", d), null) ?: p(ctx).getString("warning_message", "") ?: ""
    fun setWarningMessage(ctx: Context, v: String) { p(ctx).edit().putString(k("warning_message", dev), v).apply(); Sync.push(ctx) }

    // ───────── رمز الدخول (PIN) ─────────
    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
    fun hasPin(ctx: Context) = p(ctx).getString("pin_hash", null) != null
    fun setPin(ctx: Context, pin: String) {
        p(ctx).edit().putString("pin_hash", sha256(pin)).apply()
        resetFails(ctx)
    }
    fun checkPin(ctx: Context, pin: String) = p(ctx).getString("pin_hash", null) == sha256(pin)

    fun failCount(ctx: Context) = p(ctx).getInt("fail_count", 0)
    fun registerFail(ctx: Context): Int {
        val n = failCount(ctx) + 1
        p(ctx).edit().putInt("fail_count", n).putLong("lock_until", System.currentTimeMillis() + lockSeconds(n) * 1000L).apply()
        return n
    }
    fun resetFails(ctx: Context) = p(ctx).edit().putInt("fail_count", 0).putLong("lock_until", 0).apply()
    fun lockRemainingSeconds(ctx: Context): Long {
        val until = p(ctx).getLong("lock_until", 0)
        val rem = (until - System.currentTimeMillis()) / 1000
        return if (rem > 0) rem else 0
    }
    private fun lockSeconds(failCount: Int): Int = when {
        failCount < 3 -> 0
        failCount < 6 -> 30
        failCount < 9 -> 120
        else -> 300
    }
}
