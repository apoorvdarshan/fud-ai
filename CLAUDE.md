# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Fud AI is an open-source calorie tracker. The iOS client (SwiftUI, iOS 17.6+) lives in `ios/` — shipping on the App Store at v3.1. The Android client (Kotlin + Jetpack Compose, min SDK 26) lives in `android/` — feature-parity port, v1.0.0, ready for Play Store submission. The marketing website (plain HTML/CSS, deployed to Vercel at https://fud-ai.app) lives in `web/`. Both clients work the same way: snap/speak/type a meal, an AI provider returns nutrition JSON, the user reviews it, and it lands in the food store + Apple Health (iOS) or Health Connect (Android). There's also a "Coach" tab — multi-turn AI chat that sees the user's full profile, weight history, and food log and answers questions like "what's my expected weight in 30 days?". Bring-your-own-key model; all data is local. No subscriptions, no sign-in, no cloud sync.

## Repo Layout

```
fud-ai/
├── ios/          ← iOS app (SwiftUI, Xcode project)
├── android/      ← Android app (Kotlin + Jetpack Compose)
├── web/          ← Marketing site (index.html, styles.css, privacy/terms, sitemap)
├── APPSTORE.md   ← App Store Connect listing copy (iOS)
├── PLAYSTORE.md  ← Play Console listing copy (Android)
└── …root-level meta (README, LICENSE, CONTRIBUTING, SECURITY, CLAUDE.md, .github/)
```

## Build, Install, Launch (iOS)

The app is tested on Apoorv's physical iPhone (iPhone 16, device ID `E2095CDC-E117-527C-818A-9F741A145103`). After every change run all three commands. The Release config is intentional — it matches what users actually see. `-derivedDataPath ios/build` keeps the build output at a known location so the install path doesn't depend on Xcode's hashed DerivedData folder.

```bash
# Build
xcodebuild -project ios/calorietracker.xcodeproj -scheme calorietracker \
  -destination 'id=E2095CDC-E117-527C-818A-9F741A145103' \
  -derivedDataPath ios/build build

# Install
xcrun devicectl device install app --device E2095CDC-E117-527C-818A-9F741A145103 \
  ios/build/Build/Products/Release-iphoneos/calorietracker.app

# Launch
xcrun devicectl device process launch --device E2095CDC-E117-527C-818A-9F741A145103 com.apoorvdarshan.calorietracker

# Pass --reset-onboarding to test the onboarding flow:
xcrun devicectl device process launch --device E2095CDC-E117-527C-818A-9F741A145103 com.apoorvdarshan.calorietracker -- --reset-onboarding
```

## Tests (iOS)

`calorietrackerTests` and `calorietrackerUITests` targets exist but only contain Xcode boilerplate — there are no real tests. Verify behavior by hand on device. If you do add tests, run them with:

```bash
xcodebuild test -project ios/calorietracker.xcodeproj -scheme calorietracker \
  -destination 'id=E2095CDC-E117-527C-818A-9F741A145103'
```

## Code Review

Use Codex CLI before each PR / after each commit cluster:

```bash
codex exec review --commit <SHA> --full-auto
```

Address P1 and P2 findings. P3 is judgment-call.

## Architecture (iOS)

### State / Dependency Injection

All stores use Swift's `@Observable` macro (not `ObservableObject`) and are injected with `.environment(...)` (not `.environmentObject(...)`). Created once in `calorietrackerApp.swift` and shared:

- `FoodStore` — food entries, favorites, macro aggregates
- `WeightStore` — weight entries; `addEntry` auto-syncs `profile.weightKg` to latest
- `ProfileStore` — **source of truth for `UserProfile`**. All reads/writes go through `profileStore.profile`. It listens for `.userProfileDidChange` and reloads so external writers (WeightStore, HealthKit observer) propagate to every view.
- `ChatStore` — Coach conversation history (persisted in UserDefaults as JSON, capped at last 20 messages in LLM payload)
- `NotificationManager`, `HealthKitManager`

Build setting `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` means most types are main-actor isolated by default. New files are auto-discovered via `PBXFileSystemSynchronizedRootGroup` — **do not** edit `project.pbxproj` to register source files. (The `knownRegions` entry in pbxproj *is* edited when adding a new localization.)

### AI / LLM Routing (13 providers, 3 formats)

Two services, both route to the same 13 providers via `AIProvider.apiFormat`:

- **`GeminiService`** (`Services/GeminiService.swift`) — single-shot food/label analysis. Methods: `analyzeFood`, `analyzeTextInput`, `autoAnalyze`, `analyzeNutritionLabel`. All funnel through `callAI`.
- **`ChatService`** (`Services/ChatService.swift`) — multi-turn Coach chat. Builds a fresh system prompt every turn from the live profile + forecast + recent weights/foods, sends history + new user message.

The three API dialects are:
- **Gemini** (`.gemini`): `POST /models/{model}:generateContent` with `systemInstruction` + `contents[{role, parts}]`. API key goes in `X-goog-api-key` header, not the URL.
- **Anthropic Messages** (`.anthropic`): `POST /messages` with `system` + `messages` array, `x-api-key` header + `anthropic-version: 2023-06-01`.
- **OpenAI-compatible** (`.openaiCompatible`): `POST /chat/completions` with `messages` array (system + user/assistant). Used by OpenAI, xAI Grok, OpenRouter, Together AI, Groq, **Hugging Face** (router for open-weight models — Gemma, Qwen VL, Llama Vision), **Fireworks AI**, **DeepInfra** (open-weight hosts), **Mistral** (Pixtral vision), Ollama (local), and the **Custom (OpenAI-compatible)** provider where the user supplies their own base URL + free-form model name. OpenRouter and Hugging Face both set `supportsCustomModelName = true` so users can type any model ID alongside the preset list.

Adding a provider: add a case to `AIProvider` in `Models/AIProvider.swift`, set `baseURL`/`models`/`apiFormat`/`apiKeyPlaceholder`. If `apiFormat` is `.openaiCompatible` it works automatically; otherwise add a branch in both `GeminiService.callAI` and `ChatService.sendMessage`.

Transient 503/529/429 responses auto-retry with 1s/2s/4s backoff before surfacing the error — so "model overloaded" spikes usually resolve invisibly. This applies to **both** services (`GeminiService.makeRequest` and `ChatService.send`); if you add a third LLM entry point, port the same loop. On final failure, both services convert status codes to user-friendly copy ("The AI provider is overloaded right now. We retried a few times…") — don't surface raw `HTTP 503` / provider JSON messages.

### Speech-to-Text Routing (5 providers)

`VoiceInputView` branches on `SpeechSettings.selectedProvider`:
- **Native iOS** — `SFSpeechRecognizer` live streaming with partial results. One-tap: tap Analyze to stop + submit.
- **Remote** (OpenAI Whisper / Groq / Deepgram / AssemblyAI) — `AVAudioRecorder` writes 16 kHz mono AAC to a temp m4a, uploads on stop, `SpeechService.transcribe` returns final text. Two-tap flow so user can review the transcription before Analyze.

OpenAI + Groq share `/v1/audio/transcriptions` (multipart). Deepgram takes raw audio body with `Token <key>` auth. AssemblyAI is a 3-step flow: upload → submit → poll every 1s up to 60s.

### Coach chat (`ChatView` + `ChatStore` + `ChatService`)

- 5th tab in `ContentView` TabView (Home / Progress / Coach / Settings / About).
- `ChatStore` persists the full conversation in UserDefaults. `contextMessages()` returns the last 20 for the LLM payload (token-cost cap); the full history stays visible locally regardless.
- `ChatService.buildSystemPrompt` includes: profile (gender/age/height/weight/activity/goal + body fat if set), **which BMR formula is active** (Katch-McArdle if body fat known, else Mifflin-St Jeor), BMR/TDEE numbers, macro targets, `WeightAnalysisService.compute` output (predicted/observed trends, 30/60/90-day weight, days-to-goal, under-logging flag), last 10 weight entries, last 7 days of daily calorie totals.
- Goal-aware prompt chips — `ChatView.promptChips` returns a different set for Lose / Gain / Maintain.

### Weight forecast math (`WeightAnalysisService`)

Pure function. Uses up to 90 days of available data (auto-scales to however much the user actually has). Returns a `WeightForecast` with:
- `predictedWeeklyChangeKg` — from energy balance vs TDEE (7700 kcal ≈ 1 kg)
- `observedWeeklyChangeKg` — linear regression on weight entries in window (nil if <2 entries)
- 30/60/90-day predictions, `daysToGoal` if direction matches, `trendsDisagree` flag when predicted and observed differ by >0.3 kg/week

Used exclusively as context for `ChatService` — no standalone UI card (there used to be one; it was removed in favor of the Coach tab).

### FoodStore → HealthKit callbacks

`FoodStore` exposes four hooks that `calorietrackerApp.wireUpHealthKit()` wires to `HealthKitManager`:
- `onEntryAdded` → `writeNutrition(for:)` (immediate, synchronous)
- `onEntryDeleted` → `deleteNutrition(entryID:)`
- `onEntryUpdated` → `updateNutrition(for:)` (delete-then-write, awaited so they don't race)
- `onEntriesChanged` → notification rescheduling

Edits use `onEntryUpdated` rather than back-to-back delete+add so HealthKit can serialize the two operations atomically.

### WeightStore → HealthKit callbacks

- `onEntryAdded` → `writeWeight(for:)` — tags each HK sample with `fudai_weight_id = entry.id.uuidString`
- `onEntryDeleted` → `deleteWeight(entryID:)` — deletes by metadata predicate, bypasses the `healthKitEnabled` flag

`WeightStore.addEntry` also detects goal-weight crossings (previous-on-wrong-side → new-on-correct-side) and posts `.weightGoalReached` — the Progress tab listens and shows "Congratulations!".

**`WeightStore.init()` does NOT seed a starter entry.** An earlier version did, falling back to `UserProfile.default` (70 kg) when the store initialized before onboarding finished — that dropped a phantom 70 kg entry onto every fresh user's chart regardless of their real starting weight. The seed now runs from `calorietrackerApp.onChange(of: hasCompletedOnboarding)` via `weightStore.seedInitialWeightFromProfileIfEmpty(profile.weightKg)` once the profile is real. The method is idempotent (no-ops when `entries` is non-empty). Don't reintroduce an init-time seed.

`HealthKitManager.writeWeight(kg:date:)` (the profile-state push used by onboarding/Settings, not the per-entry variant) also tags samples with a synthetic `fudai_weight_id = UUID().uuidString`. Untagged samples are invisible to `deleteWeight(entryID:)`'s metadata predicate.

### HealthKit Conventions

`HealthKitManager` (`Stores/HealthKitManager.swift`) is the only HealthKit boundary.

- **`typesVersion`** (renamed from the old `authVersion` to dodge a CodeQL heuristic on "auth" keywords) is bumped when new HealthKit types are added. `needsReauthorization` returns `max(typesVersionKey, legacy healthKitAuthVersion)` < current so existing users aren't re-prompted after the rename.
- `requestAuthorization` only persists the new version via `persistCurrentTypesVersion()` when **all** dietary share types are `.sharingAuthorized`, so users who deny nutrition can re-prompt.
- Each nutrition sample carries `fudai_entry_id` metadata; each weight sample carries `fudai_weight_id`. Deletion uses metadata predicates.
- `writeNutrition` guards on `healthKitEnabled`. `deleteNutrition` and the delete half of `updateNutrition` always run (even with sync off) so in-app edits/deletes still clean up samples that were exported before the user flipped sync off — otherwise those would orphan in Apple Health forever.
- `backfillNutritionIfNeeded` is idempotent (queries Apple Health for each entry's UUID before writing) and is guarded by `isBackfillingNutrition` so scene-phase re-entry can't spawn overlapping scans. The caller passes `currentEntryIDs: () -> Set<UUID>` so a meal deleted mid-backfill won't be re-exported as a phantom sample.
- The body-measurements observer skips adding weights whose sample metadata contains `fudai_weight_id` — those are our own writes and are handled by `WeightStore.addEntry` directly. External samples (Apple Watch, scale, Health app entry) go through the observer's date+value dedup and get added to `WeightStore` with the sample's real date.
- `startBodyMeasurementObserver()` calls `stopObserver()` **first** before registering the three `HKObserverQuery` instances. `wireUpHealthKit()` runs on every scene-active, so without the tear-down the `observerQueries` array kept growing and one HK change would fire the callback N times per session.

Clear Food Log keeps Apple Health samples (per product spec — only saves storage). **Delete All Data is local-only too**: it wipes in-memory stores, UserDefaults, Keychain API keys, the Coach chat history, and the widget App Group snapshot — but intentionally does NOT touch Apple Health. Health data is user-owned and personal; if they want it gone they can use the Health app's Sources → Fud AI panel. (Earlier revisions purged HK nutrition in this flow; that was reverted in favor of treating HK as read/write-while-synced but untouched on resets.)

### Widgets (`FudAIWidgetsExtension` target + App Group)

The widget extension lives in the `ios/FudAIWidgets/` folder as its own target (`com.apoorvdarshan.calorietracker.FudAIWidgets`). It's embedded into the main app via the `Embed Foundation Extensions` copy phase. Five supported families: `.systemSmall`, `.systemMedium`, `.accessoryCircular`, `.accessoryRectangular`, `.accessoryInline`.

Because widgets run in a separate process, they can't read the main app's `UserDefaults`. Data flows through an **App Group** shared container:
- **App Group ID**: `group.com.apoorvdarshan.calorietracker` (declared in both `ios/calorietracker/calorietracker.entitlements` and `ios/FudAIWidgets/FudAIWidgets.entitlements` — must match exactly).
- **`WidgetSnapshot`** is a small Codable struct (today's totals + goals) written by the main app into the shared suite under key `widget_snapshot_v1`.
- **Duplicated file**: `ios/calorietracker/Services/WidgetSnapshot.swift` and `ios/FudAIWidgets/WidgetSnapshot.swift` are identical copies. The widget target can't see the main app's sources (auto-discovery via `PBXFileSystemSynchronizedRootGroup` is per-target), so we keep two files in sync manually. If you change one, change both.
- **`WidgetSnapshotWriter.publish(...)`** (main app only) recomputes today's totals, writes the snapshot, and calls `WidgetCenter.shared.reloadAllTimelines()`. Called from three places in `calorietrackerApp.swift`: on `foodStore.onEntriesChanged`, on `.userProfileDidChange` notification (goal edits), and on scene-phase `.active` (so midnight rollover doesn't require an explicit food change).
- **Callback-wiring gotcha**: `wireUpFoodStoreCallback()` (where the `onEntriesChanged` closure gets installed) must run on **every** scene-active, not just the onboarding `false→true` transition. The `.onChange(of: hasCompletedOnboarding)` branch only fires once ever; if it were the sole wire-up site, existing users who completed onboarding before this code landed would never get the widget-refresh callback installed and would have to open the app to see new entries. Closure assignment is idempotent, so re-wiring on scene-active is safe.
- **Timeline policy**: `CalorieProvider.getTimeline` emits one entry for "now" and refreshes after 30 minutes as a safety net for days when the user doesn't log anything.
- **Freshness rules baked into the data layer** (don't regress these):
  1. `ios/FudAIWidgets/WidgetSnapshot.read()` returns `nil` when `snapshot.dayStart` isn't today. The widget then falls back to `.empty` (zeroed today). Without this the 30-min timeline refresh kept showing yesterday's totals past midnight.
  2. `WidgetSnapshotWriter.publish(...)` filters entries with `Calendar.isDate($0.timestamp, inSameDayAs: Date())` — NOT a plain `>= startOfDay`. The latter would fold tomorrow-dated entries (pre-logged via the week strip) into today's widget totals.
  3. `WidgetSnapshot.clear()` is called from Delete All Data and from `refreshWidgetSnapshot()` when no profile is loaded. The App Group container sits outside `UserDefaults.standard`, so `removePersistentDomain` doesn't touch it — without `clear()` the widget would keep showing the previous profile's numbers after a reset.

Adding a new widget: add a new `Widget` conforming type in `ios/FudAIWidgets/`, add it to `FudAIWidgetsBundle.body`, extend `CalorieWidgetView`'s `@Environment(\.widgetFamily)` switch if you're adding a new family. If you need additional data, extend `WidgetSnapshot` in **both** files (add fields with Codable defaults so old snapshots still decode).

### Localization (15 languages)

The app ships with `ios/calorietracker/Localizable.xcstrings` (String Catalog) — ~200 UI strings × 15 locales: `en` (source), `ar`, `az`, `de`, `es`, `fr`, `hi`, `it`, `ja`, `ko`, `nl`, `pt-BR`, `ro`, `ru`, `zh-Hans`.

No in-app language picker. iOS auto-selects from the device language (matches Cal AI / MyFitnessPal / Yazio).

**Rule when adding UI strings**: every new `Text("...")`, `Button("...")`, `Section("...")`, `.alert("...")`, `.navigationTitle("...")`, placeholder, etc. must land in the catalog with translations for all 14 non-English locales before commit. For batches of 10+ strings spawn a general-purpose agent with the translation prompt (see prior commits for the format), merge the JSON into the catalog via a small Python script. `SWIFT_EMIT_LOC_STRINGS = YES` is set — Xcode auto-extracts new English strings on build, but will leave non-English entries empty; fill them in manually before shipping. Adding a new language requires a new code in the catalog + a new `knownRegions` entry in `ios/calorietracker.xcodeproj/project.pbxproj`.

### UI Structure

- `ContentView` hosts a **5-tab layout**: Home, Progress, Coach, Settings, About.
- `OnboardingView` is the first-run flow including an AI-provider-setup step.
- Sheets and pickers route through a single `.sheet(item: $activeSheet)` driven by an enum to avoid SwiftUI's stacked-sheet bugs.
- `Views/Theme.swift` (`AppColors`) holds the gradient palette used across the app.
- Picker sheets (height, weight, body-fat, calories/macros) seed their `@State` in `init()`, not `.onAppear`, to avoid a "flash to default value" on open.
- **Share sheets use `ActivityShareSheet`** (a `UIViewControllerRepresentable` over `UIActivityViewController` defined in `ContentView.swift`) — **not** SwiftUI's `ShareLink`. SwiftUI's `ShareLink(item: URL, message:)` silently drops the `message` arg for most share targets (Messages, Mail, X), so only the URL gets shared. Wrapping `UIActivityViewController` with `[String, URL]` in `activityItems` forwards both: iMessage shows the text plus the URL preview, Mail uses the text as body. Used by About → Share the App.

## Build, Install, Launch (Android)

Kotlin + Jetpack Compose client. Target device is Apoorv's iQOO Z9 5G on OriginOS 6 (Android 15). Min SDK 26 (bumped from 24 for Health Connect).

```bash
# From /Users/ApoorvDarshan/fud-ai/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Build
./gradlew :app:assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity

# Launch with onboarding reset (equivalent to iOS --reset-onboarding)
adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez reset_onboarding true

# Tail logs
adb logcat -s FudAI:* AndroidRuntime:E
```

OriginOS quirks: USB debugging needs **two** toggles enabled (USB debugging + USB debugging Security settings under Developer options). After first install, whitelist Fud AI in battery optimization so alarm-based reminders survive.

## Tests (Android)

No automated tests yet — matches iOS policy. Validate by hand on device. `calorietrackerTests`-equivalent scaffolding lives at `android/app/src/androidTest/` and `src/test/` but only contains Android Studio boilerplate.

## Architecture (Android)

### Package layout

```
android/app/src/main/java/com/apoorvdarshan/calorietracker/
├── MainActivity.kt              ← entry point, checks onboarding flag, wires NavHost
├── FudAIApp.kt                  ← Application class + AppContainer (manual DI)
├── models/                      ← data classes: UserProfile, FoodEntry, WeightEntry,
│                                   ChatMessage, AIProvider, SpeechProvider, WidgetSnapshot,
│                                   Gender/ActivityLevel/WeightGoal/MealType/FoodSource/AutoBalanceMacro
├── data/                        ← PreferencesStore (DataStore), KeyStore (EncryptedSharedPreferences),
│                                   FoodRepository, WeightRepository, ProfileRepository, ChatRepository
├── services/
│   ├── ai/                      ← AiError, RetryPolicy, GeminiClient, AnthropicClient,
│   │                              OpenAICompatibleClient, FoodAnalysisService, ChatService,
│   │                              FoodAnalysis (+ NutritionLabelAnalysis + FoodJsonParser)
│   ├── speech/                  ← NativeSpeechRecognizer, AudioRecorder, SpeechService,
│   │                              WhisperClient, DeepgramClient, AssemblyAIClient
│   ├── health/HealthConnectManager.kt   ← Health Connect I/O (replaces iOS HealthKit)
│   ├── FoodImageStore.kt        ← filesDir/fudai-food-images/
│   ├── NotificationService.kt   ← 3 channels, AlarmManager scheduling, ReminderReceiver
│   └── WeightAnalysisService.kt ← pure forecast math (linear regression + energy balance)
└── ui/
    ├── theme/                   ← AppColors (iOS pink/red), FudAITheme, Typography
    ├── navigation/              ← FudAINavHost, FudAIBottomNavBar, FudAIRoutes
    ├── onboarding/              ← OnboardingScreen + OnboardingViewModel (10-step flow)
    ├── home/                    ← HomeScreen + HomeViewModel
    ├── progress/                ← ProgressScreen + ProgressViewModel (Canvas line chart)
    ├── coach/                   ← CoachScreen + CoachViewModel
    ├── settings/                ← SettingsScreen + SettingsViewModel (sheet-driven pickers)
    └── about/                   ← AboutScreen
```

### State / DI

Manual DI via `FudAIApp.container` (an `AppContainer` singleton). No Hilt — repositories and services are instantiated once in `FudAIApp.onCreate()` and handed to ViewModels via a `ViewModelProvider.Factory` pattern. Screens pull ViewModels with `viewModel(factory = ...)`.

Reactive reads go through `Flow<T>`. Each ViewModel exposes a `StateFlow<UiState>` that screens collect via `collectAsState()`. Matches iOS `@Observable` + `.environment()` in spirit.

### AI routing (13 providers, 3 API formats)

Matches iOS semantically:
- **Gemini** → `GeminiClient` (POST `/models/{model}:generateContent`, `X-goog-api-key` header).
- **Anthropic Messages** → `AnthropicClient` (POST `/messages`, `x-api-key` + `anthropic-version: 2023-06-01`).
- **OpenAI-compatible** → `OpenAICompatibleClient` — covers OpenAI, xAI, OpenRouter, Together AI, Groq, Hugging Face, Fireworks, DeepInfra, Mistral, Ollama, and Custom.

`FoodAnalysisService` and `ChatService` both dispatch by `AIProvider.apiFormat`. `RetryPolicy` does 1s/2s/4s exponential backoff on 503/429/529. Error copy surfaces friendly messages ("provider is overloaded", "API key rejected") instead of raw HTTP codes.

### Speech-to-text

- Native: `android.speech.SpeechRecognizer` wrapped as a cold `Flow<SttEvent>` with streaming partials.
- Remote (Whisper / Groq / Deepgram / AssemblyAI): `AudioRecorder` writes 16 kHz mono AAC to cache, then the per-provider client uploads + parses.

### Health Connect (replaces HealthKit)

Single boundary in `HealthConnectManager`. Weight + Nutrition read/write with `Metadata.manualEntry(clientRecordId = "fudai_<uuid>")` for dedup. Change-token loop for external weight imports (Samsung Health, Withings, Fitbit via Health Connect). Requires **Min SDK 26** — that's why `minSdk = 26` in `app/build.gradle.kts` (Android Studio default was 24).

### Persistence

| iOS | Android |
|---|---|
| UserDefaults JSON blobs | DataStore Preferences + kotlinx.serialization |
| iOS Keychain | EncryptedSharedPreferences (AES-256) |
| Application Support/ | `context.filesDir/fudai-food-images/` |
| App Group shared UserDefaults | Widget reads same DataStore (widget runs in same process on Android by default) |

### UI

5-tab bottom navigation (Home / Progress / Coach / Settings / About) mirroring iOS `ContentView`. NavHost hides the bar on the onboarding route. Design system matches iOS — pink `#FF375F → #FF6B8A` gradient, cream/dark semantic backgrounds, rounded typography via `FontFamily.Default` (Nunito can be swapped in if desired).

### Localization (Android)

Same 15-language coverage as iOS, but Android resolves via Android resource qualifiers instead of a String Catalog:

- `app/src/main/res/values/strings.xml` — English source (~509 keys)
- `app/src/main/res/values-{ar,az,de,es,fr,hi,it,ja,ko,nl,pt-rBR,ro,ru,zh-rCN}/strings.xml` — per-locale catalogs
- Android picks the right file at runtime from `Locale.getDefault()` — no in-app picker, matches iOS

**Enum displayName pattern**: model enums (`Gender`, `ActivityLevel`, `WeightGoal`, `MealType`, `AIProvider`, `SpeechProvider`, `AutoBalanceMacro`) expose `@get:StringRes val displayNameRes: Int` instead of a hardcoded `displayName: String`. Call sites wrap in `stringResource(it.displayNameRes)`. When adding a new enum case, add the matching `<string>` in `values/strings.xml` plus all 14 locale files.

**Composable scope gotcha**: `stringResource(...)` only works inside `@Composable` functions. `LazyColumn { ... }` lambdas have `LazyListScope` (not @Composable), so resolve strings before the LazyColumn and capture them in the closure — the existing `EditFoodEntrySheet.kt` "More Nutrition" block hoists `gUnit`/`mgUnit`/`micros` for exactly this reason.

**Glance widgets** can't use `stringResource` directly; they use `LocalContext.current.getString(R.string.x)` instead.

### Release Build & Signing (Android)

Release config in `app/build.gradle.kts` enables `isMinifyEnabled = true` + `isShrinkResources = true` for production. Without these, the APK is ~29 MB; with R8 it's ~4.4 MB.

**Signing**: `signingConfigs.release` reads from `android/keystore.properties` (gitignored — see `keystore.properties.template`). When the file is absent (fresh checkout, CI without secrets), the build still succeeds and emits an unsigned APK. The keystore itself lives at `~/Documents/fudai-keystore/fudai-release.jks` outside the repo. **Losing the keystore means a 1–2 week recovery flow with Google's Play App Signing reset** — back it up to a password manager + offsite.

Build the AAB Play Console wants:
```bash
./gradlew :app:bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

**Shipped artifacts live in `android/release/`** (tracked in git, ~24 MB and growing). After every Play-Store-bound build, copy the signed outputs into that folder as `fudai-v<MARKETING>.aab` + `fudai-v<MARKETING>.apk` and commit them — this gives us an offline archive of every Play Console upload so a side-load reproducer (or an internal-track recovery) is always one `git checkout` away. Older versions are never deleted.

**ProGuard/R8 keep rules** (`app/proguard-rules.pro`) — these caught real release-only crashes during prep, don't strip them:

1. **kotlinx.serialization** — every `@Serializable` data class is reflected at runtime to find its generated `$$serializer` companion. Without keep rules, DataStore + widget snapshot decode fails with `NoSuchMethodError: serializer()`.
2. **WorkManager + Room** — Glance uses WorkManager internally, which uses Room for `WorkDatabase`. R8 strips the `@Database` class without explicit keeps, crashing `Application.onCreate` with `Failed to create an instance of androidx.work.impl.WorkDatabase`.
3. **Glance widgets** — `CalorieAppWidget` / `ProteinAppWidget` are loaded reflectively from `AndroidManifest.xml`; keep the whole `widget/` package.
4. **Health Connect** — defensive keep on `androidx.health.connect.client.records.**` even though the AAR ships consumer rules.

If a release build crashes but debug works, R8 stripping is the first suspect — check `app/build/outputs/mapping/release/missing_rules.txt`.

**Verify the release APK before uploading**: `./gradlew :app:assembleRelease` → sign with the keystore (or debug-sign with `apksigner` for sideload testing) → install → exercise the surfaces that hit reflection (food log persistence, widget refresh, KeyStore reads) on device.

## Website (`web/`)

Plain static HTML + CSS — no build step, no framework. Deployed to Vercel with the domain `fud-ai.app`. After the monorepo merge, Vercel's git integration must point at this repo with **Root Directory = `web/`** (previously it pointed at the now-obsolete `apoorvdarshan/fud-ai-web` repo).

- **Pages**: `web/index.html` (landing), `web/privacy.html`, `web/terms.html`.
- **Assets**: `web/assets/` — logo, OG preview image, screenshots used by the landing hero.
- **OG image**: `web/assets/og-preview.png` is referenced with `?v=N` cache-busting in `index.html` meta tags. Bump the version when replacing the image so X / Facebook / LinkedIn re-scrape.
- **SEO**: `web/robots.txt`, `web/sitemap.xml`.
- **Preview locally**: any static server, e.g. `cd web && python3 -m http.server 8000`.

## Gotchas (Android)

- **EncryptedSharedPreferences AEAD recovery**: on Android 14/15 the AndroidKeystore master-key alias survives `pm uninstall` but the encrypted prefs file does not, so a reinstall hits `javax.crypto.AEADBadTagException` on first read and crashes `Application.onCreate` before any UI shows. `KeyStore.openOrRecover()` catches this, deletes the prefs file + the master-key alias, and rebuilds. Don't strip the recovery path — losing it means every Play Store update from a wiped install crashes the user.
- **Glance bitmap rasterization**: Glance has no `Canvas` / arc primitives. The pink-gradient progress ring on the Calorie/Protein widgets is rendered into a Bitmap (`widget/RingBitmap.kt`) and passed via `ImageProvider(bitmap)`. Computed in `provideGlance` per recomposition — small enough (~100×100×4 bytes) not to matter.
- **NavController + start destination**: tapping the Home tab while *on* the Home destination is a no-op for `nav.navigate(HOME) { popUpTo(HOME); launchSingleTop = true }`. Use `nav.popBackStack(HOME, inclusive = false)` instead. The bottom-nav code already does this.
- **OriginOS USB debugging**: needs **two** toggles in Developer options — USB debugging *plus* USB debugging (Security settings). Without the second one, `adb install` works but `adb shell input tap` and `am start --es ...` extras get blocked.
- **OriginOS battery optimization**: `AlarmManager.setAndAllowWhileIdle` reminders get killed unless the app is whitelisted. Settings exposes a deep-link to the per-app whitelist via `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (with a fallback chain to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` + `ACTION_APPLICATION_DETAILS_SETTINGS` for OEMs that block the direct intent).
- **No exact alarms**: reminders use the inexact `setAndAllowWhileIdle` path, never `setExactAndAllowWhileIdle`. Play Console blocks `USE_EXACT_ALARM` for any app whose core function isn't a calendar or alarm clock — Fud AI is neither, and the v1.0.0 closed-test upload was rejected the one time we shipped that permission. The manifest deliberately omits both `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM`. A daily streak/summary nudge that fires within a few minutes of the chosen time is fine for this use case; don't reintroduce exact alarms.
- **DataStore singleton**: `Context.fudaiDataStore` is a `preferencesDataStore` extension. Both the main app process and widget receivers call `PreferencesStore(context)` and get the same backing DataStore as long as the application context is used — that's how widgets see writes from the app without IPC. Don't reach for a separate widget store.
- **Drag-to-reorder inside ModalBottomSheet** is fundamentally fragile on Android — the sheet's drag-to-dismiss and any internal vertical scroll compete for the same gesture. Favorites in Saved Meals uses native ↑/↓ buttons (`MoveButtons`) instead. If you reach for drag-to-reorder again, expect to fight it.

## Gotchas (iOS)

- **SourceKit false positives**: editing surfaces "no module 'UIKit'" / "Cannot find type 'FoodEntry' in scope" errors that are not real. Build with `xcodebuild` to verify.
- **`.buttonStyle(.plain)` kills row tap-highlight** in a `List`. Use `.tint(.primary)` if you want the highlight while keeping primary text color.
- **Multiple `.sheet()` modifiers** on the same view cause white/black-screen bugs. Always use a single `.sheet(item:)` driven by an enum.
- **`ProgressView`** is renamed to `ProgressTabView` to avoid clashing with SwiftUI's built-in `ProgressView`.
- **`@Observable` tracking can miss property access buried in computed vars.** HomeView, ProgressTabView, and NutritionDetailView each read `let _ = profileStore.profile` at the top of `body` to force observation tracking. Don't remove those lines.
- **Dead files** (kept for git history but not referenced anywhere): `StoreManager.swift`, `PaywallView.swift`, `SpinWheelView.swift`, `CloudKitService.swift`. Don't add new code to them.
- **Persistent state** lives in two places: `UserDefaults` (preferences + JSON-encoded `entries`/`weights`/`favorites`/`coachChatHistory` arrays + `*Enabled`/`*Reminder*` keys read via `@AppStorage`) and iOS Keychain (LLM + STT API keys via `KeychainHelper`). There is no Core Data / SwiftData / iCloud.
- **CodeQL is not configured** anymore — the workflow was removed because it produced almost entirely false positives on UserDefaults writes near "auth"-keyword variables for a local-only app with no auth flows.

## Commit Style

- Plain factual messages. No co-author trailer. No marketing language.
- Commit and push immediately after each working change.
- When a commit adds user-facing strings, the message should mention the catalog was updated.

## Release Artifacts

- **`APPSTORE.md`** (repo root) holds the App Store Connect listing copy for iOS — name, subtitle, promo text, keywords, What's New, full description, reviewer notes. Update it whenever the iOS version bumps; the current header is `v3.1`. Uploads to App Store Connect happen by hand-pasting from this file — don't let it drift from the code.
- **`PLAYSTORE.md`** (repo root) is the Android-side equivalent for the Play Console — short + full description, Data Safety answers, App Content declarations, reviewer notes. Header is `v1.0.0`.
- **App Store screenshots** live in `~/Documents/fud ai/appstore screenshots/` (raw 1179×2556 captures from device) and get composited into 1242×2688 marketing PNGs by ad-hoc Python scripts in `/tmp/`. The scripts are not in the repo — they're rebuilt per release. The current iteration uses PIL gradient backgrounds + a pixel-perfect iPhone 15 Pro Max frame + Bricolage Grotesque ExtraBold typography.
- Bump `MARKETING_VERSION` in `ios/calorietracker.xcodeproj/project.pbxproj` (two occurrences — main app + widget extension) before each App Store submission. `CURRENT_PROJECT_VERSION` is the build number.

## Identity

- Website: https://fud-ai.app
- App Store: https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726
- Play Store (after first publish): https://play.google.com/store/apps/details?id=com.apoorvdarshan.calorietracker
- Email: apoorv@fud-ai.app
- X: @apoorvdarshan
- Donations: https://paypal.me/apoorvdarshan
