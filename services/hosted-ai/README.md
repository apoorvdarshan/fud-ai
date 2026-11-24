# Fud AI Hosted Path (Cloudflare Worker)

Optional **Plus / Pro** subscriptions route food, coach, workout, and goal AI through a Fud-operated proxy instead of the user's BYOK keys. Free users stay on BYOK forever — there is no free hosted quota.

Implementation lives in `web/hosted-ai-api.ts` (routing/handlers), `web/hosted-ai-ledger.ts` (RevenueCat verification + D1 usage ledger), `web/hosted-ai-gemini-request.ts` (request allow-list), and `web/hosted-ai-live.ts` (voice assistant relay). It is mounted from the main `fud-ai.app` worker at `/api/hosted-ai/v1/*`.

## Endpoints

| Method | Path | Purpose | Metered |
|--------|------|---------|---------|
| POST | `/api/hosted-ai/v1/generate` | Flash-Lite text/vision (`prompt`, optional `images[]` ≤ 3, optional `systemInstruction`) | 1 action |
| POST | `/api/hosted-ai/v1/gemini` | Allow-listed Gemini `generateContent` body for coach tool calling (model pinned server-side) | 1 action **per call** (each tool round is a call) |
| POST | `/api/hosted-ai/v1/transcribe` | Deepgram STT (`audio` base64 WAV or MPEG-4/AAC, at most 120 s by measured duration; optional `mimeType`, optional `language`) | 1 action |
| GET | `/api/hosted-ai/v1/quota` | Current quota snapshot plus the voice balance; `?refresh=1` forces a RevenueCat re-check | none |
| GET (WebSocket) | `/api/hosted-ai/v1/live` | Voice assistant relay to Gemini Live; see [Voice assistant relay](#voice-assistant-relay-live) | 15-second blocks: monthly voice minutes, then 1 credit per block |

Every request carries exactly one identity header:

```
X-Fud-User-Id: <RevenueCat app user id>   # $RCAnonymousID:<32 lowercase hex> only
```

Only RevenueCat **anonymous** ids are accepted. They carry 122 random bits (a UUIDv4), which is what makes the header usable as a credential at all; custom ids (`Purchases.logIn` with an email or username) would be guessable and are rejected with `401 invalid_user_id` until requests carry a device-bound proof of identity (see *Known residual risk*). Neither the iOS nor the Android app calls `logIn`, and both RevenueCat SDKs emit the same `$RCAnonymousID:` + 32-hex shape, so this matches production behaviour. Entitlements and credits from both stores are visible to the one RevenueCat secret key because the Play app lives in the same RevenueCat project as the App Store app.

There is **no shared client secret**. The previous `FUD_HOSTED_AI_APP_SECRET` was embedded in the public iOS source and is treated as compromised: the Worker no longer reads it, and the value should be deleted from the Worker (`npx wrangler secret delete FUD_HOSTED_AI_APP_SECRET`). The `Authorization` and `X-Fud-Plan` headers sent by older builds are ignored.

## Model pins and allowances

- Generation and Coach use `gemini-3.5-flash-lite`; Live defaults to `gemini-3.8-live`.
- Recorded dictation defaults to `nova-2`. Set `HOSTED_AI_TRANSCRIBE_MODEL=nova-3`
  in a staging Worker to compare it. A fixed `language` is passed through;
  omitting it requests Deepgram detection, not English. Each app request makes
  at most one billed Deepgram request. The client cannot select a model.
- Paid Plus/Pro receive 30/60 actions per UTC day and 20/100 voice minutes per
  UTC calendar month. Active trials receive 20 actions per UTC day and 2/5
  voice minutes per UTC day. Credits can extend those allowances for entitled
  users; they do not grant access without an entitlement.

## Security model

1. **Server-side entitlement verification.** The Worker calls `GET https://api.revenuecat.com/v1/subscribers/{app_user_id}` with a RevenueCat **secret** API key held only in Worker env, derives the plan (`pro` > `plus` > `none`), and caches the result in D1 for 15 min (2 min for `none`). If RevenueCat is unreachable, a previously verified plan is honoured for up to 24 h. A client cannot assert its own plan.
2. **Server-side ledger (D1, migrations `0002_hosted_ai_ledger.sql` and `0003_hosted_ai_refunds.sql`).** One row per hashed user id holds the UTC day counter, lifetime credits granted, and credits spent. Every upstream round-trip deducts one action — daily pool first, then credit bank — using a compare-and-swap on the row `version`, so concurrent requests cannot double-spend. Reinstalls, backup restores, or edited preferences on the device have no effect on quota.
   - **Charge order.** A request is charged only after the entitlement check, provider-configuration check, and full body validation succeed, so 4xx/503 responses never depend on a refund; only a failed upstream call does.
   - **Refunds.** When the upstream call fails *without being billed by the provider* (an HTTP error status from Gemini/Deepgram, or a request that never reached it) the action is refunded. Timeouts and `200` responses with no usable output (safety block, output exhausted) keep their action, because the provider still bills them; refunding those would make them free and unmetered. If D1 rejects the refund, it is retried with backoff in `ctx.waitUntil` (after the error response is sent); if every attempt fails, a `hosted_ai_refund_failed` log line carries the full user hash and receipt so the row can be reconciled by hand. Refunds are idempotent: every spend carries a unique receipt id, and the refund writes the ledger update together with a marker row in `hosted_ai_refunds` in one D1 transaction, with the update gated on the marker not existing yet. A retry after a refund that committed but reported an error is therefore a no-op, never a second credit. Markers are pruned after 30 days.
   - **Monotonic entitlement cache.** Overlapping RevenueCat verifications that finish out of order cannot roll a plan backwards: an upsert only replaces `plan`/`entitlement_verified_at` when its verification timestamp is at least as recent as the stored one.
   - **Retention.** Idle rows are pruned after 180 days **unless they have ever spent purchased credits**. `credits_granted` can be rebuilt from RevenueCat, but `credits_spent` lives only in this table, so deleting such a row would hand consumed credits back.
3. **Idempotent credits.** Credit packs are reconciled from RevenueCat `non_subscriptions` by transaction id; the device never grants credits. Both the App Store ids (`com.apoorvdarshan.calorietracker.credits.*`) and the Google Play ids (`credits_50`, `credits_150`, `credits_400`; Play caps ids at 40 characters) are counted, and a test keeps the list in sync with `store/catalog/products.json`. `credits_granted` only ever grows, so a transient partial RevenueCat response cannot shrink a balance.
4. **Rate limiting.** Two Cloudflare rate-limiter bindings: `HOSTED_AI_USER_RATE_LIMITER` (40/min per hashed user id) and `HOSTED_AI_ADDRESS_RATE_LIMITER` (120/min per hashed `CF-Connecting-IP`). Limits are applied before any RevenueCat or D1 work.
5. **`/gemini` allow-list.** The body is rebuilt from scratch with only: `contents` (roles `user`/`model`; parts `text`, `inlineData` image ≤ 3 per request, `functionCall`, `functionResponse`, `thoughtSignature`/`thought` echo metadata), `systemInstruction` (text only), bounded `generationConfig` (`temperature`, `topP`, `topK ≤ 100`, `maxOutputTokens ≤ 8192`, `candidateCount == 1`, ≤ 5 `stopSequences`, `responseMimeType` text/json; when the client sends no `maxOutputTokens` the Worker sets 8192), `tools` (≤ 1 entry containing only `functionDeclarations`, ≤ 32 declarations with bounded `name`/`description`/`parameters`), and `toolConfig.functionCallingConfig`. Anything else — `googleSearch`, `codeExecution`, `urlContext`, `fileData`, `safetySettings`, `cachedContent`, `responseSchema`, unknown keys — is rejected with `400 invalid_request_body` and a `detail` such as `unsupported_field:tools[0].googleSearch`.
6. **Error hygiene.** Upstream (Gemini/Deepgram/RevenueCat) response bodies are never returned to callers and never logged verbatim either — provider error bodies echo request content and (for Deepgram) the presented token. Logs carry only structured identifiers extracted from the body (`status`, `code`, `err_code`, `request_id`), and any configured provider secret is scrubbed from every log line as a second layer. Callers see stable codes only: `upstream_error` (502), `upstream_unavailable` (503, upstream 429/5xx), `upstream_timeout` (504), `entitlement_unavailable` (503). The voice relay follows the same rule for Gemini Live close reasons: only the numeric close code is logged or passed on.
7. **Voice relay.** The same identity, rate-limit and plan checks run before a WebSocket upgrade, plus a voice balance check; the session's `setup` is rebuilt from an allow-list with the model pinned server-side, later messages are filtered by type and size, and time is charged in 15-second blocks before it is served. Details below.

### Known residual risk: the subscriber id is a bearer credential

`X-Fud-User-Id` is the only thing that ties a request to a subscriber. It is unguessable (`$RCAnonymousID:` + 122 random bits, enforced by format) but not unforgeable: anyone who obtains a subscriber's id — from a jailbroken device, a proxy log, or the subscriber sharing it deliberately — can spend that subscriber's daily pool and purchased credits until the id rotates (reinstall). Mitigations in place:

- Only anonymous ids are accepted; guessable custom ids are rejected outright.
- Nothing is charged, and no request body is even parsed, before RevenueCat confirms an active `plus`/`pro` entitlement for that exact id; an unknown or lapsed id costs the attacker nothing and gains them nothing.
- Damage is bounded to the victim's own ledger (30/60 actions per day plus credits), and further capped by the per-user and per-address rate limiters.
- The raw id is never persisted or logged; D1 and logs hold only a SHA-256 hash (12-char prefix in logs).

The voice relay wraps Gemini resumption handles in an HMAC-SHA256 authenticated token bound to the subscriber hash, with a two-hour expiry. The signing key is derived for this purpose from `GEMINI_API_KEY`; rotating that key invalidates existing wrappers. A handle issued to one subscriber cannot be resumed using another subscriber identity. Apps treat the wrapper as opaque; the Worker verifies it and unwraps the provider handle before forwarding setup. Raw provider handles, modified tokens and expired tokens are rejected. Neither wrapper nor provider handle is stored or logged. Someone who obtains both the subscriber bearer id and its resumption token can still act as that subscriber.

What would close the gap is a device-bound proof that the request originates from a genuine Fud AI install holding that RevenueCat account: Apple **App Attest** (attestation on first launch, then a per-request assertion over `X-Fud-User-Id` + body hash + nonce) verified in `identifyClient()` in `web/hosted-ai-api.ts`. That needs no new secrets — only Apple's public App Attest root certificate plus the team/bundle id — but it is a sizeable piece of work (CBOR/X.509 parsing, key storage in D1, nonce handling, a fallback for devices where App Attest is unsupported) and is deliberately out of scope for this change.

## Response codes

| Status | `error` | Meaning |
|--------|---------|---------|
| 401 | `invalid_user_id` | Missing or malformed `X-Fud-User-Id` |
| 402 | `quota_exceeded` | Daily pool and credit bank exhausted; body includes `quota` |
| 402 | `voice_quota_exceeded` | `/live` only: no monthly voice minutes and no credits left; body includes `quota` and `voice` |
| 403 | `subscription_required` | Verified plan is `none`; body includes `quota` (and `voice` on `/live`) |
| 426 | `upgrade_required` | `/live` requested without `Upgrade: websocket` |
| 429 | `rate_limited` | Per-user or per-address limiter tripped (`Retry-After: 60`) |
| 400 | `invalid_json`, `invalid_body`, `invalid_request_body`, `missing_prompt`, `too_many_images`, `unsupported_audio_type`, `unreadable_audio`, `audio_too_long` (body includes `maxSeconds`), … | Validation failures (never metered); `invalid_body` = valid JSON that is not an object |
| 503 | `gemini_not_configured`, `deepgram_not_configured`, `entitlements_not_configured` | Secrets not set on the Worker |
| 503 | `voice_unavailable` | `/live` only: `GEMINI_API_KEY` missing, `HOSTED_AI_LIVE_MODEL` malformed, or Gemini Live refused or did not answer the handshake (`Retry-After: 30` when that is transient) |

Successful and 402 responses carry the current ledger state in headers the iOS and Android apps cache for display:

```
X-Fud-Quota-Plan: plus
X-Fud-Quota-Day: 2026-09-13          # UTC day the counter applies to
X-Fud-Quota-Daily-Used: 4
X-Fud-Quota-Daily-Limit: 30
X-Fud-Quota-Credits: 150
```

## Voice assistant relay (`/live`)

`wss://fud-ai.app/api/hosted-ai/v1/live` relays the apps' live voice assistant to Gemini Live for Plus/Pro subscribers. The contract shared with the iOS and Android apps is `shared/voice-assistant/README.md` ("Hosted relay"); this section covers the Worker side.

### Handshake

The app opens the WebSocket with the usual `X-Fud-User-Id` header (plus `X-Fud-Platform: ios|android`, which is only logged). Before upgrading, the Worker checks, in order:

1. `GET` with `Upgrade: websocket` (otherwise 405 / 426);
2. the user id and both rate limiters (401 / 429), exactly as for the other routes;
3. the RevenueCat-verified plan (403 `subscription_required`; credits alone do not qualify);
4. the relay configuration: `GEMINI_API_KEY` set and a well-formed model (503 `voice_unavailable`);
5. the voice balance: at least one payable block (402 `voice_quota_exceeded`);
6. the upstream socket: `fetch` of `https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` with `Upgrade: websocket` and the key in the `x-goog-api-key` header (never in the URL), with a 10-second handshake timeout (503 `voice_unavailable`; `Retry-After: 30` when Gemini answered 429/5xx, the network failed, or it timed out).

All of these are plain JSON responses and none of them charge anything. Only then does the Worker answer `101` and start relaying.

### Session protocol

After the upgrade the app speaks the Gemini Live wire protocol through the Worker.

- **The first message must be `setup`.** It is rebuilt from scratch, and anything not listed here is rejected:
  - `model`: accepted and ignored; always replaced with `models/<HOSTED_AI_LIVE_MODEL>` (default `gemini-3.8-live`).
  - `generationConfig`: only `responseModalities` (`AUDIO`, `TEXT`) and `speechConfig` (`voiceConfig.prebuiltVoiceConfig.voiceName`, `languageCode`). Sampling knobs, `thinkingConfig`, `mediaResolution` and multi-speaker voices are refused.
  - `systemInstruction`: text parts only, at most 60 000 characters in total (`role` is dropped).
  - `tools`: `functionDeclarations` only (no Google Search, code execution or URL context), at most 64 declarations and 64 KB serialized; a declaration may have `name`, `description`, `parameters` (at most 12 levels deep) and `behavior` (`BLOCKING` / `NON_BLOCKING`).
  - `inputAudioTranscription` / `outputAudioTranscription`: `{}` or `{"languageCodes": [...]}`.
  - `contextWindowCompression` (`triggerTokens`, `slidingWindow.targetTokens`), `sessionResumption` (`handle`, an authenticated wrapper issued by this relay), and `realtimeInputConfig` (`automaticActivityDetection`, `activityHandling`, `turnCoverage`).
- **Later messages** must be exactly one of `realtimeInput`, `clientContent` or `toolResponse`, at most 1 MiB each, in text or binary frames (binary must be UTF-8 JSON). They are forwarded unchanged, in the frame type they arrived in. `clientContent` and `toolResponse` may not contain `fileData` (Gemini fetching content by reference). Each client message is parsed once for these checks; server messages keep their own frame type (Gemini sends JSON in binary frames). Frames containing a `sessionResumptionUpdate` are inspected and `newHandle` is replaced with the subscriber-bound wrapper; other frames pass through unchanged. Following frames and close events retain their order while the handle is signed, with a 2 MiB queue limit.
- **Abuse guards**, about 3× what the apps send (16 kHz PCM plus at most one 768 px JPEG a second): 1 MiB/s of client input with a 4 MiB burst, and 2 video frames a second with a burst of 10.
- **The unmetered phases are bounded:** `setup` must arrive within 10 s of the upgrade and Gemini's `setupComplete` within 15 s of the setup. Input the app sends between `setup` and the first paid block is held (up to 2 MiB), not forwarded, then released in order right after `setupComplete`.

The Worker adds its own text frames:

| Message | When |
|---------|------|
| `{"fudVoice":{"secondsRemaining":n}}` | Right after `setupComplete`, then every 60 s (every fourth block). `n` is the voice time the account can still use: the paid rest of the current block plus the monthly allowance and credits left. It is a balance, not capped by the 15-minute session limit. |
| `{"fudVoice":{"error":"…"}}` | Before every close the Worker initiates (table below). |

| Close | `fudVoice.error` | Cause |
|-------|------------------|-------|
| 1000 | none | Gemini ended a running conversation cleanly (for example after `goAway`). When the app closes, the Worker closes Gemini and stops charging. |
| 1008 | `invalid_setup` + `detail` | First message missing, malformed, or outside the setup allow-list |
| 1008 | `invalid_message` + `detail` | A later message of another type, over 1 MiB, not UTF-8 JSON, containing `fileData`, or more than 2 MiB sent before `setupComplete` |
| 1008 | `setup_timeout` | No `setup` within 10 s |
| 1008 | `rate_limited` | An abuse guard tripped |
| 1011 | `upstream_error` (+ `upstreamCode` when Gemini closed) | Gemini closed abnormally or before the conversation started, a send to Gemini failed, or no `setupComplete` within 15 s |
| 1011 | `voice_unavailable` | The ledger could not be charged after 3 attempts or within 5 s total, or an internal error |
| 4402 | `voice_quota_exceeded` | A block could not be paid |
| 4408 | `session_limit` | 15 minutes after `setupComplete` |

`detail` uses the `/gemini` allow-list format, `<reason>:<json.path>`, for example `unsupported_field:setup.tools[0].googleSearch` or `unsupported_message:setup`. Apps should treat an unknown `fudVoice.error` as a generic failure.

### Metering

- Time is charged in **15-second blocks from `setupComplete` until close, each block when it starts**: block 0 is paid before `setupComplete` is forwarded to the app. A 15-minute session is exactly 60 blocks; the block that would start at 15:00 closes the session with 4408 instead.
- A block comes from the **monthly voice allowance** (Plus 20 min = 1 200 s, Pro 100 min = 6 000 s, per UTC calendar month) while a whole block of it is left, and otherwise from the **credit bank at 1 credit per block** (4 credits a minute), the same bank hosted actions use. With neither, the session closes with 4402.
- Charging is a compare-and-swap on the ledger row `version`, like `spendFromLedger`, so voice sessions and hosted actions of one user running at once cannot double-spend. A D1 failure is retried twice (after 250 ms and 1 s), with one stable random receipt per block. The debit and receipt commit in one transaction, so retrying after a committed write whose response was lost cannot debit again, even when that was the last available credit. The conversation can continue during this short retry window; a five-second total charge deadline closes both sockets if D1 stalls. An independent timer enforces the 15-minute session cap even while a charge is pending. Every charge is handed to `ctx.waitUntil` so a disconnect cannot cancel a write in flight. Concurrent sessions per subscriber are not capped: each pays for its own blocks, so parallel sessions only drain that subscriber's balance faster (while sharing Gemini's per-project session limit).
- Blocks are **not refunded**: each one pays for the provider time that follows it. A reconnect (for example session resumption after Gemini's `goAway`) is a new session that starts with a new block.
- The allowance lives in two ledger columns added by migration `0004_hosted_ai_voice.sql`: `voice_month` (`YYYY-MM`) and `voice_seconds_used` (allowance seconds used in that month; a new month starts from zero). Credit-paid blocks only raise `credits_spent`. Every charge bumps `updated_at`, so the 180-day retention rule and its exception for rows that spent credits apply unchanged.
- Migration `0005_hosted_ai_voice_spends.sql` adds random billing receipts with the user hash, charge source (`allowance` / `credits`) and timestamp. These contain no conversation content, are retained for 30 days, and are pruned by the hourly cleanup.
- `GET /quota` returns the voice balance next to the action quota:

  ```json
  {
    "quota": { "plan": "plus", "day": "2026-10-16", "dailyUsed": 4, "dailyLimit": 30, "creditBank": 150 },
    "voice": { "month": "2026-10", "secondsUsed": 315, "secondsLimit": 1200, "creditBank": 150 }
  }
  ```

  `secondsUsed` counts allowance seconds only, and `creditBank` is the same shared bank as `quota.creditBank`.

### Runtime design

The relay runs in the **stateless Worker invocation that accepted the upgrade, without a Durable Object**:

- Sessions share nothing but the ledger, which the D1 compare-and-swap already serializes. A Durable Object would add a hop and per-session duration billing without coordinating anything.
- An HTTP invocation has no wall-time limit while the client stays connected, and Workers runtime updates give open connections up to 20 minutes to finish, more than the 15-minute session cap. A Durable Object instead drops all of its WebSockets when it restarts on a deploy.
- A stateless invocation's CPU limit covers the whole session (Durable Objects reset it per message; plain Workers do not), so per-message work is kept small: client messages are parsed once and server messages are parsed only when they may contain a resumption update. The subscriber-bound handle code has not been profiled in production; `wrangler.toml` keeps the default CPU limit. If `exceededCpu` outcomes ever appear on `/live`, raise `[limits] cpu_ms`.
- The compatibility date (`2026-07-10`) already brings `web_socket_auto_reply_to_close` (no half-open mode is needed: each side's close is mirrored to the other) and Blob as the default `binaryType`, which the relay sets back to `arraybuffer` on both sockets before `accept()`. No compatibility flags are added.
- The upstream handshake timeout is an `AbortController` cancelled as soon as the response arrives, so it can never tear down an established socket.

### Logging

No conversation content or resumption handles are stored or logged. The ledger retains quota counters and the random billing receipts described above. Log lines carry structured fields only (event, codes, phase, `platform`, 12-character user-hash prefix): `hosted_ai_live_rejected` (policy closes, with the sanitized `detail`), `hosted_ai_live_upstream_failed` (the Gemini close code, never its reason text), `hosted_ai_live_charge_failed`, `hosted_ai_live_connect_failed`, `hosted_ai_live_misconfigured`, and `hosted_ai_live_internal_error`. Sessions that end normally log nothing.

## Required secrets (Wrangler)

Set on the `fud-ai` worker (Dashboard → Workers → fud-ai → Settings → Variables, or `wrangler secret put`). **None of these are needed for the repo to build or for `npm test`, which runs against mocks.**

| Variable | Description | When |
|----------|-------------|------|
| `REVENUECAT_API_KEY` | A RevenueCat key that can read `GET /v1/subscribers`: a **v1** secret key (`sk_…`), or the app's public SDK key (production currently uses the Android `goog_…` key, which is read-only and already ships in the app). A **v2** secret key does not work here (RevenueCat answers 403, code 7723), and every hosted request fails with `entitlement_unavailable`. Never ship a secret key in an app binary. | Before enabling hosted mode |
| `GEMINI_API_KEY` | Google AI Studio key for Flash-Lite and for the voice relay (Gemini Live, sent in the `x-goog-api-key` header) | Production time |
| `DEEPGRAM_API_KEY` | Deepgram API key for hosted voice STT | Production time |
| `HOSTED_AI_LIVE_MODEL` | *Optional plain variable, not a secret.* Gemini Live model the voice relay pins, without the `models/` prefix; defaults to `gemini-3.8-live`. Set it under `[vars]` in `web/wrangler.toml` (a dashboard-only value is wiped by the next deploy). A malformed value makes `/live` answer 503 `voice_unavailable`. | Only to change the model |
| `HOSTED_AI_TRANSCRIBE_MODEL` | *Optional plain variable, not a secret.* `nova-2` (default) or `nova-3`. Anything else fails before the request is charged. Compare WAV/m4a, fixed language, Automatic, latency and metering on staging before promoting `nova-3`. | Only to change recorded dictation |

```bash
cd web
npx wrangler secret put REVENUECAT_API_KEY
npx wrangler secret put GEMINI_API_KEY        # at production time
npx wrangler secret put DEEPGRAM_API_KEY      # at production time
npx wrangler secret delete FUD_HOSTED_AI_APP_SECRET   # retire the leaked shared secret
```

Until the provider keys are set, the proxy responds `503 gemini_not_configured` / `deepgram_not_configured` without metering the request. No RevenueCat webhook is required for this design (entitlements are pulled, not pushed), so no webhook secret exists.

## Deploy checklist

1. Apply the D1 migrations to the `fud-ai-challenge` database (the hosted ledger shares it) **before** deploying the Worker. Apply `0004_hosted_ai_voice.sql` for voice columns, `0005_hosted_ai_voice_spends.sql` for voice receipts, and `0006_hosted_ai_trials.sql` for the `trial_expires_at` allowance data. These migrations are additive.

   ```bash
   cd web
   npx wrangler d1 migrations apply fud-ai-challenge --remote
   ```

2. The `HOSTED_AI_USER_RATE_LIMITER` / `HOSTED_AI_ADDRESS_RATE_LIMITER` bindings are declared in `web/wrangler.toml` (namespace ids 61005/61006) and are created on deploy.
3. `run_worker_first` in `web/wrangler.toml` already includes `/api/hosted-ai/v1/*`. The voice relay needs no other `wrangler.toml` change: no Durable Object, binding, compatibility flag or `[limits]` entry (see [Runtime design](#runtime-design)).
4. Set the secrets above, then `npx wrangler deploy`.
5. Run the live checks below. `scripts/hosted_ai_smoke.sh` covers hosted actions and the voice balance; `scripts/hosted_ai_voice_smoke.mjs` checks the live WebSocket relay with a synthetic customer. Unit tests run against mocks and cannot catch a missing migration or a wrong RevenueCat key. Verify microphone, camera, playback and app controls separately in the apps.

## Hosted limits

- Model pinned to `gemini-3.5-flash-lite` (worker constant; update when Flash-Lite GA name changes)
- Plus: **30** actions per UTC day; Pro: **60**. Credits are spent only after the daily pool is exhausted and only while a plan is active.
- Max **3** images per hosted request (BYOK allows up to 10)
- Voice food in Hosted mode = **2** actions (STT + LLM); a coach message costs one action per Gemini round (tool calls included)
- Voice assistant: Plus **20** minutes, Pro **100** minutes per UTC calendar month, then **1** credit per 15-second block; sessions end after 15 minutes. Model pinned to `gemini-3.8-live` unless `HOSTED_AI_LIVE_MODEL` is set.
- Ledger rows idle for 180 days are pruned by the hourly cron, except rows that have spent purchased credits (kept so consumed credits cannot reappear)

## Local smoke test

```bash
cd web
npm test -- hosted-ai
```

Against the deployed Worker, end to end (creates a throwaway Plus customer, then checks quota, text, one and two photos, a Coach tool call, Android WAV and iOS m4a voice, and metering; exits non-zero on the first failure):

```bash
RC_V2_SECRET_KEY=sk_... scripts/hosted_ai_smoke.sh
```

For the live voice relay, first create an isolated RevenueCat anonymous test customer with an active Plus or Pro entitlement. Supply its identity through the `FUD_VOICE_SMOKE_USER_ID` environment variable without printing it. After installing the existing web dependencies, run from the repository root:

```bash
node scripts/hosted_ai_voice_smoke.mjs
```

The check requires a setup acknowledgement, a harmless synthetic function call and response, audio bytes, nonempty output transcription and quota changes in 15-second blocks. If the provider issues a signed resumption handle, it reconnects and requires a second acknowledgement; otherwise the summary explicitly reports `not_issued`. It normally takes about 20 seconds and spends 45 seconds of voice allowance, with a 60-second deadline. It never plays or saves audio and prints only summary counters or error codes, never identities, handles, transcripts or response bodies. Revoke the temporary test entitlement after testing; this script does not create or revoke customers or grants.

Manual requests (need a real RevenueCat app user id with an active entitlement):

```bash
curl -sS "https://fud-ai.app/api/hosted-ai/v1/quota" \
  -H 'X-Fud-User-Id: $RCAnonymousID:0123456789abcdef0123456789abcdef'

curl -sS -X POST "https://fud-ai.app/api/hosted-ai/v1/generate" \
  -H 'X-Fud-User-Id: $RCAnonymousID:0123456789abcdef0123456789abcdef' \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Reply with exactly: ok"}' -i
```
