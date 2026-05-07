package com.aicomp.ai

import org.json.JSONObject

/**
 * AI 指令协议数据类
 *
 * 与 Qwen3-VL-Flash 约定的 JSON 响应格式（[0, 1000] 像素坐标系）：
 * ```json
 * {
 *   "action": "MOVE_GUIDE | CAMERA_CONTROL | SHUTTER",
 *   "guide": { "dx": 120, "dy": -80, "message": "向右平移" },
 *   "camera": { "zoom": 1.5, "ev": -1 },
 *   "shutter": "READY | WAITING"
 * }
 * ```
 *
 * dx/dy 为像素偏移（正=右/下，负=左/上），50~300 范围
 * |dx|<30 且 |dy|<30 视为已对准
 */
/**
 * AI 裁剪区域（归一化 0-1 坐标系）
 * @param x1,y1 左上角归一化坐标
 * @param x2,y2 右下角归一化坐标
 * @param message 裁剪建议文字
 */
data class AICropZone(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val message: String = ""
) {
    fun isValid() = x2 > x1 && y2 > y1 &&
            x1 >= 0f && y1 >= 0f && x2 <= 1f && y2 <= 1f
}

data class AICommand(
    val action: String = "",
    val guideDx: Float = 0f,
    val guideDy: Float = 0f,
    val guideMessage: String = "",
    val cameraZoom: Float? = null,
    val cameraEv: Int? = null,
    val shutterState: ShutterState = ShutterState.WAITING,
    /** AI 推荐裁剪区域（归一化 0-1 坐标） */
    val cropZone: AICropZone? = null,
    // 保留传统构图分析字段（向后兼容）
    val sceneDescription: String = "",
    val compositionAdvice: String = "",
    val technicalAdvice: String = "",
    val score: Int = 0,
    val isGoodShot: Boolean = false,
    val rawResponse: String = ""
) {
    enum class ShutterState {
        READY,      // AI 判定构图已达标
        WAITING     // 还在等待
    }

    /** 是否包含移动引导指令 */
    val hasGuide: Boolean get() = action.contains("MOVE_GUIDE") && (guideDx != 0f || guideDy != 0f)

    /** 是否包含相机控制指令 */
    val hasCameraControl: Boolean get() = action.contains("CAMERA_CONTROL")

    /** 是否包含快门指令 */
    val hasShutterCommand: Boolean get() = action.contains("SHUTTER")

    /** 快门是否就绪 */
    val isShutterReady: Boolean get() = shutterState == ShutterState.READY

    companion object {
        /**
         * 解析 AI 返回的 JSON 字符串为 AICommand
         * 兼容两种格式：
         * 1. 新协议（带 action/guide/camera/shutter）
         * 2. 旧协议（scene/composition/technical/score/good_shot）
         */
        fun fromJson(jsonStr: String): AICommand {
            return try {
                val json = JSONObject(jsonStr)

                // 检查是否是新协议格式
                if (json.has("action")) {
                    parseNewProtocol(json, jsonStr)
                } else {
                    // 旧协议格式（向后兼容）
                    parseLegacyProtocol(json, jsonStr)
                }
            } catch (e: Exception) {
                AICommand(rawResponse = jsonStr)
            }
        }

        private fun parseNewProtocol(json: JSONObject, raw: String): AICommand {
            val action = json.optString("action", "")
            val guide = json.optJSONObject("guide")
            val camera = json.optJSONObject("camera")
            val shutterStr = json.optString("shutter", "WAITING")
            val crop = json.optJSONObject("crop")

            // guide 字段兼容：优先 "message"，回退 "msg"
            val guideMessage = guide?.let {
                it.optString("message", "").ifEmpty { it.optString("msg", "") }
            } ?: ""

            // crop 字段解析
            val cropZone = crop?.let {
                val x1 = it.optDouble("x1", Double.NaN).toFloat()
                val y1 = it.optDouble("y1", Double.NaN).toFloat()
                val x2 = it.optDouble("x2", Double.NaN).toFloat()
                val y2 = it.optDouble("y2", Double.NaN).toFloat()
                val msg = it.optString("message", "").ifEmpty { it.optString("msg", "") }
                if (!x1.isNaN() && !y1.isNaN() && !x2.isNaN() && !y2.isNaN()) {
                    AICropZone(x1, y1, x2, y2, msg)
                } else null
            }

            return AICommand(
                action = action,
                guideDx = guide?.optDouble("dx", 0.0)?.toFloat() ?: 0f,
                guideDy = guide?.optDouble("dy", 0.0)?.toFloat() ?: 0f,
                guideMessage = guideMessage,
                cameraZoom = camera?.let {
                    val z = it.optDouble("zoom", Double.NaN)
                    if (z.isNaN()) null else z.toFloat()
                },
                cameraEv = camera?.optInt("ev", Int.MIN_VALUE)?.let {
                    if (it == Int.MIN_VALUE) null else it
                },
                shutterState = when (shutterStr.uppercase()) {
                    "READY" -> ShutterState.READY
                    else -> ShutterState.WAITING
                },
                cropZone = cropZone,
                rawResponse = raw
            )
        }

        private fun parseLegacyProtocol(json: JSONObject, raw: String): AICommand {
            return AICommand(
                action = "MOVE_GUIDE",
                sceneDescription = json.optString("scene", ""),
                compositionAdvice = json.optString("composition", ""),
                technicalAdvice = json.optString("technical", ""),
                score = json.optInt("score", 50),
                isGoodShot = json.optBoolean("good_shot", false),
                rawResponse = raw
            )
        }
    }
}
