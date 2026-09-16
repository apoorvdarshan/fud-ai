/**
 * Adaptive Goals. Port of `AdaptiveGoalSettings` in `UserProfile.swift`.
 *
 * Native uses a weekly AI goal pass. The shared app keeps the same cadence, previous-target
 * snapshot, and toggle restore — then applies the on-device formula (`dailyTargets`) so the
 * feature is real without inventing a local model.
 */

import { dayKey, startOfDay } from '../dates';
import { dailyTargets, type UserProfile } from './userProfile';

export const ADAPTIVE_GOALS_ENABLED_KEY = 'adaptiveGoalsEnabled';
export const ADAPTIVE_GOALS_PREVIOUS_TARGETS_KEY = 'adaptiveGoalsPreviousTargets';
export const ADAPTIVE_GOALS_LAST_CHECK_DAY_KEY = 'adaptiveGoalsLastCheckDay';
export const ADAPTIVE_GOALS_DAYS_BETWEEN_CHECKS = 7;

export interface AdaptiveTargetSnapshot {
  customCalories?: number;
  customProtein?: number;
  customFat?: number;
  customCarbs?: number;
}

export function parseAdaptiveTargetSnapshot(raw: string | undefined): AdaptiveTargetSnapshot | undefined {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw) as AdaptiveTargetSnapshot;
    return parsed && typeof parsed === 'object' ? parsed : undefined;
  } catch {
    return undefined;
  }
}

export function serializeAdaptiveTargetSnapshot(snapshot: AdaptiveTargetSnapshot): string {
  return JSON.stringify(snapshot);
}

export function snapshotFromProfile(profile: UserProfile): AdaptiveTargetSnapshot {
  return {
    ...(profile.customCalories !== undefined ? { customCalories: profile.customCalories } : {}),
    ...(profile.customProtein !== undefined ? { customProtein: profile.customProtein } : {}),
    ...(profile.customFat !== undefined ? { customFat: profile.customFat } : {}),
    ...(profile.customCarbs !== undefined ? { customCarbs: profile.customCarbs } : {}),
  };
}

export function restoreAdaptiveTargets(profile: UserProfile, snapshot: AdaptiveTargetSnapshot | undefined): UserProfile {
  if (!snapshot) return profile;
  return {
    ...profile,
    customCalories: snapshot.customCalories,
    customProtein: snapshot.customProtein,
    customFat: snapshot.customFat,
    customCarbs: snapshot.customCarbs,
  };
}

export function shouldCheckAdaptiveGoals(lastCheckDay: string | undefined, now: Date = new Date()): boolean {
  if (!lastCheckDay) return true;
  const last = dateFromDay(lastCheckDay);
  if (!last) return true;
  const days = Math.round((startOfDay(now).getTime() - startOfDay(last).getTime()) / 86_400_000);
  return days >= ADAPTIVE_GOALS_DAYS_BETWEEN_CHECKS;
}

function dateFromDay(key: string): Date | undefined {
  const parts = key.split('-').map((part) => Number.parseInt(part, 10));
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) return undefined;
  return new Date(parts[0]!, parts[1]! - 1, parts[2]!);
}

/** Pin custom targets to the current formula so weekly weight changes move the plan. */
export function applyFormulaAdaptiveGoals(profile: UserProfile, now: Date = new Date()): UserProfile {
  const unlocked: UserProfile = {
    ...profile,
    customCalories: undefined,
    customProtein: undefined,
    customCarbs: undefined,
    customFat: undefined,
  };
  const targets = dailyTargets(unlocked, now);
  return {
    ...unlocked,
    customCalories: targets.calories,
    customProtein: targets.protein,
    customCarbs: targets.carbs,
    customFat: targets.fat,
  };
}

export function markAdaptiveGoalsChecked(now: Date = new Date()): string {
  return dayKey(now);
}
