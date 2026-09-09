# Contributing to Fud AI

Thanks for your interest in contributing! Fud AI is an open-source, "bring-your-own-key" calorie tracker. The repo is a monorepo:

- `ios/` — SwiftUI iOS app (current release candidate: v6.1 build 34)
- `android/` — Kotlin + Jetpack Compose app (current release candidate: v6.1 / versionCode 34)
- `web/` — marketing site at [fud-ai.app](https://fud-ai.app) (plain HTML/CSS, Cloudflare Workers Static Assets)

PRs, bug reports, and feature ideas for any of these are welcome.

## Getting Started (iOS)

1. Fork the repo
2. Clone your fork
3. Open `ios/calorietracker.xcodeproj` in Xcode (16+)
4. Build and run on a simulator or device running iOS 17.6 or later

For normal feature work, just Xcode and a valid Apple developer account are enough.

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

The site is plain HTML/CSS with a validation-only npm build step and no framework. It is deployed with Cloudflare Workers Static Assets from `web/` using `web/wrangler.toml`.

Marketing screenshots live in `web/assets/screenshots/` and are also used by the README. When replacing screenshots, update the whole set together so the website and README stay in sync.

## First-Run Setup (both platforms)

Go to **Settings → AI Access** in the running app. In BYOK mode, paste an API key for any of the 13 supported providers (Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama for local, or any custom OpenAI-compatible endpoint). A free Gemini key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey) is the fastest way to get started. Keys are stored in iOS Keychain (iOS) or EncryptedSharedPreferences/AES-256 (Android). Food-description analysis for text, voice-transcribed, and Siri food logs can use Apple Intelligence on-device only as the final fallback after BYOK provider/fallback attempts fail on supported iPhones. (The optional Fud AI Premium proxy from earlier versions has been discontinued — the app is BYOK-only.)

Barcode logging on iOS and Android uses Open Food Facts directly from the device and does not require an API key. If a packaged food is missing or incomplete there, the app should report that clearly and let the user photograph the packaging through Camera or Photos instead of inventing values. Camera and Photos both support up to 10 separate images with an optional note; keep that shared capture-review-analysis path and the same retry/edit flow on both platforms.

For a codebase overview, start with the Architecture and Source Layout sections in [`README.md`](README.md), then follow the platform-specific patterns already used beside the code you are changing.

## Code Style (iOS)

- **SwiftUI** with `@Observable` (not `ObservableObject`)
- Environment injection via `.environment()` (not `.environmentObject()`)
- Main actor isolation is default (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`) — no manual `@MainActor` annotations needed
- Services are stateless structs with static methods (`GeminiService`, `ChatService`, `SpeechService`, etc.)
- Xcode auto-discovers files via `PBXFileSystemSynchronizedRootGroup` — **do not** edit `project.pbxproj` to register source files
- Every user-facing string must be added to `ios/calorietracker/Localizable.xcstrings`; English is required, and touched translations should be updated wherever practical
- All data persistence is local (`UserDefaults` + iOS Keychain). No Core Data, no iCloud, no CloudKit
- Keep fasting sessions in their dedicated local store. Do not represent a skipped meal as a fast or write fasting records into nutrition/HealthKit paths
- Siri/App Intents live under `ios/calorietracker/AppIntents/`; phrase-help UI is opened from + → Describe Meal → Siri Phrases on iOS

## Code Style (Android)

- **Jetpack Compose** with manual DI via `FudAIApp.container` (`AppContainer`) — no Hilt
- Each screen has a `*ViewModel` exposing `StateFlow<UiState>`; UI collects via `collectAsState()`
- Repositories expose `Flow<T>` from DataStore; ViewModels `combine()` them into screen state
- Every user-facing string lives in `app/src/main/res/values/strings.xml`; English is required, and touched translations should be updated in the 14 non-English locale files (`values-{ar,az,de,es,fr,hi,it,ja,ko,nl,pt-rBR,ro,ru,zh-rCN}/strings.xml`) wherever practical
- Model enums (`Gender`, `MealType`, `AIProvider`, etc.) expose `@get:StringRes val displayNameRes: Int` — no hardcoded `displayName: String` strings
- All data persistence is local (DataStore Preferences + EncryptedSharedPreferences). No Room, no Firebase, no cloud
- Keep fasting sessions in their dedicated repository. Do not represent a skipped meal as a fast or write fasting records into nutrition/Health Connect paths
- When touching the release build path: keep R8 keep rules in `app/proguard-rules.pro` for kotlinx.serialization, Glance, WorkManager+Room, Health Connect — these all crash production-only without explicit keeps

## Pull Requests

1. Create a branch from `main`
2. Keep changes focused — one feature or fix per PR
3. Test on a real device if possible (the Release config is intentional — it matches what users see)
4. Run the Codex review before opening the PR if you have it set up: `codex exec review --commit <SHA> --full-auto`
5. Address P1 and P2 findings. P3 is judgment-call
6. Write a clear PR description explaining the **why**, not just the **what**

## Automated tests and release checks

Major new functionality must include automated tests for its behavior. Bug fixes
should include a regression test when practical. Cover successful inputs, invalid
inputs and failure paths; keep mobile behavior aligned across iOS and Android.
Explain any testing limitation in the pull request. Device checks complement the
automated suite rather than replacing it.

Run the checks relevant to your change before opening a pull request:

```bash
cd web
npm ci
npm run check
```

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease
```

The web suite includes fast-check generated-input tests for malformed JSON,
unknown fields and score validation. They run 1,000 generated cases per property
on each check; use the reported seed and shrink path to reproduce a failure.

Run affected iOS tests through Xcode's Test action on a supported simulator. Run
instrumented Android tests on an emulator or device for Android framework paths.
Do not uninstall an existing app or clear its data as part of routine validation.

The Quality checks workflow runs web tests, Android unit tests and Android lint
on pushes, pull requests and daily. Release workflows require these checks before
creating their release artifacts/entries. Before an App Store release through
Xcode Cloud, the maintainer must also verify a successful Quality checks run for
the exact release commit and run the affected iOS tests. Resolve lint errors;
review warnings and document any accepted limitations before release. Security
findings require explicit triage and remediation, not a blanket lint baseline.

## Release notes

Before creating a release tag, add a matching `## <tag>` section to
[`RELEASE_NOTES.md`](RELEASE_NOTES.md). Summarize user-visible changes, upgrade
impact and publicly known security fixes, including identifiers when available.
Do not substitute raw commit logs for release notes. Keep App Store and Google
Play copy aligned with this summary. The release workflows reject tags without
an exact reviewed note section.

## Reporting Issues

Open a bug at [github.com/apoorvdarshan/fud-ai/issues/new?labels=bug](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20) with:
- Steps to reproduce
- Expected vs actual behavior
- Device model + OS version (iPhone model + iOS version, or Android model + OS / OEM skin like OriginOS / One UI / HyperOS)
- Which AI provider you were using (if the bug is analysis-related)
- For Siri/App Intent bugs, the exact phrase used and whether the issue happened from Siri, Shortcuts, or the in-app phrase guide
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

**iOS:** extend `SpeechProvider` in `ios/calorietracker/Models/SpeechProvider.swift` and add the handler in `SpeechService.transcribe`. **Android:** extend `models/SpeechProvider.kt` and add a client in `services/speech/`. Follow the pattern from the existing providers (OpenAI, Groq, Deepgram, AssemblyAI).

## Localization

iOS ships 16 locale resources; Android ships 15. English is the complete fallback on both platforms, so a missing translation must never block rendering or produce an empty label. Update every affected locale when practical and call out intentional fallback copy in the PR.

**iOS:** Add to `ios/calorietracker/Localizable.xcstrings` (String Catalog) — Xcode auto-extracts new English strings on build with `SWIFT_EMIT_LOC_STRINGS = YES`, but leaves the other 15 columns empty. Fill the translations you are changing and verify fallback behavior for the rest.

**Android:** Add the key to `app/src/main/res/values/strings.xml`, then update the relevant non-English `values-*/strings.xml` files. Android intentionally falls back to the default English resource while locale updates are completed; release lint disables only `MissingTranslation` for that reason. Enums use `displayNameRes: Int` instead of `displayName: String` — see the existing `MealType` / `WeightGoal` for the pattern.

Before committing, build both platform resources so malformed translations and format-argument mismatches fail locally, then spot-check any locale files you touched.

## Contact

If you want to chat before opening a big PR, or you hit a wall and need help:

- **Email:** **apoorv@fud-ai.app** or **ad13dtu@gmail.com**
- **X (Twitter):** [@apoorvdarshan](https://x.com/apoorvdarshan)
- **GitHub Issues:** [github.com/apoorvdarshan/fud-ai/issues](https://github.com/apoorvdarshan/fud-ai/issues)

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
