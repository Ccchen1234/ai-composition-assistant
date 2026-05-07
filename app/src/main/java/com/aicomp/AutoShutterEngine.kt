package com.aicomp

/**
 * 自动快门触发引擎
 *
 * 状态机设计（防误触 + 冷却期）：
 *   IDLE → (构图达标) → LOCKING → (稳定 0.5-1s) → TRIGGER → COOLDOWN(3s) → IDLE
 *
 * 核心改进：
 *   1. LOCKING 阶段先触发提示振动，给用户"快拍了"的预感
 *   2. 构图中途偏离则立即回退到 IDLE
 *   3. 拍照后 3 秒冷却期，防止相册被垃圾图塞满
 */
object AutoShutterEngine {

    enum class Sensitivity {
        LOW,    // 锁定 1.0s → 冷却 3.0s
        MEDIUM, // 锁定 0.7s → 冷却 2.5s
        HIGH    // 锁定 0.4s → 冷却 2.0s
    }

    internal enum class ShutterPhase {
        IDLE,       // 等待构图到位
        LOCKING,    // 构图到位，正在锁定确认
        COOLDOWN    // 拍照后冷却期
    }

    data class AutoShutterState(
        var enabled: Boolean = true,
        var sensitivity: Sensitivity = Sensitivity.MEDIUM
    ) {
        internal var phase: ShutterPhase = ShutterPhase.IDLE
        internal var phaseStartTimeMs: Long = 0L
        internal var lastTriggerTimeMs: Long = 0L
        internal var lockingHintFired: Boolean = false

        /** 根据灵敏度获取锁定所需稳定时间 */
        fun getLockDurationMs(): Long = when (sensitivity) {
            Sensitivity.LOW -> 1000L
            Sensitivity.MEDIUM -> 700L
            Sensitivity.HIGH -> 400L
        }

        /** 冷却期时长 */
        fun getCooldownDurationMs(): Long = when (sensitivity) {
            Sensitivity.LOW -> 3000L
            Sensitivity.MEDIUM -> 2500L
            Sensitivity.HIGH -> 2000L
        }

        /** 最小触发间隔（额外安全保护） */
        fun getMinIntervalMs(): Long = when (sensitivity) {
            Sensitivity.LOW -> 4000L
            Sensitivity.MEDIUM -> 3000L
            Sensitivity.HIGH -> 2500L
        }
    }

    /**
     * 事件类型 — 通知上层做触觉/声音反馈
     */
    enum class ShutterEvent {
        NONE,           // 无事件
        LOCK_HINT,      // 进入锁定：提示振动（"快拍了"预感）
        TRIGGER_SHUTTER // 真正触发快门
    }

    data class ShutterDecision(
        val event: ShutterEvent = ShutterEvent.NONE,
        val isLocking: Boolean = false  // 当前是否处于锁定中（UI 可显示锁定指示器）
    )

    /**
     * 每帧调用 — 核心状态机
     *
     * @param state       持久化状态（ViewModel 持有）
     * @param isPerfect   当前帧构图是否完美
     * @param currentTimeMs 当前时间戳
     */
    fun evaluate(
        state: AutoShutterState,
        isPerfect: Boolean,
        currentTimeMs: Long
    ): ShutterDecision {
        if (!state.enabled) {
            state.phase = ShutterPhase.IDLE
            return ShutterDecision()
        }

        return when (state.phase) {
            ShutterPhase.IDLE -> handleIdle(state, isPerfect, currentTimeMs)
            ShutterPhase.LOCKING -> handleLocking(state, isPerfect, currentTimeMs)
            ShutterPhase.COOLDOWN -> handleCooldown(state, currentTimeMs)
        }
    }

    // ──── IDLE：等待构图到位 ────

    private fun handleIdle(
        state: AutoShutterState,
        isPerfect: Boolean,
        now: Long
    ): ShutterDecision {
        if (!isPerfect) return ShutterDecision()

        // 构图达标 → 进入 LOCKING
        state.phase = ShutterPhase.LOCKING
        state.phaseStartTimeMs = now
        state.lockingHintFired = false

        return ShutterDecision(
            event = ShutterEvent.LOCK_HINT,  // 立即触发提示振动
            isLocking = true
        ).also {
            state.lockingHintFired = true
        }
    }

    // ──── LOCKING：确认锁定 ────

    private fun handleLocking(
        state: AutoShutterState,
        isPerfect: Boolean,
        now: Long
    ): ShutterDecision {
        if (!isPerfect) {
            // 构图偏离 → 回退到 IDLE
            state.phase = ShutterPhase.IDLE
            return ShutterDecision()
        }

        val lockedDuration = now - state.phaseStartTimeMs
        if (lockedDuration >= state.getLockDurationMs()) {
            // 锁定时间达标 → 检查最小间隔
            if (now - state.lastTriggerTimeMs < state.getMinIntervalMs()) {
                return ShutterDecision(isLocking = true)
            }

            // 触发快门！
            state.phase = ShutterPhase.COOLDOWN
            state.phaseStartTimeMs = now
            state.lastTriggerTimeMs = now

            return ShutterDecision(
                event = ShutterEvent.TRIGGER_SHUTTER,
                isLocking = false
            )
        }

        // 仍在锁定中
        return ShutterDecision(isLocking = true)
    }

    // ──── COOLDOWN：冷却期 ────

    private fun handleCooldown(
        state: AutoShutterState,
        now: Long
    ): ShutterDecision {
        val cooldownElapsed = now - state.phaseStartTimeMs
        if (cooldownElapsed >= state.getCooldownDurationMs()) {
            state.phase = ShutterPhase.IDLE
        }
        return ShutterDecision()
    }

    // ──── 工具方法 ────

    /** 强制重置（如切换摄像头时调用） */
    fun reset(state: AutoShutterState) {
        state.phase = ShutterPhase.IDLE
        state.phaseStartTimeMs = 0L
        state.lockingHintFired = false
    }

    /** 检查主体是否在完美位置（构图框内） */
    fun isInPerfectPosition(
        subjectCenterX: Float,
        subjectCenterY: Float,
        recommendedBox: CompositionBoxEngine.CompositionBox,
        threshold: Float = 0.05f
    ): Boolean {
        val boxCenterX = (recommendedBox.x1 + recommendedBox.x2) / 2f
        val boxCenterY = (recommendedBox.y1 + recommendedBox.y2) / 2f
        return kotlin.math.abs(subjectCenterX - boxCenterX) < threshold &&
               kotlin.math.abs(subjectCenterY - boxCenterY) < threshold
    }

    /** 检查主体是否完全在构图框内 */
    fun isCompletelyInside(
        subjectLeft: Float, subjectTop: Float,
        subjectRight: Float, subjectBottom: Float,
        box: CompositionBoxEngine.CompositionBox
    ): Boolean {
        return subjectLeft >= box.x1 && subjectTop >= box.y1 &&
               subjectRight <= box.x2 && subjectBottom <= box.y2
    }
}
