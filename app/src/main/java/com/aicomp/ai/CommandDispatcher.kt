package com.aicomp.ai

import android.util.Log
import com.aicomp.AutoShutterEngine
import com.aicomp.HapticFeedbackManager
import com.aicomp.settings.ConfigManager

/**
 * 指令分发中心
 *
 * 解析 AI 返回的 JSON 命令，映射到物理操作：
 *   - camera 字段 → CameraControl (zoom, exposure)
 *   - guide 字段  → OverlayView 渲染 + HapticFeedback
 *   - shutter 字段 → AutoShutterEngine 权限位
 *
 * 使用方式：在 CompositionViewModel 中调用
 *   val command = AICommand.fromJson(aiResponse)
 *   CommandDispatcher.dispatch(command, callbacks)
 */
object CommandDispatcher {

    private const val TAG = "CommandDispatcher"

    /**
     * 分发回调接口
     * 由 ViewModel 实现，桥接到各个子系统
     */
    interface Callbacks {
        /** 相机缩放控制 */
        fun setZoomRatio(ratio: Float)

        /** 曝光补偿控制 */
        fun setExposureCompensationIndex(index: Int)

        /** 移动引导矢量 → OverlayView */
        fun setGuideVector(dx: Float, dy: Float, message: String)

        /** 清除移动引导 */
        fun clearGuide()

        /** 更新 AI 快门就绪状态 → AutoShutterEngine */
        fun setAIShutterReady(ready: Boolean)

        /** 更新 AI 推荐裁剪区域 → OverlayView */
        fun setCropZone(zone: AICropZone?)
    }

    /**
     * 分发 AI 命令到各个子系统
     *
     * @param command 解析后的 AI 命令
     * @param callbacks 回调接口
     */
    fun dispatch(command: AICommand, callbacks: Callbacks) {
        if (command.action.isBlank()) {
            Log.w(TAG, "Empty action in command, skipping")
            return
        }

        Log.d(TAG, "Dispatching command: action=${command.action}, " +
                "guide=(${command.guideDx}, ${command.guideDy}), " +
                "zoom=${command.cameraZoom}, ev=${command.cameraEv}, " +
                "shutter=${command.shutterState}")

        // 1. 移动引导指令
        if (command.hasGuide) {
            dispatchGuide(command, callbacks)
        } else {
            // 没有引导指令时清除之前的引导
            callbacks.clearGuide()
        }

        // 2. 相机控制指令
        if (command.hasCameraControl) {
            dispatchCameraControl(command, callbacks)
        }

        // 3. 快门指令
        if (command.hasShutterCommand) {
            dispatchShutter(command, callbacks)
        }

        // 4. 裁剪区域指令
        dispatchCrop(command, callbacks)
    }

    /**
     * 分发移动引导指令
     * - (dx, dy) 位移矢量 → OverlayView 绘制引导箭头
     * - 空间感触觉反馈（向左/向右不同振感）
     * - 消息文本展示
     */
    private fun dispatchGuide(command: AICommand, callbacks: Callbacks) {
        val dx = command.guideDx
        val dy = command.guideDy

        // 传递矢量给 OverlayView
        callbacks.setGuideVector(dx, dy, command.guideMessage)

        // 空间感触觉反馈
        if (ConfigManager.isHapticEnabled) {
            triggerSpatialHaptic(dx, dy)
        }
    }

    /**
     * 分发相机控制指令
     * - zoom → CameraX setZoomRatio
     * - ev   → CameraX setExposureCompensationIndex
     */
    private fun dispatchCameraControl(command: AICommand, callbacks: Callbacks) {
        command.cameraZoom?.let { zoom ->
            val clampedZoom = zoom.coerceIn(1.0f, 10.0f)
            Log.d(TAG, "Setting zoom ratio: $clampedZoom")
            callbacks.setZoomRatio(clampedZoom)
        }

        command.cameraEv?.let { ev ->
            Log.d(TAG, "Setting exposure compensation: $ev")
            callbacks.setExposureCompensationIndex(ev)
        }
    }

    /**
     * 分发快门指令
     * - READY → 设置 AutoShutterEngine 的 AI 快门就绪标志
     * - WAITING → 清除就绪标志
     *
     * 闭环逻辑：
     *   if (AI_SHUTTER_READY && ML_KIT_STABLE && GYRO_STABLE) → 触发快门
     */
    private fun dispatchShutter(command: AICommand, callbacks: Callbacks) {
        val ready = command.isShutterReady
        Log.d(TAG, "AI shutter state: ${if (ready) "READY" else "WAITING"}")
        callbacks.setAIShutterReady(ready)

        // 构图完美时的确认触觉
        if (ready && ConfigManager.isHapticEnabled) {
            HapticFeedbackManager.compositionPerfect()
        }
    }

    /**
     * 分发裁剪区域指令
     * - AI 推荐裁剪区域（归一化坐标） → OverlayView 绘制
     */
    private fun dispatchCrop(command: AICommand, callbacks: Callbacks) {
        val zone = command.cropZone
        Log.d(TAG, "Crop zone: ${zone?.let { "x1=${it.x1}, y1=${it.y1}, x2=${it.x2}, y2=${it.y2}, msg=${it.message}" } ?: "none"}")
        callbacks.setCropZone(zone)
    }

    /**
     * 空间感触觉反馈
     * 根据移动方向产生不同的振感：
     *   - 左移：短促左偏振感
     *   - 右移：短促右偏振感
     *   - 上移：轻柔提升振感
     *   - 下移：沉稳下降振感
     */
    private fun triggerSpatialHaptic(dx: Float, dy: Float) {
        val absDx = Math.abs(dx)
        val absDy = Math.abs(dy)

        when {
            // 水平移动为主 (dx > 50 像素)
            absDx > absDy && absDx > 50f -> {
                HapticFeedbackManager.hintTick()
            }
            // 垂直移动为主 (dy > 50 像素)
            absDy > absDx && absDy > 50f -> {
                HapticFeedbackManager.hintTick()
            }
            // 接近目标位置 (< 30 像素)
            absDx < 30f && absDy < 30f -> {
                val deviation = (absDx + absDy) / 60f
                HapticFeedbackManager.magneticAttraction(deviation)
            }
        }
    }

    /**
     * 将 AICommand 转换为传统 AICompositionResult（向后兼容旧 UI）
     */
    fun toLegacyResult(command: AICommand): AICompositionResult {
        return AICompositionResult(
            sceneDescription = command.sceneDescription,
            compositionAdvice = command.compositionAdvice,
            technicalAdvice = command.technicalAdvice,
            moodAdvice = "",
            shootingTip = "",
            score = command.score,
            isGoodShot = command.isGoodShot,
            keyAdjustments = if (command.hasGuide) {
                listOf(command.guideMessage)
            } else {
                emptyList()
            },
            rawResponse = command.rawResponse
        )
    }
}
