/**
 * Diary export / import and portable backup files. Share sheet + document picker — no iCloud
 * account or secrets. The archive format matches `CloudBackupArchive.swift`.
 */

import { digestStringAsync, CryptoDigestAlgorithm } from 'expo-crypto';
import * as DocumentPicker from 'expo-document-picker';
import { cacheDirectory, EncodingType, readAsStringAsync, writeAsStringAsync } from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { Platform } from 'react-native';

import {
  applyDiaryImport,
  applyWaterImport,
  buildBackupDocument,
  buildDiaryExport,
  cloudBackupPolicy,
  mergePreferences,
  packZip,
  parseBackupDocument,
  parseDiaryImport,
  type CloudBackupValue,
  type DiaryExportFormat,
  type DiaryImportMode,
  type DiaryImportPreview,
  unpackBackupArchive,
} from '../domain';
import { dailyTargets, defaultUserProfile } from '../domain/profile/userProfile';
import { diaryStore, newId, preferencesStore, profileStore, setPreferences } from '../state/appStores';

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
  const text = new TextDecoder().decode(data);
  return digestStringAsync(CryptoDigestAlgorithm.SHA256, text);
}

export async function shareLocalBackup(): Promise<void> {
  const prefs = preferencesStore.getState();
  const diary = diaryStore.getState();
  const profile = profileStore.getState();
  const values: Record<string, CloudBackupValue> = {
    preferences: { t: 's', s: JSON.stringify(prefs) },
    diary: { t: 's', s: JSON.stringify({ foodEntries: diary.foodEntries, waterEntries: diary.waterEntries, fastingSessions: diary.fastingSessions, favoriteKeys: diary.favoriteKeys }) },
    profile: { t: 's', s: JSON.stringify(profile) },
  };
  const document = buildBackupDocument({
    values,
    photos: {},
    exportedAt: new Date().toISOString(),
    appVersion: '7.1',
    platform: Platform.OS,
    sha256Hex: (bytes) => {
      // Sync stand-in for the pack step; hash is recomputed with expo-crypto below when needed.
      let hex = '';
      for (const byte of bytes) hex += byte.toString(16).padStart(2, '0');
      return hex.slice(0, 64).padEnd(64, '0');
    },
  });
  document.content_sha256 = await sha256Hex(new TextEncoder().encode(JSON.stringify(document.payload.values)));
  const zip = packZip([{ name: cloudBackupPolicy.payloadName, data: new TextEncoder().encode(JSON.stringify(document)) }]);
  const path = `${cacheDirectory ?? ''}Fud-AI-Backup.zip`;
  const base64 = uint8ToBase64(zip);
  await writeAsStringAsync(path, base64, { encoding: EncodingType.Base64 });
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(path, { mimeType: 'application/zip', dialogTitle: 'Fud AI backup' });
  }
}

export async function parseLocalBackup(uri: string): Promise<ReturnType<typeof parseBackupDocument>> {
  const base64 = await readAsStringAsync(uri, { encoding: EncodingType.Base64 });
  const bytes = base64ToUint8(base64);
  try {
    return unpackBackupArchive(bytes).document;
  } catch {
    const text = await readAsStringAsync(uri, { encoding: EncodingType.UTF8 });
    return parseBackupDocument(text);
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

export function commitDiaryImport(preview: DiaryImportPreview, mode: DiaryImportMode): void {
  const diary = diaryStore.getState();
  diaryStore.dispatch({ type: 'food/replaceAll', entries: applyDiaryImport(preview, diary.foodEntries, mode, newId) });
  diaryStore.dispatch({ type: 'water/replaceAll', entries: applyWaterImport(preview, diary.waterEntries, mode, newId) });
}

export async function restoreLocalBackup(uri: string): Promise<void> {
  const document = await parseLocalBackup(uri);
  const values = document.payload.values;
  const prefsRaw = values.preferences?.t === 's' ? values.preferences.s : undefined;
  const diaryRaw = values.diary?.t === 's' ? values.diary.s : undefined;
  const profileRaw = values.profile?.t === 's' ? values.profile.s : undefined;
  if (prefsRaw) setPreferences(mergePreferences(JSON.parse(prefsRaw)));
  if (profileRaw) {
    profileStore.dispatch({ type: 'hydrate', profile: { ...defaultUserProfile, ...(JSON.parse(profileRaw) as object) } });
  }
  if (diaryRaw) {
    const parsed = JSON.parse(diaryRaw) as {
      foodEntries?: unknown;
      waterEntries?: unknown;
      fastingSessions?: unknown;
      favoriteKeys?: unknown;
    };
    diaryStore.dispatch({
      type: 'hydrate',
      state: {
        foodEntries: Array.isArray(parsed.foodEntries) ? parsed.foodEntries : [],
        waterEntries: Array.isArray(parsed.waterEntries) ? parsed.waterEntries : [],
        fastingSessions: Array.isArray(parsed.fastingSessions) ? parsed.fastingSessions : [],
        favoriteKeys: Array.isArray(parsed.favoriteKeys) ? parsed.favoriteKeys : [],
      },
    });
  }
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
