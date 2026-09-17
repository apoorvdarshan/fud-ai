import { COACH_CHAT_STORAGE_KEY } from '../domain/coach/coach';
import {
  NATIVE_FOOD_IMAGES_COPIED_KEY,
  NATIVE_FOOD_IMAGES_PENDING_KEY,
  NATIVE_FOOD_IMAGES_STORAGE_KEY,
  NATIVE_MIGRATION_STORAGE_KEY,
} from '../domain/nativeMigration/nativeSnapshot';

export const storageKeys = {
  diary: 'fudai.diary.v1',
  preferences: 'fudai.preferences.v1',
  profile: 'fudai.profile.v1',
  body: 'fudai.body.v1',
  workouts: 'fudai.workouts.v1',
  /** Same key as `ChatStore.swift` so the name lines up with the native UserDefaults blob. */
  chat: COACH_CHAT_STORAGE_KEY,
  nativeMigration: NATIVE_MIGRATION_STORAGE_KEY,
  nativeFoodImages: NATIVE_FOOD_IMAGES_STORAGE_KEY,
  nativeFoodImagesCopied: NATIVE_FOOD_IMAGES_COPIED_KEY,
  nativeFoodImagesPending: NATIVE_FOOD_IMAGES_PENDING_KEY,
} as const;
