/**
 * Shareable food-diary files. Port of `DiaryExporter.swift` — JSON / Markdown / CSV with the
 * same columns, source labels and `format_version` 1.5 so native imports still decode.
 */

import { addDays, dayKey, isSameDay, startOfDay, startOfWeek } from '../dates';
import { mealTypeDisplayName, type FoodEntry, type MealType } from '../food/food';
import type { DailyTargets } from '../profile/userProfile';
import type { WaterEntry } from '../water/water';

export const diaryExportFormats = ['json', 'markdown', 'csv'] as const;
export type DiaryExportFormat = (typeof diaryExportFormats)[number];

export const diaryExportRanges = ['today', 'thisWeek', 'thisMonth', 'allTime', 'custom'] as const;
export type DiaryExportRange = (typeof diaryExportRanges)[number];

export const diaryExportFormatLabel: Record<DiaryExportFormat, string> = {
  json: 'JSON',
  markdown: 'Markdown',
  csv: 'CSV',
};

export const diaryExportRangeLabel: Record<DiaryExportRange, string> = {
  today: 'Today',
  thisWeek: 'This week',
  thisMonth: 'This month',
  allTime: 'All time',
  custom: 'Custom',
};

export const DIARY_EXPORT_FORMAT_VERSION = '1.5';

const mealOrder: readonly MealType[] = ['breakfast', 'lunch', 'dinner', 'snack', 'other'];

export interface DiaryExportBundle {
  filename: string;
  text: string;
}

export function diaryExportExtension(format: DiaryExportFormat): string {
  switch (format) {
    case 'json':
      return 'json';
    case 'markdown':
      return 'md';
    case 'csv':
      return 'csv';
  }
}

export function resolveDiaryExportRange(
  range: DiaryExportRange,
  now: Date,
  entries: readonly FoodEntry[],
  waterEntries: readonly WaterEntry[] = [],
  customStart?: Date,
  customEnd?: Date,
): { start: Date; end: Date } {
  const today = startOfDay(now);
  switch (range) {
    case 'today':
      return { start: today, end: today };
    case 'thisWeek':
      return { start: startOfWeek(today, true), end: today };
    case 'thisMonth': {
      const start = new Date(today.getFullYear(), today.getMonth(), 1);
      return { start, end: today };
    }
    case 'allTime': {
      const times = [...entries.map((e) => new Date(e.timestamp)), ...waterEntries.map((e) => new Date(e.date))];
      const earliest = times.reduce<Date | undefined>((min, date) => (!min || date < min ? date : min), undefined);
      return { start: earliest ? startOfDay(earliest) : today, end: today };
    }
    case 'custom': {
      const start = startOfDay(customStart ?? today);
      const end = startOfDay(customEnd ?? today);
      return start <= end ? { start, end } : { start: end, end: start };
    }
  }
}

export function sourceLabel(source: FoodEntry['source']): string {
  return source === 'manual' ? 'manually_edited' : 'ai_estimated';
}

function r1(value: number): number {
  return Math.round(value * 10) / 10;
}

function optionalNumber(value: number | undefined, missing = ''): string {
  return value === undefined ? missing : String(r1(value));
}

function timeFmt(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

interface DayBundle {
  date: Date;
  groups: { meal: MealType; entries: FoodEntry[] }[];
  water: WaterEntry[];
}

function collectDays(start: Date, end: Date, entries: readonly FoodEntry[], waterEntries: readonly WaterEntry[]): DayBundle[] {
  const days: DayBundle[] = [];
  for (let day = startOfDay(start); day <= end; day = addDays(day, 1)) {
    const dayEntries = entries.filter((e) => isSameDay(new Date(e.timestamp), day)).sort((a, b) => b.timestamp.localeCompare(a.timestamp));
    const water = waterEntries.filter((e) => isSameDay(new Date(e.date), day)).sort((a, b) => a.date.localeCompare(b.date));
    if (dayEntries.length === 0 && water.length === 0) continue;
    const groups = mealOrder
      .map((meal) => ({ meal, entries: dayEntries.filter((e) => e.mealType === meal) }))
      .filter((g) => g.entries.length > 0);
    days.push({ date: day, groups, water });
  }
  return days;
}

function totals(groups: DayBundle['groups']): { calories: number; protein: number; carbs: number; fat: number } {
  let calories = 0;
  let protein = 0;
  let carbs = 0;
  let fat = 0;
  for (const group of groups) {
    for (const entry of group.entries) {
      calories += entry.calories;
      protein += entry.protein;
      carbs += entry.carbs;
      fat += entry.fat;
    }
  }
  return { calories, protein, carbs, fat };
}

export function buildDiaryExport(input: {
  start: Date;
  end: Date;
  format: DiaryExportFormat;
  entries: readonly FoodEntry[];
  waterEntries?: readonly WaterEntry[];
  targets: DailyTargets;
}): DiaryExportBundle | undefined {
  const start = startOfDay(input.start <= input.end ? input.start : input.end);
  const end = startOfDay(input.start <= input.end ? input.end : input.start);
  const days = collectDays(start, end, input.entries, input.waterEntries ?? []);
  if (days.length === 0) return undefined;

  const text =
    input.format === 'json'
      ? jsonExport(days, start, end, input.targets)
      : input.format === 'markdown'
        ? markdownExport(days, start, end, input.targets)
        : csvExport(days);

  return {
    filename: `Fud-Food-Diary-${dayKey(start)}_to_${dayKey(end)}.${diaryExportExtension(input.format)}`,
    text,
  };
}

function jsonExport(days: DayBundle[], start: Date, end: Date, targets: DailyTargets): string {
  const dayDocs = days.map((bundle) => {
    const t = totals(bundle.groups);
    return {
      date: dayKey(bundle.date),
      totals: { calories: t.calories, protein_g: r1(t.protein), carbs_g: r1(t.carbs), fat_g: r1(t.fat) },
      targets: { calories: targets.calories, protein_g: targets.protein, carbs_g: targets.carbs, fat_g: targets.fat },
      remaining: {
        calories: Math.max(0, targets.calories - t.calories),
        protein_g: r1(Math.max(0, targets.protein - t.protein)),
        carbs_g: r1(Math.max(0, targets.carbs - t.carbs)),
        fat_g: r1(Math.max(0, targets.fat - t.fat)),
      },
      meals: bundle.groups.map((g) => ({
        type: g.meal,
        items: g.entries.map((e) => ({
          entry_id: e.id,
          name: e.name,
          quantity_g: e.servingSizeGrams === undefined ? null : r1(e.servingSizeGrams),
          calories: e.calories,
          protein_g: r1(e.protein),
          carbs_g: r1(e.carbs),
          fat_g: r1(e.fat),
          sugar_g: e.sugar === undefined ? null : r1(e.sugar),
          added_sugar_g: e.addedSugar === undefined ? null : r1(e.addedSugar),
          fiber_g: e.fiber === undefined ? null : r1(e.fiber),
          saturated_fat_g: e.saturatedFat === undefined ? null : r1(e.saturatedFat),
          monounsaturated_fat_g: e.monounsaturatedFat === undefined ? null : r1(e.monounsaturatedFat),
          polyunsaturated_fat_g: e.polyunsaturatedFat === undefined ? null : r1(e.polyunsaturatedFat),
          cholesterol_mg: e.cholesterol === undefined ? null : r1(e.cholesterol),
          caffeine_mg: e.caffeine === undefined ? null : r1(e.caffeine),
          sodium_mg: e.sodium === undefined ? null : r1(e.sodium),
          potassium_mg: e.potassium === undefined ? null : r1(e.potassium),
          supplemental_nutrients_g: Object.fromEntries(Object.entries(e.supplementalNutrients).map(([k, v]) => [k, r1(v)])),
          trans_fat_g: e.transFat === undefined ? null : r1(e.transFat),
          calcium_mg: e.calcium === undefined ? null : r1(e.calcium),
          iron_mg: e.iron === undefined ? null : r1(e.iron),
          magnesium_mg: e.magnesium === undefined ? null : r1(e.magnesium),
          zinc_mg: e.zinc === undefined ? null : r1(e.zinc),
          vitamin_a_mcg: e.vitaminA === undefined ? null : r1(e.vitaminA),
          vitamin_c_mg: e.vitaminC === undefined ? null : r1(e.vitaminC),
          vitamin_d_mcg: e.vitaminD === undefined ? null : r1(e.vitaminD),
          vitamin_b12_mcg: e.vitaminB12 === undefined ? null : r1(e.vitaminB12),
          vitamin_e_mg: e.vitaminE === undefined ? null : r1(e.vitaminE),
          vitamin_k_mcg: e.vitaminK === undefined ? null : r1(e.vitaminK),
          folate_mcg: e.folate === undefined ? null : r1(e.folate),
          omega3_g: e.omega3 === undefined ? null : r1(e.omega3),
          time: timeFmt(new Date(e.timestamp)),
          source: sourceLabel(e.source),
          note: e.customNote && e.customNote.length > 0 ? e.customNote : null,
          ingredients: e.ingredients.map((ingredient) => ({
            name: ingredient.name,
            quantity_g: r1(ingredient.grams),
            calories: ingredient.calories,
            protein_g: r1(ingredient.protein),
            carbs_g: r1(ingredient.carbs),
            fat_g: r1(ingredient.fat),
          })),
        })),
      })),
      water_entries: bundle.water.map((entry) => ({
        entry_id: entry.id,
        time: timeFmt(new Date(entry.date)),
        milliliters: entry.milliliters,
      })),
      water_total_ml: bundle.water.reduce((sum, entry) => sum + entry.milliliters, 0),
    };
  });

  return `${JSON.stringify(
    {
      export: {
        app: 'Fud AI',
        format_version: DIARY_EXPORT_FORMAT_VERSION,
        date_range: { start: dayKey(start), end: dayKey(end) },
      },
      days: dayDocs,
    },
    null,
    2,
  )}\n`;
}

function ingredientsText(entry: FoodEntry): string {
  return entry.ingredients
    .map((ingredient) => `${ingredient.name} (${r1(ingredient.grams)}g · ${ingredient.calories} kcal · P ${r1(ingredient.protein)}g · C ${r1(ingredient.carbs)}g · F ${r1(ingredient.fat)}g)`)
    .join('; ')
    .replace(/\|/g, '/');
}

function markdownExport(days: DayBundle[], start: Date, end: Date, targets: DailyTargets): string {
  let s = `# Food diary export\nDate range: ${dayKey(start)} to ${dayKey(end)}\nGenerated by Fud AI\n`;
  for (const bundle of days) {
    const t = totals(bundle.groups);
    s += `\n## ${dayKey(bundle.date)}\nTotals:\n`;
    s += `- Calories: ${t.calories} / ${targets.calories} kcal\n`;
    s += `- Protein: ${r1(t.protein)} / ${targets.protein} g\n`;
    s += `- Carbs: ${r1(t.carbs)} / ${targets.carbs} g\n`;
    s += `- Fat: ${r1(t.fat)} / ${targets.fat} g\n`;
    s += `- Water: ${bundle.water.reduce((sum, e) => sum + e.milliliters, 0)} ml\n`;
    if (bundle.water.length > 0) {
      s += '### Water\n| Time | Amount (ml) |\n|---|---:|\n';
      for (const entry of bundle.water) {
        s += `| ${timeFmt(new Date(entry.date))} | ${entry.milliliters} |\n`;
      }
    }
    for (const group of bundle.groups) {
      s += `### ${mealTypeDisplayName(group.meal)}\n`;
      s += '| Time | Food | Weight | Calories | Protein (g) | Carbs (g) | Fat (g) | Sugar (g) | Added sugar (g) | Fiber (g) | Saturated fat (g) | Monounsaturated fat (g) | Polyunsaturated fat (g) | Cholesterol (mg) | Caffeine (mg) | Sodium (mg) | Potassium (mg) | Trans fat (g) | Calcium (mg) | Iron (mg) | Magnesium (mg) | Zinc (mg) | Vitamin A (mcg) | Vitamin C (mg) | Vitamin D (mcg) | Vitamin B12 (mcg) | Vitamin E (mg) | Vitamin K (mcg) | Folate (mcg) | Omega-3 (g) | Source | Ingredients |\n';
      s += '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n';
      for (const e of group.entries) {
        const weight = e.servingSizeGrams !== undefined ? `${Math.trunc(e.servingSizeGrams)} g` : '-';
        const cells = [
          timeFmt(new Date(e.timestamp)),
          e.name.replace(/\|/g, '/'),
          weight,
          String(e.calories),
          String(r1(e.protein)),
          String(r1(e.carbs)),
          String(r1(e.fat)),
          optionalNumber(e.sugar, '-'),
          optionalNumber(e.addedSugar, '-'),
          optionalNumber(e.fiber, '-'),
          optionalNumber(e.saturatedFat, '-'),
          optionalNumber(e.monounsaturatedFat, '-'),
          optionalNumber(e.polyunsaturatedFat, '-'),
          optionalNumber(e.cholesterol, '-'),
          optionalNumber(e.caffeine, '-'),
          optionalNumber(e.sodium, '-'),
          optionalNumber(e.potassium, '-'),
          optionalNumber(e.transFat, '-'),
          optionalNumber(e.calcium, '-'),
          optionalNumber(e.iron, '-'),
          optionalNumber(e.magnesium, '-'),
          optionalNumber(e.zinc, '-'),
          optionalNumber(e.vitaminA, '-'),
          optionalNumber(e.vitaminC, '-'),
          optionalNumber(e.vitaminD, '-'),
          optionalNumber(e.vitaminB12, '-'),
          optionalNumber(e.vitaminE, '-'),
          optionalNumber(e.vitaminK, '-'),
          optionalNumber(e.folate, '-'),
          optionalNumber(e.omega3, '-'),
          sourceLabel(e.source),
          ingredientsText(e),
        ];
        s += `| ${cells.join(' | ')} |\n`;
      }
    }
  }
  return s;
}

/** Neutralize spreadsheet formula injection (`=`, `+`, `-`, `@`) and quote CSV specials. */
export function csvEscape(field: string): string {
  const formula = /^[=+\-@\t\r]/.test(field);
  const safe = formula ? `'${field}` : field;
  if (safe.includes(',') || safe.includes('"') || safe.includes('\n') || formula) {
    return `"${safe.replace(/"/g, '""')}"`;
  }
  return safe;
}

function csvExport(days: DayBundle[]): string {
  let s =
    'date,meal,time,food,weight_g,calories,protein_g,carbs_g,fat_g,sugar_g,added_sugar_g,fiber_g,saturated_fat_g,monounsaturated_fat_g,polyunsaturated_fat_g,cholesterol_mg,caffeine_mg,sodium_mg,potassium_mg,trans_fat_g,calcium_mg,iron_mg,magnesium_mg,zinc_mg,vitamin_a_mcg,vitamin_c_mg,vitamin_d_mcg,vitamin_b12_mcg,vitamin_e_mg,vitamin_k_mcg,folate_mcg,omega3_g,source,note,ingredients,entry_type,water_ml,water_total_ml\n';
  for (const bundle of days) {
    const date = dayKey(bundle.date);
    for (const group of bundle.groups) {
      for (const e of group.entries) {
        const cols = [
          date,
          group.meal,
          timeFmt(new Date(e.timestamp)),
          e.name,
          e.servingSizeGrams === undefined ? '' : String(Math.trunc(e.servingSizeGrams)),
          String(e.calories),
          String(r1(e.protein)),
          String(r1(e.carbs)),
          String(r1(e.fat)),
          optionalNumber(e.sugar),
          optionalNumber(e.addedSugar),
          optionalNumber(e.fiber),
          optionalNumber(e.saturatedFat),
          optionalNumber(e.monounsaturatedFat),
          optionalNumber(e.polyunsaturatedFat),
          optionalNumber(e.cholesterol),
          optionalNumber(e.caffeine),
          optionalNumber(e.sodium),
          optionalNumber(e.potassium),
          optionalNumber(e.transFat),
          optionalNumber(e.calcium),
          optionalNumber(e.iron),
          optionalNumber(e.magnesium),
          optionalNumber(e.zinc),
          optionalNumber(e.vitaminA),
          optionalNumber(e.vitaminC),
          optionalNumber(e.vitaminD),
          optionalNumber(e.vitaminB12),
          optionalNumber(e.vitaminE),
          optionalNumber(e.vitaminK),
          optionalNumber(e.folate),
          optionalNumber(e.omega3),
          sourceLabel(e.source),
          e.customNote ?? '',
          ingredientsText(e),
          'food',
          '',
          '',
        ];
        s += `${cols.map(csvEscape).join(',')}\n`;
      }
    }
    const total = bundle.water.reduce((sum, entry) => sum + entry.milliliters, 0);
    for (const entry of bundle.water) {
      const cols = Array<string>(38).fill('');
      cols[0] = date;
      cols[2] = timeFmt(new Date(entry.date));
      cols[3] = 'Water';
      cols[35] = 'water';
      cols[36] = String(entry.milliliters);
      s += `${cols.map(csvEscape).join(',')}\n`;
    }
    const summary = Array<string>(38).fill('');
    summary[0] = date;
    summary[35] = 'water_total';
    summary[37] = String(total);
    s += `${summary.join(',')}\n`;
  }
  return s;
}
