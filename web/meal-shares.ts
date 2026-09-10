export const MEAL_SHARE_API = "/api/meal-shares";
export const MEAL_SHARE_TTL = 7 * 24 * 60 * 60;
const MAX_BODY_BYTES = 65_536;
const ID = /^[A-Za-z0-9_-]{22}$/;
const ORIGIN = "https://www.fud-ai.app";
const headers = {
  "Cache-Control": "no-store",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Robots-Tag": "noindex, nofollow, noarchive",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
};
interface Meal { name: string; calories: number; protein: number; carbs: number; fat: number; }
interface Payload { v: 1; meals: Meal[]; }
interface StoredShare { payload: Payload; expiresAt: number; }
class ShareError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}
function json(value: unknown, status: number, extra: HeadersInit = {}): Response {
  return Response.json(value, { status, headers: { ...headers, ...extra } });
}

export async function handleMealShareRequest(request: Request, env: Env): Promise<Response> {
  const path = new URL(request.url).pathname;
  try {
    const creating = path === MEAL_SHARE_API;
    const id = path.slice(3);
    if (!creating && !ID.test(id)) return json({ error: "Share not found." }, 404);
    const allowed = creating ? "POST" : "GET, HEAD";
    if (creating ? request.method !== "POST" : !["GET", "HEAD"].includes(request.method)) {
      return json({ error: "Method not allowed." }, 405, { Allow: allowed });
    }
    const origin = request.headers.get("Origin");
    if (creating && origin && ![ORIGIN, "https://fud-ai.app"].includes(origin)) {
      return json({ error: "Origin not allowed." }, 403);
    }
    const limiter = creating ? env.MEAL_SHARE_CREATE_RATE_LIMITER : env.MEAL_SHARE_READ_RATE_LIMITER;
    // Cloudflare supplies this header; never trust client-selected forwarding headers.
    const { success } = await limiter.limit({ key: request.headers.get("CF-Connecting-IP") ?? "unknown" });
    if (!success) return json({ error: "Too many requests. Try again shortly." }, 429, { "Retry-After": "60" });
    if (creating) {
      const payload = await readPayload(request);
      const id = base64url(crypto.getRandomValues(new Uint8Array(16)));
      const expiresAt = Date.now() + MEAL_SHARE_TTL * 1000;
      await env.MEAL_SHARES.put(id, JSON.stringify({ payload, expiresAt }), { expirationTtl: MEAL_SHARE_TTL });
      return json({ url: `${ORIGIN}/m/${id}`, expiresAt: new Date(expiresAt).toISOString() }, 201);
    }
    const stored = await env.MEAL_SHARES.get<StoredShare>(id, "json");
    if (!stored || stored.expiresAt <= Date.now()) {
      return page("Meal link unavailable", "<p>This meal link has expired or could not be found. Ask the sender to share it again.</p>", 404, request.method === "HEAD");
    }
    const encoded = base64url(new TextEncoder().encode(JSON.stringify(stored.payload)));
    const summary = stored.payload.meals.map(meal => `${meal.name} — ${meal.calories} kcal`);
    const title = summary.join(" · ").slice(0, 300);
    const meals = stored.payload.meals.map(meal => `<li><strong>${escapeHtml(meal.name)}</strong><br>${meal.calories} kcal · ${meal.protein}P · ${meal.carbs}C · ${meal.fat}F</li>`).join("");
    return page(title, `<h2>Shared meals</h2><ul>${meals}</ul>
      <p><a class="button" href="fudai://add-meal?d=${encoded}">Open in Fud AI</a></p>
      <p><a href="${ORIGIN}/add-meal?d=${encoded}">Try the app link</a></p>
      <p>This link expires after seven days. Importing adds a copy to your diary.</p>
      <p>Need the app? <a href="https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726">App Store</a> · <a href="https://play.google.com/store/apps/details?id=com.apoorvdarshan.calorietracker">Google Play</a></p>`, 200, request.method === "HEAD");
  } catch (error) {
    if (error instanceof ShareError) return json({ error: error.message }, error.status);
    // Do not log meal payloads, share IDs, or IP addresses.
    return json({ error: "Meal sharing is temporarily unavailable." }, 503);
  }
}

async function readPayload(request: Request): Promise<Payload> {
  if (request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
    throw new ShareError(415, "Content-Type must be application/json.");
  }
  if (Number(request.headers.get("Content-Length")) > MAX_BODY_BYTES) throw new ShareError(413, "Meal payload is too large.");
  if (!request.body) throw new ShareError(400, "Missing meal payload.");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY_BYTES) {
        await reader.cancel();
        throw new ShareError(413, "Meal payload is too large.");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let payload: Payload;
  try { payload = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes)); }
  catch { throw new ShareError(400, "Invalid JSON."); }
  if (!payload || payload.v !== 1 || !Array.isArray(payload.meals) || payload.meals.length < 1 || payload.meals.length > 100) {
    throw new ShareError(400, "Invalid version 1 meal payload.");
  }
  return { v: 1, meals: payload.meals.map(normalizeMeal) };
}

function normalizeMeal(meal: unknown): Meal {
  if (!meal || typeof meal !== "object") throw new ShareError(400, "Invalid version 1 meal payload.");
  const value = meal as Meal & Record<string, unknown>;
  if (typeof value.name !== "string" || value.name.trim().length === 0 || value.name.length > 500) {
    throw new ShareError(400, "Invalid version 1 meal payload.");
  }
  for (const key of ["calories", "protein", "carbs", "fat"] as const) {
    if (typeof value[key] !== "number" || !Number.isFinite(value[key]) || value[key] < 0) {
      throw new ShareError(400, "Invalid version 1 meal payload.");
    }
  }
  const calories = Math.round(value.calories);
  if (!Number.isSafeInteger(calories)) throw new ShareError(400, "Invalid version 1 meal payload.");
  return { ...value, name: value.name, calories, protein: value.protein, carbs: value.carbs, fat: value.fat };
}
function base64url(bytes: Uint8Array): string {
  return btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join("")).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]!);
}
function page(title: string, body: string, status: number, head: boolean): Response {
  return new Response(head ? null : `<!doctype html><html lang="en"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1"><meta name="referrer" content="no-referrer">
    <title>${escapeHtml(title)} · Fud AI</title><meta property="og:title" content="${escapeHtml(title)}">
    <meta property="og:description" content="Open in Fud AI to add these shared meals to your diary."><meta property="og:type" content="website">
    <style>body{font-family:system-ui,sans-serif;background:#0c0b09;color:#f2ede3;margin:0;padding:24px}main{max-width:480px;margin:40px auto;padding:24px;background:#15130e;border-radius:24px;overflow-wrap:anywhere}a{color:#ff6b7a}li{margin-bottom:16px}.button{display:inline-block;padding:14px;border-radius:12px;background:#ff6b7a;color:#0c0b09;font-weight:bold}</style>
    </head><body><main><h1>Fud AI</h1>${body}<p><a href="/privacy.html">Privacy</a></p></main></body></html>`, {
    status, headers: { ...headers, "Content-Type": "text/html; charset=utf-8" },
  });
}
