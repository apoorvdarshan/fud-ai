# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Fud AI is an open-source iOS calorie tracker (SwiftUI, iOS 17.6+). Snap/speak/type a meal, an AI provider returns nutrition JSON, the user reviews it, and it lands in `FoodStore` + Apple Health. Bring-your-own-key model; all data is local. No subscriptions, no sign-in, no cloud sync.

## Build, Install, Launch

The app is tested on Apoorv's physical iPhone (iPhone 16, device ID `E2095CDC-E117-527C-818A-9F741A145103`). After every change run all three commands. The Release config is intentional — it matches what users actually see.

```bash
# Build
xcodebuild -scheme calorietracker -destination 'id=E2095CDC-E117-527C-818A-9F741A145103' build

# Install
xcrun devicectl device install app --device E2095CDC-E117-527C-818A-9F741A145103 \
  ~/Library/Developer/Xcode/DerivedData/calorietracker-gyjqfuacfxocddfrskbcdsbwqhxa/Build/Products/Release-iphoneos/calorietracker.app

# Launch
xcrun devicectl device process launch --device E2095CDC-E117-527C-818A-9F741A145103 com.apoorvdarshan.calorietracker
```

## Tests

`calorietrackerTests` and `calorietrackerUITests` targets exist but only contain Xcode boilerplate — there are no real tests. Verify behavior by hand on device. If you do add tests, run them with:

```bash
xcodebuild test -scheme calorietracker -destination 'id=E2095CDC-E117-527C-818A-9F741A145103'
```

## Code Review

Use Codex CLI before each PR / after each commit cluster:

```bash
codex exec review --commit <SHA> --full-auto
```

Address P1 and P2 findings. P3 is judgment-call.

## Architecture

### State / Dependency Injection

- All stores use Swift's `@Observable` macro (not `ObservableObject`) and are injected with `.environment(...)` (not `.environmentObject(...)`).
- `FoodStore`, `WeightStore`, `NotificationManager`, `HealthKitManager` are created once in `calorietrackerApp.swift` and shared.
- Build setting `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` means most types are main-actor isolated by default.
- New files are auto-discovered by Xcode via `PBXFileSystemSynchronizedRootGroup`. **Do not** edit `project.pbxproj` to register files.

### AI Provider Routing

`GeminiService` (`Services/GeminiService.swift`) is a pure struct of static methods. `analyzeFood`, `analyzeTextInput`, `autoAnalyze`, `analyzeNutritionLabel` all funnel through `callAI`, which dispatches by provider format:
- **Gemini API** (Google)
- **OpenAI-compatible** (OpenAI, Grok, Groq, OpenRouter, Together AI, Ollama)
- **Anthropic Messages API** (Claude)

The active provider + model + base URL come from `AIProviderSettings` (UserDefaults). API keys come from `KeychainHelper`. To add a provider: add a case to `AIProvider` enum in `Models/AIProvider.swift` (set `baseURL`, `models`, `apiFormat`, `apiKeyPlaceholder`); OpenAI-compatible providers work automatically, otherwise add a branch in `callAI`.

### FoodStore Callbacks

`FoodStore` exposes four hooks that `calorietrackerApp.wireUpHealthKit()` wires to `HealthKitManager`:
- `onEntryAdded` → `writeNutrition(for:)` (immediate, synchronous)
- `onEntryDeleted` → `deleteNutrition(entryID:)`
- `onEntryUpdated` → `updateNutrition(for:)` (delete-then-write, awaited so they don't race)
- `onEntriesChanged` → notification rescheduling

Edits use `onEntryUpdated` rather than back-to-back delete+add so HealthKit can serialize the two operations atomically.

### HealthKit Conventions

`HealthKitManager` (`Stores/HealthKitManager.swift`) is the only HealthKit boundary.

- `authVersion` is bumped when new HealthKit types are added; `needsReauthorization` becomes true for users still on the old version. `requestAuthorization` only persists the new version when **all** dietary share types are `.sharingAuthorized` so users who deny nutrition can re-prompt.
- Each nutrition sample carries `fudai_entry_id` metadata = `FoodEntry.id.uuidString`. Deletion uses a metadata predicate.
- `deleteNutrition`, `writeNutrition`, `updateNutrition` all guard on the `healthKitEnabled` flag. `purgeNutrition` bypasses the flag — used only by Delete-All-Data so previously-synced samples are removed even if HealthKit was later turned off.
- `backfillNutritionIfNeeded` is idempotent (queries Apple Health for each entry's UUID before writing) and is guarded by `isBackfillingNutrition` so scene-phase re-entry can't spawn overlapping scans. The caller passes `currentEntryIDs: () -> Set<UUID>` so a meal deleted mid-backfill won't be re-exported as a phantom sample.

Clear Food Log keeps Apple Health samples (per product spec — only saves storage). Delete All Data wipes them.

### UI Structure

- `ContentView` hosts a 4-tab layout: Home, Progress, Settings (originally "Profile"), About.
- `OnboardingView` is the first-run flow including an AI-provider-setup step.
- Sheets and pickers route through a single `.sheet(item: $activeSheet)` driven by an enum to avoid SwiftUI's stacked-sheet bugs.
- `Views/Theme.swift` (`AppColors`) holds the gradient palette used across the app.

## Gotchas

- **SourceKit false positives**: editing in Claude Code surfaces "no module 'UIKit'" / "Cannot find type 'FoodEntry' in scope" errors that are not real. Build with `xcodebuild` to verify.
- **`.buttonStyle(.plain)` kills row tap-highlight** in a `List`. If you need the tap highlight back while keeping primary text color, use `.tint(.primary)` instead.
- **Multiple `.sheet()` modifiers** on the same view cause white/black-screen bugs. Always use a single `.sheet(item:)` driven by an enum.
- **`ProgressView`** is renamed to `ProgressTabView` to avoid clashing with SwiftUI's built-in `ProgressView`.
- **Dead files** (kept for git history but not referenced anywhere): `AuthManager.swift`, `StoreManager.swift`, `PaywallView.swift`, `SpinWheelView.swift`, `CloudKitService.swift`. Don't add new code to these.
- **Persistent state** lives in two places: `UserDefaults` (preferences + JSON-encoded `entries`/`weights`/`favorites` arrays + the various `*Enabled` / `*Reminder*` keys read via `@AppStorage`) and iOS Keychain (API keys via `KeychainHelper`). There is no Core Data / SwiftData / iCloud.
- **CodeQL workflow** (`.github/workflows/codeql.yml`) pins `latest-stable` Xcode via `maxim-lobanov/setup-xcode@v1` because the runner default is too old for our Swift Charts usage.

## Commit Style

- Plain factual messages. No co-author trailer. No marketing language.
- Commit and push immediately after each working change.

## Identity

- Website: https://fud-ai.app
- App Store: https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726
- Email: apoorv@fud-ai.app
- X: @apoorvdarshan
- Donations: https://paypal.me/apoorvdarshan
