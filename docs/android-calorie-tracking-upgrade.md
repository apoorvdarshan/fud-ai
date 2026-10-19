# Calorie Tracking Android upgrade

## Scope and safeguards

Upgrade the existing Android application in place. Keep the application ID
`com.apoorvdarshan.calorietracker`, DataStore keys and serialized data formats,
AI provider/key handling, and backup format stable. Do not remove existing
tracking tabs, barcode sources, or optional backup/export paths. The new display
name is `Calorie Tracking`; project, package, provider, and license identifiers
are not display names and remain unchanged.

## Audit

| Area | Already present | Gap to address |
| --- | --- | --- |
| App structure | Kotlin, Jetpack Compose, manual app-scoped dependency wiring, view models, dedicated Home/Progress/Coach/Settings/Workouts navigation | Prefer targeted changes over a rewrite |
| Food photo logging | Multi-photo camera/gallery intake, AI vision providers, component breakdown, editable ingredient/portion/macros, review draft persistence, explicit add-to-diary action | No visible confidence score or clear estimate caveat; do not invent numerical confidence |
| Recipes and Sudanese food | Ingredient editing, reusable saved meals, and recipe serving split/scaling | No Sudanese/Arabic food catalog or source-attributed nutrition data |
| Coach | Separate persistent chat, photo context, configured AI providers, profile/weight/food history context and historical-data tools | Current-day calorie and macro totals are not explicit in the initial coach context |
| Barcode | Open Food Facts, saved label memory, optional USDA FoodData Central; lookup and error-path unit tests | Retain existing lookup order and verify regression paths |
| Persistence and recovery | Preferences/DataStore-backed diary and settings, Health Connect synchronization, JSON/CSV/Markdown diary export, optional Google Drive backup/restore including meal photos | Add regression checks; do not change existing formats or require cloud setup |
| Localization and themes | Arabic resources, RTL manifest support, per-app locale selection, system light/dark theme, theme colors | Check missing Arabic strings and test the redesigned composer in RTL |
| Build and CI | Gradle wrapper; CI runs Android unit tests, release lint, unsigned release package, and emulator instrumentation | This workspace has Java 25 and no configured Android SDK/JDK 17; a local APK build may be blocked. A signing keystore is also absent, so local Release output is unsigned |

Relevant existing implementation includes `FoodResultSheet`, `MealIngredientsUi`,
`RecipeServings`, `HomeViewModel`, `CoachContext`, `ChatService`,
`PreferencesStore`, `DiaryExporter`, and the `backup` package. Existing tests
cover barcode lookup, food analysis parsing, recipe servings, backup/restore,
diary import/export, and diary submission guards.

## Prioritized implementation plan

### 1. Protect data and establish current behavior

- Preserve package ID, DataStore serialization, backup format, and existing
  diary write gates.
- Run focused existing unit tests and the prescribed Android build checks.
- Acceptance: current flows remain covered; no storage migration is introduced.

### 2. Make food analysis transparent and verifiable

- Keep the existing editable review sheet and single explicit log action.
- Clearly label vision-derived values as estimates; show uncertainty as a
  qualitative caveat rather than fabricating a confidence percentage.
- Add regression tests for ingredient totals, serving scaling, review edits, and
  duplicate-submit protection.
- Acceptance: edits flow into the one logged diary entry, and absent evidence is
  never represented as verified nutrition.

### 3. Add extensible Arabic/Sudanese recipe support

- Add a local, source-attributed catalog and recipe composition using existing
  ingredient and serving models where possible.
- Only calculate authoritative nutrition from ingredients with usable sourced
  nutrition values. User-created recipes with incomplete references remain
  clearly incomplete/estimated; do not seed guessed macros for kisra, asida,
  tagalia, or weika.
- Acceptance: amounts and serving splits scale predictably and every sourced
  value carries provenance; missing values are not silently treated as zero.

### 4. Ground the coach in today's real nutrition

- Add today's logged calories and macros, targets, and remaining amounts to the
  coach context using the same local-day boundary and totals as the diary.
- Distinguish an empty/partial diary from proof of no food intake; send context
  only through the user's configured coach request.
- Acceptance: deterministic tests cover empty days, entries across midnight,
  goal values, and remaining totals.

### 5. Finish identity and conversational polish

- Change Android-facing labels and debug launcher names to `Calorie Tracking`;
  retain package names, provider names, old backup compatibility, and required
  legal notices.
- Replace the launcher/splash mark with an original nutrition-tracking symbol
  and simplify the Coach composer while keeping the photo, voice, and reset
  actions and the dedicated tracking tabs.
- Acceptance: no old product name remains in Android user-visible resources,
  controls remain accessible, and English/Arabic, light/dark resource checks
  pass.

## Verification

Focused checks, when the local Android toolchain is available:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease -PworkoutVectors=none
```

Use a disposable API 36 emulator for `:app:connectedDebugAndroidTest`. Device
camera behavior, RTL visual layout, and an install/upgrade data-preservation
check require an emulator/device and must not be reported as verified without
running them. Release signing requires the maintainer's own keystore and is not
needed for an unsigned local APK or CI packaging check.
