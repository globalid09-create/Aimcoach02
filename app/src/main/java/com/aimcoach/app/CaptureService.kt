package com.aimcoach.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast

/**
 * Foreground service (type mediaProjection). Holds the screen-capture session, the overlay
 * panel and the hit detector. Frames are only analysed while LEARN is active.
 * Observe-only: nothing here injects input or touches the game.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL = "aimcoach"
        private const val NOTIF_ID = 42

        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var engine: TuningEngine
    private val main = Handler(Looper.getMainLooper())
    private val detector = HitDetector()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var panel: OverlayPanel? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 320

    @Volatile private var learning = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            main.post { rebuildIfRotated() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY // already running

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        engine = TuningEngine.get(this)

        // Must go foreground (type mediaProjection) before getMediaProjection on Android 14+.
        startForegroundCompat()

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = readData(intent)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }, main)

            thread = HandlerThread("aimcoach-frames").also { it.start() }
            handler = Handler(thread!!.looper)

            startCapture(p)

            val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, main)

            panel = OverlayPanel(this, engine, object : OverlayPanel.Callbacks {
                override fun onLearn(on: Boolean) {
                    if (on) detector.reset()
                    learning = on
                    if (!on) engine.flush()
                }

                override fun onExit() {
                    stopSelf()
                }
            }).also { it.show() }

            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "Aim Coach could not start: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Aim Coach", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Aim Coach is running")
            .setContentText("Observing the screen only while LEARN is on")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun makeReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        return r
    }

    private fun startCapture(p: MediaProjection) {
        val dm = screenMetrics()
        capW = maxOf(2, dm.widthPixels / 2) // half resolution
        capH = maxOf(2, dm.heightPixels / 2)
        dpi = dm.densityDpi
        val r = makeReader(capW, capH)
        reader = r
        vdisplay = p.createVirtualDisplay(
            "AimCoach", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    /** Rebuild the capture when the phone rotates (size changes). */
    private fun rebuildIfRotated() {
        val vd = vdisplay ?: return
        val dm = screenMetrics()
        val w = maxOf(2, dm.widthPixels / 2)
        val h = maxOf(2, dm.heightPixels / 2)
        if (w == capW && h == capH) return
        capW = w
        capH = h
        dpi = dm.densityDpi
        val old = reader
        val fresh = makeReader(w, h)
        vd.resize(w, h, dpi)
        vd.surface = fresh.surface
        reader = fresh
        old?.close()
        detector.reset()
        panel?.resetPosition()
    }

    private fun onFrame(r: ImageReader) {
        var img: android.media.Image? = null
        try {
            img = r.acquireLatestImage()
        } catch (_: Exception) {
        }
        if (img == null) return
        try {
            if (!learning) return // only analyse while LEARN is active
            val plane = img.planes[0]
            val hit = detector.process(
                plane.buffer, img.width, img.height,
                plane.rowStride, plane.pixelStride, SystemClock.elapsedRealtime()
            )
            if (hit != null) {
                engine.recordHit(hit == HitDetector.Hit.HEAD)
                panel?.refresh()
            }
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        learning = false
        running = false
        try {
            (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        panel?.remove()
        panel = null
        vdisplay?.release()
        vdisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        if (this::engine.isInitialized) engine.flush()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
