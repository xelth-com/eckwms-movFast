package com.xelth.eckwms_movfast.xelixir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.xelth.eckwms_movfast.R
import com.xelth.eckwms_movfast.utils.SettingsManager
import java.io.ByteArrayOutputStream

/**
 * Foreground service that hosts the embedded xelixir agent: it mirrors the PDA
 * screen via MediaProjection and streams JPEG frames to the C2 server over a
 * [XelixirWsClient]. Streaming is gated on `observers_active` so a dormant agent
 * holds a socket but burns no CPU/battery until an operator actually watches.
 *
 * Android 14+ requires the foreground service (type=mediaProjection) to be
 * running BEFORE MediaProjectionManager.getMediaProjection(), so we always call
 * startForeground() first. On Android 13 the projection is reusable and lenient.
 */
class XelixirAgentService : Service() {

    companion object {
        private const val TAG = "XelixirAgent"
        private const val CHANNEL_ID = "xelixir_support"
        private const val NOTIF_ID = 0x9ECC

        const val ACTION_START = "com.xelth.eckwms_movfast.xelixir.START"
        const val ACTION_STOP = "com.xelth.eckwms_movfast.xelixir.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // Stream defaults (overridden live by operator set_stream_config).
        private const val DEFAULT_LONG_EDGE = 1280
        private const val DEFAULT_QUALITY = 50
        private const val TARGET_FPS = 3

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val i = Intent(context, XelixirAgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, XelixirAgentService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var ws: XelixirWsClient? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var reusableBitmap: Bitmap? = null

    // Live stream state (mutated from WS thread, read from capture thread).
    @Volatile private var observersActive = false
    @Volatile private var forceFullFrame = false
    @Volatile private var quality = DEFAULT_QUALITY
    @Volatile private var longEdgeCap = DEFAULT_LONG_EDGE
    @Volatile private var streamW = 0
    @Volatile private var streamH = 0
    private var realW = 0
    private var realH = 0
    private var densityDpi = 0

    private var frameId = 0
    private var lastFrameMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system/user")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode == 0 || resultData == null) {
                    Log.e(TAG, "missing projection grant; stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                beginProjection(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    // ── Foreground / notification (privacy indicator) ──────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Xelixir Support", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Remote support session — screen is visible to support"
                }
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, XelixirAgentService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xelixir поддержка активна")
            .setContentText("Экран PDA доступен поддержке")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Стоп", stopPi,
                ).build()
            )
            .build()

        // Declare the mediaProjection FGS type on API 29+ (not just 34+). The system grants
        // the FGS-start exemption for a fresh MediaProjection token ONLY when the service
        // goes foreground WITH this type; the untyped overload on API 33 got
        // "startForeground() not allowed due to bg restriction" → getMediaProjection() then
        // threw. Manifest already declares foregroundServiceType="mediaProjection".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 29+
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isRunning = true
    }

    // ── MediaProjection + capture pipeline ─────────────────────────────────

    private fun beginProjection(resultCode: Int, resultData: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }
        projection = proj

        val metrics = resources.displayMetrics
        realW = metrics.widthPixels
        realH = metrics.heightPixels
        densityDpi = metrics.densityDpi

        captureThread = HandlerThread("xelixir-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        proj.registerCallback(projectionCallback, captureHandler)
        configureCapture(longEdgeCap, quality)

        connectWs()
    }

    /** (Re)create ImageReader + VirtualDisplay at the resolution implied by [longEdge]. */
    private fun configureCapture(longEdge: Int, q: Int) {
        quality = q.coerceIn(10, 95)
        val (w, h) = scaledDims(realW, realH, longEdge)
        if (w == streamW && h == streamH && imageReader != null) {
            return
        }
        streamW = w
        streamH = h

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        reusableBitmap?.recycle()
        reusableBitmap = null

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, captureHandler)
        imageReader = reader

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "xelixir",
                w, h, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler,
            )
        } else {
            vd.resize(w, h, densityDpi)
            vd.setSurface(reader.surface)
        }
        XelixirInputController.setMapping(realW, realH, w, h)
        Log.i(TAG, "capture configured: ${w}x${h} q=$quality (real ${realW}x${realH})")
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = System.currentTimeMillis()
            val intervalMs = 1000L / TARGET_FPS
            val due = forceFullFrame || (observersActive && (now - lastFrameMs) >= intervalMs)
            if (!due) return
            lastFrameMs = now
            forceFullFrame = false

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height
            val rowPadding = rowStride - pixelStride * w
            val bmpWidth = w + (if (pixelStride > 0) rowPadding / pixelStride else 0)

            var bmp = reusableBitmap
            if (bmp == null || bmp.width != bmpWidth || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bmpWidth, h, Bitmap.Config.ARGB_8888)
                reusableBitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)

            val baos = ByteArrayOutputStream(64 * 1024)
            if (rowPadding == 0) {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            } else {
                // Crop the stride padding before encoding.
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                cropped.recycle()
            }
            val jpeg = baos.toByteArray()
            frameId += 1
            val frame = XelixirProtocol.buildFrame(frameId, w, h, now.toDouble(), jpeg)
            ws?.sendBinary(frame)
        } catch (e: Exception) {
            Log.w(TAG, "frame encode failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    private fun connectWs() {
        // Token claim may do network I/O → run on the capture thread, then connect.
        captureHandler?.post {
            val url = SettingsManager.getXelixirWsUrl()
            val token = XelixirTokenProvider.obtainBlocking()
            val agentId = SettingsManager.getInstanceId()
            ws = XelixirWsClient(
                baseWsUrl = url,
                token = token,
                agentId = agentId,
                onCommand = ::handleCommand,
                onConnected = {
                    ws?.sendText(XelixirProtocol.agentThought("[PDA] xelixir agent online"))
                },
            ).also { it.connect() }
        }
    }

    private fun handleCommand(cmd: XelixirCommand) {
        when (cmd) {
            is XelixirCommand.ObserversActive -> {
                observersActive = cmd.active
                if (cmd.active) forceFullFrame = true
                Log.i(TAG, "observers active = ${cmd.active}")
            }
            is XelixirCommand.RequestFullFrame -> forceFullFrame = true
            is XelixirCommand.SetStreamConfig -> {
                val longEdge = maxOf(cmd.maxW, cmd.maxH).takeIf { it > 0 } ?: longEdgeCap
                longEdgeCap = longEdge
                val q = if (cmd.quality in 1..100) cmd.quality else quality
                captureHandler?.post { configureCapture(longEdge, q) }
            }
            // Phase B — input injection via the AccessibilityService:
            is XelixirCommand.Pointer -> XelixirInputController.onPointer(cmd.kind, cmd.x, cmd.y, cmd.button)
            is XelixirCommand.Key -> XelixirInputController.onKey(cmd.kind, cmd.key)
            is XelixirCommand.TypeText -> XelixirInputController.onTypeText(cmd.text)
            is XelixirCommand.Unknown -> Log.d(TAG, "unhandled command: ${cmd.type}")
        }
    }

    // ── Teardown ──────────────────────────────────────────────────────────

    override fun onDestroy() {
        Log.i(TAG, "stopping xelixir agent")
        isRunning = false
        try { ws?.close() } catch (_: Exception) {}
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {}
        reusableBitmap?.recycle()
        reusableBitmap = null
        captureThread?.quitSafely()
        super.onDestroy()
    }

    private fun scaledDims(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
        if (longEdge <= 0 || (w <= longEdge && h <= longEdge)) return evenPair(w, h)
        val scale = longEdge.toFloat() / maxOf(w, h)
        return evenPair((w * scale).toInt(), (h * scale).toInt())
    }

    // Encoders prefer even dimensions; round down to even.
    private fun evenPair(w: Int, h: Int): Pair<Int, Int> = Pair(w - (w % 2), h - (h % 2))
}
