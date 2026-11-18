package com.example.ytblocker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// تفتح بعد الضغط على إشعار "طلب مشاهدة شاشة". موافقة صريحة من صاحب الجهاز، كل مرة من جديد.
class ScreenShareActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private val REQ_CODE = 7701

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 120, 50, 50)
        }
        root.addView(TextView(this).apply {
            text = "فيه طلب لمشاهدة شاشة جهازك الآن.\nتقدر توافق أو ترفض."
            textSize = 17f
        })
        val accept = Button(this).apply {
            text = "موافقة وبدء البث"
            setOnClickListener {
                clearRequest()
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CODE)
            }
        }
        val reject = Button(this).apply {
            text = "رفض"
            setOnClickListener { clearRequest(); finish() }
        }
        root.addView(accept); root.addView(reject)
        setContentView(root)
    }

    private fun clearRequest() {
        Thread {
            try {
                val url = "${DeviceSync.URL_BASE}/screen?pair=${DeviceSync.PAIR}&device=${DeviceSync.deviceId(this)}"
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"; connectTimeout = 8000; readTimeout = 8000
                }.responseCode
            } catch (e: Exception) { }
        }.start()
    }
}

// خدمة أمامية (إشعار دائم ما ينشال لأنه إلزامي من أندرويد) تلتقط الشاشة وترسلها
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private var liveCheckAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "screen_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(channelId, "بث الشاشة", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("بث الشاشة شغّال").setContentText("اضغط لإيقافه")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(stopIntent).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("بث الشاشة شغّال").setContentText("اضغط لإيقافه")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notif)

        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(resultCode, data)
        startCapture()
        running = true
        return START_STICKY
    }

    private fun startCapture() {
        val dm = DisplayMetrics(); 
        @Suppress("DEPRECATION") windowManager().defaultDisplay.getRealMetrics(dm)
        val w = (dm.widthPixels * 0.5).toInt().coerceAtLeast(240)
        val h = (dm.heightPixels * 0.5).toInt().coerceAtLeast(240)
        reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "tozy-cap", w, h, dm.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null
        )
        Thread { captureLoop(w, h) }.start()
    }

    private fun windowManager() = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

    private fun captureLoop(w: Int, h: Int) {
        val deviceId = DeviceSync.deviceId(this)
        while (running || reader != null) {
            if (!checkStillWatched()) { stopSelfSafely(); return }
            try {
                val image = reader?.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    image.close()
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 55, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    uploadFrame(deviceId, b64)
                    bmp.recycle(); cropped.recycle()
                }
            } catch (e: Exception) { }
            Thread.sleep(1200) // KV يسمح بكتابة وحدة بالثانية لكل مفتاح
        }
    }

    private fun checkStillWatched(): Boolean {
        val now = System.currentTimeMillis()
        if (now - liveCheckAt < 6000) return true
        liveCheckAt = now
        return try {
            val url = "${DeviceSync.URL_BASE}/screen/live?pair=${DeviceSync.PAIR}&device=${DeviceSync.deviceId(this)}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(txt).optBoolean("live", false)
        } catch (e: Exception) { true } // خطأ شبكة مؤقت ما يوقف البث
    }

    private fun uploadFrame(deviceId: String, b64: String) {
        try {
            val url = "${DeviceSync.URL_BASE}/screen/frame?pair=${DeviceSync.PAIR}&device=$deviceId"
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("jpg", b64).toString().toByteArray()) }
            c.responseCode
        } catch (e: Exception) { }
    }

    private fun stopSelfSafely() {
        running = false
        try { virtualDisplay?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        reader = null; virtualDisplay = null; projection = null
        stopForeground(true); stopSelf()
    }

    override fun onDestroy() { stopSelfSafely(); super.onDestroy() }
}
