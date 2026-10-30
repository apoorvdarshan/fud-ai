import { test, afterEach } from "vitest";
import assert from "node:assert/strict";
import { DiscordModeration, classify, fingerprint, nextStrike, runDiscordModeration } from "../discord-moderation.mjs";

const OWNER = "823100527675965480", GUILD = "1548469034570354709", BOT = "1548469419922038845";
const CHANNEL = "1548469037133078693", LOG = "1565585935792472084", USER = "405605637842534408";
const ROLE = "1548479768276500613", DAY = 86400000;
const nativeFetch = globalThis.fetch, nativeNow = Date.now;
afterEach(() => { globalThis.fetch = nativeFetch; Date.now = nativeNow; });
const id = (time, index = 0) => (((BigInt(time) - 1420070400000n) << 22n) + BigInt(index)).toString();

function setup() {
  let now = 1793330000000;
  Date.now = () => now;
  const data = new Map(), calls = [], messages = [];
  const storage = {
    get: async key => structuredClone(data.get(key)),
    put: async (key, value) => { data.set(key, structuredClone(value)); },
    delete: async key => data.delete(key),
    list: async ({ prefix, limit, startAfter }) => new Map([...data].filter(([k]) => k.startsWith(prefix) && (!startAfter || k > startAfter)).sort(([a], [b]) => a.localeCompare(b)).slice(0, limit)),
    transaction: async callback => {
      const before = structuredClone(data);
      try { return await callback(storage); }
      catch (e) { data.clear(); for (const [k, v] of before) data.set(k, v); throw e; }
    },
  };
  const env = { DISCORD_MODERATION_ENABLED: "true", DISCORD_GUILD_ID: GUILD, DISCORD_APPLICATION_ID: BOT,
    DISCORD_MODERATION_LOG_CHANNEL_ID: LOG, DISCORD_BOT_TOKEN: "unit-test-bot-token" };
  let override = () => undefined;
  globalThis.fetch = async (url, init) => {
    assert.equal(init.redirect, "manual");
    assert.equal(init.headers.Authorization, "Bot unit-test-bot-token");
    assert.ok(!url.includes("unit-test"));
    const path = new URL(url).pathname.replace("/api/v10", "");
    const method = init.method;
    const body = init.body ? JSON.parse(init.body) : undefined;
    calls.push({ path, method, body });
    const custom = override(path, method, body);
    if (custom !== undefined) return custom;
    if (method !== "GET") return method === "POST" ? Response.json({ id: id(now, 999) }) : new Response(null, { status: 204 });
    if (path === `/guilds/${GUILD}`) return Response.json({ id: GUILD, owner_id: OWNER });
    if (path === "/users/@me") return Response.json({ id: BOT, bot: true });
    if (path === "/oauth2/applications/@me") return Response.json({ flags: 1 << 19 });
    if (path === `/guilds/${GUILD}/roles`) return Response.json([{ id: GUILD, position: 0, permissions: "0" }, { id: ROLE, position: 2, permissions: ((1n << 40n) | 8192n | 4n).toString() }]);
    if (path === `/channels/${LOG}`) return Response.json({ guild_id: GUILD, permission_overwrites: [{ id: GUILD, type: 0, deny: "1024", allow: "0" }] });
    if (path.includes("/members/")) return Response.json({ user: { id: path.split("/").at(-1) }, roles: path.endsWith(BOT) ? [ROLE] : [] });
    if (path.endsWith("/threads/active")) return Response.json({ threads: [] });
    if (path === `/guilds/${GUILD}/channels`) return Response.json([{ id: CHANNEL, type: 0 }, { id: LOG, type: 0 }]);
    if (path.endsWith("/messages")) {
      const after = BigInt(new URL(url).searchParams.get("after"));
      return Response.json(messages.filter(m => m.channel_id === path.split("/")[2] && BigInt(m.id) > after).slice(0, 100).toReversed());
    }
    throw new Error(`Unexpected GET ${path}`);
  };
  const object = new DiscordModeration({ storage }, env);
  return { env, data, calls, messages, storage, object,
    override: fn => { override = fn; },
    advance: ms => { now += ms; },
    message: (content, extra = {}) => ({ id: id(now, messages.length + 1), channel_id: CHANNEL, type: 0, author: { id: USER }, content, mentions: [], ...extra }),
    scan: () => object.fetch(new Request("https://internal/scan", { method: "POST" })),
    now: () => now,
  };
}
const spam = f => f.message("hello", { mentions: Array.from({ length: 6 }, (_, n) => ({ id: String(100 + n) })) });
const writes = f => f.calls.filter(c => c.method !== "GET");

test("GitHub identity, ordinary links, issue logs and quoted promotions remain allowed", async () => {
  for (const content of ["This is me on github @candzon", "https://github.com/candzon", "The response failed: https://github.com/example/issues/1", "> join my server https://discord.gg/example", "```join my server https://example.com```", "Check the fix https://github.com/a/b/pull/42"]) {
    assert.deepEqual(classify({ content }, [], await fingerprint(content), Date.now()), {});
  }
});
test("unique actual mentions count; duplicate mentions and textual examples do not", () => {
  assert.equal(classify({ mentions: Array.from({ length: 6 }, (_, i) => ({ id: String(i) })) }, [], "x", 0).violation, "mass mentions");
  assert.deepEqual(classify({ content: "<@1> <@2>", mentions: Array(8).fill({ id: "1" }) }, [], "x", 0), {});
});
test("promotion is only flagged once; repetition crosses a narrow threshold", async () => {
  const content = "Join my server https://discord.gg/example", hash = await fingerprint(content), at = Date.now();
  assert.ok(classify({ content }, [], hash, at).flag);
  assert.ok(classify({ content }, [{ hash, at: at - 1000 }, { hash, at: at - 2000 }], hash, at).violation);
  assert.ok(!classify({ content }, [{ hash, at: at - 400000 }], hash, at).violation);
});
test("five matching long messages in a minute are spam; short chat and stale history are not", () => {
  const recent = Array.from({ length: 4 }, (_, n) => ({ hash: "h", at: 10000 + n }));
  assert.ok(classify({ content: "Repeated text long enough to be flooding" }, recent, "h", 11000).violation);
  assert.deepEqual(classify({ content: "ok" }, recent, "h", 11000), {});
  assert.deepEqual(classify({ content: "Repeated text long enough to be flooding" }, recent, "h", 400000), {});
});
test("first scan seeds activation; existing history is never moderated", async () => {
  const f = setup(); f.messages.push({ ...spam(f), id: id(f.now() - 1000) });
  assert.equal((await f.scan()).status, 200); assert.equal(writes(f).length, 0);
});
test("owner is exempt before member lookup and any write, even from mass mentions", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push({ ...spam(f), author: { id: OWNER } });
  await f.scan(); assert.equal(writes(f).length, 0);
  assert.ok(!f.calls.some(c => c.path.endsWith(`/members/${OWNER}`)));
});
test("bots, webhooks, system messages, administrators and higher roles are exempt", async () => {
  const f = setup(); await f.scan(); f.advance(1000);
  f.messages.push({ ...spam(f), author: { id: USER, bot: true } });
  f.messages.push({ ...spam(f), webhook_id: "123" });
  f.messages.push({ ...spam(f), type: 7 });
  f.messages.push(spam(f));
  f.override(path => path.endsWith(`/members/${USER}`) ? Response.json({ roles: [ROLE] }) : undefined);
  await f.scan(); assert.equal(writes(f).length, 0);
});
test("first strike deletes, times out for ten minutes, warns only offender and logs privately", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  assert.equal((await f.scan()).status, 200);
  const effects = writes(f); assert.equal(effects.length, 4);
  assert.equal(effects[0].method, "DELETE");
  assert.equal(effects[1].body.communication_disabled_until, new Date(f.now() + 600000).toISOString());
  assert.deepEqual(effects[2].body.allowed_mentions, { parse: [], users: [USER] });
  assert.equal(effects[2].body.enforce_nonce, true);
  assert.match(effects[2].body.content, /Strike 1\/3/);
  assert.equal(effects[3].path, `/channels/${LOG}/messages`);
  await f.scan(); assert.equal(writes(f).length, 4);
});
test("a burst adds no extra strikes; later violations get 24h timeout then ban", async () => {
  const f = setup(); await f.scan(); f.advance(1000);
  f.messages.push(spam(f)); f.messages.push(spam(f)); f.messages.push(spam(f));
  await f.scan(); assert.equal(f.data.get(`user:${USER}`).strikes, 1);
  assert.equal(writes(f).filter(c => c.method === "PATCH").length, 1);
  f.advance(600001); f.messages.push(spam(f)); await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 2);
  assert.equal(writes(f).filter(c => c.method === "PATCH").at(-1).body.communication_disabled_until, new Date(f.now() + DAY).toISOString());
  f.advance(DAY + 1); f.messages.push(spam(f)); await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 3);
  assert.equal(writes(f).filter(c => c.method === "PUT").length, 1);
  assert.deepEqual(writes(f).find(c => c.method === "PUT").body, { delete_message_seconds: 0 });
});
test("strikes reset after 30 quiet days and existing longer timeout is preserved", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f)); await f.scan();
  f.advance(30 * DAY + 1); f.messages.push(spam(f));
  f.override(path => path.endsWith(`/members/${USER}`) ? Response.json({ roles: [], communication_disabled_until: new Date(f.now() + DAY).toISOString() }) : undefined);
  await f.scan(); assert.equal(f.data.get(`user:${USER}`).strikes, 1);
  assert.equal(writes(f).filter(c => c.method === "PATCH").length, 1);
});
test("promotional ambiguity only produces one private flag per user per day", async () => {
  const f = setup(); await f.scan(); f.advance(1000);
  f.messages.push(f.message("Join my server https://discord.gg/example"));
  f.messages.push(f.message("Join our group https://example.com")); await f.scan();
  assert.equal(writes(f).length, 1); assert.equal(writes(f)[0].path, `/channels/${LOG}/messages`);
  assert.match(writes(f)[0].body.content, /no deletion, warning or strike/);
  assert.equal(f.data.get(`user:${USER}`).strikes, 0);
  assert.ok(!JSON.stringify([...f.data]).includes("Join my"));
});
test("rate limits save a retry deadline and resume the same strike without duplicate deletion", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.override((path, method) => method === "PATCH" ? Response.json({ retry_after: 120 }, { status: 429 }) : undefined);
  await f.scan(); const calls = f.calls.length;
  await f.scan(); assert.equal(f.calls.length, calls);
  f.advance(120001); f.override(() => undefined); await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 1);
  assert.equal(writes(f).filter(c => c.method === "DELETE").length, 1);
  assert.equal(f.data.get("pending"), undefined);
});
test("failed warning resumes without repeating punishment and uses a stable nonce", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.override((path, method) => method === "POST" && path.includes(CHANNEL) ? new Response(null, { status: 503 }) : undefined);
  await f.scan(); f.override(() => undefined); await f.scan();
  assert.equal(writes(f).filter(c => c.method === "PATCH").length, 1);
  const warnings = writes(f).filter(c => c.method === "POST" && c.path.includes(CHANNEL));
  assert.equal(warnings[0].body.nonce, warnings[1].body.nonce);
});
test("permission failures do not claim enforcement and abandoned punishments roll back strikes", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.override((path, method) => method === "PATCH" ? new Response(null, { status: 403 }) : undefined);
  for (let i = 0; i < 5; i++) await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 0);
  assert.ok(f.data.get("lastFailure"));
  assert.ok(writes(f).filter(c => c.method === "POST").every(c => c.path.includes(LOG) && c.body.content.includes("Moderation incomplete")));
});
test("storage failure prevents external enforcement and leaves cursor available for retry", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.storage.transaction = async () => { throw new Error("storage unavailable"); };
  assert.equal((await f.scan()).status, 503); assert.equal(writes(f).length, 0);
  assert.equal(f.data.get(`cursor:${CHANNEL}`), undefined);
});
test("wrong owner, bot identity, public logs or missing permissions fail closed", async () => {
  for (const bad of ["owner", "bot", "logs", "permissions", "intent"]) {
    const f = setup();
    f.override(path => {
      if (bad === "owner" && path === `/guilds/${GUILD}`) return Response.json({ id: GUILD, owner_id: USER });
      if (bad === "bot" && path === "/users/@me") return Response.json({ id: USER });
      if (bad === "logs" && path === `/channels/${LOG}`) return Response.json({ guild_id: GUILD, permission_overwrites: [] });
      if (bad === "permissions" && path.endsWith("/roles")) return Response.json([]);
      if (bad === "intent" && path === "/oauth2/applications/@me") return Response.json({ flags: 0 });
    });
    assert.equal((await f.scan()).status, 503); assert.equal(writes(f).length, 0);
  }
});
test("pagination continues beyond 100 messages and does not rescan processed messages", async () => {
  const f = setup(); await f.scan(); f.advance(1000);
  for (let i = 0; i < 101; i++) f.messages.push(f.message(`unique message ${i}`));
  await f.scan(); assert.equal(f.data.get(`cursor:${CHANNEL}`), f.messages[99].id);
  await f.scan(); assert.equal(f.data.get(`cursor:${CHANNEL}`), f.messages[100].id);
  assert.equal(writes(f).length, 0);
});
test("disabled monitoring never touches Discord or storage and namespace is guild-scoped", async () => {
  const f = setup(); f.env.DISCORD_MODERATION_ENABLED = "false";
  await f.scan(); await runDiscordModeration(f.env); assert.equal(f.calls.length, 0);
  await runDiscordModeration({ ...f.env, DISCORD_MODERATION_ENABLED: "true", DISCORD_MODERATION: {
    idFromName(name) { assert.equal(name, GUILD); return "id"; },
    get() { return { fetch: async () => new Response() }; },
  } });
});
test("timeout window boundaries do not escalate backlog", () => {
  assert.equal(nextStrike({ lastStrike: 1000, nextStrikeAt: 10000, strikes: 1 }, 10000, 11000), null);
  assert.equal(nextStrike({ lastStrike: 1000, nextStrikeAt: 10000, strikes: 1 }, 10001, 11000).count, 2);
});

test("scam solicitation is narrow; official gift links and discussion are not automatically punished", () => {
  assert.ok(classify({ content: "Claim your free Nitro https://discord.gift.attacker.invalid" }, [], "x", 0).violation);
  assert.ok(!classify({ content: "Claim your free Nitro https://discord.gift/abc" }, [], "x", 0).violation);
  assert.deepEqual(classify({ content: "Someone sent me a free Nitro scam https://example.com; is it safe?" }, [], "x", 0), {});
});
test("an object restart resumes pending delivery without issuing a second strike", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.override((path, method) => method === "POST" ? new Response(null, { status: 503 }) : undefined);
  await f.scan(); f.override(() => undefined);
  const restarted = new DiscordModeration({ storage: f.storage }, f.env);
  await restarted.fetch(new Request("https://internal/scan", { method: "POST" }));
  assert.equal(f.data.get(`user:${USER}`).strikes, 1);
  assert.equal(writes(f).filter(c => c.method === "PATCH").length, 1);
  assert.equal(f.data.get("pending"), undefined);
});
test("concurrent scan requests cannot reserve two strikes", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  await Promise.all([f.scan(), f.scan(), f.scan()]);
  assert.equal(f.data.get(`user:${USER}`).strikes, 1);
  assert.equal(writes(f).filter(c => c.method === "PATCH").length, 1);
});
test("a promotion to a protected role cancels a pending punishment", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.override((path, method) => method === "DELETE" ? new Response(null, { status: 503 }) : undefined);
  await f.scan();
  f.override(path => path.endsWith(`/members/${USER}`) ? Response.json({ roles: [ROLE] }) : undefined);
  await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 0);
  assert.equal(f.data.get("pending"), undefined);
  assert.ok(!writes(f).some(c => c.method === "PATCH"));
});
test("expired unconfirmed timeout is abandoned without a false success warning", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  f.override((path, method) => method === "PATCH" ? new Response(null, { status: 503 }) : undefined);
  await f.scan(); f.advance(600001); f.override(() => undefined); await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 0);
  assert.ok(writes(f).filter(c => c.method === "POST").every(c => c.path.includes(LOG)));
});

test("an older fifth copy scanned in another channel still triggers repetition", async () => {
  const f = setup(), second = "1548481448024084540";
  f.override(path => path === `/guilds/${GUILD}/channels` ? Response.json([{ id: CHANNEL, type: 0 }, { id: second, type: 0 }]) : undefined);
  await f.scan(); f.advance(1000);
  f.messages.push(f.message("Repeated long message across two channels", { channel_id: second }));
  for (let i = 0; i < 4; i++) { f.advance(1000); f.messages.push(f.message("Repeated long message across two channels")); }
  await f.scan(); assert.equal(f.data.get(`user:${USER}`).strikes, 1);
  assert.ok(writes(f).some(c => c.method === "DELETE" && c.path.includes(second)));
});
test("out-of-order repetition does not combine two disjoint windows", () => {
  const recent = [0, 1000, 90000, 91000].map(at => ({ hash: "h", at }));
  assert.deepEqual(classify({ content: "Repeated message that is long enough" }, recent, "h", 45000), {});
});
test("quoted examples are ignored but appending a quote does not hide an unquoted solicitation", () => {
  for (const suffix of ["\n> quoted text", "\n```some example```", "\n```unfinished example"]) {
    assert.ok(classify({ content: "Claim free Nitro https://example.invalid" + suffix }, [], "x", 0).violation);
  }
  assert.deepEqual(classify({ content: "> Claim free Nitro https://example.invalid" }, [], "x", 0), {});
  assert.deepEqual(classify({ content: "```Claim free Nitro https://example.invalid```" }, [], "x", 0), {});
  assert.ok(!classify({ content: "Claim free Nitro https://discord.gift:443/abc" }, [], "x", 0).violation);
});
test("a member leaving between checks gets no unserved strike or success warning", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  let lookups = 0;
  f.override(path => path.endsWith(`/members/${USER}`) && ++lookups >= 3 ? new Response(null, { status: 404 }) : undefined);
  await f.scan();
  assert.equal(f.data.get(`user:${USER}`).strikes, 0);
  assert.ok(!writes(f).some(c => c.method === "PATCH"));
  assert.ok(writes(f).filter(c => c.method === "POST").every(c => c.path.includes(LOG)));
});
test("a retained longer timeout is reported accurately and defines the incident window", async () => {
  const f = setup(); await f.scan(); f.advance(1000); f.messages.push(spam(f));
  const until = f.now() + DAY;
  f.override(path => path.endsWith(`/members/${USER}`) ? Response.json({ roles: [], communication_disabled_until: new Date(until).toISOString() }) : undefined);
  await f.scan();
  const warning = writes(f).find(c => c.method === "POST" && c.path.includes(CHANNEL));
  assert.ok(warning.body.content.includes(String(Math.floor(until / 1000))));
  assert.equal(f.data.get(`user:${USER}`).nextStrikeAt, until);
});
test("cleanup rotates past 1000 active records to remove expired member metadata", async () => {
  const f = setup();
  for (let i = 0; i < 1000; i++) f.data.set(`user:${String(i).padStart(4, "0")}`, { lastSeen: f.now() });
  f.data.set("user:zzzz", { lastSeen: f.now() - 31 * DAY });
  await f.scan(); assert.ok(f.data.has("user:zzzz"));
  await f.scan(); assert.ok(!f.data.has("user:zzzz"));
  assert.equal(f.data.get("cleanupCursor"), undefined);
});
