package com.farmbot.screenshot

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc

class ScreenshotService : Service() {

    private val binder by lazy { ScreenshotBinder() }
    private val windowManager: android.view.WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    }

    companion object {
        private const val TAG = "ScreenshotService"
        private const val VIRTUAL_DISPLAY_NAME = "FarmBotCapture"
        private const val DEFAULT_IMAGE_WIDTH = 1080
        private const val DEFAULT_IMAGE_HEIGHT = 1920
        private const val NOTIF_ID = 1002

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var currentImage: Image? = null

        // v1.4.4: 帧访问互斥锁 + 专用后台线程
        // 此前监听器跑在主线程(handler=null)且无锁，高帧率下 buffer queue 打满会阻塞
        // SurfaceFlinger 合成 → 整机画面冻结/触摸无渲染响应（用户报"屏幕静止无法操作"）
        private val frameLock = Any()
        private var captureThread: HandlerThread? = null
        private var captureHandler: Handler? = null
        private var projectionCallback: MediaProjection.Callback? = null
        private const val MAX_IMAGES = 4

        @Volatile private var pendingResultCode: Int = Int.MIN_VALUE
        @Volatile private var pendingData: Intent? = null

        fun getMediaProjection(): MediaProjection? = mediaProjection

        /**
         * 录屏授权回调后调用（v1.4.3）：启动服务并在其 onStartCommand 中初始化捕获会话。
         * Android 14+ 要求 MediaProjection 必须运行于 mediaProjection 类型的前台服务。
         */
        fun startWithCapture(context: Context, resultCode: Int, data: Intent) {
            pendingResultCode = resultCode
            pendingData = data
            val i = Intent(context, ScreenshotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stopCapture() {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            synchronized(frameLock) {
                currentImage?.close()
                currentImage = null
            }
            projectionCallback?.let { cb ->
                runCatching { mediaProjection?.unregisterCallback(cb) }
            }
            projectionCallback = null
            mediaProjection?.stop()
            mediaProjection = null
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            Log.i(TAG, "Capture stopped")
        }

        fun isCapturing(): Boolean = virtualDisplay != null

        private fun ensureCaptureThread() {
            if (captureHandler == null) {
                val t = HandlerThread("FarmBotCapture")
                t.start()
                captureThread = t
                captureHandler = Handler(t.looper)
            }
        }
    }

    inner class ScreenshotBinder : Binder() {
        fun getService(): ScreenshotService = this@ScreenshotService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenshotService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ScreenshotService started")
        // Android 14+: createVirtualDisplay 前必须先进入 mediaProjection 类型前台服务
        startForegroundCompat()
        val rc = pendingResultCode
        val data = pendingData
        if (!isCapturing() && rc != Int.MIN_VALUE && data != null) {
            pendingResultCode = Int.MIN_VALUE
            pendingData = null
            val ok = runCatching { startCapture(rc, data) }.getOrDefault(false)
            Log.i(TAG, "capture init=$ok")
            if (ok) {
                // v1.4.3: 捕获会话建立后立即装配到引擎（状态机OCR定位依赖此通道）
                com.farmbot.engine.FarmEngine.getInstance(this).setScreenshotService(this)
            }
        }
        return START_STICKY
    }

    private var foregroundStarted = false

    @SuppressLint("WrongConstant")
    private fun startForegroundCompat() {
        if (foregroundStarted) return
        val channelId = "farmbot_screenshot"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channelId, "屏幕捕获", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        }
        builder.setContentTitle("农场助手")
            .setContentText("屏幕捕获运行中（自动点击OCR识别依赖）")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, builder.build())
        }
        foregroundStarted = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Companion.stopCapture()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        com.farmbot.engine.FarmEngine.getInstance(this).setScreenshotService(null)
        Log.i(TAG, "ScreenshotService destroyed")
    }

    @SuppressLint("WrongConstant")
    fun startCapture(resultCode: Int, data: Intent): Boolean {
        if (mediaProjection != null && virtualDisplay != null) {
            Log.w(TAG, "Capture already running")
            return true
        }

        val projection = Companion.mediaProjection ?: run {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            manager.getMediaProjection(resultCode, data)
        } ?: run {
            Log.e(TAG, "getMediaProjection returned null, capture aborted")
            return false
        }
        // v1.4.4 关键修复：此前 projection 仅赋给局部变量未写回 companion 字段，
        // captureBitmap() 判空恒真返回 null → OCR 定位通道整体失效（“一直是模拟数据”的根因）
        Companion.mediaProjection = projection

        // Android 14+ 要求 createVirtualDisplay 前必须注册 Callback，否则抛 SecurityException
        ensureCaptureThread()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection 被系统或用户停止")
                Handler(Looper.getMainLooper()).post { Companion.stopCapture() }
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        // v1.4.4: 监听器移至专用后台线程并持锁消费，确保 buffer queue 不积压（防整机冻结）
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    synchronized(frameLock) {
                        currentImage?.close()
                        currentImage = image
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "帧消费异常: ${e.message}")
            }
        }, captureHandler)

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        Log.i(TAG, "Capture started: ${width}x${height}")
        return true
    }

    fun captureOnce(): Mat? = synchronized(frameLock) {
        val image = currentImage
        if (image == null) {
            Log.w(TAG, "无可用捕获帧，返回 null")
            null
        } else {
            imageToMat(image)
        }
    }

    fun captureFresh(): Mat? = synchronized(frameLock) {
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull()
        if (image != null) {
            currentImage?.close()
            currentImage = image
        }
        val target = currentImage
        if (target == null) null else imageToMat(target)
    }

    /**
     * 获取当前屏幕截图位图（RGBA_8888）。
     * 供 OCR 文字定位使用；无捕获会话或暂无帧时返回 null。
     * v1.4.4: 全程持 frameLock 与后台帧监听线程互斥，杜绝 Image 并发 close/泄漏。
     */
    fun captureBitmap(): Bitmap? {
        return synchronized(frameLock) {
            if (mediaProjection == null) {
                null
            } else {
                val fresh = runCatching { imageReader?.acquireLatestImage() }.getOrNull()
                if (fresh != null) {
                    currentImage?.close()
                    currentImage = fresh
                }
                val image = currentImage
                if (image == null) {
                    null
                } else {
                    runCatching {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        buffer.rewind()
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * image.width
                        val padded = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )
                        padded.copyPixelsFromBuffer(buffer)
                        if (rowPadding > 0) {
                            val trimmed = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                            padded.recycle()
                            trimmed
                        } else padded
                    }.getOrNull()
                }
            }
        }
    }

    private fun imageToMat(image: Image): Mat {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val mat = Mat(image.height + rowPadding / pixelStride, image.width, CvType.CV_8UC4)
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        mat.put(0, 0, bytes)

        val rgbMat = Mat()
        Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        return rgbMat
    }

    fun getScreenWidth(): Int {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels
    }

    fun getScreenHeight(): Int {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }
}