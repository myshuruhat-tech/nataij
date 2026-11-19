package com.example.ytblocker
import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView.ScaleType
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// تفتح من زر "شاهد شاشته الآن" بجوار جهاز معيّن في قائمة الأجهزة
class ScreenViewActivity : Activity() {
    private var deviceId: String = ""
    private var running = true
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var img: ImageView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("device") ?: ""

        img = ImageView(this).apply { scaleType = ScaleType.FIT_CENTER; adjustViewBounds = false; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            text = "جاري إرسال الطلب..."
            setPadding(30, 20, 30, 20)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val stop = Button(this).apply {
            text = "إيقاف المشاهدة"
            setOnClickListener { running = false; Sync.stopScreen(this@ScreenViewActivity, deviceId); finish() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            // نترك مكان لشريط الحالة عشان النص ما يختفي تحته
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 80, 0, 0)
            addView(status)
            addView(FrameLayout(this@ScreenViewActivity).apply {
                addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(stop)
        }
        setContentView(root)

        if (deviceId.isBlank()) { status.text = "ما تحدد الجهاز"; return }
        sendRequest()
        pollLoop()
    }

    private fun base() = Sync.url(this)
    private fun pair() = Sync.pair(this)
    private fun adminKey() = Sync.key(this)

    private fun sendRequest() {
        Thread {
            try {
                val c = URL("${base()}/screen?pair=${pair()}&device=$deviceId").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.setRequestProperty("x-admin-key", adminKey())
                c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
                c.outputStream.use { it.write(ByteArray(0)) }
                val ok = c.responseCode in 200..299
                handler.post { status.text = if (ok) "بانتظار موافقته على جهازه..." else "تعذّر إرسال الطلب" }
            } catch (e: Exception) { handler.post { status.text = "خطأ بالاتصال" } }
        }.start()
    }

    private var lastLiveSent = 0L
    private fun pollLoop() {
        if (!running) return
        Thread {
            try {
                val now = System.currentTimeMillis()
                if (now - lastLiveSent > 6000) {
                    lastLiveSent = now
                    val cl = URL("${base()}/screen/live?pair=${pair()}&device=$deviceId").openConnection() as HttpURLConnection
                    cl.requestMethod = "POST"; cl.setRequestProperty("x-admin-key", adminKey())
                    cl.doOutput = true; cl.connectTimeout = 6000; cl.readTimeout = 6000
                    cl.outputStream.use { it.write(ByteArray(0)) }
                    cl.responseCode
                }
                val c = URL("${base()}/screen/frame?pair=${pair()}&device=$deviceId").openConnection() as HttpURLConnection
                c.setRequestProperty("x-admin-key", adminKey())
                c.connectTimeout = 6000; c.readTimeout = 6000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val jpg = JSONObject(txt).optString("jpg", "")
                if (jpg.isNotBlank()) {
                    val bytes = Base64.decode(jpg, Base64.NO_WRAP)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    handler.post { if (running) { img.setImageBitmap(bmp); status.text = "مباشر الآن" } }
                } else {
                    handler.post { if (running) status.text = "ماتوفر صورة بعد — يمكن ما وافق أو البث توقف" }
                }
            } catch (e: Exception) {
                handler.post { if (running) status.text = "انقطع الاتصال، بنحاول ثاني..." }
            }
            if (running) handler.postDelayed({ pollLoop() }, 900)
        }.start()
    }

    override fun onDestroy() { running = false; Sync.stopScreen(this, deviceId); super.onDestroy() }
}
