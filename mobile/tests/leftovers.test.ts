import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';

import { deflateSync } from 'fflate';

import {
  assertBackupIntegrity,
  buildBackupDocument,
  CloudBackupError,
  contentHash,
  includeBackupKey,
  packZip,
  parseBackupDocument,
  safePhotoName,
  unpackBackupArchive,
  unpackZip,
  validateBackupDiary,
  zipInflateLimits,
} from '../src/domain/diary/cloudBackup';
import { applyFormulaAdaptiveGoals, shouldCheckAdaptiveGoals, snapshotFromProfile } from '../src/domain/profile/adaptiveGoals';
import { reminderTimesFromPrefs, scheduledReminders } from '../src/domain/prefs/reminders';
import { defaultPreferences } from '../src/domain/prefs/preferences';
import { defaultUserProfile } from '../src/domain/profile/userProfile';
import { createWeeklyChallengeAPI, validateWeeklyChallengeProfile, WeeklyChallengeAPIError } from '../src/domain/progress/weeklyChallengeApi';

const sha256Hex = (data: Uint8Array) => createHash('sha256').update(data).digest('hex');

describe('cloud backup archive', () => {
  it('excludes HealthKit bookkeeping and weekly-challenge tokens', () => {
    expect(includeBackupKey('healthKitEnabled')).toBe(true);
    expect(includeBackupKey('healthKitTypesVersion')).toBe(false);
    expect(includeBackupKey('weeklyChallengeBearerToken')).toBe(false);
    expect(safePhotoName('photos/meal.jpg')).toBe('meal.jpg');
    expect(safePhotoName('photos/../secret.txt')).toBeUndefined();
  });

  it('packs and unpacks backup.json with a stable content hash', () => {
    const values = { waterTrackingEnabled: { t: 'b' as const, b: true }, displayName: { t: 's' as const, s: 'Ada' } };
    const photos = { 'meal.jpg': new Uint8Array([1, 2, 3]) };
    const document = buildBackupDocument({
      values,
      photos,
      exportedAt: '2026-09-16T12:00:00Z',
      appVersion: '7.1',
      platform: 'ios',
      sha256Hex,
    });
    expect(document.format).toBe('fudai-cloud-backup');
    expect(document.content_sha256).toBe(contentHash(document.payload.values, photos, sha256Hex));
    const zip = packZip([
      { name: 'backup.json', data: new TextEncoder().encode(JSON.stringify(document)) },
      { name: 'photos/meal.jpg', data: photos['meal.jpg']! },
    ]);
    const unpacked = unpackBackupArchive(zip);
    expect(unpacked.document.payload.values.displayName).toEqual({ t: 's', s: 'Ada' });
    expect([...unpacked.photos['meal.jpg']!]).toEqual([1, 2, 3]);
    expect(() => parseBackupDocument('{"format":"nope"}')).toThrow(CloudBackupError);
    expect(() => assertBackupIntegrity({ ...document, content_sha256: 'nope' }, photos, sha256Hex)).toThrow(/damaged/);
  });

  it('inflates DEFLATE (method 8) Android-style ZIP entries', () => {
    const payload = new TextEncoder().encode('{"format":"fudai-cloud-backup","format_version":1}');
    const zip = packZipDeflate([{ name: 'backup.json', data: payload }]);
    expect([...unpackZip(zip)['backup.json']!]).toEqual([...payload]);
  });

  it('rejects ZIP entries whose declared uncompressed size exceeds the inflate budget', () => {
    const name = 'bomb.bin';
    const zip = packZip([{ name, data: new Uint8Array([1]) }]);
    const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
    const tooBig = zipInflateLimits.maxEntryBytes + 1;
    view.setUint32(22, tooBig, true);
    view.setUint32(30 + name.length + 1 + 24, tooBig, true);
    expect(() => unpackZip(zip)).toThrow(/too large/);
  });

  it('rejects malformed diary collections and keeps missing ones unset', () => {
    expect(validateBackupDiary({})).toEqual({});
    expect(validateBackupDiary({ foodEntries: [] }).foodEntries).toEqual([]);
    expect(() => validateBackupDiary({ foodEntries: [{ name: 'Oats' }] })).toThrow(CloudBackupError);
    expect(() => validateBackupDiary(null)).toThrow(CloudBackupError);
  });
});

function packZipDeflate(files: Array<{ name: string; data: Uint8Array }>): Uint8Array {
  const locals: number[] = [];
  const central: number[] = [];
  let offset = 0;
  const u16 = (value: number) => [value & 0xff, (value >> 8) & 0xff];
  const u32 = (value: number) => [value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff, (value >>> 24) & 0xff];
  const crcTable = Array.from({ length: 256 }, (_, i) => {
    let c = i;
    for (let bit = 0; bit < 8; bit += 1) c = (c & 1) === 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc32 = (data: Uint8Array) => {
    let crc = 0xffffffff;
    for (const byte of data) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 0xff]!;
    return (crc ^ 0xffffffff) >>> 0;
  };
  for (const file of files) {
    const nameData = [...new TextEncoder().encode(file.name)];
    const compressed = deflateSync(file.data);
    const crc = crc32(file.data);
    const local = [
      ...u32(0x04034b50),
      ...u16(20),
      ...u16(0),
      ...u16(8),
      ...u16(0),
      ...u16(0),
      ...u32(crc),
      ...u32(compressed.byteLength),
      ...u32(file.data.byteLength),
      ...u16(nameData.length),
      ...u16(0),
      ...nameData,
      ...compressed,
    ];
    locals.push(...local);
    central.push(
      ...u32(0x02014b50),
      ...u16(20),
      ...u16(20),
      ...u16(0),
      ...u16(8),
      ...u16(0),
      ...u16(0),
      ...u32(crc),
      ...u32(compressed.byteLength),
      ...u32(file.data.byteLength),
      ...u16(nameData.length),
      ...u16(0),
      ...u16(0),
      ...u16(0),
      ...u16(0),
      ...u32(0),
      ...u32(offset),
      ...nameData,
    );
    offset += local.length;
  }
  return Uint8Array.from([
    ...locals,
    ...central,
    ...u32(0x06054b50),
    ...u16(0),
    ...u16(0),
    ...u16(files.length),
    ...u16(files.length),
    ...u32(central.length),
    ...u32(offset),
    ...u16(0),
  ]);
}

describe('adaptive goals', () => {
  it('checks once a week and pins formula targets', () => {
    const now = new Date(2026, 8, 16);
    expect(shouldCheckAdaptiveGoals(undefined, now)).toBe(true);
    expect(shouldCheckAdaptiveGoals('2026-09-16', now)).toBe(false);
    expect(shouldCheckAdaptiveGoals('2026-09-09', now)).toBe(true);
    const profile = applyFormulaAdaptiveGoals({ ...defaultUserProfile, customCalories: 9999 }, now);
    expect(profile.customCalories).toBeGreaterThan(1000);
    expect(profile.customCalories).not.toBe(9999);
    expect(snapshotFromProfile({ ...defaultUserProfile, customProtein: 180 })).toEqual({ customProtein: 180 });
  });
});

describe('custom reminder times', () => {
  it('schedules only enabled meals', () => {
    const reminders = reminderTimesFromPrefs(defaultPreferences);
    expect(reminders.map((r) => r.id)).toEqual(['breakfast', 'lunch', 'dinner']);
    expect(scheduledReminders({ ...defaultPreferences, lunchReminderEnabled: false })).toHaveLength(2);
    expect(reminders.find((r) => r.id === 'breakfast')?.hour).toBe(8);
  });
});

describe('weekly challenge profile + API', () => {
  it('validates display names and handles', () => {
    expect(validateWeeklyChallengeProfile('Ada', undefined, '').ok).toBe(true);
    expect(validateWeeklyChallengeProfile('x', undefined, '').ok).toBe(false);
    expect(validateWeeklyChallengeProfile('Fud AI', undefined, '').ok).toBe(false);
    expect(validateWeeklyChallengeProfile('Ada', 'x', 'ada_ok').ok).toBe(true);
    expect(validateWeeklyChallengeProfile('Ada', 'x', '@ada').ok).toBe(false);
  });

  it('sends bearer auth and maps server errors', async () => {
    const calls: string[] = [];
    const api = createWeeklyChallengeAPI({
      fetch: (async (input, init) => {
        const headers = init?.headers as Record<string, string> | undefined;
        calls.push(`${init?.method} ${String(input)} ${headers?.Authorization ?? ''}`);
        if (String(input).includes('leaderboard')) {
          return new Response(JSON.stringify({ weekStart: '2026-09-14', category: 'overall', updatedAt: 't', rankings: [] }), { status: 200 });
        }
        return new Response(JSON.stringify({ error: { message: 'Nope' } }), { status: 400 });
      }) as typeof fetch,
    });
    const board = await api.leaderboard('overall', '2026-09-14', 'token-1');
    expect(board.weekStart).toBe('2026-09-14');
    expect(calls[0]).toContain('Bearer token-1');
    await expect(api.createProfile({ displayName: 'Ada', acceptedRules: true, eligibilityAccepted: true })).rejects.toBeInstanceOf(WeeklyChallengeAPIError);
  });
});
