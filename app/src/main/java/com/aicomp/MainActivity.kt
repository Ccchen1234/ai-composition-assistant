package com.aicomp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aicomp.camera.CameraManager
import com.aicomp.settings.ConfigManager
import com.aicomp.settings.SettingsActivity
import com.aicomp.viewmodel.CompositionUiState
import com.aicomp.viewmodel.CompositionViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import io.noties.markwon.Markwon

/**
 * MainActivity — 纯 UI 壳
 *
 * 不含任何业务逻辑，只负责：
 *   1. 初始化 CameraManager + CompositionViewModel
 *   2. 观察 CompositionUiState 渲染 UI
 *   3. 将用户操作转发给 ViewModel / CameraManager
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ──── ViewModel ────
    private val viewModel: CompositionViewModel by viewModels()

    // ──── CameraManager ────
    private lateinit var cameraManager: CameraManager

    // ──── Markdown 渲染器 ────
    private lateinit var markwon: Markwon

    // ──── UI 组件 ────
    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var guidanceText: TextView
    private lateinit var sceneHintText: TextView
    private lateinit var arrowIndicator: ImageView
    private lateinit var captureButton: View
    private lateinit var cameraSwitch: ImageButton
    private lateinit var gridToggle: ImageButton
    private lateinit var flashToggle: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var autoShutterToggle: View
    private lateinit var sensitivityLow: View
    private lateinit var sensitivityMedium: View
    private lateinit var sensitivityHigh: View

    // AI 辅助按钮
    private lateinit var aiAssistButton: View
    private lateinit var aiAssistProgress: ProgressBar

    // AI 指导 UI
    private lateinit var aiPanel: LinearLayout
    private lateinit var aiSceneLabel: TextView
    private lateinit var aiScore: TextView
    private lateinit var aiLoadingIndicator: ProgressBar
    private lateinit var aiCompositionAdvice: TextView
    private lateinit var aiTechnicalAdvice: TextView
    private lateinit var aiShootingTip: TextView
    private lateinit var aiAdjustmentsContainer: LinearLayout

    // ──── 权限 ────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            initCamera()
        } else {
            guidanceText.text = "需要相机权限才能使用，请在设置中开启"
            guidanceText.visibility = View.VISIBLE
        }
    }

    // ═══════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ConfigManager.init(this)
        HapticFeedbackManager.init(this)
        markwon = Markwon.create(this)

        bindViews()
        setupListeners()
        selectSensitivity(sensitivityMedium)

        if (hasCameraPermission()) {
            initCamera()
        } else {
            requestCameraPermission()
        }

        observeState()
        observeEvents()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAIConfig()
        viewModel.startStabilityTracking()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopStabilityTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.release()
        }
    }

    // ═══════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════

    private fun initCamera() {
        cameraManager = CameraManager(this, this, previewView)
        cameraManager.initDetectors()

        // 桥接：ViewModel → CameraManager（相机控制回调）
        viewModel.cameraControlCallback = object : CompositionViewModel.CameraControlCallback {
            override fun setZoomRatio(ratio: Float) {
                cameraManager.setZoomRatio(ratio)
            }

            override fun setExposureCompensationIndex(index: Int) {
                cameraManager.setExposureCompensationIndex(index)
            }
        }

        // 桥接：CameraManager → ViewModel
        cameraManager.frameCallback = object : CameraManager.FrameCallback {
            override fun onFrameAnalyzed(
                detectedObjects: List<CompositionRuleEngine.DetectionResult>,
                imageWidth: Int,
                imageHeight: Int,
                isFrontCamera: Boolean
            ) {
                viewModel.processFrame(
                    detectedObjects, imageWidth, imageHeight, isFrontCamera,
                    frameBitmapProvider = { capturePreviewBitmap() }
                )
            }

            override fun capturePreviewBitmap(): Bitmap? {
                return try {
                    val bmp = previewView.bitmap ?: return null
                    val scale = 320f / maxOf(bmp.width, bmp.height)
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * scale).toInt(),
                            (bmp.height * scale).toInt(),
                            true
                        )
                    } else {
                        bmp
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to capture preview frame", e)
                    null
                }
            }

            override fun onCameraError(message: String) {
                runOnUiThread {
                    guidanceText.text = message
                    guidanceText.visibility = View.VISIBLE
                }
            }
        }

        cameraManager.startCamera()
    }

    // ═══════════════════════════════════════════
    //  观察 StateFlow 渲染 UI
    // ═══════════════════════════════════════════

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state -> renderState(state) }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 拍照事件
                var lastTakePicture = 0L
                launch {
                    viewModel.takePictureEvent
                        .filter { it > lastTakePicture }
                        .collectLatest { timestamp ->
                            lastTakePicture = timestamp
                            doTakePicture()
                        }
                }
            }
        }
    }

    /**
     * 纯渲染：根据 State 更新所有 UI 元素
     */
    private fun renderState(state: CompositionUiState) {
        // 场景提示
        sceneHintText.text = state.sceneHint

        // 构图引导（AI 未启用时）
        if (state.guidanceVisible) {
            guidanceText.text = state.guidanceMessage
            guidanceText.visibility = View.VISIBLE
        } else {
            guidanceText.visibility = View.GONE
        }

        // 方向箭头
        if (state.arrowVisible) {
            arrowIndicator.rotation = when {
                state.arrowX > 0 -> 90f
                state.arrowX < 0 -> 270f
                state.arrowY > 0 -> 180f
                else -> 0f
            }
            arrowIndicator.visibility = View.VISIBLE
        } else {
            arrowIndicator.visibility = View.GONE
        }

        // 叠加层
        overlayView.setRecommendedBox(state.recommendedBox, state.imageWidth, state.imageHeight)
        overlayView.setTiltAngle(state.tiltAngle)

        // AI 矢量引导（指令驱动模式）
        if (state.guideVisible) {
            overlayView.setGuideVector(state.guideDx, state.guideDy, state.guideMessage)
        } else {
            overlayView.clearGuide()
        }

        // 网格
        overlayView.setShowGrid(state.gridEnabled)
        gridToggle.alpha = if (state.gridEnabled) 1.0f else 0.5f

        // 闪光灯
        flashToggle.setImageResource(
            if (state.flashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
        cameraManager.toggleTorch(state.flashEnabled)

        // 自动快门
        autoShutterToggle.alpha = if (state.autoShutterEnabled) 1.0f else 0.5f

        // AI 辅助按钮状态
        renderAIAssistButton(state)

        // AI 面板
        aiPanel.visibility = if (state.aiEnabled) View.VISIBLE else View.GONE

        if (state.aiEnabled) {
            aiLoadingIndicator.visibility = if (state.aiAnalyzing) View.VISIBLE else View.GONE

            aiSceneLabel.text = state.aiSceneLabel
            // 性冷淡风格评分色：克制版
            val scoreColor = when {
                state.aiScore >= 80 -> "#6A9A7A"   // 暗薄荷绿
                state.aiScore >= 60 -> "#A08860"   // 暗琥珀
                else -> "#B06060"                   // 暗珊瑚
            }
            aiScore.text = " ${state.aiScore}分"
            aiScore.setTextColor(android.graphics.Color.parseColor(scoreColor))

            markwon.setMarkdown(aiCompositionAdvice, state.aiCompositionAdvice)
            aiCompositionAdvice.visibility =
                if (state.aiCompositionAdvice.isNotBlank()) View.VISIBLE else View.GONE

            markwon.setMarkdown(aiTechnicalAdvice, state.aiTechnicalAdvice ?: "")
            aiTechnicalAdvice.visibility =
                if (state.aiTechnicalAdvice.isNotBlank()) View.VISIBLE else View.GONE

            markwon.setMarkdown(aiShootingTip, state.aiShootingTip)
            aiShootingTip.visibility =
                if (state.aiShootingTip.isNotBlank()) View.VISIBLE else View.GONE

            // 关键调整项
            aiAdjustmentsContainer.removeAllViews()
            if (state.aiKeyAdjustments.isNotEmpty()) {
                state.aiKeyAdjustments.forEach { adj ->
                    val tv = TextView(this).apply {
                        markwon.setMarkdown(this, "• $adj")
                        setTextColor(android.graphics.Color.parseColor("#606060"))
                        textSize = 11f
                        setPadding(0, 2, 0, 2)
                    }
                    aiAdjustmentsContainer.addView(tv)
                }
                aiAdjustmentsContainer.visibility = View.VISIBLE
            } else {
                aiAdjustmentsContainer.visibility = View.GONE
            }

            // AI 错误
            state.aiError?.let { error ->
                aiTechnicalAdvice.text = error
                aiTechnicalAdvice.visibility = View.VISIBLE
            }
        }
    }

    // ═══════════════════════════════════════════
    //  拍照
    // ═══════════════════════════════════════════

    private fun doTakePicture() {
        if (ConfigManager.isHapticEnabled) {
            HapticFeedbackManager.autoShutterTrigger()
        }

        cameraManager.takePicture(
            flashEnabled = viewModel.stateValue.flashEnabled,
            onSuccess = { file ->
                // EXIF 写入
                val exifData = getCompositionExifData()
                val galleryUri = ExifWriterService.savePhotoToGallery(
                    file, exifData, contentResolver
                )

                val msg = if (galleryUri != null) "📸 已保存到相册" else "📸 已保存: ${file.name}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                sceneHintText.text = "✅ $msg"
            },
            onError = { error ->
                Toast.makeText(this, "拍照失败: $error", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun getCompositionExifData(): ExifWriterService.CompositionExifData {
        val result = viewModel.lastCompositionResult
        val aiResult = viewModel.getAIManager().getLastResult()
        val state = viewModel.stateValue
        return ExifWriterService.CompositionExifData(
            sceneId = if (state.isFrontCamera) "selfie" else "main",
            sceneLabel = aiResult?.sceneDescription ?: sceneHintText.text.toString(),
            matchedRuleIds = result?.guidances?.map { it.ruleId } ?: emptyList(),
            matchedRuleLabels = result?.guidances?.map { it.message } ?: emptyList(),
            isAutoShutter = state.autoShutterEnabled
        )
    }

    // ═══════════════════════════════════════════
    //  UI 绑定与事件监听
    // ═══════════════════════════════════════════

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        guidanceText = findViewById(R.id.guidanceText)
        sceneHintText = findViewById(R.id.sceneHintText)
        arrowIndicator = findViewById(R.id.arrowIndicator)
        captureButton = findViewById(R.id.captureButton)
        cameraSwitch = findViewById(R.id.cameraSwitch)
        gridToggle = findViewById(R.id.gridToggle)
        flashToggle = findViewById(R.id.flashToggle)
        settingsButton = findViewById(R.id.settingsButton)
        autoShutterToggle = findViewById(R.id.autoShutterToggle)
        sensitivityLow = findViewById(R.id.sensitivityLow)
        sensitivityMedium = findViewById(R.id.sensitivityMedium)
        sensitivityHigh = findViewById(R.id.sensitivityHigh)

        aiAssistButton = findViewById(R.id.aiAssistButton)
        aiAssistProgress = findViewById(R.id.aiAssistProgress)

        aiPanel = findViewById(R.id.aiPanel)
        aiSceneLabel = findViewById(R.id.aiSceneLabel)
        aiScore = findViewById(R.id.aiScore)
        aiLoadingIndicator = findViewById(R.id.aiLoadingIndicator)
        aiCompositionAdvice = findViewById(R.id.aiCompositionAdvice)
        aiTechnicalAdvice = findViewById(R.id.aiTechnicalAdvice)
        aiShootingTip = findViewById(R.id.aiShootingTip)
        aiAdjustmentsContainer = findViewById(R.id.aiAdjustmentsContainer)
    }

    private fun setupListeners() {
        // 拍照
        captureButton.setOnClickListener {
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.buttonClick()
            doTakePicture()
        }

        // 前后置切换
        cameraSwitch.setOnClickListener {
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.buttonClick()
            cameraManager.switchCamera()
            viewModel.onCameraSwitched()
        }

        // 网格开关
        gridToggle.setOnClickListener {
            viewModel.toggleGrid()
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.toggleSwitch(viewModel.stateValue.gridEnabled)
        }

        // 闪光灯
        flashToggle.setOnClickListener {
            viewModel.toggleFlash()
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.toggleSwitch(viewModel.stateValue.flashEnabled)
        }

        // 设置页
        settingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        // 自动快门
        autoShutterToggle.setOnClickListener {
            viewModel.toggleAutoShutter()
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.toggleSwitch(viewModel.stateValue.autoShutterEnabled)
        }

        // 灵敏度
        sensitivityLow.setOnClickListener {
            viewModel.setSensitivity(AutoShutterEngine.Sensitivity.LOW)
            selectSensitivity(sensitivityLow)
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.buttonClick()
        }
        sensitivityMedium.setOnClickListener {
            viewModel.setSensitivity(AutoShutterEngine.Sensitivity.MEDIUM)
            selectSensitivity(sensitivityMedium)
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.buttonClick()
        }
        sensitivityHigh.setOnClickListener {
            viewModel.setSensitivity(AutoShutterEngine.Sensitivity.HIGH)
            selectSensitivity(sensitivityHigh)
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.buttonClick()
        }

        // 点击对焦
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val cam = if (::cameraManager.isInitialized) cameraManager else return@setOnTouchListener false
                cam.focusAt(event.x, event.y)
                if (ConfigManager.isHapticEnabled) HapticFeedbackManager.focusSuccess()
            }
            false
        }

        // AI 面板手动触发
        aiPanel.setOnClickListener {
            if (viewModel.stateValue.aiEnabled) {
                previewView.bitmap?.let { bmp ->
                    val scale = 320f / maxOf(bmp.width, bmp.height)
                    val small = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else bmp
                    viewModel.requestAIManualAnalyze(small)
                }
            }
        }

        // AI 辅助按钮 - 点击触发 AI 分析
        aiAssistButton.setOnClickListener {
            if (ConfigManager.isHapticEnabled) HapticFeedbackManager.buttonClick()

            val currentState = viewModel.stateValue.aiAssistState

            // 正在分析中，忽略
            if (currentState == CompositionUiState.AIAssistState.ANALYZING) {
                return@setOnClickListener
            }

            // 捕获当前帧
            previewView.bitmap?.let { bmp ->
                val scale = 320f / maxOf(bmp.width, bmp.height)
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                } else bmp

                if (currentState == CompositionUiState.AIAssistState.HAS_RESULT) {
                    // 有结果 → 清除旧结果，立即开始新分析
                    viewModel.clearAIAssist()
                }
                // 触发新分析
                viewModel.triggerAIAssist(small)
            }
        }
    }

    private fun selectSensitivity(selected: View) {
        val buttons = listOf(sensitivityLow, sensitivityMedium, sensitivityHigh)
        buttons.forEach { it.alpha = if (it == selected) 1.0f else 0.5f }
    }

    /**
     * 渲染 AI 辅助按钮状态
     *
     * 性冷淡风格：冷青(#5AC8C8)为主，克制不抢眼
     */
    private fun renderAIAssistButton(state: CompositionUiState) {
        val textView = (aiAssistButton as? FrameLayout)?.getChildAt(0) as? TextView

        when (state.aiAssistState) {
            CompositionUiState.AIAssistState.IDLE -> {
                aiAssistProgress.visibility = View.GONE
                textView?.text = "AI"
                textView?.setTextColor(android.graphics.Color.parseColor("#5A6060"))
                aiAssistButton.alpha = if (state.aiEnabled) 0.9f else 0.3f
            }
            CompositionUiState.AIAssistState.ANALYZING -> {
                aiAssistProgress.visibility = View.VISIBLE
                textView?.text = ""
                aiAssistButton.alpha = 0.9f
            }
            CompositionUiState.AIAssistState.HAS_RESULT -> {
                aiAssistProgress.visibility = View.GONE
                textView?.text = "✓"
                textView?.setTextColor(android.graphics.Color.parseColor("#6A9A7A"))  // 暗薄荷
                aiAssistButton.alpha = 1.0f
            }
            CompositionUiState.AIAssistState.ERROR -> {
                aiAssistProgress.visibility = View.GONE
                textView?.text = "!"
                textView?.setTextColor(android.graphics.Color.parseColor("#B06060"))  // 暗珊瑚
                aiAssistButton.alpha = 0.9f
            }
        }
    }

    // ═══════════════════════════════════════════
    //  权限
    // ═══════════════════════════════════════════

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }
}
