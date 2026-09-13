# Fud AI Hosted Path (Cloudflare Worker)

Optional **Plus / Pro** subscriptions route food, coach, workout, and goal AI through a Fud-operated proxy instead of the user's BYOK keys. Free users stay on BYOK forever — there is no free hosted quota.

Implementation lives in `web/hosted-ai-api.ts` (routing/handlers), `web/hosted-ai-ledger.ts` (RevenueCat verification + D1 usage ledger), and `web/hosted-ai-gemini-request.ts` (request allow-list). It is mounted from the main `fud-ai.app` worker at `/api/hosted-ai/v1/*`.

## Endpoints

| Method | Path | Purpose | Metered |
|--------|------|---------|---------|
| POST | `/api/hosted-ai/v1/generate` | Flash-Lite text/vision (`prompt`, optional `images[]` ≤ 3, optional `systemInstruction`) | 1 action |
| POST | `/api/hosted-ai/v1/gemini` | Allow-listed Gemini `generateContent` body for coach tool calling (model pinned server-side) | 1 action **per call** (each tool round is a call) |
| POST | `/api/hosted-ai/v1/transcribe` | Deepgram STT (`audio` base64, optional `mimeType`, optional `language`) | 1 action |
| GET | `/api/hosted-ai/v1/quota` | Current quota snapshot; `?refresh=1` forces a RevenueCat re-check | none |

Every request carries exactly one identity header:

```
X-Fud-User-Id: <RevenueCat app user id>   # $RCAnonymousID:<32 hex> or a custom id [A-Za-z0-9_.@-]{8,128}
```

There is **no shared client secret**. The previous `FUD_HOSTED_AI_APP_SECRET` was embedded in the public iOS source and is treated as compromised: the Worker no longer reads it, and the value should be deleted from the Worker (`npx wrangler secret delete FUD_HOSTED_AI_APP_SECRET`). The `Authorization` and `X-Fud-Plan` headers sent by older builds are ignored.

## Security model

1. **Server-side entitlement verification.** The Worker calls `GET https://api.revenuecat.com/v1/subscribers/{app_user_id}` with a RevenueCat **secret** API key held only in Worker env, derives the plan (`pro` > `plus` > `none`), and caches the result in D1 for 15 min (2 min for `none`). If RevenueCat is unreachable, a previously verified plan is honoured for up to 24 h. A client cannot assert its own plan.
2. **Server-side ledger (D1, migration `0002_hosted_ai_ledger.sql`).** One row per hashed user id holds the UTC day counter, lifetime credits granted, and credits spent. Every upstream round-trip deducts one action — daily pool first, then credit bank — using a compare-and-swap on the row `version`, so concurrent requests cannot double-spend. Failed upstream calls are refunded. Reinstalls, backup restores, or edited preferences on the device have no effect on quota.
3. **Idempotent credits.** Credit packs are reconciled from RevenueCat `non_subscriptions` by transaction id; the device never grants credits. `credits_granted` only ever grows, so a transient partial RevenueCat response cannot shrink a balance.
4. **Rate limiting.** Two Cloudflare rate-limiter bindings: `HOSTED_AI_USER_RATE_LIMITER` (40/min per hashed user id) and `HOSTED_AI_ADDRESS_RATE_LIMITER` (120/min per hashed `CF-Connecting-IP`). Limits are applied before any RevenueCat or D1 work.
5. **`/gemini` allow-list.** The body is rebuilt from scratch with only: `contents` (roles `user`/`model`; parts `text`, `inlineData` image ≤ 3 per request, `functionCall`, `functionResponse`, `thoughtSignature`/`thought` echo metadata), `systemInstruction` (text only), bounded `generationConfig` (`temperature`, `topP`, `topK ≤ 100`, `maxOutputTokens ≤ 8192`, `candidateCount == 1`, ≤ 5 `stopSequences`, `responseMimeType` text/json), `tools` (≤ 1 entry containing only `functionDeclarations`, ≤ 32 declarations with bounded `name`/`description`/`parameters`), and `toolConfig.functionCallingConfig`. Anything else — `googleSearch`, `codeExecution`, `urlContext`, `fileData`, `safetySettings`, `cachedContent`, `responseSchema`, unknown keys — is rejected with `400 invalid_request_body` and a `detail` such as `unsupported_field:tools[0].googleSearch`.
6. **Error hygiene.** Upstream (Gemini/Deepgram/RevenueCat) response bodies are logged server-side (truncated) and never returned. Callers see stable codes only: `upstream_error` (502), `upstream_unavailable` (503, upstream 429/5xx), `upstream_timeout` (504), `entitlement_unavailable` (503).

Known residual risk: identity is the RevenueCat app user id, which is unguessable (`$RCAnonymousID:` + 128 random bits) but not unforgeable if leaked from a subscriber's device. The per-user ledger and rate limits bound the damage to that subscriber's own quota. App Attest / DeviceCheck binding is the natural next step and slots into `identifyClient()` in `web/hosted-ai-api.ts`.

## Response codes

| Status | `error` | Meaning |
|--------|---------|---------|
| 401 | `invalid_user_id` | Missing or malformed `X-Fud-User-Id` |
| 402 | `quota_exceeded` | Daily pool and credit bank exhausted; body includes `quota` |
| 403 | `subscription_required` | Verified plan is `none`; body includes `quota` |
| 429 | `rate_limited` | Per-user or per-address limiter tripped (`Retry-After: 60`) |
| 400 | `invalid_request_body`, `missing_prompt`, `too_many_images`, … | Validation failures (never metered) |
| 503 | `gemini_not_configured`, `deepgram_not_configured`, `entitlements_not_configured` | Secrets not set on the Worker |

Successful and 402 responses carry the current ledger state in headers the iOS app caches for display:

```
X-Fud-Quota-Plan: plus
X-Fud-Quota-Day: 2026-09-13          # UTC day the counter applies to
X-Fud-Quota-Daily-Used: 4
X-Fud-Quota-Daily-Limit: 30
X-Fud-Quota-Credits: 150
```

## Required secrets (Wrangler)

Set on the `fud-ai` worker (Dashboard → Workers → fud-ai → Settings → Variables, or `wrangler secret put`). **None of these are needed for the repo to build or for `npm test`, which runs against mocks.**

| Variable | Description | When |
|----------|-------------|------|
| `REVENUECAT_API_KEY` | RevenueCat **v1 secret API key** (`sk_…`; Project → API keys). Read access to `GET /v1/subscribers` is all that is used. Never ship this in an app binary. | Before enabling hosted mode |
| `GEMINI_API_KEY` | Google AI Studio key for Flash-Lite | Production time |
| `DEEPGRAM_API_KEY` | Deepgram API key for hosted voice STT | Production time |

```bash
cd web
npx wrangler secret put REVENUECAT_API_KEY
npx wrangler secret put GEMINI_API_KEY        # at production time
npx wrangler secret put DEEPGRAM_API_KEY      # at production time
npx wrangler secret delete FUD_HOSTED_AI_APP_SECRET   # retire the leaked shared secret
```

Until the provider keys are set, the proxy responds `503 gemini_not_configured` / `deepgram_not_configured` without metering the request. No RevenueCat webhook is required for this design (entitlements are pulled, not pushed), so no webhook secret exists.

## Deploy checklist

1. Apply the D1 migration to the `fud-ai-challenge` database (the hosted ledger shares it):

   ```bash
   cd web
   npx wrangler d1 migrations apply fud-ai-challenge --remote
   ```

2. The `HOSTED_AI_USER_RATE_LIMITER` / `HOSTED_AI_ADDRESS_RATE_LIMITER` bindings are declared in `web/wrangler.toml` (namespace ids 61005/61006) and are created on deploy.
3. `run_worker_first` in `web/wrangler.toml` already includes `/api/hosted-ai/v1/*`.
4. Set the secrets above, then `npx wrangler deploy`.

## Hosted limits

- Model pinned to `gemini-3.5-flash-lite` (worker constant; update when Flash-Lite GA name changes)
- Plus: **30** actions per UTC day; Pro: **60**. Credits are spent only after the daily pool is exhausted and only while a plan is active.
- Max **3** images per hosted request (BYOK allows up to 10)
- Voice food in Hosted mode = **2** actions (STT + LLM); a coach message costs one action per Gemini round (tool calls included)
- Ledger rows idle for 180 days are pruned by the hourly cron

## Local smoke test

```bash
cd web
npm test -- hosted-ai
```

Against a deployed Worker (needs a real RevenueCat app user id with an active entitlement):

```bash
curl -sS "https://fud-ai.app/api/hosted-ai/v1/quota" \
  -H 'X-Fud-User-Id: $RCAnonymousID:0123456789abcdef0123456789abcdef'

curl -sS -X POST "https://fud-ai.app/api/hosted-ai/v1/generate" \
  -H 'X-Fud-User-Id: $RCAnonymousID:0123456789abcdef0123456789abcdef' \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Reply with exactly: ok"}' -i
```
