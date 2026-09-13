# Fud AI v7 — Hosted Plans, Credits & RevenueCat Setup (iOS first)

Fud AI keeps the **full app free with BYOK forever** on both platforms.

**v7 shipping scope:** optional **Plus / Pro** + credit packs + tip jar are implemented on **iOS** (RevenueCat + App Store). **Android stays BYOK-only** for now (Ko-fi tip link unchanged). Android hosted plans can ship in a later update.

## Entitlements (RevenueCat)

| Entitlement ID | Plan | Daily hosted actions |
|----------------|------|----------------------|
| `plus` | Plus | 30 |
| `pro` | Pro | 60 (includes Plus tier) |

Configure **Pro** entitlement to include **Plus** in RevenueCat so upgrades behave correctly.

## Product IDs (App Store / iOS)

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

### Tips (unchanged on iOS)

| Product ID | Tier | Price |
|------------|------|-------|
| `com.apoorvdarshan.calorietracker.tip.snack` | Snack | $0.99 |
| `com.apoorvdarshan.calorietracker.tip.proteinshake` | Protein Shake | $2.99 |
| `com.apoorvdarshan.calorietracker.tip.lunch` | Lunch | $4.99 |
| `com.apoorvdarshan.calorietracker.tip.feast` | Feast | $9.99 |

## RevenueCat offerings (suggested)

Create separate offerings named **`plus`** and **`pro`** (iOS paywall looks up these identifiers). Include credit packs on those offerings (or the current offering) so the iOS credits sheet can load them:

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

### SDK keys

- **iOS:** already configured (`appl_…` in `calorietrackerApp.swift`)
- **Android:** not wired for hosted billing in this release

## Hosted AI worker

See `services/hosted-ai/README.md`. Deploy secrets on the `fud-ai` worker:

- `REVENUECAT_API_KEY` — RevenueCat v1 **secret** key; the Worker verifies each subscriber's entitlements and credit purchases with it. Never embedded in the app.
- `GEMINI_API_KEY` — set at production time
- `DEEPGRAM_API_KEY` — set at production time

The app ships **no proxy secret**. It sends only its RevenueCat app user id (`X-Fud-User-Id`, anonymous `$RCAnonymousID:` form only — the app never calls `Purchases.logIn`); the Worker checks the plan with RevenueCat, caches it in D1, and meters usage in a server-side ledger. No RevenueCat webhook is needed. Because the id is the sole credential, the Worker treats it as a bearer token: guessable custom ids are rejected, nothing is charged before RevenueCat confirms the plan, and the residual leak-and-replay risk (with the App Attest follow-up that would close it) is documented in `services/hosted-ai/README.md`.

## Metering rules (enforced by the Worker)

One shared daily pool per subscriber, keyed by RevenueCat app user id and reset at **midnight UTC**. Every upstream call the Worker makes on the user's behalf costs one action:

| Action | Cost |
|--------|------|
| Photo/text food, workout AI, what-if, allergens, ingredient AI, reprocess, manual recalculate (per LLM call) | 1 |
| Voice food (Deepgram + LLM) | 2 |
| Coach message | 1 per Gemini round — a reply that needs tool calls costs one action per round |

**Not counted:** Adaptive Goals, onboarding goal calc, barcode, Health, widgets, BYOK traffic.

Spend order: **daily allowance → credit bank → `402 quota_exceeded` → soft paywall**.

Credits are reconciled from RevenueCat `non_subscriptions` by transaction id, so a pack is counted exactly once regardless of reinstalls, restores, or backups. The iOS app only caches the numbers the Worker returns (`X-Fud-Quota-*` headers) for display and forces a re-verification (`GET /quota?refresh=1`) right after a purchase or restore.

## Store copy notes

- Free forever = full tracker + BYOK (both platforms)
- Optional Plus/Pro + credits = **iOS hosted convenience** in this release
- iOS tip jar unchanged; Android keeps Ko-fi
