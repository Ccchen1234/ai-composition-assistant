# AI Composition Assistant - User Guide

## Overview

AI Composition Assistant helps you capture better photos by providing real-time composition guidance. It analyzes your camera view and suggests optimal framing based on professional photography rules.

## Getting Started

### First Launch

1. **Grant Camera Permission** when prompted
2. **Configure AI (Optional)**: Go to Settings → Enter your DashScope API Key
   - Get your API key from [Alibaba Cloud DashScope](https://dashscope.console.aliyun.com/)
   - Without API key, the app still works with local ML Kit analysis

### Basic Interface

```
┌────────────────────────────────────────┐
│                                        │
│    ┌──────────────────────────┐        │
│    │   Composition Hint       │        │
│    └──────────────────────────┘        │
│                                        │
│            Rule of Thirds Grid         │
│           (toggle with 👁)             │
│                                        │
│                                        │
│  ⚡      [   ◉   ]          🔄        │
│ Flash   Capture      Switch Camera     │
│                                        │
│  ┌────────────────────────────┐        │
│  │ AI Score: 85  Good!        │        │
│  │ Composition advice...      │        │
│  └────────────────────────────┘        │
└────────────────────────────────────────┘
```

## Features

### 1. Smart Composition Grid

**Rule of Thirds**: The most fundamental composition technique
- Display: 2 horizontal + 2 vertical lines dividing the frame into 9 parts
- Key intersection points are marked with small crosses
- Position your subject near these intersections for visually pleasing photos

**Toggle**: Tap the 👁 icon in bottom bar

---

### 2. Composition Scoring (0-100)

The app calculates a composition score based on:
- Subject position relative to grid lines
- Headroom and breathing space
- Horizon levelness
- Subject size appropriateness

**Score Colors:**
| Score | Color | Meaning |
|-------|-------|---------|
| 80+ | Dark Mint | Excellent composition |
| 60-79 | Dark Amber | Good, minor adjustments |
| <60 | Dark Coral | Needs improvement |

---

### 3. AI Guidance (Pro Feature)

When enabled, AI provides:

#### Composition Advice
- "Place subject at right intersection"
- "Increase headroom"
- "Level the horizon"

#### Technical Tips
- Camera angle suggestions
- Focus point recommendations
- Lighting considerations

#### Scene-Specific Guidance
| Scene | AI Suggestion |
|-------|---------------|
| Portrait | Chin down slightly, shoulders relaxed |
| Food | 45° angle, focus on texture |
| Pet | Get eye-level with the animal |
| Landscape | Horizon at 1/3 line |
| Architecture | Low angle, keep lines vertical |

**Manual Trigger**: Tap the "AI" button to get instant analysis

---

### 4. Auto Shutter

The app can automatically capture when composition is perfect:

1. **Arm**: Tap the ⏱ button to enable
2. **Wait**: Hold your phone steady
3. **Capture**: Photo is taken automatically when:
   - Device is stable
   - Composition score meets threshold
   - Stability maintained for ~2 seconds

**Sensitivity Levels:**
| Level | Stability Needed | Score Threshold |
|-------|------------------|-----------------|
| Low | Very steady | 75 |
| Medium | Normal steady | 65 |
| High | Some movement OK | 50 |

---

### 5. Scene Detection

The app automatically detects what you're photographing:

| Scene | Icon | Special Features |
|-------|------|------------------|
| Portrait | 👤 | Face focus, smile detection |
| Food | 🍜 | 45° angle recommendation |
| Pet | 🐾 | Eye-level guidance |
| Landscape | 🌄 | Horizon alignment |
| Architecture | 🏢 | Vertical line detection |
| Document | 📄 | Parallel alignment |

---

### 6. Direction Arrows

When your subject is off-center, arrows guide you:

- **→**: Move right
- **←**: Move left
- **↓**: Move down
- **↑**: Move up

Arrow size indicates how much adjustment is needed.

---

### 7. Tilt Indicator

The level indicator shows:
- **Cyan bubble**: Device is level
- **Gray bubble**: Tilted (angle displayed in degrees)
- Only appears when tilt > 1.5°

---

### 8. Camera Controls

| Button | Function |
|--------|----------|
| ⚡ | Toggle flash |
| 🔄 | Switch front/rear camera |
| 👁 | Toggle composition grid |
| ⏱ | Toggle auto shutter |
| 🎯 | Manual focus (tap viewfinder) |
| ⚙️ | Open settings |

---

## Settings

### AI Configuration
- **API Key**: Your DashScope API key
- **Base URL**: API endpoint (default: DashScope)
- **Model**: AI model selection
- **Enable AI Guidance**: Toggle AI features

### Auto Shutter
- **Sensitivity**: Low / Medium / High
- **Enable Haptic**: Vibration feedback

### Display
- **Show Grid**: Default grid visibility
- **Show Level**: Tilt indicator visibility

---

## Tips & Tricks

### Best Results
1. **Good lighting** improves ML detection accuracy
2. **Single subject** allows clearer guidance
3. **Steady hands** enable auto shutter to work

### Common Issues

| Issue | Solution |
|-------|----------|
| No AI response | Check API key in Settings |
| Slow analysis | Normal - AI takes 2-3 seconds |
| Wrong scene | Tap AI button for manual detection |
| Auto shutter not firing | Increase sensitivity or lower score threshold |

---

## Version History

### v1.0 - Neo-Brutalist Redesign
- New minimalist dark interface
- AI composition guidance
- Auto shutter with sensitivity levels
- Real-time scoring system
