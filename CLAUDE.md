# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build for simulator
xcodebuild -scheme calorietracker -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

# Build for physical device
xcodebuild -scheme calorietracker -destination 'id=00008140-000C02942169801C' build

# Install + launch on device
xcrun devicectl device install app --device 00008140-000C02942169801C \
  /Users/ApoorvDarshan/Library/Developer/Xcode/DerivedData/calorietracker-gpxszidbuonxcogxztdsjuodfjkp/Build/Products/Debug-iphoneos/calorietracker.app \
  && xcrun devicectl device process launch --device 00008140-000C02942169801C com.apoorvdarshan.calorietracker

# Reset onboarding (delete + reinstall — UserDefaults lives on device)
xcrun devicectl device uninstall app --device 00008140-000C02942169801C com.apoorvdarshan.calorietracker
# then reinstall with the install command above

# Reset onboarding in simulator (alternative — uses launch argument)
# App supports --reset-onboarding launch argument to clear UserDefaults
```

Available simulators: iPhone 17 Pro, iPhone 17, iPhone Air (no iPhone 16 Pro).

## Workflow

- After EVERY change: git commit and push immediately, NO co-author line in commits.

## Architecture

SwiftUI iOS app (Swift 5, iOS 26.2) with zero external dependencies. Uses Gemini 2.5 Flash API for AI-powered food photo analysis.

### Key Patterns

- **`@Observable` macro** — not `ObservableObject`. Inject with `.environment()`, consume with `@Environment(FoodStore.self)`. Four objects injected at app root: `FoodStore`, `WeightStore`, `NotificationManager`, `AuthManager`.
- **`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`** — all code runs on main actor by default. No manual `@MainActor` needed.
- **`PBXFileSystemSynchronizedRootGroup`** — Xcode auto-discovers new files. Never edit pbxproj manually.
- **`GeminiService`** — pure struct with static async methods, no state.
- **Secrets** — `Secrets.plist` (gitignored) loaded via `APIKeyManager`. Contains `GEMINI_API_KEY`.

### Data Flow

User captures photo → `GeminiService.autoAnalyze(image:)` → JSON response parsed into `FoodAnalysis` → user reviews/edits in `FoodResultView` → `FoodStore.addEntry()` → persisted to UserDefaults as JSON → `HomeView` recomputes via `@Observable`. Text input follows a similar path via `TextFoodInputView` → `GeminiService`.

### Cloud Sync & Auth

`AuthManager` wraps Apple Sign-In (ASAuthorization). `CloudKitService` is a pure struct with static methods that syncs `FoodEntry`, `WeightEntry`, and `UserProfile` to iCloud private database. Sync is triggered on sign-in and merges by comparing entries by UUID. OnboardingView checks for existing cloud data on first launch and offers to restore.

### Source Layout

| Directory | Purpose |
|-----------|---------|
| `Models/` | `UserProfile` (BMR/TDEE/macros), `FoodEntry` (logged food item), `Article` (learn content), `WeightEntry` |
| `Views/` | `OnboardingView` (24-step flow), `HomeComponents`, `FoodResultView`, `LearnView`, `ProgressComponents`, `Theme` (AppColors) |
| `Services/` | `GeminiService` (Gemini API), `APIKeyManager`, `AuthManager` (Apple Sign-In), `CloudKitService` (iCloud sync) |
| `Stores/` | `FoodStore` (@Observable, food entries), `WeightStore` (@Observable, weight tracking), `NotificationManager` (@Observable, local notifications) |

### Main Views

- **`calorietrackerApp`** — routes to `OnboardingView` or `ContentView` based on `@AppStorage("hasCompletedOnboarding")`
- **`ContentView`** — 4-tab layout: Home, Progress, Learn, Profile. Also contains `HomeView`, `ProfileView`, `CameraView`, `FoodRow`, `MacroPill` inline.
- **`OnboardingView`** — 24 steps (0-23) with step index switch. Steps shift when inserting new ones.
- **`HomeView`** (inside ContentView) — daily tracker with week strip, calorie hero, macro cards, meal-grouped food list, camera toolbar.
- **`LearnView`** — educational articles with search, category filter chips, and sort options. Articles defined in `Article.swift` with Unsplash image thumbnails.
- **`ProgressTabView`** (inside ProgressComponents) — weight tracking, calorie/macro charts, streak stats.

### Nutrition Math (UserProfile)

- **BMR**: Katch-McArdle when `bodyFatPercentage` is set, otherwise Mifflin-St Jeor
- **TDEE**: BMR × activity level multiplier (6 levels, 1.2–2.0)
- **Daily calories**: `max(1200, TDEE + calorieAdjustment)` where adjustment = `weeklyChangeKg × 7700 / 7`
- **Protein**: `activityLevel.proteinPerKg × weightKg` (1.0–2.2 g/kg based on activity)
- **Fat**: `0.6 × weightKg`
- **Carbs**: remaining calories after protein and fat → `(dailyCalories − protein×4 − fat×9) / 4`

## Gotchas

- **SourceKit false errors**: Cross-file references and UIKit types show errors in editor on macOS. Always build to verify — if `xcodebuild` succeeds, the code is correct.
- **`ProgressTabView`**: Named to avoid clash with SwiftUI's built-in `ProgressView`.
- **Multiple `.sheet()` modifiers**: Cause white/black screens. Use single `.sheet(item:)` with an `ActiveSheet` enum (see `HomeView` for the pattern).
- **`FoodEntry` backward compat**: Has custom `init(from:)` that defaults `mealType` to `.other` for old entries missing the field.
- **`UserProfile` optional fields**: `bodyFatPercentage` and `weeklyChangeKg` are optional so old saved JSON decodes without them (Swift Codable defaults missing optionals to nil).
- **AsyncImage `.fill` overflow**: When using `.aspectRatio(contentMode: .fill)` with `AsyncImage`, wrap in `Color.clear.frame(height:).overlay { ... }.clipped()` — otherwise the image layout expands beyond the frame and clips surrounding text.
