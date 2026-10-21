package com.farmbot.policy

import android.content.Context
import com.farmbot.config.ConfigManager
import com.farmbot.config.FarmConfig
import com.farmbot.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * 策略管理器 — 插件化策略的加载 / 保存 / 切换 / 导入 / 导出
 *
 * 存储路径：`context.getExternalFilesDir(null)/FarmBot/policies/<id>.json`
 *
 * 当前激活策略 ID 通过 SharedPreferences 保存，启动时自动恢复。
 * 激活策略会立即同步覆盖到 [FarmConfig]（ConfigManager）。
 */
class PolicyManager(private val context: Context) {

    companion object {
        private const val DIR_NAME = "FarmBot/policies"
        private const val SP_NAME = "farmbot_policy"
        private const val KEY_ACTIVE = "active_policy_id"
        private const val KEY_INIT_DONE = "builtin_seeded"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dir: File = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }
    private val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private val _activePolicy = MutableStateFlow(CropPolicy.DEFAULT)
/**
 * 当前生效的作物策略流
 */
    val activePolicy: StateFlow<CropPolicy> = _activePolicy.asStateFlow()

    private val _policies = MutableStateFlow<List<CropPolicy>>(emptyList())
/**
 * 全部已保存策略的流
 */
    val policies: StateFlow<List<CropPolicy>> = _policies.asStateFlow()

    init {
        seedBuiltinIfNeeded()
        reload()
        // 恢复激活策略
        val activeId = sp.getString(KEY_ACTIVE, "default") ?: "default"
        activate(activeId, syncToConfig = false)
    }

    // ==================== 内置策略 ====================

    /** 首次启动时把内置策略写到磁盘 */
    private fun seedBuiltinIfNeeded() {
        if (sp.getBoolean(KEY_INIT_DONE, false)) return
        CropPolicy.BUILTIN_LIST.forEach { p ->
            val f = File(dir, "${p.id}.json")
            if (!f.exists()) writePolicy(p)
        }
        sp.edit().putBoolean(KEY_INIT_DONE, true).apply()
        Timber.i("PolicyManager: 内置策略已种入 ${dir.absolutePath}")
    }

    // ==================== 列表与加载 ====================

    fun reload() {
        val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { json.decodeFromString(CropPolicy.serializer(), f.readText()) }.getOrNull() }
            ?: emptyList()
        _policies.value = list.sortedBy { it.id }
        Logger.i("PolicyManager", "loaded ${list.size} policies from ${dir.absolutePath}")
    }

/**
 * 按 id 取策略
 */
    fun getPolicy(id: String): CropPolicy? =
        _policies.value.firstOrNull { it.id == id } ?: readPolicy(id)

/**
 * 写入 / 更新一条策略
 */
    fun writePolicy(p: CropPolicy) {
        val f = File(dir, "${p.id}.json")
        f.writeText(json.encodeToString(CropPolicy.serializer(), p))
    }

    private fun readPolicy(id: String): CropPolicy? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(CropPolicy.serializer(), f.readText()) }
            .onFailure { Logger.e("PolicyManager", "readPolicy $id", it) }
            .getOrNull()
    }

    // ==================== 激活策略 ====================

    /**
     * 激活指定策略；若 syncToConfig=true，则把策略字段同步覆盖到 FarmConfig
     */
    fun activate(id: String, syncToConfig: Boolean = true): Boolean {
        val p = getPolicy(id) ?: run {
            Logger.w("PolicyManager", "策略不存在: $id，回退 default")
            _activePolicy.value = CropPolicy.DEFAULT
            sp.edit().putString(KEY_ACTIVE, "default").apply()
            return false
        }
        _activePolicy.value = p
        sp.edit().putString(KEY_ACTIVE, p.id).apply()
        Logger.i("PolicyManager", "激活策略: ${p.displayName} (${p.id})")
        if (syncToConfig) syncToConfig(p)
        return true
    }

    /** 把策略字段同步写入 FarmConfig */
    fun syncToConfig(p: CropPolicy) {
        val cur = ConfigManager.getConfig()
        val newCfg = cur.copy(
            waterIntervalMin = p.waterIntervalMin,
            autoAdjustByPlant = p.autoAdjustByPlant,
            inheritCountdown = p.inheritCountdown,
            enableSteal = p.enableSteal,
            enableBless = p.enableBless,
            dailyBattle = p.dailyBattle,
            surrenderAfterSec = p.surrenderAfterSec,
            sellMode = runCatching { FarmConfig.SellMode.valueOf(p.sellMode) }.getOrDefault(FarmConfig.SellMode.NONE),
            upgradePriority = runCatching { FarmConfig.UpgradePriority.valueOf(p.upgradePriority) }.getOrDefault(FarmConfig.UpgradePriority.LAND_FIRST),
            expandAlways = p.expandAlways,
            perfectMode = p.perfectMode
        )
        ConfigManager.save(newCfg)
    }

    // ==================== 增删改 ====================

    fun saveOrUpdate(p: CropPolicy) {
        writePolicy(p)
        reload()
        if (_activePolicy.value.id == p.id) _activePolicy.value = p
    }

/**
 * 删除一条策略
 */
    fun delete(id: String): Boolean {
        if (id == "default") {
            Logger.w("PolicyManager", "默认策略不可删除")
            return false
        }
        val f = File(dir, "$id.json")
        val ok = if (f.exists()) f.delete() else false
        if (ok) {
            reload()
            if (_activePolicy.value.id == id) activate("default")
        }
        return ok
    }

    /** 复制一份策略（新 id），用于"另存为" */
    fun duplicate(srcId: String, newId: String, newDisplayName: String): CropPolicy? {
        val src = getPolicy(srcId) ?: return null
        val copy = src.copy(id = newId, displayName = newDisplayName)
        writePolicy(copy)
        reload()
        return copy
    }

    // ==================== 导入 / 导出 ====================

    /** 导出单个策略为 JSON 文件 */
    fun exportPolicy(id: String, targetFile: File): Boolean {
        val p = getPolicy(id) ?: return false
        targetFile.writeText(json.encodeToString(CropPolicy.serializer(), p))
        return true
    }

    /** 从 JSON 文件导入策略 */
    fun importPolicy(jsonFile: File): CropPolicy? {
        return runCatching {
            val p = json.decodeFromString(CropPolicy.serializer(), jsonFile.readText())
            writePolicy(p)
            reload()
            p
        }.onFailure { Logger.e("PolicyManager", "importPolicy", it) }.getOrNull()
    }

    /** 导出所有策略为单个 JSON 数组（备份用） */
    fun exportAll(targetFile: File): Boolean = runCatching {
        val arr = _policies.value
        targetFile.writeText(json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CropPolicy.serializer()),
            arr
        ))
        true
    }.getOrDefault(false)

    /** 从 JSON 数组文件批量导入 */
    fun importAll(jsonFile: File): Int = runCatching {
        val list = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(CropPolicy.serializer()),
            jsonFile.readText()
        )
        list.forEach { writePolicy(it) }
        reload()
        list.size
    }.getOrDefault(0)

/**
 * 策略文件所在目录
 */
    fun getDir(): File = dir
}
