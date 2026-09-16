/**
 * Companion snapshot builder. Same fields as `WidgetSnapshot` / `WatchSnapshotSync` so a
 * native widget or watch target that shares the App Group can decode what the Expo app writes.
 */

import { dayKey, startOfDay } from '../dates';
import { activeFast, caloriesOn, foodEntriesOn, waterTotalOn, type DiaryState } from '../diary/diaryState';
import { homeNutrientGoal, homeNutrients } from '../diary/homeNutrients';
import { parseHomeTopNutrients, type HomeTopNutrientId, type Preferences } from '../prefs/preferences';
import { dailyTargets, type UserProfile } from '../profile/userProfile';
import type { CompanionSnapshot, WidgetNutrientValue, WidgetSnapshotPayload } from './companions';

/** Apple `Date` reference: 2001-01-01T00:00:00Z, used by default `JSONEncoder`. */
export const APPLE_REFERENCE_DATE_UNIX = 978_307_200;

/** Accent start/end hex without importing the UI theme layer. */
const ACCENT_HEX: Record<string, { start: string; end: string }> = {
  fudPink: { start: '#FF375F', end: '#FF6B8A' },
  red: { start: '#FF3B30', end: '#FF6961' },
  orange: { start: '#FF9500', end: '#FFB340' },
  green: { start: '#34C759', end: '#62D46F' },
  mint: { start: '#00C7BE', end: '#66D4CF' },
  teal: { start: '#30B0C7', end: '#64D2FF' },
  blue: { start: '#0A84FF', end: '#5EAEFF' },
  purple: { start: '#AF52DE', end: '#BF5AF2' },
  yellow: { start: '#FFCC00', end: '#FFD60A' },
  coral: { start: '#FF7F50', end: '#FFA382' },
  roseGold: { start: '#C9807C', end: '#E8B4B0' },
  mochaBrown: { start: '#A2845E', end: '#C9A57E' },
  indigo: { start: '#5856D6', end: '#7D7AFF' },
  lavender: { start: '#B57EDC', end: '#D0A9F5' },
  skyCyan: { start: '#32ADE6', end: '#70CFFF' },
  graphite: { start: '#8E8E93', end: '#B8B8BE' },
  babyPink: { start: '#FF8FAB', end: '#FFB3C6' },
  lime: { start: '#A0D911', end: '#C3E956' },
};

export function appleTimeInterval(date: Date): number {
  return date.getTime() / 1000 - APPLE_REFERENCE_DATE_UNIX;
}

export function hexToUint(hex: string): number {
  return Number.parseInt(hex.replace('#', ''), 16);
}

export function buildCompanionSnapshot(input: {
  now?: Date;
  diary: DiaryState;
  profile: UserProfile;
  prefs: Preferences;
}): CompanionSnapshot {
  const now = input.now ?? new Date();
  const day = startOfDay(now);
  const foods = foodEntriesOn(input.diary, day);
  const targets = dailyTargets(input.profile, now);
  const fast = activeFast(input.diary);
  return {
    day: dayKey(day),
    caloriesEaten: caloriesOn(input.diary, day),
    calorieGoal: targets.calories,
    protein: foods.reduce((sum, e) => sum + e.protein, 0),
    carbs: foods.reduce((sum, e) => sum + e.carbs, 0),
    fat: foods.reduce((sum, e) => sum + e.fat, 0),
    waterMilliliters: waterTotalOn(input.diary, day),
    waterGoalMilliliters: input.prefs.waterDailyGoalMl,
    ...(fast ? { fastStartedAt: fast.startedAt, fastGoalMinutes: fast.goalMinutes } : {}),
    accentId: ACCENT_HEX[input.prefs.appThemeColor] ? input.prefs.appThemeColor : 'fudPink',
  };
}

export function widgetNutrientValues(
  diary: DiaryState,
  profile: UserProfile,
  prefs: Preferences,
  now: Date = new Date(),
): WidgetNutrientValue[] {
  const foods = foodEntriesOn(diary, now);
  const targets = dailyTargets(profile, now);
  const selected = parseHomeTopNutrients(prefs.homeTopNutrients).filter((id) => id !== ('water' as HomeTopNutrientId));
  return selected.map((id) => {
    const def = homeNutrients[id];
    return {
      id,
      label: def.displayName,
      shortLabel: def.displayName.charAt(0).toUpperCase(),
      unit: def.unit,
      iconName: iconNameFor(id),
      value: def.total(foods),
      goal: homeNutrientGoal(id, targets),
    };
  });
}

function iconNameFor(id: HomeTopNutrientId): string {
  switch (id) {
    case 'protein':
      return 'fork.knife';
    case 'carbs':
      return 'leaf';
    case 'fat':
      return 'drop.fill';
    default:
      return 'circle';
  }
}

/** Wire format the native widget / watch decode (`WidgetSnapshot.swift`). */
export function widgetSnapshotPayload(input: {
  now?: Date;
  diary: DiaryState;
  profile: UserProfile;
  prefs: Preferences;
}): WidgetSnapshotPayload {
  const now = input.now ?? new Date();
  const snapshot = buildCompanionSnapshot({ ...input, now });
  const accent = ACCENT_HEX[snapshot.accentId] ?? ACCENT_HEX.fudPink!;
  const targets = dailyTargets(input.profile, now);
  return {
    date: appleTimeInterval(now),
    dayStart: appleTimeInterval(startOfDay(now)),
    calories: snapshot.caloriesEaten,
    calorieGoal: snapshot.calorieGoal,
    protein: snapshot.protein,
    proteinGoal: targets.protein,
    carbs: snapshot.carbs,
    carbsGoal: targets.carbs,
    fat: snapshot.fat,
    fatGoal: targets.fat,
    homeNutrients: widgetNutrientValues(input.diary, input.profile, input.prefs, now),
    waterTrackingEnabled: input.prefs.waterTrackingEnabled,
    waterCurrentMl: snapshot.waterMilliliters,
    waterGoalMl: snapshot.waterGoalMilliliters,
    waterUnitRaw: input.prefs.waterUnit === 'floz' ? 'floz' : 'ml',
    themeStartHex: hexToUint(accent.start),
    themeEndHex: hexToUint(accent.end),
  };
}
