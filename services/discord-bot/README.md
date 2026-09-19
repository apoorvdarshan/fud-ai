# Fud AI Discord bot

Community help lives in the [Fud AI Discord server](https://discord.gg/Py4VrFctP3). Use **`/ask`** for help from the Fud AI bot, or **`/bug`** to file a GitHub issue — both reply in-channel via Cloudflare (always online; no Mac required).

## Production (Cloudflare — no Mac required)

Slash commands **`/ask`** and **`/bug`** are handled by the main `fud-ai.app` Worker:

- Endpoint: `https://fud-ai.app/api/discord/interactions`
- Code: `web/discord-interactions.ts`
- Secrets:
  - `DISCORD_GEMINI_API_KEY` for `/ask` only (free-tier). Does **not** use `GEMINI_API_KEY` (hosted/billed).
  - `GITHUB_TOKEN` for `/bug` issue creation (same Worker secret as star-history; needs `issues:write` on `apoorvdarshan/fud-ai`).
- Vars in `web/wrangler.toml`: `DISCORD_PUBLIC_KEY`, `DISCORD_APPLICATION_ID`

### One-time Discord portal steps

1. [Developer Portal](https://discord.com/developers/applications) → Fud AI → **General Information**
2. Set **Interactions Endpoint URL** to:
   `https://fud-ai.app/api/discord/interactions`
3. Save (Discord sends a PING; Worker must answer with type `1`)

### Register `/ask` (guild — instant)

```bash
export DISCORD_BOT_TOKEN='…'   # from Discord portal; never commit
export DISCORD_APPLICATION_ID=1548469419922038845
export DISCORD_GUILD_ID=1548469034570354709
node services/discord-bot/register-ask.mjs
```

`/bug` is already registered on this guild (options: `title`, `details`, required; `device`, `app_version`, `platform` optional). Platform is inferred from the iOS (`1548481436129165353`) or Android (`1548481448024084540`) channel when omitted. Issues get labels `bug` plus `ios` or `android`.

### Deploy Worker secrets + code

```bash
cd web
echo 'YOUR_FREE_GEMINI_KEY' | npx wrangler secret put DISCORD_GEMINI_API_KEY
npx wrangler secret put GITHUB_TOKEN
npx wrangler deploy
```

`GITHUB_TOKEN` is the existing star-history secret. For `/bug` it must also be allowed to create issues on `apoorvdarshan/fud-ai` (`issues:write`, plus the `ios` / `android` labels on that repo).

In Discord: `/ask question: How do I add my Gemini key?`

In Discord: `/bug title: Crash on save details: Steps…` (optional `device`, `app_version`, `platform`).

## Optional local gateway bot (`@mention`)

The Python gateway bot under `~/Documents/fud-ai-discord-bot` (or a future `gateway/` folder) is **optional** and **not** required for `/ask`. Do not leave it running on a laptop if Cloudflare `/ask` is enough — sleep/offline kills mentions only; `/ask` stays online on Cloudflare.
