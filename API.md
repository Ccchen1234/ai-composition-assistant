# API Documentation

## AI Provider Interface

### AIProvider Interface

```kotlin
interface AIProvider {
    fun isAvailable(): Boolean
    fun getProviderName(): String
    suspend fun analyzeCommand(
        frame: Bitmap,
        sceneType: String,
        previousGuide: String?
    ): AICommand
}
```

### AICommand Response Format

```json
{
  "direction": {
    "dx": 0.35,
    "dy": -0.15
  },
  "compositionAdvice": "Place the subject at the **right intersection** point for better visual balance.",
  "sceneDescription": "Portrait with one person, natural indoor lighting",
  "technicalAdvice": "• Focus on the eyes\n• Use soft side lighting\n• Chin slightly down",
  "keyAdjustments": [
    "Move right by 15% of frame width",
    "Add 10% more headroom"
  ],
  "shootingTip": "Wait for subject to smile naturally before capturing"
}
```

### QwenProvider Configuration

```kotlin
data class APIConfig(
    val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val modelName: String = "qwen3-vl-flash",
    val apiKey: String
)
```

**Endpoint**: `POST /chat/completions`

**Headers**:
```
Authorization: Bearer {api_key}
Content-Type: application/json
```

**Request Body**:
```json
{
  "model": "qwen3-vl-flash",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,{image}"}},
        {"type": "text", "text": "System prompt..."}
      ]
    }
  ],
  "max_tokens": 512
}
```

---

## Composition Result Types

### DetectionResult

```kotlin
data class DetectionResult(
    val centerX: Float,      // 0.0-1.0, origin top-left
    val centerY: Float,      // 0.0-1.0, origin top-left
    val width: Float,        // Relative to image width
    val height: Float,       // Relative to image height
    val aspectRatio: Float,  // width/height
    val areaRatio: Float,    // Subject area / Image area
    val category: String,    // face/person/pet/food/building/landscape/other
    val label: String,       // ML Kit label
    val faceDetected: Boolean = false,
    val faceSmilingProbability: Float = 0f,
    val faceEulerY: Float = 0f,        // Head tilt angle
    val poseDetected: Boolean = false,
    val shoulderTilt: Float = 0f,
    val bodyAngle: Float = 0f
)
```

### Guidance

```kotlin
data class Guidance(
    val message: String,      // User-facing message
    val ruleId: String,      // Internal rule identifier
    val priority: Int,       // 6-11, higher = more important
    val confidence: Float,    // 0.0-1.0
    val boxRuleId: String?,  // Associated box rule
    val boxStyle: String,    // primary/secondary/error
    val tipText: String?     // Short tip
)
```

### CompositionResult

```kotlin
data class CompositionResult(
    val guidances: List<Guidance>,
    val recommendedBox: CompositionBox?,
    val arrowX: Float,       // -1 to 1, >0 = move right
    val arrowY: Float,       // -1 to 1, >0 = move down
    val tiltAngle: Float,    // Device tilt in degrees
    val isPerfect: Boolean    // True if no guidance needed
)
```

---

## Internal APIs

### CompositionRuleEngine

```kotlin
object CompositionRuleEngine {
    fun analyze(
        subjects: List<DetectionResult>,
        imageWidth: Int,
        imageHeight: Int
    ): CompositionResult
}
```

### AIGuidanceManager

```kotlin
class AIGuidanceManager(scope: CoroutineScope) {
    fun initFromConfig()
    fun refreshConfig()
    fun isEnabled(): Boolean
    fun getProviderName(): String
    
    fun requestAnalysis(
        frame: Bitmap,
        sceneType: String,
        sceneHash: String,
        isDeviceStable: Boolean,
        compositionScore: Int,
        sceneChanged: Boolean
    ): Boolean
    
    fun getLastResult(): AICompositionResult?
    fun getLastCommand(): AICommand?
    fun reset()
}
```

### CompositionBoxEngine

```kotlin
data class CompositionBox(
    val x1: Float,  // Normalized 0-1
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: String,
    val style: String
)

object CompositionBoxEngine {
    fun calculateBox(
        ruleId: String,
        compositionType: String,
        centerX: Float,
        centerY: Float,
        subjectWidth: Float,
        subjectHeight: Float,
        screenAspect: Float
    ): CompositionBox
}
```

---

## Preferences Keys

```kotlin
// ConfigManager Keys
const val KEY_BASE_URL = "openai_base_url"
const val KEY_MODEL_NAME = "openai_model"
const val SECURE_KEY_API_KEY = "secure_api_key"
const val KEY_AI_GUIDANCE = "ai_guidance_enabled"
const val KEY_GRID_DEFAULT = "grid_default_on"
const val KEY_HAPTIC_ENABLED = "haptic_feedback_enabled"
```

---

## Error Codes

| Code | Meaning |
|------|---------|
| `ERROR_001` | Device tilt detected |
| `ERROR_002` | Subject cut off at edge |
| `ERROR_003` | Insufficient gaze direction space |
| `ERROR_004` | Subject too small/large |
| `FACE_001` | Eyes closed |
| `FACE_002` | Not smiling |
| `CORE_001` | Rule of thirds adjustment needed |
| `CORE_002` | Center alignment needed |
| `SCENE_001` | Food scene - angle suggestion |
| `SCENE_003` | Portrait - pose tip |
| `SCENE_004` | Environmental portrait |
| `SCENE_005` | Pet scene - eye level |
| `SCENE_006` | Landscape - horizon |
| `SCENE_007` | Architecture - angle |
| `SCENE_008` | Document - alignment |
