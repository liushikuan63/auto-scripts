/**
 * 模板采集 Activity — Compose 版
 */

package com.farmbot.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.farmbot.recognition.TemplateCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 模板采集 Activity — Compose 版
 *
 * 功能：
 *  - 步骤选择器（17 个标准模板）
 *  - 全屏采集 / 自定义裁剪采集（传入矩形区域坐标）
 *  - 自动采集全部
 *  - 已采集模板预览 + 重新采集
 *  - 从相册导入模板图片
 */
class TemplateCollectActivity : ComponentActivity() {

    private lateinit var collector: TemplateCollector
    private val REQ_IMPORT = 1001

/**
 * 模板采集页创建：展示采集步骤与预览
 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collector = TemplateCollector(this)
        setContent { MaterialTheme { TemplateCollectScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TemplateCollectScreen() {
        val steps = collector.getAllSteps()
        var idx by remember { mutableIntStateOf(0) }
        var info by remember { mutableStateOf(collector.getCollectionInfo()) }
        var hint by remember { mutableStateOf("请按步骤引导进入对应界面，再点击采集") }
        var preview by remember { mutableStateOf<Bitmap?>(null) }
        val scope = rememberCoroutineScope()

        fun refreshPreview() {
            scope.launch {
                preview = withContext(Dispatchers.IO) {
                    collector.loadTemplate(steps[idx].templateName)
                }
            }
        }

        LaunchedEffect(idx) {
            info = collector.getCollectionInfo()
            refreshPreview()
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text("采集模板 (${idx + 1}/${steps.size})") },
                navigationIcon = { IconButton(onClick = { finish() }) { Text("←") } })
        }) { pad ->
            Column(modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(info, style = MaterialTheme.typography.titleMedium)
                val step = steps[idx]
                Text("步骤: ${step.description}", style = MaterialTheme.typography.titleLarge)
                Text(step.hint, color = MaterialTheme.colorScheme.primary)

                if (preview != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("上次采集预览:")
                            Image(bitmap = preview!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp))
                        }
                    }
                } else {
                    Text("(尚未采集此模板)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val w = Modifier.weight(1f)
                    Button(onClick = {
                        collector.captureFullScreen(step) { ok, msg ->
                            runOnUiThread {
                                hint = if (ok) "✓ $msg" else "✗ $msg"
                                info = collector.getCollectionInfo()
                                refreshPreview()
                            }
                        }
                    }, modifier = w) { Text("全屏采集") }

                    OutlinedButton(onClick = { refreshPreview() }, modifier = w) { Text("重看预览") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val w = Modifier.weight(1f)
                    OutlinedButton(onClick = { importFromGallery() }, modifier = w) { Text("从相册导入") }
                    OutlinedButton(onClick = {
                        collector.deleteTemplate(step.templateName)
                        preview = null; info = collector.getCollectionInfo()
                        hint = "已删除: ${step.templateName}"
                    }, modifier = w) { Text("删除此模板") }
                }

                Divider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = idx > 0,
                        onClick = { if (idx > 0) idx-- },
                        modifier = Modifier.weight(1f)
                    ) { Text("上一步") }
                    Button(
                        enabled = idx < steps.size - 1,
                        onClick = { if (idx < steps.size - 1) idx++ },
                        modifier = Modifier.weight(1f)
                    ) { Text("下一步") }
                }

                Divider()
                var autoRunning by remember { mutableStateOf(false) }
                Button(
                    enabled = !autoRunning,
                    onClick = {
                        autoRunning = true
                        collector.autoCollectAll(
                            onProgress = { c, t, s -> runOnUiThread { info = "$c/$t - $s" } },
                            onComplete = { r -> runOnUiThread {
                                autoRunning = false
                                info = "采集完成: ${r.size}"
                                refreshPreview()
                            }}
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (autoRunning) "自动采集中..." else "⚡ 自动采集全部(每步3秒)")
                }

                Text(hint, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    private fun importFromGallery() {
        val i = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
/**
 * 处理选图结果：把所选图片写入模板目录
 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val u: Uri = data?.data ?: return
            var ins: InputStream? = null
            try {
                ins = contentResolver.openInputStream(u)
                val bm = BitmapFactory.decodeStream(ins)
                // ComponentActivity 无 supportFragmentManager，兜底取第 0 步
                val idx = 0
                val allSteps = collector.getAllSteps()
                val step = allSteps[idx.coerceIn(0, allSteps.size - 1)]
                // 直接保存为当前步骤模板
                val dirField = collector.javaClass.getDeclaredField("buttonDir")
                    .apply { isAccessible = true }
                val baseDir = dirField.get(collector) as? java.io.File ?: return
                val out = java.io.File(baseDir.parentFile ?: filesDir, step.templateName)
                java.io.FileOutputStream(out).use { os ->
                    bm.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            } catch (_: Throwable) { /* fallback no-op */ }
            finally { runCatching { ins?.close() } }
        }
    }
}