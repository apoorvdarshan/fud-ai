/**
 * Map native UserDefaults / DataStore JSON into the RN persistence shapes.
 *
 * iOS `JSONEncoder` writes `Date` as seconds since 2001-01-01. Android
 * `InstantSerializer` writes epoch milliseconds. IDs are UUID strings on both.
 */

import { isBodyFatEntry, isWeightEntry, type BodyFatEntry, type WeightEntry } from '../body/bodyState';
import { isChatMessage, type ChatMessage } from '../coach/coach';
import { favoriteKey } from '../diary/diaryState';
import { clampGoalMinutes, type FastingSession } from '../fasting/fasting';
import { makeFoodEntry, mealTypes, type FoodEntry, type FoodSource, type MealType } from '../food/food';
import type { Preferences, WeightUnit } from '../prefs/preferences';
import { activityLevels, defaultUserProfile, genders, weightGoals, type UserProfile } from '../profile/userProfile';
import type { WaterEntry } from '../water/water';
import {
  defaultWorkoutPreferences,
  rpeScales,
  workoutSplits,
  type DraftExercise,
  type DraftSet,
  type RPEScale,
  type WorkoutDraft,
  type WorkoutPreferences,
  type WorkoutSession,
  type WorkoutSplit,
} from '../workouts/workoutSessions';
import { nativeBlobKeys, NATIVE_MIGRATION_DONE, NATIVE_MIGRATION_STARTED, type NativePlatform, type NativeStorageSnapshot } from './nativeSnapshot';

/** Swift `Date` default Codable: seconds since 2001-01-01 00:00:00 UTC. */
const IOS_REFERENCE_DATE_UNIX_SECONDS = 978_307_200;

const foodSources: readonly FoodSource[] = ['snapFood', 'nutritionLabel', 'barcode', 'textInput', 'manual'];

export const nativePrefAliases: Record<string, keyof Preferences> = {
  userContext: 'aiUserContext',
  maxResponseTokens: 'aiMaxResponseTokens',
  healthConnectEnabled: 'healthKitEnabled',
  aiAnalysisConsentGiven: 'aiConsentGiven',
};

export interface PersistedDiary {
  foodEntries: FoodEntry[];
  waterEntries: WaterEntry[];
  fastingSessions: FastingSession[];
  favoriteKeys: string[];
}

export interface PersistedBody {
  weightEntries: WeightEntry[];
  bodyFatEntries: BodyFatEntry[];
}

export interface PersistedWorkouts {
  sessions: WorkoutSession[];
  drafts: Record<string, WorkoutDraft>;
  preferences: WorkoutPreferences;
}

export interface MappedNativeStores {
  diary?: PersistedDiary;
  preferences?: Partial<Preferences>;
  profile?: UserProfile;
  body?: PersistedBody;
  workouts?: PersistedWorkouts;
  chat?: ChatMessage[];
}

export interface RnExistingState {
  diary?: unknown;
  preferences?: unknown;
  profile?: unknown;
  body?: unknown;
  workouts?: unknown;
  chat?: unknown;
}

export type NativeMigrationSkipReason = 'already-migrated' | 'rn-populated' | 'native-unavailable';

export type NativeMigrationPlan =
  | { action: 'skip'; reason: NativeMigrationSkipReason }
  | { action: 'mark-empty' }
  | { action: 'migrate'; mapped: MappedNativeStores };

export const nativeMigrationStoreKeys = ['diary', 'preferences', 'profile', 'body', 'workouts', 'chat'] as const;
export type NativeMigrationStoreKey = (typeof nativeMigrationStoreKeys)[number];

export function planNativeMigration(input: {
  marker: string | null | undefined;
  snapshot: NativeStorageSnapshot | undefined;
  existing: RnExistingState;
}): NativeMigrationPlan {
  if (input.marker === NATIVE_MIGRATION_DONE) return { action: 'skip', reason: 'already-migrated' };
  // A crash after partial writes leaves RN keys but `started` — resume empty stores only.
  if (input.marker !== NATIVE_MIGRATION_STARTED && rnLooksPopulated(input.existing)) {
    return { action: 'skip', reason: 'rn-populated' };
  }
  if (!input.snapshot?.available) return { action: 'skip', reason: 'native-unavailable' };
  if (!nativeHasUserData(input.snapshot)) return { action: 'mark-empty' };
  return { action: 'migrate', mapped: mapNativeSnapshot(input.snapshot) };
}

/** First run writes every mapped store. A `started` retry fills only stores that are still empty. */
export function storesToWrite(
  marker: string | null | undefined,
  existing: RnExistingState,
  mapped: MappedNativeStores,
): NativeMigrationStoreKey[] {
  const available = nativeMigrationStoreKeys.filter((key) => mapped[key] != null);
  if (marker !== NATIVE_MIGRATION_STARTED) return available;
  return available.filter((key) => !rnStoreHasData(key, existing[key]));
}

export function rnStoreHasData(key: NativeMigrationStoreKey, value: unknown): boolean {
  switch (key) {
    case 'diary':
      return hasDiaryEntries(value);
    case 'preferences':
      return isRecord(value) && Object.keys(value).length > 0;
    case 'profile':
      return isRecord(value) && typeof value.gender === 'string';
    case 'body':
      return hasNamedArray(value, 'weightEntries') || hasNamedArray(value, 'bodyFatEntries');
    case 'workouts':
      return (
        hasNamedArray(value, 'sessions') ||
        (isRecord(value) && isRecord(value.drafts) && Object.keys(value.drafts).length > 0)
      );
    case 'chat':
      return Array.isArray(value) && value.length > 0;
  }
}

/** Filenames still referenced by RN / about-to-write diary rows — never copy deleted meals. */
export function referencedFoodImageFilenames(...diaries: unknown[]): string[] {
  const names = new Set<string>();
  for (const diary of diaries) {
    if (!isRecord(diary) || !Array.isArray(diary.foodEntries)) continue;
    for (const entry of diary.foodEntries) {
      if (!isRecord(entry)) continue;
      if (typeof entry.imageFilename === 'string' && entry.imageFilename.trim()) {
        names.add(entry.imageFilename);
      }
      if (!Array.isArray(entry.additionalImageFilenames)) continue;
      for (const name of entry.additionalImageFilenames) {
        if (typeof name === 'string' && name.trim()) names.add(name);
      }
    }
  }
  return [...names];
}

export function rnLooksPopulated(existing: RnExistingState): boolean {
  if (hasDiaryEntries(existing.diary)) return true;
  if (isRecord(existing.preferences) && existing.preferences.hasCompletedOnboarding === true) return true;
  if (isRecord(existing.profile) && typeof existing.profile.gender === 'string') return true;
  if (hasNamedArray(existing.body, 'weightEntries') || hasNamedArray(existing.body, 'bodyFatEntries')) return true;
  if (hasNamedArray(existing.workouts, 'sessions')) return true;
  if (Array.isArray(existing.chat) && existing.chat.length > 0) return true;
  return false;
}

export function nativeHasUserData(snapshot: NativeStorageSnapshot): boolean {
  if (snapshot.prefs.hasCompletedOnboarding === true) return true;
  const blobs = snapshot.blobs;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.foodEntries])) return true;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.favoriteFoodEntries])) return true;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.waterEntries])) return true;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.fastingSessions])) return true;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.weightEntries])) return true;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.bodyFatEntries])) return true;
  if (nonEmptyJsonArray(blobs[nativeBlobKeys.chat])) return true;
  if (blobs[nativeBlobKeys.userProfile] && parseObject(blobs[nativeBlobKeys.userProfile])) return true;
  const workouts = parseObject(workoutsBlob(snapshot));
  if (workouts && nonEmptyArray(workouts.completedSessions)) return true;
  if (workouts && isRecord(workouts.dayPlans) && Object.keys(workouts.dayPlans).length > 0) return true;
  return false;
}

export function mapNativeSnapshot(snapshot: NativeStorageSnapshot): MappedNativeStores {
  const platform = snapshot.platform;
  const mapped: MappedNativeStores = {};

  const foodEntries = mapFoodEntries(snapshot.blobs[nativeBlobKeys.foodEntries], platform);
  const favoriteEntries = mapFoodEntries(snapshot.blobs[nativeBlobKeys.favoriteFoodEntries], platform);
  const waterEntries = mapWaterEntries(snapshot.blobs[nativeBlobKeys.waterEntries], platform);
  const fastingSessions = mapFastingSessions(snapshot.blobs[nativeBlobKeys.fastingSessions], platform);
  const favoriteKeys = mapFavoriteKeys(snapshot.blobs[nativeBlobKeys.favorites], favoriteEntries);

  if (foodEntries || waterEntries || fastingSessions || favoriteKeys.length > 0) {
    mapped.diary = {
      foodEntries: foodEntries ?? [],
      waterEntries: waterEntries ?? [],
      fastingSessions: fastingSessions ?? [],
      favoriteKeys,
    };
  }

  const preferences = mapPreferences(snapshot.prefs, snapshot.blobs);
  if (Object.keys(preferences).length > 0) mapped.preferences = preferences;

  const profile = mapProfile(snapshot.blobs[nativeBlobKeys.userProfile], platform);
  if (profile) mapped.profile = profile;

  const weightEntries = mapWeightEntries(snapshot.blobs[nativeBlobKeys.weightEntries], platform);
  const bodyFatEntries = mapBodyFatEntries(snapshot.blobs[nativeBlobKeys.bodyFatEntries], platform);
  if (weightEntries || bodyFatEntries) {
    mapped.body = {
      weightEntries: weightEntries ?? [],
      bodyFatEntries: bodyFatEntries ?? [],
    };
  }

  const workouts = mapWorkouts(workoutsBlob(snapshot), platform, preferences.weightUnit);
  if (workouts) mapped.workouts = workouts;

  const chat = mapChat(snapshot.blobs[nativeBlobKeys.chat], platform);
  if (chat) mapped.chat = chat;

  return mapped;
}

export function nativeDateToIso(value: unknown, platform: NativePlatform): string | undefined {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return undefined;
    if (!trimmed.includes('T') && !trimmed.includes('-') && !trimmed.includes(':')) {
      const asNumber = Number(trimmed);
      if (Number.isFinite(asNumber)) return nativeDateToIso(asNumber, platform);
    }
    const parsed = new Date(trimmed);
    return Number.isFinite(parsed.getTime()) ? parsed.toISOString() : undefined;
  }
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;

  if (platform === 'android') {
    // InstantSerializer always writes epoch milliseconds, including birthdays near
    // 1970 (e.g. 1.4e10). Those must not be treated as seconds or the year shifts.
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? date.toISOString() : undefined;
  }

  if (value > 1e12) {
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? date.toISOString() : undefined;
  }
  if (value > 1e10) {
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? date.toISOString() : undefined;
  }
  const date = new Date((value + IOS_REFERENCE_DATE_UNIX_SECONDS) * 1000);
  return Number.isFinite(date.getTime()) ? date.toISOString() : undefined;
}

function workoutsBlob(snapshot: NativeStorageSnapshot): string | undefined {
  return snapshot.blobs[nativeBlobKeys.workoutsIos] ?? snapshot.blobs[nativeBlobKeys.workoutsAndroid];
}

function mapFoodEntries(raw: string | undefined, platform: NativePlatform): FoodEntry[] | undefined {
  const rows = parseJsonArray(raw);
  if (!rows) return undefined;
  const entries: FoodEntry[] = [];
  for (const row of rows) {
    const entry = mapFoodEntry(row, platform);
    if (entry) entries.push(entry);
  }
  return entries;
}

function mapFoodEntry(value: unknown, platform: NativePlatform): FoodEntry | undefined {
  if (!isRecord(value)) return undefined;
  const id = asId(value.id);
  const name = typeof value.name === 'string' ? value.name : undefined;
  const calories = asFiniteNumber(value.calories);
  const protein = asFiniteNumber(value.protein);
  const carbs = asFiniteNumber(value.carbs);
  const fat = asFiniteNumber(value.fat);
  const timestamp = nativeDateToIso(value.timestamp, platform);
  if (!id || !name || calories === undefined || protein === undefined || carbs === undefined || fat === undefined || !timestamp) {
    return undefined;
  }
  const source = asFoodSource(value.source) ?? 'manual';
  const extras: Record<string, unknown> = {};
  for (const key of [
    'sugar',
    'addedSugar',
    'fiber',
    'saturatedFat',
    'monounsaturatedFat',
    'polyunsaturatedFat',
    'cholesterol',
    'caffeine',
    'sodium',
    'potassium',
    'transFat',
    'calcium',
    'iron',
    'magnesium',
    'zinc',
    'vitaminA',
    'vitaminC',
    'vitaminD',
    'vitaminB12',
    'vitaminE',
    'vitaminK',
    'folate',
    'omega3',
    'servingSizeGrams',
    'selectedServingUnit',
    'selectedServingQuantity',
    'customNote',
    'emoji',
    'imageFilename',
  ] as const) {
    if (value[key] !== undefined) extras[key] = value[key];
  }
  return makeFoodEntry(
    {
      name,
      calories: Math.round(calories),
      protein,
      carbs,
      fat,
      source,
      timestamp,
      mealType: asMealType(value.mealType),
      additionalImageFilenames: asStringArray(value.additionalImageFilenames),
      servingUnitOptions: Array.isArray(value.servingUnitOptions) ? (value.servingUnitOptions as FoodEntry['servingUnitOptions']) : [],
      supplementalNutrients: isRecord(value.supplementalNutrients)
        ? Object.fromEntries(
            Object.entries(value.supplementalNutrients).filter((entry): entry is [string, number] => typeof entry[1] === 'number'),
          )
        : {},
      progressiveMeal: value.progressiveMeal === true,
      ingredients: Array.isArray(value.ingredients) ? (value.ingredients as FoodEntry['ingredients']) : [],
      ...extras,
    },
    id,
    new Date(timestamp),
  );
}

function mapWaterEntries(raw: string | undefined, platform: NativePlatform): WaterEntry[] | undefined {
  const rows = parseJsonArray(raw);
  if (!rows) return undefined;
  const entries: WaterEntry[] = [];
  for (const row of rows) {
    if (!isRecord(row)) continue;
    const id = asId(row.id);
    const date = nativeDateToIso(row.date, platform);
    const milliliters = asFiniteNumber(row.milliliters);
    if (!id || !date || milliliters === undefined || milliliters <= 0) continue;
    entries.push({ id, date, milliliters: Math.round(milliliters) });
  }
  return entries;
}

function mapFastingSessions(raw: string | undefined, platform: NativePlatform): FastingSession[] | undefined {
  const rows = parseJsonArray(raw);
  if (!rows) return undefined;
  const sessions: FastingSession[] = [];
  for (const row of rows) {
    if (!isRecord(row)) continue;
    const id = asId(row.id);
    const startedAt = nativeDateToIso(row.startedAt, platform);
    const goalMinutes = asFiniteNumber(row.goalMinutes);
    if (!id || !startedAt || goalMinutes === undefined) continue;
    const endedAt = row.endedAt == null ? undefined : nativeDateToIso(row.endedAt, platform);
    sessions.push({
      id,
      startedAt,
      ...(endedAt ? { endedAt } : {}),
      goalMinutes: clampGoalMinutes(Math.round(goalMinutes)),
    });
  }
  return sessions;
}

function mapFavoriteKeys(raw: string | undefined, favoriteEntries: FoodEntry[] | undefined): string[] {
  const fromEntries = (favoriteEntries ?? []).map((entry) => favoriteKey(entry));
  const parsed = parseJsonUnknown(raw);
  const fromKeys: string[] = [];
  if (Array.isArray(parsed)) {
    for (const item of parsed) {
      if (typeof item === 'string' && item.trim()) fromKeys.push(favoriteKeyFromNative(item));
    }
  } else if (isRecord(parsed)) {
    for (const [key, flag] of Object.entries(parsed)) {
      if (flag && key.trim()) fromKeys.push(favoriteKeyFromNative(key));
    }
  }
  return uniqueStrings([...fromKeys, ...fromEntries]);
}

function favoriteKeyFromNative(value: string): string {
  const pipe = value.indexOf('|');
  const name = pipe === -1 ? value : value.slice(0, pipe);
  return favoriteKey({ name });
}

function mapWeightEntries(raw: string | undefined, platform: NativePlatform): WeightEntry[] | undefined {
  const rows = parseJsonArray(raw);
  if (!rows) return undefined;
  return rows.flatMap((row) => {
    if (!isRecord(row)) return [];
    const mapped = {
      id: asId(row.id),
      date: nativeDateToIso(row.date, platform),
      weightKg: asFiniteNumber(row.weightKg),
    };
    return isWeightEntry(mapped) ? [mapped] : [];
  });
}

function mapBodyFatEntries(raw: string | undefined, platform: NativePlatform): BodyFatEntry[] | undefined {
  const rows = parseJsonArray(raw);
  if (!rows) return undefined;
  return rows.flatMap((row) => {
    if (!isRecord(row)) return [];
    const mapped = {
      id: asId(row.id),
      date: nativeDateToIso(row.date, platform),
      bodyFatFraction: asFiniteNumber(row.bodyFatFraction),
    };
    return isBodyFatEntry(mapped) ? [mapped] : [];
  });
}

function mapProfile(raw: string | undefined, platform: NativePlatform): UserProfile | undefined {
  const object = parseObject(raw);
  if (!object) return undefined;
  const birthday = nativeDateToIso(object.birthday, platform) ?? defaultUserProfile.birthday;
  const gender = (genders as readonly string[]).includes(String(object.gender)) ? (object.gender as UserProfile['gender']) : defaultUserProfile.gender;
  const activityLevel = (activityLevels as readonly string[]).includes(String(object.activityLevel))
    ? (object.activityLevel as UserProfile['activityLevel'])
    : defaultUserProfile.activityLevel;
  const goal = (weightGoals as readonly string[]).includes(String(object.goal)) ? (object.goal as UserProfile['goal']) : defaultUserProfile.goal;
  const heightCm = asFiniteNumber(object.heightCm) ?? defaultUserProfile.heightCm;
  const weightKg = asFiniteNumber(object.weightKg) ?? defaultUserProfile.weightKg;
  return {
    ...defaultUserProfile,
    ...(typeof object.name === 'string' ? { name: object.name } : {}),
    gender,
    birthday,
    heightCm,
    weightKg,
    activityLevel,
    goal,
    ...(asFiniteNumber(object.bodyFatPercentage) !== undefined ? { bodyFatPercentage: asFiniteNumber(object.bodyFatPercentage) } : {}),
    ...(asFiniteNumber(object.goalBodyFatPercentage) !== undefined ? { goalBodyFatPercentage: asFiniteNumber(object.goalBodyFatPercentage) } : {}),
    ...(asFiniteNumber(object.weeklyChangeKg) !== undefined ? { weeklyChangeKg: asFiniteNumber(object.weeklyChangeKg) } : {}),
    ...(asFiniteNumber(object.goalWeightKg) !== undefined ? { goalWeightKg: asFiniteNumber(object.goalWeightKg) } : {}),
    ...(asFiniteNumber(object.customCalories) !== undefined ? { customCalories: Math.round(asFiniteNumber(object.customCalories)!) } : {}),
    ...(asFiniteNumber(object.customProtein) !== undefined ? { customProtein: Math.round(asFiniteNumber(object.customProtein)!) } : {}),
    ...(asFiniteNumber(object.customFat) !== undefined ? { customFat: Math.round(asFiniteNumber(object.customFat)!) } : {}),
    ...(asFiniteNumber(object.customCarbs) !== undefined ? { customCarbs: Math.round(asFiniteNumber(object.customCarbs)!) } : {}),
    ...(Array.isArray(object.allergenSensitivities) ? { allergenSensitivities: object.allergenSensitivities.filter((item): item is string => typeof item === 'string') } : {}),
  };
}

function mapPreferences(prefs: NativeStorageSnapshot['prefs'], blobs: Record<string, string>): Partial<Preferences> {
  const patch: Partial<Preferences> = {};
  const merged = { ...prefs };
  if (blobs.adaptiveGoalsPreviousTargets && merged.adaptiveGoalsPreviousTargets === undefined) {
    merged.adaptiveGoalsPreviousTargets = blobs.adaptiveGoalsPreviousTargets;
  }
  for (const [rawKey, value] of Object.entries(merged)) {
    const key = (nativePrefAliases[rawKey] ?? rawKey) as keyof Preferences;
    assignPreference(patch, key, value);
  }
  return patch;
}

function assignPreference(patch: Partial<Preferences>, key: keyof Preferences, value: unknown): void {
  switch (key) {
    case 'hasCompletedOnboarding':
    case 'weekStartsOnMonday':
    case 'waterTrackingEnabled':
    case 'fastingTrackingEnabled':
    case 'notificationsEnabled':
    case 'breakfastReminderEnabled':
    case 'lunchReminderEnabled':
    case 'dinnerReminderEnabled':
    case 'healthKitEnabled':
    case 'adaptiveGoalsEnabled':
    case 'onboardingPlanEdited':
    case 'aiConsentGiven':
    case 'acceptedTermsAndPrivacy':
    case 'separateTextProviderEnabled':
    case 'aiFallbackEnabled':
      if (typeof value === 'boolean') patch[key] = value;
      break;
    case 'appearanceMode':
      if (value === 'system' || value === 'light' || value === 'dark') patch.appearanceMode = value;
      break;
    case 'heightUnit':
      if (value === 'cm' || value === 'ftin') patch.heightUnit = value;
      break;
    case 'weightUnit':
      if (value === 'kg' || value === 'lbs') patch.weightUnit = value;
      break;
    case 'waterUnit':
      if (value === 'ml' || value === 'floz') patch.waterUnit = value;
      break;
    case 'foodLogSortOrder':
      if (value === 'standard' || value === 'latestMealsFirst') patch.foodLogSortOrder = value;
      break;
    case 'aiAccessMode':
      if (value === 'byok' || value === 'hosted') patch.aiAccessMode = value;
      break;
    case 'waterDailyGoalMl':
    case 'fastingDefaultGoalMinutes':
    case 'breakfastReminderHour':
    case 'breakfastReminderMinute':
    case 'lunchReminderHour':
    case 'lunchReminderMinute':
    case 'dinnerReminderHour':
    case 'dinnerReminderMinute':
    case 'aiMaxResponseTokens':
    case 'aiRequestTimeoutSeconds': {
      const number = asFiniteNumber(value);
      if (number !== undefined) (patch as Record<string, number>)[key] = Math.round(number);
      break;
    }
    case 'appThemeColor':
    case 'homeTopNutrients':
    case 'adaptiveGoalsPreviousTargets':
    case 'adaptiveGoalsLastCheckDay':
    case 'selectedSpeechProvider':
    case 'selectedAIProvider':
    case 'selectedAIModel':
    case 'selectedTextAIProvider':
    case 'selectedTextAIModel':
    case 'aiUserContext':
    case 'selectedFallbackAIProvider':
    case 'selectedFallbackAIModel':
      if (typeof value === 'string') (patch as Record<string, string>)[key] = value;
      break;
    default:
      break;
  }
}

function mapChat(raw: string | undefined, platform: NativePlatform): ChatMessage[] | undefined {
  const rows = parseJsonArray(raw);
  if (!rows) return undefined;
  const messages: ChatMessage[] = [];
  for (const row of rows) {
    if (!isRecord(row)) continue;
    const id = asId(row.id);
    const role = row.role === 'user' || row.role === 'assistant' ? row.role : undefined;
    const content = typeof row.content === 'string' ? row.content : undefined;
    const timestamp = nativeDateToIso(row.timestamp, platform);
    if (!id || !role || content === undefined || !timestamp) continue;
    const attachment = typeof row.attachmentImageBase64 === 'string'
      ? row.attachmentImageBase64
      : typeof row.attachmentImageData === 'string'
        ? row.attachmentImageData
        : undefined;
    const message: ChatMessage = {
      id,
      role,
      content,
      timestamp,
      ...(attachment ? { attachmentImageBase64: attachment } : {}),
    };
    if (isChatMessage(message)) messages.push(message);
  }
  return messages;
}

function mapWorkouts(raw: string | undefined, platform: NativePlatform, weightUnit?: WeightUnit): PersistedWorkouts | undefined {
  const object = parseObject(raw);
  if (!object) return undefined;
  const sessions = Array.isArray(object.completedSessions)
    ? object.completedSessions.flatMap((row) => {
        const session = mapWorkoutSession(row, platform);
        return session ? [session] : [];
      })
    : [];
  const drafts = mapWorkoutDrafts(object.dayPlans, platform);
  const preferences = mapWorkoutPreferences(object.preferences, weightUnit);
  if (sessions.length === 0 && Object.keys(drafts).length === 0 && preferences === defaultWorkoutPreferences) {
    return undefined;
  }
  return { sessions, drafts, preferences };
}

function mapWorkoutSession(value: unknown, platform: NativePlatform): WorkoutSession | undefined {
  if (!isRecord(value)) return undefined;
  const id = asId(value.id);
  const startedAt = nativeDateToIso(value.startedAt, platform);
  const completedAt = nativeDateToIso(value.completedAt, platform);
  if (!id || !startedAt || !completedAt) return undefined;
  const diaryDateKey = typeof value.diaryDateKey === 'string' && value.diaryDateKey
    ? value.diaryDateKey
    : isoDayKey(nativeDateToIso(value.diaryDate, platform) ?? startedAt);
  const diaryDate = nativeDateToIso(value.diaryDate, platform) ?? `${diaryDateKey}T12:00:00.000Z`;
  const exercises = Array.isArray(value.exercises)
    ? value.exercises.flatMap((exercise) => {
        if (!isRecord(exercise)) return [];
        const exerciseId = asId(exercise.id);
        const itemID = typeof exercise.itemID === 'string' ? exercise.itemID : typeof exercise.itemId === 'string' ? exercise.itemId : undefined;
        const name = typeof exercise.name === 'string' ? exercise.name : undefined;
        if (!exerciseId || !itemID || !name) return [];
        const sets = Array.isArray(exercise.sets)
          ? exercise.sets.flatMap((set, index) => {
              if (!isRecord(set)) return [];
              const setId = asId(set.id) ?? `${exerciseId}-set-${index}`;
              return [{
                id: setId,
                setNumber: asFiniteNumber(set.setNumber) ?? index + 1,
                weight: typeof set.weight === 'string' ? set.weight : '',
                weightUnit: asWeightUnit(set.weightUnit) ?? 'lbs',
                reps: typeof set.reps === 'string' ? set.reps : '',
                rpe: typeof set.rpe === 'string' ? set.rpe : '',
                ...(asRpeScale(set.rpeScale) ? { rpeScale: asRpeScale(set.rpeScale) } : {}),
              }];
            })
          : [];
        return [{
          id: exerciseId,
          itemID,
          name,
          targetMuscles: asStringArray(exercise.targetMuscles).length > 0 ? asStringArray(exercise.targetMuscles) : asStringArray(exercise.primaryMuscles),
          equipment: typeof exercise.equipment === 'string' ? exercise.equipment : typeof exercise.rawEquipment === 'string' ? exercise.rawEquipment : '',
          sets,
        }];
      })
    : [];
  return {
    id,
    diaryDate,
    diaryDateKey,
    startedAt,
    completedAt,
    durationSeconds: Math.max(0, Math.round(asFiniteNumber(value.durationSeconds) ?? 0)),
    exercises,
    ...(asFiniteNumber(value.caloriesBurned) !== undefined ? { caloriesBurned: Math.round(asFiniteNumber(value.caloriesBurned)!) } : {}),
  };
}

function mapWorkoutDrafts(value: unknown, platform: NativePlatform): Record<string, WorkoutDraft> {
  if (!isRecord(value)) return {};
  const drafts: Record<string, WorkoutDraft> = {};
  for (const [key, plan] of Object.entries(value)) {
    if (!isRecord(plan)) continue;
    const dayKey = typeof plan.dateKey === 'string' && plan.dateKey ? plan.dateKey : key;
    const exercises = Array.isArray(plan.exercises)
      ? plan.exercises.flatMap((exercise): DraftExercise[] => {
          if (!isRecord(exercise)) return [];
          const id = asId(exercise.id);
          const itemID = typeof exercise.itemID === 'string' ? exercise.itemID : typeof exercise.itemId === 'string' ? exercise.itemId : undefined;
          const name = typeof exercise.name === 'string' ? exercise.name : undefined;
          if (!id || !itemID || !name) return [];
          const sets: DraftSet[] = Array.isArray(exercise.sets)
            ? exercise.sets.flatMap((set, index) => {
                if (!isRecord(set)) return [];
                return [{
                  id: asId(set.id) ?? `${id}-set-${index}`,
                  weight: typeof set.weight === 'string' ? set.weight : '',
                  reps: typeof set.reps === 'string' ? set.reps : '',
                  rpe: typeof set.rpe === 'string' ? set.rpe : '',
                  ...(asWeightUnit(set.weightUnit) ? { weightUnit: asWeightUnit(set.weightUnit) } : {}),
                  ...(asRpeScale(set.rpeScale) ? { rpeScale: asRpeScale(set.rpeScale) } : {}),
                }];
              })
            : [];
          return [{
            id,
            itemID,
            name,
            targetMuscles: asStringArray(exercise.primaryMuscles).length > 0 ? asStringArray(exercise.primaryMuscles) : asStringArray(exercise.targetMuscles),
            equipment: typeof exercise.equipment === 'string' ? exercise.equipment : typeof exercise.rawEquipment === 'string' ? exercise.rawEquipment : '',
            category: typeof exercise.category === 'string' ? exercise.category : '',
            sets,
          }];
        })
      : [];
    if (exercises.length === 0) continue;
    drafts[dayKey] = {
      dayKey,
      startedAt: nativeDateToIso(plan.startedAt, platform) ?? `${dayKey}T12:00:00.000Z`,
      exercises,
    };
  }
  return drafts;
}

function mapWorkoutPreferences(value: unknown, fallbackUnit?: WeightUnit): WorkoutPreferences {
  if (!isRecord(value)) return { ...defaultWorkoutPreferences, ...(fallbackUnit ? { weightUnit: fallbackUnit } : {}) };
  return {
    split: asWorkoutSplit(value.split) ?? defaultWorkoutPreferences.split,
    rpeScale: asRpeScale(value.rpeScale) ?? defaultWorkoutPreferences.rpeScale,
    weightUnit: asWeightUnit(value.weightUnit) ?? fallbackUnit ?? defaultWorkoutPreferences.weightUnit,
  };
}

function asWorkoutSplit(value: unknown): WorkoutSplit | undefined {
  if (typeof value !== 'string') return undefined;
  const aliases: Record<string, WorkoutSplit> = {
    fullBody: 'fullBody',
    FULL_BODY: 'fullBody',
    upperLower: 'upperLower',
    UPPER_LOWER: 'upperLower',
    pushPullLegs: 'pushPullLegs',
    PUSH_PULL_LEGS: 'pushPullLegs',
    broSplit: 'broSplit',
    BODY_PART: 'broSplit',
    custom: 'custom',
    CUSTOM: 'custom',
  };
  const mapped = aliases[value];
  if (mapped) return mapped;
  return (workoutSplits as readonly string[]).includes(value) ? (value as WorkoutSplit) : undefined;
}

function asRpeScale(value: unknown): RPEScale | undefined {
  if (typeof value !== 'string') return undefined;
  const aliases: Record<string, RPEScale> = {
    strength: 'strength',
    STRENGTH: 'strength',
    cr10: 'cr10',
    CR10: 'cr10',
    borg: 'borg',
    BORG: 'borg',
  };
  const mapped = aliases[value];
  if (mapped) return mapped;
  return (rpeScales as readonly string[]).includes(value) ? (value as RPEScale) : undefined;
}

function asWeightUnit(value: unknown): WeightUnit | undefined {
  if (value === 'kg' || value === 'KG') return 'kg';
  if (value === 'lbs' || value === 'LBS') return 'lbs';
  return undefined;
}

function asFoodSource(value: unknown): FoodSource | undefined {
  return typeof value === 'string' && (foodSources as readonly string[]).includes(value) ? (value as FoodSource) : undefined;
}

function asMealType(value: unknown): MealType | undefined {
  return typeof value === 'string' && (mealTypes as readonly string[]).includes(value) ? (value as MealType) : undefined;
}

function asId(value: unknown): string | undefined {
  if (typeof value === 'string' && value.trim()) return value;
  if (isRecord(value) && typeof value.uuid === 'string') return value.uuid;
  return undefined;
}

function asFiniteNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function parseJsonArray(raw: string | undefined): unknown[] | undefined {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function parseJsonUnknown(raw: string | undefined): unknown {
  if (!raw) return undefined;
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return undefined;
  }
}

function parseObject(raw: string | undefined): Record<string, unknown> | undefined {
  const parsed = parseJsonUnknown(raw);
  return isRecord(parsed) ? parsed : undefined;
}

function nonEmptyJsonArray(raw: string | undefined): boolean {
  const rows = parseJsonArray(raw);
  return !!rows && rows.length > 0;
}

function nonEmptyArray(value: unknown): boolean {
  return Array.isArray(value) && value.length > 0;
}

function hasDiaryEntries(value: unknown): boolean {
  if (!isRecord(value)) return false;
  return (
    nonEmptyArray(value.foodEntries) ||
    nonEmptyArray(value.waterEntries) ||
    nonEmptyArray(value.fastingSessions) ||
    nonEmptyArray(value.favoriteKeys)
  );
}

function hasNamedArray(value: unknown, key: string): boolean {
  return isRecord(value) && nonEmptyArray(value[key]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values.filter(Boolean))];
}

function isoDayKey(iso: string): string {
  return iso.slice(0, 10);
}
