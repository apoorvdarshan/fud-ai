/**
 * Import of Fud AI diary JSON (`format_version` 1.x). Port of `DiaryImporter.swift`.
 */

import { dateFromDayKey, dayKey, startOfDay } from '../dates';
import { mealTypes, type FoodEntry, type MealIngredient, type MealType } from '../food/food';
import type { WaterEntry } from '../water/water';

export const DIARY_IMPORT_MAX_BYTES = 20 * 1_024 * 1_024;

export type DiaryImportMode = 'replaceDateRange' | 'addAsNew';

export type DiaryImportErrorKind =
  | 'fileTooLarge'
  | 'invalidDocument'
  | 'unsupportedDocument'
  | 'noEntries'
  | 'invalidDate'
  | 'invalidMeal'
  | 'invalidEntry'
  | 'invalidWaterEntry';

export class DiaryImportError extends Error {
  readonly kind: DiaryImportErrorKind;

  constructor(kind: DiaryImportErrorKind, detail?: string) {
    super(messageFor(kind, detail));
    this.name = 'DiaryImportError';
    this.kind = kind;
  }
}

function messageFor(kind: DiaryImportErrorKind, detail?: string): string {
  switch (kind) {
    case 'fileTooLarge':
      return 'This file is too large to import.';
    case 'invalidDocument':
      return 'This is not a valid Fud AI food diary JSON file.';
    case 'unsupportedDocument':
      return 'This food diary format is not supported.';
    case 'noEntries':
      return 'The selected diary does not contain any food or water entries.';
    case 'invalidDate':
      return `The diary contains an invalid date or time: ${detail ?? ''}.`;
    case 'invalidMeal':
      return `The diary contains an unknown meal type: ${detail ?? ''}.`;
    case 'invalidWaterEntry':
      return 'The diary contains an invalid water entry.';
    case 'invalidEntry':
      return `The diary contains an invalid food entry: ${detail ?? ''}.`;
  }
}

export interface DiaryImportPreview {
  entries: FoodEntry[];
  startDate: Date;
  endDate: Date;
  waterEntries: WaterEntry[];
  includesWater: boolean;
}

interface Document {
  export: { app: string; format_version: string; date_range: { start: string; end: string } };
  days: Day[];
}

interface Day {
  date: string;
  meals: { type: string; items: Item[] }[];
  water_entries?: { entry_id: string; time: string; milliliters: number }[];
}

interface Item {
  entry_id?: string;
  name: string;
  quantity_g?: number;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  sugar_g?: number;
  added_sugar_g?: number;
  fiber_g?: number;
  saturated_fat_g?: number;
  monounsaturated_fat_g?: number;
  polyunsaturated_fat_g?: number;
  cholesterol_mg?: number;
  caffeine_mg?: number;
  sodium_mg?: number;
  potassium_mg?: number;
  supplemental_nutrients_g?: Record<string, number>;
  trans_fat_g?: number;
  calcium_mg?: number;
  iron_mg?: number;
  magnesium_mg?: number;
  zinc_mg?: number;
  vitamin_a_mcg?: number;
  vitamin_c_mg?: number;
  vitamin_d_mcg?: number;
  vitamin_b12_mcg?: number;
  vitamin_e_mg?: number;
  vitamin_k_mcg?: number;
  folate_mcg?: number;
  omega3_g?: number;
  time: string;
  source: string;
  note?: string;
  ingredients?: {
    name: string;
    quantity_g: number;
    calories: number;
    protein_g: number;
    carbs_g: number;
    fat_g: number;
  }[];
}

export function parseDiaryImport(data: string | Uint8Array, makeId: () => string = fallbackId): DiaryImportPreview {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  if (bytes.byteLength > DIARY_IMPORT_MAX_BYTES) throw new DiaryImportError('fileTooLarge');

  let document: Document;
  try {
    document = JSON.parse(typeof data === 'string' ? data : new TextDecoder().decode(data)) as Document;
  } catch {
    throw new DiaryImportError('invalidDocument');
  }

  if (!document?.export || typeof document.export.app !== 'string' || document.export.app.toLowerCase() !== 'fud ai') {
    throw new DiaryImportError('invalidDocument');
  }
  const major = Number.parseInt(String(document.export.format_version).split('.')[0] ?? '', 10);
  if (major !== 1) throw new DiaryImportError('unsupportedDocument');

  const start = parseDay(document.export.date_range.start);
  const end = parseDay(document.export.date_range.end);
  const lowerBound = start <= end ? start : end;
  const upperBound = start <= end ? end : start;

  const entries: FoodEntry[] = [];
  const waterEntries: WaterEntry[] = [];
  for (const day of document.days ?? []) {
    const date = parseDay(day.date);
    if (date < lowerBound || date > upperBound) throw new DiaryImportError('invalidDate', day.date);
    for (const water of day.water_entries ?? []) {
      if (!Number.isInteger(water.milliliters) || water.milliliters <= 0 || water.milliliters > 2_147_483_647 || !isUuid(water.entry_id)) {
        throw new DiaryImportError('invalidWaterEntry');
      }
      const timestamp = parseTimestamp(day.date, water.time);
      if (dayKey(timestamp) !== day.date || timeKey(timestamp) !== water.time) {
        throw new DiaryImportError('invalidDate', `${day.date} ${water.time}`);
      }
      waterEntries.push({ id: water.entry_id, date: timestamp.toISOString(), milliliters: water.milliliters });
    }
    for (const meal of day.meals ?? []) {
      const mealType = meal.type.toLowerCase() as MealType;
      if (!(mealTypes as readonly string[]).includes(mealType)) throw new DiaryImportError('invalidMeal', meal.type);
      for (const item of meal.items ?? []) {
        validateItem(item);
        const timestamp = parseTimestamp(day.date, item.time);
        if (dayKey(timestamp) !== day.date || timeKey(timestamp) !== item.time) {
          throw new DiaryImportError('invalidDate', `${day.date} ${item.time}`);
        }
        const ingredients: MealIngredient[] = (item.ingredients ?? []).map((ingredient, index) => {
          if (
            !isNonNegative(ingredient.quantity_g) ||
            ingredient.calories < 0 ||
            !isNonNegative(ingredient.protein_g) ||
            !isNonNegative(ingredient.carbs_g) ||
            !isNonNegative(ingredient.fat_g) ||
            !ingredient.name.trim()
          ) {
            throw new DiaryImportError('invalidEntry', item.name);
          }
          return {
            id: `${item.entry_id ?? 'ing'}-${index}`,
            name: ingredient.name,
            grams: ingredient.quantity_g,
            calories: ingredient.calories,
            protein: ingredient.protein_g,
            carbs: ingredient.carbs_g,
            fat: ingredient.fat_g,
          };
        });
        entries.push({
          id: item.entry_id && isUuid(item.entry_id) ? item.entry_id : makeId(),
          name: item.name.trim(),
          calories: item.calories,
          protein: item.protein_g,
          carbs: item.carbs_g,
          fat: item.fat_g,
          timestamp: timestamp.toISOString(),
          additionalImageFilenames: [],
          source: item.source === 'manually_edited' ? 'manual' : 'textInput',
          mealType,
          sugar: optionalNumber(item.sugar_g),
          addedSugar: optionalNumber(item.added_sugar_g),
          fiber: optionalNumber(item.fiber_g),
          saturatedFat: optionalNumber(item.saturated_fat_g),
          monounsaturatedFat: optionalNumber(item.monounsaturated_fat_g),
          polyunsaturatedFat: optionalNumber(item.polyunsaturated_fat_g),
          cholesterol: optionalNumber(item.cholesterol_mg),
          caffeine: optionalNumber(item.caffeine_mg),
          supplementalNutrients: item.supplemental_nutrients_g ?? {},
          sodium: optionalNumber(item.sodium_mg),
          potassium: optionalNumber(item.potassium_mg),
          transFat: optionalNumber(item.trans_fat_g),
          calcium: optionalNumber(item.calcium_mg),
          iron: optionalNumber(item.iron_mg),
          magnesium: optionalNumber(item.magnesium_mg),
          zinc: optionalNumber(item.zinc_mg),
          vitaminA: optionalNumber(item.vitamin_a_mcg),
          vitaminC: optionalNumber(item.vitamin_c_mg),
          vitaminD: optionalNumber(item.vitamin_d_mcg),
          vitaminB12: optionalNumber(item.vitamin_b12_mcg),
          vitaminE: optionalNumber(item.vitamin_e_mg),
          vitaminK: optionalNumber(item.vitamin_k_mcg),
          folate: optionalNumber(item.folate_mcg),
          omega3: optionalNumber(item.omega3_g),
          servingSizeGrams: optionalNumber(item.quantity_g),
          servingUnitOptions: [],
          customNote: item.note,
          progressiveMeal: false,
          ingredients,
        });
      }
    }
  }
  if (entries.length === 0 && waterEntries.length === 0) throw new DiaryImportError('noEntries');
  return {
    entries,
    startDate: lowerBound,
    endDate: upperBound,
    waterEntries,
    includesWater: (document.days ?? []).some((day) => day.water_entries != null),
  };
}

export function applyDiaryImport(
  preview: DiaryImportPreview,
  existing: readonly FoodEntry[],
  mode: DiaryImportMode,
  makeId: () => string = fallbackId,
): FoodEntry[] {
  if (mode === 'addAsNew') {
    return [...existing, ...preview.entries.map((imported) => entryFrom(imported, undefined, makeId()))];
  }

  const outsideRange = existing.filter((e) => {
    const day = startOfDay(new Date(e.timestamp));
    return day < preview.startDate || day > preview.endDate;
  });
  const inRange = existing.filter((e) => {
    const day = startOfDay(new Date(e.timestamp));
    return day >= preview.startDate && day <= preview.endDate;
  });

  const existingByID = new Map<string, FoodEntry>();
  for (const entry of inRange) existingByID.set(entry.id, entry);
  const existingByKey = new Map<string, FoodEntry[]>();
  for (const entry of inRange) {
    const key = matchKey(entry);
    const list = existingByKey.get(key) ?? [];
    list.push(entry);
    existingByKey.set(key, list);
  }
  const usedIDs = new Set<string>();
  const occupiedIDs = new Set(outsideRange.map((e) => e.id));

  const imported = preview.entries.map((item) => {
    let match: FoodEntry | undefined;
    const byId = existingByID.get(item.id);
    if (byId && !usedIDs.has(byId.id)) {
      match = byId;
    } else {
      const key = matchKey(item);
      const bucket = existingByKey.get(key) ?? [];
      while (bucket[0] && usedIDs.has(bucket[0].id)) bucket.shift();
      if (bucket[0]) {
        match = bucket.shift();
        existingByKey.set(key, bucket);
      }
    }
    if (match) {
      usedIDs.add(match.id);
      occupiedIDs.add(match.id);
      existingByID.delete(match.id);
      return entryFrom(item, match, match.id);
    }
    const desiredID = occupiedIDs.has(item.id) ? makeId() : item.id;
    occupiedIDs.add(desiredID);
    return entryFrom(item, undefined, desiredID);
  });
  return [...outsideRange, ...imported];
}

/** Foods Health should delete, then write. Preserved IDs are deleted first so iOS does not append a second sample. */
export function diaryImportHealthReconcile(
  preview: DiaryImportPreview,
  existing: readonly FoodEntry[],
  next: readonly FoodEntry[],
  mode: DiaryImportMode,
): { deleteIds: string[]; writeEntries: FoodEntry[] } {
  if (mode === 'addAsNew') {
    const existingIds = new Set(existing.map((entry) => entry.id));
    return { deleteIds: [], writeEntries: next.filter((entry) => !existingIds.has(entry.id)) };
  }
  const inRange = (iso: string) => {
    const day = startOfDay(new Date(iso));
    return day >= preview.startDate && day <= preview.endDate;
  };
  return {
    deleteIds: [...new Set(existing.filter((entry) => inRange(entry.timestamp)).map((entry) => entry.id))],
    writeEntries: next.filter((entry) => inRange(entry.timestamp)),
  };
}

export function applyWaterImport(
  preview: DiaryImportPreview,
  existing: readonly WaterEntry[],
  mode: DiaryImportMode,
  makeId: () => string = fallbackId,
): WaterEntry[] {
  if (!preview.includesWater) return [...existing];
  const retained =
    mode === 'addAsNew'
      ? [...existing]
      : existing.filter((e) => {
          const day = startOfDay(new Date(e.date));
          return day < preview.startDate || day > preview.endDate;
        });
  const occupied = new Set(retained.map((e) => e.id));
  const imported = preview.waterEntries.map((entry) => {
    const id = mode === 'addAsNew' || occupied.has(entry.id) ? makeId() : entry.id;
    occupied.add(id);
    return { ...entry, id };
  });
  return [...retained, ...imported];
}

function entryFrom(imported: FoodEntry, old: FoodEntry | undefined, id: string): FoodEntry {
  return {
    ...imported,
    id,
    imageFilename: old?.imageFilename,
    additionalImageFilenames: old?.additionalImageFilenames ?? [],
    emoji: old?.emoji,
    servingUnitOptions: old?.servingUnitOptions ?? [],
    selectedServingUnit: old?.selectedServingUnit,
    selectedServingQuantity: old?.selectedServingQuantity,
    progressiveMeal: old?.progressiveMeal ?? false,
  };
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function validateItem(item: Item): void {
  const name = item.name.trim();
  const required = [item.protein_g, item.carbs_g, item.fat_g];
  const optional = [
    item.quantity_g,
    item.sugar_g,
    item.added_sugar_g,
    item.fiber_g,
    item.saturated_fat_g,
    item.monounsaturated_fat_g,
    item.polyunsaturated_fat_g,
    item.cholesterol_mg,
    item.caffeine_mg,
    item.sodium_mg,
    item.potassium_mg,
    item.trans_fat_g,
    item.calcium_mg,
    item.iron_mg,
    item.magnesium_mg,
    item.zinc_mg,
    item.vitamin_a_mcg,
    item.vitamin_c_mg,
    item.vitamin_d_mcg,
    item.vitamin_b12_mcg,
    item.vitamin_e_mg,
    item.vitamin_k_mcg,
    item.folate_mcg,
    item.omega3_g,
  ].map(optionalNumber).filter((value): value is number => value !== undefined);
  const supplements = Object.values(item.supplemental_nutrients_g ?? {});
  if (!name || item.calories < 0 || !required.every(isNonNegative) || !optional.every(isNonNegative) || !supplements.every(isNonNegative)) {
    throw new DiaryImportError('invalidEntry', name || 'Unnamed food');
  }
}

function isNonNegative(value: number): boolean {
  return Number.isFinite(value) && value >= 0;
}

function parseDay(value: string): Date {
  const parts = value.split('-').map((part) => Number.parseInt(part, 10));
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) throw new DiaryImportError('invalidDate', value);
  const date = dateFromDayKey(value);
  if (dayKey(date) !== value) throw new DiaryImportError('invalidDate', value);
  return startOfDay(date);
}

function parseTimestamp(day: string, time: string): Date {
  const dayParts = day.split('-').map((part) => Number.parseInt(part, 10));
  const timeParts = time.split(':').map((part) => Number.parseInt(part, 10));
  if (dayParts.length !== 3 || timeParts.length !== 2 || [...dayParts, ...timeParts].some((part) => !Number.isFinite(part))) {
    throw new DiaryImportError('invalidDate', `${day} ${time}`);
  }
  return new Date(dayParts[0]!, dayParts[1]! - 1, dayParts[2]!, timeParts[0], timeParts[1], 0, 0);
}

function timeKey(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function matchKey(entry: FoodEntry): string {
  const date = new Date(entry.timestamp);
  return `${dayKey(date)} ${timeKey(date)}|${entry.mealType}|${entry.name.trim().toLowerCase()}`;
}

function isUuid(value: string | undefined): boolean {
  return !!value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

let fallbackCounter = 0;
function fallbackId(): string {
  fallbackCounter += 1;
  return `imported-${fallbackCounter}`;
}
