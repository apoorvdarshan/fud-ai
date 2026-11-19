# Fud AI Discord bot

Community help lives in the [Fud AI Discord server](https://discord.gg/Py4VrFctP3). Use **`/ask`** for help from the Fud AI bot, **`/bug`** to file a bug, or **`/feature`** to request an enhancement — all reply in-channel via Cloudflare (always online; no Mac required).

## Production (Cloudflare — no Mac required)

Slash commands **`/ask`**, **`/bug`**, and **`/feature`** are handled by the main `fud-ai.app` Worker:

- Endpoint: `https://fud-ai.app/api/discord/interactions`
- Code: `web/discord-interactions.ts`
- Secrets:
  - `DISCORD_GEMINI_API_KEY` for `/ask` replies and `/bug`/`/feature` issue drafting (free-tier). Does **not** use `GEMINI_API_KEY` (hosted/billed).
  - `GITHUB_TOKEN` for `/bug` and `/feature` issue creation (same Worker secret as star-history; needs `issues:write` on `apoorvdarshan/fud-ai`).
- Vars in `web/wrangler.toml`: `DISCORD_PUBLIC_KEY`, `DISCORD_APPLICATION_ID`
- KV binding: `DISCORD_STATE`, backed by the dedicated `FUDDY_DISCORD_STATE` namespace.

### Command cooldowns

- **`/ask`: one question per minute per user.**
- **`/bug` and `/feature`: an escalating wait per user, shared between both commands.** The first report starts a 10-minute wait. Each further report in a streak doubles it (20, 40, 80 minutes…) up to 24 hours. Two days without a report ends the streak. A bug report also starts the feature-request wait, and vice versa, and the reply tells the user how long is left. A report sent while the user's previous one is still being reserved gets a reply that the previous one is still being sent.
- Cooldowns apply across channels within the same server, including admins. Direct messages use a separate scope for each user.
- Only valid commands start a cooldown. Once a command is accepted, upstream AI/GitHub failures still consume the cooldown and count toward the report streak; blocked attempts do not extend the stored timer or the streak.
- Blocked commands receive a wait message without calling Gemini or GitHub. If cooldown storage is unavailable, Fuddy asks users to try again later instead of processing an unmetered command.
- Failed reads release provisional local reservations. Failed or timed-out writes trigger attempt-specific cancellation after all writes settle, including late writes; pending cleanup returns unavailable rather than a normal cooldown. Cleanup is best effort during KV outages and propagation delays, with KV TTLs as the final fallback. Pending local guards remain until write settlement and cancellation finish; they are not evicted to admit new commands. Cancellation writes an immutable per-attempt marker instead of deleting shared keys. The marker lives as long as the longest record it cancels (two days for a report's cooldown key), so a cancelled report never blocks the user or counts toward their streak. A marker that fails to save is retried twice, after 0.5 and 2 seconds. Cooldown values stay numeric for older deployed readers, with ownership stored in KV metadata.
- Storage warnings identify the failing stage and timeout/operation-failure category without logging raw errors or credentials.
- KV stores hashed identifiers, random reservation ownership tokens, and expiry timestamps: 60 seconds for questions and 15 minutes for interaction replay markers. A report's cooldown key lives two days. Its metadata carries the times of the user's last 12 reports, and nothing else about them, so the next wait can escalate. No prompts, API keys, or interaction tokens are stored there. Private issue-closure routing also uses this namespace with the separate retention described below.
- Local reservations guard concurrent requests in one Worker instance. An instance keeps a cooldown it read from KV until the cooldown ends, so a stale read can't end it early. At most once a minute it checks whether the attempt behind the cooldown was cancelled, so a cancellation that lands later still lifts it. KV is eventually consistent, so cooldowns and replay protection across instances are best effort.

### One-time Discord portal steps

1. [Developer Portal](https://discord.com/developers/applications) → Fud AI → **General Information**
2. Set **Interactions Endpoint URL** to:
   `https://fud-ai.app/api/discord/interactions`
3. Save (Discord sends a PING; Worker must answer with type `1`)

### Register guild commands (instant)

```bash
export DISCORD_BOT_TOKEN='…'   # from Discord portal; never commit
export DISCORD_APPLICATION_ID=1548469419922038845
export DISCORD_GUILD_ID=1548469034570354709
node services/discord-bot/register-ask.mjs
node services/discord-bot/register-bug.mjs
node services/discord-bot/register-feature.mjs
```

- **`/ask`** — `question` required.
- **`/bug`** — `report` required (one freeform field, same UX as `/ask`). The Worker uses `DISCORD_GEMINI_API_KEY` (free-tier, never `GEMINI_API_KEY`) to draft a title + body; if Gemini fails it still files using the first short line / clipped excerpt and the raw report. Platform labels prefer clear signals in the report (and Gemini’s optional `platform` field), then the iOS (`1548481436129165353`) or Android (`1548481448024084540`) channel. If both platforms are mentioned, the channel is used when available; otherwise the issue is labeled `bug` only. Issues get `bug` plus `ios` or `android` when a platform is known.
- **`/feature`** — `report` required (same Gemini-then-fallback drafting). Platform is **not** inferred from channel. Issues get the `enhancement` label (same as the GitHub feature-request template). Works from any channel.

The same Worker wakes hourly and posts new store releases once in **#announcements**, each with its What's new notes.

- **Android** — reads the Play production track, but only after the public Play listing shows the release: the track API reports a version as `completed` while Google is still reviewing, so the store page is the source of truth. Open testing is not announced.
- **iOS** — reads App Store Connect for the version in `READY_FOR_SALE` (the one users can actually download) and its en-US release notes, so it is already a liveness signal. It uses a dedicated Customer Support API key ("Fud AI Discord announcer"), which can read versions and, through an `include`, their notes. The public iTunes lookup answers Cloudflare Workers with HTTP 403, so it is not used.

The first run only records the versions already live, so it does not announce them again. Secrets: `DISCORD_BOT_TOKEN`, `PLAY_SERVICE_ACCOUNT_JSON`, and `ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_PRIVATE_KEY` (the key's `.p8` contents; the key is kept in Bitwarden).

### Deploy Worker secrets + code

The production `DISCORD_STATE` binding is already in `web/wrangler.toml`. For a separate deployment, create a dedicated namespace with `npx wrangler kv namespace create FUDDY_DISCORD_STATE`, then bind its ID as `DISCORD_STATE`. No Discord command re-registration is needed for cooldown changes.

```bash
cd web
echo 'YOUR_FREE_GEMINI_KEY' | npx wrangler secret put DISCORD_GEMINI_API_KEY
npx wrangler secret put GITHUB_TOKEN
npx wrangler deploy
```

`GITHUB_TOKEN` is the existing star-history secret. For `/bug` and `/feature` it must also be allowed to create issues on `apoorvdarshan/fud-ai` (`issues:write`). `/bug` also uses the `ios` / `android` labels when a platform is known.

In Discord: `/ask question: How do I add my Gemini key?`

In Discord: `/bug report: Crash on save when I tap the checkmark.`

In Discord: `/feature report: Show remaining calories on the home widget.`

## Optional local gateway bot (`@mention`)

The Python gateway bot under `~/Documents/fud-ai-discord-bot` (or a future `gateway/` folder) is **optional** and **not** required for `/ask`. Do not leave it running on a laptop if Cloudflare `/ask` is enough — sleep/offline kills mentions only; `/ask` stays online on Cloudflare.

## GitHub issue closure replies

New `/bug` and `/feature` submissions privately map the GitHub issue number to the Discord submitter, channel, and original bot response. When that issue closes, the bot replies to that response and mentions only the submitter. It distinguishes completed, not planned, duplicate, and unspecified closure reasons; it never claims an app update has shipped. No mobile update is required.

- Webhook: `https://fud-ai.app/api/discord/github-issues`, JSON, GitHub **issues** events only, active, TLS verification enabled.
- Set a unique random `DISCORD_GITHUB_WEBHOOK_SECRET` in the Worker and the repository webhook's secret field. The handler validates SHA-256 HMAC before parsing, restricts the repository, and ignores pull requests/non-closure events. Bot token and KV must also be configured.
- Give the bot View Channel, Send Messages, and Read Message History in report channels (and Send Messages in Threads where applicable). If the original response was deleted, delivery falls back to a normal message in the same channel. Missing channel/access leaves the notification queued; it does not DM the user or post elsewhere.
- The private mapping and closure deduplication markers expire after two years. No interaction tokens, credentials, or report text are stored. Existing unmapped reports do not notify; no public-text inference or automatic historical backfill is performed.
- Webhooks enqueue before acknowledging. Discord failures retry every five minutes in bounded batches for up to seven days, independently of the hourly release checks. `issue-close:link:NUMBER` holds the private mapping; `issue-close:pending:NUMBER:TIMESTAMP` holds an unsent event; `issue-close:sent:NUMBER:TIMESTAMP` is the completion marker. Remove a mapping to stop future notifications for that report.
- Persistent completion markers, local in-flight guards, and Discord's deterministic message nonce suppress repeat deliveries. KV is eventually consistent and Discord nonce deduplication has a limited time window: this is best-effort delivery, not an exactly-once guarantee across crashes/outages. Reopened issues can notify on a later distinct closure.
- If enqueueing fails, the webhook returns 503. Inspect failed GitHub webhook deliveries and redeliver after recovery; GitHub does not automatically redeliver failed deliveries. If mapping persistence fails, a safe operational warning is emitted; issue creation is not retried.
- Test with a clearly labeled report, close its GitHub issue, and verify the response reference, mention, outcome, and no duplicate on webhook redelivery. Keep test messages/reports free of private data. Use the automated closure tests for invalid signatures, wrong repositories, unmapped issues, replay, retry, and privacy.

## Automatic moderation

See [MODERATION.md](./MODERATION.md) for the spam rules, 10-minute / 24-hour / ban escalation, owner exemption, private logs, deployment and recovery. Monitoring runs on Cloudflare approximately once a minute and never requires a laptop process.
