package com.aicomp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.aicomp.viewmodel.CompositionUiState.CropZone
import com.aicomp.viewmodel.CompositionUiState.FocusIndicator

/**
 * 构图叠加层
 * 显示：九宫格、交叉点、推荐构图框、主体框、水平仪、最佳拍摄点
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ====== 画笔（性冷淡风格：极细 + 低饱和） ======

    /** 九宫格线：0.8dp，暗灰 */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2C2C")
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }

    /** 交叉点：小十字（非实心圆） */
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        strokeCap = Paint.Cap.ROUND
    }

    /** 主体检测框 */
    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A4A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 最佳拍摄点：小圆点 + 细环 */
    private val targetDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5AC8C8")
        style = Paint.Style.FILL
    }

    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A6060")
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }

    /** 水平仪 */
    private val levelBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }

    private val levelBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val levelPath = Path()

    // AI 引导箭头
    private val guideArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A6060")  // 暗青色，不抢画面
        style = Paint.Style.FILL
    }
    private val guideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#707070")   // 中灰，非纯白
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val guideTextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC111111") // 深灰玻璃背景
        style = Paint.Style.FILL
    }
    private val guideArrowPath = Path()

    // 裁剪区域遮罩画笔
    private val cropMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5AC8C8")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        alpha = 220
    }
    private val cropCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5AC8C8")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val cropTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0FFFFFF")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val cropTextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1A1A1A")
        style = Paint.Style.FILL
    }

    // 对焦指示器画笔
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val focusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ====== 状态 ======
    private var subjectRect = RectF()
    private var recommendedBox: CompositionBoxEngine.CompositionBox? = null
    private var targetPoint: Pair<Float, Float>? = null
    private var showGrid: Boolean = true
    private var tiltAngle: Float = 0f
    private var showLevel: Boolean = true

    // AI 矢量引导
    private var guideDx: Float = 0f
    private var guideDy: Float = 0f
    private var guideMessage: String = ""
    private var showGuide: Boolean = false
    private var animatedGuideDx: Float = 0f
    private var animatedGuideDy: Float = 0f
    private var guideAlpha: Float = 0f  // 0-255
    private var guideAnimator: ValueAnimator? = null

    // 裁剪区域
    private var cropZone: CropZone? = null
    private var animatedCropRect: RectF? = null
    private var cropAnimator: ValueAnimator? = null

    // 对焦指示器
    private var focusIndicator: FocusIndicator? = null
    private var focusIndicatorX: Float = 0f
    private var focusIndicatorY: Float = 0f
    private var focusIndicatorAlpha: Float = 0f  // 0-255
    private var focusRingRadius: Float = 30f
    private var focusIndicatorSuccess: Boolean = true
    private var focusAnimator: ValueAnimator? = null
    private var focusRingPulseAnimator: ValueAnimator? = null

    // 动画状态
    private var animatedBox: RectF? = null
    private var boxAnimator: ValueAnimator? = null
    private var ringRadius = 20f
    private var ringAnimator: ValueAnimator? = null

    // 图像尺寸（用于坐标转换）
    private var sourceImageWidth: Int = 1
    private var sourceImageHeight: Int = 1

    // ====== 公开接口 ======

    fun setShowGrid(show: Boolean) {
        showGrid = show
        invalidate()
    }

    fun setShowLevel(show: Boolean) {
        showLevel = show
        invalidate()
    }

    /**
     * 设置 AI 移动引导矢量（带平滑动画）
     * @param dx 水平偏移 (-1.0 到 1.0, >0 向右, <0 向左)
     * @param dy 垂直偏移 (-1.0 到 1.0, >0 向下, <0 向上)
     * @param message 引导消息文本
     */
    fun setGuideVector(dx: Float, dy: Float, message: String) {
        guideDx = dx.coerceIn(-1000f, 1000f)
        guideDy = dy.coerceIn(-1000f, 1000f)
        guideMessage = message
        showGuide = dx != 0f || dy != 0f || message.isNotBlank()

        if (showGuide) {
            animateGuideTo(guideDx, guideDy, 255f)
        } else {
            animateGuideFadeOut()
        }
    }

    /**
     * 清除 AI 移动引导
     */
    fun clearGuide() {
        showGuide = false
        guideDx = 0f
        guideDy = 0f
        guideMessage = ""
        animateGuideFadeOut()
    }

    /**
     * 设置 AI 推荐裁剪区域（带动画）
     * @param zone 归一化裁剪区，null 清除
     */
    fun setCropZone(zone: CropZone?) {
        cropZone = zone
        if (zone != null && zone.isValid() && width > 0 && height > 0) {
            val targetRect = RectF(
                zone.x1 * width,
                zone.y1 * height,
                zone.x2 * width,
                zone.y2 * height
            )
            animateCropTo(targetRect)
        } else {
            animateCropFadeOut()
        }
        invalidate()
    }

    /**
     * 设置点击对焦指示器（带入场动画）
     * @param indicator 归一化坐标的指示器，null 清除
     */
    fun setFocusIndicator(indicator: FocusIndicator?) {
        focusIndicator = indicator
        if (indicator != null && width > 0 && height > 0) {
            // 转换为屏幕坐标
            val targetX = indicator.x * width
            val targetY = indicator.y * height
            focusIndicatorSuccess = indicator.success
            animateFocusIndicatorTo(targetX, targetY)
        } else {
            animateFocusFadeOut()
        }
    }

    private fun animateFocusIndicatorTo(targetX: Float, targetY: Float) {
        focusAnimator?.cancel()
        focusRingPulseAnimator?.cancel()
        val startX = focusIndicatorX
        val startY = focusIndicatorY
        focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                focusIndicatorX = startX + (targetX - startX) * f
                focusIndicatorY = startY + (targetY - startY) * f
                focusIndicatorAlpha = (f * 255f).toInt().coerceIn(0, 255).toFloat()
                focusRingRadius = 40f - f * 20f  // 从大到小
                invalidate()
            }
            start()
        }
        // 脉冲动画（从紧到松然后保持）
        focusRingPulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                focusRingRadius = 20f + (1f - f) * 8f  // 20→28→20
                invalidate()
            }
            start()
        }
    }

    private fun animateFocusFadeOut() {
        focusAnimator?.cancel()
        focusRingPulseAnimator?.cancel()
        focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { anim ->
                focusIndicatorAlpha = ((1f - anim.animatedFraction) * 255f).toInt().coerceIn(0, 255).toFloat()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    focusIndicator = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun animateCropTo(target: RectF) {
        cropAnimator?.cancel()
        val start = animatedCropRect ?: target
        cropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                animatedCropRect = RectF(
                    start.left + (target.left - start.left) * f,
                    start.top + (target.top - start.top) * f,
                    start.right + (target.right - start.right) * f,
                    start.bottom + (target.bottom - start.bottom) * f
                )
                invalidate()
            }
            start()
        }
    }

    private fun animateCropFadeOut() {
        cropAnimator?.cancel()
        animatedCropRect = null
    }

    private fun animateGuideTo(targetDx: Float, targetDy: Float, targetAlpha: Float) {
        guideAnimator?.cancel()
        val startDx = animatedGuideDx
        val startDy = animatedGuideDy
        val startAlpha = guideAlpha
        guideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250  // 性冷淡：稍快，避免拖沓感
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                animatedGuideDx = startDx + (targetDx - startDx) * f
                animatedGuideDy = startDy + (targetDy - startDy) * f
                guideAlpha = startAlpha + (targetAlpha - startAlpha) * f
                invalidate()
            }
            start()
        }
    }

    private fun animateGuideFadeOut() {
        animateGuideTo(0f, 0f, 0f)
    }

    fun setTiltAngle(angle: Float) {
        if (Math.abs(tiltAngle - angle) > 0.5f) {
            tiltAngle = angle
            invalidate()
        }
    }

    /**
     * 设置主体检测框
     */
    fun setSubject(
        centerX: Float, centerY: Float,
        width: Float, height: Float,
        imageWidth: Int, imageHeight: Int
    ) {
        sourceImageWidth = imageWidth
        sourceImageHeight = imageHeight

        if (this.width == 0 || this.height == 0) return

        val scaleX = this.width.toFloat() / imageWidth.toFloat()
        val scaleY = this.height.toFloat() / imageHeight.toFloat()

        subjectRect = RectF(
            (centerX - width / 2) * scaleX,
            (centerY - height / 2) * scaleY,
            (centerX + width / 2) * scaleX,
            (centerY + height / 2) * scaleY
        )
        invalidate()
    }

    /**
     * 设置推荐构图框（带动画过渡）
     */
    fun setRecommendedBox(
        box: CompositionBoxEngine.CompositionBox?,
        imageWidth: Int,
        imageHeight: Int
    ) {
        sourceImageWidth = imageWidth
        sourceImageHeight = imageHeight
        recommendedBox = box

        // 更新最佳提示点 = 推荐框中心
        if (box != null && this.width > 0 && this.height > 0) {
            val cx = (box.x1 + box.x2) / 2f * this.width
            val cy = (box.y1 + box.y2) / 2f * this.height
            targetPoint = Pair(cx, cy)

            // 动画到新位置
            val targetRect = RectF(
                box.x1 * this.width,
                box.y1 * this.height,
                box.x2 * this.width,
                box.y2 * this.height
            )
            animateBoxTo(targetRect)
            animateRing()
        } else {
            targetPoint = null
            animatedBox = null
        }

        invalidate()
    }

    // ====== 动画 ======

    private fun animateBoxTo(target: RectF) {
        boxAnimator?.cancel()

        val start = animatedBox ?: target
        boxAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                animatedBox = RectF(
                    start.left + (target.left - start.left) * fraction,
                    start.top + (target.top - start.top) * fraction,
                    start.right + (target.right - start.right) * fraction,
                    start.bottom + (target.bottom - start.bottom) * fraction
                )
                invalidate()
            }
            start()
        }
    }

    private fun animateRing() {
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(18f, 12f).apply {
            duration = 500  // 性冷淡：更慢、更克制的脉动
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                ringRadius = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ====== 绘制 ======

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 九宫格
        if (showGrid) {
            drawGrid(canvas, w, h)
        }

        // 2. 水平仪
        if (showLevel && Math.abs(tiltAngle) > 1.5f) {
            drawLevel(canvas, w, h)
        }

        // 3. 推荐构图框
        animatedBox?.let { rect ->
            val box = recommendedBox ?: return@let
            val color = Color.parseColor(box.color)
            boxPaint.color = color
            boxPaint.alpha = 180
            boxPaint.strokeWidth = 1f

            // 极淡填充
            boxFillPaint.color = color
            boxFillPaint.alpha = 20

            canvas.drawRect(rect, boxFillPaint)
            canvas.drawRect(rect, boxPaint)

            // 极细角标
            drawCornerMarks(canvas, rect, color)
        }

        // 4. 最佳拍摄点（极简：小点 + 细环）
        targetPoint?.let { (x, y) ->
            canvas.drawCircle(x, y, ringRadius, targetRingPaint)
            canvas.drawCircle(x, y, 3f, targetDotPaint)
        }

        // 5. 检测主体（极细虚线框）
        if (subjectRect.width() > 0 && subjectRect.height() > 0) {
            subjectPaint.alpha = 80
            canvas.drawRect(subjectRect, subjectPaint)
        }

        // 6. AI 移动引导箭头
        if (guideAlpha > 1f) {
            drawGuideArrows(canvas, w, h)
        }

        // 7. AI 裁剪区域
        animatedCropRect?.let { rect ->
            drawCropZone(canvas, rect, cropZone)
        }

        // 8. 点击对焦指示器
        if (focusIndicatorAlpha > 0 && focusIndicator != null) {
            drawFocusIndicator(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val thirdW = w / 3f
        val thirdH = h / 3f

        // 竖线
        canvas.drawLine(thirdW, 0f, thirdW, h, gridPaint)
        canvas.drawLine(thirdW * 2, 0f, thirdW * 2, h, gridPaint)
        // 横线
        canvas.drawLine(0f, thirdH, w, thirdH, gridPaint)
        canvas.drawLine(0f, thirdH * 2, w, thirdH * 2, gridPaint)

        // 四个交叉点：改为小十字（8px），非实心圆
        val crossHalf = 4f
        val points = listOf(
            Pair(thirdW, thirdH),
            Pair(thirdW * 2, thirdH),
            Pair(thirdW, thirdH * 2),
            Pair(thirdW * 2, thirdH * 2)
        )
        for ((cx, cy) in points) {
            // 横线
            canvas.drawLine(cx - crossHalf, cy, cx + crossHalf, cy, crossPaint)
            // 竖线
            canvas.drawLine(cx, cy - crossHalf, cx, cy + crossHalf, crossPaint)
        }
    }

    private fun drawLevel(canvas: Canvas, w: Float, h: Float) {
        // 性冷淡：水平仪极简化
        val barWidth = w * 0.35f
        val barHeight = 1.5f
        val centerX = w / 2f
        val centerY = h / 2f
        val left = centerX - barWidth / 2f

        // 背景细线
        levelBarPaint.alpha = 60
        canvas.drawLine(left, centerY, left + barWidth, centerY, levelBarPaint)

        // 气泡偏移
        val maxOffset = barWidth / 2f - 6f
        val offset = (tiltAngle / 30f * maxOffset).coerceIn(-maxOffset, maxOffset)
        val bubbleX = centerX + offset

        // 气泡颜色：水平时淡青，否则暗灰
        levelBubblePaint.color = if (kotlin.math.abs(tiltAngle) < 3f) {
            Color.parseColor("#5AC8C8")
        } else {
            Color.parseColor("#505050")
        }

        canvas.drawCircle(bubbleX, centerY, 4f, levelBubblePaint)

        // 角度文字（小字，中灰）
        levelBarPaint.alpha = 80
        levelBarPaint.textSize = 18f
        levelBarPaint.textAlign = Paint.Align.CENTER
        val text = if (kotlin.math.abs(tiltAngle) > 0.5f) {
            String.format("%.1f", kotlin.math.abs(tiltAngle)) + "°"
        } else ""
        if (text.isNotEmpty()) {
            canvas.drawText(text, centerX, centerY + 24f, levelBarPaint)
        }
    }

    /**
     * 绘制 AI 移动引导箭头
     *
     * 性冷淡风格：暗青色箭头（非亮蓝），小尺寸，克制
     */
    private fun drawGuideArrows(canvas: Canvas, w: Float, h: Float) {
        val alpha = guideAlpha.toInt().coerceIn(0, 255)
        if (alpha < 5) return

        guideArrowPaint.alpha = (alpha * 0.7f).toInt().coerceIn(0, 255)
        guideTextPaint.alpha = alpha
        guideTextBgPaint.alpha = (alpha * 0.5f).toInt().coerceIn(0, 255)

        val normDx = animatedGuideDx / 1000f
        val normDy = animatedGuideDy / 1000f
        val absDx = kotlin.math.abs(normDx)
        val absDy = kotlin.math.abs(normDy)

        // 水平箭头
        if (absDx > 0.03f) {
            val arrowSize = (30f + absDx * 100f).coerceAtMost(60f)
            val centerY = h / 2f

            if (normDx > 0) {
                val baseX = w - 24f
                guideArrowPath.reset()
                guideArrowPath.moveTo(baseX, centerY - arrowSize / 2)
                guideArrowPath.lineTo(baseX + arrowSize, centerY)
                guideArrowPath.lineTo(baseX, centerY + arrowSize / 2)
                guideArrowPath.close()
            } else {
                val baseX = 24f
                guideArrowPath.reset()
                guideArrowPath.moveTo(baseX, centerY - arrowSize / 2)
                guideArrowPath.lineTo(baseX - arrowSize, centerY)
                guideArrowPath.lineTo(baseX, centerY + arrowSize / 2)
                guideArrowPath.close()
            }
            canvas.drawPath(guideArrowPath, guideArrowPaint)
        }

        // 垂直箭头
        if (absDy > 0.03f) {
            val arrowSize = (30f + absDy * 100f).coerceAtMost(60f)
            val centerX = w / 2f

            if (normDy > 0) {
                val baseY = h - 160f
                guideArrowPath.reset()
                guideArrowPath.moveTo(centerX - arrowSize / 2, baseY)
                guideArrowPath.lineTo(centerX, baseY + arrowSize)
                guideArrowPath.lineTo(centerX + arrowSize / 2, baseY)
                guideArrowPath.close()
            } else {
                val baseY = 160f
                guideArrowPath.reset()
                guideArrowPath.moveTo(centerX - arrowSize / 2, baseY)
                guideArrowPath.lineTo(centerX, baseY - arrowSize)
                guideArrowPath.lineTo(centerX + arrowSize / 2, baseY)
                guideArrowPath.close()
            }
            canvas.drawPath(guideArrowPath, guideArrowPaint)
        }

        // 引导消息文本（深灰玻璃底）
        if (guideMessage.isNotBlank()) {
            val textY = h / 2f + 90f
            val textWidth = guideTextPaint.measureText(guideMessage)
            val padding = 16f

            canvas.drawRoundRect(
                w / 2f - textWidth / 2f - padding,
                textY - 22f,
                w / 2f + textWidth / 2f + padding,
                textY + 12f,
                6f, 6f,
                guideTextBgPaint
            )
            canvas.drawText(guideMessage, w / 2f, textY, guideTextPaint)
        }
    }

    /**
     * 绘制 AI 裁剪区域：半透明遮罩 + 青色边框 + 角标
     */
    private fun drawCropZone(canvas: Canvas, rect: RectF, zone: CropZone?) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 外部遮罩（上下左右四块）
        canvas.drawRect(0f, 0f, w, rect.top, cropMaskPaint)         // 上
        canvas.drawRect(0f, rect.bottom, w, h, cropMaskPaint)     // 下
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, cropMaskPaint) // 左
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, cropMaskPaint) // 右

        // 青色边框
        canvas.drawRect(rect, cropBorderPaint)

        // 四角粗线标记
        val cornerLen = 16f
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLen, rect.top, cropCornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLen, cropCornerPaint)

        canvas.drawLine(rect.right, rect.top, rect.right - cornerLen, rect.top, cropCornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLen, cropCornerPaint)

        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLen, rect.bottom, cropCornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLen, cropCornerPaint)

        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLen, rect.bottom, cropCornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLen, cropCornerPaint)

        // 裁剪建议文字
        zone?.message?.takeIf { it.isNotBlank() }?.let { msg ->
            val textY = rect.top - 10f
            val textW = cropTextPaint.measureText(msg)
            val pad = 12f
            if (textY > 40f) {
                canvas.drawRoundRect(
                    rect.centerX() - textW / 2 - pad,
                    textY - 22f,
                    rect.centerX() + textW / 2 + pad,
                    textY + 8f,
                    4f, 4f,
                    cropTextBgPaint
                )
                canvas.drawText(msg, rect.centerX(), textY, cropTextPaint)
            }
        }
    }

    /**
     * 绘制点击对焦指示器：方角环 + 中心点
     */
    private fun drawFocusIndicator(canvas: Canvas) {
        val alpha = focusIndicatorAlpha.coerceIn(0f, 255f).toInt()
        val cx = focusIndicatorX
        val cy = focusIndicatorY
        val r = focusRingRadius

        // 颜色：成功=淡青色，失败=暗珊瑚
        val ringColor = if (focusIndicatorSuccess) {
            Color.parseColor("#5AC8C8")
        } else {
            Color.parseColor("#B06060")
        }

        focusRingPaint.color = ringColor
        focusRingPaint.alpha = alpha
        focusDotPaint.color = ringColor
        focusDotPaint.alpha = alpha

        // 方角环（正方形）
        canvas.drawRoundRect(
            cx - r, cy - r,
            cx + r, cy + r,
            4f, 4f,
            focusRingPaint
        )

        // 内部加一圈更细的
        focusRingPaint.strokeWidth = 0.8f
        canvas.drawRoundRect(
            cx - r + 4f, cy - r + 4f,
            cx + r - 4f, cy + r - 4f,
            3f, 3f,
            focusRingPaint
        )
        focusRingPaint.strokeWidth = 1.5f

        // 中心小方点
        canvas.drawRect(cx - 2f, cy - 2f, cx + 2f, cy + 2f, focusDotPaint)
    }

    private fun drawCornerMarks(canvas: Canvas, rect: RectF, color: Int) {
        // 性冷淡：极细角标（1dp），不抢画面
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = Paint.Style.STROKE
            this.strokeWidth = 1f
            this.alpha = 160
        }
        val markLen = 10f

        canvas.drawLine(rect.left, rect.top, rect.left + markLen, rect.top, markPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + markLen, markPaint)

        canvas.drawLine(rect.right, rect.top, rect.right - markLen, rect.top, markPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + markLen, markPaint)

        canvas.drawLine(rect.left, rect.bottom, rect.left + markLen, rect.bottom, markPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - markLen, markPaint)

        canvas.drawLine(rect.right, rect.bottom, rect.right - markLen, rect.bottom, markPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - markLen, markPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        boxAnimator?.cancel()
        ringAnimator?.cancel()
        guideAnimator?.cancel()
        cropAnimator?.cancel()
        focusAnimator?.cancel()
        focusRingPulseAnimator?.cancel()
    }
}
