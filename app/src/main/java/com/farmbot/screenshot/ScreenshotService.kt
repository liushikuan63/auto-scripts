/**
 * 录屏截图服务
 */

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

/**
 * 录屏截图服务
 *
 * 基于 MediaProjection 持续提供屏幕位图：OCR 文字定位与图像识别都依赖它。
 * 授权失效时通过 notifyCaptureLost 通知引擎暂停，避免数百次无效重试。
 */
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
        private const val ALERT_NOTIF_ID = 10021

        // v1.4.8: 告警通知渠道（录屏授权失效等不可自愈事件的用户可见提醒）
        private const val ALERT_CHANNEL_ID = "farmbot_alert"

        // v1.4.8: 帧新鲜度监测与自愈节流（投影存活但长时间无新帧时重建采集链路）
        private const val STALE_FRAME_MS = 12_000L
        private const val REBUILD_THROTTLE_MS = 15_000L

        @Volatile private var lastFrameAtMs = 0L
        @Volatile private var rebuildAtMs = 0L

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
            // v1.4.8: 重置新鲜度/自愈时间戳，避免残留旧值影响下次会话判定
            lastFrameAtMs = 0L
            rebuildAtMs = 0L
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

/**
 * 绑定服务，返回 Binder
 */
    override fun onBind(intent: Intent?): IBinder = binder

/**
 * 服务创建：准备投影与帧缓存
 */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenshotService created")
    }

/**
 * 启动命令：维持前台运行并维持录屏
 */
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
        } else if (!isCapturing()) {
            // v1.4.8: START_STICKY 进程被杀重启后 intent 为 null、pendingData 已丢失，
            // 且 Android 14+ 录屏授权为单次令牌不可复用 —— 此前静默不动作导致引擎侧
            // 只能看到无限"截屏失败"循环；现明确告警引导用户重新授权
            Log.w(TAG, "⚠无可用录屏授权令牌（进程重启后丢失），需用户重新授权")
            notifyCaptureLost("进程重启后录屏授权已失效（单次令牌不可复用）")
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
            nm.createNotificationChannel(
                android.app.NotificationChannel(ALERT_CHANNEL_ID, "农场助手告警", android.app.NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "录屏授权失效等需用户介入的事件提醒" }
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

    /**
     * 录屏授权失效等不可自愈事件 → 发高优先级告警通知（v1.4.8 新增可观测性）。
     * 此前投影被停止后仅静默释放资源，引擎侧只能看到无限"截屏失败"日志，用户无从得知需重新授权。
     */
    fun notifyCaptureLost(reason: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val canPost = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!canPost) {
            Log.w(TAG, "未获通知权限，仅记录告警: $reason")
            return
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        }
        val openIntent = android.app.PendingIntent.getActivity(
            this, 2001,
            Intent(this, com.farmbot.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentTitle("⚠ 录屏授权已失效")
            .setContentText("$reason，请到主界面「权限体检」重新授权录屏")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
        runCatching { nm.notify(ALERT_NOTIF_ID, builder.build()) }
        Log.w(TAG, "已发出录屏失效告警: $reason")
    }

/**
 * 服务销毁：释放投影与帧缓存
 */
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
/**
 * 用用户授权的结果码与 Intent 启动 MediaProjection 录屏
 */
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
            /**
             * 
录屏停止回调：释放投影资源
             */
            override fun onStop() {
                Log.i(TAG, "MediaProjection 被系统或用户停止")
                Handler(Looper.getMainLooper()).post {
                    // v1.4.8: 授权失效属不可自愈事件 —— 明确告警引导重新授权，
                    // 配合状态机 onCaptureLost 快速暂停，终结"截屏失败"数百次空转循环
                    notifyCaptureLost("录屏授权已被系统或用户停止")
                    Companion.stopCapture()
                }
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        createCapturePipeline(projection, width, height, density)
        Log.i(TAG, "Capture started: ${width}x${height}")
        return true
    }

    /**
     * 创建 ImageReader + VirtualDisplay 并挂帧监听器（v1.4.8 抽出）：
     * 首次启动与"无帧自愈重建"共用同一条装配路径，避免两处实现漂移。
     */
    private fun createCapturePipeline(projection: MediaProjection, width: Int, height: Int, density: Int) {
        ensureCaptureThread()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        // v1.4.4: 监听器跑在专用后台线程并持锁消费，确保 buffer queue 不积压（防整机冻结）；
        // v1.4.8: 记录帧时间戳，供 captureBitmap 新鲜度监测触发自愈重建
        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage()
                if (img != null) {
                    synchronized(frameLock) {
                        currentImage?.close()
                        currentImage = img
                        lastFrameAtMs = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "帧消费异常: ${e.message}")
            }
        }, captureHandler)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        lastFrameAtMs = System.currentTimeMillis()
    }

/**
 * 取当前帧（可能为空）
 */
    fun captureOnce(): Mat? = synchronized(frameLock) {
        val image = currentImage
        if (image == null) {
            Log.w(TAG, "无可用捕获帧，返回 null")
            null
        } else {
            imageToMat(image)
        }
    }

/**
 * 强制等待一帧新画面后返回
 */
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
     * v1.4.8: 投影存活但长时间无新帧时节流触发采集链路自愈重建。
     */
    fun captureBitmap(): Bitmap? {
        if (mediaProjection == null) return null   // 投影已停止：授权失效，等待用户重新授权
        val fresh = runCatching { imageReader?.acquireLatestImage() }.getOrNull()
        var copied: Bitmap? = null
        synchronized(frameLock) {
            if (fresh != null) {
                currentImage?.close()
                currentImage = fresh
                lastFrameAtMs = System.currentTimeMillis()
            }
            val image = currentImage ?: return@synchronized
            copied = copyImageToBitmap(image)
        }
        if (copied != null) return copied
        // v1.4.8 自愈：投影仍存活但长时间拿不到任何帧（VirtualDisplay surface 失效等场景），
        // 节流式释放并重建 ImageReader+VirtualDisplay。此前只能永久失败直到重新授权。
        val now = System.currentTimeMillis()
        if (lastFrameAtMs > 0 && now - lastFrameAtMs > STALE_FRAME_MS &&
            now - rebuildAtMs > REBUILD_THROTTLE_MS
        ) {
            Log.w(TAG, "⚠超过 ${STALE_FRAME_MS}ms 无新帧，尝试重建采集链路自愈")
            maybeRebuildCapture()
        }
        return null
    }

    /** 持锁将当前帧拷贝为位图（处理行对齐填充）；失败返回 null */
    private fun copyImageToBitmap(image: Image): Bitmap? = runCatching {
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

    /** v1.4.8: 无帧自愈 —— 保持 MediaProjection 会话，仅重建 ImageReader+VirtualDisplay 采集管道 */
    private fun maybeRebuildCapture() {
        val projection = mediaProjection ?: return
        rebuildAtMs = System.currentTimeMillis()
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val ok = runCatching {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            synchronized(frameLock) {
                currentImage?.close()
                currentImage = null
            }
            createCapturePipeline(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }.onFailure { Log.w(TAG, "自愈重建失败: ${it.message}") }.isSuccess
        Log.i(TAG, if (ok) "✅自愈完成：采集链路已重建（投影会话保留）" else "⚠自愈失败")
    }

    private fun imageToMat(image: Image): Mat {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // v1.4.8: 修复行填充 bug —— rowStride 对齐产生的是每行末尾的填充字节，不是额外行数；
        // 此前 Mat(height + rowPadding/pixelStride) 会生成多出的错误行，
        // 导致下游模板匹配/坐标换算在 rowStride≠width*4 的设备上全部错位。
        // 逐行拷贝有效像素并跳过行末填充，兼容源码 Stub 与真实 OpenCV AAR
        val mat = Mat(image.height, image.width, CvType.CV_8UC4)
        if (rowPadding == 0) {
            mat.put(0, 0, bytes)
        } else {
            val validBytes = pixelStride * image.width
            var offset = 0
            for (row in 0 until image.height) {
                mat.put(row, 0, bytes.copyOfRange(offset, offset + validBytes))
                offset += rowStride
            }
        }

        val rgbMat = Mat()
        Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        mat.release()
        return rgbMat
    }

/**
 * 屏幕宽度
 */
    fun getScreenWidth(): Int {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels
    }

/**
 * 屏幕高度
 */
    fun getScreenHeight(): Int {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }
}