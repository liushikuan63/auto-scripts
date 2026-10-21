/**
 * AppPickerActivity 的实现。
 */

package com.farmbot.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farmbot.accessibility.ForegroundAppDetector
import com.farmbot.config.ConfigManager
import com.farmbot.config.FarmConfig
import com.farmbot.ui.theme.FarmTheme
import com.farmbot.ui.theme.GreenPrimary
import com.farmbot.ui.theme.TextSecondary

/** 已安装应用条目（文件内共享） */
private data class AppItem(
/**
 * 应用显示名
 */
    val label: String,
/**
 * 应用包名
 */
    val pkg: String
)

/**
 * 目标应用选择器
 *
 * 让用户从已安装（带桌面入口）的应用中多选需要监控的目标游戏/应用，
 * 选择结果持久化到 FarmConfig.targetPackages，并实时同步到 ForegroundAppDetector。
 * 解决"游戏包名硬编码、无法识别其他游戏"的问题。
 */
class AppPickerActivity : ComponentActivity() {

/**
 * 应用选择页创建：列出可导入的已安装应用
 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apps = loadLaunchableApps()
        val currentTargets = runCatching { ConfigManager.getConfig().targetPackages }
            .getOrDefault(setOf(FarmConfig.DEFAULT_TARGET_PKG))

        setContent {
            FarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppPickerScreen(
                        apps = apps,
                        initialSelected = currentTargets,
                        onSave = { selected -> saveAndFinish(selected) },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }

    private fun loadLaunchableApps(): List<AppItem> {
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            resolveInfos.asSequence()
                .map { it.activityInfo.applicationInfo }
                .filterNotNull()
                .filter { it.packageName != packageName } // 排除自己
                .distinctBy { it.packageName }
                .map {
                    AppItem(
                        label = runCatching {
                            packageManager.getApplicationLabel(it).toString()
                        }.getOrDefault(it.packageName),
                        pkg = it.packageName
                    )
                }
                .sortedWith(
                    compareByDescending<AppItem> { it.pkg == FarmConfig.DEFAULT_TARGET_PKG }
                        .thenBy { it.label }
                )
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun saveAndFinish(selected: Set<String>) {
        val finalSet = if (selected.isEmpty()) setOf(FarmConfig.DEFAULT_TARGET_PKG) else selected
        runCatching {
            val cfg = ConfigManager.getConfig()
            ConfigManager.save(cfg.copy(targetPackages = finalSet))
            // 实时同步到检测器，无需重启服务
            ForegroundAppDetector.setTargetPackages(finalSet)
        }
        android.widget.Toast.makeText(
            this, "目标应用已保存：${finalSet.size} 个", android.widget.Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}

@Composable
private fun AppPickerScreen(
    apps: List<AppItem>,
    initialSelected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .padding(16.dp)
    ) {
        Text(
            text = "🎯 选择目标应用",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF212121)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "勾选需要自动化的游戏/应用（可多选），默认包含王者荣耀",
            fontSize = 12.sp,
            color = TextSecondary
        )

        Spacer(Modifier.height(10.dp))

        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索应用名或包名", fontSize = 13.sp) },
            singleLine = true
        )

        Spacer(Modifier.height(10.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.pkg }) { app ->
                    val checked = app.pkg in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - app.pkg
                                else selected + app.pkg
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selected = if (it) selected + app.pkg
                                else selected - app.pkg
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = app.label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF212121)
                                )
                                if (app.pkg == FarmConfig.DEFAULT_TARGET_PKG) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(GreenPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("默认", fontSize = 10.sp, color = GreenPrimary)
                                    }
                                }
                            }
                            Text(
                                text = app.pkg,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "已选 ${selected.size} 个",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }
            Button(
                onClick = { onSave(selected) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
            ) {
                Text("保存")
            }
        }
    }
}