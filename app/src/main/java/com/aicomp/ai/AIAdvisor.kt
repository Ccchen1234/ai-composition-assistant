package com.aicomp.ai

import android.graphics.Bitmap

/**
 * AI 构图顾问接口
 */
interface AIAdvisor {
    suspend fun analyzeComposition(
        frame: Bitmap,
        sceneType: String = "auto",
        previousGuidance: String? = null
    ): AICompositionResult

    fun isAvailable(): Boolean
    fun getProviderName(): String
}

/**
 * AI 构图分析结果
 */
data class AICompositionResult(
    val sceneDescription: String,
    val compositionAdvice: String,
    val technicalAdvice: String,
    val moodAdvice: String,
    val shootingTip: String,
    val score: Int,
    val isGoodShot: Boolean,
    val keyAdjustments: List<String>,
    val rawResponse: String = ""
) {
    companion object {
        fun AICompositionResult.isValid(): Boolean = sceneDescription.isNotBlank()

        fun AICompositionResult.toDisplayText(): String {
            return buildString {
                if (score >= 80) appendLine("评分: $score/100 - 优秀！")
                else if (score >= 60) appendLine("评分: $score/100 - 不错")
                else appendLine("评分: $score/100 - 可以更好")

                if (compositionAdvice.isNotBlank()) {
                    appendLine()
                    appendLine("构图: $compositionAdvice")
                }
                if (technicalAdvice.isNotBlank()) {
                    appendLine("技术: $technicalAdvice")
                }
                if (shootingTip.isNotBlank()) {
                    appendLine("贴士: $shootingTip")
                }
            }.trim()
        }
    }
}
