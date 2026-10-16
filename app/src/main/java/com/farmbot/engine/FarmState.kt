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
