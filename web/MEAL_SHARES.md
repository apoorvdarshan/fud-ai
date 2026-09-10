# Short meal-share links

`POST /api/meal-shares` accepts the existing UTF-8 JSON `{ "v": 1, "meals": [...] }`
payload with `Content-Type: application/json`. It returns HTTP 201 with
`{ "url": "https://www.fud-ai.app/m/<id>", "expiresAt": "<ISO timestamp>" }`.
The body is limited to 64 KiB and 1–100 meals. Each meal needs a nonempty name
(up to 500 characters), nonnegative calories (fractional values are rounded to the
nearest integer), and finite nonnegative protein/carbs/fat. Optional meal fields are
preserved for existing mobile decoders.
The apps send the same fields as their old links: no photos, account data, or API keys.

IDs use 128 random bits encoded as 22 base64url characters. `MEAL_SHARES` stores
payloads with a 604800-second TTL; the handler also checks an explicit expiration.
`GET /m/:id` renders escaped, server-side preview metadata and meal summaries,
plus `fudai://add-meal?d=...` and `/add-meal?d=...` buttons using the full payload.
`HEAD` returns the same status and headers without a body. Missing/expired links
return 404 with instructions to ask the sender to share again. There is no automatic
redirect, so a recipient can review the meals before opening the app. Existing apps
can import via the buttons without understanding short IDs. `/m/*` intentionally
stays outside native App/Universal Link associations so it reaches the preview.

Creation is limited to 10 requests/minute per Cloudflare connecting IP; preview
reads to 120/minute using separate native Worker rate-limit bindings. These limits
are local to Cloudflare locations, not a strict global quota. 429 includes
`Retry-After: 60`; invalid input uses 400/413/415, disallowed origins 403, unsupported
methods 405, and service failures 503. Native apps need no CORS preflight; creation
rejects browser origins other than the two Fud AI hosts. Responses disable caching,
indexing, and referrers. The handler does not log payloads, IDs, or IP addresses.
Recipients/messaging services may retain copies beyond expiry. KV is eventually
consistent: a newly created link can briefly be unavailable at another location.

## Local checks and production setup

Use Node 22 or later with the existing locked dependencies:

```sh
npm ci
npm run check
npm run types
npx wrangler deploy --dry-run
```

`wrangler.toml` routes `/api/meal-shares` and `/m/*` through the Worker before
static assets. The `MEAL_SHARES` namespace is provisioned and its production ID is recorded in
`wrangler.toml`. Reuse that binding for updates. For a separate deployment, create
a namespace with `npx wrangler kv namespace create MEAL_SHARES` and configure
that deployment with the returned ID.
See [Wrangler configuration](https://developers.cloudflare.com/workers/wrangler/configuration/).
No account resources are created by the tests or dry-run.

Both mobile share sheets attempt creation with a five-second timeout, send
`Content-Type: application/json` and a `FudAI/1.0 (...; MealShare)` User-Agent,
and retain the long link on offline, rate-limit (HTTP 429), service, or
invalid-response failures. They do not immediately retry 429s: the Worker returns
`Retry-After: 60`, so a second request in the same window would still fail and
only delay the share sheet. Deploy
the Worker before releasing the mobile changes. Verify creation, messenger previews,
expiry, and iOS/Android import on a simulator/emulator or test device separately.
