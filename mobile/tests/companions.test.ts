import { describe, expect, it } from 'vitest';

import { companionStatuses, HealthUnavailableError, healthServiceName, noopSnapshotWriter, unavailableHealthSync } from '../src/domain/integrations/companions';
import { appleTimeInterval, buildCompanionSnapshot, hexToUint, widgetSnapshotPayload } from '../src/domain/integrations/snapshot';
import { initialDiaryState, diaryReducer } from '../src/domain/diary/diaryState';
import { makeFoodEntry } from '../src/domain/food/food';
import { defaultPreferences } from '../src/domain/prefs/preferences';
import { defaultUserProfile } from '../src/domain/profile/userProfile';

describe('companion seams', () => {
  it('lists health, widgets, watch and Siri with honest availability', () => {
    const ios = companionStatuses('ios');
    expect(ios.map((s) => s.id)).toEqual(['health', 'widgets', 'watch', 'siri']);
    expect(ios.find((s) => s.id === 'siri')?.availability).toBe('nativeOnly');
    expect(ios.find((s) => s.id === 'health')?.availability).toBe('available');
    const android = companionStatuses('android');
    expect(android.map((s) => s.id)).toEqual(['health', 'widgets']);
    expect(healthServiceName('android')).toBe('Health Connect');
    expect(companionStatuses('ios', { health: 'connected' })[0]?.availability).toBe('connected');
    expect(companionStatuses('ios', { health: 'denied' })[0]?.availability).toBe('denied');
  });

  it('refuses health writes when the bridge is unavailable', async () => {
    const health = unavailableHealthSync('ios');
    expect(health.isAvailable).toBe(false);
    await expect(health.requestAuthorization()).resolves.toBe(false);
    await expect(health.writeWeight(80, new Date())).rejects.toBeInstanceOf(HealthUnavailableError);
    await expect(health.deleteWeight('w1')).rejects.toBeInstanceOf(HealthUnavailableError);
    await expect(health.deleteBodyFat('bf1')).rejects.toBeInstanceOf(HealthUnavailableError);
    await expect(health.readSteps(new Date())).resolves.toBeUndefined();
    await expect(
      noopSnapshotWriter.write({
        date: 0,
        dayStart: 0,
        calories: 0,
        calorieGoal: 2000,
        protein: 0,
        proteinGoal: 150,
        carbs: 0,
        carbsGoal: 220,
        fat: 0,
        fatGoal: 70,
        homeNutrients: [],
        waterTrackingEnabled: false,
        waterCurrentMl: 0,
        waterGoalMl: 2000,
        waterUnitRaw: 'ml',
        themeStartHex: 0xff375f,
        themeEndHex: 0xff6b8a,
      }),
    ).resolves.toBeUndefined();
  });
});

describe('companion snapshot shape', () => {
  const now = new Date(2026, 8, 16, 12, 0, 0);
  const diary = diaryReducer(initialDiaryState, {
    type: 'food/add',
    entry: makeFoodEntry(
      { name: 'Oats', calories: 300, protein: 10, carbs: 50, fat: 5, source: 'manual', timestamp: now.toISOString() },
      'e1',
    ),
  });
  const withWater = diaryReducer(diary, { type: 'water/add', entry: { id: 'w1', date: now.toISOString(), milliliters: 250 } });

  it('sums today food, water and goals', () => {
    const snapshot = buildCompanionSnapshot({
      now,
      diary: withWater,
      profile: { ...defaultUserProfile, customCalories: 2000, customProtein: 150, customCarbs: 220, customFat: 70 },
      prefs: { ...defaultPreferences, waterDailyGoalMl: 2000, appThemeColor: 'fudPink' },
    });
    expect(snapshot.day).toBe('2026-09-16');
    expect(snapshot.caloriesEaten).toBe(300);
    expect(snapshot.calorieGoal).toBe(2000);
    expect(snapshot.protein).toBe(10);
    expect(snapshot.waterMilliliters).toBe(250);
    expect(snapshot.accentId).toBe('fudPink');
  });

  it('encodes widget dates as Apple reference intervals and Fud Pink hex', () => {
    const payload = widgetSnapshotPayload({
      now,
      diary: withWater,
      profile: { ...defaultUserProfile, customCalories: 2000, customProtein: 150, customCarbs: 220, customFat: 70 },
      prefs: { ...defaultPreferences, waterTrackingEnabled: true, waterDailyGoalMl: 2000, appThemeColor: 'fudPink' },
    });
    expect(payload.date).toBeCloseTo(appleTimeInterval(now), 6);
    expect(payload.calories).toBe(300);
    expect(payload.waterCurrentMl).toBe(250);
    expect(payload.themeStartHex).toBe(hexToUint('#FF375F'));
    expect(payload.homeNutrients[0]?.id).toBe('protein');
  });
});
