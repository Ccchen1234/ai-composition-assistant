package com.aicomp.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.aicomp.CompositionRuleEngine
import com.aicomp.device.K80ProOptimizer
import com.aicomp.settings.ConfigManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraManager — 相机生命周期管理器
 *
 * 职责：
 *   1. CameraX 初始化与 UseCase 绑定（Preview, ImageCapture, ImageAnalysis）
 *   2. 前后置切换、闪光灯控制、点击对焦
 *   3. ML Kit 检测器生命周期管理
 *   4. 帧分析管道（对象检测 + 人脸检测 + 姿态检测）
 *   5. 将检测结果通过 [FrameCallback] 传递给上层（ViewModel）
 *
 * Activity 不再直接操作 CameraX / ML Kit，只通过 CameraManager 调度。
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    companion object {
        private const val TAG = "CameraManager"
        const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"
    }

    // ──── 回调接口 ────
    interface FrameCallback {
        /** 每帧分析完成时调用（后台线程） */
        fun onFrameAnalyzed(
            detectedObjects: List<CompositionRuleEngine.DetectionResult>,
            imageWidth: Int,
            imageHeight: Int,
            isFrontCamera: Boolean
        )

        /** 获取 AI 分析用的预览帧 Bitmap */
        fun capturePreviewBitmap(): Bitmap?

        /** 相机绑定失败 */
        fun onCameraError(message: String)
    }

    var frameCallback: FrameCallback? = null

    // ──── CameraX ────
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    var imageCapture: ImageCapture? = null
        private set
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    var isFrontCamera: Boolean = false
        private set

    // ──── ML Kit 检测器 ────
    private lateinit var objectDetector: ObjectDetector
    private lateinit var faceDetector: FaceDetector
    private lateinit var poseDetector: PoseDetector

    // ──── 执行器 ────
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ──── 分析节流 ────
    private var lastAnalysisTime = 0L
    private val analysisIntervalMs: Long by lazy {
        K80ProOptimizer.getPerformanceConfig(context).analysisIntervalMs
    }

    // ═══════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════

    fun initDetectors() {
        objectDetector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )

        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )

        poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    // ═══════════════════════════════════════════
    //  相机启动与绑定
    // ═══════════════════════════════════════════

    fun startCamera() {
        K80ProOptimizer.enableHighRefreshRate(context)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        isFrontCamera = !isFrontCamera
        cameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        rebind()
    }

    fun toggleTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    /**
     * 设置缩放比例
     * @param ratio 缩放倍率 (1.0 = 最小, 线性插值到最大)
     */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val clamped = ratio.coerceIn(1.0f, 10.0f)
        cam.cameraControl.setZoomRatio(clamped)
            .addListener({}, ContextCompat.getMainExecutor(context))
    }

    /**
     * 设置曝光补偿
     * @param index 曝光补偿索引 (整数, 范围取决于设备, 通常 -6 到 6)
     */
    fun setExposureCompensationIndex(index: Int) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(clamped)
            .addListener({}, ContextCompat.getMainExecutor(context))
    }

    /**
     * 获取当前缩放比例
     */
    fun getCurrentZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
    }

    /**
     * 获取曝光补偿范围
     */
    fun getExposureRange(): IntRange {
        val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
        return (range?.lower ?: -3)..(range?.upper ?: 3)
    }

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
        )
        action.addListener({
            try {
                action.get()
            } catch (e: Exception) {
                Log.w(TAG, "Focus failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ═══════════════════════════════════════════
    //  拍照
    // ═══════════════════════════════════════════

    fun takePicture(
        flashEnabled: Boolean,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture ?: return

        val outputDir = context.getExternalFilesDir(null) ?: return
        val fileName = SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
            .format(System.currentTimeMillis()) + ".jpg"
        val outputFile = File(outputDir, fileName)

        capture.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSuccess(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    onError(exception.message ?: "拍照失败")
                }
            }
        )
    }

    // ═══════════════════════════════════════════
    //  内部：UseCase 绑定
    // ═══════════════════════════════════════════

    private fun rebind() {
        cameraProvider?.unbindAll()
        bindUseCases()
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return

        // ──── 关键：获取当前显示旋转角度 ────
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.rotation
        }

        // ──── Preview ────
        val preview = Preview.Builder()
            .setTargetRotation(displayRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // ──── ImageCapture：与 Preview 一致的旋转 ────
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(displayRotation)
            .build()

        // ──── ImageAnalysis ────
        val analysisSize = K80ProOptimizer.getAnalysisResolution()
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(analysisSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(displayRotation)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture!!,
                imageAnalysis!!
            )
            Log.d(TAG, "Camera bound successfully")
        } catch (exc: Exception) {
            Log.e(TAG, "Camera bind failed", exc)
            frameCallback?.onCameraError("相机启动失败: ${exc.message}")
        }
    }

    // ═══════════════════════════════════════════
    //  内部：帧分析管道
    // ═══════════════════════════════════════════

    private fun analyzeFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val now = System.currentTimeMillis()

        if (now - lastAnalysisTime < analysisIntervalMs) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        val faceEnabled = ConfigManager.isFaceGuidanceEnabled
        val poseEnabled = ConfigManager.isPoseGuidanceEnabled

        // ──── P0 修复：统一用 AtomicReference 收集结果，用 CountDownLatch 同步 ────
        val objectResults = AtomicReference<List<CompositionRuleEngine.DetectionResult>>(emptyList())
        val poseResult = AtomicReference<com.google.mlkit.vision.pose.Pose?>(null)

        // 每个检测器对应一个 count，face/pose 可能被禁用（直接减掉）
        val activeCount = 1 + (if (faceEnabled) 1 else 0) + (if (poseEnabled) 1 else 0)
        val latch = CountDownLatch(activeCount)

        // ──── 对象检测 ────
        objectDetector.process(inputImage)
            .addOnSuccessListener { objects ->
                objectResults.set(objects.mapNotNull { obj ->
                    val box = obj.boundingBox
                    val centerX = (box.left + box.width() / 2f) / mediaImage.width
                    val centerY = (box.top + box.height() / 2f) / mediaImage.height
                    val width = box.width().toFloat() / mediaImage.width
                    val height = box.height().toFloat() / mediaImage.height

                    val category = obj.labels.firstOrNull()?.text?.let { classifyObject(it) } ?: "其他"

                    CompositionRuleEngine.DetectionResult(
                        centerX = centerX.coerceIn(0f, 1f),
                        centerY = centerY.coerceIn(0f, 1f),
                        width = width.coerceIn(0f, 1f),
                        height = height.coerceIn(0f, 1f),
                        aspectRatio = if (height > 0) width / height else 1f,
                        areaRatio = (width * height).coerceIn(0f, 1f),
                        category = category,
                        label = obj.labels.firstOrNull()?.text ?: "unknown"
                    )
                })
            }
            .addOnCompleteListener {
                latch.countDown()
            }

        // ──── 人脸检测 ────
        if (faceEnabled) {
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces.first()
                        val faceBox = face.boundingBox
                        val faceCenterX = (faceBox.left + faceBox.width() / 2f) / mediaImage.width
                        val faceCenterY = (faceBox.top + faceBox.height() / 2f) / mediaImage.height

                        // 合并逻辑：直接构建新列表，不依赖 objectResults 的当前值
                        val faceDetection = CompositionRuleEngine.DetectionResult(
                            centerX = faceCenterX.coerceIn(0f, 1f),
                            centerY = faceCenterY.coerceIn(0f, 1f),
                            width = (faceBox.width().toFloat() / mediaImage.width).coerceIn(0f, 1f),
                            height = (faceBox.height().toFloat() / mediaImage.height).coerceIn(0f, 1f),
                            aspectRatio = faceBox.width().toFloat() / faceBox.height().toFloat(),
                            areaRatio = (faceBox.width() * faceBox.height()).toFloat() /
                                    (mediaImage.width * mediaImage.height),
                            category = "人脸",
                            label = "face",
                            faceDetected = true,
                            faceSmilingProbability = face.smilingProbability ?: 0f,
                            faceClosedEyeProbability = face.leftEyeOpenProbability?.let { 1f - it } ?: 0f,
                            faceEulerY = face.headEulerAngleY
                        )

                        // 尝试与最近的检测对象合并
                        val current = objectResults.get()
                        val nearestObj = current.minByOrNull { obj ->
                            Math.abs(obj.centerX - faceCenterX) + Math.abs(obj.centerY - faceCenterY)
                        }
                        if (nearestObj != null && Math.abs(nearestObj.centerX - faceCenterX) < 0.2f) {
                            val updated = current.map {
                                if (it === nearestObj) it.copy(
                                    category = "人脸",
                                    faceDetected = true,
                                    faceSmilingProbability = face.smilingProbability ?: 0f,
                                    faceClosedEyeProbability = face.leftEyeOpenProbability?.let { 1f - it } ?: 0f,
                                    faceEulerY = face.headEulerAngleY
                                ) else it
                            }
                            objectResults.set(updated)
                        } else {
                            objectResults.set(current + faceDetection)
                        }
                    }
                }
                .addOnCompleteListener {
                    latch.countDown()
                }
        }

        // ──── 姿态检测 ────
        if (poseEnabled) {
            poseDetector.process(inputImage)
                .addOnSuccessListener { pose ->
                    poseResult.set(pose)
                }
                .addOnCompleteListener {
                    latch.countDown()
                }
        }

        // ──── 等待所有检测完成后再合并 ────
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                Log.w(TAG, "ML Kit detection timed out")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            imageProxy.close()
            return
        }

        // 此时所有 AtomicReference 都已就绪，安全读取
        finalizeFrame(objectResults.get(), poseResult.get(), imageProxy)
    }

    private fun finalizeFrame(
        objects: List<CompositionRuleEngine.DetectionResult>,
        pose: com.google.mlkit.vision.pose.Pose?,
        imageProxy: androidx.camera.core.ImageProxy
    ) {
        try {
            var finalObjects = objects

            // 姿态 → 肩膀倾斜角度合并到最近的人体对象
            if (pose != null) {
                val leftShoulder = pose.getPoseLandmark(
                    com.google.mlkit.vision.pose.PoseLandmark.LEFT_SHOULDER
                )
                val rightShoulder = pose.getPoseLandmark(
                    com.google.mlkit.vision.pose.PoseLandmark.RIGHT_SHOULDER
                )

                if (leftShoulder != null && rightShoulder != null) {
                    val tiltAngle = Math.toDegrees(
                        Math.atan2(
                            (rightShoulder.position.y - leftShoulder.position.y).toDouble(),
                            (rightShoulder.position.x - leftShoulder.position.x).toDouble()
                        )
                    ).toFloat()

                    val bodyObj = finalObjects.firstOrNull { it.category in listOf("人脸", "人体") }
                    if (bodyObj != null) {
                        val idx = finalObjects.indexOf(bodyObj)
                        finalObjects = finalObjects.toMutableList().apply {
                            set(idx, bodyObj.copy(
                                category = "人体",
                                poseDetected = true,
                                shoulderTilt = tiltAngle
                            ))
                        }
                    }
                }
            }

            frameCallback?.onFrameAnalyzed(
                finalObjects,
                imageProxy.width,
                imageProxy.height,
                isFrontCamera
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame finalization failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun classifyObject(label: String): String {
        val text = label.lowercase()
        return when {
            text.contains("face") || text.contains("person") || text.contains("human") -> "人脸"
            text.contains("food") || text.contains("drink") || text.contains("meal") -> "美食"
            text.contains("cat") || text.contains("dog") || text.contains("pet") -> "宠物"
            text.contains("building") || text.contains("tower") || text.contains("house") -> "建筑"
            text.contains("tree") || text.contains("mountain") || text.contains("water")
                || text.contains("landscape") -> "风景"
            text.contains("document") || text.contains("text") || text.contains("paper") -> "文档"
            text.contains("product") || text.contains("object") -> "产品"
            else -> "其他"
        }
    }

    // ═══════════════════════════════════════════
    //  资源释放
    // ═══════════════════════════════════════════

    fun release() {
        analysisExecutor.shutdown()
        if (::objectDetector.isInitialized) objectDetector.close()
        if (::faceDetector.isInitialized) faceDetector.close()
        if (::poseDetector.isInitialized) poseDetector.close()
    }
}
