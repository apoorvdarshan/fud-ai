import { describe, expect, it } from 'vitest';

import {
  analysisFromHttpResponse,
  analysisFromProduct,
  BarcodeLookupError,
  displayTags,
  gramsFromServingSize,
  openFoodFactsRequestURL,
  productName,
  sanitizeBarcode,
} from '../src/domain/food/openFoodFacts';

describe('Open Food Facts barcode lookup', () => {
  it('keeps leading zeros in the request path and rejects unsafe codes', () => {
    expect(openFoodFactsRequestURL('  0012345678901\n')).toContain('/0012345678901.json?');
    expect(sanitizeBarcode('123/456')).toBeUndefined();
    expect(sanitizeBarcode('ABC123')).toBeUndefined();
    expect(sanitizeBarcode('١٢٣٤٥٦٧٨')).toBeUndefined();
    expect(sanitizeBarcode('1'.repeat(25))).toBeUndefined();
  });

  it('scales 100g nutrients to the serving and keeps package as a second unit', () => {
    const analysis = analysisFromHttpResponse(
      200,
      JSON.stringify({
        status: 1,
        product: {
          product_name: 'Test Product',
          quantity: '250 g',
          serving_quantity: 50,
          nutriments: {
            'energy-kcal_100g': 200,
            proteins_100g: 10,
            carbohydrates_100g: 30,
            fat_100g: 5,
          },
        },
      }),
      '0012345678901',
    );
    expect(analysis.name).toBe('Test Product');
    expect(analysis.calories).toBe(100);
    expect(analysis.protein).toBe(5);
    expect(analysis.carbs).toBe(15);
    expect(analysis.fat).toBe(2.5);
    expect(analysis.servingSizeGrams).toBe(50);
    expect(analysis.servingUnitOptions.map((o) => o.unit)).toEqual(['serving', 'package']);
    expect(analysis.emoji).toBe('🏷️');
  });

  it('maps HTTP and body failures to the native user-facing errors', () => {
    const expectKind = (status: number, body: string, kind: string) => {
      try {
        analysisFromHttpResponse(status, body, '0123456789012');
        throw new Error(`expected ${kind}`);
      } catch (error) {
        expect(error).toBeInstanceOf(BarcodeLookupError);
        expect((error as BarcodeLookupError).kind).toBe(kind);
      }
    };
    expectKind(404, '{"status":0}', 'productNotFound');
    expectKind(200, '{"status":0}', 'productNotFound');
    expectKind(429, '{"status":0}', 'rateLimited');
    expectKind(503, '{"status":0}', 'serviceUnavailable');
    expectKind(200, '{"status":1,"product":{"product_name":"Incomplete Product"}}', 'missingNutrition');
    expectKind(200, 'not-json', 'invalidResponse');
  });

  it('builds a branded fallback name and display tags', () => {
    expect(productName({ product_name: 'Yogurt', brands: 'Fage' }, '1')).toBe('Fage Yogurt');
    expect(productName({ brands: 'Chobani' }, '99')).toBe('Chobani');
    expect(productName({}, '001')).toBe('Barcode 001');
    expect(displayTags(['en:milk', 'en:nuts', 'en:milk'], 16)).toEqual(['Milk', 'Nuts']);
  });

  it('parses serving-size grams', () => {
    expect(gramsFromServingSize('30 g')).toBe(30);
    expect(gramsFromServingSize('1.5 oz')).toBeCloseTo(42.52425, 4);
    expect(analysisFromProduct({ nutriments: { 'energy-kcal_serving': 120, proteins_serving: 3 } }, '1').calories).toBe(120);
  });
});
