package com.example.mediaprojectionsimple

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.net.Inet4Address

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "mirror.start"
        const val ACTION_STOP = "mirror.stop"
        const val EXTRA_CODE = "resultCode"
        const val EXTRA_DATA = "resultData"
        const val EXTRA_FLOATING = "floating"
        private const val CHANNEL_ID = "live_mirror"
        @Volatile var isRunning = false
            private set
        @Volatile var currentShareUrl: String? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var overlay: FrameLayout? = null
    private var preview: ImageView? = null
    private var shareServer: ShareServer? = null
    private var lastFrameTime = 0L
    private var stopped = false
    private var floating = false
    @Volatile private var latestJpeg: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || isRunning) return START_NOT_STICKY

        stopped = false
        floating = intent.getBooleanExtra(EXTRA_FLOATING, false)
        try {
            val channel = NotificationChannel(CHANNEL_ID, "Udostępnianie ekranu", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val stopIntent = PendingIntent.getService(this, 0, Intent(this, CaptureService::class.java).apply {
                action = ACTION_STOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Live Screen Mirror działa")
                .setContentText("Udostępniasz ekran. Stuknij, aby zatrzymać.")
                .setContentIntent(stopIntent)
                .setOngoing(true)
                .build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val consent = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
            } ?: error("Brak zgody na ekran")
            val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            check(code == Activity.RESULT_OK) { "Brak zgody Androida" }

            val mp = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(code, consent) ?: error("Nie udało się przechwycić ekranu")
            projection = mp
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopCapture() } }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (Build.VERSION.SDK_INT >= 34) main.post { resizeCapture(width, height) }
                }
            }.also { mp.registerCallback(it, main) }

            worker = HandlerThread("mirror-frames").also { it.start() }
            captureHandler = Handler(worker!!.looper)
            val metrics = resources.displayMetrics
            startDisplay(metrics.widthPixels, metrics.heightPixels)
            if (floating) showFloatingWindow() else startWifiServer()
            isRunning = true
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd: ${e.message}", Toast.LENGTH_LONG).show()
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val ratio = minOf(1f, 720f / maxOf(width, height).toFloat())
        return Pair((width * ratio).toInt().coerceAtLeast(2), (height * ratio).toInt().coerceAtLeast(2))
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        val (w, h) = scaledSize(width, height)
        return ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ incoming -> processFrame(incoming) }, captureHandler)
        }
    }

    private fun startDisplay(width: Int, height: Int) {
        val r = newReader(width, height)
        reader = r
        display = projection!!.createVirtualDisplay(
            "LiveScreenMirror", r.width, r.height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, captureHandler
        )
    }

    private fun resizeCapture(width: Int, height: Int) {
        if (stopped || width <= 0 || height <= 0) return
        val (w, h) = scaledSize(width, height)
        if (reader?.width == w && reader?.height == h) return
        try {
            val newReader = newReader(width, height)
            display?.setSurface(null)
            display?.resize(w, h, resources.displayMetrics.densityDpi)
            display?.setSurface(newReader.surface)
            val previous = reader
            reader = newReader
            previous?.close()
        } catch (_: Exception) { /* restart the session if the device doesn't support resizing */ }
    }

    private fun processFrame(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (stopped) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFrameTime < if (floating) 200 else 500) return
            lastFrameTime = now
            val plane = image.planes[0]
            if (plane.pixelStride != 4) return
            val rowBytes = image.width * 4
            val rows = ByteBuffer.allocate(rowBytes * image.height)
            val row = ByteArray(rowBytes)
            for (y in 0 until image.height) {
                plane.buffer.position(y * plane.rowStride)
                plane.buffer.get(row)
                rows.put(row)
            }
            rows.rewind()
            val frame = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(rows)
            if (floating) {
                main.post { if (!stopped) preview?.setImageBitmap(frame) else frame.recycle() }
            } else {
                val bytes = ByteArrayOutputStream()
                frame.compress(Bitmap.CompressFormat.JPEG, 60, bytes)
                latestJpeg = bytes.toByteArray()
                frame.recycle()
            }
        } catch (_: Exception) {
            // MediaProjection can end between acquiring and decoding a frame.
        } finally {
            image.close()
        }
    }

    private fun startWifiServer() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: error("Połącz telefon z Wi-Fi")
        val capabilities = cm.getNetworkCapabilities(active)
        check(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Połącz telefon z Wi-Fi (nie z internetem komórkowym)"
        }
        val ip = cm.getLinkProperties(active)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            ?: error("Brak adresu Wi-Fi")
        shareServer = ShareServer(ip) { latestJpeg }.also {
            it.start()
            currentShareUrl = it.url
        }
    }

    private fun showFloatingWindow() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val width = (resources.displayMetrics.widthPixels * .55f).toInt().coerceAtLeast(120)
        val height = (width * 1.35f).toInt()
        val root = FrameLayout(this)
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        preview = image
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        val controls = TextView(this).apply {
            text = "☰  Przesuń                     ✕ Zamknij"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xDD151B27.toInt())
        }
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 160
        }
        var originX = 0
        var originY = 0
        var startX = 0f
        var startY = 0f
        controls.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = params.x; originY = params.y
                    startX = event.rawX; startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + (event.rawX - startX).toInt()
                    params.y = originY + (event.rawY - startY).toInt()
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (event.rawX - startX < 18 && event.rawX - startX > -18 &&
                        event.rawY - startY < 18 && event.rawY - startY > -18 &&
                        event.x > controls.width * .68f) stopCapture()
                    true
                }
                else -> true
            }
        }
        wm.addView(root, params)
        overlay = root
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        isRunning = false
        currentShareUrl = null
        shareServer?.stop()
        shareServer = null
        latestJpeg = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        overlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        preview = null
        projection?.let { p ->
            projectionCallback?.let { p.unregisterCallback(it) }
            p.stop()
        }
        projection = null
        projectionCallback = null
        worker?.quitSafely()
        worker = null
        captureHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
