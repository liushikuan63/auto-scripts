package com.farmbot.config

/**
 * 配置数据模型 — 融合 HonorFarmBot BotConfig + 当前 FarmConfig
 */
data class FarmConfig(
    // ===== 浇水 =====
    val waterIntervalMin: Int = 120,           // 浇水间隔(分钟)
    val autoAdjustByPlant: Boolean = true,     // 根据植物自动调整间隔
    val inheritCountdown: Boolean = false,     // 继承倒计时(OCR读取剩余时间)

    // ===== 对战 =====
    val dailyBattle: Boolean = true,           // 每日对战及奖励
    val surrenderAfterSec: Int = 180,          // 投降秒数
    val newMode: Boolean = true,               // 新模式 S43/S44

    // ===== 出售 / 升级 / 账号 =====
    val sellMode: SellMode = SellMode.NONE,
    val accountType: AccountType = AccountType.NORMAL,
    val upgradePriority: UpgradePriority = UpgradePriority.LAND_FIRST,
    val upgradeExpandAfter: Boolean = true,    // 升级后开地
    val expandAlways: Boolean = false,         // 每次都开地
    val perfectMode: Boolean = false,          // 完美模式

    // ===== 偷菜/祝福 =====
    val enableSteal: Boolean = true,
    val enableBless: Boolean = true,

    // ===== 农场玩法（S43 最新机制）=====
    val farmLevel: Int = 1,                              // 当前农场等级（决定可用功能与推荐作物）
    val cropStrategy: CropStrategy = CropStrategy.AUTO,  // 种植策略（智能=工作日经验/周末金币）
    val respectStealProtection: Boolean = true,          // 遵守0:00~8:00偷菜保护时间
    val stealBeforeBless: Boolean = true,                // 先偷菜后祝福（祝福触发变异后可二次偷）
    val stealDailyLimit: Int = 120,                      // 每日偷菜上限
    val blessDailyLimit: Int = 10,                       // 每日祝福上限
    val weekendDoubleAware: Boolean = true,              // 周末双倍感知（卡点浇水+双倍期出售）
    val hoardUntilWeekend: Boolean = false,              // 高价值作物囤到周末双倍再卖

    // ===== 高级操作参数 =====
    val operationDelayMinMs: Int = 800,        // 最小操作延迟
    val operationDelayMaxMs: Int = 2500,       // 最大操作延迟
    val maxDailyOperations: Int = 500,         // 每日操作上限
    val dailyOperationLimit: Int = 500,        // 兼容 HonorFarmBot
    val randomOffsetRange: Int = 8,            // 随机偏移像素
    val templateMatchThreshold: Double = 0.85, // 模板匹配阈值
    val enableLog: Boolean = true,             // 启用日志

    // ===== 前台App检测联动 =====
    val autoStartWhenGameForeground: Boolean = true,  // 游戏进入前台自动启动引擎
    val pauseWhenGameBackground: Boolean = false,     // 游戏离开前台自动暂停（默认关闭以免误操作）

    // ===== 目标应用（可自定义监控的游戏/应用包名集合）=====
    val targetPackages: Set<String> = setOf(DEFAULT_TARGET_PKG)
) {
    enum class SellMode { NONE, SELL_ALL, SELL_MAXED }
    enum class AccountType { NORMAL, TRAINING }
    enum class UpgradePriority { LAND_FIRST, STALL_FIRST, BALANCED }
    enum class CropStrategy { AUTO, EXP_FIRST, COIN_FIRST }

    companion object {
        /** 默认目标：王者荣耀（王者农场所在宿主App） */
        const val DEFAULT_TARGET_PKG = "com.tencent.tmgp.sgame"
        val DEFAULT = FarmConfig()
    }
}
