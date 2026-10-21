package com.farmbot.engine.runtime

import com.farmbot.util.RandomDelay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 走位支撑：把角色移动到目标地块
 *
 * 依赖 OcrClickSupport 定位画面中的目标文字，按文字相对画面中心的偏移推摇杆方向，
 * 逐步推进后复查，直到文字贴近画面中心或消失。农场为 SurfaceView 自绘、
 * 无障碍节点树为空，移动方向只能由 OCR 视觉定位给出。
 */
class MovementSupport(private val rt: FarmRuntime, private val ocr: OcrClickSupport) {

    /**
     * v1.4.37: 走到目标地块——朝 OCR 目标文字推摇杆让角色靠近。
     * 开垦/祝福/偷菜/单块操作均需走到对应地块才可操作：相机跟随角色，
     * 目标文字相对画面中心的偏移即角色需要移动的方向；每步推进后复查，
     * 文字靠近画面中心（角色已贴近）或从画面消失（走到身前/被遮挡）即视为到达。
     * @return true=已接近/到达目标；false=目标未找到或步数用尽（如实返回，不假成功）
     */
    internal suspend fun walkNearText(
        keywords: List<String>,
        maxSteps: Int = 6,
        nearRadius: Int = 200
    ): Boolean {
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        if (rt.screenshotProvider == null) return false
        val label = keywords.joinToString("/")
        for (step in 0 until maxSteps) {
            if (!rt.isRunning.value || !rt.scope.isActive) return false
            val pos = ocr.ocrFindPos(keywords)
            if (pos == null) {
                // 目标文字消失：可能已贴近身前或视角遮挡，视为到达，交给后续点击校验
                if (step > 0) {
                    rt.log("🚶走位到[$label]: 目标已离开画面，交由后续点击确认")
                    return true
                }
                rt.log("🚶走位到[$label]: 当前画面未找到目标")
                return false
            }
            val scaleX = pos.imageWidth / 1920f
            val scaleY = pos.imageHeight / 1080f
            val moveScale = minOf(scaleX, scaleY)
            val joyX = (300 * scaleX).toInt()
            val joyY = (820 * scaleY).toInt()
            val cx = pos.imageWidth / 2
            val cy = pos.imageHeight / 2
            val dx = pos.x - cx
            val dy = pos.y - cy
            val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
            if (distance <= nearRadius * moveScale) {
                rt.log("🚶走位到[${pos.keyword}]: 目标已在角色附近，无需移动")
                return true
            }
            // 摇杆推动方向与目标偏移同向，幅度按距离缩放(限幅避免推过头)
            val pushDistance = 260.0 * moveScale
            val scale = pushDistance / distance
            val maxPush = (280 * moveScale).toInt().coerceAtLeast(120)
            val pushX = (dx * scale).toInt().coerceIn(-maxPush, maxPush)
            val pushY = (dy * scale).toInt().coerceIn(-maxPush, maxPush)
            if (!svc.swipe(joyX, joyY, joyX + pushX, joyY + pushY, 900)) {
                rt.log("🚶走位到[${pos.keyword}]失败：滑动手势未被系统接受")
                return false
            }
            rt.log("🚶走位到[${pos.keyword}] 第${step + 1}步: 目标(${pos.x},${pos.y}) 推杆($pushX,$pushY)")
            delay(RandomDelay.uniform(900, 1400))
        }
        rt.log("🚶走位到[$label]: 步数用尽，未确认接近目标")
        return false
    }

    /**
     * v1.4.35: 模拟"移动方向键"（虚拟摇杆）把角色推向雕像。
     * 实测（模拟器 1920x1080）：角色出生在画面下方中央圆台，雕像位于**正上方**（告示牌建筑方向）——
     * 靠近后右侧出现"一键务农/流光加速/农场升级"。此前 v1.4.30/31 按"雕像在左下方"向下/左下推摇杆，
     * 使角色被推离雕像、永远无法靠近（"一键务农"永不出现）。改为**向上**（为主）/左上/右上多方向推进，
     * 每步用 OCR 复查"一键务农"，出现即停。
     */

    internal suspend fun moveTowardsStatue(): Boolean {
        val svc = com.farmbot.accessibility.FarmAccessibilityService.getInstance() ?: return false
        val provider = rt.screenshotProvider ?: return false
        val bmp = runCatching { provider() }.getOrNull() ?: return false
        val scaleX = bmp.width / 1920f
        val scaleY = bmp.height / 1080f
        bmp.recycle()
        val joyX = (300 * scaleX).toInt()
        val joyY = (820 * scaleY).toInt()
        // 向上（雕像在角色上方）为主，辅以左上/右上微调；每次幅度适中，避免推过头
        val steps = listOf(
            0 to 0,       // 占位，直接先查一次
            0 to (-280 * scaleY).toInt(),
            (-160 * scaleX).toInt() to (-220 * scaleY).toInt(),
            (160 * scaleX).toInt() to (-220 * scaleY).toInt(),
            0 to (-200 * scaleY).toInt(),
            (-110 * scaleX).toInt() to (-160 * scaleY).toInt(),
            (110 * scaleX).toInt() to (-160 * scaleY).toInt()
        )
        for (i in steps.indices) {
            if (!rt.isRunning.value || !rt.scope.isActive) return false
            if (i > 0) {
                val (dx, dy) = steps[i]
                svc.swipe(joyX, joyY, joyX + dx, joyY + dy, 1100)
                delay(RandomDelay.uniform(900, 1500))
            }
            if (ocr.ocrContainsAny("一键务农", "键务农")) {
                rt.log("✅朝雕像移动成功(第${i}步)，[一键务农]已出现")
                return true
            }
            rt.log("朝雕像移动(第${i}步)，仍未出现[一键务农]")
        }
        return false
    }

}
