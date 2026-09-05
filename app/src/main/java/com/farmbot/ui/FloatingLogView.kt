package com.farmbot.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.farmbot.engine.FarmEngine
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗实时日志面板 — v1.4.4 重构
 *
 * v1.4.3 问题：无关闭按钮、单击正文即触发最小化、最小化后仍包裹40行文本视觉不变小。
 * v1.4.4 结构：
 *   标题栏 [📋 FarmBot日志(拖动区/单击收缩)] [➖] [✕]
 *   内容区 ScrollView(weight=1)
 * - ✕ 关闭窗口；➖ 与单击拖把手均可真最小化为小条（内容 GONE + WRAP_CONTENT）
 * - 拖动手势仅挂在标题文本把手上，与右侧按钮互不干扰
 */
class FloatingLogView(context: Context) {

    companion object {
        private const val TAG = "FloatingLogView"
        private const val MAX_LINES = 40
        private const val TOUCH_SLOP = 8
    }

    private val appContext: Context = context.applicationContext

    private val wm: WindowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var contentScroll: ScrollView? = null
    private var textView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectJob: Job? = null
    private var minimized = false

    private val screenWidth: Int get() = currentScreenSize().x
    private val screenHeight: Int get() = currentScreenSize().y

    private fun currentScreenSize(): Point = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }
    }.getOrElse {
        val metrics = appContext.resources.displayMetrics
        Point(metrics.widthPixels, metrics.heightPixels)
    }

    fun show(): Boolean {
        if (attached) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(appContext)) {
                Logger.e(TAG, "show: 缺少悬浮窗权限，请先授权 '显示在其他应用上方'")
                return false
            }
        }

        return runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

            params = WindowManager.LayoutParams(
                if (minimized) WindowManager.LayoutParams.WRAP_CONTENT else (screenWidth * 0.55f).toInt(),
                if (minimized) WindowManager.LayoutParams.WRAP_CONTENT else (screenHeight * 0.35f).toInt(),
                type, flags, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // v1.4.9: 默认位置下移到屏幕中下部，避免与顶部居中的控制面板重叠而拦截其按钮点击
                x = (screenWidth * 0.05f).toInt()
                y = (screenHeight * 0.45f).toInt()
            }

            // ===== 标题栏 =====
            val handle = TextView(appContext).apply {
                text = appContext.getString(com.farmbot.R.string.floating_log_title)
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(24, 18, 12, 18)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { toggleMinimize() }
            }
            handle.setOnTouchListener(dragListener)

            val btnMin = TextView(appContext).apply {
                text = "➖"
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(28, 12, 28, 12)
                setOnClickListener {
                    minimized = true
                    applyMinimizeState()
                    Logger.i(TAG, "已最小化日志窗")
                }
            }
            val btnClose = TextView(appContext).apply {
                text = "✕"
                textSize = 15f
                setTextColor(0xFFFF6B6B.toInt())
                setPadding(28, 12, 28, 12)
                setOnClickListener {
                    Logger.i(TAG, "用户点击✕关闭日志窗")
                    hide()
                }
            }

            val titleBar = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF263238.toInt())
                gravity = Gravity.CENTER_VERTICAL
                addView(handle)
                addView(btnMin)
                addView(btnClose)
            }

            // ===== 内容区 =====
            val tv = TextView(appContext).apply {
                textSize = 11f
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(24, 16, 24, 16)
                text = "等待引擎启动..."
            }
            textView = tv
            val sv = ScrollView(appContext).apply { addView(tv) }
            contentScroll = sv

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleBar)
                addView(sv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ))
            }
            if (minimized) sv.visibility = View.GONE
            root = container

            try {
                wm.addView(root, params)
            } catch (e: BadTokenException) {
                Logger.e(TAG, "addView 失败：BadTokenException，请检查悬浮窗权限", e)
                cleanupRefs()
                return false
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "addView 失败：IllegalStateException（View已附着/窗口失效）", e)
                cleanupRefs()
                return false
            }

            attached = true
            startCollecting()
            Logger.i(TAG, "show done: attached=true, type=$type")
            true
        }.getOrElse {
            Logger.e(TAG, "show 异常", it)
            false
        }
    }

    fun hide() {
        if (!attached) return
        runCatching { collectJob?.cancel() }
        root?.let {
            try { wm.removeViewImmediate(it) } catch (_: Throwable) { /* 已被移除 */ }
        }
        attached = false
        cleanupRefs()
        Logger.i(TAG, "hide done")
    }

    /** v1.4.4: 原地切换最小化/展开，替代旧版 hide()+show() 整窗重建 */
    fun toggleMinimize() {
        minimized = !minimized
        if (attached) applyMinimizeState()
    }

    fun isShown() = attached

    fun destroy() {
        hide()
        runCatching { scope.cancel() }
    }

    private fun cleanupRefs() {
        root = null
        contentScroll = null
        textView = null
        params = null
    }

    private fun applyMinimizeState() {
        val p = params ?: return
        val r = root ?: return
        if (minimized) {
            contentScroll?.visibility = View.GONE
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            contentScroll?.visibility = View.VISIBLE
            p.width = (screenWidth * 0.55f).toInt()
            p.height = (screenHeight * 0.35f).toInt()
        }
        runCatching { wm.updateViewLayout(r, p) }
            .onFailure { Logger.w(TAG, "applyMinimizeState 失败: ${it.message}") }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = scope.launch {
            val engine = runCatching { FarmEngine.getInstance(appContext) }.getOrNull() ?: return@launch
            engine.logs.collect { list ->
                if (list.isEmpty()) return@collect
                val last = list.takeLast(MAX_LINES).joinToString("\n")
                textView?.post {
                    runCatching {
                        textView?.text = last
                        if (!minimized) contentScroll?.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    // ===== 拖动：仅挂在标题文本把手上，不覆盖右侧按钮 =====

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    private val dragListener = View.OnTouchListener { view, ev ->
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                startX = params?.x ?: 0; startY = params?.y ?: 0
                dragged = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downX).toInt()
                val dy = (ev.rawY - downY).toInt()
                if (!dragged && (kotlin.math.abs(dx) > TOUCH_SLOP || kotlin.math.abs(dy) > TOUCH_SLOP)) {
                    dragged = true
                }
                if (dragged) {
                    params?.let { p ->
                        val w = p.width.takeIf { it > 0 } ?: 200
                        val maxX = (screenWidth - w).coerceAtLeast(0)
                        val maxY = (screenHeight - 100).coerceAtLeast(0)
                        p.x = (startX + dx).coerceIn(0, maxX)
                        p.y = (startY + dy).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(root, p) }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    view.performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragged = false
                false
            }
            else -> false
        }
    }
}
