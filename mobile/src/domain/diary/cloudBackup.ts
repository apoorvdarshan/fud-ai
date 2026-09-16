/**
 * Portable Fud AI backup archive (ZIP of `backup.json` + food photos). Port of
 * `CloudBackupArchive.swift`. iCloud Drive itself stays native-only; this file format is what
 * Settings can export / import on both platforms without secrets in git.
 */

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

export type CloudBackupErrorKind = 'missingPayload' | 'invalidFormat' | 'needsNewerApp';

export class CloudBackupError extends Error {
  readonly kind: CloudBackupErrorKind;

  constructor(kind: CloudBackupErrorKind) {
    super(
      kind === 'needsNewerApp' ? 'This backup needs a newer Fud AI.' : 'This is not a Fud AI backup.',
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

export function contentHash(values: Record<string, CloudBackupValue>, photos: Record<string, Uint8Array>, sha256Hex: (data: Uint8Array) => string): string {
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
  return sha256Hex(new TextEncoder().encode(canonical));
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
  const result: Record<string, Uint8Array> = {};
  let i = 0;
  while (i + 30 <= data.byteLength) {
    const sig = readU32(data, i);
    if (sig === 0x02014b50 || sig === 0x06054b50) break;
    if (sig !== 0x04034b50) break;
    const nameLen = readU16(data, i + 26);
    const extraLen = readU16(data, i + 28);
    const compact = readU32(data, i + 18);
    const nameStart = i + 30;
    const nameEnd = nameStart + nameLen;
    if (nameEnd + extraLen + compact > data.byteLength) break;
    const name = new TextDecoder().decode(data.subarray(nameStart, nameEnd));
    const dataStart = nameEnd + extraLen;
    result[name] = data.subarray(dataStart, dataStart + compact);
    i = dataStart + compact;
  }
  return result;
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
  return data[i]! | (data[i + 1]! << 8) | (data[i + 2]! << 16) | (data[i + 3]! << 24);
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
