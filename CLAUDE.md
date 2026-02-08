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
```

Available simulators: iPhone 17 Pro, iPhone 17, iPhone Air (no iPhone 16 Pro).

## Workflow

- After EVERY change: git commit and push immediately, NO co-author line in commits.

## Architecture

SwiftUI iOS app (Swift 5, iOS 26.2) with zero external dependencies. Uses Gemini 2.5 Flash API for AI-powered food photo analysis.

### Key Patterns

- **`@Observable` macro** — not `ObservableObject`. Inject with `.environment()`, consume with `@Environment(FoodStore.self)`.
- **`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`** — all code runs on main actor by default. No manual `@MainActor` needed.
- **`PBXFileSystemSynchronizedRootGroup`** — Xcode auto-discovers new files. Never edit pbxproj manually.
- **`GeminiService`** — pure struct with static async methods, no state.
- **Secrets** — `Secrets.plist` (gitignored) loaded via `APIKeyManager`. Contains `GEMINI_API_KEY`.

### Data Flow

User captures photo → `GeminiService.autoAnalyze(image:)` → JSON response parsed into `FoodAnalysis` → user reviews/edits in `FoodResultView` → `FoodStore.addEntry()` → persisted to UserDefaults as JSON → `HomeView` recomputes via `@Observable`.

### Source Layout

| Directory | Purpose |
|-----------|---------|
| `Models/` | `UserProfile` (BMR/TDEE/macros), `FoodEntry` (logged food item) |
| `Views/` | `OnboardingView` (24-step flow), `HomeComponents`, `FoodResultView`, `Theme` (AppColors) |
| `Services/` | `GeminiService` (Gemini API), `APIKeyManager` |
| `Stores/` | `FoodStore` (@Observable, manages food entries array) |

### Main Views

- **`calorietrackerApp`** — routes to `OnboardingView` or `ContentView` based on `@AppStorage("hasCompletedOnboarding")`
- **`ContentView`** — 4-tab layout (Home, Progress, Groups, Profile). Home is fully built; others are placeholders.
- **`OnboardingView`** — 24 steps (0-23) with step index switch. Steps shift when inserting new ones.
- **`HomeView`** (inside ContentView) — daily tracker with week strip, calorie hero, macro cards, meal-grouped food list, camera toolbar.

### Nutrition Math (UserProfile)

- **BMR**: Katch-McArdle when `bodyFatPercentage` is set, otherwise Mifflin-St Jeor
- **TDEE**: BMR × activity level multiplier (6 levels, 1.2–2.0)
- **Daily calories**: `max(1200, TDEE + calorieAdjustment)` where adjustment = `weeklyChangeKg × 7700 / 7`
- **Macros**: 30% protein, 45% carbs, 25% fat

## Gotchas

- **SourceKit false errors**: Cross-file references and UIKit types show errors in editor on macOS. Always build to verify — if `xcodebuild` succeeds, the code is correct.
- **`ProgressTabView`**: Named to avoid clash with SwiftUI's built-in `ProgressView`.
- **Multiple `.sheet()` modifiers**: Cause white/black screens. Use single `.sheet(item:)` with an enum instead.
- **`FoodEntry` backward compat**: Has custom `init(from:)` that defaults `mealType` to `.other` for old entries missing the field.
- **`UserProfile` optional fields**: `bodyFatPercentage` and `weeklyChangeKg` are optional so old saved JSON decodes without them (Swift Codable defaults missing optionals to nil).
