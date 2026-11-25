export const CHALLENGE_CATEGORIES = [
  "overall",
  "activity",
  "nutrition",
  "consistency",
  "hydration",
] as const;

export const REPORT_REASONS = [
  "impersonation",
  "inappropriate_name",
  "spam",
  "unsafe_content",
  "other",
] as const;

export type ChallengeCategory = (typeof CHALLENGE_CATEGORIES)[number];
export type ChallengePeriod = "daily" | "weekly" | "monthly";
export type ReportReason = (typeof REPORT_REASONS)[number];
export type SocialPlatform = "x" | "instagram";

export interface SocialProfile {
  socialPlatform: SocialPlatform;
  socialHandle: string;
}

export interface CreateProfileInput {
  displayName: string;
  social: SocialProfile | null;
}

export interface PatchProfileInput {
  displayName?: string;
  social?: SocialProfile | null;
}

export interface WeeklyScoreInput {
  weekStart: string;
  overallPoints: number;
  activityDays: number;
  nutritionDays: number;
  consistencyDays: number;
  hydrationDays: number;
  activityKcal: number;
}

export interface ReportInput {
  reportedParticipantId: string;
  reason: ReportReason;
  details: string | null;
}

export interface LeaderboardQuery {
    category: ChallengeCategory;
    weekStart: string;
    limit: number;
  offset: number;
  period: ChallengePeriod;
}

export interface PeriodScoreInput {
  period: Exclude<ChallengePeriod, "weekly">;
  periodStart: string;
  activityDays: number;
  nutritionDays: number;
  consistencyDays: number;
  hydrationDays: number;
  balancedDays: number;
  longestStreak: number;
  overallPoints: number;
}

export class ChallengeValidationError extends Error {
  readonly fields: string[];

  constructor(message: string, fields: string[]) {
    super(message);
    this.name = "ChallengeValidationError";
    this.fields = [...new Set(fields)];
  }
}

const DISPLAY_NAME_ALLOWED = /^[\p{L}\p{M}\p{N} ._'’-]+$/u;
const DISPLAY_NAME_HAS_WORD = /[\p{L}\p{N}]/u;
const EMAIL_LIKE = /\b[^\s@]+@[^\s@]+\.[^\s@]+\b/u;
const URL_LIKE = /(?:\b(?:https?:\/\/|www\.)|\b[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+\b)/iu;
const PARTICIPANT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const X_HANDLE = /^[A-Za-z0-9_]{1,15}$/;
const INSTAGRAM_HANDLE = /^(?!.*\.\.)(?!.*\.$)[A-Za-z0-9._]{1,30}$/;
const CONTROL_CHARACTER = /\p{C}/u;

// This deliberately stays focused and testable. It is a first-pass safeguard,
// not a substitute for participant reporting and human moderation.
const RESERVED_NAME_TOKENS = new Set([
  "admin",
  "administrator",
  "moderator",
  "staff",
  "support",
]);
const DISALLOWED_NAME_TOKENS = new Set([
  "bitch",
  "chink",
  "cunt",
  "faggot",
  "fuck",
  "kike",
  "kkk",
  "nazi",
  "nigger",
  "porn",
  "pornhub",
  "shit",
]);
const DISALLOWED_COMPACT_PHRASES = new Set(["heilhitler", "whitepower"]);

export function parseCreateProfileInput(value: unknown): CreateProfileInput {
  const body = requireObject(value);
  assertKnownKeys(body, [
    "displayName",
    "socialPlatform",
    "socialHandle",
    "acceptedRules",
    "eligibilityAccepted",
  ]);

  const fields: string[] = [];
  if (body.acceptedRules !== true) fields.push("acceptedRules");
  if (body.eligibilityAccepted !== true) fields.push("eligibilityAccepted");
  if (fields.length > 0) {
    throw new ChallengeValidationError(
      "Challenge rules and 18+ eligibility must be accepted.",
      fields,
    );
  }

  return {
    displayName: normalizeDisplayName(body.displayName),
    social: parseSocial(body),
  };
}

export function parsePatchProfileInput(value: unknown): PatchProfileInput {
  const body = requireObject(value);
  assertKnownKeys(body, ["displayName", "socialPlatform", "socialHandle"]);

  const hasDisplayName = Object.hasOwn(body, "displayName");
  const hasSocialPlatform = Object.hasOwn(body, "socialPlatform");
  const hasSocialHandle = Object.hasOwn(body, "socialHandle");
  if (!hasDisplayName && !hasSocialPlatform && !hasSocialHandle) {
    throw new ChallengeValidationError("At least one profile field is required.", [
      "displayName",
      "socialPlatform",
      "socialHandle",
    ]);
  }

  const result: PatchProfileInput = {};
  if (hasDisplayName) result.displayName = normalizeDisplayName(body.displayName);
  if (hasSocialPlatform || hasSocialHandle) result.social = parseSocial(body);
  return result;
}

export function parseWeeklyScoreInput(value: unknown, now = new Date()): WeeklyScoreInput {
  const body = requireObject(value);
  const keys = [
    "weekStart",
    "overallPoints",
    "activityDays",
    "nutritionDays",
    "consistencyDays",
    "hydrationDays",
    "activityKcal",
  ];
  assertKnownKeys(body, keys);

  const result: WeeklyScoreInput = {
    weekStart: parseWeekStart(body.weekStart),
    overallPoints: parseInteger(body.overallPoints, "overallPoints", 0, 28),
    activityDays: parseInteger(body.activityDays, "activityDays", 0, 7),
    nutritionDays: parseInteger(body.nutritionDays, "nutritionDays", 0, 7),
    consistencyDays: parseInteger(body.consistencyDays, "consistencyDays", 0, 7),
    hydrationDays: parseInteger(body.hydrationDays, "hydrationDays", 0, 7),
    activityKcal: parseInteger(body.activityKcal, "activityKcal", 0, 14_000),
  };
  assertCurrentWeekWindow(result.weekStart, now);

  const calculatedOverall =
    result.activityDays +
    result.nutritionDays +
    result.consistencyDays +
    result.hydrationDays;
  if (result.overallPoints !== calculatedOverall) {
    throw new ChallengeValidationError(
      "overallPoints must equal the sum of the four day metrics.",
      [
        "overallPoints",
        "activityDays",
        "nutritionDays",
        "consistencyDays",
        "hydrationDays",
      ],
    );
  }

  return result;
}

export function parseReportInput(value: unknown): ReportInput {
  const body = requireObject(value);
  assertKnownKeys(body, ["reportedParticipantId", "reason", "details"]);

  if (
    typeof body.reportedParticipantId !== "string" ||
    !PARTICIPANT_ID.test(body.reportedParticipantId)
  ) {
    throw new ChallengeValidationError("Invalid reported participant.", [
      "reportedParticipantId",
    ]);
  }
  if (
    typeof body.reason !== "string" ||
    !REPORT_REASONS.includes(body.reason as ReportReason)
  ) {
    throw new ChallengeValidationError("Invalid report reason.", ["reason"]);
  }

  let details: string | null = null;
  if (body.details !== undefined && body.details !== null) {
    if (typeof body.details !== "string") {
      throw new ChallengeValidationError("Report details must be text.", ["details"]);
    }
    // Mobile presents this as a multiline editor. Collapse ordinary whitespace
    // (including line breaks) before rejecting hidden control characters so a
    // natural multiline report is accepted and stored as bounded plain text.
    details = body.details.normalize("NFKC").replace(/\p{White_Space}+/gu, " ").trim();
    if (details.length === 0) details = null;
    if (details !== null && (codePointLength(details) > 300 || CONTROL_CHARACTER.test(details))) {
      throw new ChallengeValidationError(
        "Report details must be at most 300 characters and contain no control characters.",
        ["details"],
      );
    }
  }

  return {
    reportedParticipantId: body.reportedParticipantId.toLowerCase(),
    reason: body.reason as ReportReason,
    details,
  };
}

export function parseLeaderboardQuery(url: URL, now = new Date()): LeaderboardQuery {
  const knownKeys = new Set(["category", "weekStart", "period", "periodStart", "limit", "offset"]);
  const unknownKeys = [...url.searchParams.keys()].filter((key) => !knownKeys.has(key));
  if (unknownKeys.length > 0) {
    throw new ChallengeValidationError("Unknown query parameter.", unknownKeys);
  }
  for (const key of knownKeys) {
    if (url.searchParams.getAll(key).length > 1) {
      throw new ChallengeValidationError("Query parameters cannot be repeated.", [key]);
    }
  }

  const category = url.searchParams.get("category");
  if (!category || !CHALLENGE_CATEGORIES.includes(category as ChallengeCategory)) {
    throw new ChallengeValidationError("Invalid leaderboard category.", ["category"]);
  }

  const limitValue = url.searchParams.get("limit");
  let limit = 50;
  if (limitValue !== null) {
    if (!/^\d{1,3}$/.test(limitValue)) {
      throw new ChallengeValidationError("limit must be an integer from 1 to 100.", [
        "limit",
      ]);
    }
    limit = Number(limitValue);
    if (limit < 1 || limit > 100) {
      throw new ChallengeValidationError("limit must be an integer from 1 to 100.", [
        "limit",
      ]);
    }
  }

  const offsetValue = url.searchParams.get("offset");
  const offset = offsetValue === null ? 0 : Number(offsetValue);
  if (offsetValue !== null && (!/^\d{1,16}$/.test(offsetValue) ||
      !Number.isSafeInteger(offset) || offset > Number.MAX_SAFE_INTEGER - limit)) {
    throw new ChallengeValidationError("offset must be a non-negative integer.", ["offset"]);
  }
  const periodValue = url.searchParams.get("period") ?? "weekly";
  if (periodValue !== "weekly" && periodValue !== "daily" && periodValue !== "monthly") {
    throw new ChallengeValidationError("Invalid challenge period.", ["period"]);
  }
  let weekStart: string;
  if (periodValue === "weekly") {
    if (url.searchParams.has("periodStart")) throw new ChallengeValidationError("Use weekStart for the weekly board.", ["periodStart"]);
    weekStart = parseWeekStart(url.searchParams.get("weekStart"));
    assertCurrentWeekWindow(weekStart, now);
  } else {
    if (url.searchParams.has("weekStart")) throw new ChallengeValidationError("Use periodStart for this board.", ["weekStart"]);
    weekStart = parsePeriodStart(periodValue, url.searchParams.get("periodStart"), now).key;
  }
  return {
    category: category as ChallengeCategory,
    weekStart,
    limit,
    offset,
    period: periodValue,
  };
}

// Local calendar boundaries on current civil time zones, without collecting a time zone.
function parsePeriodStart(period: "daily" | "monthly", value: unknown, now: Date): { key: string; elapsedDays: number } {
  const keyFor = (date: Date) => {
    const day = date.toISOString().slice(0, 10);
    return period === "daily" ? day : `${day.slice(0, 7)}-01`;
  };
  const earliest = new Date(now.getTime() - 12 * 3_600_000);
  const latest = new Date(now.getTime() + 14 * 3_600_000);
  const latestMatching = [latest, now, earliest].find((date) => value === keyFor(date));
  if (typeof value !== "string" || !latestMatching) {
    throw new ChallengeValidationError("periodStart must match the current local challenge period.", ["periodStart"]);
  }
  if (period === "daily") return { key: value, elapsedDays: 1 };
  return { key: value, elapsedDays: latestMatching.getUTCDate() };
}

export function parsePeriodScore(value: unknown, now = new Date()): PeriodScoreInput {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ChallengeValidationError("Expected a challenge score object.", []);
  }
  const body = value as Record<string, unknown>;
  const keys = ["activityDays", "nutritionDays", "consistencyDays", "hydrationDays", "balancedDays", "longestStreak"] as const;
  const allowed = new Set<string>(["period", "periodStart", "acceptedRules", ...keys]);
  const unknown = Object.keys(body).filter((key) => !allowed.has(key));
  if (unknown.length) throw new ChallengeValidationError("Unknown score fields.", unknown);
  if (body.period !== "daily" && body.period !== "monthly") {
    throw new ChallengeValidationError("Choose daily or monthly.", ["period"]);
  }
  if (body.acceptedRules !== true) {
    throw new ChallengeValidationError("Opt in to sharing scores for this period first.", ["acceptedRules"]);
  }
  const { key, elapsedDays } = parsePeriodStart(body.period, body.periodStart, now);
  const metrics = {} as Record<(typeof keys)[number], number>;
  for (const field of keys) {
    const metric = body[field];
    if (typeof metric !== "number" || !Number.isInteger(metric) || metric < 0 || metric > elapsedDays) {
      throw new ChallengeValidationError(`${field} must be an integer within elapsed days.`, [field]);
    }
    metrics[field] = metric;
  }
  const base = metrics.activityDays + metrics.nutritionDays + metrics.consistencyDays + metrics.hydrationDays;
  if (metrics.nutritionDays > metrics.consistencyDays ||
      metrics.balancedDays > Math.floor(base / 3) ||
      metrics.longestStreak > Math.floor(base / 2) ||
      (metrics.balancedDays > 0 && metrics.longestStreak === 0)) {
    throw new ChallengeValidationError("Habit totals and bonuses must agree.", ["nutritionDays", "balancedDays", "longestStreak"]);
  }
  const overallPoints = base + metrics.balancedDays + Math.max(0, metrics.longestStreak - 1);
  return { period: body.period, periodStart: key, ...metrics, overallPoints };
}

export function normalizeDisplayName(value: unknown): string {
  if (typeof value !== "string") {
    throw new ChallengeValidationError("Display name must be text.", ["displayName"]);
  }

  const name = value.normalize("NFKC").replace(/\p{White_Space}+/gu, " ").trim();
  const length = codePointLength(name);
  if (length < 2 || length > 40) {
    throw new ChallengeValidationError("Display name must be 2 to 40 characters.", [
      "displayName",
    ]);
  }
  if (
    !DISPLAY_NAME_ALLOWED.test(name) ||
    !DISPLAY_NAME_HAS_WORD.test(name) ||
    EMAIL_LIKE.test(name) ||
    URL_LIKE.test(name)
  ) {
    throw new ChallengeValidationError(
      "Display name may contain letters, numbers, spaces, periods, apostrophes, underscores, and hyphens only; links and emails are not allowed.",
      ["displayName"],
    );
  }

  const lower = name.toLocaleLowerCase("en-US");
  const tokens = lower.split(/[^\p{L}\p{N}]+/u).filter(Boolean);
  const compact = tokens.join("");
  const impersonatesFudAi =
    tokens.some((token, index) => token === "fud" && tokens[index + 1] === "ai") ||
    compact === "fudai";
  if (
    impersonatesFudAi ||
    tokens.some((token) => RESERVED_NAME_TOKENS.has(token)) ||
    tokens.some((token) => DISALLOWED_NAME_TOKENS.has(token)) ||
    [...DISALLOWED_COMPACT_PHRASES].some((phrase) => compact.includes(phrase))
  ) {
    throw new ChallengeValidationError(
      "Display name does not meet the Weekly Challenge community rules.",
      ["displayName"],
    );
  }

  return name;
}

export function parseWeekStart(value: unknown): string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new ChallengeValidationError("weekStart must be a Monday in YYYY-MM-DD format.", [
      "weekStart",
    ]);
  }
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== value ||
    parsed.getUTCDay() !== 1
  ) {
    throw new ChallengeValidationError("weekStart must be a Monday in YYYY-MM-DD format.", [
      "weekStart",
    ]);
  }
  return value;
}

function assertCurrentWeekWindow(weekStart: string, now: Date): void {
  const currentMonday = new Date(now);
  currentMonday.setUTCHours(0, 0, 0, 0);
  currentMonday.setUTCDate(currentMonday.getUTCDate() - ((currentMonday.getUTCDay() + 6) % 7));
  const requestedMonday = new Date(`${weekStart}T00:00:00.000Z`);
  if (Math.abs(requestedMonday.getTime() - currentMonday.getTime()) > 7 * 86_400_000) {
    throw new ChallengeValidationError(
      "weekStart must be the current challenge week (with a one-week timezone boundary allowance).",
      ["weekStart"],
    );
  }
}

function parseSocial(body: Record<string, unknown>): SocialProfile | null {
  const hasPlatform = Object.hasOwn(body, "socialPlatform");
  const hasHandle = Object.hasOwn(body, "socialHandle");
  if (!hasPlatform && !hasHandle) return null;
  if (!hasPlatform || !hasHandle) {
    throw new ChallengeValidationError(
      "socialPlatform and socialHandle must be supplied together.",
      ["socialPlatform", "socialHandle"],
    );
  }
  if (body.socialPlatform === null && body.socialHandle === null) return null;
  if (body.socialPlatform !== "x" && body.socialPlatform !== "instagram") {
    throw new ChallengeValidationError("socialPlatform must be x or instagram.", [
      "socialPlatform",
    ]);
  }
  if (typeof body.socialHandle !== "string") {
    throw new ChallengeValidationError("Invalid social handle.", ["socialHandle"]);
  }

  const handle = body.socialHandle.toLowerCase();
  const isValid =
    body.socialPlatform === "x"
      ? X_HANDLE.test(handle)
      : INSTAGRAM_HANDLE.test(handle);
  if (!isValid) {
    throw new ChallengeValidationError(
      body.socialPlatform === "x"
        ? "X handles must be 1 to 15 letters, numbers, or underscores, without @ or a URL."
        : "Instagram handles must be 1 to 30 letters, numbers, periods, or underscores, without @ or a URL.",
      ["socialHandle"],
    );
  }

  return { socialPlatform: body.socialPlatform, socialHandle: handle };
}

function parseInteger(
  value: unknown,
  field: string,
  minimum: number,
  maximum: number,
): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new ChallengeValidationError(
      `${field} must be an integer from ${minimum} to ${maximum}.`,
      [field],
    );
  }
  return value as number;
}

function requireObject(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ChallengeValidationError("Request body must be a JSON object.", ["body"]);
  }
  return value as Record<string, unknown>;
}

function assertKnownKeys(body: Record<string, unknown>, allowedKeys: string[]): void {
  const allowed = new Set(allowedKeys);
  const unknown = Object.keys(body).filter((key) => !allowed.has(key));
  if (unknown.length > 0) {
    throw new ChallengeValidationError("Request contains unknown fields.", unknown);
  }
}

function codePointLength(value: string): number {
  return Array.from(value).length;
}
