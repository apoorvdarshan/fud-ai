/**
 * One store for the whole Home diary: food, water and fasting.
 *
 * Android's `HomeViewModel` kept these in separate flows and merged them by snapshotting the
 * previous UI state; a food re-emission could then overwrite a newer water/fasting update
 * (#369). Here every mutation is a pure reducer step over a single immutable state, so there
 * is exactly one emission per change and no cross-collector lost updates by construction.
 */

import { isSameDay } from '../dates';
import { clampGoalMinutes, fastDiaryDate, fastsOverlap, isFastActive, type FastingSession } from '../fasting/fasting';
import { foodEntryDate, makeFoodEntry, type FoodEntry, type NewFoodEntryInput } from '../food/food';
import type { WaterEntry } from '../water/water';

export interface DiaryState {
  foodEntries: readonly FoodEntry[];
  waterEntries: readonly WaterEntry[];
  fastingSessions: readonly FastingSession[];
  /** Favorite keys (`FoodStore.favoriteKeys` on iOS) — name-based so re-logged meals match. */
  favoriteKeys: readonly string[];
  /** Monotonic counter; bumps on every persisted change so persistence can coalesce writes. */
  revision: number;
}

export const initialDiaryState: DiaryState = {
  foodEntries: [],
  waterEntries: [],
  fastingSessions: [],
  favoriteKeys: [],
  revision: 0,
};

export type DiaryAction =
  | { type: 'hydrate'; state: Partial<Omit<DiaryState, 'revision'>> }
  | { type: 'food/add'; entry: FoodEntry }
  | { type: 'food/update'; entry: FoodEntry }
  | { type: 'food/delete'; id: string }
  | { type: 'food/toggleFavorite'; entry: FoodEntry }
  | { type: 'water/add'; entry: WaterEntry }
  | { type: 'water/delete'; id: string }
  | { type: 'fasting/start'; session: FastingSession }
  | { type: 'fasting/end'; endedAt: string }
  | { type: 'fasting/cancelActive' }
  | { type: 'fasting/update'; session: FastingSession }
  | { type: 'fasting/delete'; id: string }
  | { type: 'food/replaceAll'; entries: readonly FoodEntry[] }
  | { type: 'water/replaceAll'; entries: readonly WaterEntry[] }
  | { type: 'clearAll' };

export function favoriteKey(entry: Pick<FoodEntry, 'name'>): string {
  return entry.name.trim().toLowerCase();
}

function bump(state: DiaryState, patch: Partial<DiaryState>): DiaryState {
  return { ...state, ...patch, revision: state.revision + 1 };
}

function sortSessions(sessions: readonly FastingSession[]): FastingSession[] {
  return [...sessions].sort((a, b) => a.startedAt.localeCompare(b.startedAt));
}

export function diaryReducer(state: DiaryState, action: DiaryAction): DiaryState {
  switch (action.type) {
    case 'hydrate':
      return {
        ...state,
        foodEntries: action.state.foodEntries ?? state.foodEntries,
        waterEntries: action.state.waterEntries ?? state.waterEntries,
        fastingSessions: sortSessions(action.state.fastingSessions ?? state.fastingSessions),
        favoriteKeys: action.state.favoriteKeys ?? state.favoriteKeys,
      };

    case 'food/add':
      if (state.foodEntries.some((e) => e.id === action.entry.id)) return state;
      return bump(state, { foodEntries: [...state.foodEntries, action.entry] });

    case 'food/update': {
      const index = state.foodEntries.findIndex((e) => e.id === action.entry.id);
      if (index === -1) return state;
      const next = [...state.foodEntries];
      next[index] = action.entry;
      return bump(state, { foodEntries: next });
    }

    case 'food/delete':
      if (!state.foodEntries.some((e) => e.id === action.id)) return state;
      return bump(state, { foodEntries: state.foodEntries.filter((e) => e.id !== action.id) });

    case 'food/toggleFavorite': {
      const key = favoriteKey(action.entry);
      const has = state.favoriteKeys.includes(key);
      return bump(state, {
        favoriteKeys: has ? state.favoriteKeys.filter((k) => k !== key) : [...state.favoriteKeys, key],
      });
    }

    case 'water/add':
      if (action.entry.milliliters <= 0) return state;
      // Idempotent on id: a retried Watch/widget transfer can never log the same glass twice.
      if (state.waterEntries.some((e) => e.id === action.entry.id)) return state;
      return bump(state, { waterEntries: [...state.waterEntries, action.entry] });

    case 'water/delete':
      if (!state.waterEntries.some((e) => e.id === action.id)) return state;
      return bump(state, { waterEntries: state.waterEntries.filter((e) => e.id !== action.id) });

    case 'fasting/start': {
      if (activeFast(state)) return state;
      const session = { ...action.session, goalMinutes: clampGoalMinutes(action.session.goalMinutes) };
      if (state.fastingSessions.some((s) => fastsOverlap(s, session))) return state;
      return bump(state, { fastingSessions: sortSessions([...state.fastingSessions, session]) });
    }

    case 'fasting/end': {
      const active = activeFast(state);
      if (!active) return state;
      const endedAt = action.endedAt < active.startedAt ? active.startedAt : action.endedAt;
      return bump(state, {
        fastingSessions: state.fastingSessions.map((s) => (s.id === active.id ? { ...s, endedAt } : s)),
      });
    }

    case 'fasting/cancelActive': {
      const active = activeFast(state);
      if (!active) return state;
      return bump(state, { fastingSessions: state.fastingSessions.filter((s) => s.id !== active.id) });
    }

    case 'fasting/update': {
      const index = state.fastingSessions.findIndex((s) => s.id === action.session.id);
      if (index === -1) return state;
      const validated: FastingSession = { ...action.session, goalMinutes: clampGoalMinutes(action.session.goalMinutes) };
      if (validated.endedAt !== undefined && validated.endedAt < validated.startedAt) {
        validated.endedAt = validated.startedAt;
      }
      if (isFastActive(validated) && state.fastingSessions.some((s) => s.id !== validated.id && isFastActive(s))) {
        return state;
      }
      if (state.fastingSessions.some((s) => s.id !== validated.id && fastsOverlap(s, validated))) return state;
      const next = [...state.fastingSessions];
      next[index] = validated;
      return bump(state, { fastingSessions: sortSessions(next) });
    }

    case 'fasting/delete':
      if (!state.fastingSessions.some((s) => s.id === action.id)) return state;
      return bump(state, { fastingSessions: state.fastingSessions.filter((s) => s.id !== action.id) });

    case 'food/replaceAll':
      return bump(state, { foodEntries: [...action.entries] });

    case 'water/replaceAll':
      return bump(state, { waterEntries: [...action.entries] });

    case 'clearAll':
      return bump(state, { foodEntries: [], waterEntries: [], fastingSessions: [], favoriteKeys: [] });
  }
}

// MARK: - Selectors (pure; safe to memoize per state reference)

export function foodEntriesOn(state: DiaryState, day: Date): FoodEntry[] {
  return state.foodEntries
    .filter((e) => isSameDay(foodEntryDate(e), day))
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
}

export function caloriesOn(state: DiaryState, day: Date): number {
  return foodEntriesOn(state, day).reduce((sum, e) => sum + e.calories, 0);
}

export function waterEntriesOn(state: DiaryState, day: Date): WaterEntry[] {
  return state.waterEntries.filter((e) => isSameDay(new Date(e.date), day)).sort((a, b) => b.date.localeCompare(a.date));
}

export function waterTotalOn(state: DiaryState, day: Date): number {
  return waterEntriesOn(state, day).reduce((sum, e) => sum + e.milliliters, 0);
}

export function activeFast(state: DiaryState): FastingSession | undefined {
  for (let i = state.fastingSessions.length - 1; i >= 0; i -= 1) {
    const session = state.fastingSessions[i];
    if (session && isFastActive(session)) return session;
  }
  return undefined;
}

export function completedFastsOn(state: DiaryState, day: Date): FastingSession[] {
  return state.fastingSessions
    .filter((s) => s.endedAt !== undefined && isSameDay(fastDiaryDate(s), day))
    .sort((a, b) => (b.endedAt ?? '').localeCompare(a.endedAt ?? ''));
}

export function isFavorite(state: DiaryState, entry: Pick<FoodEntry, 'name'>): boolean {
  return state.favoriteKeys.includes(favoriteKey(entry));
}

/** Distinct meals by favorite key, newest first — the Saved Meals list. */
export function uniqueEntriesByName(entries: readonly FoodEntry[], limit: number): FoodEntry[] {
  const seen = new Set<string>();
  const result: FoodEntry[] = [];
  for (const entry of [...entries].sort((a, b) => b.timestamp.localeCompare(a.timestamp))) {
    const key = favoriteKey(entry);
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(entry);
    if (result.length === limit) break;
  }
  return result;
}

export function favoriteEntries(state: DiaryState): FoodEntry[] {
  return uniqueEntriesByName(state.foodEntries.filter((e) => isFavorite(state, e)), Number.POSITIVE_INFINITY);
}

export function recentEntries(state: DiaryState, limit = 20): FoodEntry[] {
  return uniqueEntriesByName(state.foodEntries.filter((e) => !isFavorite(state, e)), limit);
}

/** `FoodStore.frequentGroups` — count by name+calories over the last 90 days. */
export function frequentEntries(state: DiaryState, limit = 20, now: Date = new Date()): FoodEntry[] {
  const cutoff = now.getTime() - 90 * 24 * 60 * 60 * 1000;
  const aggregates = new Map<string, { count: number; template: FoodEntry }>();
  for (const entry of state.foodEntries) {
    if (Date.parse(entry.timestamp) < cutoff) continue;
    const key = `${favoriteKey(entry)}|${entry.calories}`;
    const current = aggregates.get(key);
    if (!current) {
      aggregates.set(key, { count: 1, template: entry });
      continue;
    }
    const template = entry.timestamp > current.template.timestamp ? entry : current.template;
    aggregates.set(key, { count: current.count + 1, template });
  }
  return [...aggregates.values()]
    .sort((a, b) => (a.count !== b.count ? b.count - a.count : a.template.name.localeCompare(b.template.name)))
    .slice(0, limit)
    .map((pair) => pair.template);
}

/** Convenience for callers that build entries from a review screen. */
export function newFoodEntryAction(input: NewFoodEntryInput, id: string, now: Date = new Date()): DiaryAction {
  return { type: 'food/add', entry: makeFoodEntry(input, id, now) };
}
