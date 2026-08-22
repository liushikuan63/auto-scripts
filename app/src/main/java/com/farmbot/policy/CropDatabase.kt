package com.farmbot.policy

/**
 * 作物数据库 — 基于王者农场 1~80 级公开图鉴数据（S43赛季）
 *
 * 数据说明：
 *  - expPerHour / coinPerHour 为不计算小摊加成的基础效率
 *  - isCoinFocus = true 表示"经验黑洞"型纯金币作物（经验为0，周末双倍期首选）
 *  - heroName 为该作物可变异出的英雄主题果实（不可被偷，售价×10~50）
 */
data class CropInfo(
    val name: String,
    val unlockLevel: Int,
    val growHours: Double,     // 自然成熟时长（浇水后约缩短25%）
    val expPerHour: Int,
    val coinPerHour: Int,
    val isCoinFocus: Boolean = false,
    val heroName: String? = null
)

object CropDatabase {

    enum class Strategy { EXP_FIRST, COIN_FIRST, AUTO }

    /** 主要作物表（按解锁等级升序） */
    val CROPS: List<CropInfo> = listOf(
        CropInfo("小麦", 1, 0.01, 120, 360),
        CropInfo("胡萝卜", 2, 0.04, 180, 270),
        CropInfo("番茄", 3, 0.09, 180, 276),
        CropInfo("蘑菇", 5, 0.34, 180, 387),
        CropInfo("土豆", 6, 1.0, 180, 425),
        CropInfo("玉米", 8, 8.0, 191, 394),
        CropInfo("草莓", 10, 16.0, 196, 338),
        CropInfo("向日葵", 12, 1.0, 202, 444),
        CropInfo("青椒", 14, 8.0, 207, 406),
        CropInfo("大蒜", 16, 16.0, 212, 349),
        CropInfo("香蕉", 18, 16.0, 0, 1094, isCoinFocus = true),
        CropInfo("蓝莓", 20, 32.0, 223, 260, heroName = "澜莓"),
        CropInfo("黄瓜", 22, 1.0, 229, 509),
        CropInfo("茄子", 24, 8.0, 234, 475),
        CropInfo("南瓜", 26, 16.0, 239, 409),
        CropInfo("柚子", 28, 16.0, 0, 1283, isCoinFocus = true),
        CropInfo("卷心菜", 30, 32.0, 250, 305, heroName = "姬文菜"),
        CropInfo("白萝卜", 32, 1.0, 256, 622),
        CropInfo("葡萄", 34, 8.0, 261, 578),
        CropInfo("生菜", 36, 16.0, 266, 498),
        CropInfo("西瓜", 38, 16.0, 0, 1565, isCoinFocus = true),
        CropInfo("辣椒", 40, 32.0, 277, 371, heroName = "安琪拉椒"),
        CropInfo("洋葱", 42, 1.0, 283, 726),
        CropInfo("花菜", 44, 8.0, 288, 684),
        CropInfo("猕猴桃", 45, 32.0, 290, 402, heroName = "悟空桃"),
        CropInfo("花生", 46, 16.0, 293, 587),
        CropInfo("木瓜", 48, 16.0, 0, 1849, isCoinFocus = true),
        CropInfo("荞麦", 50, 32.0, 304, 438, heroName = "小乔麦"),
        CropInfo("莴笋", 51, 1.0, 310, 868),
        CropInfo("红枣", 52, 8.0, 315, 823),
        CropInfo("橘子", 54, 16.0, 0, 2225, isCoinFocus = true),
        CropInfo("菠萝", 55, 32.0, 331, 527, heroName = "马可菠萝"),
        CropInfo("四季豆", 56, 1.0, 337, 1031),
        CropInfo("冬瓜", 57, 8.0, 342, 963),
        CropInfo("李子", 58, 16.0, 347, 828),
        CropInfo("杨桃", 59, 16.0, 0, 2609, isCoinFocus = true),
        CropInfo("梨子", 60, 32.0, 358, 617, heroName = "公孙梨"),
        CropInfo("甘蔗", 61, 1.0, 364, 1068),
        CropInfo("葫芦", 62, 8.0, 369, 997),
        CropInfo("桃子", 63, 16.0, 374, 856),
        CropInfo("柠檬", 64, 16.0, 0, 2706, isCoinFocus = true),
        CropInfo("樱桃", 65, 32.0, 385, 648, heroName = "云樱桃"),
        CropInfo("枇杷", 69, 16.0, 0, 3455, isCoinFocus = true),
        // ===== S44 赛季新增 (Lv70~79) =====
        CropInfo("灯笼果", 70, 32.0, 412, 662, heroName = "大乔灯"),
        CropInfo("树莓", 71, 1.0, 418, 1548),
        CropInfo("芒果", 72, 8.0, 423, 1428),
        CropInfo("山竹", 73, 16.0, 428, 1227),
        CropInfo("山楂", 74, 16.0, 0, 3882, isCoinFocus = true),
        CropInfo("莲花", 75, 32.0, 439, 769, heroName = "哪吒藕"),
        CropInfo("咖啡豆", 76, 1.0, 445, 1845),
        CropInfo("火龙果", 77, 8.0, 450, 1709),
        CropInfo("石榴", 78, 16.0, 455, 1466),
        CropInfo("牛油果", 79, 16.0, 0, 4551, isCoinFocus = true)
    )

    /** 当前等级可种植的作物列表 */
    fun availableCrops(farmLevel: Int): List<CropInfo> =
        CROPS.filter { it.unlockLevel <= farmLevel }

    /**
     * 推荐种植作物
     *
     * 策略规则（来自高收益攻略共识）：
     *  - 工作日冲经验 → 种当前等级经验/h 最高的作物（快速升级解锁地块与一键功能）
     *  - 周末双倍刷金币 → 产量×2，种金币/h 最高的"经验黑洞"作物
     *  - AUTO = 结合双倍状态自动切换；EXP_FIRST/COIN_FIRST 强制覆盖
     *
     * @param maxGrowHours 可选：期望的最大生长时长（小时），用于匹配离线时长防偷菜
     */
    fun recommend(
        farmLevel: Int,
        strategy: Strategy = Strategy.AUTO,
        isWeekendDouble: Boolean = false,
        maxGrowHours: Double? = null
    ): CropInfo? {
        var candidates = availableCrops(farmLevel).filter { it.expPerHour > 0 || it.coinPerHour > 0 }
        if (candidates.isEmpty()) return null
        if (maxGrowHours != null && maxGrowHours > 0) {
            candidates.filter { it.growHours <= maxGrowHours }
                .takeIf { it.isNotEmpty() }
                ?.let { candidates = it }
        }
        return when (strategy) {
            Strategy.EXP_FIRST -> candidates.maxByOrNull { it.expPerHour }
            Strategy.COIN_FIRST -> candidates.maxByOrNull { it.coinPerHour }
            Strategy.AUTO ->
                if (isWeekendDouble) candidates.maxByOrNull { it.coinPerHour }
                else candidates.maxByOrNull { it.expPerHour }
        }
    }

    /** 英雄变异作物查询（不可被偷，囤货优先级最高） */
    fun heroCrops(farmLevel: Int): List<CropInfo> =
        availableCrops(farmLevel).filter { it.heroName != null }

    /** 按名称查找 */
    fun find(name: String): CropInfo? = CROPS.firstOrNull { it.name == name }
}
