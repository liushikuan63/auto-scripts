package com.farmbot.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmbot.config.FarmConfig
import com.farmbot.policy.CropDatabase
import com.farmbot.ui.theme.GreenPrimary
import com.farmbot.vision.ApkTemplateImporter
import com.farmbot.vision.ApkResourceReader
import kotlinx.coroutines.launch

/**
 * 设置面板与 APK 模板导入界面
 *
 * 以 Compose 管理设置项的展开状态与本地草稿配置，并提供从已安装应用导入模板的流程：
 * 挑选 APK → 扫描其中的图片资源 → 写入模板目录。
 */
class SettingsDialogManager {

    companion object {
        private const val TAG = "SettingsDialogManager"

        fun show(context: Context) {
            val intent = Intent(context, SettingsActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

@Composable
fun SettingsContent(
    config: FarmConfig,
    onConfigChange: (FarmConfig) -> Unit,
    onClose: () -> Unit
) {
    var localConfig by remember { mutableStateOf(config) }

    // f5修复: 设置页可滚动，避免小屏溢出
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "农场助手设置",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF212121)
        )
        Spacer(Modifier.height(16.dp))

        SettingSection(title = "浇水设置") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "浇水间隔 (分钟)",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = localConfig.waterIntervalMin.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let {
                            localConfig = localConfig.copy(waterIntervalMin = it.coerceIn(10, 720))
                            onConfigChange(localConfig)
                        }
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "根据植物自动调整间隔",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = localConfig.autoAdjustByPlant,
                    onCheckedChange = {
                        localConfig = localConfig.copy(autoAdjustByPlant = it)
                        onConfigChange(localConfig)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingSection(title = "每日任务") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "每日对战及奖励",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = localConfig.dailyBattle,
                    onCheckedChange = {
                        localConfig = localConfig.copy(dailyBattle = it)
                        onConfigChange(localConfig)
                    }
                )
            }

            if (localConfig.dailyBattle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "投降等待 (秒)",
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = localConfig.surrenderAfterSec.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let {
                                localConfig = localConfig.copy(surrenderAfterSec = it.coerceIn(30, 600))
                                onConfigChange(localConfig)
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        singleLine = true
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingSection(title = "功能选项") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "新模式 (S43/S44)",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = localConfig.newMode,
                    onCheckedChange = {
                        localConfig = localConfig.copy(newMode = it)
                        onConfigChange(localConfig)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "偷菜",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = localConfig.enableSteal,
                    onCheckedChange = {
                        localConfig = localConfig.copy(enableSteal = it)
                        onConfigChange(localConfig)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "祝福",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = localConfig.enableBless,
                    onCheckedChange = {
                        localConfig = localConfig.copy(enableBless = it)
                        onConfigChange(localConfig)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingSection(title = "策略选择") {
            SellModeSelector(
                selected = localConfig.sellMode,
                onSelect = { mode ->
                    localConfig = localConfig.copy(sellMode = mode)
                    onConfigChange(localConfig)
                }
            )

            Spacer(Modifier.height(8.dp))

            AccountTypeSelector(
                selected = localConfig.accountType,
                onSelect = { type ->
                    localConfig = localConfig.copy(accountType = type)
                    onConfigChange(localConfig)
                }
            )

            Spacer(Modifier.height(8.dp))

            UpgradePrioritySelector(
                selected = localConfig.upgradePriority,
                onSelect = { priority ->
                    localConfig = localConfig.copy(upgradePriority = priority)
                    onConfigChange(localConfig)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingSection(title = "玩法策略 (S43最新机制)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "农场等级",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = localConfig.farmLevel.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let {
                            localConfig = localConfig.copy(farmLevel = it.coerceIn(1, com.farmbot.policy.GameRules.FARM_MAX_LEVEL))
                            onConfigChange(localConfig)
                        }
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true
                )
            }

            Spacer(Modifier.height(8.dp))

            CropStrategySelector(
                selected = localConfig.cropStrategy,
                onSelect = { strategy ->
                    localConfig = localConfig.copy(cropStrategy = strategy)
                    onConfigChange(localConfig)
                }
            )

            Spacer(Modifier.height(8.dp))

            // v1.4.37: 种植配置——右下角种植按钮未选种子时按此选种（空=按等级自动推荐）
            CropPickerRow("周一~周四种植作物", localConfig.weekdayCrop, localConfig.farmLevel) { crop ->
                localConfig = localConfig.copy(weekdayCrop = crop)
                onConfigChange(localConfig)
            }
            Spacer(Modifier.height(6.dp))
            CropPickerRow("周末双倍作物", localConfig.weekendCrop, localConfig.farmLevel) { crop ->
                localConfig = localConfig.copy(weekendCrop = crop)
                onConfigChange(localConfig)
            }

            Spacer(Modifier.height(8.dp))

            SwitchRow("遵守偷菜保护时间(0~8点)", localConfig.respectStealProtection) {
                localConfig = localConfig.copy(respectStealProtection = it)
                onConfigChange(localConfig)
            }
            SwitchRow("先偷菜后祝福", localConfig.stealBeforeBless) {
                localConfig = localConfig.copy(stealBeforeBless = it)
                onConfigChange(localConfig)
            }
            // v1.4.35: 流程控制——默认先完成自家农场(种植/浇水/收获)后才进入好友农场；
            // 开启后跳过自家农场、优先拜访好友农场偷菜。
            SwitchRow("优先偷菜（跳过自家农场，先访好友）", localConfig.prioritySteal) {
                localConfig = localConfig.copy(prioritySteal = it)
                onConfigChange(localConfig)
            }
            SwitchRow("周末双倍策略(卡点浇水)", localConfig.weekendDoubleAware) {
                localConfig = localConfig.copy(weekendDoubleAware = it)
                onConfigChange(localConfig)
            }
            SwitchRow("囤货到周末再卖", localConfig.hoardUntilWeekend) {
                localConfig = localConfig.copy(hoardUntilWeekend = it)
                onConfigChange(localConfig)
            }

            Spacer(Modifier.height(8.dp))

            NumberInputRow("每日偷菜上限", localConfig.stealDailyLimit, 0, 120) {
                localConfig = localConfig.copy(stealDailyLimit = it)
                onConfigChange(localConfig)
            }
            NumberInputRow("每日祝福上限", localConfig.blessDailyLimit, 0, 10) {
                localConfig = localConfig.copy(blessDailyLimit = it)
                onConfigChange(localConfig)
            }
        }

        Spacer(Modifier.height(16.dp))

        TemplateImportSection()

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "修改仅保存在本机",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text("✕ 取消")
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Button(
                onClick = {
                    onConfigChange(localConfig)
                    onClose()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = GreenPrimary
                )
            ) {
                Text("💾 保存设置")
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2196F3),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun SellModeSelector(
    selected: FarmConfig.SellMode,
    onSelect: (FarmConfig.SellMode) -> Unit
) {
    Text(
        text = "出售策略",
        fontSize = 13.sp,
        color = Color(0xFF666666)
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FarmConfig.SellMode.values().forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        text = when (mode) {
                            FarmConfig.SellMode.NONE -> "不出售"
                            FarmConfig.SellMode.SELL_ALL -> "全部出售"
                            FarmConfig.SellMode.SELL_MAXED -> "出售满级"
                        },
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun AccountTypeSelector(
    selected: FarmConfig.AccountType,
    onSelect: (FarmConfig.AccountType) -> Unit
) {
    Text(
        text = "账号类型",
        fontSize = 13.sp,
        color = Color(0xFF666666)
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FarmConfig.AccountType.values().forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = {
                    Text(
                        text = when (type) {
                            FarmConfig.AccountType.NORMAL -> "正常号"
                            FarmConfig.AccountType.TRAINING -> "练小号"
                        },
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun UpgradePrioritySelector(
    selected: FarmConfig.UpgradePriority,
    onSelect: (FarmConfig.UpgradePriority) -> Unit
) {
    Text(
        text = "升级优先级",
        fontSize = 13.sp,
        color = Color(0xFF666666)
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FarmConfig.UpgradePriority.values().forEach { priority ->
            FilterChip(
                selected = priority == selected,
                onClick = { onSelect(priority) },
                label = {
                    Text(
                        text = when (priority) {
                            FarmConfig.UpgradePriority.LAND_FIRST -> "开垦优先"
                            FarmConfig.UpgradePriority.STALL_FIRST -> "小摊优先"
                            FarmConfig.UpgradePriority.BALANCED -> "均衡发展"
                        },
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NumberInputRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let {
                    if (it in min..max) onValueChange(it)
                }
            },
            modifier = Modifier.width(80.dp),
            singleLine = true
        )
    }
}

@Composable
private fun CropStrategySelector(
    selected: FarmConfig.CropStrategy,
    onSelect: (FarmConfig.CropStrategy) -> Unit
) {
    Text(
        text = "种植策略",
        fontSize = 13.sp,
        color = Color(0xFF666666)
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FarmConfig.CropStrategy.values().forEach { strategy ->
            FilterChip(
                selected = strategy == selected,
                onClick = { onSelect(strategy) },
                label = {
                    Text(
                        text = when (strategy) {
                            FarmConfig.CropStrategy.AUTO -> "智能"
                            FarmConfig.CropStrategy.EXP_FIRST -> "经验优先"
                            FarmConfig.CropStrategy.COIN_FIRST -> "金币优先"
                        },
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

/**
 * v1.4.37: 种植作物选择行——下拉列表按当前农场等级列出可种作物，空选项=按策略自动推荐。
 */
@Composable
private fun CropPickerRow(
    label: String,
    selected: String,
    farmLevel: Int,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(farmLevel) {
        listOf("" to "自动推荐") + CropDatabase.availableCrops(farmLevel).map { it.name to "${it.name}(${it.growHours}h)" }
    }
    val selectedLabel = selected.takeIf { current -> options.any { it.first == current } } ?: ""
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    text = if (selectedLabel.isBlank()) "自动推荐" else selectedLabel,
                    fontSize = 12.sp
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display, fontSize = 12.sp) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateImportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { ApkTemplateImporter.getInstance() }

    var installedApps by remember { mutableStateOf<List<ApkResourceReader.InstalledAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ApkTemplateImporter.ImportResult?>(null) }
    var selectedApkPath by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        installedApps = importer.getAvailableFarmApps(context)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val path = uri.path ?: return@let
            selectedApkPath = path
            statusMessage = "已选择: $path"
        }
    }

    SettingSection(title = "模板导入") {
        Text(
            text = "从游戏安装包或已安装应用中提取识别模板图片",
            fontSize = 12.sp,
            color = Color(0xFF888888)
        )

        Spacer(Modifier.height(12.dp))

        if (installedApps.isNotEmpty()) {
            Text(
                text = "检测到已安装的游戏应用:",
                fontSize = 12.sp,
                color = Color(0xFF333333),
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(8.dp))

            installedApps.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF333333)
                        )
                        Text(
                            text = "${app.packageName} v${app.versionName}",
                            fontSize = 11.sp,
                            color = Color(0xFF888888)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = "正在导入 ${app.appName} 的模板..."
                                val result = importer.importFromInstalledApp(
                                    context,
                                    app.packageName
                                )
                                importResult = result
                                isLoading = false
                                statusMessage = result.message
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("导入", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        } else {
            Text(
                text = "未检测到已安装的农场游戏应用",
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "或从APK文件导入:",
            fontSize = 12.sp,
            color = Color(0xFF333333),
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                },
                enabled = !isLoading,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("选择APK文件", fontSize = 12.sp)
            }

            androidx.compose.material3.Button(
                onClick = {
                    val path = selectedApkPath
                    if (path != null) {
                        scope.launch {
                            isLoading = true
                            statusMessage = "正在扫描APK..."
                            val result = importer.importFromApk(context, path)
                            importResult = result
                            isLoading = false
                            statusMessage = result.message
                        }
                    }
                },
                enabled = !isLoading && selectedApkPath != null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = GreenPrimary
                )
            ) {
                Text("开始导入", fontSize = 12.sp)
            }
        }

        if (selectedApkPath != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已选择: ${selectedApkPath!!.take(60)}${if (selectedApkPath!!.length > 60) "..." else ""}",
                fontSize = 11.sp,
                color = Color(0xFF888888)
            )
        }

        if (isLoading) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "处理中...",
                fontSize = 12.sp,
                color = GreenPrimary
            )
        }

        if (statusMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusMessage,
                fontSize = 12.sp,
                color = if (importResult?.success == true) GreenPrimary else Color(0xFFE53935)
            )
        }

        importResult?.let { result ->
            if (result.success && result.matchedTemplates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已导入的模板类型:",
                    fontSize = 11.sp,
                    color = Color(0xFF333333),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                result.matchedTemplates.forEach { (key, count) ->
                    Text(
                        text = "  • $key ($count 张)",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
                if (result.failedTemplates.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "未匹配: ${result.failedTemplates.joinToString(", ")}",
                        fontSize = 10.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
        }
    }
}
