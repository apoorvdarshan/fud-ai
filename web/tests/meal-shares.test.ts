import { describe, expect, it, vi } from "vitest";
import worker from "../worker";
import { MEAL_SHARE_TTL } from "../meal-shares";

const payload = { v: 1, meals: [{ name: '🥚 Eggs <script>"&', calories: 124, protein: 10, carbs: 1, fat: 8,
  ingredients: [{ name: "Egg", grams: 80 }], customNote: "Keep this note", supplementalNutrients: { creatine: 0 } }] };
const id = "abcdefghijklmnopqrstuv";
function setup() {
  const shares = new Map<string, unknown>();
  const put = vi.fn(async (key: string, value: string) => { shares.set(key, JSON.parse(value)); });
  const get = vi.fn(async (key: string) => shares.get(key) ?? null);
  const createLimit = vi.fn().mockResolvedValue({ success: true });
  const readLimit = vi.fn().mockResolvedValue({ success: true });
  const assets = vi.fn().mockResolvedValue(new Response("static"));
  const env = { MEAL_SHARES: { put, get }, MEAL_SHARE_CREATE_RATE_LIMITER: { limit: createLimit },
    MEAL_SHARE_READ_RATE_LIMITER: { limit: readLimit }, ASSETS: { fetch: assets } } as unknown as Env;
  return { env, put, get, shares, createLimit, readLimit, assets };
}
const post = (body = JSON.stringify(payload), headers = {}) => new Request("https://fud-ai.app/api/meal-shares", {
  method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1", ...headers }, body,
});
const preview = (shareId = id, method = "GET") => new Request(`https://fud-ai.app/m/${shareId}`, { method });

describe("short meal shares", () => {
  it("creates unique short URLs with seven-day TTL and preserves the full legacy payload in the deep link", async () => {
    const { env, put } = setup();
    const before = Date.now();
    const response = await worker.fetch(post(), env);
    expect(response.status).toBe(201);
    const result = await response.json() as { url: string; expiresAt: string };
    expect(result.url).toMatch(/^https:\/\/www\.fud-ai\.app\/m\/[A-Za-z0-9_-]{22}$/);
    expect(Date.parse(result.expiresAt)).toBeGreaterThanOrEqual(before + MEAL_SHARE_TTL * 1000);
    expect(put.mock.calls[0][1]).toContain("Keep this note");
    expect(put).toHaveBeenCalledWith(result.url.split("/").at(-1), expect.any(String), { expirationTtl: 604800 });
    const page = await worker.fetch(new Request(result.url), env);
    expect(page.status).toBe(200);
    const html = await page.text();
    expect(html).toContain("🥚 Eggs &lt;script&gt;&quot;&amp;");
    expect(html).not.toContain('<script>');
    const encoded = html.match(/fudai:\/\/add-meal\?d=([A-Za-z0-9_-]+)/)![1];
    const bytes = Uint8Array.from(atob(encoded.replace(/-/g, "+").replace(/_/g, "/")), char => char.charCodeAt(0));
    expect(JSON.parse(new TextDecoder().decode(bytes))).toEqual(payload);
    const other = await (await worker.fetch(post(), env)).json() as { url: string };
    expect(other.url).not.toBe(result.url);
    expect(page.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(page.headers.get("Cache-Control")).toBe("no-store");
    expect(page.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
    expect(page.headers.get("X-Robots-Tag")).toContain("noindex");
  });
  it.each(["bad", "a/b", "../m/bad"])("rejects invalid IDs without KV access: %s", async value => {
    const { env, get } = setup();
    expect((await worker.fetch(preview(value), env)).status).toBe(404);
    expect(get).not.toHaveBeenCalled();
  });
  it("handles missing and expired shares and HEAD without exposing a payload", async () => {
    const { env, shares } = setup();
    expect((await worker.fetch(preview(), env)).status).toBe(404);
    shares.set(id, { payload, expiresAt: Date.now() - 1 });
    expect(await (await worker.fetch(preview(), env)).text()).toContain("expired");
    shares.set(id, { payload, expiresAt: Date.now() + 10000 });
    const response = await worker.fetch(preview(id, "HEAD"), env);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("");
  });
  it.each(["null", "{}", "broken", JSON.stringify({ ...payload, v: 2 }), JSON.stringify({ v: 1, meals: [] }),
    JSON.stringify({ v: 1, meals: [{ name: "bad", calories: -1 }] }), JSON.stringify({ v: 1, meals: Array(101).fill(payload.meals[0]) })])("rejects malformed payload %s", async body => {
    const { env, put } = setup();
    expect((await worker.fetch(post(body), env)).status).toBe(400);
    expect(put).not.toHaveBeenCalled();
  });
  it("enforces actual and declared byte limits and content type", async () => {
    const { env, put } = setup();
    expect((await worker.fetch(post("a".repeat(65537)), env)).status).toBe(413);
    expect((await worker.fetch(post("{}", { "Content-Length": "65537" }), env)).status).toBe(413);
    expect((await worker.fetch(post("{}", { "Content-Type": "text/plain" }), env)).status).toBe(415);
    expect(put).not.toHaveBeenCalled();
  });
  it("rejects cross-origin creation and unsupported methods", async () => {
    const { env, put } = setup();
    expect((await worker.fetch(post(undefined, { Origin: "https://evil.example" }), env)).status).toBe(403);
    const response = await worker.fetch(new Request("https://fud-ai.app/api/meal-shares"), env);
    expect(response.status).toBe(405);
    expect(response.headers.get("Allow")).toBe("POST");
    expect((await worker.fetch(preview(id, "DELETE"), env)).status).toBe(405);
    expect(put).not.toHaveBeenCalled();
  });
  it("rate limits writes and reads before storage access", async () => {
    const { env, createLimit, readLimit, put, get } = setup();
    createLimit.mockResolvedValue({ success: false });
    readLimit.mockResolvedValue({ success: false });
    const response = await worker.fetch(post(), env);
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("60");
    expect(createLimit).toHaveBeenCalledWith({ key: "192.0.2.1" });
    expect((await worker.fetch(preview(), env)).status).toBe(429);
    expect(put).not.toHaveBeenCalled();
    expect(get).not.toHaveBeenCalled();
  });
  it("returns a safe error when storage or rate limiting is unavailable", async () => {
    const { env, put, readLimit } = setup();
    put.mockRejectedValue(new Error("private data"));
    const response = await worker.fetch(post(), env);
    expect(response.status).toBe(503);
    expect(await response.text()).not.toContain("private data");
    readLimit.mockRejectedValue(new Error("binding unavailable"));
    expect((await worker.fetch(preview(), env)).status).toBe(503);
  });
  it("continues serving old long-link pages through static assets", async () => {
    const { env, assets } = setup();
    const request = new Request("https://fud-ai.app/add-meal?d=legacy");
    expect(await (await worker.fetch(request, env)).text()).toBe("static");
    expect(assets).toHaveBeenCalledWith(request);
  });
});
