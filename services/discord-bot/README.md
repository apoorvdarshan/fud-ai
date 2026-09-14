# Fud AI Discord bot

## Production (Cloudflare — no Mac required)

Slash command **`/ask`** is handled by the main `fud-ai.app` Worker:

- Endpoint: `https://fud-ai.app/api/discord/interactions`
- Code: `web/discord-interactions.ts`
- Secrets: `DISCORD_GEMINI_API_KEY` (preferred) or existing `GEMINI_API_KEY`
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

### Deploy Worker secrets + code

```bash
cd web
echo 'YOUR_FREE_GEMINI_KEY' | npx wrangler secret put DISCORD_GEMINI_API_KEY
npx wrangler deploy
```

In Discord: `/ask question: How do I add my Gemini key?`

## Optional local gateway bot (`@mention`)

The Python gateway bot under `~/Documents/fud-ai-discord-bot` (or a future `gateway/` folder) is **optional** and **not** required for `/ask`. Do not leave it running on a laptop if Cloudflare `/ask` is enough — sleep/offline kills mentions only; `/ask` stays online on Cloudflare.
