/**
 * AI selection preferences (`AIProviderSettings` on iOS). Keys match the iOS `UserDefaults`
 * names so a future import of a native backup maps 1:1. API keys never live here — they go
 * through `SecretStore` (Keychain / Android Keystore) under `apikey_<provider rawValue>`.
 */

import {
  aiProviderFromRawValue,
  aiProviders,
  defaultModel,
  defaultTextModel,
  supportedModelOrDefault,
  supportedTextModelOrDefault,
  textProviders,
  visionProviders,
  type AIProviderDefinition,
  type MobilePlatform,
} from './providers';

export const aiSettingsKeys = {
  provider: 'selectedAIProvider',
  model: 'selectedAIModel',
  separateTextProviderEnabled: 'separateTextProviderEnabled',
  textProvider: 'selectedTextAIProvider',
  textModel: 'selectedTextAIModel',
  userContext: 'aiUserContext',
  fallbackEnabled: 'aiFallbackEnabled',
  fallbackProvider: 'selectedFallbackAIProvider',
  fallbackModel: 'selectedFallbackAIModel',
  textFallbackEnabled: 'textAIFallbackEnabled',
  textFallbackProvider: 'selectedTextFallbackAIProvider',
  textFallbackModel: 'selectedTextFallbackAIModel',
  maxResponseTokens: 'aiMaxResponseTokens',
  requestTimeoutSeconds: 'aiRequestTimeoutSeconds',
  openRouterReasoningEffort: 'openRouterReasoningEffort',
} as const;

export const API_KEY_SECRET_PREFIX = 'apikey_';
export const CUSTOM_BASE_URL_PREFIX = 'customBaseURL_';
export const FALLBACK_CUSTOM_BASE_URL_PREFIX = 'fallbackCustomBaseURL_';

export function apiKeySecretName(provider: AIProviderDefinition): string {
  return API_KEY_SECRET_PREFIX + provider.rawValue;
}

export function customBaseURLKey(provider: AIProviderDefinition): string {
  return CUSTOM_BASE_URL_PREFIX + provider.rawValue;
}

export function fallbackCustomBaseURLKey(provider: AIProviderDefinition): string {
  return FALLBACK_CUSTOM_BASE_URL_PREFIX + provider.rawValue;
}

export function needsCustomEndpoint(provider: AIProviderDefinition): boolean {
  return provider.id === 'ollama' || provider.requiresCustomEndpoint;
}

export function usesCustomModelName(provider: AIProviderDefinition, text: boolean): boolean {
  if (provider.requiresCustomModelName) return true;
  return (text ? provider.textModels : provider.models).length === 0;
}

export const DEFAULT_MAX_RESPONSE_TOKENS = 1024;
export const DEFAULT_REQUEST_TIMEOUT_SECONDS = 180;
export const MIN_REQUEST_TIMEOUT_SECONDS = 30;
export const MAX_REQUEST_TIMEOUT_SECONDS = 600;

export function clampRequestTimeout(seconds: number | undefined): number {
  if (!seconds || seconds <= 0) return DEFAULT_REQUEST_TIMEOUT_SECONDS;
  return Math.min(Math.max(seconds, MIN_REQUEST_TIMEOUT_SECONDS), MAX_REQUEST_TIMEOUT_SECONDS);
}

export type OpenRouterReasoningEffort = 'auto' | 'none' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';

export interface AISelection {
  provider: AIProviderDefinition;
  model: string;
}

/** Resolve the persisted vision provider, falling back to Gemini exactly like iOS. */
export function resolveVisionSelection(
  platform: MobilePlatform,
  providerRaw: string | null | undefined,
  modelRaw: string | null | undefined,
): AISelection {
  const provider = aiProviderFromRawValue(providerRaw);
  const eligible = provider && visionProviders(platform).includes(provider) ? provider : aiProviders.gemini;
  return { provider: eligible, model: supportedModelOrDefault(eligible, provider === eligible ? modelRaw : undefined) };
}

export function resolveTextSelection(
  platform: MobilePlatform,
  providerRaw: string | null | undefined,
  modelRaw: string | null | undefined,
): AISelection {
  const provider = aiProviderFromRawValue(providerRaw);
  const eligible = provider && textProviders(platform).includes(provider) ? provider : aiProviders.gemini;
  return {
    provider: eligible,
    model: supportedTextModelOrDefault(eligible, provider === eligible ? modelRaw : undefined),
  };
}

export interface RequestConfig {
  provider: AIProviderDefinition;
  model: string;
  baseURL: string;
  apiKey: string | null;
}

export function requestConfig(selection: AISelection, customBaseURL: string | null, apiKey: string | null): RequestConfig {
  return {
    provider: selection.provider,
    model: selection.model,
    baseURL: customBaseURL && customBaseURL.length > 0 ? customBaseURL : selection.provider.baseURL,
    apiKey,
  };
}

export { defaultModel, defaultTextModel };
