/**
 * Disk-backed store for `FoodEntry` photos — the shared-app `FoodImageStore.swift`. Images
 * live as JPEGs under `<documents>/fudai-food-images/<entry id>.jpg` and the entry persists
 * only `imageFilename`, so the diary JSON in AsyncStorage stays a few hundred bytes per entry
 * however many meals are photographed. Every operation is best-effort: a refused write means
 * the entry is saved without a photo rather than not at all.
 */

import { Directory, File, Paths } from 'expo-file-system';

const FOLDER_NAME = 'fudai-food-images';

function folder(): Directory {
  const directory = new Directory(Paths.document, FOLDER_NAME);
  if (!directory.exists) directory.create({ intermediates: true, idempotent: true });
  return directory;
}

/** Writes JPEG bytes (base64) under a filename derived from the entry id; undefined when the disk refused. */
export function storeFoodImage(jpegBase64: string, entryId: string): string | undefined {
  const filename = `${entryId}.jpg`;
  try {
    const file = new File(folder(), filename);
    file.write(jpegBase64, { encoding: 'base64' });
    return filename;
  } catch (error) {
    console.warn('[fudai] could not store the meal photo', error);
    return undefined;
  }
}

/** `file://` URI for an `imageFilename`, for `<Image source={{ uri }}>`. */
export function foodImageURI(filename: string): string {
  return new File(Paths.document, FOLDER_NAME, filename).uri;
}

export function deleteFoodImage(filename: string | undefined): void {
  if (!filename) return;
  try {
    const file = new File(Paths.document, FOLDER_NAME, filename);
    if (file.exists) file.delete();
  } catch (error) {
    console.warn('[fudai] could not delete the meal photo', error);
  }
}

/** `FoodImageStore.deleteAll()` — Clear All Data. */
export function deleteAllFoodImages(): void {
  try {
    const directory = new Directory(Paths.document, FOLDER_NAME);
    if (directory.exists) directory.delete();
  } catch (error) {
    console.warn('[fudai] could not clear the meal photos', error);
  }
}

/** Bytes for every meal photo on disk, keyed by safe filename. */
export function listFoodImages(): Record<string, Uint8Array> {
  const photos: Record<string, Uint8Array> = {};
  try {
    const directory = new Directory(Paths.document, FOLDER_NAME);
    if (!directory.exists) return photos;
    for (const item of directory.list()) {
      if (!(item instanceof File)) continue;
      if (![...item.name].every((ch) => /[A-Za-z0-9._-]/.test(ch))) continue;
      const ext = (item.name.split('.').pop() ?? '').toLowerCase();
      if (!['jpg', 'jpeg', 'png', 'webp'].includes(ext)) continue;
      photos[item.name] = item.bytesSync();
    }
  } catch (error) {
    console.warn('[fudai] could not list meal photos', error);
  }
  return photos;
}

/** Writes restored backup photos under the food-image directory. */
export function restoreFoodImages(photos: Record<string, Uint8Array>): void {
  for (const [name, bytes] of Object.entries(photos)) {
    if (![...name].every((ch) => /[A-Za-z0-9._-]/.test(ch))) continue;
    try {
      const file = new File(folder(), name);
      file.write(bytes);
    } catch (error) {
      console.warn('[fudai] could not restore a meal photo', error);
    }
  }
}
