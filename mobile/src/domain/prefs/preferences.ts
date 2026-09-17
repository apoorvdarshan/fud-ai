/**
 * App preferences. Property names are the iOS `UserDefaults` keys (Android DataStore uses the
 * same names) so backups and docs line up across platforms.
 */

import { AI_MODE_STORAGE_KEY, type AIMode } from '../ai/hosted';
import { aiSettingsKeys } from '../ai/settings';
import { FOOD_LOG_SORT_ORDER_KEY, type FoodLogSortOrder } from '../diary/mealGroups';
import { fastingSettings } from '../fasting/fasting';
import { ADAPTIVE_GOALS_ENABLED_KEY, ADAPTIVE_GOALS_LAST_CHECK_DAY_KEY, ADAPTIVE_GOALS_PREVIOUS_TARGETS_KEY } from '../profile/adaptiveGoals';
import { defaultReminderTimes, reminderPreferenceKeys } from './reminders';
import { DEFAULT_WATER_UNIT, waterSettings, type WaterUnit } from '../water/water';

export type AppearanceMode = 'system' | 'light' | 'dark';

export type HeightUnit = 'cm' | 'ftin';
export type WeightUnit = 'kg' | 'lbs';

/** Nutrients shown as vertical bars under the calorie dome (`HomeTopNutrient`). */
export const homeTopNutrientIds = [
  'protein',
  'carbs',
  'fat',
  'fiber',
  'sugar',
  'addedSugar',
  'saturatedFat',
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
  'creatine',
  'betaAlanine',
  'lCitrulline',
  'lCarnitine',
  'lArginine',
  'taurine',
  'betaine',
  'hmb',
] as const;

export type HomeTopNutrientId = (typeof homeTopNutrientIds)[number];

export const defaultHomeTopNutrients: readonly HomeTopNutrientId[] = ['protein', 'carbs', 'fat', 'fiber'];

export const HOME_TOP_NUTRIENTS_KEY = 'homeTopNutrients';

/** `HomeTopNutrient.selection(from:)` — comma-separated raw values, max 4, de-duplicated. */
export function parseHomeTopNutrients(raw: string | null | undefined): HomeTopNutrientId[] {
  if (!raw) return [...defaultHomeTopNutrients];
  const selection: HomeTopNutrientId[] = [];
  for (const part of raw.split(',')) {
    if ((homeTopNutrientIds as readonly string[]).includes(part) && !selection.includes(part as HomeTopNutrientId)) {
      selection.push(part as HomeTopNutrientId);
      if (selection.length === 4) break;
    }
  }
  return selection.length === 0 ? [...defaultHomeTopNutrients] : selection;
}

export function serializeHomeTopNutrients(nutrients: readonly HomeTopNutrientId[]): string {
  return nutrients.slice(0, 4).join(',');
}

export interface Preferences {
  hasCompletedOnboarding: boolean;
  appearanceMode: AppearanceMode;
  appThemeColor: string;
  heightUnit: HeightUnit;
  weightUnit: WeightUnit;
  weekStartsOnMonday: boolean;
  homeTopNutrients: string;
  foodLogSortOrder: FoodLogSortOrder;

  waterTrackingEnabled: boolean;
  waterDailyGoalMl: number;
  waterUnit: WaterUnit;

  fastingTrackingEnabled: boolean;
  fastingDefaultGoalMinutes: number;

  /** Meal reminders granted during onboarding / Settings → Notifications. */
  notificationsEnabled: boolean;
  breakfastReminderEnabled: boolean;
  breakfastReminderHour: number;
  breakfastReminderMinute: number;
  lunchReminderEnabled: boolean;
  lunchReminderHour: number;
  lunchReminderMinute: number;
  dinnerReminderEnabled: boolean;
  dinnerReminderHour: number;
  dinnerReminderMinute: number;
  /** Apple Health / Health Connect sync. */
  healthKitEnabled: boolean;
  /** Weekly Adaptive Goals. Absent native key defaults to on; Expo matches that. */
  adaptiveGoalsEnabled: boolean;
  adaptiveGoalsPreviousTargets?: string;
  adaptiveGoalsLastCheckDay?: string;
  /** `SpeechProvider.rawValue` — OpenAI Whisper / Groq. On-device Whisper Base is unsupported here. */
  selectedSpeechProvider?: string;
  /** Set when the user hand-tuned the Plan Ready numbers, so Adaptive Goals stays off. */
  onboardingPlanEdited: boolean;

  aiAccessMode: AIMode;
  /** Consent captured on the AI step. */
  aiConsentGiven: boolean;
  acceptedTermsAndPrivacy: boolean;

  selectedAIProvider?: string;
  selectedAIModel?: string;
  separateTextProviderEnabled: boolean;
  selectedTextAIProvider?: string;
  selectedTextAIModel?: string;
  aiUserContext: string;
  aiFallbackEnabled: boolean;
  selectedFallbackAIProvider?: string;
  selectedFallbackAIModel?: string;
  textAIFallbackEnabled: boolean;
  selectedTextFallbackAIProvider?: string;
  selectedTextFallbackAIModel?: string;
  aiMaxResponseTokens?: number;
  aiRequestTimeoutSeconds?: number;

  /** Home + food menu JSON (`AddMenuConfig.storageKey`). */
  addMenuConfig?: string;
  /** Outdoor Walking / Running in the Workouts + menu. */
  walkRunQuickLogEnabled: boolean;
}

export const defaultPreferences: Preferences = {
  hasCompletedOnboarding: false,
  appearanceMode: 'system',
  appThemeColor: 'fudPink',
  heightUnit: 'ftin',
  weightUnit: 'lbs',
  weekStartsOnMonday: true,
  homeTopNutrients: serializeHomeTopNutrients(defaultHomeTopNutrients),
  foodLogSortOrder: 'standard',

  waterTrackingEnabled: false,
  waterDailyGoalMl: waterSettings.defaultDailyGoalMl,
  waterUnit: DEFAULT_WATER_UNIT,

  fastingTrackingEnabled: false,
  fastingDefaultGoalMinutes: fastingSettings.defaultGoalMinutes,

  notificationsEnabled: false,
  breakfastReminderEnabled: defaultReminderTimes.breakfast.enabled,
  breakfastReminderHour: defaultReminderTimes.breakfast.hour,
  breakfastReminderMinute: defaultReminderTimes.breakfast.minute,
  lunchReminderEnabled: defaultReminderTimes.lunch.enabled,
  lunchReminderHour: defaultReminderTimes.lunch.hour,
  lunchReminderMinute: defaultReminderTimes.lunch.minute,
  dinnerReminderEnabled: defaultReminderTimes.dinner.enabled,
  dinnerReminderHour: defaultReminderTimes.dinner.hour,
  dinnerReminderMinute: defaultReminderTimes.dinner.minute,
  healthKitEnabled: false,
  adaptiveGoalsEnabled: true,
  onboardingPlanEdited: false,

  aiAccessMode: 'byok',
  aiConsentGiven: false,
  acceptedTermsAndPrivacy: false,

  separateTextProviderEnabled: false,
  aiUserContext: '',
  aiFallbackEnabled: false,
  textAIFallbackEnabled: false,
  walkRunQuickLogEnabled: false,
};

/**
 * Compile-time check that every preference property is a real native key. The literal keys
 * here are the iOS `UserDefaults` names; adding a preference with a different name fails here.
 */
export const preferenceKeys: { readonly [K in keyof Preferences]: K } = {
  hasCompletedOnboarding: 'hasCompletedOnboarding',
  appearanceMode: 'appearanceMode',
  appThemeColor: 'appThemeColor',
  heightUnit: 'heightUnit',
  weightUnit: 'weightUnit',
  weekStartsOnMonday: 'weekStartsOnMonday',
  homeTopNutrients: HOME_TOP_NUTRIENTS_KEY,
  foodLogSortOrder: FOOD_LOG_SORT_ORDER_KEY,
  waterTrackingEnabled: waterSettings.enabledKey,
  waterDailyGoalMl: waterSettings.dailyGoalKey,
  waterUnit: waterSettings.unitKey,
  fastingTrackingEnabled: fastingSettings.enabledKey,
  fastingDefaultGoalMinutes: fastingSettings.defaultGoalMinutesKey,
  notificationsEnabled: 'notificationsEnabled',
  breakfastReminderEnabled: reminderPreferenceKeys.breakfastEnabled,
  breakfastReminderHour: reminderPreferenceKeys.breakfastHour,
  breakfastReminderMinute: reminderPreferenceKeys.breakfastMinute,
  lunchReminderEnabled: reminderPreferenceKeys.lunchEnabled,
  lunchReminderHour: reminderPreferenceKeys.lunchHour,
  lunchReminderMinute: reminderPreferenceKeys.lunchMinute,
  dinnerReminderEnabled: reminderPreferenceKeys.dinnerEnabled,
  dinnerReminderHour: reminderPreferenceKeys.dinnerHour,
  dinnerReminderMinute: reminderPreferenceKeys.dinnerMinute,
  healthKitEnabled: 'healthKitEnabled',
  adaptiveGoalsEnabled: ADAPTIVE_GOALS_ENABLED_KEY,
  adaptiveGoalsPreviousTargets: ADAPTIVE_GOALS_PREVIOUS_TARGETS_KEY,
  adaptiveGoalsLastCheckDay: ADAPTIVE_GOALS_LAST_CHECK_DAY_KEY,
  selectedSpeechProvider: 'selectedSpeechProvider',
  onboardingPlanEdited: 'onboardingPlanEdited',
  aiAccessMode: AI_MODE_STORAGE_KEY,
  aiConsentGiven: 'aiConsentGiven',
  acceptedTermsAndPrivacy: 'acceptedTermsAndPrivacy',
  selectedAIProvider: aiSettingsKeys.provider,
  selectedAIModel: aiSettingsKeys.model,
  separateTextProviderEnabled: aiSettingsKeys.separateTextProviderEnabled,
  selectedTextAIProvider: aiSettingsKeys.textProvider,
  selectedTextAIModel: aiSettingsKeys.textModel,
  aiUserContext: aiSettingsKeys.userContext,
  aiFallbackEnabled: aiSettingsKeys.fallbackEnabled,
  selectedFallbackAIProvider: aiSettingsKeys.fallbackProvider,
  selectedFallbackAIModel: aiSettingsKeys.fallbackModel,
  textAIFallbackEnabled: aiSettingsKeys.textFallbackEnabled,
  selectedTextFallbackAIProvider: aiSettingsKeys.textFallbackProvider,
  selectedTextFallbackAIModel: aiSettingsKeys.textFallbackModel,
  aiMaxResponseTokens: aiSettingsKeys.maxResponseTokens,
  aiRequestTimeoutSeconds: aiSettingsKeys.requestTimeoutSeconds,
  addMenuConfig: 'addMenuConfig',
  walkRunQuickLogEnabled: 'walkRunQuickLogEnabled',
};

export function mergePreferences(stored: unknown): Preferences {
  if (!stored || typeof stored !== 'object') return { ...defaultPreferences };
  return { ...defaultPreferences, ...(stored as Partial<Preferences>) };
}
