package com.aicomp.device

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display
import android.view.WindowManager

/**
 * 红米K80 Pro 专项优化器
 * 
 * K80 Pro 硬件规格:
 * - 处理器: 骁龙8 Elite (Snapdragon 8 Elite)
 * - 屏幕: 6.67" 2K AMOLED, 3200x1440, 120Hz, 12bit色深
 * - 相机: 50MP 主摄 (Sony IMX906), OIS光学防抖
 * - 马达: X轴线性马达 (瑞声科技AAC)
 * - 内存: 12GB/16GB LPDDR5X
 * - 存储: 256GB/512GB/1TB UFS 4.0
 */
object K80ProOptimizer {

    // ====== 设备识别 ======
    
    /** 检测是否为红米K80 Pro */
    fun isK80Pro(): Boolean {
        val model = Build.MODEL.uppercase()
        val device = Build.DEVICE.uppercase()
        return model.contains("K80 PRO") || 
               model.contains("24127RK2CC") || // K80 Pro 型号
               device.contains("MIK80PRO")
    }

    /** 检测是否为红米K系列 (包括K70/K80等) */
    fun isKSeries(): Boolean {
        val model = Build.MODEL.uppercase()
        return model.contains("K70") || model.contains("K80") || model.contains("K60")
    }

    // ====== 显示优化 ======
    
    data class DisplayConfig(
        val screenWidth: Int,
        val screenHeight: Int,
        val refreshRate: Float,
        val isHdr: Boolean,
        val colorDepth: Int // bits
    )

    /** 获取屏幕配置 */
    fun getDisplayConfig(context: Context): DisplayConfig {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        return DisplayConfig(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            refreshRate = display.refreshRate,
            isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                display.isHdr
            } else false,
            colorDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                display.preferredWideGamutColorSpace?.let { 10 } ?: 8
            } else 8
        )
    }

    /** 
     * 获取最佳相机预览分辨率
     * K80 Pro 屏幕 3200x1440, 但预览不需要全分辨率
     * 选择接近屏幕比例但较低的分辨率以保证流畅度
     */
    fun getOptimalPreviewSize(
        context: Context,
        availableSizes: List<Size>
    ): Size {
        val display = getDisplayConfig(context)
        val targetRatio = display.screenWidth.toFloat() / display.screenHeight

        // K80 Pro 屏幕比例约 20:9
        // 120Hz下选择 1080x2400 (FHD+) 足够清晰且省性能
        val preferredSize = if (isK80Pro()) {
            Size(1080, 2400)
        } else {
            Size(1080, 1920)
        }

        // 从可用分辨率中找最接近的
        return availableSizes
            .filter { 
                val ratio = it.width.toFloat() / it.height
                Math.abs(ratio - targetRatio) < 0.1f // 比例接近
            }
            .minByOrNull { 
                Math.abs(it.width - preferredSize.width) + 
                Math.abs(it.height - preferredSize.height)
            } ?: availableSizes.maxByOrNull { it.width * it.height } ?: preferredSize
    }

    /**
     * 获取最佳拍照分辨率
     * K80 Pro 主摄50MP, 但通常使用 12.5MP (4096x3072) 四合一输出
     */
    fun getOptimalCaptureSize(
        availableSizes: List<Size>
    ): Size {
        return if (isK80Pro()) {
            // K80 Pro: 优先使用 4096x3072 (12.5MP 四合一)
            availableSizes
                .filter { it.width * it.height <= 12_500_000 }
                .maxByOrNull { it.width * it.height }
                ?: Size(4096, 3072)
        } else {
            // 其他设备: 1080p足够
            availableSizes
                .filter { it.width <= 1920 }
                .maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
        }
    }

    /**
     * 获取 ML Kit 分析分辨率
     * K80 Pro 性能强劲,可以使用更高分辨率提升检测精度
     */
    fun getAnalysisResolution(): Size {
        return if (isK80Pro()) {
            Size(960, 2160) // 更高分辨率提升人脸/姿势检测精度
        } else {
            Size(720, 1280)
        }
    }

    // ====== 刷新率优化 ======
    
    /**
     * 设置高刷新率模式
     * 相机预览需要稳定的高刷新率
     */
    fun enableHighRefreshRate(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            
            // 请求最高刷新率
            val modes = display.supportedModes
            val bestMode = modes.maxByOrNull { it.refreshRate }
            
            bestMode?.let { mode ->
                val params = (context as? android.app.Activity)
                    ?.window?.attributes
                params?.preferredDisplayModeId = mode.modeId
                (context as? android.app.Activity)?.window?.attributes = params
            }
        }
    }

    // ====== 性能优化 ======
    
    data class PerformanceConfig(
        val analysisIntervalMs: Long,
        val maxConcurrentAnalysis: Int,
        val useGpuAcceleration: Boolean,
        val enableNpuDelegate: Boolean,
        val memoryCacheSizeMB: Int
    )

    /**
     * 获取性能配置
     * 骁龙8 Elite 强劲性能允许更高配置
     */
    fun getPerformanceConfig(context: Context): PerformanceConfig {
        val ramMB = getAvailableRAM(context)

        return if (isK80Pro()) {
            PerformanceConfig(
                analysisIntervalMs = 50,      // 20 FPS 分析 (性能充足)
                maxConcurrentAnalysis = 2,     // 同时跑2个检测器
                useGpuAcceleration = true,     // 启用GPU加速
                enableNpuDelegate = true,      // 启用NPU (如果ML Kit支持)
                memoryCacheSizeMB = (ramMB * 0.1f).toInt().coerceIn(64, 256)
            )
        } else {
            PerformanceConfig(
                analysisIntervalMs = 100,     // 10 FPS 分析
                maxConcurrentAnalysis = 1,
                useGpuAcceleration = false,
                enableNpuDelegate = false,
                memoryCacheSizeMB = 32
            )
        }
    }

    private fun getAvailableRAM(context: Context): Int {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    // ====== 相机特性 ======
    
    data class CameraFeatures(
        val supportOIS: Boolean,         // 光学防抖
        val supportHDR: Boolean,         // HDR拍照
        val supportNightMode: Boolean,   // 夜景模式
        val supportMacro: Boolean,       // 微距
        val autoFocusModes: List<Int>,   // 支持的对焦模式
        val maxZoom: Float               // 最大变焦倍数
    )

    /** K80 Pro 相机特性 */
    fun getCameraFeatures(): CameraFeatures {
        return if (isK80Pro()) {
            CameraFeatures(
                supportOIS = true,
                supportHDR = true,
                supportNightMode = true,
                supportMacro = true,
                autoFocusModes = listOf(
                    // CONTINUOUS_PICTURE, MACRO, EDOF
                    android.hardware.camera2.CameraCharacteristics
                        .CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    android.hardware.camera2.CameraCharacteristics
                        .CONTROL_AF_MODE_MACRO
                ),
                maxZoom = 10f // 数码变焦上限
            )
        } else {
            CameraFeatures(
                supportOIS = false,
                supportHDR = false,
                supportNightMode = false,
                supportMacro = false,
                autoFocusModes = listOf(
                    android.hardware.camera2.CameraCharacteristics
                        .CONTROL_AF_MODE_CONTINUOUS_PICTURE
                ),
                maxZoom = 4f
            )
        }
    }
}
