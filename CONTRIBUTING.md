# Contributing to Fud AI

Thanks for your interest in contributing! Fud AI is an open-source, "bring-your-own-key" calorie tracker. The repo is a monorepo:

- `ios/` — SwiftUI iOS app (shipping on the App Store, v3.6)
- `android/` — Kotlin + Jetpack Compose app (feature-parity port, v2.0.0)
- `web/` — marketing site at [fud-ai.app](https://fud-ai.app) (plain HTML/CSS, Vercel)

PRs, bug reports, and feature ideas for any of these are welcome.

## Getting Started (iOS)

1. Fork the repo
2. Clone your fork
3. Open `ios/calorietracker.xcodeproj` in Xcode (16+)
4. Build and run on a simulator or device running iOS 17.6 or later

Uses Swift Package Manager for RevenueCat purchases. For normal feature work, just Xcode and a valid Apple developer account are enough.

## Getting Started (Android)

1. Fork the repo
2. Clone your fork
3. Open `android/` in Android Studio (Narwhal or newer), let Gradle sync
4. Hit ▶ Run on a connected device or emulator (Android 8.0 / API 26+)

CLI alternative:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity
```

Android Studio is needed for the SDK + bundled JDK, but you can do all your day-to-day editing/building/installing from the terminal once it's installed.

## Getting Started (Web)

1. Fork the repo
2. Clone your fork
3. `cd web && python3 -m http.server 8000` (any static server works)
4. Open http://localhost:8000

The site is plain HTML/CSS — no build step, no framework, no dependencies. Deployed to Vercel from `web/`.

Marketing screenshots live in `web/assets/screenshots/` and are also used by the README. When replacing screenshots, update the whole set together so the website and README stay in sync.

## First-Run Setup (both platforms)

Go to **Settings → AI Provider** in the running app and paste an API key for any of the 13 supported providers (Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama for local, or any custom OpenAI-compatible endpoint). A free Gemini key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey) is the fastest way to get started. Keys are stored in iOS Keychain (iOS) or EncryptedSharedPreferences/AES-256 (Android) — never transmitted to us.

iOS can also use **Fud AI Plus** instead of BYOK. Local StoreKit testing uses `ios/calorietracker/Products.storekit` with weekly, monthly, and yearly products. The production Plus proxy needs `GEMINI_API_KEY` for food/Coach and `DEEPGRAM_API_KEY` for voice; default Plus limits are 30 food analyses, 20 speech transcriptions, 25 Coach messages, and 70 total successful Plus calls per day.

Barcode logging on iOS and Android uses Open Food Facts directly from the device and does not require an API key. If a packaged food is missing or incomplete there, the app should guide the user back to Nutrition Label scan instead of inventing values. Camera + Camera logging should preserve the same review/edit path as other AI food inputs.

> For a full architecture deep-dive (stores/repositories, services, widgets, HealthKit/Health Connect conventions, localization rules, R8 keep rules, gotchas), read [`CLAUDE.md`](CLAUDE.md) in the repo root. It's the source of truth for how the codebase is organized.

## Code Style (iOS)

- **SwiftUI** with `@Observable` (not `ObservableObject`)
- Environment injection via `.environment()` (not `.environmentObject()`)
- Main actor isolation is default (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`) — no manual `@MainActor` annotations needed
- Services are stateless structs with static methods (`GeminiService`, `ChatService`, `SpeechService`, etc.)
- Xcode auto-discovers files via `PBXFileSystemSynchronizedRootGroup` — **do not** edit `project.pbxproj` to register source files
- Every user-facing string must be localized in `ios/calorietracker/Localizable.xcstrings` across all 15 supported languages before commit
- All data persistence is local (`UserDefaults` + iOS Keychain). No Core Data, no iCloud, no CloudKit

## Code Style (Android)

- **Jetpack Compose** with manual DI via `FudAIApp.container` (`AppContainer`) — no Hilt
- Each screen has a `*ViewModel` exposing `StateFlow<UiState>`; UI collects via `collectAsState()`
- Repositories expose `Flow<T>` from DataStore; ViewModels `combine()` them into screen state
- Every user-facing string lives in `app/src/main/res/values/strings.xml` and **must** be translated into all 14 non-English locale files (`values-{ar,az,de,es,fr,hi,it,ja,ko,nl,pt-rBR,ro,ru,zh-rCN}/strings.xml`) before commit
- Model enums (`Gender`, `MealType`, `AIProvider`, etc.) expose `@get:StringRes val displayNameRes: Int` — no hardcoded `displayName: String` strings
- All data persistence is local (DataStore Preferences + EncryptedSharedPreferences). No Room, no Firebase, no cloud
- When touching the release build path: keep R8 keep rules in `app/proguard-rules.pro` for kotlinx.serialization, Glance, WorkManager+Room, Health Connect — these all crash production-only without explicit keeps

## Pull Requests

1. Create a branch from `main`
2. Keep changes focused — one feature or fix per PR
3. Test on a real device if possible (the Release config is intentional — it matches what users see)
4. Run the Codex review before opening the PR if you have it set up: `codex exec review --commit <SHA> --full-auto`
5. Address P1 and P2 findings. P3 is judgment-call
6. Write a clear PR description explaining the **why**, not just the **what**

## Reporting Issues

Open a bug at [github.com/apoorvdarshan/fud-ai/issues/new?labels=bug](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20) with:
- Steps to reproduce
- Expected vs actual behavior
- Device model + OS version (iPhone model + iOS version, or Android model + OS / OEM skin like OriginOS / One UI / HyperOS)
- Which AI provider you were using (if the bug is analysis-related)
- Screenshots or a short screen recording if relevant

For feature ideas, use [the enhancement label](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20).

## Adding an AI Provider

The app already supports 13 providers across 3 API dialects. Add it to **both clients** to keep parity:

**iOS:**
1. Add a case to `AIProvider` in `ios/calorietracker/Models/AIProvider.swift`
2. Set `baseURL`, `models`, `apiFormat`, `apiKeyPlaceholder`
3. **If `apiFormat == .openaiCompatible`** → done; `GeminiService` + `ChatService` route automatically
4. **Custom shape** → add a branch in both `GeminiService.callAI` and `ChatService.sendMessage`; keep the 1s/2s/4s exponential-backoff loop for 503/529/429

**Android:** mirror the same enum case in `android/.../models/AIProvider.kt` with matching `baseUrl`/`models`/`apiFormat`. Custom shapes need a new client in `services/ai/` and a branch in both `FoodAnalysisService` + `ChatService`. `RetryPolicy` already handles backoff — just route through it.

Include vision-capable model IDs since the app needs vision for food photo analysis.

## Adding a Speech-to-Text Provider

**iOS:** extend `SpeechProvider` in `ios/calorietracker/Models/SpeechProvider.swift`, add the handler in `SpeechService.transcribe`, and keep Fud AI Plus routed through Deepgram unless the Plus proxy contract changes. **Android:** extend `models/SpeechProvider.kt` and add a client in `services/speech/`. Follow the pattern from the existing providers (OpenAI, Groq, Deepgram, AssemblyAI).

## Localization

Both clients ship in 15 languages. Any new user-facing string must be translated before the PR lands.

**iOS:** Add to `ios/calorietracker/Localizable.xcstrings` (String Catalog) — Xcode auto-extracts new English strings on build with `SWIFT_EMIT_LOC_STRINGS = YES`, but leaves the other 14 columns empty. Fill them in.

**Android:** Add the key to `app/src/main/res/values/strings.xml`, then add the translated value to all 14 non-English `values-*/strings.xml` files. For batches of 10+ strings, spawn a translation agent per locale (the existing translations were sourced from the iOS catalog where keys matched, plus fresh translations for Android-specific strings — same workflow applies). Enums use `displayNameRes: Int` instead of `displayName: String` — see the existing `MealType` / `WeightGoal` for the pattern.

See the full localization workflow in [`CLAUDE.md`](CLAUDE.md).

## Contact

If you want to chat before opening a big PR, or you hit a wall and need help:

- **Email:** **apoorv@fud-ai.app** or **ad13dtu@gmail.com**
- **X (Twitter):** [@apoorvdarshan](https://x.com/apoorvdarshan)
- **Instagram:** [@fudai.app](https://www.instagram.com/fudai.app/)
- **GitHub Issues:** [github.com/apoorvdarshan/fud-ai/issues](https://github.com/apoorvdarshan/fud-ai/issues)

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
