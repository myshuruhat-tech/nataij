package com.example.ytblocker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ───────── Search log: records the last stable text in a named search/address box, inside an allowed app only ─────────
// This is not a general keylogger: three constraints actually limit the scope:
//  1) The app must be in Control.getSearchApps (YouTube/Chrome/Google by default, or any app added by name via admin)
//  2) The field itself must be a "search/address box" by its resource-id or hint, not any random EditText
//  3) We log the text after it stabilizes for 1.5s (i.e. after the user finishes typing), not every keystroke
// Note: in Chrome this includes full URLs typed in the address bar (url_bar), not just search terms
object SearchLog {

    private val searchIdHints = listOf(
        "search_edit_text", "search_src_text", "search_box", "searchbox",
        "omnibox", "url_bar", "search_plate", "search_text"
    )
    private val searchHintWords = listOf(
        "search", "search", "search youtube", "search"
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

    // Called from BlockerService on every text-change event, for the currently focused field only
    // Logs any stable text in the field (search or full address-bar URL), as long as the field and app are in the allowed scope above
    fun onFocusedTextChanged(ctx: Context, pkg: String, node: AccessibilityNodeInfo, allowedApps: Set<String>) {
        if (pkg !in allowedApps) { reset(); return }
        if (!looksLikeSearchField(node)) { reset(); return }
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isEmpty() || text.length < 2) { reset(); return }

        val now = System.currentTimeMillis()
        if (pkg != candApp || text != candText) {
            candApp = pkg; candText = text; candSince = now
            handler.removeCallbacksAndMessages(STABLE_TOKEN)
            // postAtTime uses device uptime, not real time, otherwise it never fires
            handler.postAtTime({ trySend(ctx) }, STABLE_TOKEN, SystemClock.uptimeMillis() + 1500)
        }
    }

    private val STABLE_TOKEN = Any()

    private fun trySend(ctx: Context) {
        val app = candApp; val text = candText
        if (app.isEmpty() || text.isEmpty()) return
        val now = System.currentTimeMillis()
        // Don't repeat the same search from the same app within two minutes (reduces noise if the same field re-fires)
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
                    .put("device", DeviceSync.deviceId(ctx)).put("deviceName", DeviceSync.deviceName())
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
