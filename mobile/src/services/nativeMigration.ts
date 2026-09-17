/**
 * One-time cold-start copy of native UserDefaults / DataStore into RN AsyncStorage.
 *
 * Store Release overlays keep the same bundle id, so existing users would otherwise
 * look like a fresh Expo install (empty diary + onboarding). Native keys are never
 * deleted so a rollback still sees the original blobs.
 *
 * `fudai.nativeMigration.v1` is `started` before any store write and `done` only after
 * every mapped store succeeds and referenced meal JPEGs are in Expo Documents. A crash
 * mid-copy leaves `started` so the next launch fills remaining empty keys. Stores that
 * already have RN data — including edits the user saved after the failure — are left
 * alone.
 */

import {
  referencedFoodImageFilenames,
  storesToWrite,
  planNativeMigration,
  type MappedNativeStores,
  type NativeMigrationStoreKey,
  type RnExistingState,
} from '../domain/nativeMigration/mapNativeSnapshot';
import {
  NATIVE_FOOD_IMAGES_COPIED_KEY,
  NATIVE_FOOD_IMAGES_PENDING_KEY,
  NATIVE_FOOD_IMAGES_STORAGE_KEY,
  NATIVE_MIGRATION_DONE,
  NATIVE_MIGRATION_STARTED,
  NATIVE_MIGRATION_STORAGE_KEY,
  type NativeStorageSnapshot,
} from '../domain/nativeMigration/nativeSnapshot';
import { readJSON, writeJSON, type KeyValueStore } from '../state/persistence';
import { storageKeys } from '../state/storageKeys';
import { readNativeSnapshot } from './nativeStorage';

export { NATIVE_MIGRATION_DONE, NATIVE_MIGRATION_STARTED, NATIVE_MIGRATION_STORAGE_KEY };

export const NATIVE_FOOD_IMAGE_COPY_FAILED = 'native meal photo copy failed';

export interface NativeMigrationHooks {
  /** Return filenames that still need a Documents copy. Empty means success. */
  copyReferencedFoodImages?: (sourceDirectory: string, filenames: readonly string[]) => string[] | Promise<string[]>;
}

const storeStorageKeys: Record<NativeMigrationStoreKey, string> = {
  diary: storageKeys.diary,
  preferences: storageKeys.preferences,
  profile: storageKeys.profile,
  body: storageKeys.body,
  workouts: storageKeys.workouts,
  chat: storageKeys.chat,
};

export async function migrateNativeDataIfNeeded(
  kv: KeyValueStore,
  readSnapshot: () => Promise<NativeStorageSnapshot | undefined> = readNativeSnapshot,
  hooks: NativeMigrationHooks = {},
): Promise<void> {
  await restorePersistedFoodImages(kv, hooks);

  const marker = await kv.get(NATIVE_MIGRATION_STORAGE_KEY);
  if (marker === NATIVE_MIGRATION_DONE) return;

  const existing = await readExistingRnState(kv);
  if (!existing) return;

  const snapshot = await readSnapshot();
  const plan = planNativeMigration({ marker, snapshot, existing });

  if (plan.action === 'skip') return;

  if (plan.action === 'mark-empty') {
    await kv.set(NATIVE_MIGRATION_STORAGE_KEY, NATIVE_MIGRATION_DONE);
    return;
  }

  // Persist in-progress before any store write so a crash cannot look like a
  // finished RN install and skip the remaining empty stores forever.
  await kv.set(NATIVE_MIGRATION_STORAGE_KEY, NATIVE_MIGRATION_STARTED);

  const { mapped } = plan;
  const keys = storesToWrite(marker, existing, mapped);
  for (const key of keys) {
    const value = mapped[key];
    if (value !== undefined) await writeJSON(kv, storeStorageKeys[key], value);
  }

  await persistAndCopyFoodImages(kv, snapshot, existing, mapped, keys.includes('diary'), hooks);

  await kv.set(NATIVE_MIGRATION_STORAGE_KEY, NATIVE_MIGRATION_DONE);
}

async function restorePersistedFoodImages(kv: KeyValueStore, hooks: NativeMigrationHooks): Promise<void> {
  const directory = await kv.get(NATIVE_FOOD_IMAGES_STORAGE_KEY);
  if (!directory) return;
  adoptNativeFoodImagesBestEffort(directory);
  if (await kv.get(NATIVE_FOOD_IMAGES_COPIED_KEY)) {
    includeAdoptedNativeInListingBestEffort(false);
    return;
  }
  includeAdoptedNativeInListingBestEffort(true);
  const diary = await readJSON(kv, storageKeys.diary);
  const names = referencedFoodImageFilenames(diary);
  if (names.length === 0) return;
  try {
    await copyReferencedOrThrow(kv, directory, names, hooks);
  } catch {
    /* Retry again during migrate / next launch — keep listing the native dir. */
  }
}

async function persistAndCopyFoodImages(
  kv: KeyValueStore,
  snapshot: NativeStorageSnapshot | undefined,
  existing: RnExistingState,
  mapped: MappedNativeStores,
  wroteDiary: boolean,
  hooks: NativeMigrationHooks,
): Promise<void> {
  const directory = snapshot?.foodImagesDirectory?.trim();
  if (directory) {
    await kv.set(NATIVE_FOOD_IMAGES_STORAGE_KEY, directory);
    adoptNativeFoodImagesBestEffort(directory);
  }
  if (await kv.get(NATIVE_FOOD_IMAGES_COPIED_KEY)) {
    includeAdoptedNativeInListingBestEffort(false);
    return;
  }
  if (!directory) return;
  const names = referencedFoodImageFilenames(existing.diary, wroteDiary ? mapped.diary : undefined);
  await copyReferencedOrThrow(kv, directory, names, hooks);
}

async function copyReferencedOrThrow(
  kv: KeyValueStore,
  directory: string,
  filenames: readonly string[],
  hooks: NativeMigrationHooks,
): Promise<void> {
  if (filenames.length === 0) {
    await kv.remove(NATIVE_FOOD_IMAGES_PENDING_KEY);
    await kv.set(NATIVE_FOOD_IMAGES_COPIED_KEY, '1');
    includeAdoptedNativeInListingBestEffort(false);
    return;
  }
  const failed = await copyReferencedFoodImages(directory, filenames, hooks);
  if (failed.length > 0) {
    await kv.set(NATIVE_FOOD_IMAGES_PENDING_KEY, JSON.stringify(failed));
    includeAdoptedNativeInListingBestEffort(true);
    throw new Error(`${NATIVE_FOOD_IMAGE_COPY_FAILED}: ${failed.join(', ')}`);
  }
  await kv.remove(NATIVE_FOOD_IMAGES_PENDING_KEY);
  await kv.set(NATIVE_FOOD_IMAGES_COPIED_KEY, '1');
  includeAdoptedNativeInListingBestEffort(false);
}

async function copyReferencedFoodImages(
  directory: string,
  filenames: readonly string[],
  hooks: NativeMigrationHooks,
): Promise<string[]> {
  if (hooks.copyReferencedFoodImages) {
    return [...(await hooks.copyReferencedFoodImages(directory, filenames))];
  }
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const foodImages = require('./foodImageStore') as typeof import('./foodImageStore');
    return foodImages.copyReferencedFoodImagesIntoDocuments(directory, filenames);
  } catch {
    return [...filenames];
  }
}

async function readExistingRnState(kv: KeyValueStore): Promise<RnExistingState | undefined> {
  const keys = [storageKeys.diary, storageKeys.preferences, storageKeys.profile, storageKeys.body, storageKeys.workouts, storageKeys.chat] as const;
  const reads = await Promise.all(keys.map((key) => readExistingKey(kv, key)));
  if (reads.some((read) => !read.ok)) return undefined;
  return {
    diary: reads[0]?.value,
    preferences: reads[1]?.value,
    profile: reads[2]?.value,
    body: reads[3]?.value,
    workouts: reads[4]?.value,
    chat: reads[5]?.value,
  };
}

async function readExistingKey(kv: KeyValueStore, key: string): Promise<{ ok: boolean; value?: unknown }> {
  try {
    return { ok: true, value: await readJSON(kv, key) };
  } catch {
    // An unreadable RN blob must not be replaced by a native copy.
    return { ok: false };
  }
}

function adoptNativeFoodImagesBestEffort(directory: string): void {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { adoptNativeFoodImages } = require('./foodImageStore') as typeof import('./foodImageStore');
    adoptNativeFoodImages(directory);
  } catch {
    /* Expo Go / tests / file-system unavailable */
  }
}

function includeAdoptedNativeInListingBestEffort(include: boolean): void {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { includeAdoptedNativeFoodImagesInListing } = require('./foodImageStore') as typeof import('./foodImageStore');
    includeAdoptedNativeFoodImagesInListing(include);
  } catch {
    /* Expo Go / tests / file-system unavailable */
  }
}
