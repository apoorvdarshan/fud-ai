/**
 * Provider resolution + dispatch for one AI call. Mirrors `GeminiService.callAI`: pick the
 * vision or text selection, try the primary provider, and retry once on the configured
 * fallback. Hosted mode routes to the proxy instead. Dependencies are injected so this
 * stays pure and unit-testable.
 */

import { AIError, aiErrorMessage } from './errors';
import type { AIMode } from './hosted';
import { foodAnalysisGenerateRequest, parseFoodAnalysis } from './foodAnalysis';
import type { MobilePlatform } from './providers';
import { clampRequestTimeout, requestConfig, resolveTextSelection, resolveVisionSelection, type AISelection, type RequestConfig } from './settings';
import { generateHostedText, generateText, type AIGenerateRequest } from './transport';
import type { FoodAnalysis, FoodAnalysisRequest } from '../food/analysis';
import type { Preferences } from '../prefs/preferences';

export interface AIRuntimeDeps {
  platform: MobilePlatform;
  preferences: Preferences;
  /** Resolves the stored key for a provider (`apikey_<rawValue>` in the secure store). */
  apiKey: (providerRawValue: string) => Promise<string | null>;
  customBaseURL: (providerRawValue: string, role?: 'primary' | 'fallback') => Promise<string | null>;
  /** RevenueCat app user id for the hosted proxy; undefined when purchases are not configured. */
  hostedAppUserId?: string;
  hasHostedEntitlement: boolean;
  fetchImpl?: typeof fetch;
  makeId: () => string;
}

export interface AICallOptions {
  signal?: AbortSignal;
  /** True for requests that carry images (uses the vision selection + image fallback). */
  vision: boolean;
}

export function activeAIMode(preferences: Preferences): AIMode {
  return preferences.aiAccessMode === 'hosted' ? 'hosted' : 'byok';
}

/** Primary selection for a request kind, from persisted preferences. */
export function primarySelection(deps: AIRuntimeDeps, vision: boolean): AISelection {
  const p = deps.preferences;
  if (vision || !p.separateTextProviderEnabled) {
    return vision
      ? resolveVisionSelection(deps.platform, p.selectedAIProvider, p.selectedAIModel)
      : resolveTextSelection(deps.platform, p.selectedAIProvider, p.selectedAIModel);
  }
  return resolveTextSelection(deps.platform, p.selectedTextAIProvider, p.selectedTextAIModel);
}

export function fallbackSelection(deps: AIRuntimeDeps, vision: boolean): AISelection | undefined {
  const p = deps.preferences;
  if (vision) {
    if (!p.aiFallbackEnabled || !p.selectedFallbackAIProvider) return undefined;
    const selection = resolveVisionSelection(deps.platform, p.selectedFallbackAIProvider, p.selectedFallbackAIModel);
    const primary = primarySelection(deps, true);
    if (selection.provider.id === primary.provider.id && selection.model === primary.model) return undefined;
    return selection;
  }
  if (!p.textAIFallbackEnabled || !p.selectedTextFallbackAIProvider) return undefined;
  const selection = resolveTextSelection(deps.platform, p.selectedTextFallbackAIProvider, p.selectedTextFallbackAIModel);
  const primary = primarySelection(deps, false);
  if (selection.provider.id === primary.provider.id && selection.model === primary.model) return undefined;
  return selection;
}

async function configFor(deps: AIRuntimeDeps, selection: AISelection, role: 'primary' | 'fallback' = 'primary'): Promise<RequestConfig> {
  const [apiKey, baseURL] = await Promise.all([
    selection.provider.requiresAPIKey ? deps.apiKey(selection.provider.rawValue) : Promise.resolve(null),
    deps.customBaseURL(selection.provider.rawValue, role),
  ]);
  const config = requestConfig(selection, baseURL, apiKey);
  if (selection.provider.requiresAPIKey && !config.apiKey) throw new AIError('noKey');
  return config;
}

function timeoutMs(deps: AIRuntimeDeps, selection: AISelection | undefined): number {
  const configured = clampRequestTimeout(deps.preferences.aiRequestTimeoutSeconds);
  // Cloud providers answer in well under a minute; only local/user-hosted endpoints get the
  // long configurable timeout, exactly like `usesConfigurableRequestTimeout` on iOS.
  const seconds = selection?.provider.usesConfigurableRequestTimeout ? configured : Math.min(configured, 90);
  return seconds * 1000;
}

/** One text generation through the active mode, with a single fallback retry in BYOK. */
export async function generate(deps: AIRuntimeDeps, request: AIGenerateRequest, options: AICallOptions): Promise<string> {
  const withContext: AIGenerateRequest = {
    ...request,
    ...(request.systemInstruction || !deps.preferences.aiUserContext.trim() ? {} : { systemInstruction: deps.preferences.aiUserContext.trim() }),
    maxOutputTokens: request.maxOutputTokens ?? deps.preferences.aiMaxResponseTokens,
  };
  const requestOptions = (selection?: AISelection) => ({
    timeoutMs: timeoutMs(deps, selection),
    ...(options.signal ? { signal: options.signal } : {}),
    ...(deps.fetchImpl ? { fetchImpl: deps.fetchImpl } : {}),
  });

  if (activeAIMode(deps.preferences) === 'hosted') {
    if (!deps.hasHostedEntitlement || !deps.hostedAppUserId) throw new AIError('hostedUnauthorized', 'Subscribe to Plus or Pro to use Hosted AI, or switch to BYOK in Settings → AI Access.');
    return generateHostedText(deps.hostedAppUserId, withContext, requestOptions());
  }

  const primary = primarySelection(deps, options.vision);
  try {
    return await generateText(await configFor(deps, primary), withContext, requestOptions(primary));
  } catch (primaryError) {
    if (primaryError instanceof AIError && (primaryError.kind === 'cancelled' || primaryError.kind === 'noKey')) throw primaryError;
    const fallback = fallbackSelection(deps, options.vision);
    if (!fallback) throw primaryError;
    try {
      return await generateText(await configFor(deps, fallback, 'fallback'), withContext, requestOptions(fallback));
    } catch (fallbackError) {
      if (fallbackError instanceof AIError && fallbackError.kind === 'cancelled') throw fallbackError;
      throw new AIError(
        'generic',
        `${primary.provider.displayName} and fallback ${fallback.provider.displayName} both failed. ${aiErrorMessage(fallbackError)}`,
      );
    }
  }
}

/** `GeminiService.analyzeTextInput` / `analyzeFood` — one call, parsed into `FoodAnalysis`. */
export async function analyzeFood(deps: AIRuntimeDeps, request: FoodAnalysisRequest, signal?: AbortSignal): Promise<FoodAnalysis> {
  const generateRequest = foodAnalysisGenerateRequest({
    ...request,
    ...(request.userContext === undefined && deps.preferences.aiUserContext.trim() ? { userContext: deps.preferences.aiUserContext } : {}),
  });
  const vision = (request.imagesBase64?.length ?? 0) > 0;
  const text = await generate(deps, generateRequest, { vision, ...(signal ? { signal } : {}) });
  return parseFoodAnalysis(text, deps.makeId);
}
