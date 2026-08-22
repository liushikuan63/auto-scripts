package com.farmbot.policy

import java.util.Calendar

/**
 * 王者农场玩法规则引擎（S45赛季机制）
 *
 * S44/S45 变更要点：
 *  - 昼夜系统：白天6:00~18:00 / 夜晚18:00~次日6:00，只影响变异概率不影响产量
 *  - 变异果实培育台：40级解锁，高倍果实作主材洗词条，最高4词条合计840倍
 *  - 流光沙漏：全场成熟-30分钟，周末双倍期效果减半
 *  - 土地模型改版：普通田随等级升至22块(Lv40)，44级起开放二级田最多17块
 *  - 等级上限提升至100级；小摊满级40(+195%售价)
 *  - 单株限额：祝福3次/日被偷4次/保底可偷40%产量
 */
object GameRules {

    const val FARM_MAX_LEVEL = 100

    // ===== 周末双倍 =====
    const val DOUBLE_START_DAY = Calendar.FRIDAY
    const val DOUBLE_START_HOUR = 18

    /** 当前是否处于周末双倍收益期（周五18:00 ~ 周日24:00） */
    fun isWeekendDouble(now: Calendar = Calendar.getInstance()): Boolean {
        val day = now.get(Calendar.DAY_OF_WEEK)
        return when (day) {
            DOUBLE_START_DAY -> now.get(Calendar.HOUR_OF_DAY) >= DOUBLE_START_HOUR
            Calendar.SATURDAY -> true
            Calendar.SUNDAY -> true   // 周日全天，至24:00结束
            else -> false
        }
    }

    /** 距离下一次双倍期开始的分钟数；若已在双倍期内返回 -1 */
    fun minutesUntilNextDouble(now: Calendar = Calendar.getInstance()): Long {
        if (isWeekendDouble(now)) return -1
        val cal = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, 1)
        }
        while (!isWeekendDouble(cal)) {
            cal.add(Calendar.MINUTE, 30)
        }
        return (cal.timeInMillis - now.timeInMillis) / 60_000L
    }

    // ===== 昼夜系统（S45：只影响变异概率） =====
    const val DAY_START_HOUR = 6
    const val NIGHT_START_HOUR = 18

    /** 当前是否处于夜晚（18:00 ~ 次日6:00） */
    fun isNightTime(now: Calendar = Calendar.getInstance()): Boolean {
        val h = now.get(Calendar.HOUR_OF_DAY)
        return h >= NIGHT_START_HOUR || h < DAY_START_HOUR
    }

    /** 当前昼夜时段展示名 */
    fun dayPhaseName(now: Calendar = Calendar.getInstance()): String =
        if (isNightTime(now)) "🌙夜晚(变异概率提升)" else "☀️白天"

    // ===== 流光沙漏（S45：全场成熟-30分钟） =====
    const val HOURGLASS_REDUCE_MINUTES = 30

    /** 周末双倍期内流光沙漏效果减半 */
    fun isHourglassHalfEffect(now: Calendar = Calendar.getInstance()): Boolean =
        isWeekendDouble(now)

    /** 流光沙漏实际生效的减少分钟数 */
    fun hourglassEffectiveMinutes(now: Calendar = Calendar.getInstance()): Int =
        if (isHourglassHalfEffect(now)) HOURGLASS_REDUCE_MINUTES / 2 else HOURGLASS_REDUCE_MINUTES

    // ===== 偷菜保护时间 =====
    const val STEAL_PROTECTION_END_HOUR = 8

    /** 当前是否处于偷菜保护时间（0:00~8:00 不能拜访好友农场偷菜） */
    fun isStealProtectionTime(now: Calendar = Calendar.getInstance()): Boolean =
        now.get(Calendar.HOUR_OF_DAY) < STEAL_PROTECTION_END_HOUR

    /**
     * 卡双倍浇水判断：周五临近18:00时，若浇水会把成熟时间"提前"到双倍开启前，
     * 应暂缓浇水等待卡点（浇水会缩短成熟时间）。
     *
     * @param waterIntervalMin 预计浇水后到成熟的时长（分钟），即当前浇水间隔
     */
    fun shouldHoldWateringForDouble(
        now: Calendar = Calendar.getInstance(),
        waterIntervalMin: Int
    ): Boolean {
        if (now.get(Calendar.DAY_OF_WEEK) != DOUBLE_START_DAY) return false
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val doubleStartMinutes = DOUBLE_START_HOUR * 60
        val minutesToDouble = doubleStartMinutes - nowMinutes
        // 处于双倍前的一个浇水周期窗口内 → 浇水会导致成熟落在双倍开启前
        return minutesToDouble in 1..waterIntervalMin
    }

    /** 浇水临界阈值：剩余成熟时间低于总时长1/30时，浇水不再缩短成熟时间 */
    fun wateringInvalidThresholdMinutes(totalGrowMinutes: Int): Int =
        totalGrowMinutes / 30

    /** 判断当前剩余时间内浇水是否已无效 */
    fun isWateringInvalid(totalGrowMinutes: Int, remainingMinutes: Int): Boolean =
        remainingMinutes < wateringInvalidThresholdMinutes(totalGrowMinutes)

    // ===== 农场等级解锁表（S45） =====
    data class LevelUnlock(val level: Int, val feature: String)

    val LEVEL_UNLOCKS = listOf(
        LevelUnlock(4, "一键浇水 + 第4块土地"),
        LevelUnlock(5, "农场小摊开放"),
        LevelUnlock(8, "一键收获"),
        LevelUnlock(12, "稻草人一键务农"),
        LevelUnlock(18, "解锁香蕉"),
        LevelUnlock(20, "解锁蓝莓、澜莓"),
        LevelUnlock(30, "仓库扩容"),
        LevelUnlock(40, "变异果实培育台(S45)"),
        LevelUnlock(44, "二级土地开放"),
        LevelUnlock(100, "等级上限"),
    )

    /** 指定等级刚解锁的内容（用于升级提示） */
    fun unlockedAt(level: Int): List<String> =
        LEVEL_UNLOCKS.filter { it.level == level }.map { it.feature }

    /** 下一个待解锁内容（用于升级决策建议） */
    fun nextUnlock(level: Int): LevelUnlock? =
        LEVEL_UNLOCKS.firstOrNull { it.level > level }

    /** 普通田数量：lv<4 为3块，之后 2+(min(lv,40)+1)/2，Lv40 满22块 */
    fun normalLandCountForLevel(level: Int): Int {
        if (level < 4) return 3
        return 2 + (minOf(level, 40) + 1) / 2
    }

    /** 二级田数量：44级起开放，73级后增速放缓，最高17块 */
    fun extraLandCountForLevel(level: Int): Int = when {
        level < 44 -> 0
        level < 73 -> (level - 42) / 2
        else -> 14 + (level - 70) / 3
    }

    /** 总土地数（普通田 + 二级田） */
    fun landCountForLevel(level: Int): Int =
        normalLandCountForLevel(level) + extraLandCountForLevel(level)

    // ===== 变异果实培育台（S45） =====
    const val BREEDING_UNLOCK_LEVEL = 40
    const val BREEDING_NAME = "变异果实培育台"
    const val MAX_MUTATION_STACK = 4          // 最高4词条
    const val MAX_COMBINED_MULTIPLIER = 840   // 四词条合计最高840倍

    // ===== 农场小摊 =====
    const val STALL_MAX_LEVEL = 40            // 小摊满级40(+195%售价)

    // ===== 单株社交限额（S45） =====
    const val MAX_BLESS_PER_CROP = 3          // 单株祝福上限次数
    const val MAX_STEAL_PER_CROP_DAILY = 4    // 单株每日被偷上限次数
    const val STEAL_QUOTA_RATIO = 0.4f        // 保底可偷40%产量

    // ===== 每日社交限额 =====
    const val DAILY_STEAL_LIMIT = 120     // 每日偷菜上限
    const val DAILY_BLESS_LIMIT = 10      // 每日主动祝福上限
    const val DAILY_BLESSED_LIMIT = 30    // 每日被祝福上限

    // ===== 变异机制 =====
    const val MUTATION_BASE_PERCENT = 20       // 自然变异概率(%)
    const val MUTATION_BLESS_BONUS_MIN = 30    // 好友祝福加成下限(%)
    const val MUTATION_BLESS_BONUS_MAX = 50    // 好友祝福加成上限(%)

    /** 变异类型与售价倍率（用于高价值作物识别与囤货策略） */
    enum class MutationType(val displayName: String, val priceMultiplier: ClosedFloatingPointRange<Float>) {
        COLOR("颜色变异", 1.2f..2.0f),
        MATERIAL("材质变异", 3.0f..5.0f),
        GIANT("巨大化变异", 5.0f..10.0f),
        HERO("英雄变异", 10.0f..50.0f);
    }
}
