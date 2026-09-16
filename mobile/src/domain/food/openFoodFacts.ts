/**
 * Open Food Facts barcode lookup. Pure port of `OpenFoodFactsService.swift` so the shared app
 * uses the same request shape, nutrition scaling, and user-facing errors as iOS.
 */

import type { FoodAnalysis } from './analysis';
import type { ServingUnitOption } from './food';

export const barcodeLookupErrorKinds = [
  'invalidBarcode',
  'productNotFound',
  'missingNutrition',
  'rateLimited',
  'serviceUnavailable',
  'invalidResponse',
  'offline',
  'timeout',
  'networkError',
] as const;

export type BarcodeLookupErrorKind = (typeof barcodeLookupErrorKinds)[number];

export const barcodeLookupMessages: Record<BarcodeLookupErrorKind, string> = {
  invalidBarcode: 'That barcode could not be read. Try scanning it again.',
  productNotFound: 'Product not found in Open Food Facts. Scan the nutrition label instead.',
  missingNutrition:
    'This barcode was found, but nutrition data is incomplete. Scan the nutrition label instead.',
  rateLimited: 'Too many barcode lookups. Wait a moment and try again.',
  serviceUnavailable: 'Open Food Facts is temporarily unavailable. Try again later.',
  invalidResponse: 'Open Food Facts returned an unexpected response.',
  offline: 'You appear to be offline. Check your connection and try again.',
  timeout: 'Open Food Facts took too long to respond. Try scanning again.',
  networkError: 'Barcode lookup failed. Check your connection and try again.',
};

export class BarcodeLookupError extends Error {
  readonly kind: BarcodeLookupErrorKind;
  readonly offersScanLabel: boolean;

  constructor(kind: BarcodeLookupErrorKind) {
    super(barcodeLookupMessages[kind]);
    this.name = 'BarcodeLookupError';
    this.kind = kind;
    this.offersScanLabel = kind === 'productNotFound' || kind === 'missingNutrition';
  }
}

export const OPEN_FOOD_FACTS_FIELDS = [
  'product_name',
  'generic_name',
  'brands',
  'quantity',
  'product_quantity',
  'product_quantity_unit',
  'serving_size',
  'serving_quantity',
  'nutriments',
  'ingredients_text',
  'allergens_tags',
  'traces_tags',
  'nutriscore_grade',
  'nova_group',
  'ecoscore_grade',
  'labels_tags',
  'categories_tags',
  'image_front_url',
].join(',');

const MAX_BARCODE_UTF8 = 24;
const ENERGY_KJ_TO_KCAL = 0.23900573614;

/** Digits-only, ≤24 UTF-8 bytes — rejected before any network call. */
export function sanitizeBarcode(raw: string): string | undefined {
  const code = raw.trim();
  if (!code || code.length > MAX_BARCODE_UTF8) return undefined;
  if (![...code].every((ch) => ch >= '0' && ch <= '9')) return undefined;
  return code;
}

export function openFoodFactsUserAgent(version = '7.1'): string {
  return `FudAI/${version} (https://fud-ai.app)`;
}

export function openFoodFactsRequestURL(barcode: string, languageCode?: string): string | undefined {
  const code = sanitizeBarcode(barcode);
  if (!code) return undefined;
  const params = new URLSearchParams({ fields: OPEN_FOOD_FACTS_FIELDS });
  if (languageCode) params.set('lc', languageCode);
  return `https://world.openfoodfacts.org/api/v2/product/${code}.json?${params.toString()}`;
}

export interface OpenFoodFactsLookupDeps {
  fetch: typeof fetch;
  languageCode?: string;
  version?: string;
  /** Abort / timeout. Callers must never hang forever. */
  signal?: AbortSignal;
}

export async function lookupOpenFoodFacts(
  barcode: string,
  deps: OpenFoodFactsLookupDeps,
): Promise<FoodAnalysis> {
  const url = openFoodFactsRequestURL(barcode, deps.languageCode);
  if (!url) throw new BarcodeLookupError('invalidBarcode');
  const code = sanitizeBarcode(barcode)!;

  let response: Response;
  try {
    response = await deps.fetch(url, {
      headers: {
        'User-Agent': openFoodFactsUserAgent(deps.version),
        Accept: 'application/json',
      },
      signal: deps.signal,
    });
  } catch (error) {
    throw classifyBarcodeNetworkError(error);
  }

  return analysisFromHttpResponse(response.status, await readBody(response), code);
}

async function readBody(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    throw new BarcodeLookupError('invalidResponse');
  }
}

/** Map HTTP + JSON body to a FoodAnalysis, matching the native status-code table. */
export function analysisFromHttpResponse(status: number, body: string, barcode: string): FoodAnalysis {
  if (status === 404) throw new BarcodeLookupError('productNotFound');
  if (status === 429) throw new BarcodeLookupError('rateLimited');
  if (status >= 500 && status < 600) throw new BarcodeLookupError('serviceUnavailable');
  if (status < 200 || status >= 300) throw new BarcodeLookupError('invalidResponse');

  let decoded: OpenFoodFactsResponse;
  try {
    decoded = JSON.parse(body) as OpenFoodFactsResponse;
  } catch {
    throw new BarcodeLookupError('invalidResponse');
  }
  if (decoded.status === 0 || !decoded.product) throw new BarcodeLookupError('productNotFound');
  return analysisFromProduct(decoded.product, barcode);
}

export function classifyBarcodeNetworkError(error: unknown): BarcodeLookupError {
  if (error instanceof BarcodeLookupError) return error;
  if (isAbortError(error)) {
    const abort = new BarcodeLookupError('timeout');
    return abort;
  }
  const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  if (message.includes('network request failed') || message.includes('failed to fetch') || message.includes('offline') || message.includes('not connected')) {
    return new BarcodeLookupError('offline');
  }
  if (message.includes('timeout') || message.includes('timed out')) {
    return new BarcodeLookupError('timeout');
  }
  return new BarcodeLookupError('networkError');
}

function isAbortError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const name = 'name' in error ? String(error.name) : '';
  return name === 'AbortError' || name === 'TimeoutError';
}

interface OpenFoodFactsResponse {
  status?: number;
  product?: OpenFoodFactsProduct;
}

interface OpenFoodFactsProduct {
  product_name?: string;
  generic_name?: string;
  brands?: string;
  serving_size?: string;
  serving_quantity?: unknown;
  nutriments?: Record<string, unknown>;
  quantity?: string;
  product_quantity?: unknown;
  product_quantity_unit?: string;
  ingredients_text?: string;
  allergens_tags?: string[];
  traces_tags?: string[];
  nutriscore_grade?: string;
  nova_group?: unknown;
  ecoscore_grade?: string;
  labels_tags?: string[];
  categories_tags?: string[];
  image_front_url?: string;
}

export function analysisFromProduct(product: OpenFoodFactsProduct, barcode: string): FoodAnalysis {
  if (!product.nutriments) throw new BarcodeLookupError('missingNutrition');

  const servingGrams = Math.max(flexibleNumber(product.serving_quantity) ?? gramsFromServingSize(product.serving_size) ?? 100, 1);
  const scale = servingGrams / 100;
  const nutriments = product.nutriments;

  const energyKcal = servingValue('energy-kcal', nutriments, scale);
  const energyKj = servingValue('energy', nutriments, scale);
  const calories = energyKcal ?? (energyKj !== undefined ? energyKj * ENERGY_KJ_TO_KCAL : undefined);
  const protein = servingValue('proteins', nutriments, scale);
  const carbs = servingValue('carbohydrates', nutriments, scale);
  const fat = servingValue('fat', nutriments, scale);

  if (calories === undefined && protein === undefined && carbs === undefined && fat === undefined) {
    throw new BarcodeLookupError('missingNutrition');
  }

  const servingOption: ServingUnitOption = { unit: 'serving', gramsPerUnit: servingGrams, quantity: 1 };
  const servingUnitOptions: ServingUnitOption[] = [servingOption];
  const packageGrams = packageGramsFromProduct(product);
  if (packageGrams !== undefined && Math.abs(packageGrams - servingGrams) > 0.01) {
    servingUnitOptions.push({ unit: 'package', gramsPerUnit: packageGrams, quantity: 1 });
  }

  return {
    name: productName(product, barcode),
    calories: Math.round(calories ?? 0),
    protein: protein ?? 0,
    carbs: carbs ?? 0,
    fat: fat ?? 0,
    servingSizeGrams: servingGrams,
    emoji: '🏷️',
    servingUnitOptions,
    selectedServingUnit: servingOption.unit,
    selectedServingQuantity: 1,
    servingSizeIsKnown: true,
    requiresServingUnitFallback: false,
    progressiveMeal: false,
    ingredients: [],
    sugar: rounded(servingValue('sugars', nutriments, scale)),
    addedSugar: rounded(servingValue('added-sugars', nutriments, scale)),
    fiber: rounded(servingValue('fiber', nutriments, scale)),
    saturatedFat: rounded(servingValue('saturated-fat', nutriments, scale)),
    monounsaturatedFat: rounded(servingValue('monounsaturated-fat', nutriments, scale)),
    polyunsaturatedFat: rounded(servingValue('polyunsaturated-fat', nutriments, scale)),
    cholesterol: milligrams(servingValue('cholesterol', nutriments, scale)),
    caffeine: milligrams(servingValue('caffeine', nutriments, scale)),
    supplementalNutrients: {},
    sodium: milligrams(servingValue('sodium', nutriments, scale)),
    potassium: milligrams(servingValue('potassium', nutriments, scale)),
    transFat: rounded(servingValue('trans-fat', nutriments, scale)),
    calcium: milligrams(servingValue('calcium', nutriments, scale)),
    iron: milligrams(servingValue('iron', nutriments, scale)),
    magnesium: milligrams(servingValue('magnesium', nutriments, scale)),
    zinc: milligrams(servingValue('zinc', nutriments, scale)),
    vitaminA: micrograms(servingValue('vitamin-a', nutriments, scale)),
    vitaminC: milligrams(servingValue('vitamin-c', nutriments, scale)),
    vitaminD: micrograms(servingValue('vitamin-d', nutriments, scale)),
    vitaminB12: micrograms(servingValue('vitamin-b12', nutriments, scale)),
    vitaminE: milligrams(servingValue('vitamin-e', nutriments, scale)),
    vitaminK: micrograms(servingValue('vitamin-k', nutriments, scale)),
    folate: micrograms(servingValue('folates', nutriments, scale)),
    omega3: rounded(servingValue('omega-3-fat', nutriments, scale)),
  };
}

function servingValue(key: string, nutriments: Record<string, unknown>, scale: number): number | undefined {
  const serving = flexibleNumber(nutriments[`${key}_serving`]);
  if (serving !== undefined) return serving;
  const per100 = flexibleNumber(nutriments[`${key}_100g`]);
  if (per100 !== undefined) return per100 * scale;
  return undefined;
}

export function productName(product: Pick<OpenFoodFactsProduct, 'product_name' | 'generic_name' | 'brands'>, barcode: string): string {
  const primary = firstNonEmpty(product.product_name, product.generic_name);
  const brand = product.brands
    ?.split(',')
    .map((part) => part.trim())
    .find((part) => part.length > 0);
  if (primary && brand && !primary.toLowerCase().includes(brand.toLowerCase())) {
    return `${brand} ${primary}`;
  }
  return primary ?? brand ?? `Barcode ${barcode}`;
}

function firstNonEmpty(...values: Array<string | undefined>): string | undefined {
  for (const value of values) {
    const trimmed = value?.trim();
    if (trimmed) return trimmed;
  }
  return undefined;
}

function packageGramsFromProduct(product: OpenFoodFactsProduct): number | undefined {
  const value = flexibleNumber(product.product_quantity);
  const unit = product.product_quantity_unit?.toLowerCase();
  if (value !== undefined && unit) {
    switch (unit) {
      case 'kg':
        return value * 1_000;
      case 'mg':
        return value / 1_000;
      case 'g':
        return value;
      case 'l':
        return value * 1_000;
      case 'ml':
        return value;
      default:
        break;
    }
  }
  const quantity = product.quantity?.trim();
  if (!quantity || !/^[0-9]+(?:[.,][0-9]+)?\s*(?:kg|mg|g|oz|ml|l)$/i.test(quantity)) return undefined;
  return gramsFromServingSize(quantity);
}

export function displayTags(tags: string[] | undefined, limit: number): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of tags ?? []) {
    const withoutLanguage = raw.replace(/^[a-z]{2}:/, '');
    const display = withoutLanguage.replace(/[-_]/g, ' ').trim();
    if (!display) continue;
    const key = display.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(capitalizeWords(display));
    if (result.length === limit) break;
  }
  return result;
}

function capitalizeWords(value: string): string {
  return value.replace(/\b[\p{L}\p{N}]/gu, (ch) => ch.toUpperCase());
}

function rounded(value: number | undefined): number | undefined {
  return value === undefined ? undefined : Math.round(value * 10) / 10;
}

function milligrams(grams: number | undefined): number | undefined {
  return grams === undefined ? undefined : Math.round(grams * 1000 * 10) / 10;
}

function micrograms(grams: number | undefined): number | undefined {
  return grams === undefined ? undefined : Math.round(grams * 1_000_000 * 10) / 10;
}

export function gramsFromServingSize(servingSize: string | undefined): number | undefined {
  if (!servingSize) return undefined;
  let text = servingSize.toLowerCase().replace(/,/g, '.').replace(/fl\.\s*oz/g, 'fl oz');
  const match = text.match(/([0-9]+(?:\.[0-9]+)?)\s*(fl oz|kg|mg|g|oz|ml|l)/);
  if (!match) return undefined;
  const value = Number(match[1]);
  if (!Number.isFinite(value)) return undefined;
  switch (match[2]) {
    case 'kg':
      return value * 1000;
    case 'mg':
      return value / 1000;
    case 'oz':
      return value * 28.3495;
    case 'fl oz':
      return value * 29.5735;
    case 'ml':
      return value;
    case 'l':
      return value * 1000;
    default:
      return value;
  }
}

function flexibleNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    const parsed = Number(value.trim().replace(',', '.'));
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}
