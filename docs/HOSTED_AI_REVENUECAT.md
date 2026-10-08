# Fud AI — Hosted Plans, Credits & RevenueCat Setup (iOS + Android)

Fud AI keeps the **full app free with BYOK forever** on both platforms.

**Scope:** optional **Plus / Pro** + credit packs + tip jar ship on **iOS 7.0+** (RevenueCat + App Store) and **Android 7.2+** (RevenueCat + Google Play Billing 8). Both apps use the same RevenueCat project, entitlements, and offerings; the Worker counts both stores. Android tips moved from Ko-fi to Google Play in 7.2.

## Entitlements (RevenueCat)

| Entitlement ID | Plan | Daily hosted actions |
|----------------|------|----------------------|
| `plus` | Plus | 30 |
| `pro` | Pro | 60 (includes Plus tier) |

Configure **Pro** entitlement to include **Plus** in RevenueCat so upgrades behave correctly.

## Canonical catalog (CI)

Product IDs + RevenueCat entitlement/offering maps are also versioned under
[`store/catalog/`](../store/catalog/) for future GitHub Actions automation.
See [`store/README.md`](../store/README.md). Live store / RevenueCat sync stays
**off** until repository Variables are flipped (setup only by default).

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

## Product IDs (Google Play / Android 7.2+)

Play caps product ids at **40 characters** and ids can never be reused, so the Play ids are short and live in each item's `play` block in `store/catalog/products.json`.

| Play product | Base plan | RevenueCat product id | Entitlement / package | Price |
|---|---|---|---|---|
| subscription `plus` | `monthly` (auto-renew, 1 month) | `plus:monthly` | `plus` / `$rc_monthly` | $8.99 |
| subscription `plus` | `yearly` (auto-renew, 1 year) | `plus:yearly` | `plus` / `$rc_annual` | $69.99 |
| subscription `pro` | `monthly` | `pro:monthly` | `pro` / `pro_monthly` | $17.99 |
| subscription `pro` | `yearly` | `pro:yearly` | `pro` / `pro_yearly` | $149.99 |
| one-time `credits_50` / `credits_150` / `credits_400` | — | same | none / `credits_*` on both offerings | $1.99 / $4.99 / $9.99 |
| one-time `tip_snack` / `tip_proteinshake` / `tip_lunch` / `tip_feast` | — | same | none (bought by id) | $0.99 / $2.99 / $4.99 / $9.99 |

In RevenueCat, credit packs and tips must stay **consumable** (not "non-consumable") so they can be bought again. Plus ↔ Pro changes on Play use `oldProductId` + a replacement mode (upgrade: `CHARGE_PRORATED_PRICE`; downgrade or period change: `DEFERRED`), so a user is never billed for two subscriptions.

### Android launch checklist (one time)

1. **Play Console:** payments profile active. Upload the first 7.2 AAB (with the Billing permission) to **closed testing** — products cannot be created before a billing build exists on a track.
2. **Play Console → Monetize:** create subscriptions `plus` and `pro`, each with auto-renewing base plans `monthly` and `yearly` at the prices above; create the one-time products `credits_50/150/400` and `tip_snack/proteinshake/lunch/feast`. Activate them. Add license testers.
3. **Google Cloud:** a service account for RevenueCat with the Play Developer API enabled, invited in Play Console with view financial data and manage orders/subscriptions permissions (credentials can take up to 36 h to activate).
4. **RevenueCat (same project as iOS):** add the Play Store app (`com.apoorvdarshan.calorietracker`), upload the service-account JSON, set up real-time developer notifications, import the Play products, attach `plus:*` to the `plus` entitlement and `pro:*` to `pro` (mirroring iOS), and add the Play products to the `plus` / `pro` offering packages listed below. Copy the `goog_…` public SDK key into the Android build.
5. **Worker:** deploy the version that counts `credits_*` (already in `web/hosted-ai-ledger.ts`) before the Android release.
6. Test real purchases from the closed-testing install, then promote.

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

- **iOS:** `appl_…` public key in `calorietrackerApp.swift`
- **Android:** `goog_…` public key in `BuildConfig.REVENUECAT_API_KEY` (set in `android/app/build.gradle.kts`, or `revenuecat.google.api.key` in `local.properties` / `REVENUECAT_GOOGLE_API_KEY`). Empty = billing off and plans hidden. Debug variants use a RevenueCat **Test Store** key (`revenuecat.test.store.api.key`) because `.debug` packages cannot load Play products; never put a Test Store key in a release build (the SDK crashes on purpose).

## Hosted AI worker

See `services/hosted-ai/README.md`. Deploy secrets on the `fud-ai` worker:

- `REVENUECAT_API_KEY` — a key that can read `GET /v1/subscribers` (a v1 secret key, or the public SDK key; production uses the Android `goog_…` key). The Worker verifies each subscriber's entitlements and credit purchases with it. A v2 secret key is rejected (403) and breaks every hosted request. Verify a deploy with `scripts/hosted_ai_smoke.sh`.
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

Credits are reconciled from RevenueCat `non_subscriptions` by transaction id (App Store and Google Play ids), so a transaction is counted once per ledger row. Spent credits are tracked per anonymous app user id, so a restore onto a new id can show previously spent credits again depending on RevenueCat's restore behavior. The iOS and Android apps only cache the numbers the Worker returns (`X-Fud-Quota-*` headers) for display and force a re-verification (`GET /quota?refresh=1`) right after a purchase or restore.

## Store copy notes

- Free forever = full tracker + BYOK (both platforms)
- Optional Plus/Pro + credits = hosted convenience on iOS (App Store) and Android 7.2+ (Google Play)
- Tip jar on both platforms through the store (Android moved off Ko-fi in 7.2)
