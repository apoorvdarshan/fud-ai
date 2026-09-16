/**
 * HealthSync adapter. Loads the local `health-sync` Expo module (HealthKit / Health Connect)
 * and falls back to `unavailableHealthSync` in Expo Go.
 */

import { Platform } from 'react-native';

import type { HealthAuthorizationStatus, HealthSync, HealthNutritionTotals } from '../domain/integrations/companions';
import { unavailableHealthSync } from '../domain/integrations/companions';

const platform = Platform.OS === 'ios' ? 'ios' : 'android';

interface NativeHealthModule {
  isAvailable: boolean;
  authorizationStatus(): Promise<HealthAuthorizationStatus>;
  requestAuthorization(): Promise<boolean>;
  writeWeight(kg: number, dateMs: number, entryId?: string): Promise<void>;
  writeBodyFat(fraction: number, dateMs: number, entryId?: string): Promise<void>;
  writeNutrition(payload: HealthNutritionTotals & { dateMs: number }): Promise<void>;
  deleteNutrition(entryId: string): Promise<void>;
  deleteWeight(entryId: string): Promise<void>;
  deleteBodyFat(entryId: string): Promise<void>;
  readSteps(startMs: number, endMs: number): Promise<number | undefined>;
}

function loadNative(): NativeHealthModule | undefined {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { requireNativeModule } = require('expo-modules-core') as typeof import('expo-modules-core');
    return requireNativeModule<NativeHealthModule>('HealthSync');
  } catch {
    return undefined;
  }
}

const native = loadNative();

export function createHealthSync(): HealthSync {
  if (!native?.isAvailable) return unavailableHealthSync(platform);
  return {
    isAvailable: true,
    authorizationStatus: () => native.authorizationStatus(),
    requestAuthorization: () => native.requestAuthorization(),
    writeWeight: (kg, date, entryId) => native.writeWeight(kg, date.getTime(), entryId),
    writeBodyFat: (fraction, date, entryId) => native.writeBodyFat(fraction, date.getTime(), entryId),
    writeNutrition: (day, totals) => native.writeNutrition({ ...totals, dateMs: day.getTime() }),
    deleteNutrition: (entryId) => native.deleteNutrition(entryId),
    deleteWeight: (entryId) => native.deleteWeight(entryId),
    deleteBodyFat: (entryId) => native.deleteBodyFat(entryId),
    readSteps: async (day) => {
      const start = new Date(day);
      start.setHours(0, 0, 0, 0);
      const end = new Date(start);
      end.setDate(end.getDate() + 1);
      return native.readSteps(start.getTime(), Math.min(end.getTime(), Date.now()));
    },
  };
}

export const healthSync: HealthSync = createHealthSync();

function healthEnabled(): boolean {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { preferencesStore } = require('../state/appStores') as typeof import('../state/appStores');
    return preferencesStore.getState().healthKitEnabled && healthSync.isAvailable;
  } catch {
    return false;
  }
}

export async function writeFoodToHealthAsync(entry: {
  id: string;
  name: string;
  calories: number;
  protein: number;
  carbs: number;
  fat: number;
  timestamp: string;
}): Promise<void> {
  if (!healthEnabled()) return;
  await healthSync
    .writeNutrition(new Date(entry.timestamp), {
      calories: entry.calories,
      protein: entry.protein,
      carbs: entry.carbs,
      fat: entry.fat,
      name: entry.name,
      entryId: entry.id,
    })
    .catch(() => undefined);
}

export async function deleteFoodFromHealthAsync(entryId: string): Promise<void> {
  if (!healthEnabled()) return;
  await healthSync.deleteNutrition(entryId).catch(() => undefined);
}

/** Best-effort write; never blocks the diary. */
export function writeFoodToHealth(entry: { id: string; name: string; calories: number; protein: number; carbs: number; fat: number; timestamp: string }): void {
  void writeFoodToHealthAsync(entry);
}

export function deleteFoodFromHealth(entryId: string): void {
  void deleteFoodFromHealthAsync(entryId);
}

export function writeWeightToHealth(kg: number, date: Date, entryId?: string): void {
  if (!healthEnabled()) return;
  void healthSync.writeWeight(kg, date, entryId).catch(() => undefined);
}

export function writeBodyFatToHealth(fraction: number, date: Date, entryId?: string): void {
  if (!healthEnabled()) return;
  void healthSync.writeBodyFat(fraction, date, entryId).catch(() => undefined);
}

/** Bypasses the Health toggle so a previously synced sample is still removed. */
export function deleteWeightFromHealth(entryId: string): void {
  if (!healthSync.isAvailable) return;
  void healthSync.deleteWeight(entryId).catch(() => undefined);
}

/** Bypasses the Health toggle so a previously synced sample is still removed. */
export function deleteBodyFatFromHealth(entryId: string): void {
  if (!healthSync.isAvailable) return;
  void healthSync.deleteBodyFat(entryId).catch(() => undefined);
}
