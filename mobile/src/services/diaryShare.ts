/**
 * Diary export / import and portable backup files. Share sheet + document picker — no iCloud
 * account or secrets. The archive format matches `CloudBackupArchive.swift`.
 */

import { digest, CryptoDigestAlgorithm } from 'expo-crypto';
import * as DocumentPicker from 'expo-document-picker';
import { cacheDirectory, EncodingType, readAsStringAsync, writeAsStringAsync } from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { Platform } from 'react-native';

import {
  applyDiaryImport,
  applyWaterImport,
  buildBackupDocument,
  buildDiaryExport,
  canonicalBackupContent,
  CloudBackupError,
  cloudBackupPolicy,
  mergePreferences,
  packZip,
  parseBackupDocument,
  parseBackupJsonValue,
  parseDiaryImport,
  diaryImportHealthReconcile,
  validateBackupDiary,
  validateBackupPreferences,
  validateBackupProfile,
  type CloudBackupDocument,
  type CloudBackupValue,
  type DiaryExportFormat,
  type DiaryImportMode,
  type DiaryImportPreview,
  unpackBackupArchive,
} from '../domain';
import type { FoodEntry } from '../domain/food/food';
import { dailyTargets, defaultUserProfile } from '../domain/profile/userProfile';
import { diaryStore, newId, preferencesStore, profileStore, setPreferences } from '../state/appStores';
import { listFoodImages, restoreFoodImages } from './foodImageStore';
import { deleteFoodFromHealthAsync, healthSync, writeFoodToHealthAsync } from './health';

export async function shareDiaryExport(format: DiaryExportFormat, start: Date, end: Date): Promise<boolean> {
  const bundle = buildDiaryExport({
    start,
    end,
    format,
    entries: diaryStore.getState().foodEntries,
    waterEntries: diaryStore.getState().waterEntries,
    targets: dailyTargets(profileStore.getState()),
  });
  if (!bundle) return false;
  const path = `${cacheDirectory ?? ''}${bundle.filename}`;
  await writeAsStringAsync(path, bundle.text, { encoding: EncodingType.UTF8 });
  if (!(await Sharing.isAvailableAsync())) return false;
  await Sharing.shareAsync(path, { mimeType: format === 'json' ? 'application/json' : 'text/plain', dialogTitle: bundle.filename });
  return true;
}

export async function readImportedText(uri: string): Promise<string> {
  return readAsStringAsync(uri, { encoding: EncodingType.UTF8 });
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  const copy = new Uint8Array(data.byteLength);
  copy.set(data);
  const digestBuffer = await digest(CryptoDigestAlgorithm.SHA256, copy);
  return [...new Uint8Array(digestBuffer)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

export async function shareLocalBackup(): Promise<void> {
  const prefs = preferencesStore.getState();
  const diary = diaryStore.getState();
  const profile = profileStore.getState();
  const photos = listFoodImages();
  const values: Record<string, CloudBackupValue> = {
    preferences: { t: 's', s: JSON.stringify(prefs) },
    diary: { t: 's', s: JSON.stringify({ foodEntries: diary.foodEntries, waterEntries: diary.waterEntries, fastingSessions: diary.fastingSessions, favoriteKeys: diary.favoriteKeys }) },
    profile: { t: 's', s: JSON.stringify(profile) },
  };
  const draft = buildBackupDocument({
    values,
    photos,
    exportedAt: new Date().toISOString(),
    appVersion: '7.1',
    platform: Platform.OS,
    sha256Hex: () => '',
  });
  const document = {
    ...draft,
    content_sha256: await sha256Hex(new TextEncoder().encode(canonicalBackupContent(draft.payload.values, photos))),
  };
  const zipFiles: Array<{ name: string; data: Uint8Array }> = [
    { name: cloudBackupPolicy.payloadName, data: new TextEncoder().encode(JSON.stringify(document)) },
  ];
  for (const [name, data] of Object.entries(photos)) {
    zipFiles.push({ name: `${cloudBackupPolicy.photosDirectory}${name}`, data: new Uint8Array(data) });
  }
  const zip = packZip(zipFiles);
  const path = `${cacheDirectory ?? ''}Fud-AI-Backup.zip`;
  const base64 = uint8ToBase64(zip);
  await writeAsStringAsync(path, base64, { encoding: EncodingType.Base64 });
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(path, { mimeType: 'application/zip', dialogTitle: 'Fud AI backup' });
  }
}

export async function parseLocalBackup(uri: string): Promise<{ document: CloudBackupDocument; photos: Record<string, Uint8Array> }> {
  const base64 = await readAsStringAsync(uri, { encoding: EncodingType.Base64 });
  const bytes = base64ToUint8(base64);
  try {
    return unpackBackupArchive(bytes);
  } catch (error) {
    if (error instanceof CloudBackupError && error.kind !== 'missingPayload') throw error;
    try {
      return { document: parseBackupDocument(await readAsStringAsync(uri, { encoding: EncodingType.UTF8 })), photos: {} };
    } catch (fallback) {
      throw fallback instanceof CloudBackupError ? fallback : new CloudBackupError('invalidFormat');
    }
  }
}

export async function pickDiaryImportFile(): Promise<string | undefined> {
  const result = await DocumentPicker.getDocumentAsync({ type: ['application/json', 'text/plain', 'application/zip', '*/*'], copyToCacheDirectory: true });
  if (result.canceled || !result.assets[0]) return undefined;
  return result.assets[0].uri;
}

export async function previewDiaryImport(uri: string): Promise<DiaryImportPreview> {
  return parseDiaryImport(await readImportedText(uri), newId);
}

export async function commitDiaryImport(preview: DiaryImportPreview, mode: DiaryImportMode): Promise<void> {
  diaryStore.pause();
  try {
    const diary = diaryStore.getState();
    const nextFoods = applyDiaryImport(preview, diary.foodEntries, mode, newId);
    const nextWater = applyWaterImport(preview, diary.waterEntries, mode, newId);
    await reconcileImportedFoodsWithHealth(preview, diary.foodEntries, nextFoods, mode);
    diaryStore.applyImmediate({ type: 'food/replaceAll', entries: nextFoods });
    diaryStore.applyImmediate({ type: 'water/replaceAll', entries: nextWater });
  } finally {
    diaryStore.resume();
  }
}

async function reconcileImportedFoodsWithHealth(
  preview: DiaryImportPreview,
  existing: readonly FoodEntry[],
  nextFoods: readonly FoodEntry[],
  mode: DiaryImportMode,
): Promise<void> {
  let healthEnabled = false;
  try {
    healthEnabled = preferencesStore.getState().healthKitEnabled;
  } catch {
    return;
  }
  if (!healthEnabled) return;
  if (!healthSync.isAvailable || typeof healthSync.deleteNutrition !== 'function') {
    if (mode === 'replaceDateRange') {
      throw new Error(
        'Turn off Health sync before replacing diary days in this build. Matching nutrition records cannot be deleted here.',
      );
    }
    return;
  }
  const plan = diaryImportHealthReconcile(preview, existing, nextFoods, mode);
  for (const id of plan.deleteIds) {
    await deleteFoodFromHealthAsync(id);
  }
  for (const entry of plan.writeEntries) {
    await writeFoodToHealthAsync(entry);
  }
}

export async function restoreLocalBackup(uri: string): Promise<void> {
  const { document, photos } = await parseLocalBackup(uri);
  const hash = await sha256Hex(new TextEncoder().encode(canonicalBackupContent(document.payload.values, photos)));
  if (hash !== document.content_sha256) throw new CloudBackupError('hashMismatch');

  const values = document.payload.values;
  const prefsRaw = values.preferences?.t === 's' ? values.preferences.s : undefined;
  const diaryRaw = values.diary?.t === 's' ? values.diary.s : undefined;
  const profileRaw = values.profile?.t === 's' ? values.profile.s : undefined;

  const nextPrefs = prefsRaw ? mergePreferences(validateBackupPreferences(parseBackupJsonValue(prefsRaw))) : undefined;
  const nextProfile = profileRaw ? { ...defaultUserProfile, ...validateBackupProfile(parseBackupJsonValue(profileRaw)) } : undefined;
  const nextDiary = diaryRaw ? validateBackupDiary(parseBackupJsonValue(diaryRaw)) : undefined;

  if (nextPrefs) setPreferences(nextPrefs);
  if (nextProfile) profileStore.dispatch({ type: 'hydrate', profile: nextProfile });
  if (nextDiary) diaryStore.dispatch({ type: 'hydrate', state: nextDiary });
  restoreFoodImages(photos);
}

function uint8ToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return globalThis.btoa(binary);
}

function base64ToUint8(base64: string): Uint8Array {
  const binary = globalThis.atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
