package com.example.ytblocker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل بسيط داخل التطبيق: يكتب بملف diag.log ما يعتمد على Toast ولا logcat. */
object DiagLog {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "diag.log")

    @Synchronized
    fun log(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > 100_000) {
                val keep = f.readLines().takeLast(200).joinToString("\n") + "\n"
                f.writeText(keep)
            }
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Throwable) {
        }
    }

    fun err(ctx: Context, where: String, t: Throwable) {
        log(ctx, "ERR $where: ${t.javaClass.simpleName}: ${t.message}")
    }

    @Synchronized
    fun tail(ctx: Context, n: Int = 30): String = try {
        val f = file(ctx)
        if (!f.exists()) "(السجل فاضي — ولا شي انكتب لحد الحين)"
        else f.readLines().takeLast(n).joinToString("\n")
    } catch (t: Throwable) {
        "تعذر قراءة السجل: ${t.message}"
    }

    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Throwable) {}
    }
}
