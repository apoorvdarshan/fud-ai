/**
 * `NativeStorage` Expo module — reads the store app's UserDefaults / DataStore.
 * Expo Go and Node tests have no native binary, so this is a quiet no-op.
 */

import type { NativeStorageSnapshot } from '../domain/nativeMigration/nativeSnapshot';

interface NativeStorageModule {
  isAvailable?: boolean;
  readSnapshot(): Promise<NativeStorageSnapshot>;
  copyFoodImages?(destination: string): Promise<number>;
}

function loadNative(): NativeStorageModule | undefined {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { requireNativeModule } = require('expo-modules-core') as typeof import('expo-modules-core');
    return requireNativeModule<NativeStorageModule>('NativeStorage');
  } catch {
    return undefined;
  }
}

export async function readNativeSnapshot(): Promise<NativeStorageSnapshot | undefined> {
  const native = loadNative();
  if (!native) return undefined;
  try {
    const snapshot = await native.readSnapshot();
    if (!snapshot || typeof snapshot !== 'object') return undefined;
    return {
      available: snapshot.available !== false,
      platform: snapshot.platform === 'android' ? 'android' : 'ios',
      blobs: isStringRecord(snapshot.blobs) ? snapshot.blobs : {},
      prefs: isPrefRecord(snapshot.prefs) ? snapshot.prefs : {},
      foodImagesDirectory: typeof snapshot.foodImagesDirectory === 'string' ? snapshot.foodImagesDirectory : null,
    };
  } catch {
    return undefined;
  }
}

/** Copy native `fudai-food-images` into Expo Documents. Undefined when the module is missing. */
export async function copyNativeFoodImages(destination: string): Promise<number | undefined> {
  const native = loadNative();
  if (!native?.copyFoodImages) return undefined;
  try {
    return await native.copyFoodImages(destination);
  } catch {
    return undefined;
  }
}

function isStringRecord(value: unknown): value is Record<string, string> {
  if (!value || typeof value !== 'object') return false;
  return Object.values(value).every((item) => typeof item === 'string');
}

function isPrefRecord(value: unknown): value is NativeStorageSnapshot['prefs'] {
  if (!value || typeof value !== 'object') return false;
  return Object.values(value).every((item) => typeof item === 'boolean' || typeof item === 'number' || typeof item === 'string');
}
