package com.aicomp.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aicomp.AutoShutterEngine
import com.aicomp.CompositionBoxEngine
import com.aicomp.CompositionRuleEngine
import com.aicomp.HapticFeedbackManager
import com.aicomp.ai.AIGuidanceManager
import com.aicomp.ai.AICompositionResult
import com.aicomp.ai.AICommand
import com.aicomp.ai.CommandDispatcher
import com.aicomp.sensor.DeviceStabilityTracker
import com.aicomp.settings.ConfigManager
import com.aicomp.strategy.StrategyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CompositionViewModel — MVVM 核心（重构版）
 *
 * 职责：
 *   1. 聚合 CompositionRuleEngine + AI + 设备传感器的结果为单一 UiState
 *   2. 暴露 StateFlow 供 UI 层观察
 *   3. 提供用户操作入口（开关、拍照请求等）
 *   4. 实现 CommandDispatcher.Callbacks 接收 AI 指令
 *
 * UI 层（MainActivity / OverlayView）只观察 StateFlow 做渲染，不再含业务逻辑。
 */
class CompositionViewModel(application: Application) : AndroidViewModel(application),
    AIGuidanceManager.Callback, CommandDispatcher.Callbacks {

    companion object {
        private const val TAG = "CompositionViewModel"
    }

    // ──── 引擎实例 ────
    private val aiGuidanceManager = AIGuidanceManager(viewModelScope)
    private val autoShutterState = AutoShutterEngine.AutoShutterState()

    // ──── 设备稳定度追踪 ────
    private val stabilityTracker = DeviceStabilityTracker(getApplication())

    // ──── 状态流 ────
    private val _uiState = MutableStateFlow(CompositionUiState())
    val uiState: StateFlow<CompositionUiState> = _uiState.asStateFlow()

    // ──── 一次性事件 ────
    private val _takePictureEvent = MutableStateFlow(0L)
    val takePictureEvent: StateFlow<Long> = _takePictureEvent.asStateFlow()

    private val _hapticEvent = MutableStateFlow(HapticEvent.NONE)
    val hapticEvent: StateFlow<HapticEvent> = _hapticEvent.asStateFlow()

    // ──── CameraManager 回调引用（由 MainActivity 设置） ────
    var cameraControlCallback: CameraControlCallback? = null

    // ──── 上一次构图结果（用于 EXIF 写入） ────
    var lastCompositionResult: CompositionRuleEngine.CompositionResult? = null
        private set

    // ──── 场景变化追踪 ────
    private var lastSentSceneHash = ""

    /**
     * 相机控制接口（由 MainActivity 桥接 CameraManager）
     */
    interface CameraControlCallback {
        fun setZoomRatio(ratio: Float)
        fun setExposureCompensationIndex(index: Int)
    }

    init {
        // 初始化 StrategyManager
        StrategyManager.init(application)

        aiGuidanceManager.callback = this
        aiGuidanceManager.initFromConfig()

        _uiState.update {
            it.copy(
                aiEnabled = aiGuidanceManager.isEnabled(),
                commandModeEnabled = aiGuidanceManager.isCommandModeEnabled(),
                autoShutterEnabled = autoShutterState.enabled
            )
        }

        // 异步拉取远程策略
        viewModelScope.launch {
            StrategyManager.fetchRemoteStrategy()
        }
    }

    // ═══════════════════════════════════════════
    //  核心：处理一帧检测结果（由 CameraManager 回调）
    // ═══════════════════════════════════════════

    fun processFrame(
        detectedObjects: List<CompositionRuleEngine.DetectionResult>,
        imageWidth: Int,
        imageHeight: Int,
        isFrontCamera: Boolean,
        frameBitmapProvider: () -> Bitmap?
    ) {
        // 1) 规则引擎分析
        val result = CompositionRuleEngine.analyze(detectedObjects, imageWidth, imageHeight)
        lastCompositionResult = result

        // 2) 构图评分（0-100）
        val compositionScore = computeCompositionScore(result)

        // 3) 不再自动触发 AI — 只在用户点击 AI 辅助按钮时分析

        // 4) 构建新的 UiState
        val aiEnabled = aiGuidanceManager.isEnabled()
        val commandMode = aiGuidanceManager.isCommandModeEnabled()
        _uiState.update { state ->
            state.copy(
                sceneHint = if (result.isPerfect) "✅ 构图完美" else "📐 调整构图中...",
                guidanceMessage = if (!aiEnabled) result.guidances.firstOrNull()?.message.orEmpty() else "",
                guidanceVisible = !aiEnabled && result.guidances.isNotEmpty(),
                arrowX = result.arrowX,
                arrowY = result.arrowY,
                arrowVisible = !aiEnabled && !commandMode && (result.arrowX != 0f || result.arrowY != 0f),
                recommendedBox = result.recommendedBox,
                tiltAngle = result.tiltAngle,
                isPerfect = result.isPerfect,
                isDeviceStable = stabilityTracker.isStable,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                isFrontCamera = isFrontCamera,
                aiEnabled = aiEnabled,
                commandModeEnabled = commandMode
            )
        }

        // 5) 自动快门 — 新的状态机（锁定防误触 + 冷却期）
        // 在指令驱动模式下，快门还需要 AI_SHUTTER_READY 条件
        val aiShutterReady = _uiState.value.aiShutterReady
        val isPerfectForShutter = if (commandMode) {
            result.isPerfect && aiShutterReady && stabilityTracker.isStable
        } else {
            result.isPerfect
        }

        val shutterDecision = AutoShutterEngine.evaluate(
            autoShutterState,
            isPerfect = isPerfectForShutter,
            currentTimeMs = System.currentTimeMillis()
        )

        when (shutterDecision.event) {
            AutoShutterEngine.ShutterEvent.LOCK_HINT -> {
                if (ConfigManager.isHapticEnabled) {
                    HapticFeedbackManager.hintTick()
                }
            }
            AutoShutterEngine.ShutterEvent.TRIGGER_SHUTTER -> {
                _takePictureEvent.value = System.currentTimeMillis()
            }
            AutoShutterEngine.ShutterEvent.NONE -> { /* 无操作 */ }
        }

        // 6) 触觉反馈（磁吸 + 完美构图）
        if (ConfigManager.isHapticEnabled) {
            if (result.recommendedBox != null && !shutterDecision.isLocking) {
                val deviation = (Math.abs(result.arrowX) + Math.abs(result.arrowY)) * 0.1f
                HapticFeedbackManager.magneticAttraction(deviation)
            }
            if (result.isPerfect && !shutterDecision.isLocking) {
                HapticFeedbackManager.compositionPerfect()
            }
        }
    }

    val stateValue: CompositionUiState get() = _uiState.value

    // ═══════════════════════════════════════════
    //  用户操作入口
    // ═══════════════════════════════════════════

    fun toggleGrid() {
        _uiState.update { it.copy(gridEnabled = !it.gridEnabled) }
    }

    fun toggleFlash() {
        _uiState.update { it.copy(flashEnabled = !it.flashEnabled) }
    }

    fun toggleAutoShutter() {
        autoShutterState.enabled = !autoShutterState.enabled
        _uiState.update { it.copy(autoShutterEnabled = autoShutterState.enabled) }
    }

    fun setSensitivity(sensitivity: AutoShutterEngine.Sensitivity) {
        autoShutterState.sensitivity = sensitivity
    }

    fun requestAIManualAnalyze(frameBitmap: Bitmap) {
        if (aiGuidanceManager.isEnabled()) {
            _uiState.update { it.copy(aiAssistState = CompositionUiState.AIAssistState.ANALYZING) }
            aiGuidanceManager.forceAnalyze(frameBitmap, "auto")
        }
    }

    /**
     * AI 辅助按钮触发：用户主动请求 AI 分析当前画面
     * 不受设备稳定性 / 构图评分限制
     * @param frameBitmap 当前预览帧
     */
    fun triggerAIAssist(frameBitmap: Bitmap) {
        if (!aiGuidanceManager.isEnabled()) {
            _uiState.update {
                it.copy(
                    aiAssistState = CompositionUiState.AIAssistState.ERROR,
                    sceneHint = "⚠️ 请先在设置中配置 AI 大模型"
                )
            }
            return
        }
        if (stateValue.aiAssistState == CompositionUiState.AIAssistState.ANALYZING) {
            return // 防止重复触发
        }
        _uiState.update { it.copy(aiAssistState = CompositionUiState.AIAssistState.ANALYZING) }
        aiGuidanceManager.forceAnalyze(frameBitmap, detectCurrentSceneType())
    }

    /**
     * 清除 AI 辅助结果，回到本地指导模式
     */
    fun clearAIAssist() {
        aiGuidanceManager.reset()
        _uiState.update {
            it.copy(
                aiAssistState = CompositionUiState.AIAssistState.IDLE,
                guideDx = 0f,
                guideDy = 0f,
                guideMessage = "",
                guideVisible = false,
                aiShutterReady = false,
                aiSceneLabel = "",
                aiScore = 0,
                aiCompositionAdvice = "",
                aiTechnicalAdvice = "",
                aiShootingTip = "",
                aiKeyAdjustments = emptyList(),
                aiError = null
            )
        }
    }

    /** 从设置页返回时重新初始化 AI */
    fun refreshAIConfig() {
        aiGuidanceManager.refreshConfig()
        _uiState.update {
            it.copy(
                aiEnabled = aiGuidanceManager.isEnabled(),
                commandModeEnabled = aiGuidanceManager.isCommandModeEnabled()
            )
        }
    }

    /** 切换摄像头后重置引擎状态 */
    fun onCameraSwitched() {
        aiGuidanceManager.reset()
        AutoShutterEngine.reset(autoShutterState)
        stabilityTracker.reset()
        lastSentSceneHash = ""
        _uiState.update {
            it.copy(
                guideDx = 0f,
                guideDy = 0f,
                guideMessage = "",
                guideVisible = false,
                aiShutterReady = false
            )
        }
    }

    fun getAIManager(): AIGuidanceManager = aiGuidanceManager

    // ──── 稳定度追踪器生命周期 ────

    fun startStabilityTracking() = stabilityTracker.start()
    fun stopStabilityTracking() = stabilityTracker.stop()

    val isDeviceStable: Boolean get() = stabilityTracker.isStable

    // ═══════════════════════════════════════════
    //  AIGuidanceManager.Callback 实现
    // ═══════════════════════════════════════════

    override fun onAIResult(result: AICompositionResult) {
        _uiState.update { state ->
            state.applyAIResult(result).copy(
                aiError = null,
                aiAssistState = CompositionUiState.AIAssistState.HAS_RESULT,
                sceneHint = if (result.isGoodShot)
                    "⭐ AI 评分 ${result.score} - 可以拍了！"
                else state.sceneHint
            )
        }
        if (result.isGoodShot && ConfigManager.isHapticEnabled) {
            HapticFeedbackManager.compositionPerfect()
        }
    }

    override fun onAICommand(command: AICommand) {
        // 通过 CommandDispatcher 分发指令
        CommandDispatcher.dispatch(command, this)
        _uiState.update { it.copy(aiAssistState = CompositionUiState.AIAssistState.HAS_RESULT) }
    }

    override fun onAIError(error: String) {
        _uiState.update {
            it.copy(
                aiError = "⚠️ $error",
                aiAssistState = CompositionUiState.AIAssistState.ERROR
            )
        }
    }

    override fun onAIAnalyzing(isAnalyzing: Boolean) {
        _uiState.update {
            it.copy(
                aiAnalyzing = isAnalyzing,
                aiAssistState = if (isAnalyzing) CompositionUiState.AIAssistState.ANALYZING
                    else it.aiAssistState
            )
        }
    }

    // ═══════════════════════════════════════════
    //  CommandDispatcher.Callbacks 实现
    // ═══════════════════════════════════════════

    override fun setZoomRatio(ratio: Float) {
        cameraControlCallback?.setZoomRatio(ratio)
    }

    override fun setExposureCompensationIndex(index: Int) {
        cameraControlCallback?.setExposureCompensationIndex(index)
    }

    override fun setGuideVector(dx: Float, dy: Float, message: String) {
        _uiState.update {
            it.copy(
                guideDx = dx,
                guideDy = dy,
                guideMessage = message,
                guideVisible = dx != 0f || dy != 0f || message.isNotBlank()
            )
        }
    }

    override fun clearGuide() {
        _uiState.update {
            it.copy(
                guideDx = 0f,
                guideDy = 0f,
                guideMessage = "",
                guideVisible = false
            )
        }
    }

    override fun setAIShutterReady(ready: Boolean) {
        _uiState.update { it.copy(aiShutterReady = ready) }
    }

    // ═══════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════

    private fun detectSceneType(
        objects: List<CompositionRuleEngine.DetectionResult>,
        isFrontCamera: Boolean
    ): String {
        val mainCategory = objects.maxByOrNull {
            val priority = CompositionRuleEngine.Config.subjectPriority.indexOf(it.category) * 100
            -priority + (it.areaRatio * 10).toInt()
        }?.category ?: "其他"

        return when (mainCategory) {
            "人脸", "人体" -> if (isFrontCamera) "selfie" else "portrait"
            "风景" -> "landscape"
            "美食" -> "food"
            "宠物" -> "pet"
            "建筑" -> "architecture"
            else -> "auto"
        }
    }

    private fun buildSceneHash(
        objects: List<CompositionRuleEngine.DetectionResult>,
        w: Int, h: Int
    ): String {
        if (objects.isEmpty()) return "empty"
        val main = objects.maxByOrNull { it.areaRatio } ?: return "empty"
        val gx = (main.centerX * 10).toInt()
        val gy = (main.centerY * 10).toInt()
        return "${main.category}_${gx}_${gy}_${(main.areaRatio * 10).toInt()}"
    }

    override fun onCleared() {
        super.onCleared()
        aiGuidanceManager.reset()
        stabilityTracker.stop()
    }

    /**
     * 根据当前 UI 状态推断场景类型（用于 AI 辅助按钮触发时）
     *
     * P1 修复：后摄不再一律返回 "auto"，优先使用 ML Kit 检测到的主体类别，
     * 结合 lastCompositionResult 中已知的主体信息推断真实场景。
     */
    private fun detectCurrentSceneType(): String {
        val state = _uiState.value
        if (state.isFrontCamera) return "selfie"

        // 利用 ML Kit 已检测到的主体类别推断场景
        val mainCategory = lastCompositionResult?.let { result ->
            result.guidances
                .asSequence()
                .mapNotNull { guidance ->
                    // 从 guidance.message 或 ruleId 中提取场景关键词
                    when {
                        guidance.ruleId.startsWith("SCENE_") -> extractSceneFromGuidance(guidance)
                        guidance.ruleId == "ERROR_001" -> null  // 排除通用错误
                        else -> null
                    }
                }
                .firstOrNull()
        }

        return mainCategory ?: "auto"
    }

    /**
     * 从 Guidance 推断场景类型字符串
     */
    private fun extractSceneFromGuidance(guidance: CompositionRuleEngine.Guidance): String? {
        return when (guidance.ruleId) {
            "SCENE_001" -> "food"
            "SCENE_003", "SCENE_004" -> "portrait"
            "SCENE_005" -> "pet"
            "SCENE_006" -> "landscape"
            "SCENE_007" -> "architecture"
            "SCENE_008" -> "document"
            else -> null
        }
    }

    /**
     * P1 修复：构图评分应同时考虑优先级和置信度。
     * confidence 越低说明模型对当前指导越不确定，额外扣分。
     * 例如：priority=9 + confidence=0.7 → 70 + (1-0.7)*15 = 74.5 → 75
     *      priority=9 + confidence=1.0 → 70 + (0)*15 = 70
     */
    private fun computeCompositionScore(result: CompositionRuleEngine.CompositionResult): Int {
        if (result.guidances.isEmpty()) return 100
        val topGuidance = result.guidances.first()

        val baseScore = when {
            topGuidance.priority >= 11 -> 40
            topGuidance.priority >= 10 -> 55
            topGuidance.priority >= 9  -> 70
            topGuidance.priority >= 8  -> 80
            topGuidance.priority >= 7  -> 85
            else                       -> 90
        }

        // confidence 越低 → 额外扣分空间（最多 -15）
        val confidenceBonus = ((1f - topGuidance.confidence) * 15).toInt()

        // 指导数量越多说明问题越多，额外微调
        val guidanceCountPenalty = ((result.guidances.size - 1) * 3).coerceAtMost(5)

        return (baseScore - guidanceCountPenalty).coerceIn(0, 100) - confidenceBonus
    }

    enum class HapticEvent { NONE, BUTTON_CLICK, TOGGLE_ON, TOGGLE_OFF, FOCUS_SUCCESS, COMPOSITION_PERFECT }
}
