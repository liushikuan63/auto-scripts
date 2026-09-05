package com.farmbot.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.preferencesDataStore
import com.farmbot.config.FarmConfig.Companion.DEFAULT
import com.farmbot.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * 配置管理器 — 融合 HonorFarmBot DataStore 版 + 回退到 SharedPreferences
 *
 * 线程安全的阻塞式 getter/setter，便于非协程代码调用。
 */
object ConfigManager {

    private lateinit var appContext: Context
    private lateinit var sp: android.content.SharedPreferences
    @Volatile private var cached: FarmConfig = DEFAULT
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()
    private val generationLock = Any()
    @Volatile private var saveGeneration = 0L

    private val SP_NAME = "farmbot_config_v1"

    @Synchronized fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        cached = loadInternal()
        com.farmbot.util.DailyCounters.init(appContext)
        com.farmbot.util.DailyCounters.cleanup()
        Logger.i("ConfigManager", "init done, waterInterval=${cached.waterIntervalMin}")
    }

    private fun requireContext(): Context {
        check(this::appContext.isInitialized) {
            "ConfigManager 需要先 init(context)！请在 Application.onCreate 或首次 Activity 中调用。"
        }
        return appContext
    }

    // ========================================================
    // 新接口：DataStore (Flow)
    // ========================================================

    fun configFlow(): Flow<FarmConfig> =
        requireContext().configStore.data
            .catch { emit(emptyPreferences()) }
            .map { p -> p.toFarmConfig() }

    suspend fun loadAsync(): FarmConfig = requireContext().configStore.data
        .catch { emit(emptyPreferences()) }
        .first()
        .toFarmConfig()

    suspend fun saveAsync(new: FarmConfig) {
        val generation = synchronized(generationLock) { ++saveGeneration }
        persistenceMutex.withLock {
            if (generation != saveGeneration) return
            requireContext().configStore.edit { p -> new.writeTo(p) }
            if (generation == saveGeneration) {
                cached = new
                // 同步写入 SP 以便非协程代码也能立刻读取
                saveToSp(new)
            }
        }
    }

    // ========================================================
    // 兼容接口：阻塞式 get / set (内部 fallback 到 SP)
    // ========================================================

    fun getConfig(): FarmConfig = cached

    fun save(new: FarmConfig) {
        cached = new
        saveToSp(new)
        val generation = synchronized(generationLock) { ++saveGeneration }
        // 串行持久化且只写入最新版本，避免快速连续保存时旧协程覆盖新配置。
        persistenceScope.launch {
            persistenceMutex.withLock {
                if (generation != saveGeneration) return@withLock
                runCatching {
                    requireContext().configStore.edit { p -> new.writeTo(p) }
                }.onFailure {
                    Logger.e("ConfigManager", "save to DataStore failed", it)
                }
            }
        }
    }

    // ---------------- SP 实现 ----------------

    private fun loadInternal(): FarmConfig = try {
        FarmConfig(
            waterIntervalMin = sp.getInt("water_interval", DEFAULT.waterIntervalMin),
            autoAdjustByPlant = sp.getBoolean("auto_adjust", DEFAULT.autoAdjustByPlant),
            inheritCountdown = sp.getBoolean("inherit_countdown", DEFAULT.inheritCountdown),
            dailyBattle = sp.getBoolean("daily_battle", DEFAULT.dailyBattle),
            surrenderAfterSec = sp.getInt("surrender_sec", DEFAULT.surrenderAfterSec),
            newMode = sp.getBoolean("new_mode", DEFAULT.newMode),
            sellMode = sp.getString("sell_mode", DEFAULT.sellMode.name)
                ?.let { runCatching { FarmConfig.SellMode.valueOf(it) }.getOrDefault(DEFAULT.sellMode) }
                ?: DEFAULT.sellMode,
            accountType = sp.getString("account_type", DEFAULT.accountType.name)
                ?.let { runCatching { FarmConfig.AccountType.valueOf(it) }.getOrDefault(DEFAULT.accountType) }
                ?: DEFAULT.accountType,
            upgradePriority = sp.getString("upgrade_priority", DEFAULT.upgradePriority.name)
                ?.let { runCatching { FarmConfig.UpgradePriority.valueOf(it) }.getOrDefault(DEFAULT.upgradePriority) }
                ?: DEFAULT.upgradePriority,
            upgradeExpandAfter = sp.getBoolean("upgrade_expand", DEFAULT.upgradeExpandAfter),
            expandAlways = sp.getBoolean("expand_always", DEFAULT.expandAlways),
            perfectMode = sp.getBoolean("perfect_mode", DEFAULT.perfectMode),
            enableSteal = sp.getBoolean("enable_steal", DEFAULT.enableSteal),
            enableBless = sp.getBoolean("enable_bless", DEFAULT.enableBless),
            prioritySteal = sp.getBoolean("priority_steal", DEFAULT.prioritySteal),
            farmLevel = sp.getInt("farm_level", DEFAULT.farmLevel),
            cropStrategy = sp.getString("crop_strategy", DEFAULT.cropStrategy.name)
                ?.let { runCatching { FarmConfig.CropStrategy.valueOf(it) }.getOrDefault(DEFAULT.cropStrategy) }
                ?: DEFAULT.cropStrategy,
            respectStealProtection = sp.getBoolean("respect_steal_protection", DEFAULT.respectStealProtection),
            stealBeforeBless = sp.getBoolean("steal_before_bless", DEFAULT.stealBeforeBless),
            stealDailyLimit = sp.getInt("steal_daily_limit", DEFAULT.stealDailyLimit),
            blessDailyLimit = sp.getInt("bless_daily_limit", DEFAULT.blessDailyLimit),
            weekendDoubleAware = sp.getBoolean("weekend_double_aware", DEFAULT.weekendDoubleAware),
            hoardUntilWeekend = sp.getBoolean("hoard_until_weekend", DEFAULT.hoardUntilWeekend),
            weekdayCrop = sp.getString("weekday_crop", DEFAULT.weekdayCrop) ?: DEFAULT.weekdayCrop,
            weekendCrop = sp.getString("weekend_crop", DEFAULT.weekendCrop) ?: DEFAULT.weekendCrop,
            operationDelayMinMs = sp.getInt("delay_min", DEFAULT.operationDelayMinMs),
            operationDelayMaxMs = sp.getInt("delay_max", DEFAULT.operationDelayMaxMs),
            maxDailyOperations = sp.getInt("max_daily_ops", DEFAULT.maxDailyOperations),
            dailyOperationLimit = sp.getInt("daily_limit", DEFAULT.dailyOperationLimit),
            randomOffsetRange = sp.getInt("offset_range", DEFAULT.randomOffsetRange),
            templateMatchThreshold = sp.getFloat("match_threshold", DEFAULT.templateMatchThreshold.toFloat()).toDouble(),
            enableLog = sp.getBoolean("enable_log", DEFAULT.enableLog),
            autoStartWhenGameForeground = sp.getBoolean("auto_start_game_fg", DEFAULT.autoStartWhenGameForeground),
            pauseWhenGameBackground = sp.getBoolean("pause_game_bg", DEFAULT.pauseWhenGameBackground),
            targetPackages = sp.getStringSet("target_packages", null)
                ?.takeIf { it.isNotEmpty() }
                ?.toSet()
                ?: DEFAULT.targetPackages
        )
    } catch (t: Throwable) {
        Logger.e("ConfigManager", "loadInternal fallback to default", t)
        DEFAULT
    }

    private fun saveToSp(c: FarmConfig) {
        sp.edit().apply {
            putInt("water_interval", c.waterIntervalMin)
            putBoolean("auto_adjust", c.autoAdjustByPlant)
            putBoolean("inherit_countdown", c.inheritCountdown)
            putBoolean("daily_battle", c.dailyBattle)
            putInt("surrender_sec", c.surrenderAfterSec)
            putBoolean("new_mode", c.newMode)
            putString("sell_mode", c.sellMode.name)
            putString("account_type", c.accountType.name)
            putString("upgrade_priority", c.upgradePriority.name)
            putBoolean("upgrade_expand", c.upgradeExpandAfter)
            putBoolean("expand_always", c.expandAlways)
            putBoolean("perfect_mode", c.perfectMode)
            putBoolean("enable_steal", c.enableSteal)
            putBoolean("enable_bless", c.enableBless)
            putBoolean("priority_steal", c.prioritySteal)
            putInt("farm_level", c.farmLevel)
            putString("crop_strategy", c.cropStrategy.name)
            putBoolean("respect_steal_protection", c.respectStealProtection)
            putBoolean("steal_before_bless", c.stealBeforeBless)
            putInt("steal_daily_limit", c.stealDailyLimit)
            putInt("bless_daily_limit", c.blessDailyLimit)
            putBoolean("weekend_double_aware", c.weekendDoubleAware)
            putBoolean("hoard_until_weekend", c.hoardUntilWeekend)
            putString("weekday_crop", c.weekdayCrop)
            putString("weekend_crop", c.weekendCrop)
            putInt("delay_min", c.operationDelayMinMs)
            putInt("delay_max", c.operationDelayMaxMs)
            putInt("max_daily_ops", c.maxDailyOperations)
            putInt("daily_limit", c.dailyOperationLimit)
            putInt("offset_range", c.randomOffsetRange)
            putFloat("match_threshold", c.templateMatchThreshold.toFloat())
            putBoolean("enable_log", c.enableLog)
            putBoolean("auto_start_game_fg", c.autoStartWhenGameForeground)
            putBoolean("pause_game_bg", c.pauseWhenGameBackground)
            putStringSet("target_packages", c.targetPackages)
        }.apply()
    }

    // ---------------- DataStore <-> FarmConfig 映射 ----------------

    private object Keys {
        val WATER = intPreferencesKey("water_interval")
        val AUTO_ADJ = booleanPreferencesKey("auto_adjust")
        val INHERIT_CD = booleanPreferencesKey("inherit_countdown")
        val BATTLE = booleanPreferencesKey("daily_battle")
        val SUR_SEC = intPreferencesKey("surrender_sec")
        val NEW_MODE = booleanPreferencesKey("new_mode")
        val SELL = stringPreferencesKey("sell_mode")
        val ACCT = stringPreferencesKey("account_type")
        val UPG_PRIO = stringPreferencesKey("upgrade_priority")
        val UPG_EXP = booleanPreferencesKey("upgrade_expand")
        val EXP_ALW = booleanPreferencesKey("expand_always")
        val PERF = booleanPreferencesKey("perfect_mode")
        val STEAL = booleanPreferencesKey("enable_steal")
        val BLESS = booleanPreferencesKey("enable_bless")
        val PRIORITY_STEAL = booleanPreferencesKey("priority_steal")
        val FARM_LEVEL = intPreferencesKey("farm_level")
        val CROP_STRATEGY = stringPreferencesKey("crop_strategy")
        val RESPECT_PROTECT = booleanPreferencesKey("respect_steal_protection")
        val STEAL_FIRST = booleanPreferencesKey("steal_before_bless")
        val STEAL_LIMIT = intPreferencesKey("steal_daily_limit")
        val BLESS_LIMIT = intPreferencesKey("bless_daily_limit")
        val WEEKEND_AWARE = booleanPreferencesKey("weekend_double_aware")
        val HOARD_WEEKEND = booleanPreferencesKey("hoard_until_weekend")
        val WEEKDAY_CROP = stringPreferencesKey("weekday_crop")
        val WEEKEND_CROP = stringPreferencesKey("weekend_crop")
        val DL_MIN = intPreferencesKey("delay_min")
        val DL_MAX = intPreferencesKey("delay_max")
        val MAX_DAILY = intPreferencesKey("max_daily_ops")
        val DAILY_LIM = intPreferencesKey("daily_limit")
        val OFF = intPreferencesKey("offset_range")
        val THR = doublePreferencesKey("match_threshold")
        val LOG = booleanPreferencesKey("enable_log")
        val AUTO_START_GAME_FG = booleanPreferencesKey("auto_start_game_fg")
        val PAUSE_GAME_BG = booleanPreferencesKey("pause_game_bg")
        val TARGETS = stringSetPreferencesKey("target_packages")
    }

    private fun Preferences.toFarmConfig(): FarmConfig {
        val def = DEFAULT
        return FarmConfig(
            waterIntervalMin = this[Keys.WATER] ?: def.waterIntervalMin,
            autoAdjustByPlant = this[Keys.AUTO_ADJ] ?: def.autoAdjustByPlant,
            inheritCountdown = this[Keys.INHERIT_CD] ?: def.inheritCountdown,
            dailyBattle = this[Keys.BATTLE] ?: def.dailyBattle,
            surrenderAfterSec = this[Keys.SUR_SEC] ?: def.surrenderAfterSec,
            newMode = this[Keys.NEW_MODE] ?: def.newMode,
            sellMode = (this[Keys.SELL] ?: def.sellMode.name).let {
                runCatching { FarmConfig.SellMode.valueOf(it) }.getOrDefault(def.sellMode)
            },
            accountType = (this[Keys.ACCT] ?: def.accountType.name).let {
                runCatching { FarmConfig.AccountType.valueOf(it) }.getOrDefault(def.accountType)
            },
            upgradePriority = (this[Keys.UPG_PRIO] ?: def.upgradePriority.name).let {
                runCatching { FarmConfig.UpgradePriority.valueOf(it) }.getOrDefault(def.upgradePriority)
            },
            upgradeExpandAfter = this[Keys.UPG_EXP] ?: def.upgradeExpandAfter,
            expandAlways = this[Keys.EXP_ALW] ?: def.expandAlways,
            perfectMode = this[Keys.PERF] ?: def.perfectMode,
            enableSteal = this[Keys.STEAL] ?: def.enableSteal,
            enableBless = this[Keys.BLESS] ?: def.enableBless,
            prioritySteal = this[Keys.PRIORITY_STEAL] ?: def.prioritySteal,
            farmLevel = this[Keys.FARM_LEVEL] ?: def.farmLevel,
            cropStrategy = (this[Keys.CROP_STRATEGY] ?: def.cropStrategy.name).let {
                runCatching { FarmConfig.CropStrategy.valueOf(it) }.getOrDefault(def.cropStrategy)
            },
            respectStealProtection = this[Keys.RESPECT_PROTECT] ?: def.respectStealProtection,
            stealBeforeBless = this[Keys.STEAL_FIRST] ?: def.stealBeforeBless,
            stealDailyLimit = this[Keys.STEAL_LIMIT] ?: def.stealDailyLimit,
            blessDailyLimit = this[Keys.BLESS_LIMIT] ?: def.blessDailyLimit,
            weekendDoubleAware = this[Keys.WEEKEND_AWARE] ?: def.weekendDoubleAware,
            hoardUntilWeekend = this[Keys.HOARD_WEEKEND] ?: def.hoardUntilWeekend,
            weekdayCrop = this[Keys.WEEKDAY_CROP] ?: def.weekdayCrop,
            weekendCrop = this[Keys.WEEKEND_CROP] ?: def.weekendCrop,
            operationDelayMinMs = this[Keys.DL_MIN] ?: def.operationDelayMinMs,
            operationDelayMaxMs = this[Keys.DL_MAX] ?: def.operationDelayMaxMs,
            maxDailyOperations = this[Keys.MAX_DAILY] ?: def.maxDailyOperations,
            dailyOperationLimit = this[Keys.DAILY_LIM] ?: def.dailyOperationLimit,
            randomOffsetRange = this[Keys.OFF] ?: def.randomOffsetRange,
            templateMatchThreshold = this[Keys.THR] ?: def.templateMatchThreshold,
            enableLog = this[Keys.LOG] ?: def.enableLog,
            autoStartWhenGameForeground = this[Keys.AUTO_START_GAME_FG] ?: def.autoStartWhenGameForeground,
            pauseWhenGameBackground = this[Keys.PAUSE_GAME_BG] ?: def.pauseWhenGameBackground,
            targetPackages = this[Keys.TARGETS]
                ?.takeIf { it.isNotEmpty() }
                ?: def.targetPackages
        )
    }

    private suspend fun FarmConfig.writeTo(m: MutablePreferences) {
        m[Keys.WATER] = waterIntervalMin
        m[Keys.AUTO_ADJ] = autoAdjustByPlant
        m[Keys.INHERIT_CD] = inheritCountdown
        m[Keys.BATTLE] = dailyBattle
        m[Keys.SUR_SEC] = surrenderAfterSec
        m[Keys.NEW_MODE] = newMode
        m[Keys.SELL] = sellMode.name
        m[Keys.ACCT] = accountType.name
        m[Keys.UPG_PRIO] = upgradePriority.name
        m[Keys.UPG_EXP] = upgradeExpandAfter
        m[Keys.EXP_ALW] = expandAlways
        m[Keys.PERF] = perfectMode
        m[Keys.STEAL] = enableSteal
        m[Keys.BLESS] = enableBless
        m[Keys.PRIORITY_STEAL] = prioritySteal
        m[Keys.FARM_LEVEL] = farmLevel
        m[Keys.CROP_STRATEGY] = cropStrategy.name
        m[Keys.RESPECT_PROTECT] = respectStealProtection
        m[Keys.STEAL_FIRST] = stealBeforeBless
        m[Keys.STEAL_LIMIT] = stealDailyLimit
        m[Keys.BLESS_LIMIT] = blessDailyLimit
        m[Keys.WEEKEND_AWARE] = weekendDoubleAware
        m[Keys.HOARD_WEEKEND] = hoardUntilWeekend
        m[Keys.WEEKDAY_CROP] = weekdayCrop
        m[Keys.WEEKEND_CROP] = weekendCrop
        m[Keys.DL_MIN] = operationDelayMinMs
        m[Keys.DL_MAX] = operationDelayMaxMs
        m[Keys.MAX_DAILY] = maxDailyOperations
        m[Keys.DAILY_LIM] = dailyOperationLimit
        m[Keys.OFF] = randomOffsetRange
        m[Keys.THR] = templateMatchThreshold
        m[Keys.LOG] = enableLog
        m[Keys.AUTO_START_GAME_FG] = autoStartWhenGameForeground
        m[Keys.PAUSE_GAME_BG] = pauseWhenGameBackground
        m[Keys.TARGETS] = targetPackages
    }
}

// Required extension from androidx.datastore
private val Context.configStore by preferencesDataStore(name = "farmbot_config_v2")
