package com.farmbot.policy

import kotlinx.serialization.Serializable

/**
 * 单种作物策略 — 策略插件化最小数据模型
 *
 * 每种作物可独立配置：浇水间隔、是否偷菜、收获阈值颜色、模板名、对战是否开启等。
 * JSON 持久化在外部存储 `FarmBot/policies/<id>.json`，可导入导出复用。
 */
@Serializable
data class CropPolicy(
    val id: String,                          // 唯一标识（例如 "wheat", "tomato", "default"）
    val displayName: String,                 // 显示名称
    val waterIntervalMin: Int = 120,         // 浇水间隔(分钟)
    val autoAdjustByPlant: Boolean = true,   // 自动调整间隔
    val inheritCountdown: Boolean = false,   // 继承倒计时
    val matureHsvMin: Float = 15f,            // 成熟 HSV H 下限
    val matureHsvMax: Float = 35f,            // 成熟 HSV H 上限
    val matureMinSaturation: Float = 0.3f,    // 成熟最小饱和度
    val templateMature: String = "crop_mature.png",
    val templateImmature: String = "crop_immature.png",
    val templateWaterBtn: String = "btn_water.png",
    val templateHarvestBtn: String = "btn_harvest.png",
    val enableSteal: Boolean = true,
    val enableBless: Boolean = true,
    val dailyBattle: Boolean = true,
    val surrenderAfterSec: Int = 180,
    val sellMode: String = "NONE",           // NONE / SELL_ALL / SELL_MAXED
    val upgradePriority: String = "LAND_FIRST",
    val expandAlways: Boolean = false,
    val perfectMode: Boolean = false,
    val notes: String = ""
) {
    companion object {
        val DEFAULT = CropPolicy(
            id = "default",
            displayName = "默认策略"
        )

        /** 内置示例策略：快速作物（萝卜） */
        val BUILTIN_RADISH = CropPolicy(
            id = "radish",
            displayName = "萝卜（快速）",
            waterIntervalMin = 60,
            matureHsvMin = 10f,
            matureHsvMax = 30f
        )

        /** 内置示例策略：慢速作物（果树） */
        val BUILTIN_TREE = CropPolicy(
            id = "tree",
            displayName = "果树（慢速）",
            waterIntervalMin = 240,
            matureHsvMin = 20f,
            matureHsvMax = 45f,
            surrenderAfterSec = 240,
            perfectMode = true
        )

        val BUILTIN_LIST = listOf(DEFAULT, BUILTIN_RADISH, BUILTIN_TREE)
    }
}
