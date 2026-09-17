/**
 * Binds the pure AI runtime (`src/domain/ai/runtime.ts`) to the app stores and the secure
 * key store. Screens call these helpers; nothing here knows about React.
 */

import { Platform } from 'react-native';

import { analyzeFood as analyzeFoodWithDeps, generate as generateWithDeps, type AICallOptions, type AIRuntimeDeps } from '../domain/ai/runtime';
import { API_KEY_SECRET_PREFIX, CUSTOM_BASE_URL_PREFIX, FALLBACK_CUSTOM_BASE_URL_PREFIX } from '../domain/ai/settings';
import type { AIGenerateRequest } from '../domain/ai/transport';
import type { FoodAnalysis, FoodAnalysisRequest } from '../domain/food/analysis';
import { newId, preferencesStore, purchasesStore } from '../state/appStores';
import { asyncKeyValueStore, secureSecretStore } from '../state/persistence';

export function aiRuntimeDeps(): AIRuntimeDeps {
  const purchases = purchasesStore.getState();
  return {
    platform: Platform.OS === 'ios' ? 'ios' : 'android',
    preferences: preferencesStore.getState(),
    apiKey: (rawValue) => secureSecretStore.get(API_KEY_SECRET_PREFIX + rawValue).catch(() => null),
    customBaseURL: async (rawValue, role) => {
      if (role === 'fallback') {
        const fallback = await asyncKeyValueStore.get(FALLBACK_CUSTOM_BASE_URL_PREFIX + rawValue).catch(() => null);
        if (fallback) return fallback;
      }
      return asyncKeyValueStore.get(CUSTOM_BASE_URL_PREFIX + rawValue).catch(() => null);
    },
    ...(purchases.appUserId ? { hostedAppUserId: purchases.appUserId } : {}),
    hasHostedEntitlement: purchases.hasHostedEntitlement,
    makeId: newId,
  };
}

/** Photo / text / voice food analysis. Rejects with `AIError`; never hangs past the timeout. */
export function analyzeFood(request: FoodAnalysisRequest, signal?: AbortSignal): Promise<FoodAnalysis> {
  return analyzeFoodWithDeps(aiRuntimeDeps(), request, signal);
}

/** Free-form text generation (Coach, goal calculation). */
export function generateText(request: AIGenerateRequest, options: AICallOptions): Promise<string> {
  return generateWithDeps(aiRuntimeDeps(), request, options);
}
