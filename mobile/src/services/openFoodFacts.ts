/**
 * Network wrapper around the pure Open Food Facts parser. 20s timeout — never hangs.
 */

import { lookupOpenFoodFacts, type FoodAnalysis } from '../domain';

const LOOKUP_TIMEOUT_MS = 20_000;

export async function lookupBarcode(barcode: string, signal?: AbortSignal): Promise<FoodAnalysis> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), LOOKUP_TIMEOUT_MS);
  const onAbort = () => controller.abort();
  signal?.addEventListener('abort', onAbort);
  try {
    return await lookupOpenFoodFacts(barcode, {
      fetch,
      languageCode: Intl.DateTimeFormat().resolvedOptions().locale.split('-')[0],
      version: '7.1',
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}
