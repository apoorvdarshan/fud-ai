# Weekly Challenge API

The opt-in Weekly Challenge is the only Fud AI-operated sync service. It stores a
pseudonymous challenge profile, weekly aggregate scores, and moderation reports;
it never receives food/workout logs, photos, body measurements, date of birth, API
keys, or camera frames.

Base path: `/api/challenge/v1`. All request and response bodies are JSON. Except for
joining, every route requires `Authorization: Bearer <token>`. The join token is
returned once, stored only as a SHA-256 hash by the service, and must be kept in the
device's secure storage. Error bodies use
`{"error":{"code":"...","message":"...","fields":["..."]}}`.

## Routes

### `POST /profile`

Join with:

```json
{
  "displayName": "Apoorv D",
  "socialPlatform": "x",
  "socialHandle": "apoorvdarshan",
  "acceptedRules": true,
  "eligibilityAccepted": true
}
```

The social fields are optional but must be supplied together. `socialPlatform` is
`x` or `instagram`. The two acceptance flags must both be exactly `true`; the
service stores acceptance timestamps and the rules/eligibility version, never a
birth date. Success is `201`:

```json
{
  "participantId": "uuid",
  "bearerToken": "one-time-token",
  "profile": {
    "participantId": "uuid",
    "displayName": "Apoorv D",
    "socialPlatform": "x",
    "socialHandle": "apoorvdarshan",
    "createdAt": "ISO-8601",
    "updatedAt": "ISO-8601"
  }
}
```

### `PATCH /profile`

Authenticated. Accepts `displayName`, or both social fields. Send both social
fields as `null` to remove the link. Acceptance flags are create-only. Returns
`{"profile":{...}}` using the profile shape above.

### `DELETE /profile`

Requires a well-formed bearer and returns `{"deleted":true}`. The participant,
token hash, scores, and reports involving that participant are deleted through
foreign-key cascades. The operation is idempotent: retrying the same deletion
after its row is already gone returns the same success response.

### `PUT /weekly-score`

Authenticated. Upserts aggregates only:

```json
{
  "weekStart": "2026-08-31",
  "overallPoints": 18,
  "activityDays": 4,
  "nutritionDays": 5,
  "consistencyDays": 4,
  "hydrationDays": 5,
  "activityKcal": 3200
}
```

`weekStart` must be a Monday in `YYYY-MM-DD` form and within one week of the
current UTC Monday (to tolerate local-week boundaries). Day metrics are integers
from 0 through 7, `activityKcal` is 0 through 14,000, and `overallPoints` is 0
through 28 and must equal the four day metrics' sum. Returns
`{"score":{<request fields>,"updatedAt":"ISO-8601"}}`.

### `GET /leaderboard`

Authenticated. Query parameters are `category`, `weekStart`, and optional `limit`
(default 50, maximum 100). Categories are `overall`, `activity`, `nutrition`,
`consistency`, or `hydration`; the same week window applies. Overall ranks by
`overallPoints`; activity ranks by `activityDays` then `activityKcal`; the other
categories rank by their corresponding day metric. Ties use SQL `RANK()`.

```json
{
  "weekStart": "2026-08-31",
  "category": "activity",
  "updatedAt": "ISO-8601",
  "rankings": [
    {
      "rank": 1,
      "participantId": "uuid",
      "displayName": "Apoorv D",
      "socialPlatform": "x",
      "socialHandle": "apoorvdarshan",
      "score": 4,
      "overallPoints": 18,
      "activityDays": 4,
      "nutritionDays": 5,
      "consistencyDays": 4,
      "hydrationDays": 5,
      "activityKcal": 3200,
      "updatedAt": "ISO-8601",
      "isViewer": true
    }
  ],
  "viewer": { "same": "ranked-row shape" }
}
```

`viewer` is always the authenticated participant's full ranked row, including
when it falls outside `limit`. It can also appear in `rankings`; clients should
deduplicate by stable `participantId` when combining the two.

### `POST /reports`

Authenticated. Body:

```json
{
  "reportedParticipantId": "uuid",
  "reason": "spam",
  "details": "Optional details, at most 300 characters"
}
```

Reasons are `impersonation`, `inappropriate_name`, `spam`, `unsafe_content`, or
`other`. Self-reports and a second report of the same participant are rejected.
Success is `201` with
`{"report":{"reportId":"uuid","reportedParticipantId":"uuid","reason":"spam","status":"open","createdAt":"ISO-8601"}}`.

## Validation, abuse controls, and retention

- Display names normalize Unicode/whitespace, contain 2–40 code points, allow
  letters, marks, numbers, spaces and basic punctuation, and reject controls,
  links, email addresses, reserved impersonation terms, and a focused unsafe-term
  list. X handles are 1–15 letters/numbers/underscores; Instagram handles are
  1–30 letters/numbers/periods/underscores with no consecutive or trailing period.
- Unknown JSON/query fields are rejected, SQL values are bound parameters, bodies
  are capped at 4 KiB, browser CORS is limited to Fud AI's two website origins,
  and authenticated responses are `no-store`.
- Cloudflare rate-limit bindings cap API traffic per transient hashed network
  address (and bearer-token hash where available) and more tightly cap joins. No
  raw IP address or bearer token is written to D1 or application logs. Edge WAF
  rules remain advisable for distributed invalid-token attacks.
- The hourly scheduled handler deletes inactive profiles after 90 days, keeps the
  current plus previous 12 challenge weeks (13 total), and deletes reports after
  180 days. Profile deletion cascades to scores and reports sooner.

## Deployment

The D1 binding points to `fud-ai-challenge` in `wrangler.toml`. Before the first
Worker deployment, apply the checked-in schema without printing or logging secrets:

```sh
npx wrangler d1 migrations apply fud-ai-challenge --remote
npm run check
npx wrangler deploy --dry-run
```

Run `npm run types` whenever bindings change. Local schema validation uses the
same migration with `--local`.
