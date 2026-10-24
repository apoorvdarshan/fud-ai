# Play Store Listing

Google Play Console listing copy for Fud AI Android v8.0 / versionCode 44. Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over. This file is prepared for the 8.0 release (Live Voice, workout rest timer and routines, Health Connect workout import, Short or Direct share links) and has not been submitted. Android 7.2 / versionCode 42 is the live Play release; 7.2.1 / versionCode 43 was tagged but never uploaded to Play.

**Pending Play Console updates**
- [ ] **Before the next Android release:** update the Health Connect declaration in Play Console to include **Exercise (READ)** — paste the `READ_EXERCISE` rationale from §7 *Health apps declaration*, then re-check the §7 Data safety notes (imported workouts stay on-device).

**Where to paste each field in Play Console:**
- App name / Short description / Full description → Grow → Store presence → **Main store listing** (default English) and Grow → Store presence → **Custom store listings** → Manage translations (per-language overrides)
- What's new → **Releases → Production / Closed testing → Create new release → Release notes** field (paste the entire `<lang-tag>` block; Play Console parses tags automatically)

---

## 1. App Name

**30 char hard cap per language.** Brand name stays as `Fud AI` untranslated; the descriptor after the dash is what gets localized. English-only on Play Console — non-English Play Store browsers see the English source as fallback.

### English (en-US) — 24 chars
```
Fud AI - Calorie Tracker
```

---

## 2. Short Description

**80 char hard cap per language. Cannot include price/promotion keywords ("free", "discount", "sale", "best", "#1", etc.) — Play Console will block promotion of the listing.** Live Play Store currently has "Snap, speak, or type a meal. AI logs the calories. Free & open source." which triggers the warning; replacement below drops "Free" while keeping the same rhythm. English-only on Play Console — non-English Play Store browsers see the English source as fallback.

### English (en-US) — 63 chars
```
Snap, speak, or type a meal. AI logs the calories. Open source.
```

---

## 3. Full Description

**4000 char hard cap per language.** This is the long-form "About this app" copy. English-only on Play Console — non-English Play Store browsers see the English source as fallback (deliberate decision; the in-app UI is fully translated via per-locale `values-{lang}/strings.xml` so users still get a localized experience once installed).

### English (en-US)
```
Fud AI makes calorie tracking effortless with AI-powered food recognition. Snap a photo, scan a barcode, speak it, or type it — get instant nutrition: calories, protein, carbs, fats, vitamins, minerals, and more.

NEW in v8.0: Live Voice. Talk to Fud AI to log meals, scan barcodes and check progress. Also: workout rest timer and routines, Weekly Recap, Steps, kcal or kJ, share links, and Qwen.

Nutrition adds custom goals plus caffeine, creatine, and other performance compounds. Optional fasting adds timers, editable history, goals, alerts, and Coach context. Android includes a daily calorie summary and clearer Health Connect guidance.

Open source, privacy-first. Bring your own API key, or pick an optional Plus/Pro plan.

WAYS TO LOG A MEAL
• Camera — take up to 10 photos, add an optional note
• Photos — import up to 10 images, add an optional note
• Barcode — Open Food Facts lookup
• Voice — 6 STT engines
• Text — describe it, AI parses it
• Manual Entry
• Saved Meals — recents, frequent, favorites
• Copy from Day — copy meals from another date

AI PROVIDERS
Use Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together, Hugging Face, Fireworks, DeepInfra, Mistral, Alibaba Qwen, Ollama, or an OpenAI-compatible endpoint.

6 SPEECH-TO-TEXT ENGINES
Native Android, Gemini, OpenAI Whisper, Groq, Deepgram, or AssemblyAI, with automatic or fixed language handling.

COACH
Multi-turn chat can access profile, goals, food log, progress, workouts, and logged fasts when requested. It never assumes a missing meal means you fasted. Images supported.

REVIEW BEFORE LOGGING
Unlock Nutrition to correct calories, macros, and detailed nutrients before saving; serving changes then scale from your edits. What if? previews today's macro impact and can ask AI for a suggestion.

WORKOUTS
Plan by day; log sets, reps, weight, and RPE. Create custom exercises, autofill sets, and quick-log Walk/Run. Browse 877 exercises with photos, filters, search, sorting, and details.

PERSONALIZED GOALS
BMR and TDEE calculators, six activity levels, automatic or editable macro targets, and customizable meal-time boundaries.

OPTIONAL NUTRIENT GOALS
Set expanded nutrient goals separately from the macro calculator — fiber, sugar, fats, sodium, vitamins, minerals, and more. Use AI Estimate or set manually.

WIDGETS
Separate Calorie, Protein, Today, and Water widgets in the Home speedometer style. They refresh from local snapshots when you log.

OPTIONAL WATER TRACKING
Off by default. Set a daily goal, quick-log glasses or a custom amount, view progress below calories, schedule a reminder, and use the Water widget. Water stays on-device and is not sent to Health Connect.

OPTIONAL FASTING TRACKING
Off by default. Choose a 1–168 hour goal, start/end/cancel from Home +, keep the timer across restarts, optional local alerts, and edit completed sessions. Fasting stays on-device and is not sent to Health Connect.

19 LANGUAGES
Auto-selected by phone language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese, Hindi, Arabic, Romanian, Azerbaijani, Polish, Czech, Ukrainian, Turkish.

PRIVACY FIRST
Core tracking needs no account; no analytics, behavioral tracking, or ads. The optional 18+ Weekly Challenge uploads only a display name, optional social handle, anonymous ID, and weekly aggregate milestones—never raw food, weight, meals, workouts, or Health Connect records. Keys encrypted; AI/STT requests go to your provider, or to Fud AI’s server on Plus/Pro. MIT licensed.

HEALTH CONNECT
Optional sync for nutrition, weight, body fat, and calculated workout calories, plus energy and daily step reads for Home and goal estimates. Records can restore after reinstall. Fasting is local-only.

NOTE: Not medical advice. Estimates are AI-generated; consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

### Other languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, cs-CZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pl-PL, pt-BR, ro, ru-RU, tr-TR, uk, zh-CN) see the English source as fallback. The app includes 19 localized interfaces; newer strings may temporarily use the English fallback.

---

## 4. What's New (v8.0 / versionCode 44 — being released)

**500 char hard cap per language.** Only the `<en-US>` block is kept: store-release uploads every `<locale>` block in this fence, and Play shows the English notes to every other language. Add a translated block only when it describes this release.

```
<en-US>
Fud AI 8.0
• Live Voice: talk to Fud AI to log meals, scan barcodes and check progress. Included with Plus and Pro, or use your own Gemini, OpenAI, xAI or Qwen key.
• Workouts: rest timer, routines, Health Connect import.
• Progress: Weekly Recap, Steps, and past periods.
• Share meals as a short or direct link.
• Energy in kcal or kJ.
• Welcome back: restore your Drive backup and Health Connect data.
• Camera: sharper close-ups and tap to focus.
• Alibaba Qwen; the app is now in Turkish.
</en-US>
```

## 5. Categorization

```
App category: Health & Fitness
Tags: Calorie tracker, Nutrition, AI, Food tracker
```

## 6. Contact details

```
Email: apoorv@fud-ai.app
Phone: (omit — optional, US-only enforcement)
Website: https://fud-ai.app
Privacy policy: https://fud-ai.app/privacy.html
```

## 7. App content declarations

These are one-time setup in Play Console → Policy → App content. Don't drift from these answers across submissions:

- **Privacy policy URL**: https://fud-ai.app/privacy.html
- **Sign in details / App access**: Yes — some functionality is restricted (BYOK Gemini for AI). Console-only (not available via Play API). Path: Policy and programs → App content → Actioned → Sign in details → Manage. Keep the declaration in sync with the paste block below (and the same review key used in App Store Connect Sign-In password).
- **Ads**: No — v3.0.3 removed the AdMob banner and the ads SDK entirely. Set "contains ads" to No, and set the Advertising ID declaration to No (the `AD_ID` permission is gone from the manifest).
- **Content rating**: Everyone (E)
- **Target audience**: 13+
- **News app**: No
- **COVID-19 contact tracing**: No
- **Data safety**: Core tracking has no Fud AI account, analytics, advertising, or behavioral tracking. Do not declare Advertising ID. Most app data, including fasting history, is local, and API keys are stored in EncryptedSharedPreferences. The optional 18+ Weekly Challenge is a first-party collection for app functionality: declare **Personal info → Name** (the chosen display name), **Personal info → User IDs** (the random participant ID and optional X or Instagram handle), and **Health and fitness → Fitness info** (weekly aggregate activity calories and qualifying activity, nutrition, consistency, and hydration day counts). These fields are optional to collect because joining is optional, encrypted in transit, not shared with third parties, and deletable from Leave Challenge or Delete All Data. No date of birth is read or uploaded; the age gate stores only acceptance. Do not declare raw food names, meals, weight, workout details, or Health Connect records as challenge-backend collection because those never leave the device for this feature. User-initiated AI/STT requests send selected photos/text/audio directly to the configured provider; Coach requests may include explicitly logged fasting context when relevant; barcode lookup sends the barcode to Open Food Facts, and only when the user adds their own USDA FoodData Central API key (Settings → Product information, off by default) are barcodes that Open Food Facts and saved labels cannot match sent directly from the device to USDA FoodData Central, with the key only in the X-Api-Key header (a user-initiated lookup like the Open Food Facts one; the key stays in EncryptedSharedPreferences); optional shared-meal links place selected meal data in the URL; optional Health Connect sync reads/writes the declared health types. **Optional Exercise read (`READ_EXERCISE`):** exercise sessions other apps wrote to Health Connect (activity type, start/end time, duration, lap distance, active calories over the session, recording app and device names) are read and kept on the device only, for read-only display in Workouts and Progress. They are never uploaded to Fud AI, a server, an AI provider, or Fud AI's optional Google Drive backup, are not shared, and are not used for advertising; they are removed from the app the next time it opens after Exercise access (or all Health Connect access) is revoked, and by Delete All Data. Because nothing leaves the device, this adds no new Data safety collection or sharing; keep Health Connect exercise data out of the challenge-backend declaration as well. Complete the Play form according to Google's current definitions for these direct user-initiated transfers rather than broadly claiming that no data is processed. **7.2 billing:** declare **Financial info → Purchase history** (RevenueCat processes Google Play purchases for Plus/Pro, credit packs, and tips; collected, not optional for purchasers, app functionality, not shared for advertising) and **Device or other IDs** for the anonymous RevenueCat app user ID (app functionality; Fud AI's Worker stores only its SHA-256 hash in the hosted-AI quota ledger). In Hosted AI mode (optional, Plus/Pro only) food photos, prompt text, voice audio, and Coach messages go to Fud AI's server (fud-ai.app), which forwards them to Gemini/Deepgram and does not store them: declare **Photos**, **Audio → Voice or sound recordings**, and **App activity → Other user-generated content** as collected, optional, processed ephemerally, for app functionality. Network requests use HTTPS except a user-configured local/custom endpoint may use the URL the user supplies. Delete All Data removes local app data and first requests deletion of the remote challenge profile; if offline, the encrypted deletion credential is retained and deletion retries on the next launch. It does not delete Health Connect records.
- **Government app**: No
- **Financial features**: No
- **Health features**: Yes — nutrition, body measurements, energy-based goals, calculated workout calories, optional daily step reads for Home, optional local water/fasting tracking, and an optional 18+ Weekly Challenge. Challenge qualification is calculated locally and uploads only weekly totals: overall points, qualifying activity/nutrition/consistency/hydration day counts, and activity calories capped at 2,000 per day; it never uploads raw logs or ranks weight loss. Health Connect permissions are READ/WRITE nutrition, weight, body fat, and active calories burned, plus READ total calories burned, steps, and exercise. Exercise read is optional: it imports workouts other apps (Garmin Connect, Coros, Strava, Samsung Health, Fitbit, …) wrote to Health Connect as read-only history in Workouts and Progress, and the app works fully without it. Water and fasting history are local and are not written to Health Connect. Explain restore/backfill, Energy Burn Goals, calculated workout-burn sync, daily steps display, imported workouts, and the separate opt-in challenge aggregate in the permissions/declaration material, and keep the in-app rationale/Manage Access flow aligned with the privacy policy.

### Sign in details (Play Console → App content → Sign in details)

Console path: **Policy and programs → App content → Actioned → Sign in details → Manage**.

**Is any part of your app restricted?** Yes

| Field | Value |
| --- | --- |
| Name | `Fud AI Google Play review access` |
| Username / email / phone | `Not applicable - no user account or login.` |
| Password | Same Google Gemini API key as App Store Connect App Review Sign-In password (do not commit the key in git). |
| Personal-data checkbox | Check: account has no personal data that could identify a real person |
| Full-access checkbox | Check: these details provide full access including premium/paid content |

**Any other information** (≤500 chars — paste as-is):

```
No account/login. Password field = Google Gemini API key for review only.

1. Finish onboarding with any test values; skip notifications and Health Connect.
2. On Bring Your Own AI: keep Google Gemini + the preselected model, paste the key into API Key, tap Continue.
3. Key is reusable; no 2FA or location lock. All features work after that, including AI food log / coach.
Plus/Pro (Settings → AI Access) is optional; BYOK gives full access.
```

After Apply → Save, if Publishing overview lists pending changes, **Send for review**.

### Health apps declaration (Play Console → App content → Health apps)

**Step 1 — App features (select only these)**
- Activity and fitness
- Nutrition and weight management

Do **not** select medical, mental health, research studies, or Other.

**Step 2 — Only the fields Play shows** (wrap is intentional so you can
read/copy without sideways scroll; paste into Console as one paragraph)

#### Activity — Active calories
`READ_ACTIVE_CALORIES_BURNED`
```
Fud AI reads Active Calories Burned from Health Connect, with the
user's permission, to estimate personalized calorie goals from recent
activity energy. This helps the user set nutrition targets based on
actual activity instead of only static profile formulas. If total
calories burned is unavailable, Fud AI can combine active calories
with the user's profile-based BMR estimate. This data is used only
for nutrition and fitness goal tracking, not for diagnosis, treatment,
clinical decisions, or medical advice.
```

#### Activity — Steps
`READ_STEPS`
```
Fud AI reads daily steps from Health Connect, with the user's
permission, to show today's step count on the Home screen and to
support clearer burn and deficit context for nutrition and fitness
tracking. Steps are used only for optional user-facing progress
display and goal-related estimates inside Fud AI. This data is not
used for diagnosis, treatment, clinical decisions, or medical advice.
```

#### Activity — Exercise
`READ_EXERCISE`
```
Fud AI reads exercise sessions from Health Connect, with the user's
optional permission, so workouts recorded by other apps and devices
the user connects (for example Garmin Connect, Coros, Strava, Samsung
Health, or Fitbit) appear as read-only history in the Workouts diary
and Progress tab: activity type, time, duration, distance when
available, active calories, and the recording app. Sessions written
by Fud AI itself are skipped, so calories are never counted twice.
Imported sessions stay on the device, are never written back, and do
not change calorie goals. This data is not used for diagnosis,
treatment, clinical decisions, or medical advice.
```

#### Body measurement — Body fat
`READ_BODY_FAT`
```
Fud AI reads body fat measurements from Health Connect so users can
see body fat history and trends in the Progress tab. Body fat can
also be used, if enabled by the user, for body-fat-aware BMR and
calorie goal calculations. This supports nutrition and weight
management only. Fud AI does not use body fat data for diagnosis,
treatment, clinical decisions, or medical advice.
```

#### Body measurement — Weight
`READ_WEIGHT`
```
Fud AI reads weight measurements from Health Connect so the user's
weight progress chart, goal tracking, calorie calculations, and AI
Coach context can reflect weights logged in other approved apps or
smart scales. Imported weights are shown in the Progress tab and
used for nutrition and weight-management calculations. Fud AI does
not use weight data for diagnosis, treatment, clinical decisions, or
medical advice.
```

#### Nutrition — Total calories burned
`READ_TOTAL_CALORIES_BURNED`
```
Fud AI reads Total Calories Burned from Health Connect, with the
user's permission, to estimate the user's recent total daily energy
expenditure. This helps calculate a personalized daily calorie target
for nutrition and weight management. The data is used only to support
calorie goal estimation inside Fud AI and is not used for diagnosis,
treatment, clinical decisions, or medical advice.
```

#### Nutrition — Nutrition
`READ_NUTRITION`
```
Fud AI may read nutrition records from Health Connect, with the
user's permission, to keep the user's daily calorie, protein,
carbohydrate, fat, and nutrient totals consistent with nutrition data
stored in Health Connect. This supports the Home screen, widgets,
progress views, and AI Coach context for nutrition and weight
management. Fud AI uses this data only for user-facing food logging,
nutrition tracking, and goal tracking. It is not used for diagnosis,
treatment, clinical decisions, or medical advice.
```

#### Miscellaneous — Health data history
`READ_HEALTH_DATA_HISTORY`
```
Fud AI reads historical Health Connect records, with the user's
permission, so food log, weight, body fat, and related nutrition or
energy data can restore after reinstall or when the user reconnects
Health Connect. History access is used only to backfill and display
the user's own past tracking data for nutrition and fitness goals.
It is not used for diagnosis, treatment, clinical decisions, or
medical advice.
```

#### Miscellaneous — Health data in background
`READ_HEALTH_DATA_IN_BACKGROUND`
```
Fud AI requests background health data access only when the user
explicitly enables the optional Daily Summary notification. At the
user-selected time, the app reads that day's Active Calories Burned
and Total Calories Burned from Health Connect to calculate the user's
eaten-versus-burned calorie balance and display it in a local
notification. Background reads are limited to that optional summary
feature and are not used for diagnosis, treatment, clinical
decisions, or medical advice.
```

**Privacy policy URL:** https://fud-ai.app/privacy.html
