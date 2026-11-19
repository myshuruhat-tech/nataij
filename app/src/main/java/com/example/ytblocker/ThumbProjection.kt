package com.example.ytblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

// Screenshot fallback for Android 10 and below (takeScreenshot doesn't work before Android 11)
object ThumbProjection {
    @Volatile var projection: MediaProjection? = null
    @Volatile var skipped = false
    val active: Boolean get() = projection != null

    private fun toBitmap(img: android.media.Image, w: Int, h: Int): Bitmap? = try {
        val plane = img.planes[0]
        val pad = plane.rowStride - plane.pixelStride * w
        val full = Bitmap.createBitmap(w + pad / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        val out = Bitmap.createBitmap(full, 0, 0, w, h)
        if (out !== full) full.recycle()
        out
    } catch (e: Throwable) { null }

    // Captures a single frame. Must be called from a background thread. Returns null on failure
    fun grab(ctx: Context): Bitmap? {
        val p = projection ?: return null
        var reader: ImageReader? = null
        var vd: VirtualDisplay? = null
        try {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            vd = p.createVirtualDisplay("tozy-thumb", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            var tries = 0
            while (tries < 25) {
                val img = r.acquireLatestImage()
                if (img != null) {
                    var first: Bitmap? = null
                    try { first = toBitmap(img, w, h) } finally { img.close() }
                    // The first frame from the virtual display sometimes comes out partial/black (garbled); wait a bit and take the latest if it arrives
                    Thread.sleep(250)
                    val img2 = r.acquireLatestImage()
                    if (img2 != null) {
                        try {
                            val second = toBitmap(img2, w, h)
                            if (second != null) { first?.recycle(); return second }
                        } finally { img2.close() }
                    }
                    return first
                }
                Thread.sleep(100)
                tries++
            }
            return null
        } catch (e: Throwable) {
            return null
        } finally {
            try { vd?.release() } catch (e: Throwable) { }
            try { reader?.close() } catch (e: Throwable) { }
        }
    }
}

// Foreground service that keeps the screen-record permission alive (required by Android, has a persistent notification)
class ThumbProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "thumb_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Screenshots", NotificationManager.IMPORTANCE_LOW))
        }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notif = b.setContentTitle("Screen thumbnails enabled").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, notif)

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = pm.getMediaProjection(resultCode, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ThumbProjection.projection = null; stopSelf() }
        }, Handler(Looper.getMainLooper()))
        ThumbProjection.projection = p
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val p = ThumbProjection.projection
        ThumbProjection.projection = null
        try { p?.stop() } catch (e: Throwable) { }
        super.onDestroy()
    }
}
