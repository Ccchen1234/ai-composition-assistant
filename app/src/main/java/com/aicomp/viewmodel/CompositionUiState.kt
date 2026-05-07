package com.aicomp.viewmodel

import com.aicomp.CompositionBoxEngine
import com.aicomp.ai.AICompositionResult

/**
 * MVVM 单一状态对象
 * UI 层只观察此 StateFlow 进行渲染，不持有任何业务逻辑
 */
data class CompositionUiState(
    // ============ 构图分析结果 ============
    val sceneHint: String = "📐 调整构图中...",
    val guidanceMessage: String = "",
    val guidanceVisible: Boolean = false,
    val arrowX: Float = 0f,
    val arrowY: Float = 0f,
    val arrowVisible: Boolean = false,
    val recommendedBox: CompositionBoxEngine.CompositionBox? = null,
    val tiltAngle: Float = 0f,
    val isPerfect: Boolean = false,
    val isDeviceStable: Boolean = false,
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,

    // ============ UI 控制状态 ============
    val gridEnabled: Boolean = true,
    val flashEnabled: Boolean = false,
    val autoShutterEnabled: Boolean = true,
    val isFrontCamera: Boolean = false,

    // ============ AI 指导状态 ============
    val aiEnabled: Boolean = false,
    val aiAnalyzing: Boolean = false,
    val aiSceneLabel: String = "",
    val aiScore: Int = 0,
    val aiCompositionAdvice: String = "",
    val aiTechnicalAdvice: String = "",
    val aiShootingTip: String = "",
    val aiKeyAdjustments: List<String> = emptyList(),
    val aiError: String? = null,

    // ============ AI 指令驱动状态 ============
    val guideDx: Float = 0f,
    val guideDy: Float = 0f,
    val guideMessage: String = "",
    val guideVisible: Boolean = false,
    val aiShutterReady: Boolean = false,
    val commandModeEnabled: Boolean = false,

    // ============ AI 辅助按钮状态 ============
    val aiAssistState: AIAssistState = AIAssistState.IDLE
) {
    enum class AIAssistState {
        IDLE,           // 空闲，等待用户点击
        ANALYZING,      // 正在分析中
        HAS_RESULT,     // 有结果可展示
        ERROR           // 出错
    }
    /** 从 AI 结果合并更新 AI 相关字段 */
    fun applyAIResult(result: AICompositionResult): CompositionUiState = copy(
        aiSceneLabel = result.sceneDescription,
        aiScore = result.score,
        aiCompositionAdvice = result.compositionAdvice,
        aiTechnicalAdvice = result.technicalAdvice,
        aiShootingTip = result.shootingTip,
        aiKeyAdjustments = result.keyAdjustments,
        aiError = null
    )
}
