/**
 * Weekly Challenge scoring. Ported from `WeeklyChallengeAggregator` in
 * `ios/calorietracker/Models/WeeklyChallenge.swift`. The score is computed on device from
 * the diary; the leaderboard / profile round-trips (`WeeklyChallengeAPIClient`) are a remote
 * seam the shared app does not call yet.
 */

import { addDays, dayKey, startOfDay, startOfWeek } from '../dates';

export interface WeeklyChallengeWeek {
  start: Date;
  end: Date;
  /** `yyyy-MM-dd` of the Monday. */
  key: string;
}

/** Challenge weeks always start on Monday regardless of the `weekStartsOnMonday` preference. */
export function weeklyChallengeWeek(date: Date): WeeklyChallengeWeek {
  const start = startOfWeek(date, true);
  return { start, end: addDays(start, 6), key: dayKey(start) };
}

export interface WeeklyChallengeScore {
  weekStart: string;
  overallPoints: number;
  activityDays: number;
  nutritionDays: number;
  consistencyDays: number;
  hydrationDays: number;
  activityKcal: number;
}

export interface WeeklyChallengeInput {
  now?: Date;
  foods: readonly { date: string; calories: number }[];
  water: readonly { date: string; milliliters: number }[];
  activities: readonly { date: string; calories?: number }[];
  calorieGoal: number;
  hydrationEnabled: boolean;
  hydrationGoalMilliliters: number;
}

const clampDays = (n: number) => Math.min(Math.max(n, 0), 7);

export function weeklyChallengeScore(input: WeeklyChallengeInput): WeeklyChallengeScore {
  const now = input.now ?? new Date();
  const week = weeklyChallengeWeek(now);
  const today = startOfDay(now).getTime();
  const nextWeek = addDays(week.start, 7).getTime();
  const weekStart = week.start.getTime();

  const includedDay = (iso: string): string | undefined => {
    const day = startOfDay(new Date(iso));
    const time = day.getTime();
    if (time < weekStart || time >= nextWeek || time > today) return undefined;
    return dayKey(day);
  };

  const caloriesByDay = new Map<string, number>();
  for (const sample of input.foods) {
    const day = includedDay(sample.date);
    if (!day) continue;
    caloriesByDay.set(day, (caloriesByDay.get(day) ?? 0) + sample.calories);
  }

  const waterByDay = new Map<string, number>();
  for (const sample of input.water) {
    const day = includedDay(sample.date);
    if (!day) continue;
    waterByDay.set(day, (waterByDay.get(day) ?? 0) + Math.max(0, sample.milliliters));
  }

  const activityByDay = new Map<string, number>();
  for (const sample of input.activities) {
    const day = includedDay(sample.date);
    if (!day || sample.calories == null || sample.calories <= 0) continue;
    activityByDay.set(day, (activityByDay.get(day) ?? 0) + sample.calories);
  }

  let nutritionDays = 0;
  if (input.calorieGoal > 0) {
    const minimum = input.calorieGoal * 0.85;
    const maximum = input.calorieGoal * 1.15;
    nutritionDays = [...caloriesByDay.values()].filter((kcal) => kcal >= minimum && kcal <= maximum).length;
  }
  const hydrationDays =
    input.hydrationEnabled && input.hydrationGoalMilliliters > 0 ? [...waterByDay.values()].filter((ml) => ml >= input.hydrationGoalMilliliters).length : 0;
  const activityKcal = Math.min([...activityByDay.values()].reduce((total, kcal) => total + Math.min(kcal, 2_000), 0), 14_000);

  const activityDays = clampDays(activityByDay.size);
  const nutrition = clampDays(nutritionDays);
  const consistencyDays = clampDays(caloriesByDay.size);
  const hydration = clampDays(hydrationDays);
  return {
    weekStart: week.key,
    activityDays,
    nutritionDays: nutrition,
    consistencyDays,
    hydrationDays: hydration,
    overallPoints: Math.min(activityDays + nutrition + consistencyDays + hydration, 28),
    activityKcal,
  };
}
