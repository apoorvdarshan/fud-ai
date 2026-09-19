#!/usr/bin/env node
/**
 * Register the guild `/feature` slash command for Fud AI Discord.
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
  name: "feature",
  description: "Request a Fud AI feature (opens a GitHub issue)",
  options: [
    {
      type: 3,
      name: "title",
      description: "Short title for the request",
      required: true,
    },
    {
      type: 3,
      name: "details",
      description: "What you want and why it would help",
      required: true,
    },
    {
      type: 3,
      name: "platform",
      description: "Where this should apply (optional — omit if none / unsure)",
      required: false,
      choices: [
        { name: "iOS", value: "iOS" },
        { name: "Android", value: "Android" },
        { name: "Both", value: "both" },
      ],
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
console.log("Registered /feature:", text);
