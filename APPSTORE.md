# App Store Listing

App Store listing source for Fud AI 9.0 on iPhone, iPad, and Apple Watch. App Store Connect is the source of truth for submission status.

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
Meet Flash, a new look for Fud AI. Talk to Brulee to log meals, track progress, and make your next meal count.
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
Track calories and macros by taking a photo, scanning a barcode, typing, or talking to Brulee. Review the nutrition estimates before saving a meal.

NEW IN 9.0: FLASH
A new look for Home, the food diary, settings, Home Screen widgets, and the Apple Watch dashboard and complications. Join optional Daily or Monthly Challenges alongside the Weekly Challenge.

LIVE VOICE
Tap the orb on Home and tell Brulee what you ate or ask how you're tracking against your goals. Start Live Voice from Siri, the Action Button, or Control Center on supported devices. The food camera can use AR to help estimate portion sizes.

Nutrition includes custom goals plus caffeine, creatine, and other performance compounds. Optional fasting adds timers, editable history, goals, alerts, and Coach context. Apple Watch can log water; import Watch workouts from HealthKit when Health is enabled.

Free, open source, privacy-first. BYOK stays free forever; optional Plus/Pro hosted AI is a convenience. No ads; optional tips support development.

LOG MEALS
Use Camera or Photos (up to 10 images and a note), Barcode, Voice, Text, Manual Entry, Saved Meals, Copy from Day, Siri, or the Log Food widget. Choose which food actions appear in Home +.

AI ACCESS
Bring Your Own Key supports Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Alibaba Qwen, Ollama, or any OpenAI-compatible endpoint. Keys stay in iOS Keychain. Optional Plus/Pro hosted AI is available without your own key.

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
No account required for core tracking, and no ads or behavioral tracking. Your own AI keys stay in Keychain. Challenges are optional and use a display name and aggregate scores, not raw food, water, workout, Health, or weight records. If you use Hosted AI, request content passes through Fud AI's proxy. MIT licensed.

APPLE HEALTH
Optional sync for nutrition, weight, height, body fat, and calculated workout calories. Daily steps can appear on Home. External samples can auto-import. Apple Watch workouts can import as read-only history. Energy Burn Goals can use active/total energy. Fasting is local-only.

Fud AI is not medical advice — consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

## What's New (v9.0)
```
Fud AI 9.0 introduces Flash, a new look for Home, the food diary, and settings.
• Home Screen widgets, Apple Watch water logging, and watch-face complications now match the new design.
• Join Daily or Monthly Challenges separately, alongside the Weekly Challenge.
• Find available App Store updates in About → App & Updates.
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
- `com.apoorvdarshan.calorietracker.pro.monthly` (~$19.99)
- `com.apoorvdarshan.calorietracker.pro.yearly` (~$179.99)

**Credit packs (consumables):**
- `com.apoorvdarshan.calorietracker.credits.50` ($1.99)
- `com.apoorvdarshan.calorietracker.credits.150` ($4.99)
- `com.apoorvdarshan.calorietracker.credits.400` ($9.99)

**Tips (unchanged on iOS):** `…tip.snack`, `…tip.proteinshake`, `…tip.lunch`, `…tip.feast`

Free forever = full app + BYOK. Hosted AI is optional on iOS; no free hosted quota.

## Reviewer Notes
```
Fud AI 9.0 supports iPhone and iPad, with a companion Apple Watch app. This update brings the Flash design to Home, the food diary, Settings, widgets, and Apple Watch.

Core review path: There is no Fud AI account or required purchase. Complete onboarding, then use Home → + → Manual Entry or Barcode to log a meal without an AI key. Open the food diary and Progress to see the result. The app has no ads and does not request tracking.

AI review path: Open Settings → AI Access (also available in onboarding), choose Google Gemini, keep the default model, and enter a valid Gemini API key. If a review key is supplied in App Review Information, paste it into the in-app API Key field; it is a provider key, not a Fud AI account password. Try photo or text meal analysis, or open the orb on Home to try Brulee Live Voice after granting microphone access. Live Voice uses a separate live model and requires provider access to that model. For example, say “I had a bowl of oatmeal and a banana.”

Optional purchases: Plus/Pro Hosted AI subscriptions and credit packs are available from AI Access or the Hosted AI onboarding path; the Tip Jar is optional and unlocks nothing. Core tracking and the bring-your-own-key option do not require a subscription.

Optional Challenges: Open the Challenge card in Progress. The Weekly, Daily, and Monthly challenges have separate opt-ins. Participation requires confirming age 18+ and accepting the Community Rules. Leaderboards receive a public display name and aggregate scores, not raw meal, workout, or Apple Health records. Participants can report or block other users and leave to delete their remote challenge data.

Optional Health and fasting: Apple Health is opt-in. Fasting can be enabled in Settings and started from Home → +. Fasting is local-only and can be ended or cancelled.
```
