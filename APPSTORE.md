# App Store Listing

App Store Connect submission details for Fud AI v7.0 build 35. Each field is in a code block for easy copy-paste.

## App Name
```
Fud AI - Calorie Tracker
```

## Subtitle (30 chars max)
```
Macros, AI Coach & Workouts
```

## Promotional Text (170 chars max)
```
New in 7.0: optional Plus/Pro hosted AI, daily steps, Walk/Run logging, custom exercises, meal date/time, Log Food widget, Czech & Ukrainian.
```

## Keywords (100 chars max)
```
calorie,tracker,nutrition,macro,AI,food,scanner,fasting,protein,weight,bodyfat,health,fitness,meal
```

## Category
```
Primary: Health & Fitness
Secondary: Food & Drink
```

## Description
```
Effortless calorie tracking with AI-powered food recognition. Snap, scan, speak, or type a meal — get instant calories, macros, and nutrients.

NEW in v7.0: optional Plus/Pro hosted AI or Bring Your Own Key; daily steps and burn/deficit on Home; Walk/Run logging; custom exercises with set autofill; meal date/time on review; configurable Home + and Log Food widget; pinch-zoom photos; Czech and Ukrainian (18 languages).

Nutrition includes custom goals plus caffeine, creatine, and other performance compounds. Optional fasting adds timers, editable history, goals, alerts, and Coach context. Apple Watch can log water; import Watch workouts from HealthKit when Health is enabled.

Free, open source, privacy-first. BYOK stays free forever; optional Plus/Pro hosted AI is iOS convenience only. No ads; optional tips support development.

LOG MEALS
Use Camera or Photos (up to 10 images and a note), Barcode, Voice, Text, Manual Entry, Saved Meals, Copy from Day, Siri, or the Log Food widget. Choose which food actions appear in Home +.

AI ACCESS
Bring Your Own Key supports Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama, or any OpenAI-compatible endpoint. Keys stay in iOS Keychain. Optional Plus/Pro hosted AI is available without your own key.

6 SPEECH-TO-TEXT OPTIONS
Native iOS, Gemini Audio, OpenAI Whisper, Groq, Deepgram, and AssemblyAI, with selectable language handling.

REVIEW BEFORE LOGGING
Unlock Nutrition to correct calories, macros, and detailed nutrients. What if? previews daily macro impact before saving.

COACH
Multi-turn chat can access your profile, targets, forecast, food log, workouts, and explicitly logged fasts when requested. It never assumes a missing meal means you fasted.

WORKOUTS
Plan by day and log sets, reps, weight, and RPE without a timer. Create custom exercises, autofill sets from lift history, and quick-log Walk/Run. The 877-exercise library includes filters, search, sorting, and details.

EXPANDED NUTRIENTS
Track macros plus fiber, sugar, fats, sodium, minerals, vitamins, folate, omega-3, and more when available.

PERSONALIZED GOALS
BMR and TDEE calculators, six activity levels, automatic or editable macros, nutrient goals, meal-time boundaries, and Adaptive Goals.

WIDGETS
Home Screen Fud AI widgets in Small, Medium, and Large; Protein and Water widgets; calorie/macro Lock Screen widgets; Log Food widget. Apple Watch shows calories, macros, and water when enabled.

OPTIONAL WATER TRACKING
Off by default. Set a daily goal, quick-log glasses or a custom amount, schedule a reminder, and use the Water widget. Water stays on-device and is not written to Apple Health.

OPTIONAL FASTING TRACKING
Off by default. Choose a 1–168 hour goal, start/end/cancel from Home +, keep the timer across restarts, optional local alerts, and edit completed sessions. Fasting stays on-device and is not written to Apple Health.

PRIVACY
No account, analytics, behavioral tracking, or ads. BYOK keys stay in Keychain. The optional 18+ Weekly Challenge sends a display name, at most one X or Instagram handle, and seven-day aggregate habit scores—never raw food, water, workout, Health, or weight records. Hosted AI (if used) sends only the request content through Fud’s proxy. MIT licensed.

APPLE HEALTH
Optional sync for nutrition, weight, height, body fat, and calculated workout calories. Daily steps can appear on Home. External samples can auto-import. Apple Watch workouts can import as read-only history. Energy Burn Goals can use active/total energy. Fasting is local-only.

Fud AI is not medical advice — consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

## What's New (v7.0)
```
Fud AI 7.0 brings optional hosted AI on iPhone, richer workouts and Health insights, and two new languages.

NEW
• Choose Bring Your Own Key or optional Plus/Pro hosted AI during onboarding. Hosted uses a daily action pool plus credit packs; free forever stays fully usable with your own keys.
• See daily steps from Apple Health on Home, plus a clearer burn/deficit line. Adaptive Goals is on by default for new setups.
• Import Apple Watch workouts from HealthKit into Workouts and Progress.
• Log Walk/Run from the Workouts menu; create custom exercises; smarter search; set autofill from lift history with Coach-aware training context.
• Set meal log date and time on the review sheet; configure which actions appear in Home +; add the Log Food widget.
• Pinch-zoom meal photos and optionally save them to Photos after a successful log.
• Czech and Ukrainian join the app — 18 languages.
• Optional iCloud backup gains clearer restore guidance alongside Google Drive on Android.

IMPROVED
• Faster Workouts open and smoother meal and Coach photo thumbnails.
• More reliable Health writes, diary safety guards, and exercise demos that load on demand.
• Community links for Discord and Instagram in Settings.
• Reliability, privacy-documentation, and security updates.

Existing logs, goals, Health data, widgets, workout history, and BYOK settings are preserved during a normal update.
```

## Privacy URL
```
https://fud-ai.app/privacy.html
```

## Terms URL
```
https://fud-ai.app/terms.html
```

## App Privacy (App Store Connect)

The optional Weekly Challenge introduces first-party server collection. Update the App Privacy answers before submission; all of these are linked to the pseudonymous challenge participant ID, used only for App Functionality, and not used for tracking:

- Contact Info → Name: the chosen public challenge display name.
- Identifiers → User ID: the generated participant ID and optional X or Instagram handle.
- Health & Fitness → Health: weekly aggregate nutrition, consistency, and hydration qualifying-day counts only.
- Health & Fitness → Fitness: weekly activity qualifying-day count and capped aggregate activity calories only.
- User Content → Other User Content: an optional moderation-report explanation typed by the reporter.

Do not declare raw meals, food names, water entries, workout details, Apple Health records, body weight, weight loss, date of birth, contacts, precise location, analytics, advertising data, or tracking for this feature; the challenge does not transmit them.

## Support URL
```
https://fud-ai.app
```

## Marketing URL
```
https://fud-ai.app
```

## v7 Hosted AI products (Plus / Pro / credits) — iOS

See `docs/HOSTED_AI_REVENUECAT.md` for the RevenueCat + App Store Connect checklist.

**Scope:** iOS only in this release. Android remains BYOK + Ko-fi (no Play hosted billing yet).

**Entitlements:** `plus`, `pro` (Pro includes Plus)

**Subscriptions:**
- `com.apoorvdarshan.calorietracker.plus.monthly` (~$8.99)
- `com.apoorvdarshan.calorietracker.plus.yearly` (~$69.99)
- `com.apoorvdarshan.calorietracker.pro.monthly` (~$17.99)
- `com.apoorvdarshan.calorietracker.pro.yearly` (~$149.99)

**Credit packs (consumables):**
- `com.apoorvdarshan.calorietracker.credits.50` ($1.99)
- `com.apoorvdarshan.calorietracker.credits.150` ($4.99)
- `com.apoorvdarshan.calorietracker.credits.400` ($9.99)

**Tips (unchanged on iOS):** `…tip.snack`, `…tip.proteinshake`, `…tip.lunch`, `…tip.feast`

Free forever = full app + BYOK. Hosted AI is optional on iOS; no free hosted quota.

## Reviewer Notes
```
1) iPhone only — not optimized for iPad. Please review on iPhone.
2) There is no conventional user account or sign-in — the app is privacy-first and local-first. AI features use a "bring your own key" (BYOK) provider key. For review, a working Google Gemini API key is provided in App Review Information (entered in the Sign-In password field). To enable all AI features: on the "Set Up Your AI" onboarding step (or Settings → AI Access), Provider = Google Gemini, Model = gemini-3.5-flash-lite (the default), paste the provided key into API Key, then Accept & Continue.
3) With the key set, all AI features work immediately — Camera/Photos multi-image analysis, Text/Voice logging, "What if?" preview, and AI Coach chat. Logging any meal fills the food log, Home dashboard, and Progress charts; no account or Fud AI server data is needed. (A free Gemini key can also be created at https://aistudio.google.com/apikey.)
4) The legacy Fud AI Premium subscription from v4.4 remains discontinued. Core tracking is free forever with Bring Your Own Key — there is no required paywall.
5) Optional monetization on iOS (StoreKit via RevenueCat; sandbox testing available):
   Tip Jar (consumables; unlock nothing):
   • com.apoorvdarshan.calorietracker.tip.snack — $0.99
   • com.apoorvdarshan.calorietracker.tip.proteinshake — $2.99
   • com.apoorvdarshan.calorietracker.tip.lunch — $4.99
   • com.apoorvdarshan.calorietracker.tip.feast — $9.99
   Optional Plus/Pro hosted AI subscriptions and credit packs (Settings → AI Access / onboarding Hosted path). Free users stay on BYOK with no hosted quota. Product IDs are listed under “v7 Hosted AI products” above.
6) The app shows no ads and does not request tracking.
7) To review intermittent fasting: Settings → Fasting Tracking, enable it, choose a goal, then use Home → + → Start Fast. It is local-only, does not modify nutrition totals, and can be ended or cancelled at any time.
8) To review the optional Weekly Challenge: Progress → Weekly Challenge. Before enrollment, no leaderboard data is fetched. Enrollment requires explicit 18+ and Community Rules confirmations, a 2–40 character public display name, and optionally exactly one X or Instagram handle. The disclosure shows the exact weekly aggregate payload. Joined users can report or locally block every other participant and manage blocked participants. “Leave & Delete Remote Data” deletes the public profile and scores; Settings → Delete All Data also attempts remote deletion first and safely retries after a connection failure. No date of birth or raw health/food/workout records are uploaded.
```
