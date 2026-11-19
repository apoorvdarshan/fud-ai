// Kept identical in Fuddy and Vercie's workers; each deployment has its own DO.
const DAY = 86_400_000;
const RESET = 30 * DAY;
const OWNER = "823100527675965480";
const API = "https://discord.com/api/v10";
const REQUIRED = (1n << 2n) | (1n << 13n) | (1n << 40n);
const snowflake = ms => ((BigInt(ms) - 1420070400000n) << 22n).toString();
const timestamp = id => Number((BigInt(id) >> 22n) + 1420070400000n);
const compare = (a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1;

export async function fingerprint(text) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text.trim().toLowerCase().replace(/\s+/g, " ")));
  return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export function moderationText(text) {
  const visible = [];
  for (const line of text.replace(/```[\s\S]*?(?:```|$)/g, " ").split("\n")) {
    // Discord's >>> quotes the remainder of the message, not just this line.
    if (/^\s*>>>/.test(line)) break;
    if (!/^\s*>/.test(line)) visible.push(line);
  }
  return visible.join("\n");
}

/** Deterministic rules, no model and no following links or instructions in chat. */
export function classify(message, recent, hash, at) {
  const text = moderationText(message.content || "");
  const mentions = new Set((message.mentions || []).filter(u => !u.bot).map(u => u.id));
  if (mentions.size >= 6 || new Set(message.mention_roles || []).size >= 3) {
    return { violation: "mass mentions" };
  }
  const links = [...text.matchAll(/https?:\/\/([^\s/<>]+)[^\s<>]*/gi)];
  const externalLink = links.some(link => {
    try { return !/^(?:www\.)?(?:discord\.com|discord\.gift)$/i.test(new URL(link[0]).hostname); }
    catch { return false; }
  });
  if (/^(?:@everyone\s+|@here\s+)?(?:claim|get) (?:your )?free (?:discord )?nitro\b/i.test(text.trim())
    && externalLink) {
    return { violation: "free-Nitro solicitation linking outside Discord" };
  }
  const promotional = /\b(join my|join our|buy now|dm me to buy|hire me|subscribe to my|claim (?:your )?free nitro)\b/i.test(text);
  // Channels can be scanned out of timestamp order. Count only real windows
  // containing this message, not all neighbors in a +/- window twice as wide.
  const times = [...recent.filter(x => x.hash === hash).map(x => x.at), at].sort((a, b) => a - b);
  const repeated = (count, window) => times.some((start, i) => {
    const end = times[i + count - 1];
    return end !== undefined && end - start <= window && start <= at && end >= at;
  });
  if (text.trim().length >= 20 && repeated(5, 60_000)) {
    return { violation: "repeated identical messages (5 within 60 seconds)" };
  }
  if (links.length && promotional && repeated(3, 300_000)) {
    return { violation: "repeated promotional message (3 within 5 minutes)" };
  }
  if (links.length && promotional) return { flag: "possible unsolicited promotion; no strike" };
  return {};
}

export function nextStrike(user, at, now) {
  const fresh = !user.lastStrike || now - user.lastStrike >= RESET;
  // Messages from the same burst, including cross-channel backlog, are one incident.
  if (!fresh && at <= user.nextStrikeAt) return null;
  const count = fresh ? 1 : Math.min(3, user.strikes + 1);
  return { count, until: count === 1 ? now + 600_000 : count === 2 ? now + DAY : 0 };
}

class DiscordError extends Error {
  constructor(status, retryAt = 0) { super(`discord_http_${status}`); this.status = status; this.retryAt = retryAt; }
}

export async function runDiscordModeration(env) {
  if (env.DISCORD_MODERATION_ENABLED !== "true") return;
  if (!env.DISCORD_MODERATION) throw new Error("discord_moderation_binding_missing");
  const id = env.DISCORD_MODERATION.idFromName(env.DISCORD_GUILD_ID);
  const response = await env.DISCORD_MODERATION.get(id).fetch("https://moderation.internal/scan", { method: "POST" });
  if (!response.ok) throw new Error(`discord_moderation_scan_${response.status}`);
}

/** A single serialized, strongly consistent coordinator per server. Not publicly routed. */
export class DiscordModeration {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; this.running = false; }

  async api(path, method = "GET", body, missingOK = false) {
    if (Date.now() > this.deadline) throw new Error("scan_budget_exhausted");
    const response = await fetch(API + path, {
      method, redirect: "manual", signal: AbortSignal.timeout(8000),
      headers: { Authorization: `Bot ${this.env.DISCORD_BOT_TOKEN}`, "Content-Type": "application/json", "User-Agent": "CommunityModeration/1.0",
        ...(method !== "GET" ? { "X-Audit-Log-Reason": "Community anti-spam policy" } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (response.status === 404 && missingOK) return null;
    if (!response.ok) {
      let retryAt = 0;
      if (response.status === 429) {
        const data = await response.json().catch(() => ({}));
        retryAt = Date.now() + Math.max(1000, Math.min(DAY, Number(data.retry_after || 60) * 1000));
        await this.ctx.storage.put("retryAt", retryAt);
      }
      throw new DiscordError(response.status, retryAt);
    }
    return response.status === 204 ? null : response.json();
  }

  async fetch(request) {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/scan") return new Response("Not found", { status: 404 });
    if (this.env.DISCORD_MODERATION_ENABLED !== "true") return Response.json({ enabled: false });
    if (this.running) return Response.json({ busy: true });
    this.running = true;
    this.deadline = Date.now() + 45_000;
    try {
      if ((await this.ctx.storage.get("retryAt") || 0) > Date.now()) return Response.json({ rateLimited: true });
      await this.scan();
      await this.ctx.storage.put("lastSuccessfulScan", Date.now());
      console.log("discord_moderation_scan_ok");
      return Response.json({ ok: true });
    } catch (error) {
      // Never log message bodies, Discord responses, headers, or tokens.
      console.warn("discord_moderation_scan_failed", error instanceof DiscordError ? error.status : "configuration_or_storage_or_network");
      return Response.json({ ok: false }, { status: 503 });
    } finally { this.running = false; }
  }

  async preflight() {
    const env = this.env;
    if (!/^\d{17,20}$/.test(env.DISCORD_GUILD_ID || "") || !/^\d{17,20}$/.test(env.DISCORD_MODERATION_LOG_CHANNEL_ID || "") || !env.DISCORD_BOT_TOKEN) throw new Error("missing_config");
    const [guild, bot, roles, log, app] = await Promise.all([
      this.api(`/guilds/${env.DISCORD_GUILD_ID}`), this.api("/users/@me"),
      this.api(`/guilds/${env.DISCORD_GUILD_ID}/roles`), this.api(`/channels/${env.DISCORD_MODERATION_LOG_CHANNEL_ID}`),
      this.api("/oauth2/applications/@me"),
    ]);
    if (guild.owner_id !== OWNER || bot.id !== env.DISCORD_APPLICATION_ID || log.guild_id !== guild.id) throw new Error("wrong_identity");
    if (!(app.flags & ((1 << 18) | (1 << 19)))) throw new Error("message_content_intent_missing");
    const everyone = roles.find(r => r.id === guild.id);
    const denyEveryone = log.permission_overwrites?.find(o => o.id === guild.id && o.type === 0);
    // Refuse to put moderation metadata in a public channel.
    if (!denyEveryone || !(BigInt(denyEveryone.deny) & 1024n)) throw new Error("log_not_private");
    if (log.permission_overwrites.some(o => o.id !== bot.id && o.id !== OWNER && o.id !== guild.id && (BigInt(o.allow) & 1024n))) throw new Error("log_has_other_access");
    const member = await this.api(`/guilds/${guild.id}/members/${bot.id}`);
    const permissions = member.roles.reduce((p, id) => p | BigInt(roles.find(r => r.id === id)?.permissions || "0"), BigInt(everyone?.permissions || "0"));
    if (!(permissions & 8n) && (permissions & REQUIRED) !== REQUIRED) throw new Error("moderation_permissions_missing");
    this.guild = guild; this.roles = roles; this.bot = bot;
    this.botPosition = Math.max(0, ...member.roles.map(id => roles.find(r => r.id === id)?.position || 0));
  }

  async exempt(userId, allowAbsent = false) {
    if (userId === OWNER || userId === this.guild.owner_id || userId === this.bot.id) return true;
    const member = await this.api(`/guilds/${this.guild.id}/members/${userId}`, "GET", undefined, true);
    if (!member) return !allowAbsent;
    if (member.user?.bot) return true;
    const roles = this.roles.filter(r => member.roles.includes(r.id));
    return roles.some(r => (BigInt(r.permissions) & 8n) || r.position >= this.botPosition);
  }

  async scan() {
    await this.preflight();
    let started = await this.ctx.storage.get("started");
    if (!started) {
      started = Date.now();
      await this.ctx.storage.put("started", started);
    }
    // Retry saved actions before accepting new incidents. Never increment on a retry.
    if (!await this.deliver()) return;
    const channels = await this.api(`/guilds/${this.guild.id}/channels`);
    const active = await this.api(`/guilds/${this.guild.id}/threads/active`);
    const monitored = [...channels.filter(c => [0, 5].includes(c.type)), ...(active.threads || [])]
      .filter(c => c.id !== this.env.DISCORD_MODERATION_LOG_CHANNEL_ID);
    // Rotate starting channel, so a busy first channel cannot starve the others.
    const offset = (await this.ctx.storage.get("channelOffset") || 0) % Math.max(1, monitored.length);
    const ordered = [...monitored.slice(offset), ...monitored.slice(0, offset)];
    for (const channel of ordered) {
      if (Date.now() > this.deadline - 10_000) break;
      await this.ctx.storage.put("channelOffset", (monitored.indexOf(channel) + 1) % monitored.length);
      const cursor = await this.ctx.storage.get(`cursor:${channel.id}`) || snowflake(started);
      let messages;
      try { messages = await this.api(`/channels/${channel.id}/messages?after=${cursor}&limit=100`); }
      catch (e) { if (e instanceof DiscordError && [403, 404].includes(e.status)) { console.warn("discord_moderation_channel_unreadable", channel.id); continue; } throw e; }
      for (const message of messages.sort(compare)) {
        if (Date.now() > this.deadline - 10_000) break;
        if (BigInt(message.id) <= BigInt(cursor)) continue;
        await this.process(message, channel.id, started);
        if (!await this.deliver()) return;
      }
    }
    // Delete expired metadata in bounded batches; raw messages are never persisted.
    const cleanupCursor = await this.ctx.storage.get("cleanupCursor");
    const users = await this.ctx.storage.list({ prefix: "user:", limit: 1000, ...(cleanupCursor ? { startAfter: cleanupCursor } : {}) });
    for (const [key, user] of users) if (Date.now() - user.lastSeen > RESET) await this.ctx.storage.delete(key);
    if (users.size === 1000) await this.ctx.storage.put("cleanupCursor", [...users.keys()].at(-1));
    else await this.ctx.storage.delete("cleanupCursor");
  }

  async process(message, channelId, started) {
    const cursorKey = `cursor:${channelId}`;
    const at = timestamp(message.id);
    if (at < started || message.author.bot || message.webhook_id || ![0, 19].includes(message.type) || message.author.id === OWNER) {
      await this.ctx.storage.put(cursorKey, message.id); return;
    }
    const key = `user:${message.author.id}`;
    const now = Date.now();
    const user = await this.ctx.storage.get(key) || { strikes: 0, lastStrike: 0, nextStrikeAt: 0, recent: [], lastSeen: now, lastFlag: 0 };
    user.recent = user.recent.filter(x => Math.abs(at - x.at) <= 300_000);
    const hash = await fingerprint(moderationText(message.content || ""));
    const result = classify(message, user.recent, hash, at);
    user.recent = [...user.recent, { hash, at }].slice(-30);
    user.lastSeen = now;
    let pending;
    if ((result.violation || result.flag) && !await this.exempt(message.author.id)) {
      if (result.violation) {
        const strike = nextStrike(user, at, now);
        const previousStrike = { strikes: user.strikes, lastStrike: user.lastStrike, nextStrikeAt: user.nextStrikeAt };
        if (strike) {
          user.strikes = strike.count; user.lastStrike = now;
          user.nextStrikeAt = strike.until || now + RESET;
        }
        pending = { messageId: message.id, channelId, userId: message.author.id, reason: result.violation,
          count: strike?.count || 0, until: strike?.until || 0, step: 0, attempts: 0, created: now, previousStrike };
      } else if (now - user.lastFlag > DAY) {
        user.lastFlag = now;
        pending = { messageId: message.id, channelId, userId: message.author.id, reason: result.flag,
          count: 0, until: 0, step: 3, attempts: 0, created: now, flag: true };
      }
    }
    // Cursor, incident and strike commit together, before any Discord write.
    await this.ctx.storage.transaction(async tx => {
      await tx.put(cursorKey, message.id);
      await tx.put(key, user);
      if (pending) await tx.put("pending", pending);
    });
  }

  async post(channelId, content, nonce, users = []) {
    await this.api(`/channels/${channelId}/messages`, "POST", {
      content, allowed_mentions: { parse: [], users }, nonce, enforce_nonce: true,
    });
  }

  async deliver() {
    const p = await this.ctx.storage.get("pending");
    if (!p) return true;
    // Re-check owner, hierarchy and membership on every retry, before any side effect.
    if (p.userId === OWNER || (p.step < 2 && await this.exempt(p.userId, p.count === 3))) {
      await this.rollbackStrike(p);
      await this.ctx.storage.delete("pending"); return true;
    }
    try {
      if (p.step === 0) {
        await this.api(`/channels/${p.channelId}/messages/${p.messageId}`, "DELETE", undefined, true);
        p.step = 1; await this.ctx.storage.put("pending", p);
      }
      if (p.step === 1) {
        if (p.count && p.count < 3 && p.until <= Date.now()) {
          await this.abandon(p, "timeout window expired before enforcement"); return true;
        }
        if (p.count === 3) await this.api(`/guilds/${this.guild.id}/bans/${p.userId}`, "PUT", { delete_message_seconds: 0 });
        else if (p.count && p.until > Date.now()) {
          const member = await this.api(`/guilds/${this.guild.id}/members/${p.userId}`, "GET", undefined, true);
          if (!member) { await this.abandon(p, "member left before timeout enforcement"); return true; }
          const existingUntil = Date.parse(member.communication_disabled_until || "");
          if (!Number.isFinite(existingUntil) || existingUntil < p.until) {
            await this.api(`/guilds/${this.guild.id}/members/${p.userId}`, "PATCH", { communication_disabled_until: new Date(p.until).toISOString() });
          } else {
            p.until = existingUntil;
            const key = `user:${p.userId}`;
            await this.ctx.storage.transaction(async tx => {
              const user = await tx.get(key);
              if (user) await tx.put(key, { ...user, nextStrikeAt: Math.max(user.nextStrikeAt, existingUntil) });
              await tx.put("pending", p);
            });
          }
        }
        p.step = 2; await this.ctx.storage.put("pending", p);
      }
      if (p.step === 2) {
        if (p.count) {
          const outcome = p.count === 3 ? "Banned from the server." : `Timeout until <t:${Math.floor(p.until / 1000)}:f>.`;
          await this.post(p.channelId, `<@${p.userId}> Spam removed: ${p.reason}. Strike ${p.count}/3. ${outcome} Strikes reset after 30 days without another strike. Questions or appeals: contact the server owner.`, `w${p.messageId}`, [p.userId]);
        }
        p.step = 3; await this.ctx.storage.put("pending", p);
      }
      if (p.step === 3) {
        await this.post(this.env.DISCORD_MODERATION_LOG_CHANNEL_ID,
          `${p.flag ? "Flag only — no deletion, warning or strike" : p.count ? `Enforced strike ${p.count}/3` : "Removed additional spam from the same incident — no extra strike"}: ${p.reason}. User ID: ${p.userId}. https://discord.com/channels/${this.guild.id}/${p.channelId}/${p.messageId}`,
          `l${p.messageId}`);
        await this.ctx.storage.delete("pending");
      }
      return true;
    } catch (error) {
      if (error instanceof DiscordError && error.status === 429) return false;
      p.attempts += 1;
      if (p.attempts >= 5 || Date.now() - p.created > DAY) {
        console.warn("discord_moderation_action_failed", { step: p.step, status: error instanceof DiscordError ? error.status : "network_or_storage" });
        await this.abandon(p, "delivery failed after bounded retries");
        return true;
      }
      await this.ctx.storage.put("pending", p);
      return false;
    }
  }

  async abandon(p, reason) {
    await this.ctx.storage.put("lastFailure", { messageId: p.messageId, step: p.step, at: Date.now() });
    await this.rollbackStrike(p);
    try {
      await this.post(this.env.DISCORD_MODERATION_LOG_CHANNEL_ID,
        `Moderation incomplete: ${reason}. User ID: ${p.userId}, message ID: ${p.messageId}, failed step: ${p.step}. ${p.step < 2 ? "Punishment was not confirmed; strike reservation was rolled back." : "Punishment succeeded; notification delivery was incomplete."}`,
        `f${p.messageId}`);
    } catch { console.warn("discord_moderation_failure_notice_unavailable"); }
    await this.ctx.storage.delete("pending");
  }

  async rollbackStrike(p) {
    if (!p.count || p.step >= 2 || !p.previousStrike) return;
    const key = `user:${p.userId}`;
    const user = await this.ctx.storage.get(key);
    if (user) await this.ctx.storage.put(key, { ...user, ...p.previousStrike });
  }
}
