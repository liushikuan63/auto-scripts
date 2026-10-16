package com.farmbot.engine

/**
 * 农场状态机状态定义
 *
 * 由 FarmStateMachine.kt 拆分而来：状态声明与 displayName 类体原样搬迁，未做逻辑改动。
 */
sealed class FarmState {
    object Idle : FarmState()
    object NavigateToFarm : FarmState()
    object RecoverGame : FarmState()     // 异常恢复：游戏重启/重连
    object ReconnectA11y : FarmState()   // 异常恢复：无障碍服务重连
    object ScanCrops : FarmState()
    object WaterCrops : FarmState()
    object HarvestCrops : FarmState()
    object SellOrStore : FarmState()
    object VisitFriends : FarmState()
    object DailyBattle : FarmState()
    object UpgradeDecision : FarmState()

    fun displayName(): String = when (this) {
        Idle -> "空闲"
        NavigateToFarm -> "导航至农场"
        RecoverGame -> "异常恢复(重启游戏)"
        ReconnectA11y -> "异常恢复(重连无障碍)"
        ScanCrops -> "扫描作物"
        WaterCrops -> "浇水中"
        HarvestCrops -> "收获中"
        SellOrStore -> "售卖/存储"
        VisitFriends -> "拜访好友"
        DailyBattle -> "每日对战"
        UpgradeDecision -> "升级决策"
    }
}

/**
 * 单状态执行器契约
 *
 * 第 1 步只定义接口、不接线，供后续按状态拆分实现类时实现。
 */
interface FarmStateHandler {
    /** 本处理器负责的状态 */
    val state: FarmState

    /** 执行该状态的一轮逻辑 */
    suspend fun execute()
}
