import { describe, expect, it } from 'vitest';

import { applyDiaryImport, applyWaterImport, diaryImportHealthReconcile, DiaryImportError, parseDiaryImport } from '../src/domain/diary/diaryImport';
import { buildDiaryExport, csvEscape, resolveDiaryExportRange, sourceLabel } from '../src/domain/diary/diaryExport';
import { makeFoodEntry } from '../src/domain/food/food';

const day = new Date(2026, 8, 16, 8, 30, 0);
const entry = makeFoodEntry(
  {
    name: 'Oats',
    calories: 300,
    protein: 10,
    carbs: 50,
    fat: 5,
    source: 'manual',
    timestamp: day.toISOString(),
    mealType: 'breakfast',
    servingSizeGrams: 80,
    fiber: 8,
  },
  '11111111-1111-1111-1111-111111111111',
);

describe('diary export', () => {
  it('builds JSON 1.5 with meals, water and remaining', () => {
    const bundle = buildDiaryExport({
      start: day,
      end: day,
      format: 'json',
      entries: [entry],
      waterEntries: [{ id: '22222222-2222-2222-2222-222222222222', date: day.toISOString(), milliliters: 250 }],
      targets: { calories: 2000, protein: 150, carbs: 220, fat: 70 },
    });
    expect(bundle?.filename).toBe('Fud-Food-Diary-2026-09-16_to_2026-09-16.json');
    const doc = JSON.parse(bundle!.text) as {
      export: { app: string; format_version: string };
      days: Array<{ totals: { calories: number }; water_total_ml: number; remaining: { calories: number } }>;
    };
    expect(doc.export.app).toBe('Fud AI');
    expect(doc.export.format_version).toBe('1.5');
    expect(doc.days[0]?.totals.calories).toBe(300);
    expect(doc.days[0]?.water_total_ml).toBe(250);
    expect(doc.days[0]?.remaining.calories).toBe(1700);
    expect(sourceLabel('manual')).toBe('manually_edited');
  });

  it('returns undefined when the range is empty and resolves presets', () => {
    expect(
      buildDiaryExport({
        start: day,
        end: day,
        format: 'csv',
        entries: [],
        targets: { calories: 2000, protein: 150, carbs: 220, fat: 70 },
      }),
    ).toBeUndefined();
    const week = resolveDiaryExportRange('thisWeek', day, [entry]);
    expect(week.end.getDate()).toBe(16);
    expect(week.start.getDay()).toBe(1);
  });

  it('neutralizes spreadsheet formula injection in CSV cells', () => {
    expect(csvEscape('=1+1')).toBe(`"'=1+1"`);
    expect(csvEscape('+cmd')).toBe(`"'+cmd"`);
    expect(csvEscape('-1')).toBe(`"'-1"`);
    expect(csvEscape('@sum')).toBe(`"'@sum"`);
    expect(csvEscape('Oats')).toBe('Oats');
    const formula = makeFoodEntry(
      { name: '=HYPERLINK("http://evil")', calories: 1, protein: 0, carbs: 0, fat: 0, source: 'manual', timestamp: day.toISOString(), mealType: 'snack' },
      '33333333-3333-3333-3333-333333333333',
    );
    const csv = buildDiaryExport({
      start: day,
      end: day,
      format: 'csv',
      entries: [formula],
      targets: { calories: 2000, protein: 150, carbs: 220, fat: 70 },
    });
    expect(csv?.text).toContain(`"'=HYPERLINK(""http://evil"")"`);
    expect(csv?.text.includes(',=HYPERLINK')).toBe(false);
  });
});

describe('diary import', () => {
  const exported = buildDiaryExport({
    start: day,
    end: day,
    format: 'json',
    entries: [entry],
    waterEntries: [{ id: '22222222-2222-2222-2222-222222222222', date: day.toISOString(), milliliters: 250 }],
    targets: { calories: 2000, protein: 150, carbs: 220, fat: 70 },
  })!.text;

  it('round-trips JSON and replace keeps ids in range', () => {
    const preview = parseDiaryImport(exported);
    expect(preview.entries).toHaveLength(1);
    expect(preview.entries[0]?.name).toBe('Oats');
    expect(preview.includesWater).toBe(true);
    const replaced = applyDiaryImport(preview, [entry], 'replaceDateRange');
    expect(replaced[0]?.id).toBe(entry.id);
    const added = applyDiaryImport(preview, [entry], 'addAsNew', () => 'new-id');
    expect(added).toHaveLength(2);
    expect(added[1]?.id).toBe('new-id');
    const water = applyWaterImport(preview, [], 'replaceDateRange');
    expect(water[0]?.milliliters).toBe(250);
    const outgoing = makeFoodEntry(
      { name: 'Rice', calories: 200, protein: 4, carbs: 40, fat: 1, source: 'manual', timestamp: day.toISOString(), mealType: 'lunch' },
      '44444444-4444-4444-4444-444444444444',
    );
    const plan = diaryImportHealthReconcile(preview, [entry, outgoing], replaced, 'replaceDateRange');
    expect(plan.deleteIds).toEqual([entry.id, outgoing.id]);
    expect(plan.writeEntries.map((e) => e.id)).toEqual([entry.id]);
  });

  it('rejects invalid documents with the native messages', () => {
    expect(() => parseDiaryImport('{"nope":true}')).toThrow(DiaryImportError);
    expect(() => parseDiaryImport('{"export":{"app":"Other","format_version":"1.5","date_range":{"start":"2026-09-16","end":"2026-09-16"}},"days":[]}')).toThrow(
      /not a valid Fud AI/,
    );
    expect(() => parseDiaryImport('{"export":{"app":"Fud AI","format_version":"2.0","date_range":{"start":"2026-09-16","end":"2026-09-16"}},"days":[]}')).toThrow(
      /not supported/,
    );
    const badTime = JSON.parse(exported) as {
      days: Array<{ date: string; meals: Array<{ items: Array<{ time: string }> }> }>;
    };
    badTime.days[0]!.meals[0]!.items[0]!.time = '99:99';
    expect(() => parseDiaryImport(JSON.stringify(badTime))).toThrow(/invalid date or time/);
  });
});
