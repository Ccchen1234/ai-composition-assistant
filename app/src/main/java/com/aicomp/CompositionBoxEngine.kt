package com.aicomp

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// 实时构图框计算引擎
// 根据构图类型计算推荐的目标构图框
object CompositionBoxEngine {

    data class CompositionBox(
        val x1: Float,  // 0-1 左上角
        val y1: Float,
        val x2: Float,  // 0-1 右下角
        val y2: Float,
        val rotation: Float,  // 旋转角度
        val type: String,
        val tipText: String,
        val color: String
    )

    // 计算推荐构图框
    fun calculateBox(
        ruleId: String,
        compositionType: String,
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float  // 宽高比
    ): CompositionBox {

        return when (ruleId) {
            "BOX_001" -> calculateThirdsBox(compositionType, centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            "BOX_002" -> calculateCenterBox(centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            "BOX_003" -> calculateGuideLineBox(centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            "BOX_004" -> calculateSymmetryBox(centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            "BOX_005" -> calculateFrameBox(centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            "BOX_006" -> calculateDiagonalBox(centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            "BOX_007" -> calculateTriangleBox(centerX, centerY, subjectWidth, subjectHeight)
            "BOX_008" -> calculateFillBox(centerX, centerY, subjectWidth, subjectHeight, screenAspect)
            else -> defaultBox()
        }
    }

    // BOX_001: 三分构图 - 以交叉点为中心
    private fun calculateThirdsBox(
        compositionType: String,
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        // 找到最近的交叉点
        val crossPoints = listOf(
            Triple(0.33f, 0.33f, "左上"),
            Triple(0.67f, 0.33f, "右上"),
            Triple(0.33f, 0.67f, "左下"),
            Triple(0.67f, 0.67f, "右下")
        )
        val closest = crossPoints.minByOrNull {
            abs(it.first - centerX) + abs(it.second - centerY)
        } ?: crossPoints[0]

        val targetCx = closest.first
        val targetCy = closest.second

        // 构图框大小 = 主体大小 × 1.5
        val boxW = subjectWidth * 1.5f
        val boxH = subjectHeight * 1.5f

        // 预定义区域
        val region = when (closest.third) {
            "左上" -> RectF(0.0f, 0.0f, 0.66f, 0.66f)
            "右上" -> RectF(0.34f, 0.0f, 1.0f, 0.66f)
            "左下" -> RectF(0.0f, 0.34f, 0.66f, 1.0f)
            "右下" -> RectF(0.34f, 0.34f, 1.0f, 1.0f)
            else -> RectF(0.0f, 0.0f, 1.0f, 1.0f)
        }

        return CompositionBox(
            x1 = region.left,
            y1 = region.top,
            x2 = region.right,
            y2 = region.bottom,
            rotation = 0f,
            type = "三分构图",
            tipText = "将主体放在绿色框内",
            color = "#00FF00"
        )
    }

    // BOX_002: 中心构图
    private fun calculateCenterBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        val boxW = subjectWidth * 1.2f
        val boxH = subjectHeight * 1.2f
        val x1 = 0.5f - boxW / 2
        val y1 = 0.5f - boxH / 2
        val x2 = 0.5f + boxW / 2
        val y2 = 0.5f + boxH / 2

        return CompositionBox(
            x1 = max(0f, x1),
            y1 = max(0f, y1),
            x2 = min(1f, x2),
            y2 = min(1f, y2),
            rotation = 0f,
            type = "中心构图",
            tipText = "将主体放在画面中心",
            color = "#00FF00"
        )
    }

    // BOX_003: 引导线构图
    private fun calculateGuideLineBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        // 简化：引导线终点在交叉点，沿引导线方向拉伸
        val boxW = subjectWidth * 1.2f
        val boxH = subjectHeight * 1.2f
        // 找最近交叉点作为终点
        val targetCx = if (centerX < 0.5f) 0.33f else 0.67f
        val targetCy = if (centerY < 0.5f) 0.33f else 0.67f

        val x1 = targetCx - boxW / 2
        val y1 = targetCy - boxH / 2
        val x2 = targetCx + boxW / 2
        val y2 = targetCy + boxH / 2

        return CompositionBox(
            x1 = max(0f, x1),
            y1 = max(0f, y1),
            x2 = min(1f, x2),
            y2 = min(1f, y2),
            rotation = 0f,
            type = "引导线构图",
            tipText = "沿引导线移动手机",
            color = "#00FF00"
        )
    }

    // BOX_004: 对称构图
    private fun calculateSymmetryBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        // 自动对称轴对齐
        val boxW = subjectWidth * 1.1f
        val boxH = subjectHeight * 1.1f
        val dx = 0.5f - centerX
        val x1 = 0.5f - boxW / 2 + dx
        val y1 = 0.5f - boxH / 2
        val x2 = 0.5f + boxW / 2 + dx
        val y2 = 0.5f + boxH / 2

        return CompositionBox(
            x1 = max(0f, x1),
            y1 = max(0f, y1),
            x2 = min(1f, x2),
            y2 = min(1f, y2),
            rotation = 0f,
            type = "对称构图",
            tipText = "保持画面对称",
            color = "#00FF00"
        )
    }

    // BOX_005: 框架式构图
    private fun calculateFrameBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        // 框架内部 × 0.9
        val boxW = subjectWidth * 0.9f
        val boxH = subjectHeight * 0.9f
        val x1 = centerX - boxW / 2
        val y1 = centerY - boxH / 2
        val x2 = centerX + boxW / 2
        val y2 = centerY + boxH / 2

        return CompositionBox(
            x1 = max(0f, x1),
            y1 = max(0f, y1),
            x2 = min(1f, x2),
            y2 = min(1f, y2),
            rotation = 0f,
            type = "框架构图",
            tipText = "将主体放在框架内",
            color = "#00FF00"
        )
    }

    // BOX_006: 对角线构图
    private fun calculateDiagonalBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        // 主体对角线对齐
        val angle = if (centerX < centerY) 45f else -45f

        return CompositionBox(
            x1 = 0f,
            y1 = 0f,
            x2 = 1f,
            y2 = 1f,
            rotation = angle,
            type = "对角线构图",
            tipText = "将主体沿对角线放置",
            color = "#00FF00"
        )
    }

    // BOX_007: 三角形构图
    private fun calculateTriangleBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float
    ): CompositionBox {
        // 三角形重心为框中心
        val boxW = subjectWidth * 1.1f
        val boxH = subjectHeight * 1.1f
        val x1 = centerX - boxW / 2
        val y1 = centerY - boxH / 2
        val x2 = centerX + boxW / 2
        val y2 = centerY + boxH / 2

        return CompositionBox(
            x1 = max(0f, x1),
            y1 = max(0f, y1),
            x2 = min(1f, x2),
            y2 = min(1f, y2),
            rotation = 30f,
            type = "三角形构图",
            tipText = "三个主体构成三角形",
            color = "#00FF00"
        )
    }

    // BOX_008: 填充式构图
    private fun calculateFillBox(
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox {
        // 边缘距离均匀，主体占80%-90%
        val targetRatio = 0.85f
        val scale = targetRatio / (subjectWidth * subjectHeight)
        val boxW = subjectWidth * scale
        val boxH = subjectHeight * scale
        val x1 = centerX - boxW / 2
        val y1 = centerY - boxH / 2
        val x2 = centerX + boxW / 2
        val y2 = centerY + boxH / 2

        return CompositionBox(
            x1 = max(0f, x1),
            y1 = max(0f, y1),
            x2 = min(1f, x2),
            y2 = min(1f, y2),
            rotation = 0f,
            type = "填充式构图",
            tipText = "靠近主体填满画面",
            color = "#00FF00"
        )
    }

    private fun defaultBox(): CompositionBox {
        return CompositionBox(
            x1 = 0f,
            y1 = 0f,
            x2 = 1f,
            y2 = 1f,
            rotation = 0f,
            type = "默认",
            tipText = "对准拍摄主体",
            color = "#00FF00"
        )
    }
}
