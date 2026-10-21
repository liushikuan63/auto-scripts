package com.farmbot.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日社交操作计数器 — 对应王者农场每日限额机制
 *
 *  - 偷菜每日上限 120 次
 *  - 主动祝福每日上限 10 次
 *  - 按自然日(yyyyMMdd)自动归零
 */
object DailyCounters {

    private const val SP_NAME = "farmbot_daily_counters"
    private lateinit var sp: SharedPreferences

/**
 * 初始化每日计数存储
 */
    fun init(context: Context) {
        if (this::sp.isInitialized) return
        sp = context.applicationContext
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    }

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())

    private fun key(base: String): String = "${base}_${today()}"

    @Synchronized
/**
 * 今日已偷菜次数
 */
    fun getStealCount(): Int = if (this::sp.isInitialized) sp.getInt(key("steal"), 0) else 0

    @Synchronized
/**
 * 累加今日偷菜次数
 */
    fun addSteal(n: Int = 1) {
        if (!this::sp.isInitialized) return
        sp.edit().putInt(key("steal"), getStealCount() + n).apply()
    }

    @Synchronized
/**
 * 今日已祝福次数
 */
    fun getBlessCount(): Int = if (this::sp.isInitialized) sp.getInt(key("bless"), 0) else 0

    @Synchronized
/**
 * 累加今日祝福次数
 */
    fun addBless(n: Int = 1) {
        if (!this::sp.isInitialized) return
        sp.edit().putInt(key("bless"), getBlessCount() + n).apply()
    }

    /** 偷菜剩余可执行次数 */
    fun remainingSteal(dailyLimit: Int): Int =
        (dailyLimit - getStealCount()).coerceAtLeast(0)

    /** 祝福剩余可执行次数 */
    fun remainingBless(dailyLimit: Int): Int =
        (dailyLimit - getBlessCount()).coerceAtLeast(0)

    /** 清理过期计数（保留今天与昨天，防止 SP 无限增长） */
    @Synchronized
/**
 * 清理过期的每日计数
 */
    fun cleanup() {
        if (!this::sp.isInitialized) return
        val yesterday = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
            .format(Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000))
        val stalePrefixes = listOf("steal_", "bless_")
        sp.all.keys.toList().forEach { k ->
            val isStale = stalePrefixes.any { p ->
                k.startsWith(p) && !k.endsWith(today()) && !k.endsWith(yesterday)
            }
            if (isStale) sp.edit().remove(k).apply()
        }
    }
}
