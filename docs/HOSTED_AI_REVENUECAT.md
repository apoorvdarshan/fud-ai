# Fud AI v7 — Hosted Plans, Credits & RevenueCat Setup

Fud AI v7 keeps the **full app free with BYOK forever**. Optional **Plus / Pro** subscriptions unlock **Hosted AI** (Gemini Flash-Lite + Deepgram STT via `fud-ai.app`). Credit packs are consumable add-ons.

## Entitlements (RevenueCat)

| Entitlement ID | Plan | Daily hosted actions |
|----------------|------|----------------------|
| `plus` | Plus | 30 |
| `pro` | Pro | 60 (includes Plus tier) |

Configure **Pro** entitlement to include **Plus** in RevenueCat so upgrades behave correctly.

## Product IDs

### Subscriptions

| Product ID | Suggested price |
|------------|-----------------|
| `com.apoorvdarshan.calorietracker.plus.monthly` | $8.99 |
| `com.apoorvdarshan.calorietracker.plus.yearly` | $69.99 |
| `com.apoorvdarshan.calorietracker.pro.monthly` | $17.99 |
| `com.apoorvdarshan.calorietracker.pro.yearly` | $149.99 |

### Credit packs (consumables)

| Product ID | Credits | Suggested price |
|------------|---------|-----------------|
| `com.apoorvdarshan.calorietracker.credits.50` | 50 | $1.99 |
| `com.apoorvdarshan.calorietracker.credits.150` | 150 | $4.99 |
| `com.apoorvdarshan.calorietracker.credits.400` | 400 | $9.99 |

Credits persist across renewals/cancel; **v1 spends credits only while Plus/Pro is active**.

### Tips (unchanged on iOS; new on Android)

| Product ID | Tier | Price |
|------------|------|-------|
| `com.apoorvdarshan.calorietracker.tip.snack` | Snack | $0.99 |
| `com.apoorvdarshan.calorietracker.tip.proteinshake` | Protein Shake | $2.99 |
| `com.apoorvdarshan.calorietracker.tip.lunch` | Lunch | $4.99 |
| `com.apoorvdarshan.calorietracker.tip.feast` | Feast | $9.99 |

Android may use the same IDs on Play when allowed; otherwise create Play products with matching suffixes and map them in RevenueCat.

## RevenueCat offerings (suggested)

Create separate offerings named **`plus`** and **`pro`** (iOS paywall looks up these identifiers). Each offering can include its plan packages, and the **default/current offering** should also expose credit packs for Android:

| Package identifier | Product |
|--------------------|---------|
| `$rc_monthly` or `plus_monthly` | Plus monthly |
| `$rc_annual` or `plus_yearly` | Plus yearly |
| `pro_monthly` | Pro monthly |
| `pro_yearly` | Pro yearly |
| `credits_50` | 50 credits |
| `credits_150` | 150 credits |
| `credits_400` | 400 credits |

Legacy note: a single `default` offering alone is not enough for iOS subscribe flows.

iOS paywall looks up offerings `plus` and `pro`; Android filters `offerings.current` into subscription vs credit packages.

### SDK keys

- **iOS:** already configured (`appl_…` in `calorietrackerApp.swift`)
- **Android:** set `revenuecat.public.sdk.key=goog_…` in `android/oauth.properties` or `android/local.properties` (same pattern as `cloud.backup.web.client.id`). Release builds fail if the key is missing.

## Hosted AI worker

See `services/hosted-ai/README.md`. Deploy secrets on the `fud-ai` worker:

- `GEMINI_API_KEY`
- `DEEPGRAM_API_KEY`
- `FUD_HOSTED_AI_APP_SECRET` (must match app constant)

## Metering rules (client)

One shared daily pool per subscriber:

| Action | Cost |
|--------|------|
| Photo/text food, coach message, workout AI, what-if, allergens, ingredient AI, reprocess, manual recalculate (1 LLM) | 1 |
| Voice food (Deepgram + LLM) | 2 |

**Not counted:** Adaptive Goals, onboarding goal calc, barcode, Health, widgets, BYOK traffic.

Spend order: **daily allowance → credit bank → soft paywall**.

## App Store / Play copy notes

- Free forever = full tracker + BYOK
- Optional Plus/Pro for hosted convenience
- Tips/donations optional; Android keeps Ko-fi link
