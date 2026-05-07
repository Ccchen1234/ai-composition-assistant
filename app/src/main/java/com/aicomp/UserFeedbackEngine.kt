package com.aicomp

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

// 用户反馈闭环学习系统
// 根据用户行为自动调整规则权重，实现个性化推荐
object UserFeedbackEngine {

    private const val PREFS_NAME = "composition_prefs"
    private const val LEARNING_RATE = 0.1f
    private const val MIN_WEIGHT = 0.1f
    private const val MAX_WEIGHT = 1.5f

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 获取当前规则权重（默认使用配置值，有用户学习则覆盖）
    fun getRuleWeight(ruleId: String, defaultWeight: Float): Float {
        return prefs.getFloat(ruleId, defaultWeight)
    }

    // 用户接受推荐：按下快门时主体在推荐框内
    fun onAccept(ruleId: String, confidence: Float) {
        val current = getRuleWeight(ruleId, 1.0f)
        val delta = LEARNING_RATE * confidence
        val newWeight = min(MAX_WEIGHT, current + delta)
        saveWeight(ruleId, newWeight)
    }

    // 用户拒绝推荐：主体离开推荐框后按下快门
    fun onReject(ruleId: String, confidence: Float) {
        val current = getRuleWeight(ruleId, 1.0f)
        val delta = LEARNING_RATE * (1f - confidence)
        val newWeight = max(MIN_WEIGHT, current - delta)
        saveWeight(ruleId, newWeight)
    }

    // 用户忽略：推荐显示超过5秒未操作
    fun onIgnore(ruleId: String, confidence: Float) {
        val current = getRuleWeight(ruleId, 1.0f)
        val delta = LEARNING_RATE * 0.5f * (1f - confidence)
        val newWeight = max(MIN_WEIGHT, current - delta)
        saveWeight(ruleId, newWeight)
    }

    // 用户手动调整：记录个性化偏好
    fun onManualAdjust(ruleId: String, adjustmentFactor: Float) {
        val current = getRuleWeight(ruleId, 1.0f)
        val delta = LEARNING_RATE * 0.02f * adjustmentFactor
        val newWeight = max(MIN_WEIGHT, min(MAX_WEIGHT, current + delta))
        saveWeight(ruleId, newWeight)
    }

    // 重置所有偏好
    fun resetAllPreferences() {
        prefs.edit().clear().apply()
    }

    // 获取场景偏好默认值
    fun getDefaultScenePreference(scene: String): List<String> {
        return when (scene) {
            "人像" -> listOf("CORE_001", "SCENE_003", "ADV_004")
            "美食" -> listOf("SCENE_001", "SCENE_002", "ADV_005")
            "风景" -> listOf("CORE_001", "SCENE_006", "CORE_003")
            "宠物" -> listOf("SCENE_005", "CORE_001", "ADV_005")
            else -> emptyList()
        }
    }

    private fun saveWeight(ruleId: String, weight: Float) {
        prefs.edit().putFloat(ruleId, weight).apply()
    }
}
