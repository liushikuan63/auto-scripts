package com.farmbot.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import com.farmbot.accessibility.FarmAccessibilityService
import com.farmbot.engine.FarmEngine
import com.farmbot.ocr.OcrHelper
import com.farmbot.recognition.TemplateMatcher
import com.farmbot.screenshot.ScreenshotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 调试/校准工具页 DebugToolActivity
 *
 * 提供的工具：
 *  1) 截图预览（带十字准星）
 *  2) 指定区域 OCR 文字识别 + 倒计时解析
 *  3) 指定位置取色 / HSV 颜色检测
 *  4) 指定坐标点击（调用无障碍服务）
 *  5) 一键"模板匹配"定位指定按键坐标
 */
class DebugToolActivity : ComponentActivity() {

    private lateinit var matcher: TemplateMatcher
    private lateinit var ocr: OcrHelper
    private var lastShot: android.graphics.Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = FarmEngine.getInstance(this)
        matcher = engine.getTemplateMatcher()
        ocr = engine.getOcr()
        setContent { MaterialTheme { DebugScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DebugScreen() {
        val scope = rememberCoroutineScope()
        var logText by remember { mutableStateOf("准备就绪\n") }
        var shot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        // 输入坐标
        var x by remember { mutableStateOf("540") }
        var y by remember { mutableStateOf("1200") }
        // 区域
        var rl by remember { mutableStateOf("0") }
        var rt by remember { mutableStateOf("1800") }
        var rr by remember { mutableStateOf("1080") }
        var rb by remember { mutableStateOf("1950") }
        // 画刷
        val crossPaint = Paint().apply { color = Color.RED; strokeWidth = 4f; style = Paint.Style.STROKE }
        val rectPaint = Paint().apply { color = Color.GREEN; strokeWidth = 6f; style = Paint.Style.STROKE }

        Scaffold(topBar = {
            TopAppBar(title = { Text("调试 & 校准工具") },
                navigationIcon = { IconButton(onClick = { finish() }) { Text("←") } })
        }) { pad ->
            Column(modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val bm = takeScreenshot()
                            lastShot = bm; shot = bm
                            logText += "截图: ${bm?.width}x${bm?.height}\n"
                        }
                    }, modifier = Modifier.weight(1f)) { Text("截图预览") }
                    Button(onClick = {
                        val bm = lastShot ?: return@Button
                        val r = Rect(
                            rl.toIntOrNull() ?: 0,
                            rt.toIntOrNull() ?: 0,
                            rr.toIntOrNull() ?: bm.width,
                            rb.toIntOrNull() ?: bm.height
                        )
                        val copy = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        val c = Canvas(copy)
                        c.drawRect(android.graphics.RectF(r), rectPaint)
                        // 取色
                        val px = bm.getPixel(
                            (r.left + r.width() / 2).coerceIn(0, bm.width - 1),
                            (r.top + r.height() / 2).coerceIn(0, bm.height - 1)
                        )
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(px, hsv)
                        logText += "[取色] H=%.1f S=%.2f V=%.2f  hex=%06X\n".format(
                            hsv[0], hsv[1], hsv[2], (0xFFFFFF and px)
                        )
                        shot = copy
                    }, modifier = Modifier.weight(1f)) { Text("绘制区域+取色") }
                }

                if (shot != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Image(bitmap = shot!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(shot!!.width.toFloat() / shot!!.height))
                    }
                }

                Text("=== 点击坐标 ===")
                Row {
                    TextField(value = x, onValueChange = { x = it }, label = { Text("x") },
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextField(value = y, onValueChange = { y = it }, label = { Text("y") },
                        modifier = Modifier.weight(1f))
                }
                Button(onClick = {
                    val svc = FarmAccessibilityService.getInstance()
                    val cx = x.toIntOrNull(); val cy = y.toIntOrNull()
                    if (svc != null && cx != null && cy != null) {
                        svc.click(cx, cy)
                        logText += "已点击: ($cx, $cy)\n"
                    } else logText += "点击失败: 无障碍服务未就绪或坐标无效\n"
                }) { Text("✋ 点击") }

                Text("=== OCR 区域(相对截图) ===")
                Grid4Input(rl, rt, rr, rb, onL = { rl = it }, onT = { rt = it }, onR = { rr = it }, onB = { rb = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val bm = lastShot ?: return@Button
                        val r = safeRect(bm, rl, rt, rr, rb) ?: return@Button
                        ocr.recognizeText(bm) { full, _ ->
                            runOnUiThread { logText += "[OCR全文] $full\n" }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("OCR 全文") }
                    Button(onClick = {
                        val bm = lastShot ?: return@Button
                        val r = safeRect(bm, rl, rt, rr, rb) ?: return@Button
                        ocr.recognizeCountdown(bm, r) { s ->
                            runOnUiThread { logText += "[倒计时解析] 区域秒数=$s\n" }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("OCR 倒计时") }
                }

                Text("=== 引擎状态 ===")
                val engine = FarmEngine.getInstance(this@DebugToolActivity)
                val state by engine.currentState.collectAsState(initial = com.farmbot.engine.FarmState.Idle)
                val engState by engine.engineState.collectAsState(initial = com.farmbot.engine.EngineState.STOPPED)
                Text("引擎: $engState · 子状态: ${state.displayName()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { engine.triggerRecoverFlowForDebug() }) { Text("触发恢复") }
                    OutlinedButton(onClick = { engine.triggerWateringNow() }) { Text("立刻浇水") }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = logText, onValueChange = { logText = it },
                    readOnly = true, label = { Text("日志") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp))
            }
        }
    }

    private fun safeRect(bm: Bitmap, l: String, t: String, r: String, b: String): Rect? {
        val x = l.toIntOrNull() ?: return null
        val y = t.toIntOrNull() ?: return null
        val x2 = r.toIntOrNull() ?: return null
        val y2 = b.toIntOrNull() ?: return null
        return Rect(x.coerceIn(0, bm.width - 1), y.coerceIn(0, bm.height - 1),
            x2.coerceIn(0, bm.width), y2.coerceIn(0, bm.height))
    }

    private suspend fun takeScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        val engine = FarmEngine.getInstance(this@DebugToolActivity)
        val shotService = try {
            val f = engine.javaClass.getDeclaredField("screenshotService")
            f.isAccessible = true; f.get(engine) as? ScreenshotService
        } catch (_: Throwable) { null }
        val mat = shotService?.captureFresh() ?: return@withContext null
        val bm = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        try {
            org.opencv.android.Utils.matToBitmap(mat, bm); bm
        } catch (_: Throwable) {
            // fallback：画个空占位图
            val blank = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            val c = Canvas(blank); c.drawColor(Color.LTGRAY)
            val p = Paint().apply { color = Color.BLACK; textSize = 48f }
            c.drawText("需要初始化 ScreenshotService", 80f, 600f, p)
            blank
        }
    }
}

@Composable
private fun Grid4Input(
    l: String, t: String, r: String, b: String,
    onL: (String) -> Unit, onT: (String) -> Unit, onR: (String) -> Unit, onB: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("L" to l to onL, "T" to t to onT, "R" to r to onR, "B" to b to onB).forEach { (kv, cb) ->
            val (label, value) = kv
            TextField(value = value, onValueChange = cb, label = { Text(label) },
                modifier = Modifier.weight(1f))
        }
    }
}
