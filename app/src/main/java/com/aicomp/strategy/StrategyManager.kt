package com.aicomp.strategy

import android.content.Context
import android.util.Log
import com.aicomp.settings.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 动态 JSON 策略管理器（单例内存缓存架构）
 *
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  网络请求规则（严格遵守）：                                    ║
 * ║  - 只在 App 初始化时拉取一次远程策略                           ║
 * ║  - 只在用户手动点击"检查策略更新"时触发拉取                     ║
 * ║  - AI 3秒循环调度器中 **严禁** 发起网络请求                    ║
 * ║  - 所有 prompt 拼装只读取内存中的 @Volatile strategyJson      ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * 生命周期：
 *   1. App 启动 → init() → 同步加载 assets 默认策略 / 本地缓存
 *   2. App 启动 → fetchRemoteStrategy() → 异步拉取最新策略（一次性）
 *   3. AI 循环 → buildDynamicUserPrompt() → 纯内存读取，零网络开销
 *   4. 用户手动 → manualRefresh() → 重新拉取远程策略
 */
object StrategyManager {

    private const val TAG = "StrategyManager"
    private const val CACHE_FILE_NAME = "strategy_cache.json"

    /**
     * 内存缓存的策略 JSON 对象
     * @Volatile 保证 IO 线程（fetchRemoteStrategy）写入后，
     * AI 分析线程（buildDynamicUserPrompt）能立即看到最新值
     */
    @Volatile
    private var strategyJson: JSONObject? = null

    private var context: Context? = null

    /**
     * 防重复拉取锁（协程 Mutex，防止多次调用 fetchRemoteStrategy 竞争）
     */
    private val fetchMutex = Mutex()

    /**
     * 是否已完成首次加载（从本地缓存或 assets）
     */
    @Volatile
    private var isInitialized = false

    /**
     * 是否正在拉取远程策略（防重复触发）
     */
    @Volatile
    private var isFetching = false

    // ═══════════════════════════════════════════
    //  初始化（只调用一次，同步，无网络）
    // ═══════════════════════════════════════════

    /**
     * 初始化：同步加载本地缓存或 assets 默认策略
     * 必须在 Application 或 MainActivity.onCreate 中调用
     *
     * 此方法不发起任何网络请求，纯本地 I/O
     */
    fun init(appContext: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }
        context = appContext.applicationContext
        loadLocalStrategy()
        isInitialized = true
        Log.d(TAG, "Initialized with ${getAvailableScenes().size} scenes (local only)")
    }

    // ═══════════════════════════════════════════
    //  远程拉取（仅限 App 启动 / 用户手动触发）
    // ═══════════════════════════════════════════

    /**
     * 异步更新策略（从远程 URL 拉取）
     * 成功后自动写入本地缓存 + 更新内存
     *
     * 调用时机（且仅限以下场景）：
     *   1. App 启动时（ViewModel.init 中 viewModelScope.launch）
     *   2. 用户在设置页手动点击"检查策略更新"
     *
     * ⚠️ 严禁在 AI 分析循环中调用此方法
     */
    suspend fun fetchRemoteStrategy(): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Not initialized yet, cannot fetch")
            return false
        }

        // 防重复拉取
        if (isFetching) {
            Log.d(TAG, "Already fetching, skipping duplicate request")
            return false
        }

        fetchMutex.withLock {
            if (isFetching) return false
            isFetching = true
        }

        return try {
            doFetchRemote()
        } finally {
            isFetching = false
        }
    }

    /**
     * 用户手动触发"检查策略更新"
     * 可从 SettingsActivity 调用
     */
    suspend fun manualRefresh(): Boolean {
        Log.d(TAG, "Manual refresh triggered by user")
        return fetchRemoteStrategy()
    }

    private suspend fun doFetchRemote(): Boolean = withContext(Dispatchers.IO) {
        val url = ConfigManager.strategyUrl
        if (url.isBlank()) {
            Log.d(TAG, "No remote strategy URL configured, using local")
            return@withContext false
        }

        return@withContext try {
            Log.d(TAG, "Fetching strategy from: $url")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)

                if (!json.has("scenes")) {
                    Log.w(TAG, "Remote strategy missing 'scenes' key, ignoring")
                    return@withContext false
                }

                // 原子更新内存缓存（@Volatile 保证线程可见性）
                strategyJson = json
                saveCache(body)
                Log.d(TAG, "Strategy updated from remote (${json.optJSONObject("scenes")?.length() ?: 0} scenes)")
                true
            } else {
                Log.w(TAG, "Strategy fetch failed: HTTP ${conn.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Strategy fetch error: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════
    //  纯内存读取（AI 循环调用，零网络开销）
    // ═══════════════════════════════════════════

    /**
     * 获取场景约束参数
     * ⚡ 纯内存读取，无任何 I/O
     */
    fun getConstraint(sceneKey: String, constraintKey: String): Any? {
        val json = strategyJson ?: return null
        return try {
            val scenes = json.optJSONObject("scenes") ?: return null
            val scene = scenes.optJSONObject(sceneKey) ?: return null
            val constraints = scene.optJSONObject("constraints") ?: return null
            constraints.opt(constraintKey)
        } catch (e: Exception) {
            Log.w(TAG, "getConstraint error: $sceneKey/$constraintKey", e)
            null
        }
    }

    /**
     * 获取场景相机参数
     * ⚡ 纯内存读取，无任何 I/O
     */
    fun getCameraHint(sceneKey: String, hintKey: String): Any? {
        val json = strategyJson ?: return null
        return try {
            val scenes = json.optJSONObject("scenes") ?: return null
            val scene = scenes.optJSONObject(sceneKey) ?: return null
            val hints = scene.optJSONObject("camera_hints") ?: return null
            hints.opt(hintKey)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取全局参数
     * ⚡ 纯内存读取，无任何 I/O
     */
    fun getGlobal(key: String): Any? {
        val json = strategyJson ?: return null
        return json.optJSONObject("global")?.opt(key)
    }

    /**
     * 获取场景描述文本
     * ⚡ 纯内存读取，无任何 I/O
     */
    fun getSceneDescription(sceneKey: String): String {
        val json = strategyJson ?: return ""
        return try {
            val scenes = json.optJSONObject("scenes") ?: return ""
            val scene = scenes.optJSONObject(sceneKey) ?: return ""
            scene.optString("description", "")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 将策略约束格式化为 System Prompt 上下文段落
     * ⚡ 纯内存读取，无任何 I/O
     */
    fun buildContextForScene(sceneKey: String): String {
        val json = strategyJson ?: return ""
        val scenes = json.optJSONObject("scenes") ?: return ""
        val scene = scenes.optJSONObject(sceneKey) ?: return ""

        return buildString {
            appendLine("## 拍摄场景约束")
            val desc = scene.optString("description", sceneKey)
            appendLine("场景: $desc")

            val constraints = scene.optJSONObject("constraints")
            if (constraints != null) {
                appendLine("构图约束:")
                val keys = constraints.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = constraints.get(key)
                    appendLine("  - $key: $value")
                }
            }

            val hints = scene.optJSONObject("camera_hints")
            if (hints != null) {
                appendLine("相机参数建议:")
                val keys = hints.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = hints.get(key)
                    appendLine("  - $key: $value")
                }
            }
        }
    }

    /**
     * 获取所有可用场景的键名列表
     * ⚡ 纯内存读取，无任何 I/O
     */
    fun getAvailableScenes(): List<String> {
        val json = strategyJson ?: return emptyList()
        val scenes = json.optJSONObject("scenes") ?: return emptyList()
        return scenes.keys().asSequence().toList()
    }

    // ═══════════════════════════════════════════
    //  动态用户提示词组装
    // ═══════════════════════════════════════════

    /**
     * 为指定场景构建动态用户提示词
     * ⚡ 纯内存读取，无任何 I/O
     * 供 QwenProvider 在 AI 分析循环中调用
     */
    fun buildDynamicUserPrompt(sceneKey: String): String {
        val json = strategyJson ?: return "请分析取景画面，给出构图调整指令。"
        val scenes = json.optJSONObject("scenes") ?: return "请分析取景画面，给出构图调整指令。"
        val scene = scenes.optJSONObject(sceneKey) ?: return "请分析取景画面，给出构图调整指令。"

        return buildString {
            val strategyName = scene.optString("description", sceneKey)
            appendLine("当前拍摄场景：【$strategyName】")

            // 构图约束参数
            appendLine("构图参数参考：")
            val constraints = scene.optJSONObject("constraints")
            if (constraints != null) {
                val keys = constraints.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = constraints.get(key)
                    appendLine("- ${formatConstraintKey(key)}：$value")
                }
            }

            // 姿势/动作建议词库
            val feedbackLibrary = scene.optJSONObject("feedback_library")
            if (feedbackLibrary != null) {
                val values = feedbackLibrary.optJSONArray("values")
                if (values != null && values.length() > 0) {
                    appendLine()
                    appendLine("推荐姿势/动作（根据画面选择最合适的）：")
                    for (i in 0 until values.length()) {
                        appendLine("  ${i + 1}. ${values.getString(i)}")
                    }
                }
            }

            // 拍摄技巧
            val tips = scene.optJSONArray("tips")
            if (tips != null && tips.length() > 0) {
                appendLine()
                appendLine("拍摄技巧：")
                for (i in 0 until tips.length()) {
                    appendLine("  • ${tips.getString(i)}")
                }
            }

            // 通用摆姿规则
            val global = json.optJSONObject("global")
            if (global != null) {
                val rules = global.optJSONArray("universal_posing_rules")
                if (rules != null && rules.length() > 0) {
                    appendLine()
                    appendLine("通用摆姿要点：")
                    for (i in 0 until rules.length()) {
                        appendLine("  ✓ ${rules.getString(i)}")
                    }
                }

                // 常见错误
                val mistakes = global.optJSONArray("common_mistakes")
                if (mistakes != null && mistakes.length() > 0) {
                    appendLine()
                    appendLine("请检查是否犯了以下错误：")
                    for (i in 0 until mistakes.length()) {
                        appendLine("  ✗ ${mistakes.getString(i)}")
                    }
                }
            }

            appendLine()
            appendLine("请分析随附的预览图，综合构图和人物姿势，给出调整指令或姿势建议。")
        }
    }

    private fun formatConstraintKey(key: String): String {
        return when (key) {
            "foot_y_target" -> "脚部理想 Y 坐标"
            "foot_y_tolerance" -> "脚部 Y 坐标允许误差"
            "foot_y_range" -> "脚部 Y 坐标范围"
            "head_y_target" -> "头部理想 Y 坐标"
            "head_y_range" -> "头部 Y 坐标范围"
            "head_top_margin_ratio" -> "头部顶部留白比例"
            "face_y_range" -> "面部 Y 坐标范围"
            "face_size_range" -> "面部大小范围"
            "subject_width_range" -> "主体宽度范围"
            "subject_size_range" -> "主体大小范围"
            "subject_y_range" -> "主体 Y 坐标范围"
            "ideal_ev_offset" -> "理想曝光补偿偏移"
            "rule_of_thirds_weight" -> "三分法权重"
            "center_bias" -> "中心偏移量"
            "horizon_y_range" -> "地平线 Y 坐标范围"
            "foreground_ratio" -> "前景占比"
            "vertical_alignment_weight" -> "垂直对齐权重"
            "symmetry_weight" -> "对称性权重"
            "max_roll_angle" -> "最大倾斜角"
            else -> key.replace("_", " ")
        }
    }

    // ═══════════════════════════════════════════
    //  本地 I/O（仅 init / fetchRemoteStrategy 内部调用）
    // ═══════════════════════════════════════════

    /**
     * 同步加载本地策略（assets 默认 + 文件缓存）
     * 仅在 init() 中调用，不涉及网络
     */
    private fun loadLocalStrategy() {
        val ctx = context ?: return

        // 1. 尝试读取本地缓存文件
        val cacheFile = File(ctx.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists()) {
            try {
                val cached = cacheFile.readText()
                val json = JSONObject(cached)
                if (json.has("scenes")) {
                    strategyJson = json
                    Log.d(TAG, "Loaded strategy from cache (${json.optJSONObject("scenes")?.length() ?: 0} scenes)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache parse failed, falling back to default", e)
            }
        }

        // 2. 回退到 assets 默认策略
        try {
            val defaultJson = ctx.assets.open("default_strategy.json").bufferedReader().readText()
            strategyJson = JSONObject(defaultJson)
            Log.d(TAG, "Loaded default strategy from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default strategy", e)
        }
    }

    private fun saveCache(jsonString: String) {
        val ctx = context ?: return
        try {
            File(ctx.filesDir, CACHE_FILE_NAME).writeText(jsonString)
            Log.d(TAG, "Strategy cached to local file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache strategy", e)
        }
    }
}
