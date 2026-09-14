#!/usr/bin/env node
/**
 * Register the guild `/ask` slash command for Fud AI Discord.
 *
 * Env:
 *   DISCORD_BOT_TOKEN
 *   DISCORD_APPLICATION_ID
 *   DISCORD_GUILD_ID
 */
const token = process.env.DISCORD_BOT_TOKEN?.trim();
const appId = process.env.DISCORD_APPLICATION_ID?.trim();
const guildId = process.env.DISCORD_GUILD_ID?.trim();

if (!token || !appId || !guildId) {
  console.error("Need DISCORD_BOT_TOKEN, DISCORD_APPLICATION_ID, DISCORD_GUILD_ID");
  process.exit(1);
}

const body = {
  name: "ask",
  description: "Ask the Fud AI helper (Gemini)",
  options: [
    {
      type: 3,
      name: "question",
      description: "Your question about Fud AI",
      required: true,
    },
  ],
};

const url = `https://discord.com/api/v10/applications/${appId}/guilds/${guildId}/commands`;
const resp = await fetch(url, {
  method: "POST",
  headers: {
    Authorization: `Bot ${token}`,
    "Content-Type": "application/json",
  },
  body: JSON.stringify(body),
});

const text = await resp.text();
if (!resp.ok) {
  console.error(resp.status, text);
  process.exit(1);
}
console.log("Registered /ask:", text);
