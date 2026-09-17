import { describe, expect, it, vi } from 'vitest';

vi.mock('expo-crypto', () => ({ randomUUID: () => 'test-id' }));
vi.mock('@react-native-async-storage/async-storage', () => ({ default: {} }));
vi.mock('expo-secure-store', () => ({}));
vi.mock('expo-modules-core', () => ({ requireNativeModule: () => { throw new Error('unavailable'); } }));
vi.mock('react-native', () => ({ Platform: { OS: 'ios' } }));

import {
  mapNativeSnapshot,
  nativeDateToIso,
  nativeHasUserData,
  planNativeMigration,
  referencedFoodImageFilenames,
  rnLooksPopulated,
  rnStoreHasData,
  storesToWrite,
} from '../src/domain/nativeMigration/mapNativeSnapshot';
import { NATIVE_MIGRATION_DONE, NATIVE_MIGRATION_STARTED, type NativeStorageSnapshot } from '../src/domain/nativeMigration/nativeSnapshot';
import { migrateNativeDataIfNeeded, NATIVE_FOOD_IMAGE_COPY_FAILED } from '../src/services/nativeMigration';
import { memoryKeyValueStore } from '../src/state/persistence';
import { storageKeys } from '../src/state/storageKeys';

const ISO = '2026-01-15T12:00:00.000Z';
const unixMs = Date.parse(ISO);
const iosRefSeconds = unixMs / 1000 - 978_307_200;

function iosFoodBlob(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify([
    {
      id: '11111111-1111-1111-1111-111111111111',
      name: 'Oats',
      calories: 300,
      protein: 12,
      carbs: 50,
      fat: 6,
      timestamp: iosRefSeconds,
      source: 'manual',
      mealType: 'breakfast',
      additionalImageFilenames: ['11111111-1111-1111-1111-111111111111.jpg'],
      imageFilename: '11111111-1111-1111-1111-111111111111.jpg',
      servingUnitOptions: [],
      supplementalNutrients: {},
      progressiveMeal: false,
      ingredients: [],
      ...overrides,
    },
  ]);
}

function androidFoodBlob(): string {
  return JSON.stringify([
    {
      id: '22222222-2222-2222-2222-222222222222',
      name: 'Rice',
      calories: 200,
      protein: 4,
      carbs: 44,
      fat: 1,
      timestamp: unixMs,
      source: 'snapFood',
      mealType: 'lunch',
      additionalImageFilenames: [],
      servingUnitOptions: [],
      supplementalNutrients: {},
      progressiveMeal: false,
      ingredients: [],
    },
  ]);
}

describe('nativeDateToIso', () => {
  it('decodes iOS reference-date seconds and Android epoch millis', () => {
    expect(nativeDateToIso(iosRefSeconds, 'ios')).toBe(ISO);
    expect(nativeDateToIso(unixMs, 'android')).toBe(ISO);
    expect(nativeDateToIso(ISO, 'ios')).toBe(ISO);
  });

  it('keeps Android InstantSerializer epoch-ms birthdays near Unix epoch', () => {
    // 1970-06-01 is ~1.3e10 ms — the old abs<1e11-as-seconds heuristic shifted this to ~2381.
    const june1970Ms = Date.UTC(1970, 5, 1);
    expect(june1970Ms).toBeLessThan(1e11);
    expect(nativeDateToIso(june1970Ms, 'android')).toBe('1970-06-01T00:00:00.000Z');
    const appleSeconds = june1970Ms / 1000 - 978_307_200;
    expect(nativeDateToIso(appleSeconds, 'ios')).toBe('1970-06-01T00:00:00.000Z');
  });
});

describe('mapNativeSnapshot', () => {
  it('maps iOS JSON blobs and UserDefaults prefs into RN diary / prefs / profile / body', () => {
    const snapshot: NativeStorageSnapshot = {
      available: true,
      platform: 'ios',
      blobs: {
        foodEntries: iosFoodBlob(),
        favoriteFoodEntries: iosFoodBlob({ name: 'Greek Yogurt', calories: 150 }),
        waterEntries: JSON.stringify([{ id: 'w1', date: iosRefSeconds, milliliters: 250 }]),
        fastingSessions: JSON.stringify([{ id: 'f1', startedAt: iosRefSeconds, goalMinutes: 960 }]),
        weightEntries: JSON.stringify([{ id: 'wt1', date: iosRefSeconds, weightKg: 72.4 }]),
        bodyFatEntries: JSON.stringify([{ id: 'bf1', date: iosRefSeconds, bodyFatFraction: 0.18 }]),
        userProfile: JSON.stringify({
          name: 'Ada',
          gender: 'female',
          birthday: iosRefSeconds,
          heightCm: 168,
          weightKg: 72.4,
          activityLevel: 'moderate',
          goal: 'lose',
          customCalories: 1800,
        }),
        coachChatHistory: JSON.stringify([
          { id: 'c1', role: 'user', content: 'Hi', timestamp: iosRefSeconds, attachmentImageData: 'abc' },
        ]),
        'fudai.workouts.diary.state.v1': JSON.stringify({
          version: 1,
          completedSessions: [
            {
              id: 'ws1',
              diaryDate: iosRefSeconds,
              diaryDateKey: '2026-01-15',
              startedAt: iosRefSeconds,
              completedAt: iosRefSeconds,
              durationSeconds: 2400,
              caloriesBurned: 180,
              exercises: [
                {
                  id: 'we1',
                  itemID: 'bench',
                  name: 'Bench Press',
                  targetMuscles: ['chest'],
                  equipment: 'barbell',
                  sets: [{ id: 's1', setNumber: 1, weight: '135', weightUnit: 'lbs', reps: '8', rpe: '7', rpeScale: 'strength' }],
                },
              ],
            },
          ],
          dayPlans: {
            '2026-01-16': {
              dateKey: '2026-01-16',
              exercises: [
                {
                  id: 'de1',
                  itemID: 'squat',
                  name: 'Squat',
                  primaryMuscles: ['quads'],
                  rawEquipment: 'barbell',
                  category: 'strength',
                  sets: [{ id: 'ds1', weight: '185', reps: '5', rpe: '' }],
                },
              ],
            },
          },
          preferences: { split: 'fullBody', rpeScale: 'strength' },
        }),
      },
      prefs: {
        hasCompletedOnboarding: true,
        waterTrackingEnabled: true,
        waterDailyGoalMl: 3000,
        waterUnit: 'ml',
        appearanceMode: 'dark',
        aiAnalysisConsentGiven: true,
        healthKitEnabled: true,
        selectedAIProvider: 'Google Gemini',
      },
    };

    const mapped = mapNativeSnapshot(snapshot);
    expect(mapped.diary?.foodEntries).toHaveLength(1);
    expect(mapped.diary?.foodEntries[0]).toMatchObject({
      name: 'Oats',
      calories: 300,
      timestamp: ISO,
      imageFilename: '11111111-1111-1111-1111-111111111111.jpg',
    });
    expect(mapped.diary?.favoriteKeys).toEqual(['greek yogurt']);
    expect(mapped.diary?.waterEntries[0]).toMatchObject({ milliliters: 250, date: ISO });
    expect(mapped.diary?.fastingSessions[0]).toMatchObject({ goalMinutes: 960, startedAt: ISO });
    expect(mapped.preferences).toMatchObject({
      hasCompletedOnboarding: true,
      waterTrackingEnabled: true,
      waterDailyGoalMl: 3000,
      appearanceMode: 'dark',
      aiConsentGiven: true,
      healthKitEnabled: true,
      selectedAIProvider: 'Google Gemini',
    });
    expect(mapped.profile).toMatchObject({ name: 'Ada', gender: 'female', weightKg: 72.4, customCalories: 1800, birthday: ISO });
    expect(mapped.body?.weightEntries[0]).toMatchObject({ weightKg: 72.4, date: ISO });
    expect(mapped.body?.bodyFatEntries[0]).toMatchObject({ bodyFatFraction: 0.18 });
    expect(mapped.chat?.[0]).toMatchObject({ content: 'Hi', attachmentImageBase64: 'abc', timestamp: ISO });
    expect(mapped.workouts?.sessions[0]).toMatchObject({
      diaryDateKey: '2026-01-15',
      caloriesBurned: 180,
      exercises: [expect.objectContaining({ itemID: 'bench', name: 'Bench Press' })],
    });
    expect(mapped.workouts?.drafts['2026-01-16']?.exercises[0]).toMatchObject({ itemID: 'squat', name: 'Squat' });
  });

  it('maps Android DataStore millis timestamps and key aliases', () => {
    const snapshot: NativeStorageSnapshot = {
      available: true,
      platform: 'android',
      blobs: {
        foodEntries: androidFoodBlob(),
        favorites: JSON.stringify(['Rice|200']),
        userProfile: JSON.stringify({
          gender: 'male',
          birthday: unixMs,
          heightCm: 180,
          weightKg: 80,
          activityLevel: 'active',
          goal: 'gain',
        }),
        workoutDiaryStateV1: JSON.stringify({
          version: 1,
          completedSessions: [
            {
              id: 'ws2',
              diaryDateKey: '2026-01-15',
              startedAt: unixMs,
              completedAt: unixMs,
              durationSeconds: 1800,
              exercises: [
                {
                  id: 'we2',
                  itemId: 'row',
                  name: 'Barbell Row',
                  primaryMuscles: ['back'],
                  equipment: 'barbell',
                  sets: [{ id: 's2', setNumber: 1, weight: '60', weightUnit: 'KG', reps: '10', rpe: '8' }],
                },
              ],
            },
          ],
          preferences: { split: 'UPPER_LOWER', rpeScale: 'CR10' },
        }),
      },
      prefs: {
        hasCompletedOnboarding: true,
        healthConnectEnabled: true,
        userContext: 'no onions',
        maxResponseTokens: 2048,
        weightUnit: 'kg',
      },
    };

    const mapped = mapNativeSnapshot(snapshot);
    expect(mapped.diary?.foodEntries[0]).toMatchObject({ name: 'Rice', timestamp: ISO, source: 'snapFood' });
    expect(mapped.diary?.favoriteKeys).toEqual(['rice']);
    expect(mapped.preferences).toMatchObject({
      healthKitEnabled: true,
      aiUserContext: 'no onions',
      aiMaxResponseTokens: 2048,
      weightUnit: 'kg',
    });
    expect(mapped.profile?.birthday).toBe(ISO);
    const june1970Ms = Date.UTC(1970, 5, 1);
    const earlyBirthday = mapNativeSnapshot({
      available: true,
      platform: 'android',
      blobs: {
        userProfile: JSON.stringify({
          gender: 'male',
          birthday: june1970Ms,
          heightCm: 180,
          weightKg: 80,
          activityLevel: 'active',
          goal: 'maintain',
        }),
      },
      prefs: { hasCompletedOnboarding: true },
    });
    expect(earlyBirthday.profile?.birthday).toBe('1970-06-01T00:00:00.000Z');
    expect(mapped.workouts?.sessions[0]?.exercises[0]).toMatchObject({ itemID: 'row' });
    expect(mapped.workouts?.preferences).toMatchObject({ split: 'upperLower', rpeScale: 'cr10', weightUnit: 'kg' });
  });

  it('drops corrupt rows and ignores a wholly unreadable blob', () => {
    const snapshot: NativeStorageSnapshot = {
      available: true,
      platform: 'ios',
      blobs: {
        foodEntries: JSON.stringify([
          { id: 'ok', name: 'Apple', calories: 80, protein: 0, carbs: 20, fat: 0, timestamp: iosRefSeconds, source: 'manual' },
          { name: 'missing-required-fields' },
          null,
        ]),
        waterEntries: '{not-json',
        fastingSessions: JSON.stringify([]),
      },
      prefs: { hasCompletedOnboarding: true },
    };

    const mapped = mapNativeSnapshot(snapshot);
    expect(mapped.diary?.foodEntries).toHaveLength(1);
    expect(mapped.diary?.foodEntries[0]?.name).toBe('Apple');
    expect(mapped.diary?.waterEntries).toEqual([]);
    expect(mapped.diary?.fastingSessions).toEqual([]);
  });
});

describe('planNativeMigration', () => {
  const populatedNative: NativeStorageSnapshot = {
    available: true,
    platform: 'ios',
    blobs: { foodEntries: iosFoodBlob() },
    prefs: { hasCompletedOnboarding: true },
  };

  it('skips when the migration marker is already done', () => {
    expect(planNativeMigration({ marker: NATIVE_MIGRATION_DONE, snapshot: populatedNative, existing: {} })).toEqual({
      action: 'skip',
      reason: 'already-migrated',
    });
  });

  it('skips when RN already looks like a real install', () => {
    expect(
      planNativeMigration({
        marker: null,
        snapshot: populatedNative,
        existing: { preferences: { hasCompletedOnboarding: true } },
      }),
    ).toEqual({ action: 'skip', reason: 'rn-populated' });
    expect(
      planNativeMigration({
        marker: null,
        snapshot: populatedNative,
        existing: { diary: { foodEntries: [{ id: 'x' }], waterEntries: [], fastingSessions: [], favoriteKeys: [] } },
      }),
    ).toEqual({ action: 'skip', reason: 'rn-populated' });
  });

  it('skips when the native module is unavailable (Expo Go)', () => {
    expect(planNativeMigration({ marker: null, snapshot: undefined, existing: {} })).toEqual({
      action: 'skip',
      reason: 'native-unavailable',
    });
    expect(planNativeMigration({ marker: null, snapshot: { available: false, platform: 'ios', blobs: {}, prefs: {} }, existing: {} })).toEqual({
      action: 'skip',
      reason: 'native-unavailable',
    });
  });

  it('marks empty native storage done without writing diary data', () => {
    expect(
      planNativeMigration({
        marker: null,
        snapshot: { available: true, platform: 'android', blobs: {}, prefs: { hasCompletedOnboarding: false } },
        existing: {},
      }),
    ).toEqual({ action: 'mark-empty' });
  });

  it('migrates when native has onboarding or diary data', () => {
    const plan = planNativeMigration({ marker: null, snapshot: populatedNative, existing: {} });
    expect(plan.action).toBe('migrate');
    if (plan.action === 'migrate') {
      expect(plan.mapped.preferences?.hasCompletedOnboarding).toBe(true);
      expect(plan.mapped.diary?.foodEntries[0]?.name).toBe('Oats');
    }
  });

  it('resumes when marker is started even if RN already looks populated', () => {
    const existing = {
      diary: { foodEntries: [{ id: 'partial' }], waterEntries: [], fastingSessions: [], favoriteKeys: [] },
      preferences: { hasCompletedOnboarding: true },
    };
    const plan = planNativeMigration({
      marker: NATIVE_MIGRATION_STARTED,
      snapshot: populatedNative,
      existing,
    });
    expect(plan.action).toBe('migrate');
    if (plan.action === 'migrate') {
      expect(plan.mapped.diary?.foodEntries[0]?.name).toBe('Oats');
      expect(storesToWrite(NATIVE_MIGRATION_STARTED, existing, plan.mapped)).toEqual([]);
    }
  });

  it('on a started retry writes only stores that are still empty', () => {
    const mapped = mapNativeSnapshot(populatedNative);
    expect(
      storesToWrite(
        NATIVE_MIGRATION_STARTED,
        {
          diary: { foodEntries: [{ id: 'user-meal' }], waterEntries: [], fastingSessions: [], favoriteKeys: [] },
        },
        mapped,
      ),
    ).toEqual(['preferences']);
    expect(storesToWrite(null, {}, mapped)).toEqual(['diary', 'preferences']);
    expect(rnStoreHasData('diary', { foodEntries: [{ id: 'x' }] })).toBe(true);
    expect(rnStoreHasData('workouts', { sessions: [], drafts: { '2026-01-15': { dateKey: '2026-01-15', exercises: [] } } })).toBe(true);
  });
});

describe('rnLooksPopulated / nativeHasUserData', () => {
  it('treats default-false prefs as empty native', () => {
    expect(nativeHasUserData({ available: true, platform: 'ios', blobs: {}, prefs: { hasCompletedOnboarding: false, waterTrackingEnabled: false } })).toBe(false);
    expect(rnLooksPopulated({})).toBe(false);
    expect(rnLooksPopulated({ diary: { foodEntries: [], waterEntries: [], fastingSessions: [], favoriteKeys: [] } })).toBe(false);
  });
});

describe('migrateNativeDataIfNeeded', () => {
  it('writes RN keys from a native snapshot and records the marker', async () => {
    const kv = memoryKeyValueStore();
    await migrateNativeDataIfNeeded(kv, async () => ({
      available: true,
      platform: 'ios',
      blobs: { foodEntries: iosFoodBlob() },
      prefs: { hasCompletedOnboarding: true, appearanceMode: 'light' },
    }));

    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_DONE);
    expect(JSON.parse((await kv.get(storageKeys.diary))!).foodEntries[0].name).toBe('Oats');
    expect(JSON.parse((await kv.get(storageKeys.preferences))!).hasCompletedOnboarding).toBe(true);
  });

  it('does not clobber an existing RN diary', async () => {
    const kv = memoryKeyValueStore({
      [storageKeys.diary]: JSON.stringify({
        foodEntries: [{ id: 'rn', name: 'Expo Meal', calories: 1, protein: 0, carbs: 0, fat: 0, timestamp: ISO, source: 'manual', additionalImageFilenames: [], servingUnitOptions: [], supplementalNutrients: {}, progressiveMeal: false, ingredients: [], mealType: 'other' }],
        waterEntries: [],
        fastingSessions: [],
        favoriteKeys: [],
      }),
    });
    await migrateNativeDataIfNeeded(kv, async () => ({
      available: true,
      platform: 'ios',
      blobs: { foodEntries: iosFoodBlob() },
      prefs: { hasCompletedOnboarding: true },
    }));
    expect(await kv.get(storageKeys.nativeMigration)).toBeNull();
    expect(JSON.parse((await kv.get(storageKeys.diary))!).foodEntries[0].name).toBe('Expo Meal');
  });

  it('leaves Expo Go (unavailable) unmarked so a later binary can still migrate', async () => {
    const kv = memoryKeyValueStore();
    await migrateNativeDataIfNeeded(kv, async () => undefined);
    expect(await kv.get(storageKeys.nativeMigration)).toBeNull();
    expect(await kv.get(storageKeys.diary)).toBeNull();
  });

  it('resumes a partial write when the marker is started without clobbering RN diary', async () => {
    const kv = memoryKeyValueStore({
      [storageKeys.nativeMigration]: NATIVE_MIGRATION_STARTED,
      [storageKeys.diary]: JSON.stringify({
        foodEntries: [{ id: 'user-edit', name: 'Logged after failure' }],
        waterEntries: [],
        fastingSessions: [],
        favoriteKeys: [],
      }),
    });
    await migrateNativeDataIfNeeded(kv, async () => ({
      available: true,
      platform: 'ios',
      blobs: { foodEntries: iosFoodBlob() },
      prefs: { hasCompletedOnboarding: true },
    }));
    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_DONE);
    expect(JSON.parse((await kv.get(storageKeys.diary))!).foodEntries[0].name).toBe('Logged after failure');
    expect(JSON.parse((await kv.get(storageKeys.preferences))!).hasCompletedOnboarding).toBe(true);
  });

  it('fills only the empty stores on retry and keeps post-failure preference edits', async () => {
    const kv = memoryKeyValueStore({
      [storageKeys.nativeMigration]: NATIVE_MIGRATION_STARTED,
      [storageKeys.preferences]: JSON.stringify({ hasCompletedOnboarding: true, appearanceMode: 'dark' }),
    });
    await migrateNativeDataIfNeeded(kv, async () => ({
      available: true,
      platform: 'ios',
      blobs: { foodEntries: iosFoodBlob() },
      prefs: { hasCompletedOnboarding: true, appearanceMode: 'light' },
    }));
    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_DONE);
    expect(JSON.parse((await kv.get(storageKeys.preferences))!).appearanceMode).toBe('dark');
    expect(JSON.parse((await kv.get(storageKeys.diary))!).foodEntries[0].name).toBe('Oats');
  });

  it('does not mark done when a store write fails mid-migration', async () => {
    const inner = memoryKeyValueStore();
    const kv = {
      get: (key: string) => inner.get(key),
      remove: (key: string) => inner.remove(key),
      set: async (key: string, value: string) => {
        if (key === storageKeys.preferences) throw new Error('disk full');
        await inner.set(key, value);
      },
    };
    await expect(
      migrateNativeDataIfNeeded(kv, async () => ({
        available: true,
        platform: 'ios',
        blobs: { foodEntries: iosFoodBlob() },
        prefs: { hasCompletedOnboarding: true, appearanceMode: 'light' },
      })),
    ).rejects.toThrow('disk full');
    expect(await inner.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_STARTED);
    expect(JSON.parse((await inner.get(storageKeys.diary))!).foodEntries[0].name).toBe('Oats');
    expect(await inner.get(storageKeys.preferences)).toBeNull();
  });

  it('persists the native food-images directory so photos resolve after restart', async () => {
    const foodImagesDirectory = '/var/mobile/Library/Application Support/fudai-food-images';
    const kv = memoryKeyValueStore();
    await migrateNativeDataIfNeeded(
      kv,
      async () => ({
        available: true,
        platform: 'ios',
        blobs: { foodEntries: iosFoodBlob() },
        prefs: { hasCompletedOnboarding: true },
        foodImagesDirectory,
      }),
      { copyReferencedFoodImages: () => [] },
    );
    expect(await kv.get(storageKeys.nativeFoodImages)).toBe(foodImagesDirectory);
    expect(await kv.get(storageKeys.nativeFoodImagesCopied)).toBe('1');
    expect(await kv.get(storageKeys.nativeFoodImagesPending)).toBeNull();
    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_DONE);

    await migrateNativeDataIfNeeded(kv, async () => {
      throw new Error('should not re-read native after done');
    });
    expect(await kv.get(storageKeys.nativeFoodImages)).toBe(foodImagesDirectory);
    expect(await kv.get(storageKeys.nativeFoodImagesCopied)).toBe('1');
  });

  it('does not mark done when referenced meal photos fail to copy', async () => {
    const foodImagesDirectory = '/var/mobile/Library/Application Support/fudai-food-images';
    const kv = memoryKeyValueStore();
    await expect(
      migrateNativeDataIfNeeded(
        kv,
        async () => ({
          available: true,
          platform: 'ios',
          blobs: { foodEntries: iosFoodBlob() },
          prefs: { hasCompletedOnboarding: true },
          foodImagesDirectory,
        }),
        { copyReferencedFoodImages: () => ['11111111-1111-1111-1111-111111111111.jpg'] },
      ),
    ).rejects.toThrow(NATIVE_FOOD_IMAGE_COPY_FAILED);
    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_STARTED);
    expect(await kv.get(storageKeys.nativeFoodImagesCopied)).toBeNull();
    expect(JSON.parse((await kv.get(storageKeys.nativeFoodImagesPending))!)).toEqual([
      '11111111-1111-1111-1111-111111111111.jpg',
    ]);
    expect(JSON.parse((await kv.get(storageKeys.diary))!).foodEntries[0].name).toBe('Oats');
  });

  it('retries a failed photo copy on the next launch and then marks done', async () => {
    const foodImagesDirectory = '/var/mobile/Library/Application Support/fudai-food-images';
    const kv = memoryKeyValueStore();
    let remainingFails = 1;
    const snapshot = async (): Promise<NativeStorageSnapshot> => ({
      available: true,
      platform: 'ios',
      blobs: { foodEntries: iosFoodBlob() },
      prefs: { hasCompletedOnboarding: true },
      foodImagesDirectory,
    });
    const hooks = {
      copyReferencedFoodImages: () => {
        if (remainingFails > 0) {
          remainingFails -= 1;
          return ['11111111-1111-1111-1111-111111111111.jpg'];
        }
        return [];
      },
    };
    await expect(migrateNativeDataIfNeeded(kv, snapshot, hooks)).rejects.toThrow(NATIVE_FOOD_IMAGE_COPY_FAILED);
    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_STARTED);

    await migrateNativeDataIfNeeded(kv, snapshot, hooks);
    expect(await kv.get(storageKeys.nativeMigration)).toBe(NATIVE_MIGRATION_DONE);
    expect(await kv.get(storageKeys.nativeFoodImagesCopied)).toBe('1');
    expect(await kv.get(storageKeys.nativeFoodImagesPending)).toBeNull();
  });
});

describe('referencedFoodImageFilenames', () => {
  it('collects current diary filenames and ignores meals the user removed', () => {
    expect(
      referencedFoodImageFilenames(
        {
          foodEntries: [
            { imageFilename: 'keep.jpg', additionalImageFilenames: ['keep-2.jpg'] },
            { imageFilename: 'also.jpg', additionalImageFilenames: [] },
          ],
        },
        { foodEntries: [{ imageFilename: 'native-only.jpg', additionalImageFilenames: [] }] },
      ),
    ).toEqual(['keep.jpg', 'keep-2.jpg', 'also.jpg', 'native-only.jpg']);
    expect(referencedFoodImageFilenames({ foodEntries: [] })).toEqual([]);
  });
});
