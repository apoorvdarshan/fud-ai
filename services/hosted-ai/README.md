# Fud AI Hosted Path (Cloudflare Worker)

Optional **Plus / Pro** subscriptions route food, coach, workout, and goal AI through a Fud-operated proxy instead of the user's BYOK keys. Free users stay on BYOK forever — there is no free hosted quota.

Implementation lives in `web/hosted-ai-api.ts` and is mounted from the main `fud-ai.app` worker at `/api/hosted-ai/v1/*`.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/hosted-ai/v1/generate` | Flash-Lite text/vision (`prompt`, optional `images[]`, optional `systemInstruction`) |
| POST | `/api/hosted-ai/v1/gemini` | Passthrough Gemini `generateContent` body for coach tool calling (model pinned server-side) |
| POST | `/api/hosted-ai/v1/transcribe` | Deepgram STT (`audio` base64, optional `mimeType`, optional `language`) |

All requests require:

```
Authorization: Bearer <FUD_HOSTED_AI_APP_SECRET>
X-Fud-User-Id: <RevenueCat app user id>   # required; max 128 chars
X-Fud-Plan: plus|pro                      # required; rejects none/other values
```

The worker validates the shared secret, user id, and an active plus/pro plan header. It does **not** yet verify RevenueCat entitlements server-side — see the follow-up note below.

## Required secrets (Wrangler)

Set these on the `fud-ai` worker (Dashboard → Workers → fud-ai → Settings → Variables):

| Variable | Description |
|----------|-------------|
| `GEMINI_API_KEY` | Google AI Studio key for Flash-Lite |
| `DEEPGRAM_API_KEY` | Deepgram API key for hosted voice STT |
| `FUD_HOSTED_AI_APP_SECRET` | Shared secret embedded in iOS/Android builds (rotate periodically) |

```bash
cd web
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put DEEPGRAM_API_KEY
npx wrangler secret put FUD_HOSTED_AI_APP_SECRET
```

Update `web/wrangler.toml` `run_worker_first` to include `/api/hosted-ai/v1/*` before deploy.

## v1 metering note

Quota (daily pool + credit bank) is enforced **on device** before each hosted call. The worker validates the shared secret, user id, and plus/pro plan header, plus request size limits and upstream timeouts. **Follow-up:** add RevenueCat entitlement verification (webhooks or REST) and a server-side usage ledger so requests cannot bypass device quota.

## Hosted limits

- Model pinned to `gemini-2.0-flash-lite` (worker constant; update when Flash-Lite GA name changes)
- Max **3** images per hosted vision request (BYOK allows up to 10)
- Voice food in Hosted mode = **2** actions (STT + LLM)

## Local smoke test

```bash
cd web
npm test -- hosted-ai
curl -sS -X POST "https://fud-ai.app/api/hosted-ai/v1/generate" \
  -H "Authorization: Bearer $FUD_HOSTED_AI_APP_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Reply with exactly: ok"}'
```
