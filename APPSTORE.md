# App Store Listing

App Store Connect submission details for Fud AI v6.1 build 34. Each field is in a code block for easy copy-paste.

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
New in 6.1: smarter multi-photo meals, serving calculator, nutrient goals, fasting, chart inspection, diary import, shortcuts, and Watch water logging.
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

NEW in v6.1: progressive multi-photo analysis can follow ingredients added to the same plate, analyzed meals include editable ingredient breakdowns, and serving fields now include a quick calculator. You can also import Fud AI diary JSON, configure Home Screen quick actions, inspect exact values on long-range weight charts, and preview exercises before adding them.

Nutrition tracking now includes custom goals, caffeine, creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB. Optional fasting adds persistent timers, editable history, custom goals, local alerts, and explicit AI Coach context. Apple Watch can now log water directly.

Fud AI is free, open source, privacy-first, and bring-your-own-key. There are no ads; optional tips support development.

LOG MEALS
Use Camera or Photos (up to 10 images and a note), Barcode, Voice, Text, Manual Entry, Saved Meals, Copy from Day, or Siri shortcuts.

AI ACCESS
Bring Your Own Key supports Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama, or any OpenAI-compatible endpoint. Keys stay in iOS Keychain.

6 SPEECH-TO-TEXT OPTIONS
Native iOS, Gemini Audio, OpenAI Whisper, Groq, Deepgram, and AssemblyAI, with selectable language handling.

REVIEW BEFORE LOGGING
Unlock Nutrition to correct calories, macros, and detailed nutrients. What if? previews daily macro impact before saving.

COACH
Multi-turn chat can access your profile, targets, forecast, food log, workouts, and explicitly logged fasts when requested. It never assumes a missing meal means you fasted.

WORKOUTS
Plan by day and log sets, reps, weight, and RPE without a timer. Swipe weeks, estimate calorie burn, and review history in Progress. The 873-exercise photo library includes muscle/equipment filters, search, sorting, and details.

EXPANDED NUTRIENTS
Track macros plus fiber, sugar, fats, sodium, minerals, vitamins, folate, omega-3, and more when available.

PERSONALIZED GOALS
BMR and TDEE calculators, six activity levels, automatic or editable macros, nutrient goals, meal-time boundaries, and Adaptive Goals.

WIDGETS
Home Screen Fud AI widgets in Small, Medium, and Large; a small Protein widget; a separate Water widget for Home and Lock Screen; and calorie/macro Lock Screen widgets. Apple Watch and complications show calories and macros, plus compact water progress when enabled.

OPTIONAL WATER TRACKING
Off by default. Set your own daily goal, quick-log common glass amounts or a custom amount, view progress under calories, schedule a local reminder, and use the dedicated Water widget. Water history stays on your device and is not written to Apple Health.

OPTIONAL FASTING TRACKING
Off by default. Choose a 1–168 hour goal, start/end/cancel from the Home + menu, keep the timer across app restarts, optionally receive a local goal alert, and edit completed sessions. Fasting history stays on your device and is not written to Apple Health.

PRIVACY
No conventional account or sign-in, analytics, behavioral tracking, or ads. BYOK keys stay in Keychain. The optional 18+ Weekly Challenge sends a chosen display name, at most one X or Instagram handle, and seven-day aggregate habit scores to Fud AI; it never sends raw food, water, workout, Health, body-weight, or weight-loss records. Apple Intelligence can process eligible descriptions on-device. Barcode sends only its number to Open Food Facts. MIT licensed.

APPLE HEALTH
Optional sync for nutrition, weight, height, body fat, and calculated workout calories. External samples can auto-import or restore after reinstall. Energy Burn Goals can use active/total energy. Fasting is local-only. Permissions remain manageable in Health.

Fud AI is not medical advice — consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

## What's New (v6.1)
```
Fud AI 6.1 adds smarter meal review, deeper nutrient tracking, faster editing, and new progress tools.

NEW
• Progressive Meal mode follows ingredients added across up to 10 photos of the same plate.
• AI meals now include editable ingredient breakdowns, and Manual Entry includes fiber.
• Calculate serving amounts with +, −, ×, and ÷ directly from the quantity keyboard.
• Set custom goals for expanded nutrients, including caffeine and performance compounds such as creatine, beta-alanine, citrulline, carnitine, arginine, taurine, betaine, and HMB.
• Inspect exact weight values by dragging across the graph, with smoother long-range performance.
• Preview an exercise before adding it; two cable forearm movements have joined the library.
• Import a previously exported Fud AI food-diary JSON file with duplicate protection.
• Choose which logging actions appear in Home Screen quick actions.
• Log water directly from Apple Watch.
• Optional fasting includes a persistent timer, custom 1–168 hour goal, editable history, local goal alert, and explicit AI Coach context.

IMPROVED
• Reduced unnecessary follow-up AI requests when serving units are already usable.
• Number-pad Done controls and keyboard dismissal are now reliable across meal editing screens.
• Meal-photo sheets, analysis notes, toolbar styling, and quick-action routing were refined.
• Fasting remains separate from calories, macros, and Apple Health.
• Reliability, privacy-documentation, and dependency security updates.

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
4) The Fud AI Premium subscription from v4.4 has been removed in this version. The app is fully free; no paywall exists.
5) In-app purchases are 4 optional consumable tips (Settings → About → Tip Jar). They unlock nothing — voluntary support only:
   • com.apoorvdarshan.calorietracker.tip.snack — $0.99
   • com.apoorvdarshan.calorietracker.tip.proteinshake — $2.99
   • com.apoorvdarshan.calorietracker.tip.lunch — $4.99
   • com.apoorvdarshan.calorietracker.tip.feast — $9.99
   Purchases go through StoreKit via RevenueCat, so App Store sandbox purchase testing is available.
6) The app shows no ads and does not request tracking. In-app purchases are the 4 optional consumable tips above; nothing else is monetized.
7) To review intermittent fasting: Settings → Fasting Tracking, enable it, choose a goal, then use Home → + → Start Fast. It is local-only, does not modify nutrition totals, and can be ended or cancelled at any time.
8) To review the optional Weekly Challenge: Progress → Weekly Challenge. Before enrollment, no leaderboard data is fetched. Enrollment requires explicit 18+ and Community Rules confirmations, a 2–40 character public display name, and optionally exactly one X or Instagram handle. The disclosure shows the exact weekly aggregate payload. Joined users can report or locally block every other participant and manage blocked participants. “Leave & Delete Remote Data” deletes the public profile and scores; Settings → Delete All Data also attempts remote deletion first and safely retries after a connection failure. No date of birth or raw health/food/workout records are uploaded.
```
