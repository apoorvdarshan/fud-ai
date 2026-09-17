/**
 * Disk-backed store for `FoodEntry` photos — the shared-app `FoodImageStore.swift`. Images
 * live as JPEGs under `<documents>/fudai-food-images/<entry id>.jpg` and the entry persists
 * only `imageFilename`, so the diary JSON in AsyncStorage stays a few hundred bytes per entry
 * however many meals are photographed. Every operation is best-effort: a refused write means
 * the entry is saved without a photo rather than not at all.
 */

import { Directory, File, Paths } from 'expo-file-system';

const FOLDER_NAME = 'fudai-food-images';

/** Native iOS writes photos under Application Support; Expo uses Documents. */
let nativeFoodImagesDirectory: string | undefined;
/** Backups include the native dir until referenced copies succeed. */
let includeAdoptedNativeInListing = false;

function folder(): Directory {
  const directory = new Directory(Paths.document, FOLDER_NAME);
  if (!directory.exists) directory.create({ intermediates: true, idempotent: true });
  return directory;
}

function toFileUri(path: string): string {
  return path.startsWith('file://') ? path : `file://${path}`;
}

function isSafeImageName(name: string): boolean {
  if (![...name].every((ch) => /[A-Za-z0-9._-]/.test(ch))) return false;
  const ext = (name.split('.').pop() ?? '').toLowerCase();
  return ['jpg', 'jpeg', 'png', 'webp'].includes(ext);
}

/**
 * Keep native `FoodImageStore` filenames resolving after a store overlay.
 * iOS Application Support and Expo Documents can differ; Android `filesDir` usually matches.
 * Call this on every cold start with the persisted native directory — the in-memory
 * fallback alone does not survive process death.
 */
export function adoptNativeFoodImages(directoryPath: string): void {
  if (!directoryPath.trim()) return;
  nativeFoodImagesDirectory = toFileUri(directoryPath);
  includeAdoptedNativeInListing = true;
}

/** After referenced copies land in Documents, backups no longer enumerate the native dir. */
export function includeAdoptedNativeFoodImagesInListing(include: boolean): void {
  includeAdoptedNativeInListing = include;
}

/** Expo Documents `fudai-food-images` path (`file://…`) for native copy destinations. */
export function documentsFoodImagesPath(): string {
  return folder().uri;
}

/**
 * Copy native meal JPEGs into Expo Documents so `foodImageURI` finds them after restart
 * without relying on the in-memory adopt path. Same filenames — diary rows stay valid.
 * Pass `onlyFilenames` so a retry cannot restore photos the user already deleted.
 */
export function copyNativeFoodImagesIntoDocuments(sourcePath: string, onlyFilenames?: ReadonlySet<string>): number {
  const names = onlyFilenames
    ? [...onlyFilenames]
    : listSafeImageNames(sourcePath);
  return names.length - copyReferencedFoodImagesIntoDocuments(sourcePath, names).length;
}

/**
 * Copy referenced meal JPEGs into Expo Documents. Returns filenames that still
 * need a retry — already-present Documents files and natively-missing files succeed.
 */
export function copyReferencedFoodImagesIntoDocuments(sourcePath: string, filenames: readonly string[]): string[] {
  const dest = folder();
  const source = sourcePath.trim() ? new Directory(toFileUri(sourcePath)) : undefined;
  const failed: string[] = [];
  for (const name of filenames) {
    if (!isSafeImageName(name)) continue;
    const target = new File(dest, name);
    if (target.exists) continue;
    try {
      if (!source?.exists) {
        failed.push(name);
        continue;
      }
      const item = new File(source, name);
      if (!item.exists) continue;
      target.write(item.bytesSync());
      if (!target.exists) failed.push(name);
    } catch {
      failed.push(name);
    }
  }
  return failed;
}

function listSafeImageNames(sourcePath: string): string[] {
  if (!sourcePath.trim()) return [];
  const source = new Directory(toFileUri(sourcePath));
  if (!source.exists) return [];
  return source.list().flatMap((item) => (item instanceof File && isSafeImageName(item.name) ? [item.name] : []));
}

function foodImageFile(filename: string): File {
  const primary = new File(Paths.document, FOLDER_NAME, filename);
  if (primary.exists || !nativeFoodImagesDirectory) return primary;
  try {
    const fallback = new File(nativeFoodImagesDirectory, filename);
    if (fallback.exists) return fallback;
  } catch {
    /* invalid native path */
  }
  return primary;
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
  return foodImageFile(filename).uri;
}

export function deleteFoodImage(filename: string | undefined): void {
  if (!filename) return;
  try {
    // Documents only — never remove the native original (rollback) or a later
    // one-time copy would be the only way that file returns, which we skip.
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
    collectFoodImages(new Directory(Paths.document, FOLDER_NAME), photos);
    if (includeAdoptedNativeInListing && nativeFoodImagesDirectory) {
      collectFoodImages(new Directory(nativeFoodImagesDirectory), photos);
    }
  } catch (error) {
    console.warn('[fudai] could not list meal photos', error);
  }
  return photos;
}

function collectFoodImages(directory: Directory, photos: Record<string, Uint8Array>): void {
  if (!directory.exists) return;
  for (const item of directory.list()) {
    if (!(item instanceof File) || photos[item.name]) continue;
    if (!isSafeImageName(item.name)) continue;
    photos[item.name] = item.bytesSync();
  }
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
