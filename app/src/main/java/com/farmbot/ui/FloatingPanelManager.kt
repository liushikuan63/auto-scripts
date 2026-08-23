package com.farmbot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.farmbot.accessibility.ForegroundAppDetector
import com.farmbot.accessibility.GameSceneDetector
import com.farmbot.engine.EngineState
import com.farmbot.engine.FarmEngine
import com.farmbot.engine.FarmState
import com.farmbot.ui.theme.FarmTheme
import com.farmbot.ui.theme.GreenPrimary
import com.farmbot.ui.theme.OrangeAccent
import com.farmbot.ui.theme.RedError
import com.farmbot.ui.theme.TextOnDark
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮控制面板管理器 — 三态设计（收起/展开/边缘隐藏条）
 *
 * 状态流转：
 *  EXPANDED  ──收起按钮──▶  COLLAPSED(悬浮球)
 *  COLLAPSED ──点击──▶      EXPANDED
 *  EXPANDED/COLLAPSED ──拖到屏幕边缘──▶ EDGE_HIDDEN(边缘隐藏条)
 *  EDGE_HIDDEN ──点击──▶    EXPANDED
 *
 * 拖动：用 Compose pointerInput 手势检测，不拦截内部按钮点击
 * 吸附：拖动结束时检查中心X是否进入边缘阈值(50dp)，是则自动贴边隐藏
 */
class FloatingPanelManager(
    private val context: Context,
    private val onRequestHide: (() -> Unit)? = null
) {

    enum class PanelMode { EXPANDED, COLLAPSED, EDGE_HIDDEN }

    companion object {
        private const val TAG = "FloatingPanelManager"
    }

    private val appContext: Context = context.applicationContext
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    // 面板模式状态（Compose 可观察，状态变化自动 recompose）
    private val panelMode = mutableStateOf(PanelMode.EXPANDED)
    private val edgeSide = mutableStateOf(0) // 0=左, 1=右

    private val displayMetrics by lazy { appContext.resources.displayMetrics }
    private val density by lazy { displayMetrics.density }
    private val edgeThreshold by lazy { (50 * density).toInt() } // 50dp 内吸附

    // v1.4.9: 屏幕可用边界动态获取（WindowMetrics），替换此前按 resources.displayMetrics 一次性懒加载。
    // targetSdk 36 强制 Edge-to-Edge，且旋转后尺寸会变；固定值会导致 clamp/吸附坐标错位、
    // 窗口被夹出屏幕或部分按钮越界不可点击（"菜单无法交互/无法定位"的根因之一）。
    private fun screenWidthPx(): Int = screenBounds().first
    private fun screenHeightPx(): Int = screenBounds().second
    private fun screenBounds(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val b = (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .currentWindowMetrics.bounds
                return b.width() to b.height()
            }
        }
        return displayMetrics.widthPixels to displayMetrics.heightPixels
    }

    // 拖动回调（由 Compose pointerInput 触发 → 更新 WindowManager LayoutParams）
    private var onDragDelta: ((Float, Float) -> Unit)? = null
    private var onDragFinished: (() -> Unit)? = null

    // 拖动浮点累加器：避免每帧 dx.toInt() 截断亚像素位移导致慢速拖动不跟手
    private var pendingDx = 0f
    private var pendingDy = 0f

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (isShowing) {
            Log.d(TAG, "悬浮窗已在显示")
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(appContext)) {
                Log.e(TAG, "show: 缺少悬浮窗权限 Settings.canDrawOverlays=false")
                return false
            }
        }

        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // v1.4.9: 移除 FLAG_LAYOUT_NO_LIMITS —— 允许窗口超出屏幕是"菜单无法定位/部分按钮不可点"的根因。
            // 保留 FLAG_LAYOUT_IN_SCREEN 即可让窗口落在屏幕安全坐标内，同时保证可拖拽与边缘吸附正常。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        // 用 TOP|START，x/y 即为距左/上边缘的像素值，方便边缘吸附计算
        params.gravity = Gravity.TOP or Gravity.START
        params.x = ((screenWidthPx() - 130 * density) / 2).toInt()
        params.y = (100 * density).toInt()

        // 拖动回调：浮点累加后取整应用，消除亚像素截断
        onDragDelta = { dx, dy ->
            val p = layoutParams
            if (p != null) {
                pendingDx += dx
                pendingDy += dy
                val ix = pendingDx.toInt()
                val iy = pendingDy.toInt()
                if (ix != 0 || iy != 0) {
                    pendingDx -= ix
                    pendingDy -= iy
                    p.x += ix
                    p.y += iy
                    runCatching { windowManager?.updateViewLayout(floatingView, p) }
                }
            }
        }
        onDragFinished = { checkEdgeSnap() }

        val composeView = ComposeView(appContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FarmTheme {
                    val mode by panelMode
                    when (mode) {
                        PanelMode.EXPANDED -> FarmControlPanel(
                            onDrag = onDragDelta,
                            onDragEnd = onDragFinished,
                            onStart = {
                                // v1.4.5: 悬浮窗开始按钮同样校验录屏通道，避免无通道时静默走 [模拟] 路径
                                if (!com.farmbot.screenshot.ScreenshotService.isCapturing()) {
                                    android.widget.Toast.makeText(
                                        appContext,
                                        "⚠ 尚未授权录屏，OCR定位不可用。请到主界面→权限体检→开通录屏授权",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    FarmEngine.getInstance(appContext).start()
                                }
                            },
                            onPause = { FarmEngine.getInstance(appContext).pause() },
                            onStop = { FarmEngine.getInstance(appContext).stop() },
                            onSettings = { showSettingsDialog() },
                            onCollapse = { collapse() }
                        )
                        PanelMode.COLLAPSED -> CollapsedBubble(
                            onDrag = onDragDelta,
                            onDragEnd = onDragFinished,
                            onExpand = { expand() }
                        )
                        PanelMode.EDGE_HIDDEN -> EdgeHandle(
                            side = edgeSide.value,
                            onShow = { expand() }
                        )
                    }
                }
            }
        }

        val owner = OverlayLifecycleOwner().also { it.performCreate() }
        lifecycleOwner = owner
        composeView.setViewTreeLifecycleOwner(owner)
        composeView.setViewTreeSavedStateRegistryOwner(owner)
        // 关键防线：窗口尺寸/位置变化后立即夹回屏幕内，防止 FLAG_LAYOUT_NO_LIMITS 下把窗口拖出屏幕丢失
        composeView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            clampToScreen(v)
        }
        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { lifecycleOwner?.performResume() }
            override fun onViewDetachedFromWindow(v: View) { lifecycleOwner?.performPause() }
        })

        floatingView = composeView
        try {
            windowManager!!.addView(composeView, params)
            layoutParams = params
            isShowing = true
            Log.i(TAG, "悬浮窗已显示 (三态模式)")
            return true
        } catch (e: BadTokenException) {
            Log.e(TAG, "addView BadTokenException: 请确认已开启悬浮窗权限", e)
            floatingView = null
            isShowing = false
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "addView IllegalStateException", e)
            floatingView = null
            isShowing = false
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "addView 未知异常", t)
            floatingView = null
            isShowing = false
            return false
        }
    }

    /**
     * 屏幕边界约束：保证窗口始终有至少 8dp 的可抓取区域留在屏幕内。
     * EDGE_HIDDEN 模式除外（需要 x=0 紧贴边缘）。
     * 由 OnLayoutChangeListener 在每次布局后触发，形成拖动时的"橡皮筋"效果。
     */
    private fun clampToScreen(v: View) {
        if (panelMode.value == PanelMode.EDGE_HIDDEN) return
        if (v.width <= 0 || v.height <= 0) return
        val p = layoutParams ?: return
        val margin = (8 * density).toInt()
        val sh = screenHeightPx()
        val maxX = (screenWidthPx() - v.width - margin).coerceAtLeast(margin)
        val maxY = (sh - v.height - margin).coerceAtLeast(margin)
        val nx = p.x.coerceIn(margin, maxX)
        val ny = p.y.coerceIn(margin, maxY)
        if (nx != p.x || ny != p.y) {
            p.x = nx
            p.y = ny
            runCatching { windowManager?.updateViewLayout(v, p) }
        }
    }

    /**
     * 拖动结束后检查：面板中心X是否在屏幕边缘阈值内，是则自动贴边隐藏
     */
    private fun checkEdgeSnap() {
        val params = layoutParams ?: return
        val view = floatingView ?: return
        if (view.width <= 0) return  // 未完成布局时不做吸附判断，避免误判
        val centerX = params.x + view.width / 2
        if (centerX < edgeThreshold) {
            hideToEdge(0)  // 左边
        } else if (centerX > screenWidthPx() - edgeThreshold) {
            hideToEdge(1)  // 右边
        }
    }

    /** 收起为悬浮球 */
    fun collapse() {
        if (!isShowing) return
        panelMode.value = PanelMode.COLLAPSED
        updateLayoutForMode()
        Log.i(TAG, "面板已收起为悬浮球")
    }

    /** 展开为完整面板（从边缘条展开时自动调整位置） */
    fun expand() {
        if (!isShowing) return
        val params = layoutParams
        if (panelMode.value == PanelMode.EDGE_HIDDEN && params != null) {
            // 从边缘条展开：先放到对应侧的估算位置，布局完成后 clampToScreen 会精确校正
            params.x = if (edgeSide.value == 0) {
                (12 * density).toInt()
            } else {
                (screenWidthPx() - 160 * density).toInt()
            }
            val maxY = (screenHeightPx() - 120 * density).toInt().coerceAtLeast(0)
            params.y = params.y.coerceIn(0, maxY)
        }
        panelMode.value = PanelMode.EXPANDED
        updateLayoutForMode()
        Log.i(TAG, "面板已展开")
    }

    /** 切换收起/展开 */
    fun toggle() {
        when (panelMode.value) {
            PanelMode.EXPANDED -> collapse()
            else -> expand()
        }
    }

    /** 贴边隐藏为边缘条 */
    private fun hideToEdge(side: Int) {
        edgeSide.value = side
        // 贴边前把 y 夹回屏幕内，防止边缘条因之前的拖动位置而不可见
        layoutParams?.let { p ->
            val maxY = (screenHeightPx() - 80 * density).toInt().coerceAtLeast(0)
            p.y = p.y.coerceIn(0, maxY)
        }
        panelMode.value = PanelMode.EDGE_HIDDEN
        updateLayoutForMode()
        Log.i(TAG, "面板已吸附到${if (side == 0) "左" else "右"}边缘")
    }

    /** 根据当前模式更新窗口尺寸与位置 */
    private fun updateLayoutForMode() {
        val params = layoutParams ?: return
        val view = floatingView ?: return
        when (panelMode.value) {
            PanelMode.EXPANDED -> {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            PanelMode.COLLAPSED -> {
                val s = (48 * density).toInt()
                params.width = s
                params.height = s
            }
            PanelMode.EDGE_HIDDEN -> {
                params.width = (8 * density).toInt()
                params.height = (80 * density).toInt()
                // 紧贴对应边缘
                params.x = if (edgeSide.value == 0) 0 else screenWidthPx() - params.width
            }
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    fun hide() {
        if (!isShowing) return
        try {
            floatingView?.let {
                val wm = windowManager ?: return@let
                try { wm.removeViewImmediate(it) } catch (_: Throwable) {}
            }
            floatingView = null
            isShowing = false
            panelMode.value = PanelMode.EXPANDED  // 重置
            runCatching { lifecycleOwner?.performDestroy() }
            lifecycleOwner = null
            Log.i(TAG, "悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏悬浮窗失败", e)
        }
    }

    fun isVisible(): Boolean = isShowing

    private fun showSettingsDialog() {
        val intent = Intent(appContext, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }
}

// ================================================================
// 悬浮窗专用生命周期所有者
// ================================================================

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val registryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = registryController.savedStateRegistry

    fun performCreate() {
        runCatching { registryController.performRestore(null) }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    fun performResume() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun performPause() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
    fun performDestroy() { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
}

// ================================================================
// 手势检测：同时处理"点击"和"拖动"，解决 detectDragGestures 消费 DOWN 导致 clickable 失效的问题
// ================================================================

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectClickAndDrag(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var totalDx = 0f
        var totalDy = 0f
        var isDragging = false
        val touchSlop = viewConfiguration.touchSlop

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull() ?: break

            if (change.changedToUp()) {
                if (!isDragging) {
                    onClick()
                } else {
                    onDragEnd()
                }
                change.consume()
                break
            }

            if (change.positionChanged()) {
                val dx = change.positionChange().x
                val dy = change.positionChange().y
                totalDx += dx
                totalDy += dy
                if (!isDragging && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    change.consume()
                    onDrag(dx, dy)
                }
            }
        }
    }
}

// ================================================================
// Composable 组件
// ================================================================

/** 拖动条 — 放在 EXPANDED 面板顶部，仅处理拖动 */
@Composable
private fun DragHandle(
    onDrag: ((Float, Float) -> Unit)?,
    onDragEnd: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectClickAndDrag(
                    onClick = {},
                    onDrag = { dx, dy -> onDrag?.invoke(dx, dy) },
                    onDragEnd = { onDragEnd?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text("⋮⋮", color = TextOnDark.copy(alpha = 0.4f), fontSize = 10.sp)
    }
}

/** 完整控制面板（EXPANDED） */
@Composable
private fun FarmControlPanel(
    onDrag: ((Float, Float) -> Unit)?,
    onDragEnd: (() -> Unit)?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onCollapse: () -> Unit
) {
    val context = LocalContext.current
    val engine = remember { FarmEngine.getInstance(context) }

    var engineState by remember { mutableStateOf(EngineState.STOPPED) }
    var currentState by remember { mutableStateOf<FarmState>(FarmState.Idle) }
    var isGameForeground by remember { mutableStateOf<Boolean?>(null) }
    var gameScene by remember { mutableStateOf(GameSceneDetector.Scene.UNKNOWN) }

    LaunchedEffect(Unit) {
        launch { engine.engineState.collectLatest { engineState = it } }
        launch { engine.currentState.collectLatest { currentState = it } }
        launch { ForegroundAppDetector.isTargetGameForeground.collectLatest { isGameForeground = it } }
        launch { GameSceneDetector.currentScene.collectLatest { gameScene = it } }
    }

    val statusText = when (engineState) {
        EngineState.RUNNING -> currentState.displayName()
        EngineState.PAUSED -> "已暂停"
        EngineState.STOPPED -> "空闲"
    }
    val statusColor = when (engineState) {
        EngineState.RUNNING -> GreenPrimary
        EngineState.PAUSED -> OrangeAccent
        EngineState.STOPPED -> Color(0xFFB0B0B0)
    }
    val gameHint = when {
        isGameForeground == true -> "🎮 游戏内 · ${gameScene.displayName}"
        isGameForeground == false -> "⏸ 游戏不在前台"
        else -> "🔍 检测中..."
    }
    val gameHintColor = when (isGameForeground) {
        true -> GreenPrimary
        false -> OrangeAccent
        null -> Color(0xFFB0B0B0)
    }

    Column(
        modifier = Modifier
            .background(color = Color(0xCC222222), shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 拖动条：拖动面板用，不影响下方按钮点击
        DragHandle(onDrag = onDrag, onDragEnd = onDragEnd)
        Text("农场助手", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(gameHint, color = gameHintColor, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(statusText, color = statusColor, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("开始", fontSize = 11.sp) }
            Button(
                onClick = onPause,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("暂停", fontSize = 11.sp) }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = RedError),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("停止", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSettings) {
                Text("⚙ 设置", color = TextOnDark, fontSize = 11.sp)
            }
            TextButton(onClick = onCollapse) {
                Text("⤓ 收起", color = TextOnDark, fontSize = 11.sp)
            }
        }
    }
}

/** 收起悬浮球（COLLAPSED）— 点击展开，拖动移动，拖到边缘自动隐藏 */
@Composable
private fun CollapsedBubble(
    onDrag: ((Float, Float) -> Unit)?,
    onDragEnd: (() -> Unit)?,
    onExpand: () -> Unit
) {
    val context = LocalContext.current
    val engine = remember { FarmEngine.getInstance(context) }
    var engineState by remember { mutableStateOf(EngineState.STOPPED) }

    LaunchedEffect(Unit) {
        engine.engineState.collectLatest { engineState = it }
    }

    val bubbleColor = when (engineState) {
        EngineState.RUNNING -> GreenPrimary
        EngineState.PAUSED -> OrangeAccent
        EngineState.STOPPED -> Color(0xFF555555)
    }
    val icon = when (engineState) {
        EngineState.RUNNING -> "▶"
        EngineState.PAUSED -> "⏸"
        EngineState.STOPPED -> "🌾"
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bubbleColor)
            .pointerInput(Unit) {
                // 点击展开 + 拖动移动，两者共存不冲突
                detectClickAndDrag(
                    onClick = { onExpand() },
                    onDrag = { dx, dy -> onDrag?.invoke(dx, dy) },
                    onDragEnd = { onDragEnd?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(icon, color = Color.White, fontSize = 20.sp)
    }
}

/** 边缘隐藏条（EDGE_HIDDEN）— 贴在屏幕左/右边缘的窄条，点击展开 */
@Composable
private fun EdgeHandle(
    side: Int,  // 0=左, 1=右
    onShow: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(8.dp)
            .height(80.dp)
            .background(
                color = GreenPrimary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(
                    topStart = if (side == 1) 8.dp else 0.dp,
                    topEnd = if (side == 0) 8.dp else 0.dp,
                    bottomEnd = if (side == 0) 8.dp else 0.dp,
                    bottomStart = if (side == 1) 8.dp else 0.dp
                )
            )
            .clickable { onShow() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (side == 0) "›" else "‹",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
