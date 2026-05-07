package com.aicomp.ai

import android.graphics.Bitmap
import android.util.Log
import com.aicomp.settings.ConfigManager
import com.aicomp.strategy.StrategyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DashScope 千问 Qwen3-VL-Flash Provider（原生 API）
 *
 * 使用 DashScope 原生多模态对话协议，非 OpenAI 兼容格式。
 *
 * Endpoint: https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
 * Auth: Bearer {api_key}
 * Request: input.messages[].content[{image, text}]
 * Response: output.choices[].message.content[{text}]
 */
class QwenProvider(
    private var config: ConfigManager.ApiConfig
) : AIAdvisor {

    companion object {
        private const val TAG = "QwenProvider"
        private const val NATIVE_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        /** P2 修复：网络抖动时最多重试 1 次，移动网络下尤其重要 */
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 500L
    }

    override fun isAvailable(): Boolean = config.apiKey.isNotBlank()

    override fun getProviderName(): String = "Qwen3-VL-Flash (DashScope)"

    fun updateConfig(newConfig: ConfigManager.ApiConfig) {
        config = newConfig
        Log.d(TAG, "Config updated")
    }

    // ═══════════════════════════════════════════
    //  指令驱动分析
    // ═══════════════════════════════════════════

    suspend fun analyzeCommand(
        frame: Bitmap,
        sceneType: String = "auto",
        previousGuide: String? = null
    ): AICommand = withContext(Dispatchers.IO) {
        try {
            val imageBase64 = ImageEncodePool.encodeForAI(frame)
            val requestBody = buildCommandRequest(imageBase64, sceneType, previousGuide)
            val response = executeRequest(requestBody)

            if (response != null) {
                val content = parseDashScopeResponse(response)
                if (content != null) {
                    AICommand.fromJson(content)
                } else {
                    AICommand(rawResponse = "Empty response")
                }
            } else {
                AICommand(rawResponse = "API request failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command analysis failed", e)
            AICommand(rawResponse = e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════
    //  传统模式（向后兼容）
    // ═══════════════════════════════════════════

    override suspend fun analyzeComposition(
        frame: Bitmap,
        sceneType: String,
        previousGuidance: String?
    ): AICompositionResult = withContext(Dispatchers.IO) {
        try {
            val imageBase64 = ImageEncodePool.encodeForAI(frame)
            val requestBody = buildLegacyRequest(imageBase64, sceneType, previousGuidance)
            val response = executeRequest(requestBody)

            if (response != null) {
                val content = parseDashScopeResponse(response)
                if (content != null) {
                    parseLegacyContent(content)
                } else {
                    createErrorResult("Empty response")
                }
            } else {
                createErrorResult("API request failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Composition analysis failed", e)
            createErrorResult(e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════
    //  网络请求（含重试）
    // ═══════════════════════════════════════════

    /**
     * P2 修复：executeRequest 加上最多 MAX_RETRIES 次重试。
     * 移动网络波动时首次请求可能失败，稍等 RETRY_DELAY_MS 后重试一次。
     */
    private fun executeRequest(requestBody: JSONObject): String? {
        var lastException: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                Thread.sleep(RETRY_DELAY_MS)
                Log.d(TAG, "Retrying request (attempt ${attempt + 1}/$MAX_RETRIES)")
            }

            val result = doHttpRequest(requestBody)
            if (result != null) return result

            // 只有 IOException 才值得重试；API 返回错误码（如401/429）重试无意义
            lastException = lastException ?: return null
        }

        Log.e(TAG, "All $MAX_RETRIES retries failed, last error: $lastException")
        return null
    }

    private fun doHttpRequest(requestBody: JSONObject): String? {
        val url = URL(NATIVE_ENDPOINT)
        return try {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "API error $responseCode: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network request failed (attempt)", e)
            null
        }
    }

    // ═══════════════════════════════════════════
    //  DashScope 原生请求体构建
    // ═══════════════════════════════════════════

    /**
     * 系统提示词
     */
    private val SYSTEM_PROMPT = """
你是运行在红米 K80 Pro 底层的专业摄影指挥中枢，也是资深人像摄影师。

坐标系：输入图像对应 [0, 1000] 的像素坐标。左上角 (0,0)，右下角 (1000,1000)。

输出限制：必须且只能输出纯净的 JSON 对象，严禁包含任何解释性文字。

## 你的职责
1. 分析当前画面的构图、光线、人物姿势
2. 对比该场景的理想构图参数（用户消息中会提供）
3. 给出粗粒度的可执行调整指令
4. 适时给出姿势/表情/动作建议（用 message 字段）
5. 构图到位时果断输出 SHUTTER READY

## 核心原则：粗粒度可执行
用户手持手机拍摄，精度有限。偏差 < 30 像素视为已对准。
只在有明显偏差时才给出调整，微调不需要。

## 构图专业准则
- 三分法：人物放在左/右三分之一线上
- 视线延伸：人物看的方向留更多空间
- 头部留白：头顶到画面上边缘留 8%-15% 空间
- 脚部空间：全身照脚到下边缘留 5%-10%
- 侧身显瘦：45度侧身比正面更有立体感
- 避免裁切：不要从关节处（膝盖/脚踝/手腕）裁切

## 常见错误检测
- 头顶长树/电线杆 → 报告并建议蹲下或平移
- 地平线歪斜 → 建议水平调整
- 脸部大面积阴影 → 建议换个朝向
- 背景杂乱 → 建议靠近主体或换个角度

## JSON 输出格式
{
  "action": "MOVE_GUIDE|CAMERA_CONTROL|SHUTTER",
  "guide": { "dx": 120, "dy": -80, "message": "向右平移" },
  "camera": { "zoom": 1.5, "ev": -1 },
  "shutter": "WAITING|READY"
}

### guide 字段
- dx/dy: [0, 1000] 像素坐标系中的偏移量。正=右/下，负=左/上。
- 只给 50~300 的调整值。|dx|<30 且 |dy|<30 时不给调整。
- message: 中文指令。可以是移动指令，也可以是姿势建议。
  例："向左平移""下蹲仰拍""侧身45度""回头看向镜头"

### camera 字段
- zoom: 倍率(1.0~5.0)
- ev: 曝光补偿(-3~3)

### shutter
- 构图+姿势都到位 → "READY"
- 还需要调整 → "WAITING"
    """.trimIndent()

    private fun buildCommandRequest(
        imageBase64: String,
        sceneType: String,
        previousGuide: String?
    ): JSONObject {
        val dynamicUserPrompt = StrategyManager.buildDynamicUserPrompt(sceneType)
        val continuityNote = if (previousGuide != null) {
            "\n上一轮引导: \"$previousGuide\"\n如果场景没变，给更精确的调整指令。"
        } else ""

        val userText = dynamicUserPrompt + continuityNote

        // DashScope 原生格式: content 是数组，每项是 {image, text}
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("image", "data:image/jpeg;base64,$imageBase64")
            })
            put(JSONObject().apply {
                put("text", userText)
            })
        }

        return JSONObject().apply {
            put("model", config.modelName)
            put("input", JSONObject().apply {
                put("messages", JSONArray().apply {
                    // system 消息作为第一条
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("text", SYSTEM_PROMPT) })
                        })
                    })
                    // user 消息
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    })
                })
            })
            put("parameters", JSONObject().apply {
                put("enable_thinking", false)
                put("result_format", "message")
                put("max_tokens", 512)
                put("temperature", 0.3)
            })
        }
    }

    private fun buildLegacyRequest(
        imageBase64: String,
        sceneType: String,
        previousGuidance: String?
    ): JSONObject {
        val sceneContext = when (sceneType) {
            "portrait", "selfie" -> "当前场景是人像/自拍"
            "landscape" -> "当前场景是风景"
            "food" -> "当前场景是美食"
            "pet" -> "当前场景是宠物"
            "architecture" -> "当前场景是建筑"
            else -> "请自动判断场景类型"
        }

        val continuityNote = if (previousGuidance != null) {
            "\n上一轮指导: \"$previousGuidance\"\n如果场景没变，给更具体的调整。"
        } else ""

        val legacyPrompt = """
你是一位顶级摄影构图 AI 导师。用户正在用手机实时取景，分析画面给出专业构图指导。

$sceneContext$continuityNote

请以纯 JSON 回复，严格结构:
{"scene":"场景描述8字内","composition":"核心构图建议30字内","technical":"技术建议25字内","score":0-100整数,"good_shot":true/false,"adjustments":["最多3条"]}

score>80 可以拍了。中文回复。
        """.trimIndent()

        val contentArray = JSONArray().apply {
            put(JSONObject().apply { put("image", "data:image/jpeg;base64,$imageBase64") })
            put(JSONObject().apply { put("text", "请分析这张手机取景画面的构图。") })
        }

        return JSONObject().apply {
            put("model", config.modelName)
            put("input", JSONObject().apply {
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("text", legacyPrompt) })
                        })
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    })
                })
            })
            put("parameters", JSONObject().apply {
                put("enable_thinking", false)
                put("result_format", "message")
                put("max_tokens", 512)
                put("temperature", 0.4)
            })
        }
    }

    // ═══════════════════════════════════════════
    //  DashScope 原生响应解析
    // ═══════════════════════════════════════════

    /**
     * 解析 DashScope 原生响应格式:
     * {
     *   "output": {
     *     "choices": [{
     *       "message": {
     *         "role": "assistant",
     *         "content": [{"text": "...JSON..."}]
     *       }
     *     }]
     *   }
     * }
     *
     * @return content 中的文本，解析失败返回 null
     */
    private fun parseDashScopeResponse(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val output = json.optJSONObject("output") ?: return null
            val choices = output.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null

            val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
            val content = message.optJSONArray("content") ?: return null
            if (content.length() == 0) return null

            // content[0].text
            content.getJSONObject(0).optString("text", "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DashScope response: $responseBody", e)
            null
        }
    }

    private fun parseLegacyContent(text: String): AICompositionResult {
        return try {
            val jsonStr = text.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val data = JSONObject(jsonStr)

            AICompositionResult(
                sceneDescription = data.optString("scene", ""),
                compositionAdvice = data.optString("composition", ""),
                technicalAdvice = data.optString("technical", ""),
                moodAdvice = "",
                shootingTip = "",
                score = data.optInt("score", 50),
                isGoodShot = data.optBoolean("good_shot", false),
                keyAdjustments = data.optJSONArray("adjustments")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                rawResponse = text
            )
        } catch (e: Exception) {
            createErrorResult(text)
        }
    }

    private fun createErrorResult(error: String): AICompositionResult {
        return AICompositionResult("", "", "", "", "", 0, false, emptyList(), error)
    }
}
