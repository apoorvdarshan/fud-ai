<p align="center">
  <img src="web/assets/calorie%20logo%20transparent.png" width="120" height="120" alt="Fud AI Logo">
</p>

<h1 align="center">Fud AI</h1>

<p align="center">
  <strong>Eat Smart, Live Better</strong><br>
  Snap, speak, or type your food — AI handles the rest.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/iOS-17.6+-blue?logo=apple" alt="iOS">
  <img src="https://img.shields.io/badge/Android-8.0+-green?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/swift-5-orange?logo=swift" alt="Swift">
  <img src="https://img.shields.io/badge/kotlin-2.2-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-SwiftUI%20%2F%20Compose-purple" alt="UI">
  <img src="https://img.shields.io/badge/privacy-local--first-brightgreen" alt="Local-first privacy">
  <img src="https://img.shields.io/badge/languages-15-blue" alt="15 Languages">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
  <a href="https://github.com/apoorvdarshan/fud-ai/stargazers"><img src="https://img.shields.io/github/stars/apoorvdarshan/fud-ai?style=flat&logo=github&color=yellow" alt="GitHub stars"></a>
  <a href="https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726"><img src="https://img.shields.io/badge/App%20Store-Download-black?logo=apple" alt="App Store"></a>
  <a href="https://play.google.com/store/apps/details?id=com.apoorvdarshan.calorietracker"><img src="https://img.shields.io/badge/Google%20Play-Download-414141?logo=googleplay" alt="Google Play"></a>
</p>

---

Open-source, privacy-first calorie tracker for iOS and Android. Bring your own AI provider — 13 supported including Gemini, OpenAI, Claude, Grok, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, and any custom OpenAI-compatible endpoint. iOS also supports Fud AI Plus, an optional paid no-key mode that routes food scans and Coach through Gemini and Plus voice through Deepgram via a serverless proxy with daily limits and speech language control. Snap a meal, scan a barcode, combine two camera shots, add a note to a camera or library photo, ask your AI coach how to hit your goal, or speak your lunch. No accounts, no cloud sync, no tracking.

[App Store](https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726) · [Google Play](https://play.google.com/store/apps/details?id=com.apoorvdarshan.calorietracker) · [Website](https://fud-ai.app) · [Report an Issue](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20) · [Request a Feature](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20)

---

## Features

### Logging
- **Snap food** — camera identifies meals and estimates nutrition
- **Camera + Note** — add a description with the photo for better accuracy
- **Camera + Camera** — capture two images for one analysis, useful for front/back packaging or food + label combos
- **Nutrition label scan** — reads packaging for precise per-serving data
- **Barcode lookup** — scan packaged foods on iOS and Android and fill nutrition from Open Food Facts when product data is available
- **Photo library** — analyze existing photos
- **Photo library + Note** — pick an existing photo and add context before AI analysis
- **Text input** — type food descriptions
- **Voice input** — speak your meals hands-free (6 STT options with per-provider language selection, see below)
- **Manual Entry** — log known calories and macros without AI
- **Smart serving units** — AI can show slices, pieces, cups, ml, or other visible serving units while grams stay the source of truth
- **Saved Meals** — Recents, Frequent, and Favorites with safer swipe actions, search, and drag-to-reorder

### Intelligence
- **AI Coach tab** — multi-turn chat with memory. Coach sees your profile, weight history, food log, today's date/timezone, and richer meal details, then answers questions like "what's my expected weight in 30 days?" or "how do I lose 2 kg?". Coach also supports camera/photo attachments on Android. Memory persists across launches; Reset button starts a fresh conversation. Long-press any reply to copy.
- **AI Access modes** — Bring Your Own Key remains available; iOS onboarding now highlights optional Fud AI Plus first for no-key Gemini food scan, Deepgram voice transcription, and Coach access with a Plus speech language selector.
- **AI optional nutrient goals** — estimate detailed nutrient goals from profile data without changing calorie/protein/carbs/fat formulas.
- **Goal-aware prompt chips** — suggested questions change based on whether your goal is Lose / Gain / Maintain
- **Thermodynamic weight forecast** — expected weight at 30/60/90 days, predicted vs observed weekly change, days-to-goal, under-logging detection. Surfaced through Coach as live context on every turn.
- **Resilient requests** — transient provider overloads (503 / 529 / 429) auto-retry with 1s / 2s / 4s exponential backoff across both food analysis and Coach chat, so short spikes resolve invisibly

### Tracking
- **Expanded nutrients** per entry — macros plus sugar, fiber, fats, cholesterol, sodium, potassium, calcium, iron, magnesium, zinc, vitamins, folate, omega-3, and more when available
- **Custom Home nutrient cards** — swap the top cards from protein/carbs/fat to fiber, sodium, vitamin D, calcium, or other tracked nutrients
- **Optional nutrient goals** — set or AI-estimate goals for the non-macro nutrients; these stay separate from the calorie and macro calculator
- **Scrollable week calendar** — swipe to any past week, configurable start day
- **Food log sorting** — keep the default grouped view, or sort meal sections by latest logging order from the Home screen
- **Progress charts** — weight trends, calorie history, macro averages (1W to All Time)
- **Weight History** — tap-to-delete past entries, syncs deletion to Apple Health
- **Goal tracking** — set target weight, BMR/TDEE auto-calculation; goal-reached alert fires from both manual logs and Apple Health reads

### Health & platform
- **Apple Health** — bidirectional sync for body measurements + nutrition types written per meal
- **Health Connect** — Android sync for nutrition, weight, and body fat, with permission reconciliation and backfill support
- **Widgets** — Home Screen and Lock Screen widgets on iOS now follow your selected Home nutrient cards instead of fixed macros; Android Glance widgets update when you log
- **Android 2.0 UI refresh** — cleaner glass-style menus, sheets, dialogs, selectors, light mode, bottom spacing, cached thumbnails, in-app camera capture, and safer food-row gestures
- **Share the App** — native iOS share sheet from About → forwards App Store URL plus a personalized message and `fud-ai.app` link; message body localized into all 15 languages
- **Update check** — About shows the installed app version, opens the App Store / Play Store when a newer version is available, and shows a tab dot for pending updates
- **Theme color** — iOS and Android Settings let users change the app accent, with matching home screen / launcher icons
- **15 languages** — Arabic, Azerbaijani, Dutch, English, French, German, Hindi, Italian, Japanese, Korean, Portuguese (Brazil), Romanian, Russian, Simplified Chinese, Spanish (auto-selected by the phone's Language setting)
- **Meal reminders** — customizable breakfast, lunch, dinner notifications
- **Dark mode** — system, light, or dark
- **Metric & imperial** units

## AI Providers

Pick any of the **13 LLM providers** for food analysis, optional nutrient-goal estimation, and Coach chat. Free Gemini keys are available at [aistudio.google.com/apikey](https://aistudio.google.com/apikey). On iOS, Fud AI Plus can be selected instead of BYOK; Plus uses Gemini models for food and Coach behind a serverless proxy, Deepgram for voice transcription, and separate daily limits for food analysis, speech-to-text, and Coach. Plus voice can use Provider Auto, which follows the iPhone language, or a manually selected speech language. Plus currently offers weekly, monthly, and yearly products.

Fud AI Plus proxy env vars: `GEMINI_API_KEY` is required for food/Coach, and `DEEPGRAM_API_KEY` is required for Plus voice. Optional quota overrides are `FUD_AI_PLUS_FOOD_DAILY_LIMIT`, `FUD_AI_PLUS_SPEECH_DAILY_LIMIT`, `FUD_AI_PLUS_COACH_DAILY_LIMIT`, and `FUD_AI_PLUS_GLOBAL_DAILY_LIMIT`. Defaults are 30 food analyses, 20 speech transcriptions, 25 Coach messages, and 70 total successful Plus calls per day. Plus voice recordings are capped at 60 seconds in the app.

| Provider | Format | Highlight | Needs API Key |
|----------|--------|-----------|:---:|
| Google Gemini | Gemini API | Gemini 2.5 Pro / Flash | Yes |
| OpenAI | OpenAI | GPT-5 / 4o | Yes |
| Anthropic Claude | Messages API | Sonnet 4.6 (default) / Opus 4.7 / Haiku 4.5 | Yes |
| xAI Grok | OpenAI-compatible | Grok 4 | Yes |
| OpenRouter | OpenAI-compatible | Any model, free-form IDs | Yes |
| Together AI | OpenAI-compatible | Llama 4, Qwen VL | Yes |
| Groq | OpenAI-compatible | Llama 4 Scout, very fast | Yes |
| Hugging Face | OpenAI-compatible | Gemma 3, Qwen VL, Llama Vision (open-weight router, free-form IDs) | Yes |
| Fireworks AI | OpenAI-compatible | Qwen VL, Llama Vision, Phi-3 Vision | Yes |
| DeepInfra | OpenAI-compatible | Gemma 3, Llama Vision, Qwen VL | Yes |
| Mistral | OpenAI-compatible | Pixtral Large / 12B (open-weight) | Yes |
| Ollama | OpenAI-compatible (local) | Llama 3.2 Vision, LLaVA, Moondream — runs on your Mac | No |
| Custom (OpenAI-compatible) | OpenAI-compatible | You set base URL + free-form model name | Optional |

## Speech-to-Text Providers

Pick how voice input is transcribed. Native iOS is the default — free, on-device, real-time. Each provider has its own language setting: use Provider Auto, Use iPhone Language, or an explicit language hint where supported.

| Provider | Notes |
|----------|-------|
| Native iOS (On-Device) | Free, offline on modern iPhones, real-time partial results |
| Gemini Audio | Batch audio transcription through Gemini for BYOK users |
| OpenAI Whisper | Whisper-1 via `/v1/audio/transcriptions` |
| Groq (Whisper) | Whisper-large-v3, very fast, has a free tier |
| Deepgram | Nova-3, fast and accurate; used by Fud AI Plus voice logging through the proxy |
| AssemblyAI | Universal model, strong accuracy, free tier |

API keys are stored encrypted on-device: **iOS Keychain** on iOS and **EncryptedSharedPreferences backed by Android Keystore** on Android.

## How It Works

```
Photo / Text / Voice
        │
        ▼
  BYOK provider API or Fud AI Plus proxy  ──▶  JSON nutrition response
        │
        ▼
  User reviews & edits
        │
        ▼
  FoodStore.addEntry()  ──▶  UserDefaults (local) + Apple Health (optional)
```

For the Coach chat, every turn builds a slim system prompt from your live profile, BMR formula in use, computed forecast, today's date/timezone, and a one-line snapshot of available data. Coach then pulls any date range of weight, body fat, calorie totals, or food entries on demand via tool calling — ask "what was my weight in March?" or "show me my body fat trend over the last 6 months" and it fetches exactly the slice it needs, including meal source, meal type, serving size, and micronutrients.

## Screenshots

A ten-screen walkthrough of the current app flow — from opening the dashboard to custom nutrient cards and settings.

<table>
  <tr>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/home.png" width="230" alt="Home dashboard">
      <br><br>
      <b>01 · Home · Dashboard</b>
      <br>
      <sub>Daily calorie ring, selected Home nutrient cards, and today's logged meals grouped by meal type. Week strip at the top for date navigation.</sub>
    </td>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/logging.png" width="230" alt="Food logging options menu">
      <br><br>
      <b>02 · Log · Options</b>
      <br>
      <sub>Tap + to open Camera, Camera + Note, Camera + Camera, Nutrition Label, Barcode, Photos, Text, Voice, Manual Entry, Saved Meals, or Copy from Day.</sub>
    </td>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/snap.png" width="230" alt="Snap food capture">
      <br><br>
      <b>03 · Snap · Capture</b>
      <br>
      <sub>Point and shoot. The image is sent to your chosen AI provider; nutrition estimates come back within a few seconds.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/review.png" width="230" alt="Review food entry">
      <br><br>
      <b>04 · Review · Edit</b>
      <br>
      <sub>Review the AI's guess, adjust the serving size (everything recalculates live), and pick a meal type before logging.</sub>
    </td>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/meals.png" width="230" alt="Meals log">
      <br><br>
      <b>05 · Meals · Log</b>
      <br>
      <sub>The day's entries grouped by breakfast / lunch / dinner / snack. Swipe to delete, tap to edit any entry.</sub>
    </td>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/coach.png" width="230" alt="AI Coach chat">
      <br><br>
      <b>06 · Coach · AI Chat</b>
      <br>
      <sub>Multi-turn conversation with full context of your profile, weight history, food log, and forecast. Ask "what should I eat?" or "expected weight in 30 days?".</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/progress.png" width="230" alt="Progress charts">
      <br><br>
      <b>07 · Progress · Charts</b>
      <br>
      <sub>Weight trend with goal line, calorie history (intake vs. goal), and macro averages. Time ranges span 1 week to all time.</sub>
    </td>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/micronutrients.png" width="230" alt="Detailed nutrition view">
      <br><br>
      <b>08 · View More · Nutrients</b>
      <br>
      <sub>Detailed nutrition shows macro totals plus optional nutrients such as fiber, sugar, sodium, minerals, vitamins, and fats.</sub>
    </td>
    <td align="center" width="33%">
      <img src="web/assets/screenshots/custom.png" width="230" alt="Custom Home nutrient cards">
      <br><br>
      <b>09 · Custom · Home Cards</b>
      <br>
      <sub>Choose the three nutrients that matter most on Home without changing calorie/protein/carbs/fat calculations.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%" colspan="3">
      <img src="web/assets/screenshots/setting.png" width="230" alt="Settings">
      <br><br>
      <b>10 · Settings · Control</b>
      <br>
      <sub>Adjust AI access, goals, Health sync, notifications, theme, optional nutrients, and local data controls from one place.</sub>
    </td>
  </tr>
</table>

## Calorie & Macro Calculation

The app calculates personalized daily targets using established nutrition science formulas:

| Step | Formula | Details |
|------|---------|---------|
| **BMR** | Katch-McArdle | `370 + 21.6 × lean mass (kg)` — used when body fat % is known |
| **BMR** | Mifflin-St Jeor | `10w + 6.25h − 5a ± 5` — fallback when body fat is unknown |
| **TDEE** | BMR × activity | Multiplier ranges from 1.2 (sedentary) to 2.0 (extra active) |
| **Daily Calories** | TDEE + adjustment | Adjustment = `weeklyChangeKg × 7700 / 7` (deficit or surplus) |
| **Protein** | Activity + goal | `0.8 – 2.2 g/kg` by activity, plus +0.2 g/kg during cutting phase (Helms et al 2014) |
| **Fat** | Fixed ratio | `0.6 g/kg` body weight |
| **Carbs** | Auto-balanced | Remainder from calories − protein − fat (any macro can be pinned; max 2 pinned) |

All values can be manually overridden in Settings, with a **Recalculate Goals** button to snap back to formula defaults.

## Architecture

| Component | Details |
|-----------|---------|
| **Language** | Swift 5, SwiftUI, iOS 17.6+ |
| **Storage** | UserDefaults (local JSON), Keychain (API keys) |
| **AI** | `GeminiService` for food + label analysis, `ChatService` for multi-turn Coach chat, both route across all 13 providers |
| **Speech** | Native `SFSpeechRecognizer` or remote providers via `SpeechService` (m4a upload) |
| **Health** | HealthKit / Health Connect read-write paths for body measurements and meal nutrition, with UUID-tagged samples for safe delete |
| **Pattern** | `@Observable` + `.environment()`, main actor isolation |
| **Localization** | `Localizable.xcstrings` (String Catalog), 15 languages, auto-selected by iPhone's system language |
| **Dependencies** | RevenueCat SPM for iOS purchases; app data and API keys remain local |

### Repo Layout

```
fud-ai/
├── ios/          # SwiftUI iOS app (shipping on App Store, v3.6)
├── android/      # Kotlin + Jetpack Compose app (min SDK 26 / Android 8.0, v2.0.0)
├── web/          # Marketing site — https://fud-ai.app (static HTML/CSS, Vercel)
├── APPSTORE.md   # App Store Connect listing copy (iOS)
├── PLAYSTORE.md  # Google Play Console listing copy (Android)
└── README, LICENSE, CONTRIBUTING, SECURITY, CLAUDE.md, .github/
```

### Source Layout (iOS)

```
ios/
├── calorietracker.xcodeproj/         # Xcode project
├── calorietrackerTests/              # Unit test target (boilerplate)
├── calorietrackerUITests/            # UI test target (boilerplate)
├── FudAIWidgets/                     # Widget extension target (Home + Lock Screen)
├── screenshots/                      # App Store screenshot sources
└── calorietracker/
    ├── calorietrackerApp.swift       # Entry point, environment setup
    ├── ContentView.swift             # 5-tab layout (Home, Progress, Coach, Settings, About)
    ├── Localizable.xcstrings         # String Catalog, 15 languages
    ├── Models/
    │   ├── AIProvider.swift          # 13 LLM providers, model lists, settings
    │   ├── SpeechProvider.swift      # 6 STT options + Keychain settings
    │   ├── ChatMessage.swift         # Coach chat message model
    │   ├── UserProfile.swift         # BMR/TDEE/macro calculations
    │   ├── FoodEntry.swift           # Food item with macros and expanded optional nutrients
    │   └── WeightEntry.swift         # Weight log entry
    ├── Views/
    │   ├── OnboardingView.swift      # 15-step onboarding flow
    │   ├── ChatView.swift            # Coach tab: bubbles, prompt chips, reset
    │   ├── FoodResultView.swift      # AI result review & edit
    │   ├── RecentsView.swift         # Saved Meals (Recents / Frequent / Favorites)
    │   ├── VoiceInputView.swift      # Native + remote STT routing
    │   ├── HomeComponents.swift      # Week strip, macro cards
    │   └── ProgressComponents.swift  # Charts, weight history
    ├── Services/
    │   ├── GeminiService.swift       # Food/label analysis, routes 13 providers
    │   ├── ChatService.swift         # Multi-turn Coach chat, routes 13 providers
    │   ├── SpeechService.swift       # Remote STT router (Gemini / OpenAI / Groq / Deepgram / AssemblyAI)
    │   ├── WeightAnalysisService.swift # Thermodynamic weight-forecast math
    │   ├── KeychainHelper.swift      # iOS Keychain wrapper
    │   └── APIKeyManager.swift       # Keychain migration helper
    └── Stores/
        ├── FoodStore.swift            # Food CRUD + favorites
        ├── WeightStore.swift          # Weight CRUD (auto-syncs profile weight)
        ├── ProfileStore.swift         # @Observable wrapper over UserProfile
        ├── ChatStore.swift            # Coach chat history (persisted locally)
        ├── NotificationManager.swift  # Notification scheduler
        └── HealthKitManager.swift     # Apple Health bridge (body + nutrition)
```

## Build & Run

```bash
# Clone
git clone https://github.com/apoorvdarshan/fud-ai.git
cd fud-ai
```

### iOS

```bash
xcodebuild -project ios/calorietracker.xcodeproj \
  -scheme calorietracker \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Open `ios/calorietracker.xcodeproj` in Xcode, select your device, and run.

### Android

Open `android/` in Android Studio (Narwhal or newer), let Gradle sync, hit ▶ Run. Or from the CLI:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity
```

First launch walks you through a 15-step onboarding (gender, birthday, height/weight with metric/imperial toggle, body fat %, activity, goal, goal speed, notifications, Health Connect, AI access mode, plan preview, review). A free Gemini key is available at [aistudio.google.com/apikey](https://aistudio.google.com/apikey), or iOS users can choose Fud AI Plus. You can switch anytime in **Settings → AI Access**.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines. Bug reports and feature requests welcome.

Adding a new translation? Open `ios/calorietracker/Localizable.xcstrings` in Xcode and fill in your language column — everything else is already wired.

## Security

See [SECURITY.md](SECURITY.md). Use [private vulnerability reporting](https://github.com/apoorvdarshan/fud-ai/security/advisories/new) for sensitive issues.

## Privacy

No accounts, no cloud sync, no analytics. BYOK API keys are encrypted on-device and requests go directly to the provider you choose. Barcode lookup sends the scanned barcode to Open Food Facts and stores the returned nutrition locally. Fud AI Plus sends only the active AI/STT request through the proxy for processing and quota enforcement: Gemini for food/Coach and Deepgram for voice. Optional nutrient goals, Home nutrient-card choices, food photos, cached thumbnails, and widget snapshots are local preferences/data; AI estimation sends only the profile context needed for that one estimate. **Delete All Data** is local-only — it wipes the app's storage (food log, weight log, body-fat log, profile, Coach chat, saved meals, API keys, widget snapshot) but never touches Apple Health or Health Connect. Samples you've synced are yours; if you want them cleaned up, do it from Health / Health Connect settings. See [Privacy Policy](https://fud-ai.app/privacy.html).

## License

MIT License. See [LICENSE](LICENSE).

## Contact

- **Developer:** Apoorv Darshan
- **Email:** apoorv@fud-ai.app or ad13dtu@gmail.com
- **Follow on X:** [@apoorvdarshan](https://x.com/apoorvdarshan)
- **Follow on Instagram:** [@fudai.app](https://www.instagram.com/fudai.app/)
- **Report an Issue:** [github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20)
- **Request a Feature:** [github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20)

## Support the Project

Fud AI is fully free, open source, and privacy-first. If it helps you, consider supporting development — every bit keeps this project alive.

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-ff5e5b?logo=kofi)](https://ko-fi.com/apoorvdarshan)
[![Product Hunt](https://img.shields.io/badge/Product%20Hunt-Vote-orange?logo=producthunt)](https://www.producthunt.com/products/fud-ai-calorie-tracker)

You can also help by [voting on Product Hunt](https://www.producthunt.com/products/fud-ai-calorie-tracker), [starring the repo](https://github.com/apoorvdarshan/fud-ai), [filing bugs](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20), or [requesting features](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20).

## Contributors

Thanks to everyone who has contributed to making Fud AI better:

<a href="https://github.com/apoorvdarshan/fud-ai/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=apoorvdarshan/fud-ai&amp;max=100&amp;columns=12" alt="Contributors" />
</a>
