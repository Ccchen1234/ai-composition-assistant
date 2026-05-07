# AI Composition Assistant

An Android camera assistant app focused on intelligent composition guidance, helping users capture more aesthetically pleasing photos.

## Features

### Smart Composition Guidance
- **Rule of Thirds Grid**: Toggle with a tap to assist golden ratio composition
- **Real-time Guide Arrows**: AI detects subject position, dynamic arrows point to optimal composition
- **AI Scoring System**: Real-time composition quality assessment (0-100 score)

### Camera Controls
- **Flash Control**: Support for flash on/off
- **Camera Switch**: Quick switch between front/rear cameras
- **Auto Mode**: Intelligent scene recognition with composition recommendations

### Minimal Interface
- Inspired by Leica camera UI design language
- De-decorated, focused on photography itself
- Cold gray primary palette, only accent color #5AC8C8 (cold cyan)
- Night-friendly, non-intrusive to the viewfinder

## Preview

```
┌─────────────────────────────┐
│                             │
│    ┌───────────────────┐    │
│    │  Composition Tip  │    │
│    └───────────────────┘    │
│                             │
│         Guide Arrow ↓        │
│                             │
│  ⚡  [      ◉      ]  🔄    │
│      Capture    Switch       │
│                             │
│  ┌─────────────────────┐    │
│  │  Score: 85  Good!   │    │
│  └─────────────────────┘    │
└─────────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|------------|
| Platform | Android |
| Language | Kotlin |
| Min SDK | API 24+ |
| Camera | CameraX |
| AI | Local ML (ready) |

## Project Structure

```
app/
├── src/main/
│   ├── java/com/ai/composition/
│   │   ├── MainActivity.kt          # Main activity
│   │   ├── OverlayView.kt           # Composition overlay
│   │   └── ...
│   └── res/
│       ├── layout/
│       │   └── activity_main.xml    # Main layout
│       ├── drawable/                # UI resources
│       └── values/
│           ├── colors.xml           # Color scheme
│           └── theme.xml            # Theme definition
```

## Design Spec

### Color Palette

| Name | Hex | Usage |
|------|-----|-------|
| neo_bg | #0D0D0D | Main background (near black) |
| neo_accent | #5AC8C8 | Only accent color (cold cyan) |
| neo_text_primary | #F0F0F0 | Primary text |
| neo_text_secondary | #707070 | Secondary text |
| neo_hint | #4A4A4A | Hint text |

### UI Principles

1. **Minimal & Restrained**: Don't compete with the camera view
2. **Low Opacity**: UI elements at CC-66 opacity, non-intrusive
3. **Fine Lines**: Borders at 0.5-1.5dp for lightness
4. **Glass Effect**: Semi-transparent cards with fine borders

## Version

### v1.0 - Neo-Brutalist Redesign
- ✅ Neo-Brutalist minimalist UI redesign
- ✅ Unified dark theme
- ✅ Minimal composition guide animations
- ✅ Restrained AI score display

---

*Focus on composition, return to the essence of photography.*
