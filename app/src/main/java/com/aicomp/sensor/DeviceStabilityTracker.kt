package com.aicomp.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 设备稳定度追踪器
 *
 * 通过加速度传感器判断手机是否处于"静止"状态。
 * 使用滑动窗口 + 低通滤波去除重力分量，只保留抖动幅度。
 *
 * 用法：
 *   val tracker = DeviceStabilityTracker(context)
 *   tracker.start()
 *   if (tracker.isStable) { /* 可以触发 AI 分析 */ }
 *   tracker.stop()
 */
class DeviceStabilityTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // ──── 低通滤波（去除重力） ────
    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)
    private val alpha = 0.8f // 低通滤波系数

    // ──── 滑动窗口：记录最近 N 个抖动幅度 ────
    private val windowSize = 20 // 约 0.33 秒 (60Hz 采样)
    private val recentMagnitudes = ArrayDeque<Float>(windowSize)

    // ──── 稳定判断阈值 ────
    /** 抖动幅度阈值 (m/s²)，低于此值认为"稳定" */
    private val stabilityThreshold = 0.8f

    /** 需要连续稳定的时间 (ms) */
    private val requiredStableMs = 500L

    // ──── 状态 ────
    private var stableStartTimeMs: Long = 0L
    private var _isStable: Boolean = false
    private var isRunning = false

    /** 当前设备是否稳定 */
    val isStable: Boolean get() = _isStable

    /** 最近的平均抖动幅度（可用于判断稳定程度，0=完全静止） */
    val recentAvgMagnitude: Float
        get() {
            if (recentMagnitudes.isEmpty()) return 0f
            return recentMagnitudes.average().toFloat()
        }

    fun start() {
        if (isRunning) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) // ~50Hz
            isRunning = true
        }
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        isRunning = false
        reset()
    }

    fun reset() {
        recentMagnitudes.clear()
        stableStartTimeMs = 0L
        _isStable = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // 低通滤波提取重力
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        // 线性加速度 = 原始值 - 重力
        linearAccel[0] = event.values[0] - gravity[0]
        linearAccel[1] = event.values[1] - gravity[1]
        linearAccel[2] = event.values[2] - gravity[2]

        val magnitude = sqrt(
            linearAccel[0] * linearAccel[0] +
            linearAccel[1] * linearAccel[1] +
            linearAccel[2] * linearAccel[2]
        )

        // 滑动窗口
        recentMagnitudes.addLast(magnitude)
        if (recentMagnitudes.size > windowSize) {
            recentMagnitudes.removeFirst()
        }

        // 判断稳定度
        val avgMag = recentMagnitudes.average().toFloat()
        val now = System.currentTimeMillis()

        if (avgMag < stabilityThreshold) {
            if (stableStartTimeMs == 0L) {
                stableStartTimeMs = now
            }
            // 连续稳定超过 requiredStableMs → 标记为稳定
            if (now - stableStartTimeMs >= requiredStableMs) {
                _isStable = true
            }
        } else {
            // 抖动超标 → 立即标记为不稳定
            stableStartTimeMs = 0L
            _isStable = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* 不需要 */ }
}
