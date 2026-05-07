package com.aicomp

import com.aicomp.settings.ConfigManager
import kotlin.math.abs

/**
 * 智能构图规则引擎
 * 结构化设计，支持配置开关，滞回阈值防抖，平滑帧稳定
 */
class SmartCompositionEngine(
    private val config: CompositionConfig
) {

    data class CompositionConfig(
        val version: String,
        val engineConfig: EngineConfig,
        val sceneDetectors: List<SceneDetector>,
        val ruleSets: Map<String, List<Rule>>
    )

    data class EngineConfig(
        val portraitPromptIntervalMs: Long = 1500,
        val landscapePromptIntervalMs: Long = 2000,
        val autoShutterIdleMs: Map<String, Long>,
        val smoothingFrames: Int = 3
    )

    data class SceneDetector(
        val sceneId: String,
        val priority: Int,
        val conditions: List<Condition>
    )

    data class Condition(
        val metric: String,
        val operator: String, // ==, !=, >, <, BETWEEN
        val value: Any,
        val hysteresis: Float = 2f // 滞回阈值
    )

    data class ConditionGroup(
        val logic: String, // AND / OR
        val items: List<Condition>
    )

    data class Rule(
        val ruleId: String,
        val priority: Float, // 优先级数值越大越先触发
        val conditions: ConditionGroup,
        val actions: List<Action>
    )

    data class Action(
        val type: String, // text_prompt / ui_overlay / auto_shutter
        val content: String,
        val durationMs: Long = 0,
        val triggerAfterMs: Long = 0
    )

    // 运行时状态
    private var currentScene: String = "unknown"
    private var lastPromptTime: Long = 0L
    private var ruleIdleStartTime: Long = System.currentTimeMillis()
    private val smoothedMetrics = mutableMapOf<String, Float>()
    private val lastMetricValues = mutableMapOf<String, Float>()

    // 检测结果
    data class Result(
        val sceneId: String,
        val actions: List<Action>,
        val triggerAutoShutter: Boolean
    )

    fun processFrame(
        metrics: Map<String, Float>,
        currentTimeMs: Long
    ): Result {

        // 应用平滑滤波
        val smoothed = applySmoothing(metrics, config.engineConfig.smoothingFrames)

        // 检测当前场景
        val detectedScene = detectSceneWithSmoothing(smoothed, config.sceneDetectors)
        if (detectedScene != currentScene) {
            currentScene = detectedScene
            resetEngineState(currentTimeMs)
        }

        // 获取当前场景的规则集
        val rules = config.ruleSets[currentScene] ?: emptyList()

        // 按优先级排序：数值大的先触发
        // 分组：ERROR > portrait > 其他 > landscape
        val sortedRules = rules.sortedWith(
            compareByDescending<Rule> { rule ->
                // 先按类别分组
                when {
                    rule.ruleId.startsWith("ERROR_") -> 3
                    rule.ruleId.startsWith("portrait") -> 2
                    rule.ruleId.startsWith("landscape") -> 0
                    else -> 1
                }
            }.thenByDescending { it.priority }
        )

        val activeActions = mutableListOf<Action>()
        var triggered = false

        for (rule in sortedRules) {
            // 检查规则对应模块是否开启
            if (isRuleEnabled(rule.ruleId)) {
                if (evaluateConditions(rule.conditions, smoothed)) {
                    activeActions.addAll(rule.actions)
                    triggered = true
                    break // 每次只触发优先级最高的一条，避免 UI 冲突
                }
            }
        }

        // 如果规则触发了，重置空闲计时
        if (triggered) {
            ruleIdleStartTime = currentTimeMs
        }

        // 更新上次值用于滞回
        lastMetricValues.putAll(smoothed)

        // 自动快门逻辑：没有规则触发表示构图完美，等待稳定后触发
        val idleLimit = config.engineConfig.autoShutterIdleMs[currentScene] ?: 2000L
        val shouldTriggerAutoShutter = !triggered &&
                (currentTimeMs - ruleIdleStartTime > idleLimit)

        return Result(
            sceneId = currentScene,
            actions = activeActions,
            triggerAutoShutter = shouldTriggerAutoShutter
        )
    }

    /**
     * 平滑滤波：对度量值做滑动平均，减少帧间抖动
     */
    private fun applySmoothing(
        metrics: Map<String, Float>,
        frames: Int
    ): Map<String, Float> {
        val result = mutableMapOf<String, Float>()

        for ((key, value) in metrics) {
            val prev = smoothedMetrics[key]
            if (prev != null && frames > 1) {
                // 指数移动平均
                val alpha = 1.0f / frames
                result[key] = prev * (1 - alpha) + value * alpha
            } else {
                result[key] = value
            }
        }

        smoothedMetrics.putAll(result)
        return result
    }

    /**
     * 评估条件组（支持 AND / OR 逻辑）
     */
    private fun evaluateConditions(
        conditionGroup: ConditionGroup,
        metrics: Map<String, Float>
    ): Boolean {
        return when (conditionGroup.logic) {
            "AND" -> conditionGroup.items.all { evaluateCondition(it, metrics) }
            "OR" -> conditionGroup.items.any { evaluateCondition(it, metrics) }
            else -> conditionGroup.items.firstOrNull()
                ?.let { evaluateCondition(it, metrics) } ?: false
        }
    }

    /**
     * 评估单个条件，带滞回处理防止抖动
     */
    private fun evaluateCondition(
        condition: Condition,
        metrics: Map<String, Float>
    ): Boolean {
        val current = metrics[condition.metric] ?: return false
        val last = lastMetricValues[condition.metric]
        val hysteresis = condition.hysteresis

        return when (condition.operator) {
            "==" -> {
                val target = (condition.value as? Number)?.toFloat() ?: return false
                if (last != null) {
                    abs(current - target) < hysteresis
                } else {
                    abs(current - target) < hysteresis
                }
            }
            "!=" -> {
                val target = (condition.value as? Number)?.toFloat() ?: return false
                if (last != null && abs(last - target) >= hysteresis) {
                    // 上次已满足不等，放宽阈值保持状态
                    abs(current - target) >= hysteresis * 0.5f
                } else {
                    abs(current - target) >= hysteresis
                }
            }
            ">" -> {
                val threshold = (condition.value as? Number)?.toFloat() ?: return false
                if (last != null && last > threshold) {
                    // 上次已超过，放宽阈值（滞回）
                    current > threshold - hysteresis
                } else {
                    current > threshold
                }
            }
            "<" -> {
                val threshold = (condition.value as? Number)?.toFloat() ?: return false
                if (last != null && last < threshold) {
                    // 上次已在下方，放宽阈值（滞回）
                    current < threshold + hysteresis
                } else {
                    current < threshold
                }
            }
            "BETWEEN" -> {
                val range = condition.value as? List<*> ?: return false
                val min = (range.getOrNull(0) as? Number)?.toFloat() ?: return false
                val max = (range.getOrNull(1) as? Number)?.toFloat() ?: return false
                if (last != null && last in min..max) {
                    // 上次在范围内，放宽阈值
                    current >= min - hysteresis && current <= max + hysteresis
                } else {
                    current in min..max
                }
            }
            else -> false
        }
    }

    /**
     * 检查规则是否被用户开启
     */
    private fun isRuleEnabled(ruleId: String): Boolean {
        return when {
            ruleId.startsWith("ERROR_") -> ConfigManager.isCompositionPitfallEnabled
            ruleId.startsWith("FACE_") -> ConfigManager.isFaceGuidanceEnabled
            ruleId.startsWith("POSE_") -> ConfigManager.isPoseGuidanceEnabled
            else -> true // 通用规则默认开启
        }
    }

    /**
     * 场景检测：按优先级依次检查场景条件
     */
    private fun detectSceneWithSmoothing(
        metrics: Map<String, Float>,
        detectors: List<SceneDetector>
    ): String {
        val sorted = detectors.sortedByDescending { it.priority }
        for (detector in sorted) {
            val group = ConditionGroup("AND", detector.conditions)
            if (evaluateConditions(group, metrics)) {
                return detector.sceneId
            }
        }
        return "default"
    }

    private fun resetEngineState(currentTimeMs: Long) {
        lastPromptTime = currentTimeMs
        ruleIdleStartTime = currentTimeMs
        // 不清除 smoothedMetrics，保持平滑连续性
    }

    /** 重置所有状态（如切换摄像头时调用） */
    fun reset() {
        currentScene = "unknown"
        lastPromptTime = 0L
        ruleIdleStartTime = System.currentTimeMillis()
        smoothedMetrics.clear()
        lastMetricValues.clear()
    }

    companion object {
        /** 默认配置 */
        fun defaultConfig(): CompositionConfig {
            return CompositionConfig(
                version = "1.0.0",
                engineConfig = EngineConfig(
                    portraitPromptIntervalMs = ConfigManager.promptIntervalMs,
                    landscapePromptIntervalMs = 2000,
                    autoShutterIdleMs = mapOf(
                        "portrait_selfie" to 2000L,
                        "landscape_sunset" to 3000L,
                        "food" to 1500L,
                        "pet" to 2000L
                    ),
                    smoothingFrames = 3
                ),
                sceneDetectors = listOf(
                    SceneDetector(
                        sceneId = "portrait_selfie",
                        priority = 100,
                        conditions = listOf(
                            Condition(
                                metric = "camera_facing",
                                operator = "==",
                                value = 1f
                            )
                        )
                    )
                ),
                ruleSets = mapOf(
                    "portrait_selfie" to listOf(
                        Rule(
                            ruleId = "selfie_angle_pitch_high",
                            priority = 11f,
                            conditions = ConditionGroup(
                                logic = "AND",
                                items = listOf(
                                    Condition(
                                        metric = "face_pitch_angle",
                                        operator = ">",
                                        value = 20f,
                                        hysteresis = 2f
                                    )
                                )
                            ),
                            actions = listOf(
                                Action(
                                    type = "text_prompt",
                                    content = "手机稍微放低一点，避免双下巴"
                                ),
                                Action(
                                    type = "ui_overlay",
                                    content = "arrow_down_indicator"
                                )
                            )
                        )
                    )
                )
            )
        }
    }
}
