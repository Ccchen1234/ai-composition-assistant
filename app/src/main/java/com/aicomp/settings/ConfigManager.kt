package com.aicomp.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 全局配置管理器（千问 Qwen3-VL-Flash 专用）
 *
 * 非敏感配置 → 普通 SharedPreferences
 * API Key → EncryptedSharedPreferences (AES-256-SIV + AES-256-GCM)
 */
object ConfigManager {

    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "ai_composition_prefs"
    private const val ENCRYPTED_PREFS_NAME = "ai_composition_secure"

    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences

    // ====== Key 常量 ======

    // AI 检测开关
    private const val KEY_BODY_DETECTION = "body_detection_enabled"
    private const val KEY_POSE_GUIDANCE = "pose_guidance_enabled"
    private const val KEY_FACE_GUIDANCE = "face_guidance_enabled"
    private const val KEY_COMPOSITION_PITFALL = "composition_pitfall_enabled"

    // 交互反馈
    private const val KEY_VOICE_GUIDANCE = "voice_guidance_enabled"
    private const val KEY_POSITIVE_ENCOURAGEMENT = "positive_encouragement_enabled"
    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_PROMPT_INTERVAL = "prompt_interval_ms"

    // 自动快门
    private const val KEY_AUTO_SHUTTER_ENABLED = "auto_shutter_enabled"
    private const val KEY_AUTO_SHUTTER_DELAY = "auto_shutter_delay_ms"
    private const val KEY_COUNTDOWN_SHUTTER = "countdown_shutter_enabled"
    private const val KEY_SILENT_SHUTTER = "silent_shutter_enabled"

    // 视觉自定义
    private const val KEY_GRID_COLOR = "grid_color"
    private const val KEY_GRID_ALPHA = "grid_alpha"
    private const val KEY_GRID_WIDTH = "grid_width"
    private const val KEY_DEBUG_INFO = "debug_info_enabled"

    // AI 配置
    private const val KEY_AI_ENABLED = "ai_guidance_enabled"
    private const val KEY_BASE_URL = "openai_base_url"
    private const val KEY_MODEL_NAME = "openai_model"

    // 策略管理器
    private const val KEY_STRATEGY_URL = "strategy_url"

    // API Key (加密存储)
    private const val SECURE_KEY_API_KEY = "secure_api_key"

    // ====== 默认值 ======
    private const val DEFAULT_BODY_DETECTION = true
    private const val DEFAULT_POSE_GUIDANCE = true
    private const val DEFAULT_FACE_GUIDANCE = true
    private const val DEFAULT_COMPOSITION_PITFALL = true
    private const val DEFAULT_VOICE = false
    private const val DEFAULT_POSITIVE = true
    private const val DEFAULT_HAPTIC = true
    private const val DEFAULT_PROMPT_INTERVAL = 1500L
    private const val DEFAULT_AUTO_SHUTTER = true
    private const val DEFAULT_COUNTDOWN_SHUTTER = false
    private const val DEFAULT_SILENT = false
    private const val DEFAULT_GRID_COLOR = "green"
    private const val DEFAULT_GRID_ALPHA = 0.5f
    private const val DEFAULT_GRID_WIDTH = 2f
    private const val DEFAULT_DEBUG = false
    private const val DEFAULT_AI_ENABLED = false
    private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private const val DEFAULT_MODEL_NAME = "qwen3-vl-flash"
    private const val DEFAULT_STRATEGY_URL = "https://gist.githubusercontent.com/Ccchen1234/23bef624744e357018cfe3a40b7e0c09/raw/config.json"

    // ═══════════════════════════════════════════
    //  API 配置数据类
    // ═══════════════════════════════════════════

    data class ApiConfig(
        val apiKey: String,
        val baseUrl: String,
        val modelName: String
    )

    val apiConfig: ApiConfig get() = ApiConfig(
        apiKey = apiKey,
        baseUrl = baseUrl,
        modelName = modelName
    )

    // ═══════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            securePrefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init encrypted prefs, falling back", e)
            securePrefs = context.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
        }

        migrateOldApiKeys()
        migrateOldBaseUrl()
    }

    /**
     * 迁移旧的 Base URL 默认值（api.openai.com → dashscope）
     */
    private fun migrateOldBaseUrl() {
        val currentUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
        // 如果用户从未修改过（仍为旧默认值），自动更新为新默认值
        if (currentUrl == "https://api.openai.com" || currentUrl == "https://api.openai.com/") {
            prefs.edit().putString(KEY_BASE_URL, DEFAULT_BASE_URL).apply()
            Log.d(TAG, "Migrated Base URL from openai.com to dashscope")
        }
    }

    /**
     * 迁移旧版本多 Key 到单一 Key（一次性）
     */
    private fun migrateOldApiKeys() {
        if (!securePrefs.contains(SECURE_KEY_API_KEY)) {
            val oldKey = securePrefs.getString("secure_openai_key", "")
                ?: securePrefs.getString("secure_gemini_key", "")
                ?: ""
            if (oldKey.isNotBlank()) {
                securePrefs.edit().putString(SECURE_KEY_API_KEY, oldKey).apply()
                Log.d(TAG, "Migrated old API key to unified storage")
            }
        }
    }

    // ═══════════════════════════════════════════
    //  读取 - AI 检测开关
    // ═══════════════════════════════════════════

    val isBodyDetectionEnabled: Boolean get() = prefs.getBoolean(KEY_BODY_DETECTION, DEFAULT_BODY_DETECTION)
    val isPoseGuidanceEnabled: Boolean get() = prefs.getBoolean(KEY_POSE_GUIDANCE, DEFAULT_POSE_GUIDANCE)
    val isFaceGuidanceEnabled: Boolean get() = prefs.getBoolean(KEY_FACE_GUIDANCE, DEFAULT_FACE_GUIDANCE)
    val isCompositionPitfallEnabled: Boolean get() = prefs.getBoolean(KEY_COMPOSITION_PITFALL, DEFAULT_COMPOSITION_PITFALL)

    // ═══════════════════════════════════════════
    //  读取 - 交互反馈
    // ═══════════════════════════════════════════

    val isVoiceGuidanceEnabled: Boolean get() = prefs.getBoolean(KEY_VOICE_GUIDANCE, DEFAULT_VOICE)
    val isPositiveEncouragementEnabled: Boolean get() = prefs.getBoolean(KEY_POSITIVE_ENCOURAGEMENT, DEFAULT_POSITIVE)
    val isHapticEnabled: Boolean get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, DEFAULT_HAPTIC)
    val promptIntervalMs: Long get() = prefs.getLong(KEY_PROMPT_INTERVAL, DEFAULT_PROMPT_INTERVAL)

    // ═══════════════════════════════════════════
    //  读取 - 自动快门
    // ═══════════════════════════════════════════

    val isAutoShutterEnabled: Boolean get() = prefs.getBoolean(KEY_AUTO_SHUTTER_ENABLED, DEFAULT_AUTO_SHUTTER)
    val isCountdownShutterEnabled: Boolean get() = prefs.getBoolean(KEY_COUNTDOWN_SHUTTER, DEFAULT_COUNTDOWN_SHUTTER)
    val isSilentShutterEnabled: Boolean get() = prefs.getBoolean(KEY_SILENT_SHUTTER, DEFAULT_SILENT)

    // ═══════════════════════════════════════════
    //  读取 - 视觉自定义
    // ═══════════════════════════════════════════

    fun getGridColor(): Int = when (prefs.getString(KEY_GRID_COLOR, DEFAULT_GRID_COLOR)) {
        "green" -> android.graphics.Color.GREEN
        "white" -> android.graphics.Color.WHITE
        "yellow" -> android.graphics.Color.YELLOW
        else -> android.graphics.Color.GREEN
    }
    val gridAlpha: Float get() = prefs.getFloat(KEY_GRID_ALPHA, DEFAULT_GRID_ALPHA)
    val gridWidth: Float get() = prefs.getFloat(KEY_GRID_WIDTH, DEFAULT_GRID_WIDTH)
    val isDebugInfoEnabled: Boolean get() = prefs.getBoolean(KEY_DEBUG_INFO, DEFAULT_DEBUG)

    // ═══════════════════════════════════════════
    //  读取 - 千问大模型配置
    // ═══════════════════════════════════════════

    val isAIGuidanceEnabled: Boolean get() = prefs.getBoolean(KEY_AI_ENABLED, DEFAULT_AI_ENABLED)

    /** API Key（加密存储） */
    val apiKey: String get() = securePrefs.getString(SECURE_KEY_API_KEY, "") ?: ""

    /** API Base URL */
    val baseUrl: String get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    /** 模型名称 */
    val modelName: String get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME) ?: DEFAULT_MODEL_NAME

    /** 是否已配置可用 */
    val isAIConfigured: Boolean get() = isAIGuidanceEnabled && apiKey.isNotBlank()

    /** 策略管理器 URL */
    val strategyUrl: String get() = prefs.getString(KEY_STRATEGY_URL, DEFAULT_STRATEGY_URL) ?: DEFAULT_STRATEGY_URL

    // ═══════════════════════════════════════════
    //  写入方法
    // ═══════════════════════════════════════════

    fun saveBodyDetectionEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BODY_DETECTION, enabled).apply()
    fun savePoseGuidanceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_POSE_GUIDANCE, enabled).apply()
    fun saveFaceGuidanceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_FACE_GUIDANCE, enabled).apply()
    fun saveCompositionPitfallEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_COMPOSITION_PITFALL, enabled).apply()

    fun saveVoiceGuidanceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_VOICE_GUIDANCE, enabled).apply()
    fun savePositiveEncouragementEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_POSITIVE_ENCOURAGEMENT, enabled).apply()
    fun saveHapticEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    fun savePromptInterval(interval: Long) = prefs.edit().putLong(KEY_PROMPT_INTERVAL, interval).apply()

    fun saveAutoShutterEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_SHUTTER_ENABLED, enabled).apply()
    fun saveCountdownShutterEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_COUNTDOWN_SHUTTER, enabled).apply()
    fun saveSilentShutterEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SILENT_SHUTTER, enabled).apply()

    fun saveGridColor(colorName: String) = prefs.edit().putString(KEY_GRID_COLOR, colorName).apply()
    fun saveGridAlpha(alpha: Float) = prefs.edit().putFloat(KEY_GRID_ALPHA, alpha).apply()
    fun saveGridWidth(width: Float) = prefs.edit().putFloat(KEY_GRID_WIDTH, width).apply()
    fun saveDebugInfoEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DEBUG_INFO, enabled).apply()

    // AI 设置
    fun saveAIGuidanceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
    fun saveBaseUrl(url: String) = prefs.edit().putString(KEY_BASE_URL, url).apply()
    fun saveModelName(model: String) = prefs.edit().putString(KEY_MODEL_NAME, model).apply()
    fun saveStrategyUrl(url: String) = prefs.edit().putString(KEY_STRATEGY_URL, url).apply()

    /** API Key 写入加密存储 */
    fun saveApiKey(key: String) = securePrefs.edit().putString(SECURE_KEY_API_KEY, key).apply()
}
