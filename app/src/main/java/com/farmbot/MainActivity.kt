package com.farmbot

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farmbot.accessibility.FarmAccessibilityService
import com.farmbot.accessibility.ForegroundAppDetector
import com.farmbot.engine.EngineState
import com.farmbot.engine.FarmEngine
import com.farmbot.engine.FarmState
import com.farmbot.screenshot.ScreenshotService
import com.farmbot.service.FarmBotForegroundService
import com.farmbot.service.FloatingWindowService
import com.farmbot.ui.AppPickerActivity

/**
 * 主界面 MainActivity：启动封装(悬浮窗/前台服务)、权限体检、玩法助手入口。
 * 由 Compose UI 呈现：当前前台状态 / 引擎状态 / 目标应用 / 玩法助手 / 权限体检。
 */
class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Log.i(TAG, "悬浮窗权限已获取 -> 拉起 FloatingWindowService + 显示面板")
            FloatingWindowService.sendCommand(this, FloatingWindowService.ACTION_SHOW_PANEL)
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能显示面板", Toast.LENGTH_SHORT).show()
        }
    }

    private val postNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Log.i(TAG, "通知权限已获取")
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenshotService.startWithCapture(this, result.resultCode, result.data!!)
            Toast.makeText(this, "✅ 录屏授权成功，OCR识别通道已开启", Toast.LENGTH_SHORT).show()
            // v1.4.5: 用户点击"开始"时若未授权录屏，会先引导授权；授权成功落地后自动启动引擎
            if (pendingAutoStart) {
                pendingAutoStart = false
                FarmEngine.getInstance(this).start()
                Toast.makeText(this, "✅ 已自动启动引擎", Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingAutoStart = false
            Toast.makeText(this, "未授予录屏权限，自动点击的OCR定位不可用", Toast.LENGTH_LONG).show()
        }
    }

    /** 记录用户点击"开始"后是否在等待录屏授权落地（授权成功后自动启动引擎） */
    private var pendingAutoStart = false

/**
 * 主界面创建：Compose 承载引擎状态与控制
 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    onStart = { startEngineWithScreenCapture() },
                    onPause = { FarmEngine.getInstance(this).pause() },
                    onStop = { FarmEngine.getInstance(this).stop() },
                    onPickTarget = { startActivity(Intent(this, AppPickerActivity::class.java)) },
                    onShowPanel = { checkAndShowFloatingPanel() },
                    onHidePanel = { hideFloatingPanel() },
                    onShowLog = { showLogWindow() },
                    onCheckA11y = { checkAccessibilityService() },
                    onCheckUsage = { checkUsageStatsPermission() },
                    onCheckScreenshot = { checkScreenshotPermission() },
                    onEnableBackground = { ForegroundAppDetector.requestUsageStatsPermission(this) },
                    onCopyLogs = { copyLogsToClipboard() }
                )
            }
        }
        requestInitialPermissions()
        ForegroundAppDetector.init(applicationContext)
        FloatingWindowService.start(this)
        FarmBotForegroundService.start(this)
    }

    // v1.4.8: 回到前台时若引擎仍在运行而录屏通道已断开，主动提示重新授权（与自动暂停修复形成闭环）
    override fun onResume() {
        super.onResume()
        if (FarmEngine.getInstance(this).engineState.value == EngineState.RUNNING &&
            !ScreenshotService.isCapturing()
        ) {
            Toast.makeText(
                this,
                "⚠ 录屏授权已失效，OCR点击通道中断。请到权限体检重新授权录屏",
                Toast.LENGTH_LONG
            ).show()
        }
    }

/**
 * 主界面销毁：解除订阅
 */
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Activity 销毁，但前台服务与悬浮窗保持运行")
    }

    private fun requestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            postNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!com.farmbot.util.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            Log.w(TAG, "未忽略电池优化，可能影响后台运行")
        }
    }

    /**
     * 主界面"开始"入口：确保录屏截图通道已接通再启动引擎。
     * v1.4.5: 此前按钮直接 start()，无录屏通道时引擎静默走 [模拟] 路径（用户误以为修复未生效的根因）。
     * 现改为：已授权则直接启动；未授权则先拉起录屏授权，成功后自动启动。
     */
    private fun startEngineWithScreenCapture() {
        if (ScreenshotService.isCapturing()) {
            FarmEngine.getInstance(this).start()
            return
        }
        pendingAutoStart = true
        Toast.makeText(this, "先授权录屏，授权后自动启动引擎（OCR 定位依赖）", Toast.LENGTH_LONG).show()
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun checkAndShowFloatingPanel() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        FloatingWindowService.sendCommand(this, FloatingWindowService.ACTION_SHOW_PANEL)
        Toast.makeText(this, "悬浮面板已显示，切到其他应用也会保留", Toast.LENGTH_SHORT).show()
    }

    private fun hideFloatingPanel() {
        FloatingWindowService.sendCommand(this, FloatingWindowService.ACTION_HIDE_PANEL)
        Toast.makeText(this, "已隐藏面板", Toast.LENGTH_SHORT).show()
    }

    private fun showLogWindow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        FloatingWindowService.sendCommand(this, FloatingWindowService.ACTION_SHOW_LOG)
        Toast.makeText(this, "日志窗口已显示", Toast.LENGTH_SHORT).show()
    }

    private fun checkScreenshotPermission() {
        if (ScreenshotService.isCapturing()) {
            Toast.makeText(this, "✅ 录屏/截图通道已开启", Toast.LENGTH_SHORT).show()
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun checkUsageStatsPermission() {
        if (ForegroundAppDetector.hasUsageStatsPermission(this)) {
            Toast.makeText(this, "使用情况访问权限已开启 ✅ 可识别前台", Toast.LENGTH_SHORT).show()
            return
        }
        ForegroundAppDetector.requestUsageStatsPermission(this)
        Toast.makeText(this, "请为本应用开启'使用情况访问'\n用于识别前台游戏", Toast.LENGTH_LONG).show()
    }

    private fun checkAccessibilityService() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "请勾选开启\"王者农场无碍助手\"\n用于识别与模拟点击", Toast.LENGTH_LONG).show()
    }

    private fun copyLogsToClipboard() {
        val engine = FarmEngine.getInstance(this)
        val text = engine.getLogs().joinToString("\n")
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip?.setPrimaryClip(android.content.ClipData.newPlainText("farmbot_logs", text))
        Toast.makeText(this, "日志已复制到剪贴板（${text.length} 字符）", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

// ============================================================
// Compose UI（还原截图1 主界面）
// ============================================================

@Composable
private fun MainScreen(
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPickTarget: () -> Unit,
    onShowPanel: () -> Unit,
    onHidePanel: () -> Unit,
    onShowLog: () -> Unit,
    onCheckA11y: () -> Unit,
    onCheckUsage: () -> Unit,
    onCheckScreenshot: () -> Unit,
    onEnableBackground: () -> Unit,
    onCopyLogs: () -> Unit
) {
    val engine = FarmEngine.getInstance(com.farmbot.FarmBotApp.instance)
    val engineState by engine.engineState.collectAsState()
    val farmState by engine.currentState.collectAsState()
    var foregroundPkg by remember { mutableStateOf("…") }
    var foregroundApp by remember { mutableStateOf("…") }

    Scaffold { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🌾 农场助手", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("自动化你的王者农场", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            ForegroundStatusCard(foregroundPkg, foregroundApp, FarmAccessibilityService.isConnected())

            TargetAppsCard(onPickTarget)

            EngineStatusCard(engineState, farmState)

            GameTipsCard()

            ControlButtons(engineState, onStart, onPause, onStop)

            DiagnosticsSection(
                onCheckA11y = onCheckA11y,
                onCheckUsage = onCheckUsage,
                onCheckScreenshot = onCheckScreenshot,
                onEnableBackground = onEnableBackground,
                onShowPanel = onShowPanel,
                onHidePanel = onHidePanel,
                onShowLog = onShowLog,
                onCopyLogs = onCopyLogs
            )
        }
    }
}

@Composable
private fun ForegroundStatusCard(fgPkg: String, fgApp: String, a11y: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("当前前台：${fgApp.ifEmpty { "—" }}", style = MaterialTheme.typography.titleMedium)
            Text("完整包名：$fgPkg", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                if (a11y) "✅ 无障碍已连接" else "⚠️ 无障碍未连接",
                style = MaterialTheme.typography.bodySmall,
                color = if (a11y) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
    }
}

@Composable
private fun TargetAppsCard(onPick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎯 目标应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onPick) { Text("选择") }
        }
    }
}

@Composable
private fun EngineStatusCard(engineState: EngineState, farmState: FarmState) {
    val color = when (engineState) {
        EngineState.RUNNING -> Color(0xFF2E7D32)
        EngineState.PAUSED -> Color(0xFFF9A825)
        EngineState.STOPPED -> Color(0xFFC62828)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("引擎状态", style = MaterialTheme.typography.titleMedium)
            Text(engineState.displayName(), style = MaterialTheme.typography.bodyMedium, color = color)
            Text("当前子状态：${farmState.displayName()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = color
            )
        }
    }
}

@Composable
private fun ControlButtons(engineState: EngineState, onStart: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, enabled = engineState != EngineState.RUNNING,
            modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9E9E9E))) {
            Text("开始")
        }
        Button(onClick = onPause, enabled = engineState == EngineState.RUNNING,
            modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF9A825))) {
            Text("暂停")
        }
        Button(onClick = onStop, enabled = engineState != EngineState.STOPPED,
            modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) {
            Text("停止")
        }
    }
}

@Composable
private fun GameTipsCard() {
    val cfg = remember { com.farmbot.config.ConfigManager.getConfig() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("💡 玩法助手", style = MaterialTheme.typography.titleMedium)
            Text("等级：${cfg.farmLevel} · 策略：${cfg.cropStrategy} · 每日上限：${cfg.maxDailyOperations}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun DiagnosticsSection(
    onCheckA11y: () -> Unit,
    onCheckUsage: () -> Unit,
    onCheckScreenshot: () -> Unit,
    onEnableBackground: () -> Unit,
    onShowPanel: () -> Unit,
    onHidePanel: () -> Unit,
    onShowLog: () -> Unit,
    onCopyLogs: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("🩺 权限体检", style = MaterialTheme.typography.titleMedium)
            StatusRow("开启无障碍服务", onCheckA11y)
            StatusRow("使用情况访问(识别前台)", onCheckUsage)
            StatusRow("录屏授权(OCR定位)", onCheckScreenshot)
            StatusRow("忽略电池优化", onEnableBackground)
            Spacer(Modifier.height(8.dp))
            // v1.4.10: 悬浮控制面板/菜单此前无任何 UI 入口（onShowPanel 声明后从未被使用）→
            // 面板完全不可达。补"显示悬浮窗"/"隐藏悬浮窗"按钮并接通 onShowPanel/onHidePanel。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowPanel, modifier = Modifier.weight(1f)) { Text("🪟 显示悬浮窗") }
                Button(onClick = onHidePanel, modifier = Modifier.weight(1f)) { Text("隐藏悬浮窗") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowLog, modifier = Modifier.weight(1f)) { Text("显示日志窗") }
                Button(onClick = onCopyLogs, modifier = Modifier.weight(1f)) { Text("复制日志") }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF9A825))) {
            Text("配置")
        }
    }
}

// ============ 辅助 displayName ============

private fun EngineState.displayName(): String = when (this) {
    EngineState.RUNNING -> "运行中"
    EngineState.PAUSED -> "已暂停"
    EngineState.STOPPED -> "已停止"
}


