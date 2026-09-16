/**
 * Portable Fud AI backup archive (ZIP of `backup.json` + food photos). Port of
 * `CloudBackupArchive.swift`. iCloud Drive itself stays native-only; this file format is what
 * Settings can export / import on both platforms without secrets in git.
 */

import { inflateSync } from 'fflate';

import { activityLevels, genders, weightGoals, type UserProfile } from '../profile/userProfile';
import type { FastingSession } from '../fasting/fasting';
import type { FoodEntry } from '../food/food';
import type { WaterEntry } from '../water/water';

export const cloudBackupPolicy = {
  format: 'fudai-cloud-backup',
  version: 1,
  payloadName: 'backup.json',
  photosDirectory: 'photos/',
  excludedKeys: new Set([
    'healthKitFoodRecoveryDone',
    'healthKitNutritionBackfillVersion',
    'healthKitWeightBackfillVersion',
    'healthKitBodyFatBackfillVersion',
    'healthKitWorkoutBurnDeletionTombstones',
    'healthKitTypesVersion',
    'healthKitAuthVersion',
    'lastNotifiedAppUpdateVersion',
    'weeklyChallengeBearerToken',
  ]),
} as const;

export type CloudBackupValue =
  | { t: 'b'; b: boolean }
  | { t: 'i'; i: number }
  | { t: 's'; s: string }
  | { t: 'd'; d: string }
  | { t: 'ss'; ss: string[] };

export interface CloudBackupDocument {
  format: string;
  format_version: number;
  exported_at: string;
  app_version: string;
  platform: string;
  content_sha256: string;
  payload: { values: Record<string, CloudBackupValue> };
}

export const zipInflateLimits = {
  maxEntryBytes: 20 * 1024 * 1024,
  maxTotalBytes: 64 * 1024 * 1024,
  maxEntries: 256,
} as const;

export type CloudBackupErrorKind = 'missingPayload' | 'invalidFormat' | 'needsNewerApp' | 'hashMismatch' | 'tooLarge';

export class CloudBackupError extends Error {
  readonly kind: CloudBackupErrorKind;

  constructor(kind: CloudBackupErrorKind) {
    super(
      kind === 'needsNewerApp'
        ? 'This backup needs a newer Fud AI.'
        : kind === 'hashMismatch'
          ? 'This backup file is damaged or incomplete.'
          : kind === 'tooLarge'
            ? 'This backup is too large to restore.'
            : 'This is not a Fud AI backup.',
    );
    this.name = 'CloudBackupError';
    this.kind = kind;
  }
}

export function includeBackupKey(key: string): boolean {
  if (key === 'healthKitEnabled') return true;
  if (cloudBackupPolicy.excludedKeys.has(key)) return false;
  if (key.startsWith('healthKit')) return false;
  if (key.startsWith('Apple') || key.startsWith('NS') || key.startsWith('com.apple')) return false;
  if (key.startsWith('AK')) return false;
  return true;
}

export function safePhotoName(name: string): string | undefined {
  const base = name.split('/').pop() ?? name;
  if (![...base].every((ch) => /[A-Za-z0-9._-]/.test(ch))) return undefined;
  const ext = (base.split('.').pop() ?? '').toLowerCase();
  if (!['jpg', 'jpeg', 'png', 'webp'].includes(ext)) return undefined;
  return base;
}

/** Canonical UTF-8 payload hashed as `content_sha256` (matches `CloudBackupArchive.contentHash`). */
export function canonicalBackupContent(values: Record<string, CloudBackupValue>, photos: Record<string, Uint8Array>): string {
  let canonical = '';
  for (const key of Object.keys(values).sort()) {
    const value = values[key];
    if (!value) continue;
    canonical += `${key}=`;
    switch (value.t) {
      case 'b':
        canonical += `b:${value.b ? 'true' : 'false'}`;
        break;
      case 'i':
        canonical += `i:${value.i}`;
        break;
      case 's':
        canonical += `s:${value.s}`;
        break;
      case 'd':
        canonical += `d:${value.d}`;
        break;
      case 'ss':
        canonical += `ss:${[...value.ss].sort().join(',')}`;
        break;
    }
    canonical += '\n';
  }
  for (const name of Object.keys(photos).sort()) {
    canonical += `photo:${name}:${photos[name]?.byteLength ?? 0}\n`;
  }
  return canonical;
}

export function contentHash(values: Record<string, CloudBackupValue>, photos: Record<string, Uint8Array>, sha256Hex: (data: Uint8Array) => string): string {
  return sha256Hex(new TextEncoder().encode(canonicalBackupContent(values, photos)));
}

export function assertBackupIntegrity(
  document: CloudBackupDocument,
  photos: Record<string, Uint8Array>,
  sha256Hex: (data: Uint8Array) => string,
): void {
  if (document.content_sha256 !== contentHash(document.payload.values, photos, sha256Hex)) {
    throw new CloudBackupError('hashMismatch');
  }
}

export function buildBackupDocument(input: {
  values: Record<string, CloudBackupValue>;
  photos: Record<string, Uint8Array>;
  exportedAt: string;
  appVersion: string;
  platform: string;
  sha256Hex: (data: Uint8Array) => string;
}): CloudBackupDocument {
  const filtered = Object.fromEntries(Object.entries(input.values).filter(([key]) => includeBackupKey(key)));
  const safePhotos = Object.fromEntries(
    Object.entries(input.photos).flatMap(([name, data]) => {
      const safe = safePhotoName(name);
      return safe ? [[safe, data] as const] : [];
    }),
  );
  return {
    format: cloudBackupPolicy.format,
    format_version: cloudBackupPolicy.version,
    exported_at: input.exportedAt,
    app_version: input.appVersion,
    platform: input.platform,
    content_sha256: contentHash(filtered, safePhotos, input.sha256Hex),
    payload: { values: filtered },
  };
}

export function parseBackupDocument(json: string): CloudBackupDocument {
  let document: CloudBackupDocument;
  try {
    document = JSON.parse(json) as CloudBackupDocument;
  } catch {
    throw new CloudBackupError('invalidFormat');
  }
  if (document.format !== cloudBackupPolicy.format) throw new CloudBackupError('invalidFormat');
  if (document.format_version > cloudBackupPolicy.version) throw new CloudBackupError('needsNewerApp');
  return document;
}

/** Uncompressed ZIP (store method), matching `CloudBackupZip`. */
export function packZip(files: Array<{ name: string; data: Uint8Array }>): Uint8Array {
  const locals: number[] = [];
  const central: number[] = [];
  let offset = 0;
  const push = (target: number[], bytes: number[]) => {
    target.push(...bytes);
  };

  for (const file of files) {
    const nameData = new TextEncoder().encode(file.name);
    const crc = crc32(file.data);
    const local: number[] = [];
    push(local, u32(0x04034b50));
    push(local, u16(20));
    push(local, u16(0));
    push(local, u16(0));
    push(local, u16(0));
    push(local, u16(0));
    push(local, u32(crc));
    push(local, u32(file.data.byteLength));
    push(local, u32(file.data.byteLength));
    push(local, u16(nameData.byteLength));
    push(local, u16(0));
    local.push(...nameData, ...file.data);
    locals.push(...local);

    const dir: number[] = [];
    push(dir, u32(0x02014b50));
    push(dir, u16(20));
    push(dir, u16(20));
    push(dir, u16(0));
    push(dir, u16(0));
    push(dir, u16(0));
    push(dir, u16(0));
    push(dir, u32(crc));
    push(dir, u32(file.data.byteLength));
    push(dir, u32(file.data.byteLength));
    push(dir, u16(nameData.byteLength));
    push(dir, u16(0));
    push(dir, u16(0));
    push(dir, u16(0));
    push(dir, u16(0));
    push(dir, u32(0));
    push(dir, u32(offset));
    dir.push(...nameData);
    central.push(...dir);
    offset += local.length;
  }

  const end: number[] = [];
  push(end, u32(0x06054b50));
  push(end, u16(0));
  push(end, u16(0));
  push(end, u16(files.length));
  push(end, u16(files.length));
  push(end, u32(central.length));
  push(end, u32(offset));
  push(end, u16(0));
  return Uint8Array.from([...locals, ...central, ...end]);
}

export function unpackZip(data: Uint8Array): Record<string, Uint8Array> {
  const budget = { used: 0, entries: 0 };
  const eocd = findEndOfCentralDirectory(data);
  if (eocd >= 0) {
    const fromCentral = unpackZipFromCentralDirectory(data, eocd, budget);
    if (Object.keys(fromCentral).length > 0) return fromCentral;
  }
  return unpackZipFromLocalHeaders(data, { used: 0, entries: 0 });
}

/** Present collections only. Missing keys must not wipe existing diary state. */
export interface BackupDiaryCollections {
  foodEntries?: FoodEntry[];
  waterEntries?: WaterEntry[];
  fastingSessions?: FastingSession[];
  favoriteKeys?: string[];
}

export function parseBackupJsonValue(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new CloudBackupError('invalidFormat');
  }
}

export function validateBackupPreferences(value: unknown): Record<string, unknown> {
  if (!isPlainObject(value)) throw new CloudBackupError('invalidFormat');
  return value;
}

export function validateBackupProfile(value: unknown): Partial<UserProfile> {
  if (!isPlainObject(value)) throw new CloudBackupError('invalidFormat');
  if (value.gender !== undefined && !genders.includes(value.gender as UserProfile['gender'])) {
    throw new CloudBackupError('invalidFormat');
  }
  if (value.activityLevel !== undefined && !activityLevels.includes(value.activityLevel as UserProfile['activityLevel'])) {
    throw new CloudBackupError('invalidFormat');
  }
  if (value.goal !== undefined && !weightGoals.includes(value.goal as UserProfile['goal'])) {
    throw new CloudBackupError('invalidFormat');
  }
  if (value.heightCm !== undefined && !isFiniteNumber(value.heightCm)) throw new CloudBackupError('invalidFormat');
  if (value.weightKg !== undefined && !isFiniteNumber(value.weightKg)) throw new CloudBackupError('invalidFormat');
  if (value.birthday !== undefined && typeof value.birthday !== 'string') throw new CloudBackupError('invalidFormat');
  return value as Partial<UserProfile>;
}

export function validateBackupDiary(value: unknown): BackupDiaryCollections {
  if (!isPlainObject(value)) throw new CloudBackupError('invalidFormat');
  const collections: BackupDiaryCollections = {};
  if ('foodEntries' in value) {
    if (!Array.isArray(value.foodEntries) || !value.foodEntries.every(isBackupFoodEntry)) {
      throw new CloudBackupError('invalidFormat');
    }
    collections.foodEntries = value.foodEntries as FoodEntry[];
  }
  if ('waterEntries' in value) {
    if (!Array.isArray(value.waterEntries) || !value.waterEntries.every(isBackupWaterEntry)) {
      throw new CloudBackupError('invalidFormat');
    }
    collections.waterEntries = value.waterEntries as WaterEntry[];
  }
  if ('fastingSessions' in value) {
    if (!Array.isArray(value.fastingSessions) || !value.fastingSessions.every(isBackupFastingSession)) {
      throw new CloudBackupError('invalidFormat');
    }
    collections.fastingSessions = value.fastingSessions as FastingSession[];
  }
  if ('favoriteKeys' in value) {
    if (!Array.isArray(value.favoriteKeys) || !value.favoriteKeys.every((key) => typeof key === 'string')) {
      throw new CloudBackupError('invalidFormat');
    }
    collections.favoriteKeys = value.favoriteKeys as string[];
  }
  return collections;
}

export function unpackBackupArchive(data: Uint8Array): { document: CloudBackupDocument; photos: Record<string, Uint8Array> } {
  const files = unpackZip(data);
  const payload = files[cloudBackupPolicy.payloadName];
  if (!payload) throw new CloudBackupError('missingPayload');
  const document = parseBackupDocument(new TextDecoder().decode(payload));
  const photos: Record<string, Uint8Array> = {};
  for (const [name, bytes] of Object.entries(files)) {
    if (!name.startsWith(cloudBackupPolicy.photosDirectory)) continue;
    const safe = safePhotoName(name);
    if (safe) photos[safe] = bytes;
  }
  return { document, photos };
}

function u16(value: number): number[] {
  return [value & 0xff, (value >> 8) & 0xff];
}

function u32(value: number): number[] {
  return [value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff, (value >>> 24) & 0xff];
}

function readU16(data: Uint8Array, i: number): number {
  return data[i]! | (data[i + 1]! << 8);
}

function readU32(data: Uint8Array, i: number): number {
  return (data[i]! | (data[i + 1]! << 8) | (data[i + 2]! << 16) | (data[i + 3]! << 24)) >>> 0;
}

function findEndOfCentralDirectory(data: Uint8Array): number {
  const min = Math.max(0, data.byteLength - 22 - 65_535);
  for (let i = data.byteLength - 22; i >= min; i -= 1) {
    if (readU32(data, i) === 0x06054b50) return i;
  }
  return -1;
}

function unpackZipFromCentralDirectory(data: Uint8Array, eocd: number, budget: ZipBudget): Record<string, Uint8Array> {
  const result: Record<string, Uint8Array> = {};
  const entryCount = readU16(data, eocd + 10);
  if (entryCount > zipInflateLimits.maxEntries) throw new CloudBackupError('tooLarge');
  let i = readU32(data, eocd + 16);
  for (let n = 0; n < entryCount; n += 1) {
    if (i + 46 > data.byteLength || readU32(data, i) !== 0x02014b50) break;
    const method = readU16(data, i + 10);
    const compact = readU32(data, i + 20);
    const uncompressed = readU32(data, i + 24);
    const nameLen = readU16(data, i + 28);
    const extraLen = readU16(data, i + 30);
    const commentLen = readU16(data, i + 32);
    const localOff = readU32(data, i + 42);
    const nameEnd = i + 46 + nameLen;
    if (nameEnd > data.byteLength) break;
    const name = new TextDecoder().decode(data.subarray(i + 46, nameEnd));
    if (localOff + 30 > data.byteLength || readU32(data, localOff) !== 0x04034b50) break;
    const localNameLen = readU16(data, localOff + 26);
    const localExtraLen = readU16(data, localOff + 28);
    const dataStart = localOff + 30 + localNameLen + localExtraLen;
    if (dataStart + compact > data.byteLength) break;
    result[name] = inflateZipPayload(data.subarray(dataStart, dataStart + compact), method, uncompressed, budget);
    i = nameEnd + extraLen + commentLen;
  }
  return result;
}

function unpackZipFromLocalHeaders(data: Uint8Array, budget: ZipBudget): Record<string, Uint8Array> {
  const result: Record<string, Uint8Array> = {};
  let i = 0;
  while (i + 30 <= data.byteLength) {
    const sig = readU32(data, i);
    if (sig === 0x02014b50 || sig === 0x06054b50) break;
    if (sig !== 0x04034b50) break;
    const method = readU16(data, i + 8);
    const compact = readU32(data, i + 18);
    const uncompressed = readU32(data, i + 22);
    const nameLen = readU16(data, i + 26);
    const extraLen = readU16(data, i + 28);
    const nameStart = i + 30;
    const nameEnd = nameStart + nameLen;
    if (nameEnd + extraLen + compact > data.byteLength) break;
    const name = new TextDecoder().decode(data.subarray(nameStart, nameEnd));
    const dataStart = nameEnd + extraLen;
    result[name] = inflateZipPayload(data.subarray(dataStart, dataStart + compact), method, uncompressed, budget);
    i = dataStart + compact;
  }
  return result;
}

interface ZipBudget {
  used: number;
  entries: number;
}

function consumeZipBudget(budget: ZipBudget, produced: number, declared: number): void {
  if (declared > zipInflateLimits.maxEntryBytes || produced > zipInflateLimits.maxEntryBytes) {
    throw new CloudBackupError('tooLarge');
  }
  budget.used += produced;
  budget.entries += 1;
  if (budget.used > zipInflateLimits.maxTotalBytes || budget.entries > zipInflateLimits.maxEntries) {
    throw new CloudBackupError('tooLarge');
  }
}

function inflateZipPayload(raw: Uint8Array, method: number, uncompressed: number, budget: ZipBudget): Uint8Array {
  if (uncompressed > zipInflateLimits.maxEntryBytes) throw new CloudBackupError('tooLarge');
  const reserved = uncompressed > 0 ? uncompressed : raw.byteLength;
  if (budget.used + reserved > zipInflateLimits.maxTotalBytes) throw new CloudBackupError('tooLarge');

  let produced: Uint8Array;
  if (method === 0) {
    produced = raw;
    if (uncompressed > 0 && raw.byteLength !== uncompressed) throw new CloudBackupError('invalidFormat');
  } else if (method === 8) {
    if (uncompressed === 0) throw new CloudBackupError('tooLarge');
    try {
      produced = inflateSync(raw, { out: new Uint8Array(uncompressed) });
    } catch {
      throw new CloudBackupError('invalidFormat');
    }
    if (produced.byteLength > uncompressed) throw new CloudBackupError('tooLarge');
  } else {
    throw new CloudBackupError('invalidFormat');
  }
  consumeZipBudget(budget, produced.byteLength, uncompressed);
  return produced;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isIsoTimestamp(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value));
}

function isBackupFoodEntry(value: unknown): value is FoodEntry {
  if (!isPlainObject(value)) return false;
  return (
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    isFiniteNumber(value.calories) &&
    isFiniteNumber(value.protein) &&
    isFiniteNumber(value.carbs) &&
    isFiniteNumber(value.fat) &&
    isIsoTimestamp(value.timestamp) &&
    typeof value.source === 'string' &&
    typeof value.mealType === 'string'
  );
}

function isBackupWaterEntry(value: unknown): value is WaterEntry {
  if (!isPlainObject(value)) return false;
  return typeof value.id === 'string' && isIsoTimestamp(value.date) && isFiniteNumber(value.milliliters) && value.milliliters > 0;
}

function isBackupFastingSession(value: unknown): value is FastingSession {
  if (!isPlainObject(value)) return false;
  return (
    typeof value.id === 'string' &&
    isIsoTimestamp(value.startedAt) &&
    isFiniteNumber(value.goalMinutes) &&
    (value.endedAt === undefined || isIsoTimestamp(value.endedAt))
  );
}

function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 0xff]!;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const crcTable: number[] = Array.from({ length: 256 }, (_, i) => {
  let c = i;
  for (let bit = 0; bit < 8; bit += 1) {
    c = (c & 1) === 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return c >>> 0;
});
