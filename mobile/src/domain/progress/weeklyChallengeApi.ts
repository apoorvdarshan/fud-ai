/**
 * Weekly Challenge leaderboard API. Types and client match `WeeklyChallengeAPIClient.swift`
 * (`https://fud-ai.app/api/challenge/v1`). Profile validation is the same rules as
 * `WeeklyChallengeProfileValidator`.
 */

import type { WeeklyChallengeScore } from './weeklyChallenge';

export const WEEKLY_CHALLENGE_API_BASE = 'https://fud-ai.app/api/challenge/v1';

export const weeklyChallengeCategories = ['overall', 'activity', 'nutrition', 'consistency', 'hydration'] as const;
export type WeeklyChallengeCategory = (typeof weeklyChallengeCategories)[number];

export const weeklyChallengeSocialPlatforms = ['x', 'instagram'] as const;
export type WeeklyChallengeSocialPlatform = (typeof weeklyChallengeSocialPlatforms)[number];

export interface WeeklyChallengeProfileInput {
  displayName: string;
  socialPlatform?: WeeklyChallengeSocialPlatform;
  socialHandle?: string;
}

export interface WeeklyChallengePublicProfile {
  participantId: string;
  displayName: string;
  socialPlatform?: WeeklyChallengeSocialPlatform;
  socialHandle?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface WeeklyChallengeParticipant {
  rank: number;
  participantId: string;
  displayName: string;
  socialPlatform?: WeeklyChallengeSocialPlatform;
  socialHandle?: string;
  score: number;
  overallPoints: number;
  activityDays: number;
  nutritionDays: number;
  consistencyDays: number;
  hydrationDays: number;
  activityKcal: number;
  updatedAt?: string;
  isViewer: boolean;
}

export interface WeeklyChallengeLeaderboardResponse {
  weekStart: string;
  category: WeeklyChallengeCategory;
  updatedAt: string;
  rankings: WeeklyChallengeParticipant[];
  viewer?: WeeklyChallengeParticipant;
}

export interface WeeklyChallengeCreateProfileResponse {
  participantId: string;
  bearerToken: string;
  profile: WeeklyChallengePublicProfile;
}

export type WeeklyChallengeProfileValidationError =
  | 'invalidDisplayName'
  | 'disallowedDisplayName'
  | 'missingSocialHandle'
  | 'invalidSocialHandle';

export const weeklyChallengeValidationMessages: Record<WeeklyChallengeProfileValidationError, string> = {
  invalidDisplayName: 'Use 2–40 letters or numbers. Spaces, periods, underscores, apostrophes, and hyphens are allowed.',
  disallowedDisplayName: 'Choose a different display name that follows the Community Rules.',
  missingSocialHandle: 'Enter the selected social handle, or choose No social link.',
  invalidSocialHandle: 'Enter a handle only—without @, spaces, or a profile URL.',
};

const reservedNameTokens = new Set(['admin', 'administrator', 'moderator', 'staff', 'support']);
const disallowedNameTokens = new Set([
  'bitch',
  'chink',
  'cunt',
  'faggot',
  'fuck',
  'kike',
  'kkk',
  'nazi',
  'nigger',
  'porn',
  'pornhub',
  'shit',
]);
const disallowedCompactPhrases = ['heilhitler', 'whitepower'];

export function normalizedDisplayName(value: string): string {
  return value.normalize('NFKC').replace(/\s+/g, ' ').trim();
}

export function validateWeeklyChallengeProfile(
  displayName: string,
  socialPlatform: WeeklyChallengeSocialPlatform | undefined,
  socialHandle: string,
): { ok: true; profile: WeeklyChallengeProfileInput } | { ok: false; error: WeeklyChallengeProfileValidationError } {
  const name = normalizedDisplayName(displayName);
  const nameError = displayNameValidationError(name);
  if (nameError) return { ok: false, error: nameError };
  if (!socialPlatform) return { ok: true, profile: { displayName: name } };
  const handle = socialHandle.trim().toLowerCase();
  if (!handle) return { ok: false, error: 'missingSocialHandle' };
  if (!isValidHandle(handle, socialPlatform)) return { ok: false, error: 'invalidSocialHandle' };
  return { ok: true, profile: { displayName: name, socialPlatform, socialHandle: handle } };
}

function displayNameValidationError(value: string): WeeklyChallengeProfileValidationError | undefined {
  const scalars = [...value];
  if (scalars.length < 2 || scalars.length > 40) return 'invalidDisplayName';
  if (!scalars.every(isAllowedDisplayNameChar) || !scalars.some(isLetterOrNumber) || resemblesURL(value)) {
    return 'invalidDisplayName';
  }
  const tokens = nameTokens(value);
  const compact = tokens.join('');
  const impersonates = tokens.some((token, i) => token === 'fud' && tokens[i + 1] === 'ai') || compact === 'fudai';
  if (impersonates || tokens.some((token) => reservedNameTokens.has(token) || disallowedNameTokens.has(token))) {
    return 'disallowedDisplayName';
  }
  if (disallowedCompactPhrases.some((phrase) => compact.includes(phrase))) return 'disallowedDisplayName';
  return undefined;
}

function isValidHandle(handle: string, platform: WeeklyChallengeSocialPlatform): boolean {
  return platform === 'x' ? /^[A-Za-z0-9_]{1,15}$/.test(handle) : /^(?!.*\.\.)(?!.*\.$)[A-Za-z0-9._]{1,30}$/.test(handle);
}

function resemblesURL(value: string): boolean {
  return /(?:\b(?:https?:\/\/|www\.)|\b[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+\b)/iu.test(value);
}

function nameTokens(value: string): string[] {
  const tokens: string[] = [];
  let token = '';
  for (const ch of value.toLowerCase()) {
    if (isLetterOrNumber(ch)) token += ch;
    else if (token) {
      tokens.push(token);
      token = '';
    }
  }
  if (token) tokens.push(token);
  return tokens;
}

function isAllowedDisplayNameChar(ch: string): boolean {
  if (isLetterOrNumber(ch)) return true;
  return ch === ' ' || ch === '.' || ch === '_' || ch === "'" || ch === '’' || ch === '-';
}

function isLetterOrNumber(ch: string): boolean {
  return /[\p{L}\p{N}]/u.test(ch);
}

export type WeeklyChallengeAPIErrorKind = 'invalidRequest' | 'invalidResponse' | 'offline' | 'server';

export class WeeklyChallengeAPIError extends Error {
  readonly kind: WeeklyChallengeAPIErrorKind;
  readonly statusCode?: number;

  constructor(kind: WeeklyChallengeAPIErrorKind, message?: string, statusCode?: number) {
    super(message ?? 'Could not reach the weekly challenge.');
    this.name = 'WeeklyChallengeAPIError';
    this.kind = kind;
    this.statusCode = statusCode;
  }
}

export interface WeeklyChallengeAPI {
  createProfile(input: WeeklyChallengeProfileInput & { acceptedRules: boolean; eligibilityAccepted: boolean }): Promise<WeeklyChallengeCreateProfileResponse>;
  leaderboard(category: WeeklyChallengeCategory, weekStart: string, token: string): Promise<WeeklyChallengeLeaderboardResponse>;
  putWeeklyScore(score: WeeklyChallengeScore, token: string): Promise<void>;
  deleteProfile(token: string): Promise<void>;
}

export function createWeeklyChallengeAPI(deps: { fetch: typeof fetch; baseURL?: string; signal?: AbortSignal }): WeeklyChallengeAPI {
  const base = (deps.baseURL ?? WEEKLY_CHALLENGE_API_BASE).replace(/\/$/, '');

  const request = async <T>(path: string, init: RequestInit & { token?: string }): Promise<T> => {
    const headers: Record<string, string> = { Accept: 'application/json', ...(init.headers as Record<string, string> | undefined) };
    if (init.token) headers.Authorization = `Bearer ${init.token}`;
    let response: Response;
    try {
      response = await deps.fetch(`${base}/${path}`, { ...init, headers, signal: deps.signal });
    } catch (error) {
      const message = error instanceof Error ? error.message.toLowerCase() : '';
      throw new WeeklyChallengeAPIError(message.includes('network') || message.includes('offline') ? 'offline' : 'invalidRequest');
    }
    if (response.status < 200 || response.status >= 300) {
      let serverMessage: string | undefined;
      try {
        const envelope = (await response.json()) as { error?: { message?: string } };
        serverMessage = envelope.error?.message;
      } catch {
        /* keep default */
      }
      throw new WeeklyChallengeAPIError('server', serverMessage ?? 'The weekly challenge could not be updated.', response.status);
    }
    if (response.status === 204) return undefined as T;
    try {
      return (await response.json()) as T;
    } catch {
      throw new WeeklyChallengeAPIError('invalidResponse');
    }
  };

  return {
    createProfile(input) {
      return request<WeeklyChallengeCreateProfileResponse>('profile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          displayName: input.displayName,
          acceptedRules: input.acceptedRules,
          eligibilityAccepted: input.eligibilityAccepted,
          socialPlatform: input.socialPlatform ?? null,
          socialHandle: input.socialHandle ?? null,
        }),
      });
    },
    leaderboard(category, weekStart, token) {
      const query = new URLSearchParams({
        category,
        weekStart,
        limit: '100',
      });
      return request<WeeklyChallengeLeaderboardResponse>(`leaderboard?${query.toString()}`, { method: 'GET', token });
    },
    async putWeeklyScore(score, token) {
      await request('weekly-score', {
        method: 'PUT',
        token,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(score),
      });
    },
    async deleteProfile(token) {
      await request('profile', { method: 'DELETE', token });
    },
  };
}
