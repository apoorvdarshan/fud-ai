# Fud AI Discord bot

Community help lives in the [Fud AI Discord server](https://discord.gg/Py4VrFctP3). Use **`/ask`** for help from the Fud AI bot, **`/bug`** to file a bug, or **`/feature`** to request an enhancement — all reply in-channel via Cloudflare (always online; no Mac required).

## Production (Cloudflare — no Mac required)

Slash commands **`/ask`**, **`/bug`**, and **`/feature`** are handled by the main `fud-ai.app` Worker:

- Endpoint: `https://fud-ai.app/api/discord/interactions`
- Code: `web/discord-interactions.ts`
- Secrets:
  - `DISCORD_GEMINI_API_KEY` for `/ask` only (free-tier). Does **not** use `GEMINI_API_KEY` (hosted/billed).
  - `GITHUB_TOKEN` for `/bug` and `/feature` issue creation (same Worker secret as star-history; needs `issues:write` on `apoorvdarshan/fud-ai`).
- Vars in `web/wrangler.toml`: `DISCORD_PUBLIC_KEY`, `DISCORD_APPLICATION_ID`

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
- **`/bug`** — `title`, `details` required; `device`, `app_version`, `platform` optional. Platform is inferred from the iOS (`1548481436129165353`) or Android (`1548481448024084540`) channel when omitted. Issues get labels `bug` plus `ios` or `android`.
- **`/feature`** — `title`, `details` required; optional `platform` (`iOS` / `Android` / `both`). Platform is **not** inferred from channel. Issues get the `enhancement` label (same as the GitHub feature-request template). Works from any channel.

### Deploy Worker secrets + code

```bash
cd web
echo 'YOUR_FREE_GEMINI_KEY' | npx wrangler secret put DISCORD_GEMINI_API_KEY
npx wrangler secret put GITHUB_TOKEN
npx wrangler deploy
```

`GITHUB_TOKEN` is the existing star-history secret. For `/bug` and `/feature` it must also be allowed to create issues on `apoorvdarshan/fud-ai` (`issues:write`). `/bug` also uses the `ios` / `android` labels when a platform is known.

In Discord: `/ask question: How do I add my Gemini key?`

In Discord: `/bug title: Crash on save details: Steps…` (optional `device`, `app_version`, `platform`).

In Discord: `/feature title: Widget calories details: Show remaining calories on the home widget.` (optional `platform`).

## Optional local gateway bot (`@mention`)

The Python gateway bot under `~/Documents/fud-ai-discord-bot` (or a future `gateway/` folder) is **optional** and **not** required for `/ask`. Do not leave it running on a laptop if Cloudflare `/ask` is enough — sleep/offline kills mentions only; `/ask` stays online on Cloudflare.
