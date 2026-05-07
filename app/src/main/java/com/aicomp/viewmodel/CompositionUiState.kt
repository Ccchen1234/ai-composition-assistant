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

    // ============ 裁剪 / 缩放状态 ============
    /** 当前相机缩放倍率 */
    val currentZoomRatio: Float = 1f,
    /** AI 推荐裁剪区域（归一化 0-1 坐标），null 表示无推荐 */
    val cropZone: CropZone? = null,

    // ============ AI 辅助按钮状态 ============
    val aiAssistState: AIAssistState = AIAssistState.IDLE
) {
    /**
     * AI 推荐裁剪区域
     * @param x1,y1 左上角归一化坐标 (0-1)
     * @param x2,y2 右下角归一化坐标 (0-1)
     * @param message 裁剪建议文字
     */
    data class CropZone(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val message: String = ""
    ) {
        fun isValid() = x2 > x1 && y2 > y1 &&
                x1 >= 0f && y1 >= 0f && x2 <= 1f && y2 <= 1f
    }

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
