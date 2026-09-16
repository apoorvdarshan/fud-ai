/**
 * Weekly Challenge account + leaderboard. Calls `https://fud-ai.app/api/challenge/v1`.
 * The bearer token lives in SecureStore — never in git.
 */

import { createWeeklyChallengeAPI, WeeklyChallengeAPIError, type WeeklyChallengeCreateProfileResponse, type WeeklyChallengeLeaderboardResponse, type WeeklyChallengeProfileInput } from '../domain/progress/weeklyChallengeApi';
import { weeklyChallengeScore } from '../domain/progress/weeklyChallenge';
import { dailyTargets } from '../domain/profile/userProfile';
import { diaryStore, preferencesStore, profileStore, workoutsStore } from '../state/appStores';
import { asyncKeyValueStore, secureSecretStore } from '../state/persistence';

const TOKEN_SECRET = 'weeklyChallenge.bearerToken.v1';
const PROFILE_KEY = 'weeklyChallenge.publicProfile.v1';

export const weeklyChallengeAPI = createWeeklyChallengeAPI({ fetch });

export async function loadChallengeToken(): Promise<string | null> {
  return secureSecretStore.get(TOKEN_SECRET).catch(() => null);
}

export async function loadChallengeProfile(): Promise<WeeklyChallengeCreateProfileResponse['profile'] | null> {
  const raw = await asyncKeyValueStore.get(PROFILE_KEY).catch(() => null);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as WeeklyChallengeCreateProfileResponse['profile'];
  } catch {
    return null;
  }
}

export async function joinWeeklyChallenge(
  input: WeeklyChallengeProfileInput & { acceptedRules: boolean; eligibilityAccepted: boolean },
): Promise<WeeklyChallengeCreateProfileResponse> {
  if (!input.acceptedRules || !input.eligibilityAccepted) {
    throw new Error('Confirm you are 18 or older and agree to the Community Rules.');
  }
  const created = await weeklyChallengeAPI.createProfile(input);
  await secureSecretStore.set(TOKEN_SECRET, created.bearerToken);
  await asyncKeyValueStore.set(PROFILE_KEY, JSON.stringify(created.profile));
  return created;
}

export async function leaveWeeklyChallenge(): Promise<void> {
  const token = await loadChallengeToken();
  if (token) {
    try {
      await weeklyChallengeAPI.deleteProfile(token);
    } catch (error) {
      const status = error instanceof WeeklyChallengeAPIError ? error.statusCode : undefined;
      if (status !== 401 && status !== 404) throw error;
    }
  }
  await secureSecretStore.remove(TOKEN_SECRET).catch(() => undefined);
  await asyncKeyValueStore.remove(PROFILE_KEY).catch(() => undefined);
}

export function localWeeklyScore() {
  const diary = diaryStore.getState();
  const prefs = preferencesStore.getState();
  const profile = profileStore.getState();
  const workouts = workoutsStore.getState();
  return weeklyChallengeScore({
    foods: diary.foodEntries.map((e) => ({ date: e.timestamp, calories: e.calories })),
    water: diary.waterEntries.map((e) => ({ date: e.date, milliliters: e.milliliters })),
    activities: workouts.sessions.map((s) => ({ date: s.diaryDate, ...(s.caloriesBurned !== undefined ? { calories: s.caloriesBurned } : {}) })),
    calorieGoal: dailyTargets(profile).calories,
    hydrationEnabled: prefs.waterTrackingEnabled,
    hydrationGoalMilliliters: prefs.waterDailyGoalMl,
  });
}

export async function fetchLeaderboard(category: Parameters<typeof weeklyChallengeAPI.leaderboard>[0]): Promise<WeeklyChallengeLeaderboardResponse> {
  const token = await loadChallengeToken();
  if (!token) throw new Error('Join the leaderboard first.');
  const score = localWeeklyScore();
  try {
    await weeklyChallengeAPI.putWeeklyScore(score, token);
  } catch {
    /* still show the board */
  }
  return weeklyChallengeAPI.leaderboard(category, score.weekStart, token);
}
