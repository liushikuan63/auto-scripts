package com.farmbot.engine.states

import com.farmbot.engine.FarmState
import com.farmbot.engine.runtime.FarmRuntime
import com.farmbot.engine.runtime.FarmStateContext
import com.farmbot.engine.runtime.MovementSupport
import com.farmbot.engine.runtime.OcrClickSupport
import com.farmbot.engine.runtime.OneKeyFarmingSupport

/**
 * 测试用假上下文：只实现处理器真正需要的接缝。
 *
 * 之所以能脱离设备构造：FarmRuntime 的 ocr / matcher 已改为懒初始化，
 * 而 MovementSupport / OneKeyFarmingSupport 的构造不触碰 Android API。
 * gotoState 只记录调用，不触发真实状态机流转。
 */
internal class FakeFarmStateContext(
    override val rt: FarmRuntime = FarmRuntime()
) : FarmStateContext {

    override val ocrClick = OcrClickSupport(rt)
    override val movement = MovementSupport(rt, ocrClick)
    override val oneKey = OneKeyFarmingSupport(rt, ocrClick, movement)

    /** 记录处理器请求过的状态跳转，替代真实 transitionToInternal */
    val transitions = mutableListOf<FarmState>()

    var recoverCalls = mutableListOf<FarmState>()

    override fun gotoState(next: FarmState) {
        transitions += next
    }

    override suspend fun handleRecover(failedState: FarmState) {
        recoverCalls += failedState
    }

    override suspend fun <T> withErrorLog(op: String, block: suspend () -> T?): T? = block()

    /** 便捷断言：本次执行产生的日志条目 */
    fun logs(): List<String> = rt.logs.value
}
