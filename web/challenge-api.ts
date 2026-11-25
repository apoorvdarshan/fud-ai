import {
  ChallengeValidationError,
  parseCreateProfileInput,
  parseLeaderboardQuery,
  parsePatchProfileInput,
  parseReportInput,
  parsePeriodScore,
  parseWeeklyScoreInput,
  type ChallengeCategory,
  type ChallengePeriod,
  type SocialPlatform,
  type WeeklyScoreInput,
} from "./challenge-validation";

export const CHALLENGE_API_PREFIX = "/api/challenge/v1";

const ALLOWED_WEB_ORIGINS = new Set([
  "https://fud-ai.app",
  "https://www.fud-ai.app",
]);
const MAX_JSON_BODY_BYTES = 4_096;
const BEARER_TOKEN = /^[A-Za-z0-9_-]{43}$/;
const ACCEPTANCE_VERSION = "2026-08-31";
const PERIOD_RULES_VERSION = "2026-11-25";

type ChallengeDatabase = D1DatabaseSession;

interface ParticipantRecord {
  participant_id: string;
  display_name: string;
  social_platform: SocialPlatform | null;
  social_handle: string | null;
  created_at: string;
  updated_at: string;
}

interface RankingDatabaseRow {
  participant_count: number;
  participant_id: string;
  display_name: string;
  social_platform: SocialPlatform | null;
  social_handle: string | null;
  overall_points: number;
  activity_days: number;
  nutrition_days: number;
  consistency_days: number;
  hydration_days: number;
  activity_kcal: number;
  updated_at: string;
  score: number;
  rank: number;
  is_viewer: number;
  balanced_days?: number;
  longest_streak?: number;
}

interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    fields?: string[];
  };
}

class ChallengeApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields: string[] | undefined;
  readonly responseHeaders: HeadersInit | undefined;

  constructor(
    status: number,
    code: string,
    message: string,
    fields?: string[],
    responseHeaders?: HeadersInit,
  ) {
    super(message);
    this.name = "ChallengeApiError";
    this.status = status;
    this.code = code;
    this.fields = fields;
    this.responseHeaders = responseHeaders;
  }
}

export async function handleChallengeRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const origin = request.headers.get("Origin");
  const responseOrigin = origin !== null && ALLOWED_WEB_ORIGINS.has(origin) ? origin : null;

  try {
    assertAllowedOrigin(origin);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: challengeHeaders(responseOrigin, true),
      });
    }

    await enforceApiRateLimits(
      request,
      env,
      url.pathname === `${CHALLENGE_API_PREFIX}/profile` && request.method === "POST",
    );

    const database = env.CHALLENGE_DB.withSession("first-primary");
    if (url.pathname === `${CHALLENGE_API_PREFIX}/profile`) {
      if (request.method === "POST") {
        return await createProfile(request, database, responseOrigin);
      }
      if (request.method === "PATCH") {
        return await updateProfile(request, database, responseOrigin);
      }
      if (request.method === "DELETE") {
        return await deleteProfile(request, database, responseOrigin);
      }
      throw methodNotAllowed("POST, PATCH, DELETE");
    }

    if (url.pathname === `${CHALLENGE_API_PREFIX}/weekly-score`) {
      if (request.method !== "PUT") throw methodNotAllowed("PUT");
      return await putWeeklyScore(request, database, responseOrigin);
    }
    if (url.pathname === `${CHALLENGE_API_PREFIX}/period-score`) {
      if (request.method === "PUT") return await putPeriodScore(request, database, responseOrigin);
      if (request.method === "DELETE") return await deletePeriodScores(request, url, database, responseOrigin);
      throw methodNotAllowed("PUT, DELETE");
    }

    if (url.pathname === `${CHALLENGE_API_PREFIX}/leaderboard`) {
      if (request.method !== "GET") throw methodNotAllowed("GET");
      return await getLeaderboard(request, url, database, responseOrigin);
    }

    if (url.pathname === `${CHALLENGE_API_PREFIX}/reports`) {
      if (request.method !== "POST") throw methodNotAllowed("POST");
      return await createReport(request, database, responseOrigin);
    }

    throw new ChallengeApiError(404, "not_found", "Challenge API route not found.");
  } catch (error) {
    if (error instanceof ChallengeValidationError) {
      return jsonResponse<ApiErrorBody>(
        {
          error: {
            code: "validation_error",
            message: error.message,
            fields: error.fields,
          },
        },
        400,
        responseOrigin,
      );
    }
    if (error instanceof ChallengeApiError) {
      const body: ApiErrorBody = {
        error: { code: error.code, message: error.message },
      };
      if (error.fields) body.error.fields = error.fields;
      return jsonResponse(body, error.status, responseOrigin, error.responseHeaders);
    }

    // Do not log request bodies, Authorization headers, raw tokens, or database values.
    console.error(
      JSON.stringify({
        event: "challenge_api_error",
        method: request.method,
        path: url.pathname,
        errorType: error instanceof Error ? error.name : typeof error,
      }),
    );
    return jsonResponse<ApiErrorBody>(
      { error: { code: "internal_error", message: "The challenge service is unavailable." } },
      500,
      responseOrigin,
    );
  }
}

export async function cleanupChallengeData(database: D1Database, now = new Date()): Promise<void> {
  const inactiveCutoff = new Date(now.getTime() - 90 * 86_400_000).toISOString();
  const reportCutoff = new Date(now.getTime() - 180 * 86_400_000).toISOString();
  const currentMonday = startOfUtcWeek(now);
  const oldestRetainedWeek = new Date(currentMonday.getTime() - 12 * 7 * 86_400_000)
    .toISOString()
    .slice(0, 10);
  const oldestDay = new Date(now.getTime() - 34 * 86_400_000).toISOString().slice(0, 10);
  const oldestMonth = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 12, 1)).toISOString().slice(0, 10);

  await database.batch([
    database
      .prepare("DELETE FROM challenge_participants WHERE last_active_at < ?")
      .bind(inactiveCutoff),
    database
      .prepare("DELETE FROM challenge_weekly_scores WHERE week_start < ?")
      .bind(oldestRetainedWeek),
    database.prepare("DELETE FROM challenge_reports WHERE created_at < ?").bind(reportCutoff),
    database.prepare("DELETE FROM challenge_period_scores WHERE period = ? AND period_start < ?")
      .bind("daily", oldestDay),
    database.prepare("DELETE FROM challenge_period_scores WHERE period = ? AND period_start < ?")
      .bind("monthly", oldestMonth),
  ]);
}

async function createProfile(
  request: Request,
  database: ChallengeDatabase,
  origin: string | null,
): Promise<Response> {
  const input = parseCreateProfileInput(await readJsonBody(request));
  const participantId = crypto.randomUUID();
  const bearerToken = createBearerToken();
  const tokenHash = await sha256Hex(bearerToken);
  const now = new Date().toISOString();

  await database
    .prepare(
      `INSERT INTO challenge_participants (
        participant_id, token_hash, display_name, social_platform, social_handle,
        rules_accepted_at, rules_version, eligibility_accepted_at, eligibility_version,
        created_at, updated_at, last_active_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      participantId,
      tokenHash,
      input.displayName,
      input.social?.socialPlatform ?? null,
      input.social?.socialHandle ?? null,
      now,
      ACCEPTANCE_VERSION,
      now,
      ACCEPTANCE_VERSION,
      now,
      now,
      now,
    )
    .run();

  return jsonResponse(
    {
      participantId,
      bearerToken,
      profile: {
        participantId,
        displayName: input.displayName,
        socialPlatform: input.social?.socialPlatform ?? null,
        socialHandle: input.social?.socialHandle ?? null,
        createdAt: now,
        updatedAt: now,
      },
    },
    201,
    origin,
  );
}

async function updateProfile(
  request: Request,
  database: ChallengeDatabase,
  origin: string | null,
): Promise<Response> {
  const participant = await authenticate(request, database);
  const input = parsePatchProfileInput(await readJsonBody(request));
  const now = new Date().toISOString();
  const displayName = input.displayName ?? participant.display_name;
  const socialPlatform =
    input.social === undefined ? participant.social_platform : input.social?.socialPlatform ?? null;
  const socialHandle =
    input.social === undefined ? participant.social_handle : input.social?.socialHandle ?? null;

  await database
    .prepare(
      `UPDATE challenge_participants
       SET display_name = ?, social_platform = ?, social_handle = ?,
           updated_at = ?, last_active_at = ?
       WHERE participant_id = ?`,
    )
    .bind(displayName, socialPlatform, socialHandle, now, now, participant.participant_id)
    .run();

  return jsonResponse(
    {
      profile: {
        participantId: participant.participant_id,
        displayName,
        socialPlatform,
        socialHandle,
        createdAt: participant.created_at,
        updatedAt: now,
      },
    },
    200,
    origin,
  );
}

async function deleteProfile(
  request: Request,
  database: ChallengeDatabase,
  origin: string | null,
): Promise<Response> {
  // A retry after a lost success response must not strand the device in a
  // pending-deletion state. A well-formed bearer therefore receives the same
  // success response when its row is already gone.
  const tokenHash = await sha256Hex(readBearerToken(request));
  await database
    .prepare("DELETE FROM challenge_participants WHERE token_hash = ?")
    .bind(tokenHash)
    .run();
  return jsonResponse({ deleted: true }, 200, origin);
}

async function putWeeklyScore(
  request: Request,
  database: ChallengeDatabase,
  origin: string | null,
): Promise<Response> {
  const participant = await authenticate(request, database);
  const input = parseWeeklyScoreInput(await readJsonBody(request));
  const now = new Date().toISOString();

  await database.batch([
    database
      .prepare(
        `INSERT INTO challenge_weekly_scores (
          participant_id, week_start, overall_points, activity_days, nutrition_days,
          consistency_days, hydration_days, activity_kcal, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(participant_id, week_start) DO UPDATE SET
          overall_points = excluded.overall_points,
          activity_days = excluded.activity_days,
          nutrition_days = excluded.nutrition_days,
          consistency_days = excluded.consistency_days,
          hydration_days = excluded.hydration_days,
          activity_kcal = excluded.activity_kcal,
          updated_at = ${REACHED_AT_ASSIGNMENT}`,
      )
      .bind(
        participant.participant_id,
        input.weekStart,
        input.overallPoints,
        input.activityDays,
        input.nutritionDays,
        input.consistencyDays,
        input.hydrationDays,
        input.activityKcal,
        now,
      ),
    database
      .prepare("UPDATE challenge_participants SET last_active_at = ? WHERE participant_id = ?")
      .bind(now, participant.participant_id),
  ]);

  return jsonResponse({ score: scoreResponse(input, now) }, 200, origin);
}

async function putPeriodScore(
  request: Request, database: ChallengeDatabase, origin: string | null,
): Promise<Response> {
  const participant = await authenticate(request, database);
  const input = parsePeriodScore(await readJsonBody(request));
  const now = new Date().toISOString();
  await database.batch([
    database.prepare(`
      INSERT INTO challenge_period_scores (
        participant_id, period, period_start, overall_points, activity_days, nutrition_days,
        consistency_days, hydration_days, balanced_days, longest_streak, rules_version, rules_accepted_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(participant_id, period, period_start) DO UPDATE SET
        overall_points = excluded.overall_points, activity_days = excluded.activity_days,
        nutrition_days = excluded.nutrition_days, consistency_days = excluded.consistency_days,
        hydration_days = excluded.hydration_days, balanced_days = excluded.balanced_days,
        longest_streak = excluded.longest_streak, updated_at = excluded.updated_at
      WHERE challenge_period_scores.activity_days != excluded.activity_days
         OR challenge_period_scores.nutrition_days != excluded.nutrition_days
         OR challenge_period_scores.consistency_days != excluded.consistency_days
         OR challenge_period_scores.hydration_days != excluded.hydration_days
         OR challenge_period_scores.balanced_days != excluded.balanced_days
         OR challenge_period_scores.longest_streak != excluded.longest_streak
    `).bind(participant.participant_id, input.period, input.periodStart, input.overallPoints, input.activityDays,
      input.nutritionDays, input.consistencyDays, input.hydrationDays, input.balancedDays, input.longestStreak,
      PERIOD_RULES_VERSION, now, now),
    database.prepare("UPDATE challenge_participants SET last_active_at = ? WHERE participant_id = ?")
      .bind(now, participant.participant_id),
  ]);
  return jsonResponse({ ok: true, score: input }, 200, origin);
}

async function deletePeriodScores(
  request: Request, url: URL, database: ChallengeDatabase, origin: string | null,
): Promise<Response> {
  const participant = await authenticate(request, database);
  const entries = [...url.searchParams.entries()];
  const period = url.searchParams.get("period");
  if (entries.length !== 1 || (period !== "daily" && period !== "monthly")) {
    throw new ChallengeValidationError("Choose one period to leave.", ["period"]);
  }
  await database.prepare("DELETE FROM challenge_period_scores WHERE participant_id = ? AND period = ?")
    .bind(participant.participant_id, period).run();
  return jsonResponse({ deleted: true }, 200, origin);
}

async function getLeaderboard(
  request: Request,
  url: URL,
  database: ChallengeDatabase,
  origin: string | null,
): Promise<Response> {
  const participant = await authenticate(request, database);
  const query = parseLeaderboardQuery(url);
  const ranking = rankingSpecification(query.category);
  const weekly = query.period === "weekly";
  const range = weekly ? [query.weekStart] : [query.period, query.weekStart];
  const commonCte = `WITH ranked AS (
    SELECT
      p.participant_id,
      p.display_name,
      p.social_platform,
      p.social_handle,
      COALESCE(s.overall_points, 0) AS overall_points,
      COALESCE(s.activity_days, 0) AS activity_days,
      COALESCE(s.nutrition_days, 0) AS nutrition_days,
      COALESCE(s.consistency_days, 0) AS consistency_days,
      COALESCE(s.hydration_days, 0) AS hydration_days,
      ${weekly ? ACTIVITY_KCAL : "0"} AS activity_kcal,
      ${weekly ? "NULL" : "s.balanced_days"} AS balanced_days,
      ${weekly ? "NULL" : "s.longest_streak"} AS longest_streak,
      COALESCE(s.updated_at, p.updated_at) AS updated_at,
      ${ranking.scoreExpression} AS score,
      ${leaderboardRankingSelect(query.category, query.period)},
      COUNT(*) OVER () AS participant_count
    FROM challenge_participants p
    ${weekly
      ? "LEFT JOIN challenge_weekly_scores s ON s.participant_id = p.participant_id AND s.week_start = ?"
      : "INNER JOIN challenge_period_scores s ON s.participant_id = p.participant_id AND s.period = ? AND s.period_start = ?"}
  )`;
  const rankingsStatement = database
    .prepare(
      `${commonCte}
       SELECT *, participant_id = ? AS is_viewer
       FROM ranked
       WHERE rank BETWEEN ? AND ? OR participant_id = ?
       ORDER BY rank ASC`,
    )
    .bind(...range, participant.participant_id, query.offset + 1,
      query.offset + query.limit, participant.participant_id);
  const now = new Date().toISOString();
  const [rankingsResult, , countResult] = await database.batch([
    rankingsStatement,
    database
      .prepare("UPDATE challenge_participants SET last_active_at = ? WHERE participant_id = ?")
      .bind(now, participant.participant_id),
    ...(!weekly ? [
      database.prepare("SELECT COUNT(*) AS total FROM challenge_period_scores WHERE period = ? AND period_start = ?")
        .bind(...range),
    ] : []),
  ]);

  const rows = rankingsResult.results as unknown as RankingDatabaseRow[];
  const rankings = rows.filter((row) => row.rank > query.offset && row.rank <= query.offset + query.limit)
    .map(rankingResponse);
  const viewer = rows.find((row) => row.participant_id === participant.participant_id);
  if (weekly && !viewer) {
    throw new ChallengeApiError(500, "internal_error", "The challenge service is unavailable.");
  }

  return jsonResponse(
    {
      weekStart: query.weekStart,
      category: query.category,
      updatedAt: now,
      offset: query.offset,
      totalCount: rows[0]?.participant_count ?? Number((countResult?.results[0] as {total?: number} | undefined)?.total ?? 0),
      period: query.period,
      rankings,
      viewer: viewer ? rankingResponse(viewer) : null,
    },
    200,
    origin,
  );
}

async function createReport(
  request: Request,
  database: ChallengeDatabase,
  origin: string | null,
): Promise<Response> {
  const participant = await authenticate(request, database);
  const input = parseReportInput(await readJsonBody(request));
  if (input.reportedParticipantId === participant.participant_id) {
    throw new ChallengeApiError(400, "self_report_not_allowed", "You cannot report yourself.", [
      "reportedParticipantId",
    ]);
  }

  const target = await database
    .prepare("SELECT participant_id FROM challenge_participants WHERE participant_id = ?")
    .bind(input.reportedParticipantId)
    .first<{ participant_id: string }>();
  if (!target) {
    throw new ChallengeApiError(404, "participant_not_found", "Participant not found.", [
      "reportedParticipantId",
    ]);
  }

  const reportId = crypto.randomUUID();
  const now = new Date().toISOString();
  const [insertResult] = await database.batch([
    database
      .prepare(
        `INSERT INTO challenge_reports (
          report_id, reporter_participant_id, reported_participant_id,
          reason, details, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, 'open', ?, ?)
        ON CONFLICT(reporter_participant_id, reported_participant_id) DO NOTHING`,
      )
      .bind(
        reportId,
        participant.participant_id,
        input.reportedParticipantId,
        input.reason,
        input.details,
        now,
        now,
      ),
    database
      .prepare("UPDATE challenge_participants SET last_active_at = ? WHERE participant_id = ?")
      .bind(now, participant.participant_id),
  ]);
  if (insertResult.meta.changes === 0) {
    throw new ChallengeApiError(
      409,
      "report_already_exists",
      "You have already reported this participant.",
      ["reportedParticipantId"],
    );
  }

  return jsonResponse(
    {
      report: {
        reportId,
        reportedParticipantId: input.reportedParticipantId,
        reason: input.reason,
        status: "open",
        createdAt: now,
      },
    },
    201,
    origin,
  );
}

async function authenticate(
  request: Request,
  database: ChallengeDatabase,
): Promise<ParticipantRecord> {
  const token = readBearerToken(request);
  const tokenHash = await sha256Hex(token);
  const participant = await database
    .prepare(
      `SELECT participant_id, display_name, social_platform, social_handle, created_at, updated_at
       FROM challenge_participants
       WHERE token_hash = ?`,
    )
    .bind(tokenHash)
    .first<ParticipantRecord>();
  if (!participant) {
    throw new ChallengeApiError(401, "unauthorized", "A valid challenge bearer token is required.");
  }
  return participant;
}

function readBearerToken(request: Request): string {
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) {
    throw new ChallengeApiError(401, "unauthorized", "A valid challenge bearer token is required.");
  }
  const token = authorization.slice("Bearer ".length);
  if (!BEARER_TOKEN.test(token)) {
    throw new ChallengeApiError(401, "unauthorized", "A valid challenge bearer token is required.");
  }
  return token;
}

async function readJsonBody(request: Request): Promise<unknown> {
  const contentType = request.headers.get("Content-Type")?.split(";", 1)[0]?.trim().toLowerCase();
  if (contentType !== "application/json") {
    throw new ChallengeApiError(
      415,
      "invalid_content_type",
      "Content-Type must be application/json.",
    );
  }
  const declaredLength = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_JSON_BODY_BYTES) {
    throw new ChallengeApiError(413, "payload_too_large", "Request body is too large.");
  }
  if (!request.body) {
    throw new ChallengeApiError(400, "invalid_json", "Request body must contain valid JSON.");
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalLength = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    totalLength += value.byteLength;
    if (totalLength > MAX_JSON_BODY_BYTES) {
      await reader.cancel();
      throw new ChallengeApiError(413, "payload_too_large", "Request body is too large.");
    }
    chunks.push(value);
  }

  const bytes = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
    return JSON.parse(text) as unknown;
  } catch {
    throw new ChallengeApiError(400, "invalid_json", "Request body must contain valid JSON.");
  }
}

function scoreResponse(input: WeeklyScoreInput, updatedAt: string): Record<string, unknown> {
  return {
    weekStart: input.weekStart,
    overallPoints: input.overallPoints,
    activityDays: input.activityDays,
    nutritionDays: input.nutritionDays,
    consistencyDays: input.consistencyDays,
    hydrationDays: input.hydrationDays,
    activityKcal: input.activityKcal,
    updatedAt,
  };
}

function rankingResponse(row: RankingDatabaseRow): Record<string, unknown> {
  return {
    rank: row.rank,
    participantId: row.participant_id,
    displayName: row.display_name,
    socialPlatform: row.social_platform,
    socialHandle: row.social_handle,
    score: row.score,
    overallPoints: row.overall_points,
    activityDays: row.activity_days,
    nutritionDays: row.nutrition_days,
    consistencyDays: row.consistency_days,
    hydrationDays: row.hydration_days,
    activityKcal: row.activity_kcal,
    updatedAt: row.updated_at,
    isViewer: Boolean(row.is_viewer),
    ...(row.balanced_days != null && { balancedDays: row.balanced_days, longestStreak: row.longest_streak }),
  };
}

const OVERALL_POINTS = "COALESCE(s.overall_points, 0)";
const ACTIVITY_DAYS = "COALESCE(s.activity_days, 0)";
const NUTRITION_DAYS = "COALESCE(s.nutrition_days, 0)";
const CONSISTENCY_DAYS = "COALESCE(s.consistency_days, 0)";
const HYDRATION_DAYS = "COALESCE(s.hydration_days, 0)";
const ACTIVITY_KCAL = "COALESCE(s.activity_kcal, 0)";
const REACHED_SCORE_FIRST = "COALESCE(s.updated_at, p.updated_at) ASC";
const STABLE_PARTICIPANT = "p.participant_id ASC";

/** Keep the time the ranked habit counts were first reached, even if calories change. */
export const REACHED_AT_ASSIGNMENT = `CASE
  WHEN challenge_weekly_scores.overall_points = excluded.overall_points
   AND challenge_weekly_scores.activity_days = excluded.activity_days
   AND challenge_weekly_scores.nutrition_days = excluded.nutrition_days
   AND challenge_weekly_scores.consistency_days = excluded.consistency_days
   AND challenge_weekly_scores.hydration_days = excluded.hydration_days
  THEN challenge_weekly_scores.updated_at
  ELSE excluded.updated_at
END`;

/** Category score, balanced habits, then who got there first. */
export function leaderboardOrder(category: ChallengeCategory, period: ChallengePeriod = "weekly"): string {
  const days = {
    activity: `${ACTIVITY_DAYS} DESC`,
    nutrition: `${NUTRITION_DAYS} DESC`,
    consistency: `${CONSISTENCY_DAYS} DESC`,
    hydration: `${HYDRATION_DAYS} DESC`,
  };
  if (period !== "weekly") {
    const rest = (Object.keys(days) as Array<keyof typeof days>).filter((name) => name !== category);
    return [
      category === "overall" ? `${OVERALL_POINTS} DESC` : days[category],
      "s.balanced_days DESC", "s.longest_streak DESC",
      ...rest.map((name) => days[name]), REACHED_SCORE_FIRST, STABLE_PARTICIPANT,
    ].join(", ");
  }
  const metrics = [ACTIVITY_DAYS, NUTRITION_DAYS, CONSISTENCY_DAYS, HYDRATION_DAYS];
  const coverage = metrics.map((metric) => `(${metric} > 0)`).join(" + ");
  const primary = [
    category === "overall" ? `${OVERALL_POINTS} DESC` : days[category],
    `(${coverage}) DESC`,
    `MIN(${metrics.join(", ")}) DESC`,
    ...(category === "overall" ? [] : [`${OVERALL_POINTS} DESC`]),
    ...(Object.keys(days) as Array<keyof typeof days>).filter((name) => name !== category).map((name) => days[name]),
  ];
  return [...primary, REACHED_SCORE_FIRST, STABLE_PARTICIPANT].join(", ");
}

export function leaderboardRankingSelect(category: ChallengeCategory, period: ChallengePeriod = "weekly"): string {
  return `ROW_NUMBER() OVER (ORDER BY ${leaderboardOrder(category, period)}) AS rank`;
}

function rankingSpecification(category: ChallengeCategory): {
  scoreExpression: string;
  orderExpression: string;
} {
  const scoreExpression = category === "overall"
    ? OVERALL_POINTS
    : category === "activity"
      ? ACTIVITY_DAYS
      : category === "nutrition"
        ? NUTRITION_DAYS
        : category === "consistency"
          ? CONSISTENCY_DAYS
          : HYDRATION_DAYS;
  return { scoreExpression, orderExpression: leaderboardOrder(category) };
}

function createBearerToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function enforceApiRateLimits(
  request: Request,
  env: Env,
  isProfileCreation: boolean,
): Promise<void> {
  const requestAddress = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const addressKey = `address:${await sha256Hex(requestAddress)}`;
  const checks = [env.CHALLENGE_API_RATE_LIMITER.limit({ key: addressKey })];

  const authorization = request.headers.get("Authorization");
  if (authorization?.startsWith("Bearer ")) {
    const token = authorization.slice("Bearer ".length);
    if (BEARER_TOKEN.test(token)) {
      checks.push(
        env.CHALLENGE_API_RATE_LIMITER.limit({ key: `token:${await sha256Hex(token)}` }),
      );
    }
  }
  if (isProfileCreation) {
    checks.push(env.CHALLENGE_CREATE_RATE_LIMITER.limit({ key: addressKey }));
  }

  const results = await Promise.all(checks);
  if (results.some((result) => !result.success)) {
    throw new ChallengeApiError(
      429,
      "rate_limited",
      "Too many challenge requests. Please try again shortly.",
      undefined,
      { "Retry-After": "60" },
    );
  }
}

function startOfUtcWeek(date: Date): Date {
  const result = new Date(date);
  result.setUTCHours(0, 0, 0, 0);
  const daysSinceMonday = (result.getUTCDay() + 6) % 7;
  result.setUTCDate(result.getUTCDate() - daysSinceMonday);
  return result;
}

function assertAllowedOrigin(origin: string | null): void {
  if (origin !== null && !ALLOWED_WEB_ORIGINS.has(origin)) {
    throw new ChallengeApiError(403, "origin_not_allowed", "This web origin is not allowed.");
  }
}

function methodNotAllowed(allow: string): ChallengeApiError {
  return new ChallengeApiError(
    405,
    "method_not_allowed",
    "Method not allowed.",
    undefined,
    { Allow: allow },
  );
}

function jsonResponse<T>(
  body: T,
  status: number,
  origin: string | null,
  extraHeaders?: HeadersInit,
): Response {
  const headers = challengeHeaders(origin, false);
  if (extraHeaders) {
    for (const [name, value] of new Headers(extraHeaders)) headers.set(name, value);
  }
  return Response.json(body, { status, headers });
}

function challengeHeaders(origin: string | null, preflight: boolean): Headers {
  const headers = new Headers({
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'",
    "Cross-Origin-Resource-Policy": "same-site",
    "Referrer-Policy": "no-referrer",
    Vary: "Origin",
    "X-Content-Type-Options": "nosniff",
    "X-Robots-Tag": "noindex, nofollow",
  });
  if (origin) headers.set("Access-Control-Allow-Origin", origin);
  if (preflight) {
    headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
    headers.set("Access-Control-Max-Age", "86400");
  }
  return headers;
}
