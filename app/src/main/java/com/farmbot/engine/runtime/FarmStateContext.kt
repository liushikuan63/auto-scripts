/**
 * 状态处理器的上下文契约
 */

package com.farmbot.engine.runtime

import com.farmbot.engine.FarmState

/**
 * 状态处理器的上下文契约
 *
 * 由 FarmStateMachine 实现。处理器只依赖本接口，不直接依赖状态机，
 * 因此每个处理器都可以用假上下文在 JVM 单测里构造。
 */
internal interface FarmStateContext {
/**
 * 运行时：共享依赖、计数与日志 */
    val rt: FarmRuntime
/**
 * OCR 定位与点击链路 */
    val ocrClick: OcrClickSupport
/**
 * 走位支撑 */
    val movement: MovementSupport
/**
 * 一键务农与选种 */
    val oneKey: OneKeyFarmingSupport

    /** 在当前状态的互斥锁内同步切换状态（等价于原 transitionToInternal） */
    fun gotoState(next: FarmState)

    /** 异常恢复三级机制入口（重试 → 重连无障碍 → 重启游戏） */
    suspend fun handleRecover(failedState: FarmState)

    /** 异常包裹：记录日志并返回 null，不触发恢复流程 */
    suspend fun <T> withErrorLog(op: String, block: suspend () -> T?): T?
}