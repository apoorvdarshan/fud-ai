#!/usr/bin/env node
/**
 * Register the guild `/bug` slash command for Fud AI Discord.
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
  name: "bug",
  description: "Report a Fud AI bug (opens a GitHub issue)",
  options: [
    {
      type: 3,
      name: "title",
      description: "Short title for the bug",
      required: true,
    },
    {
      type: 3,
      name: "details",
      description: "What happened, including steps to reproduce",
      required: true,
    },
    {
      type: 3,
      name: "device",
      description: "Device and OS (optional)",
      required: false,
    },
    {
      type: 3,
      name: "app_version",
      description: "App version from Settings → About (optional)",
      required: false,
    },
    {
      type: 3,
      name: "platform",
      description: "Where you saw it (optional — inferred from iOS/Android channels)",
      required: false,
      choices: [
        { name: "iOS", value: "iOS" },
        { name: "Android", value: "Android" },
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
console.log("Registered /bug:", text);
