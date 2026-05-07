package com.aicomp.ai

import android.graphics.Bitmap
import android.util.Log
import com.aicomp.settings.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * AI 指导管理器 — 千问 Qwen3-VL-Flash 指令驱动
 *
 * P2 修复：CoroutineScope 不再内部创建，改为由外部（ViewModelScope）注入，
 * 确保 ViewModel.clear() 时协程自动取消，防止内存泄漏。
 */
class AIGuidanceManager(
    /**
     * 由调用方提供的 CoroutineScope，建议传入 ViewModelScope
     * 以保证生命周期与 ViewModel 同步。
     */
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AIGuidanceManager"
        const val COMPOSITION_SCORE_THRESHOLD = 75
        const val SCENE_CHANGED_MIN_INTERVAL_MS = 1000L
        const val NORMAL_MIN_INTERVAL_MS = 3000L
    }

    private var provider: QwenProvider? = null
    private var lastAnalysisTime = 0L
    private var lastSceneHash = ""
    private var lastResult: AICompositionResult? = null
    private var lastCommand: AICommand? = null
    private val isAnalyzing = AtomicBoolean(false)
    private var analysisJob: Job? = null

    private val pendingRequestSceneHash = AtomicReference("")
    private val pendingRequestTime = AtomicLong(0L)

    interface Callback {
        fun onAIResult(result: AICompositionResult)
        fun onAICommand(command: AICommand)
        fun onAIError(error: String)
        fun onAIAnalyzing(isAnalyzing: Boolean)
    }

    var callback: Callback? = null

    fun initFromConfig() {
        val config = ConfigManager.apiConfig
        provider = if (config.apiKey.isNotBlank()) {
            QwenProvider(config)
        } else {
            null
        }
        Log.d(TAG, "Initialized: ${provider?.getProviderName() ?: "disabled"}")
    }

    fun refreshConfig() {
        val config = ConfigManager.apiConfig
        if (provider != null && config.apiKey.isNotBlank()) {
            provider?.updateConfig(config)
        } else {
            initFromConfig()
        }
    }

    fun isEnabled(): Boolean = provider != null && provider!!.isAvailable()
    fun getProviderName(): String = provider?.getProviderName() ?: "未配置"
    fun isCommandModeEnabled(): Boolean = isEnabled()

    fun getLastResult(): AICompositionResult? = lastResult
    fun getLastCommand(): AICommand? = lastCommand

    fun requestAnalysis(
        frame: Bitmap,
        sceneType: String,
        sceneHash: String,
        isDeviceStable: Boolean,
        compositionScore: Int,
        sceneChanged: Boolean = false
    ): Boolean {
        val p = provider ?: return false
        if (!p.isAvailable()) return false
        if (isAnalyzing.get()) return false

        val now = System.currentTimeMillis()
        if (!isDeviceStable) return false

        val needsHelp = compositionScore < COMPOSITION_SCORE_THRESHOLD
        if (!needsHelp && !sceneChanged) return false

        val effectiveInterval = if (sceneChanged) SCENE_CHANGED_MIN_INTERVAL_MS else NORMAL_MIN_INTERVAL_MS
        if (now - lastAnalysisTime < effectiveInterval) return false

        lastAnalysisTime = now
        lastSceneHash = sceneHash
        isAnalyzing.set(true)

        pendingRequestSceneHash.set(sceneHash)
        pendingRequestTime.set(now)

        callback?.onAIAnalyzing(true)

        analysisJob = scope.launch {
            try {
                val command = p.analyzeCommand(
                    frame = frame,
                    sceneType = sceneType,
                    previousGuide = lastCommand?.guideMessage ?: lastResult?.compositionAdvice
                )

                val currentSceneHash = pendingRequestSceneHash.get()
                if (currentSceneHash != sceneHash) {
                    Log.d(TAG, "Discarded stale AI command (scene changed)")
                    return@launch
                }

                lastCommand = command
                callback?.onAICommand(command)

                val legacyResult = CommandDispatcher.toLegacyResult(command)
                if (legacyResult.sceneDescription.isNotBlank()) {
                    lastResult = legacyResult
                    callback?.onAIResult(legacyResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI analysis failed", e)
                callback?.onAIError(e.message ?: "AI 分析失败")
            } finally {
                isAnalyzing.set(false)
                callback?.onAIAnalyzing(false)
            }
        }

        return true
    }

    fun forceAnalyze(frame: Bitmap, sceneType: String) {
        lastAnalysisTime = 0
        lastSceneHash = ""
        requestAnalysis(
            frame = frame,
            sceneType = sceneType,
            sceneHash = "force_${System.currentTimeMillis()}",
            isDeviceStable = true,
            compositionScore = 0,
            sceneChanged = true
        )
    }

    fun reset() {
        analysisJob?.cancel()
        lastAnalysisTime = 0L
        lastSceneHash = ""
        lastResult = null
        lastCommand = null
        pendingRequestSceneHash.set("")
        pendingRequestTime.set(0L)
    }
}
