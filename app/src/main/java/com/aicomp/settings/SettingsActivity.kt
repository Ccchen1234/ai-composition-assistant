package com.aicomp.settings

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.aicomp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设置界面 — 千问 Qwen3-VL-Flash 专用
 */
class SettingsActivity : AppCompatActivity() {

    private val testScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.hide()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<View>(R.id.btnTestConnectivity)?.setOnClickListener {
            runConnectivityTest()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ═══════════════════════════════════════════
    //  连通性测试
    // ═══════════════════════════════════════════

    private fun runConnectivityTest() {
        val config = ConfigManager.apiConfig

        if (config.apiKey.isBlank()) {
            showTestResult(false, "配置不完整", "请先填写 DashScope API Key", 0)
            return
        }

        val testBtn = findViewById<View>(R.id.btnTestConnectivity)
        testBtn?.isEnabled = false
        testBtn?.alpha = 0.5f

        testScope.launch {
            val result = withContext(Dispatchers.IO) {
                doApiTest(config.apiKey, config.baseUrl, config.modelName)
            }
            testBtn?.isEnabled = true
            testBtn?.alpha = 1.0f
            showTestResult(result.success, if (result.success) "连通性测试通过" else "连通性测试失败", result.detail, result.latencyMs)
        }
    }

    private data class TestResult(val success: Boolean, val detail: String, val latencyMs: Long)
    private fun doApiTest(apiKey: String, baseUrl: String, modelName: String): TestResult {
        return try {
            // DashScope 原生端点
            val apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
            Log.d("ApiTest", "Request URL: $apiUrl")
            Log.d("ApiTest", "Model: $modelName, Key: ${apiKey.take(6)}...")

            // DashScope 原生请求格式
            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("input", JSONObject().apply {
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply { put("text", "ping，请回复 OK") })
                            })
                        })
                    })
                })
                put("parameters", JSONObject().apply {
                    put("enable_thinking", false)
                    put("result_format", "message")
                    put("max_tokens", 10)
                    put("temperature", 0)
                })
            }

            val startTime = System.currentTimeMillis()
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
            }
            conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val latencyMs = System.currentTimeMillis() - startTime
            val code = conn.responseCode

            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)

                // DashScope 原生响应: output.choices[0].message.content[0].text
                val output = json.optJSONObject("output")
                val choices = output?.optJSONArray("choices")
                val reply = if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    val content = msg?.optJSONArray("content")
                    if (content != null && content.length() > 0) {
                        content.getJSONObject(0).optString("text", "")
                    } else ""
                } else ""

                val tokens = json.optJSONObject("usage")?.optInt("total_tokens", -1) ?: -1

                TestResult(true, buildString {
                    appendLine("模型: $modelName")
                    if (reply.isNotBlank()) appendLine("回复: $reply")
                    if (tokens > 0) appendLine("Token: $tokens")
                    appendLine("响应: ${latencyMs}ms")
                }, latencyMs)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "无详情"
                val detail = buildString {
                    appendLine("HTTP $code")
                    appendLine("请求地址: $apiUrl")
                    appendLine()
                    appendLine(err)
                }
                TestResult(false, detail, latencyMs)
            }
        } catch (e: java.net.SocketTimeoutException) {
            TestResult(false, "连接超时 (30s)\n请检查网络", 30000)
        } catch (e: java.net.UnknownHostException) {
            TestResult(false, "DNS 解析失败\n无法连接到 $baseUrl", 0)
        } catch (e: Exception) {
            TestResult(false, "${e.javaClass.simpleName}: ${e.message}", 0)
        }
    }

    private fun showTestResult(success: Boolean, title: String, detail: String, latencyMs: Long) {
        val icon = if (success) "✅" else "❌"
        val warn = if (latencyMs > 5000) "\n\n⚠️ 响应较慢" else ""
        AlertDialog.Builder(this).setTitle("$icon $title").setMessage("$detail$warn").setPositiveButton("确定", null).show()
    }

    // ═══════════════════════════════════════════
    //  Settings Fragment
    // ═══════════════════════════════════════════

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            ConfigManager.init(requireContext())

            // API Key 安全存储拦截
            findPreference<androidx.preference.EditTextPreference>("openai_api_key")?.let { pref ->
                val existingKey = ConfigManager.apiKey
                pref.text = if (existingKey.isNotBlank()) maskApiKey(existingKey) else ""

                pref.setOnPreferenceChangeListener { _, newValue ->
                    val key = (newValue as? String)?.trim() ?: ""
                    if (key.isNotBlank() && key != maskApiKey(ConfigManager.apiKey)) {
                        ConfigManager.saveApiKey(key)
                        pref.text = maskApiKey(key)
                        Log.d("Settings", "API Key saved to encrypted storage")
                    } else if (key.isBlank()) {
                        ConfigManager.saveApiKey("")
                        pref.text = ""
                    }
                    false
                }
            }

            // Base URL 保存
            findPreference<androidx.preference.EditTextPreference>("openai_base_url")?.let { pref ->
                pref.text = ConfigManager.baseUrl
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val url = (newValue as? String)?.trim() ?: ""
                    ConfigManager.saveBaseUrl(url)
                    pref.text = url
                    false
                }
            }

            // Model 保存
            findPreference<androidx.preference.EditTextPreference>("openai_model")?.let { pref ->
                pref.text = ConfigManager.modelName
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val model = (newValue as? String)?.trim() ?: ""
                    ConfigManager.saveModelName(model)
                    pref.text = model
                    false
                }
            }

            // 策略 URL 保存
            findPreference<androidx.preference.EditTextPreference>("strategy_url")?.let { pref ->
                pref.text = ConfigManager.strategyUrl
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val url = (newValue as? String)?.trim() ?: ""
                    ConfigManager.saveStrategyUrl(url)
                    pref.text = url
                    false
                }
            }
        }

        private fun maskApiKey(key: String): String {
            if (key.length <= 8) return "********"
            return "${key.take(4)}${"*".repeat(key.length - 8)}${key.takeLast(4)}"
        }
    }
}
