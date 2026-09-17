/**
 * Snapshot returned by the `NativeStorage` Expo module. JSON blobs stay strings so the
 * TypeScript mapper can leniently recover partial / corrupt collections without native
 * decode wiping anything.
 */

export type NativePlatform = 'ios' | 'android';

export interface NativeStorageSnapshot {
  available: boolean;
  platform: NativePlatform;
  /** UserDefaults / DataStore JSON (or JSON-looking) values, keyed by the native name. */
  blobs: Record<string, string>;
  /** Bool / number / string prefs. Missing keys are omitted — never coerced to false/0. */
  prefs: Record<string, boolean | number | string>;
  /** Absolute path of native `FoodImageStore` (`fudai-food-images`). */
  foodImagesDirectory?: string | null;
}

export const NATIVE_MIGRATION_STORAGE_KEY = 'fudai.nativeMigration.v1';
export const NATIVE_MIGRATION_DONE = 'done';
/** Written before store copies so a crash mid-migration can resume instead of skipping. */
export const NATIVE_MIGRATION_STARTED = 'started';
/** Absolute native `fudai-food-images` directory so meal photos resolve after a cold start. */
export const NATIVE_FOOD_IMAGES_STORAGE_KEY = 'fudai.nativeFoodImages.v1';
/** Set after every referenced meal JPEG is in Expo Documents. */
export const NATIVE_FOOD_IMAGES_COPIED_KEY = 'fudai.nativeFoodImages.copied.v1';
/** Referenced filenames that still need a Documents copy — retried on the next launch. */
export const NATIVE_FOOD_IMAGES_PENDING_KEY = 'fudai.nativeFoodImages.pending.v1';

export const nativeBlobKeys = {
  foodEntries: 'foodEntries',
  favoriteFoodEntries: 'favoriteFoodEntries',
  favorites: 'favorites',
  waterEntries: 'waterEntries',
  fastingSessions: 'fastingSessions',
  weightEntries: 'weightEntries',
  bodyFatEntries: 'bodyFatEntries',
  userProfile: 'userProfile',
  chat: 'coachChatHistory',
  workoutsIos: 'fudai.workouts.diary.state.v1',
  workoutsAndroid: 'workoutDiaryStateV1',
} as const;
