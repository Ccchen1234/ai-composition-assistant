# Contributing to AI Composition Assistant

Thank you for your interest in contributing! We welcome all kinds of contributions.

## How to Contribute

### Report Issues
- Use GitHub Issues to report bugs
- Include device model, Android version, etc.
- Attach screenshots or screen recordings when possible

### Feature Requests
- Submit Feature Requests via Issues
- Describe the use case and expected outcome
- Discuss feasibility of implementation

### Code Contributions
1. Fork this repository
2. Create a feature branch `git checkout -b feature/your-feature`
3. Commit changes `git commit -m 'Add some feature'`
4. Push to branch `git push origin feature/your-feature`
5. Create a Pull Request

## Development Guide

### Requirements
- Android Studio Hedgehog (2023.1.1) or higher
- JDK 17
- Android SDK API 34

### Build
```bash
./gradlew assembleDebug
```

### Code Style
- Follow Kotlin coding conventions
- 4-space indentation
- camelCase for variables
- UPPER_SNAKE_CASE for constants

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| main | Stable releases |
| develop | Development version |
| feature/* | New features |

## License

This project is licensed under the MIT License.
