package com.aicomp

import android.graphics.RectF

// 完整构图规则引擎
// 整合 ML Kit 多模型检测能力
// 按照优先级：错误预防(11) > 全局基础(10) > 核心构图(9) > 进阶(8) > 场景特定(7) > 组合(6)
object CompositionRuleEngine {

    data class Guidance(
        val message: String,
        val ruleId: String,
        val priority: Int,
        val confidence: Float,
        val boxRuleId: String?,
        val boxStyle: String,
        val tipText: String?
    )

    data class CompositionResult(
        val guidances: List<Guidance>,
        val recommendedBox: CompositionBoxEngine.CompositionBox?,
        val arrowX: Float,
        val arrowY: Float,
        val tiltAngle: Float, // 倾斜角度，用于水平校准
        val isPerfect: Boolean
    )

    data class DetectionResult(
        val centerX: Float,  // 0-1 左上角原点
        val centerY: Float,
        val width: Float,
        val height: Float,
        val aspectRatio: Float,
        val areaRatio: Float,  // 主体占画面比例
        val category: String,  // 人脸/人体/宠物/美食/建筑/风景/其他
        val label: String,
        // 人脸检测结果
        val faceDetected: Boolean = false,
        val faceSmilingProbability: Float = 0f,
        val faceClosedEyeProbability: Float = 0f,
        val faceEulerY: Float = 0f, // 人脸倾斜角度
        // 姿势检测结果
        val poseDetected: Boolean = false,
        val shoulderTilt: Float = 0f, // 肩膀倾斜角度
        val bodyAngle: Float = 0f // 身体整体角度
    )

    // 全局配置
    object Config {
        val crossPoints = listOf(
            Pair(0.33f, 0.33f),
            Pair(0.67f, 0.33f),
            Pair(0.33f, 0.67f),
            Pair(0.67f, 0.67f)
        )
        const val horizontalTolerance = 3.0f  // 倾斜容忍度(角度)
        const val minSubjectRatio = 0.05f
        const val maxSubjectRatio = 0.90f
        val subjectPriority = listOf(
            "人脸", "人体", "宠物", "美食", "产品", "建筑", "风景", "文档", "其他"
        )
        val boxStyles = mapOf(
            "primary" to BoxStyle("#00FF00", 2f, 0.8f),
            "secondary" to BoxStyle("#FFFF00", 1.5f, 0.6f),
            "error" to BoxStyle("#FF0000", 2f, 0.9f)
        )
    }

    data class BoxStyle(
        val color: String,
        val lineWidth: Float,
        val opacity: Float
    )

    fun analyze(
        subjects: List<DetectionResult>,
        imageWidth: Int,
        imageHeight: Int
    ): CompositionResult {

        val guidances = mutableListOf<Guidance>()
        var arrowX = 0f
        var arrowY = 0f
        var tiltAngle = 0f

        // 步骤1：选择核心主体（按优先级）
        val mainSubject = selectMainSubject(subjects)

        // ========== 优先级11: 错误预防（最高） ==========
        // ERROR_001: 水平倾斜检测（利用人脸姿势检测角度）
        if (mainSubject.faceDetected) {
            if (kotlin.math.abs(mainSubject.faceEulerY) > 5f) {
                guidances.add(Guidance(
                    message = "⚠️ 人脸倾斜，请旋转手机摆正",
                    ruleId = "ERROR_001",
                    priority = 11,
                    confidence = 0.95f,
                    boxRuleId = null,
                    boxStyle = "error",
                    tipText = "保持人脸正对相机"
                ))
                tiltAngle = mainSubject.faceEulerY
                if (mainSubject.faceEulerY > 0) {
                    arrowY -= 1f
                } else {
                    arrowY += 1f
                }
            }
        }

        if (mainSubject.poseDetected) {
            if (kotlin.math.abs(mainSubject.shoulderTilt) > 5f) {
                guidances.add(Guidance(
                    message = "⚠️ 肩膀倾斜，请调整保持水平",
                    ruleId = "ERROR_001",
                    priority = 11,
                    confidence = 0.85f,
                    boxRuleId = null,
                    boxStyle = "error",
                    tipText = "肩膀放平更自然"
                ))
            }
        }

        // ERROR_002: 人物切边检查
        if (mainSubject.category in listOf("人脸", "人体")) {
            val top = mainSubject.centerY - mainSubject.height/2
            val bottom = mainSubject.centerY + mainSubject.height/2
            if (top < 0.02f) {
                guidances.add(Guidance(
                    message = "⚠️ 头部被裁切，请向下移动手机",
                    ruleId = "ERROR_002",
                    priority = 11,
                    confidence = 0.98f,
                    boxRuleId = null,
                    boxStyle = "error",
                    tipText = "避免切割人物头部和脚部"
                ))
                if (arrowY == 0f) arrowY += 1f
            }
            if (bottom > 0.98f) {
                guidances.add(Guidance(
                    message = "⚠️ 脚部被裁切，请向上移动手机",
                    ruleId = "ERROR_002",
                    priority = 11,
                    confidence = 0.98f,
                    boxRuleId = null,
                    boxStyle = "error",
                    tipText = "避免切割人物头部和脚部"
                ))
                if (arrowY == 0f) arrowY -= 1f
            }
        }

        // ERROR_003: 视线堵塞检查
        if (mainSubject.category in listOf("人脸", "人体", "宠物")) {
            // 简化：假设人物朝右
            val rightSpace = 1f - (mainSubject.centerX + mainSubject.width/2)
            val leftSpace = mainSubject.centerX - mainSubject.width/2
            if (rightSpace < leftSpace && rightSpace < 0.1f) {
                guidances.add(Guidance(
                    message = "⚠️ 视线方向空间不足，请向左移动",
                    ruleId = "ERROR_003",
                    priority = 11,
                    confidence = 0.95f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "人物视线方向保留更多空间"
                ))
                arrowX -= 1f
            }
        }

        // ERROR_004: 主体过小/过大
        if (mainSubject.areaRatio < Config.minSubjectRatio) {
            guidances.add(Guidance(
                message = "⚠️ 主体太小，请靠近/放大拍摄",
                ruleId = "ERROR_004",
                priority = 11,
                confidence = 0.92f,
                boxRuleId = null,
                boxStyle = "secondary",
                tipText = "靠近主体或放大焦距"
            ))
        }
        if (mainSubject.areaRatio > Config.maxSubjectRatio) {
            guidances.add(Guidance(
                message = "⚠️ 主体太大，请后退/缩小拍摄",
                ruleId = "ERROR_004",
                priority = 11,
                confidence = 0.92f,
                boxRuleId = null,
                boxStyle = "secondary",
                tipText = "后退留出更多空间"
            ))
        }

        // ========== 优先级10: 全局基础规则 ==========
        // GLOBAL_003: 留白与视线规则
        if (mainSubject.category in listOf("人脸", "人体", "宠物")) {
            val topSpace = mainSubject.centerY - mainSubject.height/2
            if (topSpace < 1f/6) {
                guidances.add(Guidance(
                    message = "ℹ️ 头顶留白不足，请向下移动",
                    ruleId = "GLOBAL_003",
                    priority = 10,
                    confidence = 0.95f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = null
                ))
                if (arrowY == 0f) arrowY += 1f
            }
        }

        // ========== 人脸表情专项检测 ==========
        if (mainSubject.faceDetected) {
            if (mainSubject.faceClosedEyeProbability > 0.5f) {
                guidances.add(Guidance(
                    message = "👁 提示：请睁开眼睛",
                    ruleId = "FACE_001",
                    priority = 10,
                    confidence = (1f - mainSubject.faceClosedEyeProbability),
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "睁开眼睛拍摄更好看"
                ))
            }
            if (mainSubject.faceSmilingProbability < 0.3f) {
                // P2 修复：confidence 应反映"触发指导的确定性"，
                // 笑脸概率低时我们很确定需要提醒，因此用 1-笑脸概率。
                // 原代码 confidence = faceSmilingProbability 逻辑反了。
                guidances.add(Guidance(
                    message = "😊 提示：自然微笑更好看",
                    ruleId = "FACE_002",
                    priority = 10,
                    confidence = 1f - mainSubject.faceSmilingProbability,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "放松表情自然微笑"
                ))
            }
        }

        // ========== 姿势专项检测 ==========
        if (mainSubject.poseDetected) {
            // 人像站立提示
            if (mainSubject.category == "人体") {
                guidances.add(Guidance(
                    message = "📐 提示：身体微侧 30°更好看",
                    ruleId = "POSE_001",
                    priority = 9,
                    confidence = 0.8f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "侧身站立更显瘦"
                ))
                // 显瘦提示
                guidances.add(Guidance(
                    message = "🦵 提示：腿部延伸到画面底部，踮脚显高",
                    ruleId = "POSE_002",
                    priority = 8,
                    confidence = 0.7f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "腿部拉长更显高"
                ))
            }
        }

        // ========== 优先级9: 核心构图规则 ==========
        // CORE_001: 三分构图法
        if (isSingleSubject(subjects) && !hasSymmetry(mainSubject) && !hasLinearElement(mainSubject)) {
            val (closestPoint, dx, dy) = findClosestCrossPoint(mainSubject.centerX, mainSubject.centerY)

            if (kotlin.math.abs(dx) > 0.05f) {
                if (dx > 0) {
                    guidances.add(Guidance(
                        message = "→ 请向右移动手机",
                        ruleId = "CORE_001",
                        priority = 9,
                        confidence = 0.95f,
                        boxRuleId = "BOX_001",
                        boxStyle = "primary",
                        tipText = "将主体放在绿色框内"
                    ))
                    arrowX += 1f
                } else {
                    guidances.add(Guidance(
                        message = "← 请向左移动手机",
                        ruleId = "CORE_001",
                        priority = 9,
                        confidence = 0.95f,
                        boxRuleId = "BOX_001",
                        boxStyle = "primary",
                        tipText = "将主体放在绿色框内"
                    ))
                    arrowX -= 1f
                }
            }

            if (kotlin.math.abs(dy) > 0.05f) {
                if (dy > 0) {
                    guidances.add(Guidance(
                        message = "↓ 请向下移动手机",
                        ruleId = "CORE_001",
                        priority = 9,
                        confidence = 0.95f,
                        boxRuleId = "BOX_001",
                        boxStyle = "primary",
                        tipText = "将主体放在绿色框内"
                    ))
                    arrowY += 1f
                } else {
                    guidances.add(Guidance(
                        message = "↑ 请向上移动手机",
                        ruleId = "CORE_001",
                        priority = 9,
                        confidence = 0.95f,
                        boxRuleId = "BOX_001",
                        boxStyle = "primary",
                        tipText = "将主体放在绿色框内"
                    ))
                    arrowY -= 1f
                }
            }
        }

        // CORE_002: 中心构图
        if (isCenterCandidate(mainSubject)) {
            val dx = 0.5f - mainSubject.centerX
            val dy = 0.5f - mainSubject.centerY
            if (kotlin.math.abs(dx) > 0.05f || kotlin.math.abs(dy) > 0.05f) {
                if (kotlin.math.abs(dx) > 0.05f) {
                    if (dx > 0) {
                        guidances.add(Guidance(
                            message = "→ 向右移到中心",
                            ruleId = "CORE_002",
                            priority = 9,
                            confidence = 0.92f,
                            boxRuleId = "BOX_002",
                            boxStyle = "primary",
                            tipText = "将主体放在画面中心"
                        ))
                        arrowX += 1f
                    } else {
                        guidances.add(Guidance(
                            message = "← 向左移到中心",
                            ruleId = "CORE_002",
                            priority = 9,
                            confidence = 0.92f,
                            boxRuleId = "BOX_002",
                            boxStyle = "primary",
                            tipText = "将主体放在画面中心"
                        ))
                        arrowX -= 1f
                    }
                }
            }
        }

        // ========== 优先级7: 场景特定规则 ==========
        when (mainSubject.category) {
            "美食" -> {
                guidances.add(Guidance(
                    message = "🍜 建议：45°俯拍，重心放右下交叉点",
                    ruleId = "SCENE_001",
                    priority = 7,
                    confidence = 0.90f,
                    boxRuleId = "BOX_001",
                    boxStyle = "primary",
                    tipText = "45度俯拍美食"
                ))
                guidances.add(Guidance(
                    message = "💡 提示：对焦食材纹理，背景简洁留白",
                    ruleId = "SCENE_001",
                    priority = 7,
                    confidence = 0.85f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "简化背景突出主体"
                ))
            }
            "人脸" -> {
                if (mainSubject.areaRatio < 0.3f) {
                    guidances.add(Guidance(
                        message = "👤 环境人像：人物占比 1/4-1/3 很好",
                        ruleId = "SCENE_004",
                        priority = 7,
                        confidence = 0.85f,
                        boxRuleId = "BOX_001",
                        boxStyle = "primary",
                        tipText = "保留更多环境背景"
                    ))
                }
                // 人像姿势提示
                guidances.add(Guidance(
                    message = "👤 提示：下巴微收，肩膀放松",
                    ruleId = "SCENE_003",
                    priority = 7,
                    confidence = 0.87f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "放松表情更自然"
                ))
            }
            "宠物" -> {
                guidances.add(Guidance(
                    message = "🐾 建议：机位与宠物眼睛等高",
                    ruleId = "SCENE_005",
                    priority = 7,
                    confidence = 0.86f,
                    boxRuleId = "BOX_001",
                    boxStyle = "primary",
                    tipText = "与宠物眼睛保持同一高度"
                ))
                guidances.add(Guidance(
                    message = "💡 提示：保持对焦眼睛，跟随移动准备连拍",
                    ruleId = "SCENE_005",
                    priority = 7,
                    confidence = 0.8f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "抓住宠物自然瞬间"
                ))
            }
            "风景" -> {
                if (kotlin.math.abs(mainSubject.centerY - 0.5f) < 0.08f) {
                    guidances.add(Guidance(
                        message = "🌄 建议：地平线放在三分线位置",
                        ruleId = "SCENE_006",
                        priority = 7,
                        confidence = 0.89f,
                        boxRuleId = "BOX_001",
                        boxStyle = "primary",
                        tipText = "将地平线放在三分线"
                    ))
                }
                guidances.add(Guidance(
                    message = "🌄 建议：增加前景增强层次感",
                    ruleId = "ADV_003",
                    priority = 8,
                    confidence = 0.78f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "前景占比 10%-30%"
                ))
            }
            "建筑" -> {
                // P2 修复：ruleId 改为 SCENE_007（原 ERROR_001 与水平倾斜检测冲突，语义错误）
                guidances.add(Guidance(
                    message = "🏢 建议：低角度仰拍，顶部适当留白",
                    ruleId = "SCENE_007",
                    priority = 7,
                    confidence = 0.84f,
                    boxRuleId = null,
                    boxStyle = "primary",
                    tipText = "低角度仰拍更有气势"
                ))
                guidances.add(Guidance(
                    message = "📐 提示：保持竖线垂直，避免倾斜",
                    ruleId = "SCENE_007",
                    priority = 7,
                    confidence = 0.85f,
                    boxRuleId = null,
                    boxStyle = "secondary",
                    tipText = "保持垂直线与边框平行"
                ))
            }
            "文档" -> {
                guidances.add(Guidance(
                    message = "📄 文档拍摄：保持平行，摆正手机，对焦文字",
                    ruleId = "SCENE_008",
                    priority = 7,
                    confidence = 0.9f,
                    boxRuleId = "BOX_002",
                    boxStyle = "primary",
                    tipText = "放平手机正对文档"
                ))
            }
        }

        // 按优先级排序，只保留最高优先级的前3条（性能优化）
        val sortedGuidances = guidances.sortedByDescending { it.priority }
        val topGuidances = sortedGuidances.take(3)

        // 计算推荐构图框
        val recommendedBox = if (topGuidances.isNotEmpty() && topGuidances[0].boxRuleId != null) {
            val boxRule = topGuidances[0].boxRuleId!!
            CompositionBoxEngine.calculateBox(
                ruleId = boxRule,
                compositionType = topGuidances[0].ruleId,
                centerX = mainSubject.centerX,
                centerY = mainSubject.centerY,
                subjectWidth = mainSubject.width,
                subjectHeight = mainSubject.height,
                screenAspect = imageWidth.toFloat() / imageHeight.toFloat()
            )
        } else if (subjects.size == 1) {
            // 默认三分框
            CompositionBoxEngine.calculateBox(
                ruleId = "BOX_001",
                compositionType = "三分构图",
                centerX = mainSubject.centerX,
                centerY = mainSubject.centerY,
                subjectWidth = mainSubject.width,
                subjectHeight = mainSubject.height,
                screenAspect = imageWidth.toFloat() / imageHeight.toFloat()
            )
        } else {
            null
        }

        // 判断是否完美
        val isPerfect = topGuidances.isEmpty() ||
                (topGuidances.size == 1 && topGuidances[0].priority < 11)

        return CompositionResult(
            guidances = topGuidances,
            recommendedBox = recommendedBox,
            arrowX = arrowX,
            arrowY = arrowY,
            tiltAngle = tiltAngle,
            isPerfect = isPerfect
        )
    }

    private fun selectMainSubject(subjects: List<DetectionResult>): DetectionResult {
        if (subjects.isEmpty()) {
            return DetectionResult(0.5f, 0.5f, 0.5f, 0.5f, 1f, 0.25f, "其他", "unknown")
        }
        // P2 修复：indexOf 返回 -1 时未识别类别会得到最高分（-(-1)*100=100），
        // 用 takeIf 保证未知类别排在最后。
        return subjects.maxByOrNull {
            val priorityIndex = Config.subjectPriority.indexOf(it.category)
                .takeIf { i -> i >= 0 } ?: Config.subjectPriority.size
            -priorityIndex * 100 + (it.areaRatio * 10).toInt()
        } ?: subjects.first()
    }

    private fun findClosestCrossPoint(x: Float, y: Float): Triple<Pair<Float, Float>, Float, Float> {
        var closest = Config.crossPoints[0]
        var minDist = Float.MAX_VALUE
        for (point in Config.crossPoints) {
            val dist = kotlin.math.abs(point.first - x) + kotlin.math.abs(point.second - y)
            if (dist < minDist) {
                minDist = dist
                closest = point
            }
        }
        val dx = closest.first - x
        val dy = closest.second - y
        return Triple(closest, dx, dy)
    }

    private fun isSingleSubject(subjects: List<DetectionResult>): Boolean {
        return subjects.size <= 1
    }

    private fun hasSymmetry(subject: DetectionResult): Boolean {
        return subject.category in listOf("建筑", "产品") &&
               kotlin.math.abs(subject.centerX - 0.5f) < 0.1f
    }

    private fun hasLinearElement(subject: DetectionResult): Boolean {
        return subject.aspectRatio > 3f
    }

    private fun isCenterCandidate(subject: DetectionResult): Boolean {
        return (subject.category in listOf("产品", "建筑") && subject.areaRatio > 0.5f) ||
               (subject.category == "美食" && subject.areaRatio > 0.4f) ||
               (subject.category == "文档")
    }
}
