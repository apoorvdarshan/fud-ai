<p align="center">
  <img src="appicon.png" width="120" height="120" alt="Fud AI Logo" style="border-radius: 22px;">
</p>

<h1 align="center">Fud AI</h1>

<p align="center">
  <strong>Eat Smart, Live Better</strong><br>
  Snap, speak, or type your food — AI handles the rest.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-iOS%2017.6+-blue?logo=apple" alt="Platform">
  <img src="https://img.shields.io/badge/swift-5-orange?logo=swift" alt="Swift">
  <img src="https://img.shields.io/badge/UI-SwiftUI-purple" alt="SwiftUI">
  <img src="https://img.shields.io/badge/dependencies-zero-brightgreen" alt="Zero Dependencies">
  <img src="https://img.shields.io/badge/languages-15-blue" alt="15 Languages">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
  <a href="https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726"><img src="https://img.shields.io/badge/App%20Store-Download-black?logo=apple" alt="App Store"></a>
</p>

---

Open-source, privacy-first calorie tracker for iOS. Bring your own AI provider — 13 supported including Gemini, OpenAI, Claude, Grok, Groq, Hugging Face, Fireworks AI, DeepInfra, Mistral, and any custom OpenAI-compatible endpoint. Open-weight models (Gemma, Llama Vision, Qwen VL, Pixtral) work out of the box. Snap a meal, ask your AI coach how to hit your goal, speak your lunch. All data stays on your device. No accounts, no cloud sync, no tracking, no subscriptions.

[Download on the App Store](https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726) · [Website](https://fud-ai.app) · [Report an Issue](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20) · [Request a Feature](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20)

---

## Features

### Logging
- **Snap food** — camera identifies meals and estimates nutrition
- **Camera + Note** — add a description with the photo for better accuracy
- **Nutrition label scan** — reads packaging for precise per-serving data
- **Photo library** — analyze existing photos
- **Text input** — type food descriptions
- **Voice input** — speak your meals hands-free (5 STT providers, see below)
- **Saved Meals** — Recents, Frequent, and Favorites with swipe-to-delete and drag-to-reorder

### Intelligence
- **AI Coach tab** — multi-turn chat with memory. Coach sees your profile, weight history, and food log and answers questions like "what's my expected weight in 30 days?" or "how do I lose 2 kg?". Memory persists across launches; Reset button starts a fresh conversation. Long-press any reply to copy.
- **Goal-aware prompt chips** — suggested questions change based on whether your goal is Lose / Gain / Maintain
- **Thermodynamic weight forecast** — expected weight at 30/60/90 days, predicted vs observed weekly change, days-to-goal, under-logging detection. Surfaced through Coach as live context on every turn.
- **Resilient requests** — transient provider overloads (503 / 529 / 429) auto-retry with 1s / 2s / 4s exponential backoff across both food analysis and Coach chat, so short spikes resolve invisibly

### Tracking
- **13 nutrients** per entry (calories, protein, carbs, fat + 9 micronutrients)
- **Scrollable week calendar** — swipe to any past week, configurable start day
- **Progress charts** — weight trends, calorie history, macro averages (1W to All Time)
- **Weight History** — tap-to-delete past entries, syncs deletion to Apple Health
- **Goal tracking** — set target weight, BMR/TDEE auto-calculation; goal-reached alert fires from both manual logs and Apple Health reads

### Health & platform
- **Apple Health** — bidirectional sync for body measurements + 12 nutrition types written per meal
- **Widgets** — Home Screen (small / medium with calorie ring + macro bars) and Lock Screen (circular / rectangular / inline). Update live whenever you add or delete a meal — no tap-to-open-app needed
- **Share the App** — native iOS share sheet from About → forwards App Store URL plus a personalized message and `fud-ai.app` link; message body localized into all 15 languages
- **15 languages** — Arabic, Azerbaijani, Dutch, English, French, German, Hindi, Italian, Japanese, Korean, Portuguese (Brazil), Romanian, Russian, Simplified Chinese, Spanish (auto-selected by iPhone's Language setting)
- **Meal reminders** — customizable breakfast, lunch, dinner notifications
- **Dark mode** — system, light, or dark
- **Metric & imperial** units

## AI Providers

Pick any of the **13 LLM providers** for both food analysis and the Coach chat. Free Gemini keys are available at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).

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

Pick how voice input is transcribed. Native iOS is the default — free, on-device, real-time.

| Provider | Notes |
|----------|-------|
| Native iOS (On-Device) | Free, offline on modern iPhones, real-time partial results |
| OpenAI Whisper | Whisper-1 via `/v1/audio/transcriptions` |
| Groq (Whisper) | Whisper-large-v3, very fast, has a free tier |
| Deepgram | Nova-3, fast and accurate |
| AssemblyAI | Universal model, strong accuracy, free tier |

All API keys are stored in **iOS Keychain** — encrypted, on-device only.

## How It Works

```
Photo / Text / Voice
        │
        ▼
  AI Provider API  ──▶  JSON nutrition response
        │
        ▼
  User reviews & edits
        │
        ▼
  FoodStore.addEntry()  ──▶  UserDefaults (local) + Apple Health (optional)
```

For the Coach chat, every turn builds a fresh system prompt from your live profile, BMR formula in use, computed forecast, last 10 weights, and last 7 days of calorie totals, then sends it along with the conversation history to your selected LLM.

## Screenshots

A seven-step walkthrough of the app's core flow — from opening the dashboard to reviewing long-term trends.

<table>
  <tr>
    <td align="center" width="33%">
      <img src="screenshots/home.png" width="230" alt="Home dashboard">
      <br><br>
      <b>01 · Home · Dashboard</b>
      <br>
      <sub>Daily calorie ring, macro bars (P&nbsp;/&nbsp;C&nbsp;/&nbsp;F), and today's logged meals grouped by meal type. Week strip at the top for date navigation.</sub>
    </td>
    <td align="center" width="33%">
      <img src="screenshots/logging.png" width="230" alt="Logging options menu">
      <br><br>
      <b>02 · Log · Options</b>
      <br>
      <sub>Tap + to open the entry menu: Camera, Camera + Note, Nutrition Label scan, From Photos, Text Input, Voice, or Saved Meals.</sub>
    </td>
    <td align="center" width="33%">
      <img src="screenshots/snap.png" width="230" alt="Snap food capture">
      <br><br>
      <b>03 · Snap · Capture</b>
      <br>
      <sub>Point and shoot. The image is sent to your chosen AI provider; nutrition estimates come back within a few seconds.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="screenshots/review.png" width="230" alt="Review food entry">
      <br><br>
      <b>04 · Review · Edit</b>
      <br>
      <sub>Review the AI's guess, adjust the serving size (everything recalculates live), and pick a meal type before logging.</sub>
    </td>
    <td align="center" width="33%">
      <img src="screenshots/meals.png" width="230" alt="Meals log">
      <br><br>
      <b>05 · Meals · Log</b>
      <br>
      <sub>The day's entries grouped by breakfast / lunch / dinner / snack. Swipe to delete, tap to edit any entry.</sub>
    </td>
    <td align="center" width="33%">
      <img src="screenshots/coach.png" width="230" alt="AI Coach chat">
      <br><br>
      <b>06 · Coach · AI Chat</b>
      <br>
      <sub>Multi-turn conversation with full context of your profile, weight history, food log, and forecast. Ask "what should I eat?" or "expected weight in 30 days?".</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%" colspan="3">
      <img src="screenshots/progress.png" width="230" alt="Progress charts">
      <br><br>
      <b>07 · Progress · Charts</b>
      <br>
      <sub>Weight trend with goal line, calorie history (intake vs. goal), and macro averages. Time ranges span 1 week to all time.</sub>
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
| **Health** | HealthKit read/write (body measurements + 12 nutrition types) with background observers, UUID-tagged samples for safe delete |
| **Pattern** | `@Observable` + `.environment()`, main actor isolation |
| **Localization** | `Localizable.xcstrings` (String Catalog), 15 languages, auto-selected by iPhone's system language |
| **Dependencies** | Zero |

### Source Layout

```
calorietracker/
├── calorietrackerApp.swift       # Entry point, environment setup
├── ContentView.swift             # 5-tab layout (Home, Progress, Coach, Settings, About)
├── Localizable.xcstrings         # String Catalog, 15 languages
├── Models/
│   ├── AIProvider.swift          # 13 LLM providers, model lists, settings
│   ├── SpeechProvider.swift      # 5 STT providers + Keychain settings
│   ├── ChatMessage.swift         # Coach chat message model
│   ├── UserProfile.swift         # BMR/TDEE/macro calculations
│   ├── FoodEntry.swift           # Food item with 13 nutrients
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
│   ├── SpeechService.swift       # Remote STT router (OpenAI / Groq / Deepgram / AssemblyAI)
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

# Build
xcodebuild -scheme calorietracker \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Open in Xcode, select your device, and run. On first launch, go to **Settings → AI Provider** to set your provider and API key. A free Gemini key is available at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines. Bug reports and feature requests welcome.

Adding a new translation? Open `calorietracker/Localizable.xcstrings` in Xcode and fill in your language column — everything else is already wired.

## Security

See [SECURITY.md](SECURITY.md). Use [private vulnerability reporting](https://github.com/apoorvdarshan/fud-ai/security/advisories/new) for sensitive issues.

## Privacy

All data stays on your device. No accounts, no cloud sync, no analytics. API keys are stored in iOS Keychain. **Delete All Data** is local-only — it wipes the app's storage (food log, weight log, profile, Coach chat, API keys, widget snapshot) but never touches Apple Health. Samples you've synced are yours; if you want them cleaned up, do it from Health → Sources → Fud AI. See [Privacy Policy](https://fud-ai.app/privacy.html).

## License

MIT License. See [LICENSE](LICENSE).

## Contact

- **Developer:** Apoorv Darshan
- **Email:** apoorv@fud-ai.app or ad13dtu@gmail.com
- **Follow on X:** [@apoorvdarshan](https://x.com/apoorvdarshan)
- **Report an Issue:** [github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20)
- **Request a Feature:** [github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20)

## Support the Project

Fud AI is fully free, open source, and privacy-first. If it helps you, consider supporting development — every bit keeps this project alive.

[![PayPal](https://img.shields.io/badge/PayPal-Donate-blue?logo=paypal)](https://paypal.me/apoorvdarshan)
[![Product Hunt](https://img.shields.io/badge/Product%20Hunt-Vote-orange?logo=producthunt)](https://www.producthunt.com/products/fud-ai-calorie-tracker)

You can also help by [voting on Product Hunt](https://www.producthunt.com/products/fud-ai-calorie-tracker), [starring the repo](https://github.com/apoorvdarshan/fud-ai), [filing bugs](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20), or [requesting features](https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20).

## Contributors

Thanks to everyone who has contributed to making Fud AI better:

<a href="https://github.com/apoorvdarshan/fud-ai/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=apoorvdarshan/fud-ai" alt="Contributors" />
</a>
