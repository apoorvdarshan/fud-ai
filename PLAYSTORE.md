# Play Store Listing

Google Play Console listing copy prepared for Fud AI Android v9.0 / versionCode 49. Each field is in a code block for easy copy-paste. This file does not indicate that the release or listing has been submitted or published.

**Pending Play Console updates**
- [x] Health Connect declaration includes **Exercise (READ)** with the §7 `READ_EXERCISE` rationale (submitted with 8.0, 2026-10-24). Play lists a new health permission in the declaration only once a release containing it is uploaded, and a store-release upload that adds one fails until the declaration is updated, so upload such a release through Play Console first.
- [ ] Verify the Health Connect declaration includes **Hydration (WRITE)** using the rationale below before publishing 9.0.

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

**80 char hard cap per language. Cannot include price/promotion keywords ("free", "discount", "sale", "best", "#1", etc.) — Play Console will block promotion of the listing.** English-only on Play Console — non-English Play Store browsers see the English source as fallback.

### English (en-US) — 65 chars
```
Log meals by photo, voice, or barcode. Track calories and macros.
```

---

## 3. Full Description

**4000 char hard cap per language.** This is the long-form "About this app" copy. Keep it accurate for the current production build as well as the next release; version-specific features belong in What's New. English-only on Play Console — non-English Play Store browsers see the English source as fallback.

### English (en-US)
```
Fud AI is an open-source food diary, calorie tracker, and workout log. Take a photo, scan a barcode, or say what you ate. Review calories, protein, carbs, fat, and other nutrients before saving the meal.

LOG MEALS YOUR WAY
• Take or import up to 10 food photos in one analysis and add an optional note about portions or ingredients.
• Scan a barcode to look up nutrition from Open Food Facts, type a meal description, or enter the details manually.
• Talk to Brulee in Live Voice to describe a meal or check your progress. Open it from Home, Quick Settings, or a home-screen widget.
• Reuse saved meals, copy meals from another day, or share a meal link.

REVIEW BEFORE YOU LOG
Change the serving size or correct calories and individual nutrients before saving. Use What if? to see how a meal would change your day’s totals. Barcode lookup and manual logging don’t require an AI key.

TRACK WHAT MATTERS TO YOU
• Set calorie and macro targets, choose nutrient cards, and track fiber, sugar, sodium, vitamins, and other nutrients.
• Review nutrition, weight, steps, and trends, including a Weekly Recap. Display energy in kcal or kJ.
• Use optional water tracking and reminders, or keep a private fasting timer with editable history.
• Add home-screen widgets for calories, protein, water, or other daily totals. Choose an accent that fits your home screen.

PLAN AND LOG WORKOUTS
Build routines or log a workout as you go. Record sets, reps, weight, and effort, use a rest timer, and browse exercises with photos and filters. Quickly log a walk or run. Optional Health Connect integration lets you choose which supported data to sync or import.

CHOOSE HOW AI RUNS
Bring your own API key for a supported provider, or choose an optional paid Plus or Pro plan for Hosted AI. AI Coach can answer questions about your goals and relevant diary or workout history when you ask. Estimates and coaching are informational, not medical advice.

OPEN SOURCE, NO ADS
Core tracking has no required Fud AI account. Your diary is stored on your device unless you choose a feature such as backup, sharing, or health sync. AI requests use the provider or Hosted AI service you choose. The optional Weekly Challenge is 18+ and uses aggregate scores, not raw meal or workout logs.

Terms: https://www.fud-ai.app/terms
Privacy: https://www.fud-ai.app/privacy
Source: https://github.com/apoorvdarshan/fud-ai
```

### Other languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, cs-CZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pl-PL, pt-BR, ro, ru-RU, tr-TR, uk, zh-CN) see the English source as fallback. The app includes 19 localized interfaces; newer strings may temporarily use the English fallback.

---

## 4. What's New (v9.0 / versionCode 49)

**500 char hard cap per language.** English is the fallback for other store locales.

```
<en-US>
Fud AI 9.0 brings the 04 Flash redesign to Home, the diary, and settings.
• Home-screen widgets now match, including nutrition, hydration, and voice shortcuts.
• Daily and Monthly Challenges are separate opt-ins alongside Weekly.
• About → App & Updates shows when Google Play has an update.
• Keep using your own AI key free, or view optional paid Hosted Plus/Pro plans.
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
- **Health features**: Yes — nutrition, body measurements, energy-based goals, workout calories, optional daily steps, water and fasting tracking, and optional 18+ Challenges. Challenge qualification is calculated locally; only bounded aggregates are uploaded, never raw logs or weight loss. Health Connect permissions cover READ/WRITE nutrition, weight, body fat, and active calories; READ total calories burned, steps, and optional exercise imports; and separately optional WRITE hydration. Water amounts and timestamps can be written only when water sync and the permission are enabled. Fasting history is not written. Match the permission declarations to the in-app Manage Access flow and Privacy Policy.

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

#### Nutrition — Hydration
`WRITE_HYDRATION`
```
Fud AI writes the amount and timestamp of water the user logs to
Health Connect only after they enable water sync and grant Hydration
write permission. Fud AI does not read water data from Health Connect.
Turning water sync off stops future writes. Users can manage or remove
existing records in Health Connect. This data is not used for ads,
diagnosis, treatment, or medical advice.
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
