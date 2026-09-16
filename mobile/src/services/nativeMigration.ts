/**
 * One-time cold-start copy of native UserDefaults / DataStore into RN AsyncStorage.
 *
 * Store Release overlays keep the same bundle id, so existing users would otherwise
 * look like a fresh Expo install (empty diary + onboarding). Native keys are never
 * deleted so a rollback still sees the original blobs.
 *
 * `fudai.nativeMigration.v1` is `started` before any store write and `done` only after
 * every mapped store succeeds. A crash mid-copy leaves `started` so the next launch
 * overwrites / completes remaining keys instead of treating the partial RN blob as a
 * real install.
 */

import { planNativeMigration, type RnExistingState } from '../domain/nativeMigration/mapNativeSnapshot';
import {
  NATIVE_FOOD_IMAGES_STORAGE_KEY,
  NATIVE_MIGRATION_DONE,
  NATIVE_MIGRATION_STARTED,
  NATIVE_MIGRATION_STORAGE_KEY,
  type NativeStorageSnapshot,
} from '../domain/nativeMigration/nativeSnapshot';
import { readJSON, writeJSON, type KeyValueStore } from '../state/persistence';
import { storageKeys } from '../state/storageKeys';
import { copyNativeFoodImages, readNativeSnapshot } from './nativeStorage';

export { NATIVE_MIGRATION_DONE, NATIVE_MIGRATION_STARTED, NATIVE_MIGRATION_STORAGE_KEY };

export async function migrateNativeDataIfNeeded(
  kv: KeyValueStore,
  readSnapshot: () => Promise<NativeStorageSnapshot | undefined> = readNativeSnapshot,
): Promise<void> {
  await restorePersistedFoodImages(kv);

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
  // finished RN install and skip the remaining stores forever.
  await kv.set(NATIVE_MIGRATION_STORAGE_KEY, NATIVE_MIGRATION_STARTED);

  const { mapped } = plan;
  if (mapped.diary) await writeJSON(kv, storageKeys.diary, mapped.diary);
  if (mapped.preferences) await writeJSON(kv, storageKeys.preferences, mapped.preferences);
  if (mapped.profile) await writeJSON(kv, storageKeys.profile, mapped.profile);
  if (mapped.body) await writeJSON(kv, storageKeys.body, mapped.body);
  if (mapped.workouts) await writeJSON(kv, storageKeys.workouts, mapped.workouts);
  if (mapped.chat) await writeJSON(kv, storageKeys.chat, mapped.chat);

  await persistAndCopyFoodImages(kv, snapshot);

  await kv.set(NATIVE_MIGRATION_STORAGE_KEY, NATIVE_MIGRATION_DONE);
}

async function restorePersistedFoodImages(kv: KeyValueStore): Promise<void> {
  const directory = await kv.get(NATIVE_FOOD_IMAGES_STORAGE_KEY);
  if (!directory) return;
  adoptNativeFoodImagesBestEffort(directory);
  await copyFoodImagesBestEffort(directory);
}

async function persistAndCopyFoodImages(kv: KeyValueStore, snapshot: NativeStorageSnapshot | undefined): Promise<void> {
  const directory = snapshot?.foodImagesDirectory?.trim();
  if (!directory) return;
  await kv.set(NATIVE_FOOD_IMAGES_STORAGE_KEY, directory);
  adoptNativeFoodImagesBestEffort(directory);
  await copyFoodImagesBestEffort(directory);
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

async function copyFoodImagesBestEffort(sourceDirectory: string): Promise<void> {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const foodImages = require('./foodImageStore') as typeof import('./foodImageStore');
    try {
      const copied = await copyNativeFoodImages(foodImages.documentsFoodImagesPath());
      if (copied !== undefined) return;
    } catch {
      /* fall through to the JS copy */
    }
    foodImages.copyNativeFoodImagesIntoDocuments(sourceDirectory);
  } catch {
    /* Expo Go / tests / file-system unavailable */
  }
}
