package com.aicomp

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.aicomp.device.K80ProOptimizer

/**
 * 触觉反馈管理器 - 红米K80 Pro X轴线性马达深度优化
 *
 * K80 Pro 采用瑞声科技AAC X轴线性马达:
 * - 响应时间 <5ms
 * - 支持 256 级振幅控制
 * - 支持复杂波形组合
 * - 支持预定义效果 (CLICK, HEAVY_CLICK, DOUBLE_CLICK, TICK)
 *
 * 优化:
 * - 异步执行：所有振动调用在独立 HandlerThread 上，不阻塞主线程
 * - 防抖处理：磁吸震动等高频调用经过 debounce，避免振动堆叠
 */
object HapticFeedbackManager {

    private const val TAG = "HapticFeedback"

    private var vibrator: Vibrator? = null
    private var isK80Pro = false

    // ──── 异步线程 ────
    private lateinit var hapticThread: HandlerThread
    private lateinit var hapticHandler: Handler

    // ──── 防抖状态 ────
    private var lastMagneticTime = 0L
    private var lastHintTickTime = 0L

    /** 磁吸震动防抖窗口 (ms) — 磁吸每帧都调，需要严格防抖 */
    private const val MAGNETIC_DEBOUNCE_MS = 80L

    /** hintTick 防抖窗口 (ms) */
    private const val HINT_TICK_DEBOUNCE_MS = 100L

    // K80 Pro 线性马达参数
    private const val K80_PRO_MIN_PULSE_MS = 5L
    private const val K80_PRO_MAX_PULSE_MS = 25L
    private const val K80_PRO_PROXIMITY_RANGE = 0.20f

    // 通用设备参数
    private const val GENERIC_MIN_PULSE_MS = 15L
    private const val GENERIC_MAX_PULSE_MS = 40L
    private const val GENERIC_PROXIMITY_RANGE = 0.10f

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        isK80Pro = K80ProOptimizer.isK80Pro()

        // 启动异步振动线程
        hapticThread = HandlerThread("HapticThread", Thread.MIN_PRIORITY)
        hapticThread.start()
        hapticHandler = Handler(hapticThread.looper)

        vibrator?.let { v ->
            Log.d(TAG, "Vibrator initialized: $v")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.d(TAG, "Has amplitude control: ${v.hasAmplitudeControl()}")
            }
        }
    }

    private val minPulse get() = if (isK80Pro) K80_PRO_MIN_PULSE_MS else GENERIC_MIN_PULSE_MS
    private val maxPulse get() = if (isK80Pro) K80_PRO_MAX_PULSE_MS else GENERIC_MAX_PULSE_MS
    private val proximityRange get() = if (isK80Pro) K80_PRO_PROXIMITY_RANGE else GENERIC_PROXIMITY_RANGE

    /** 在异步线程执行振动 */
    private fun vibrateAsync(block: (Vibrator) -> Unit) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (::hapticHandler.isInitialized) {
            hapticHandler.post { block(v) }
        } else {
            block(v) // init 尚未调用时降级同步
        }
    }

    // ==================== 磁吸震动 (高频 + 防抖) ====================

    /**
     * 磁吸震动 - 核心体验
     *
     * 偏差 0.0-1.0 (0=完美对准, 1=最大偏差)
     *
     * 震动策略:
     * - 偏差 >20%: 无震动 (距离太远)
     * - 偏差 10%-20%: 微弱短脉冲 (接近中)
     * - 偏差 5%-10%: 中等脉冲递增 (快要到了)
     * - 偏差 <5%: 强脉冲 + 对齐确认 (到位!)
     */
    fun magneticAttraction(deviation: Float) {
        val now = System.currentTimeMillis()
        if (now - lastMagneticTime < MAGNETIC_DEBOUNCE_MS) return
        lastMagneticTime = now

        vibrateAsync { v ->
            when {
                deviation < 0.03f -> {
                    if (isK80Pro && v.hasAmplitudeControl()) {
                        v.vibrate(VibrationEffect.createOneShot(20, 255))
                    } else {
                        v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    }
                }
                deviation < 0.08f -> {
                    val amplitude = ((1f - deviation / 0.08f) * 200 + 55).toInt().coerceIn(55, 255)
                    val duration = (maxPulse - (maxPulse - minPulse) * (1f - deviation / 0.08f)).toLong()
                    if (v.hasAmplitudeControl()) {
                        v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                    } else {
                        v.vibrate(VibrationEffect.createOneShot(duration, -1))
                    }
                }
                deviation < proximityRange -> {
                    val intensity = (1f - deviation / proximityRange)
                    val amplitude = (intensity * 120 + 30).toInt().coerceIn(30, 150)
                    val duration = (maxPulse - (maxPulse - minPulse) * intensity).toLong()
                    if (v.hasAmplitudeControl()) {
                        v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                    } else {
                        v.vibrate(VibrationEffect.createOneShot(duration, -1))
                    }
                }
            }
        }
    }

    // ==================== 快门震动 (不防抖，异步) ====================

    /**
     * 自动快门 - 模拟机械相机
     */
    fun autoShutterTrigger() {
        vibrateAsync { v ->
            if (isK80Pro && v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 12, 25, 40),
                    intArrayOf(100, 0, 200, 255),
                    -1
                ))
            } else {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 20, 30, 50),
                    intArrayOf(80, 0, 180, 255),
                    -1
                ))
            }
        }
    }

    /** 倒计时快门 */
    fun countdownTick(second: Int) {
        vibrateAsync { v ->
            val effect = when (second) {
                3, 2 -> VibrationEffect.createOneShot(30, 100)
                1 -> VibrationEffect.createOneShot(50, 150)
                0 -> {
                    autoShutterTrigger()
                    return@vibrateAsync
                }
                else -> return@vibrateAsync
            }
            v.vibrate(effect)
        }
    }

    // ==================== 界面反馈 (轻量 + 防抖) ====================

    /** 轻触提示 — 带防抖 */
    fun hintTick() {
        val now = System.currentTimeMillis()
        if (now - lastHintTickTime < HINT_TICK_DEBOUNCE_MS) return
        lastHintTickTime = now

        vibrateAsync { v ->
            if (isK80Pro) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(15, 80))
            }
        }
    }

    /** 按钮点击反馈 — 不防抖 (用户主动触发) */
    fun buttonClick() {
        vibrateAsync { v ->
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    /** 切换开关反馈 — 不防抖 */
    fun toggleSwitch(isOn: Boolean) {
        vibrateAsync { v ->
            if (isK80Pro && v.hasAmplitudeControl()) {
                if (isOn) {
                    v.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 8, 5, 15),
                        intArrayOf(80, 0, 150, 200), -1
                    ))
                } else {
                    v.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 15, 5, 8),
                        intArrayOf(200, 0, 150, 80), -1
                    ))
                }
            } else {
                v.vibrate(VibrationEffect.createPredefined(
                    if (isOn) VibrationEffect.EFFECT_CLICK
                    else VibrationEffect.EFFECT_TICK
                ))
            }
        }
    }

    /** 构图完美确认 — 不防抖 */
    fun compositionPerfect() {
        vibrateAsync { v ->
            if (isK80Pro && v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 10, 40, 20),
                    intArrayOf(180, 0, 255, 0), -1
                ))
            } else {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 15, 50, 15),
                    intArrayOf(200, 0, 255, 0), -1
                ))
            }
        }
    }

    /** 对焦成功 — 不防抖 */
    fun focusSuccess() {
        vibrateAsync { v ->
            if (isK80Pro) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(10, 60))
            }
        }
    }

    /** 错误/警告 */
    fun errorWarning() {
        vibrateAsync { v ->
            v.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 30, 50, 30),
                intArrayOf(200, 0, 200, 0), -1
            ))
        }
    }

    // ==================== 清理 ====================

    fun cancel() {
        vibrator?.cancel()
    }

    /** Activity.onDestroy 时调用，停止线程 */
    fun release() {
        cancel()
        if (::hapticThread.isInitialized) {
            hapticThread.quitSafely()
        }
    }
}
