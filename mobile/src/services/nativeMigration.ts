/**
 * One-time cold-start copy of native UserDefaults / DataStore into RN AsyncStorage.
 *
 * Store Release overlays keep the same bundle id, so existing users would otherwise
 * look like a fresh Expo install (empty diary + onboarding). Native keys are never
 * deleted so a rollback still sees the original blobs.
 */

import { planNativeMigration, type RnExistingState } from '../domain/nativeMigration/mapNativeSnapshot';
import { NATIVE_MIGRATION_DONE, NATIVE_MIGRATION_STORAGE_KEY, type NativeStorageSnapshot } from '../domain/nativeMigration/nativeSnapshot';
import { readJSON, writeJSON, type KeyValueStore } from '../state/persistence';
import { storageKeys } from '../state/storageKeys';
import { readNativeSnapshot } from './nativeStorage';

export { NATIVE_MIGRATION_DONE, NATIVE_MIGRATION_STORAGE_KEY };

export async function migrateNativeDataIfNeeded(
  kv: KeyValueStore,
  readSnapshot: () => Promise<NativeStorageSnapshot | undefined> = readNativeSnapshot,
): Promise<void> {
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

  const { mapped } = plan;
  if (mapped.diary) await writeJSON(kv, storageKeys.diary, mapped.diary);
  if (mapped.preferences) await writeJSON(kv, storageKeys.preferences, mapped.preferences);
  if (mapped.profile) await writeJSON(kv, storageKeys.profile, mapped.profile);
  if (mapped.body) await writeJSON(kv, storageKeys.body, mapped.body);
  if (mapped.workouts) await writeJSON(kv, storageKeys.workouts, mapped.workouts);
  if (mapped.chat) await writeJSON(kv, storageKeys.chat, mapped.chat);

  if (snapshot?.foodImagesDirectory) adoptNativeFoodImagesBestEffort(snapshot.foodImagesDirectory);

  await kv.set(NATIVE_MIGRATION_STORAGE_KEY, NATIVE_MIGRATION_DONE);
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
