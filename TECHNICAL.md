# AI Composition Assistant - Technical Documentation

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        MainActivity                          │
│  (UI Shell - observes StateFlow, dispatches user actions)  │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   CompositionViewModel                       │
│  (Business Logic - processes frames, computes composition)   │
│  - Processes ML Kit detection results                       │
│  - Orchestrates AI analysis                                 │
│  - Manages auto-shutter state machine                      │
└──────────┬──────────────────┬──────────────────────────────┘
           │                  │
           ▼                  ▼
┌──────────────────┐  ┌──────────────────────────────────────┐
│  CameraManager   │  │        AIGuidanceManager             │
│  - CameraX       │  │  - QwenProvider (API calls)          │
│  - ML Kit        │  │  - CommandDispatcher                 │
│  - Frame capture │  │  - Result caching                    │
└──────────────────┘  └──────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────┐
│                  OverlayView (Canvas Drawing)               │
│  - Rule of thirds grid                                     │
│  - Composition boxes                                       │
│  - AI guide arrows                                         │
│  - Level indicator                                         │
└─────────────────────────────────────────────────────────────┘
```

## Module Specifications

### 1. CameraManager (`com.aicomp.camera`)

**Responsibilities:**
- Initialize CameraX camera lifecycle
- Configure ML Kit detectors (Object, Face, Pose)
- Capture preview frames and invoke callbacks
- Handle camera switch, flash, and focus

**Key Classes:**
| Class | Description |
|-------|-------------|
| `CameraManager` | Camera lifecycle and frame analysis orchestration |

**ML Kit Integration:**
```kotlin
// Three concurrent detectors (P0 bug fix: synchronized via CountDownLatch)
- ObjectDetector: Main subject detection (person, pet, food, building)
- FaceDetector: Face detection, smile/eye state
- PoseDetector: Body pose, shoulder tilt, head angle
```

**Bug Fixes Applied:**
- P0: Race condition in ML Kit callbacks → `CountDownLatch` + `AtomicReference` synchronization
- P0: `ImageEncodePool` thread safety → `@Synchronized` methods

---

### 2. CompositionViewModel (`com.aicomp.viewmodel`)

**Responsibilities:**
- Manage `CompositionUiState` as StateFlow
- Compute local composition score
- Detect scene type from ML Kit results
- Trigger AI analysis when needed
- Control auto-shutter state machine

**Key Methods:**
```kotlin
fun processFrame(
    detectedObjects: List<DetectionResult>,
    imageWidth: Int, imageHeight: Int,
    isFrontCamera: Boolean,
    frameBitmapProvider: () -> Bitmap?
)

fun computeCompositionScore(guidances: List<Guidance>): Int

fun detectCurrentSceneType(objects: List<DetectionResult>): String
```

**Score Calculation (P1 Fix):**
```kotlin
// confidence weighting + guidance count penalty
baseScore = 100 - (100 - confidence * 100) * 0.3
if (guidanceCount > 3) baseScore -= (guidanceCount - 3) * 5
```

---

### 3. CompositionRuleEngine (`com.aicomp`)

**Rule Priority System:**
| Priority | Category | Examples |
|----------|----------|----------|
| 11 | Error Prevention | Tilt detection, edge cutting, subject size |
| 10 | Global Basics | Headroom, gaze direction |
| 9 | Core Composition | Rule of thirds, center composition |
| 8 | Advanced | Diagonal, leading lines |
| 7 | Scene-Specific | Food (45°), portrait, pet (eye level), landscape, architecture, document |
| 6 | Combined | Multiple rules synergy |

**Scene Detection:**
| Scene | Detection Method | Special Rules |
|-------|------------------|---------------|
| Portrait | Face detected | Headroom, smile, chin angle |
| Food | Object label "food" | 45° angle, texture focus |
| Pet | Object label "cat/dog" | Eye-level shot |
| Landscape | Wide ratio, no faces | Horizon at 1/3, foreground |
| Architecture | Vertical lines | Low angle, vertical lines |
| Document | Paper-like ratio | Parallel alignment |

**Bug Fixes Applied:**
- P2: `selectMainSubject` indexOf=-1 → `takeIf { i >= 0 } ?: size`
- P2: `FACE_002` confidence reversed → `1f - faceSmilingProbability`
- P2: Architecture scene ruleId collision → `SCENE_007`

---

### 4. AIGuidanceManager (`com.aicomp.ai`)

**Responsibilities:**
- Coordinate AI analysis requests
- Manage request throttling (scene change: 1s, normal: 3s)
- Cache results to avoid redundant API calls
- Support manual and auto-trigger modes

**Request Flow:**
```
requestAnalysis()
  → Check throttle interval
  → Check device stability
  → Check composition score threshold (75)
  → Launch coroutine in provided scope
    → QwenProvider.analyzeCommand()
    → Validate scene hash (discard stale)
    → Callback onAICommand()
```

**P2 Fix Applied:**
- `CoroutineScope` injection → `ViewModelScope` passed from outside (prevents leak)

---

### 5. QwenProvider (`com.aicomp.ai`)

**Responsibilities:**
- HTTP client for Qwen3-VL-Flash API
- Image encoding and compression
- JSON request/response parsing
- Retry logic

**API Configuration:**
```kotlin
baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
model: "qwen3-vl-flash"
auth: Bearer {api_key}
image: base64, max 320px
```

**Retry Logic (P2 Fix):**
```kotlin
// Max 1 retry with 500ms delay
repeat(retries + 1) {
    try {
        return executeRequest(request)
    } catch (e: Exception) {
        if (it == retries) throw e
        delay(500)
    }
}
```

---

### 6. OverlayView (`com.aicomp`)

**Drawing Elements:**
| Element | Paint | Description |
|---------|-------|-------------|
| Grid | `gridPaint` | Rule of thirds, 0.8dp dark gray |
| Cross points | `crossPaint` | Small cross marks (4px) |
| Subject box | `subjectPaint` | 1dp dashed outline |
| Recommended box | `boxPaint` | Animated transition |
| Target point | `targetDotPaint` | Pulsating cyan dot |
| Guide arrows | `guideArrowPaint` | AI directional guidance |
| Level indicator | `levelBarPaint` | Tilt angle display |

**Neo-Brutalist Design Tokens:**
```kotlin
gridPaint: #2C2C2C, 0.8dp stroke
crossPaint: #3A3A3A, 1.2dp stroke
guideArrowPaint: #3A6060 (cold dark cyan)
targetDotPaint: #5AC8C8 (cold cyan accent)
```

---

### 7. AutoShutterEngine (`com.aicomp`)

**State Machine:**
```
IDLE → (trigger) → ARMED
ARMED → (stable + good score) → FIRING
FIRING → (captured) → IDLE
ARMED → (timeout 5s) → IDLE
```

**Sensitivity Levels:**
| Level | Stability Threshold | Score Threshold |
|-------|---------------------|-----------------|
| LOW | 1.5 | 75 |
| MEDIUM | 2.5 | 65 |
| HIGH | 4.0 | 50 |

---

### 8. Strategy System (`com.aicomp.strategy`)

**JSON Strategy Configuration:**
```json
{
  "version": "1.0",
  "scenes": {
    "portrait": {
      "composition": ["rule_of_thirds", "headroom"],
      "aiPrompt": "..."
    }
  },
  "composition_rules": {
    "rule_of_thirds": {
      "cross_points": [[0.33, 0.33], ...]
    }
  }
}
```

---

## Data Flow

### Frame Analysis Pipeline
```
Camera Frame
    │
    ▼
CameraManager.frameCallback
    │
    ├─► ML Kit Object Detection ──────────────┐
    ├─► ML Kit Face Detection ────────────────┤
    └─► ML Kit Pose Detection ────────────────┘
              │ (CountDownLatch sync)
              ▼
    CompositionRuleEngine.analyze()
              │
              ▼
    ┌─────────┴─────────┐
    │                   │
    ▼                   ▼
OverlayView          CompositionViewModel
(Canvas)             (StateFlow)
                          │
                          ▼
                   AIGuidanceManager
                   (if score < 75)
                          │
                          ▼
                   QwenProvider API
                          │
                          ▼
                   AICommand / AICompositionResult
```

---

## Dependencies

### Core
| Library | Version | Purpose |
|---------|---------|---------|
| CameraX | 1.3.0 | Camera abstraction |
| ML Kit Object Detection | 17.0.0 | Subject detection |
| ML Kit Face Detection | 16.1.5 | Face analysis |
| ML Kit Pose Detection | 17.0.4 | Body pose |
| Kotlin Coroutines | 1.7.3 | Async operations |
| Markwon | 4.6.2 | Markdown rendering |

### Build
| Plugin | Version |
|--------|---------|
| Android Gradle Plugin | 8.2.0 |
| Kotlin | 1.9.20 |

---

## Security

### API Key Storage
```kotlin
// EncryptedSharedPreferences with MasterKey
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val securePrefs = EncryptedSharedPreferences.create(
    context, "secure_prefs", masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### Not Exposed in Git
- `local.properties` (SDK path)
- `build/` directory
- `.gradle/` cache
- `.idea/` configuration
- `*.keystore` signing files

---

## Performance Considerations

1. **Frame Sampling**: Analysis runs every 3-5 frames
2. **Image Downscaling**: Preview scaled to max 320px before AI upload
3. **Result Caching**: Same scene hash returns cached AI result
4. **Throttle Control**: Scene change: 1s cooldown, normal: 3s cooldown
5. **Coroutine Scope**: AI operations tied to ViewModel lifecycle

---

## Future Optimization Opportunities

1. **Local ML Fallback**: Use on-device model when API unavailable
2. **Result Deduplication**: Skip analysis for visually similar frames
3. **GPU Acceleration**: RenderScript/GPU for image processing
4. **Preference Learning**: User behavior-based guidance weighting
