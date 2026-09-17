import { describe, expect, it } from 'vitest';

import { AIError, aiErrorMessage, classifyHTTPError } from '../src/domain/ai/errors';
import { foodAnalysisGenerateRequest, parseFoodAnalysis, parseServingUnitOptions, scaledAnalysis } from '../src/domain/ai/foodAnalysis';
import { aiProviders } from '../src/domain/ai/providers';
import { analyzeFood, fallbackSelection, generate, primarySelection, type AIRuntimeDeps } from '../src/domain/ai/runtime';
import { requestConfig } from '../src/domain/ai/settings';
import {
  buildAnthropicRequest,
  buildGeminiRequest,
  buildHostedRequest,
  buildOpenAICompatibleRequest,
  extractJSON,
  generateText,
  isAllowedBaseURL,
  parseGeminiText,
  parseOpenAIText,
} from '../src/domain/ai/transport';
import { defaultPreferences, type Preferences } from '../src/domain/prefs/preferences';

const gemini = requestConfig({ provider: aiProviders.gemini, model: 'gemini-3.5-flash-lite' }, null, 'AIzaTestKey');
const openai = requestConfig({ provider: aiProviders.openai, model: 'gpt-5.4-mini' }, null, 'sk-test');
const anthropic = requestConfig({ provider: aiProviders.anthropic, model: 'claude-sonnet-5' }, null, 'sk-ant-test');

type FetchImpl = typeof fetch;

function fakeFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>): FetchImpl {
  return ((url: string | URL | Request, init?: RequestInit) => Promise.resolve(handler(String(url), init ?? {}))) as FetchImpl;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('request builders', () => {
  it('sends the Gemini key in a header, never in the URL', () => {
    const http = buildGeminiRequest(gemini, { prompt: 'hi', imagesBase64: ['AAA'] });
    expect(http.url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent');
    expect(http.url).not.toContain('AIzaTestKey');
    expect(http.headers['X-goog-api-key']).toBe('AIzaTestKey');
    const body = http.body as { contents: { parts: unknown[] }[]; generationConfig: { responseMimeType?: string } };
    expect(body.contents[0]?.parts).toHaveLength(2);
    expect(body.generationConfig.responseMimeType).toBe('application/json');
  });

  it('threads chat history into every format', () => {
    const history = [
      { role: 'user' as const, text: 'Hello' },
      { role: 'assistant' as const, text: 'Hi there' },
    ];
    const g = buildGeminiRequest(gemini, { prompt: 'Next', history, jsonResponse: false }).body as { contents: { role: string }[] };
    expect(g.contents.map((c) => c.role)).toEqual(['user', 'model', 'user']);

    const o = buildOpenAICompatibleRequest(openai, { prompt: 'Next', history, systemInstruction: 'sys' }).body as {
      messages: { role: string }[];
      max_completion_tokens?: number;
      max_tokens?: number;
    };
    expect(o.messages.map((m) => m.role)).toEqual(['system', 'user', 'assistant', 'user']);
    expect(o.max_completion_tokens).toBe(1024);
    expect(o.max_tokens).toBeUndefined();

    const a = buildAnthropicRequest(anthropic, { prompt: 'Next', history, systemInstruction: 'sys' }).body as { messages: { role: string }[]; system: string };
    expect(a.messages.map((m) => m.role)).toEqual(['user', 'assistant', 'user']);
    expect(a.system).toBe('sys');
  });

  it('uses max_tokens for non-OpenAI compatible providers and adds OpenRouter headers', () => {
    const groq = requestConfig({ provider: aiProviders.groq, model: 'qwen/qwen3.6-27b' }, null, 'gsk_x');
    const body = buildOpenAICompatibleRequest(groq, { prompt: 'p' }).body as { max_tokens?: number };
    expect(body.max_tokens).toBe(1024);

    const router = requestConfig({ provider: aiProviders.openrouter, model: 'openrouter/free' }, null, 'sk-or');
    const http = buildOpenAICompatibleRequest(router, { prompt: 'p' });
    expect(http.headers['X-Title']).toBe('Fud AI');
  });

  it('caps hosted images at the constant and identifies the user by header', () => {
    const http = buildHostedRequest('$RCAnonymousID:abc', { prompt: 'p', imagesBase64: ['1', '2', '3', '4'] });
    expect(http.headers['X-Fud-User-Id']).toBe('$RCAnonymousID:abc');
    expect((http.body as { images: string[] }).images).toHaveLength(3);
  });

  it('throws noKey when a key-requiring provider has none', () => {
    const noKey = requestConfig({ provider: aiProviders.gemini, model: 'm' }, null, null);
    expect(() => buildGeminiRequest(noKey, { prompt: 'p' })).toThrowError(AIError);
  });
});

describe('response parsers', () => {
  it('skips Gemini thought parts and flags MAX_TOKENS', () => {
    const parsed = parseGeminiText({ candidates: [{ finishReason: 'STOP', content: { parts: [{ thought: true, text: 'thinking' }, { text: '{"a":1}' }] } }] });
    expect(parsed).toEqual({ text: '{"a":1}', wasTruncated: false });
    const truncated = parseGeminiText({ candidates: [{ finishReason: 'MAX_TOKENS', content: { parts: [] } }] });
    expect(truncated.wasTruncated).toBe(true);
  });

  it('surfaces OpenAI error messages and treats reasoning-only replies as truncated', () => {
    expect(() => parseOpenAIText({ error: { message: 'bad model' } })).toThrowError(/bad model/);
    const reasoningOnly = parseOpenAIText({ choices: [{ message: { reasoning: '...' }, finish_reason: 'stop' }] });
    expect(reasoningOnly.wasTruncated).toBe(true);
  });

  it('extracts the first balanced JSON object from fenced or chatty text', () => {
    expect(extractJSON('```json\n{"name":"x"}\n```')).toBe('{"name":"x"}');
    expect(extractJSON('Sure! {"name":"a}b","n":{"x":1}} trailing')).toBe('{"name":"a}b","n":{"x":1}}');
  });

  it('classifies HTTP failures like AIErrorKind.classify', () => {
    expect(classifyHTTPError(401, '')).toBe('keyRejected');
    expect(classifyHTTPError(400, 'API key not valid')).toBe('keyRejected');
    expect(classifyHTTPError(429, 'quota exceeded per day')).toBe('dailyQuota');
    expect(classifyHTTPError(429, 'slow down')).toBe('rateLimited');
    expect(classifyHTTPError(404, '')).toBe('modelUnavailable');
    expect(classifyHTTPError(503, '')).toBe('overloaded');
  });
});

describe('generateText', () => {
  it('retries once with a compact prompt when truncated, then gives up', async () => {
    const prompts: string[] = [];
    const fetchImpl = fakeFetch((_url, init) => {
      const body = JSON.parse(String(init.body)) as { contents: { parts: { text?: string }[] }[] };
      prompts.push(body.contents[0]?.parts.at(-1)?.text ?? '');
      return jsonResponse({ candidates: [{ finishReason: 'MAX_TOKENS', content: { parts: [{ text: '{"partial"' }] } }] });
    });
    await expect(generateText(gemini, { prompt: 'analyze' }, { timeoutMs: 5000, fetchImpl })).rejects.toMatchObject({ kind: 'truncated' });
    expect(prompts).toHaveLength(2);
    expect(prompts[1]).toContain('previous response was truncated');
  });

  it('times out instead of hanging forever', async () => {
    const fetchImpl = ((_url: string, init: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
      })) as unknown as FetchImpl;
    await expect(generateText(gemini, { prompt: 'p' }, { timeoutMs: 20, fetchImpl })).rejects.toMatchObject({ kind: 'timeout' });
  });

  it('reports cancellation when the caller aborts', async () => {
    const controller = new AbortController();
    const fetchImpl = ((_url: string, init: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
      })) as unknown as FetchImpl;
    const pending = generateText(gemini, { prompt: 'p' }, { timeoutMs: 5000, signal: controller.signal, fetchImpl });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ kind: 'cancelled' });
    expect(aiErrorMessage(new AIError('cancelled'))).toBe('Analysis cancelled.');
  });

  it('refuses to send a key over cleartext to anything but the local network', async () => {
    expect(isAllowedBaseURL('https://api.openai.com/v1')).toBe(true);
    expect(isAllowedBaseURL('http://localhost:11434/v1')).toBe(true);
    expect(isAllowedBaseURL('http://192.168.1.20:1234/v1')).toBe(true);
    expect(isAllowedBaseURL('http://10.0.0.5/v1')).toBe(true);
    expect(isAllowedBaseURL('http://studio.local:1234/v1')).toBe(true);
    expect(isAllowedBaseURL('http://api.example.com/v1')).toBe(false);
    expect(isAllowedBaseURL('ftp://api.example.com/v1')).toBe(false);
    expect(isAllowedBaseURL('not a url')).toBe(false);

    const fetchImpl = fakeFetch(() => jsonResponse({}));
    const insecure = requestConfig({ provider: aiProviders.openai, model: 'gpt-5.4-mini' }, 'http://api.example.com/v1', 'sk-test');
    await expect(generateText(insecure, { prompt: 'p' }, { timeoutMs: 5000, fetchImpl })).rejects.toMatchObject({ kind: 'invalidURL' });
  });

  it('maps HTTP errors to stable kinds without leaking the body', async () => {
    const fetchImpl = fakeFetch(() => new Response('{"error":{"message":"API key not valid. Please pass a valid API key."}}', { status: 400 }));
    const error = await generateText(gemini, { prompt: 'p' }, { timeoutMs: 5000, fetchImpl }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(AIError);
    expect((error as AIError).kind).toBe('keyRejected');
    expect((error as AIError).message).not.toContain('Please pass');
  });
});

describe('food analysis parsing', () => {
  const sample = {
    name: 'Greek yogurt with berries',
    calories: 180.4,
    protein: 15,
    carbs: 20,
    fat: 4.5,
    serving_size_grams: 200,
    emoji: '🥣',
    fiber: 3,
    sodium: null,
    creatine: 0,
    ingredients: [
      { name: 'Greek yogurt', grams: 150, calories: 130, protein: 14, carbs: 8, fat: 4 },
      { name: 'Blueberries', grams: 50, calories: 30, protein: 0.4, carbs: 7, fat: 0.2 },
      { name: 'bad', grams: -1, calories: 1, protein: 1, carbs: 1, fat: 1 },
    ],
    unit_options: [{ unit: 'cup', quantity: 1, grams_per_unit: 200 }],
  };

  it('parses the native FoodAnalysis shape', () => {
    let n = 0;
    const analysis = parseFoodAnalysis(`Here you go:\n\`\`\`json\n${JSON.stringify(sample)}\n\`\`\``, () => `id-${(n += 1)}`);
    expect(analysis.name).toBe('Greek yogurt with berries');
    expect(analysis.calories).toBe(180);
    expect(analysis.emoji).toBe('🥣');
    expect(analysis.fiber).toBe(3);
    expect(analysis.sodium).toBeUndefined();
    expect(analysis.supplementalNutrients).toEqual({ creatine: 0 });
    expect(analysis.ingredients.map((i) => i.name)).toEqual(['Greek yogurt', 'Blueberries']);
    expect(analysis.ingredients[0]?.id).toBe('id-1');
    expect(analysis.servingUnitOptions).toEqual([{ unit: 'cup', gramsPerUnit: 200, quantity: 1 }]);
    expect(analysis.selectedServingUnit).toBe('cup');
    expect(analysis.servingSizeIsKnown).toBe(true);
    expect(analysis.requiresServingUnitFallback).toBe(false);
  });

  it('discards unit options that are malformed, gram-based, or inconsistent', () => {
    expect(parseServingUnitOptions({ unit_options: [{ unit: 'g', quantity: 1, grams_per_unit: 100 }] }, 100).requiresFallback).toBe(true);
    expect(parseServingUnitOptions({ unit_options: [{ unit: 'slice', quantity: '2', grams_per_unit: 50 }] }, 100).requiresFallback).toBe(true);
    expect(parseServingUnitOptions({ unit_options: [{ unit: 'slice', quantity: 2, grams_per_unit: 500 }] }, 100).requiresFallback).toBe(true);
    expect(parseServingUnitOptions({ unit_options: [] }, 100)).toEqual({ options: [], requiresFallback: false });
    expect(parseServingUnitOptions({}, 100).requiresFallback).toBe(true);
  });

  it('rejects responses without the required macro fields', () => {
    expect(() => parseFoodAnalysis('{"name":"x","calories":"12"}', () => 'id')).toThrowError(AIError);
    expect(() => parseFoodAnalysis('not json at all', () => 'id')).toThrowError(/understand the AI response/);
  });

  it('rejects negative totals and drops negative optional nutrients or a non-positive mass', () => {
    expect(() => parseFoodAnalysis(JSON.stringify({ ...sample, calories: -100 }), () => 'id')).toThrowError(AIError);
    expect(() => parseFoodAnalysis(JSON.stringify({ ...sample, fat: -0.5 }), () => 'id')).toThrowError(AIError);
    const odd = parseFoodAnalysis(JSON.stringify({ ...sample, fiber: -3, creatine: -1, serving_size_grams: 0, unit_options: [] }), () => 'id');
    expect(odd.fiber).toBeUndefined();
    expect(odd.supplementalNutrients).toEqual({});
    expect(odd.servingSizeIsKnown).toBe(false);
    expect(odd.servingSizeGrams).toBe(1);
  });

  it('scales an analysis to a new gram amount, including the serving-unit count', () => {
    const analysis = parseFoodAnalysis(JSON.stringify(sample), () => 'id');
    const half = scaledAnalysis(analysis, 100);
    expect(half.calories).toBe(90);
    expect(half.protein).toBe(7.5);
    expect(half.fiber).toBe(1.5);
    expect(half.ingredients[0]?.grams).toBe(75);
    // 1 cup at 200 g is half a cup at 100 g; grams per cup is a property of the unit and stays.
    expect(half.selectedServingUnit).toBe('cup');
    expect(half.selectedServingQuantity).toBe(0.5);
    expect(half.servingUnitOptions).toEqual([{ unit: 'cup', gramsPerUnit: 200, quantity: 0.5 }]);
    expect(scaledAnalysis(half, 400).selectedServingQuantity).toBe(2);
  });

  it('builds the text and photo prompts with the shared JSON shape', () => {
    const text = foodAnalysisGenerateRequest({ kind: 'text', text: '2 eggs and toast' });
    expect(text.prompt).toContain('Estimate the nutritional content for: 2 eggs and toast');
    expect(text.prompt).toContain('"emoji":"🍽️"');
    const photo = foodAnalysisGenerateRequest({ kind: 'photo', imagesBase64: ['AAA'], text: 'with extra cheese' });
    expect(photo.prompt).toContain('Additional context from the user about this meal: with extra cheese');
    expect(photo.prompt).not.toContain('"emoji"');
    expect(photo.imagesBase64).toEqual(['AAA']);
    expect(() => foodAnalysisGenerateRequest({ kind: 'photo' })).toThrowError(AIError);
  });
});

describe('runtime provider resolution', () => {
  const deps = (prefs: Partial<Preferences>, overrides: Partial<AIRuntimeDeps> = {}): AIRuntimeDeps => ({
    platform: 'android',
    preferences: { ...defaultPreferences, selectedAIProvider: 'Google Gemini', selectedAIModel: 'gemini-3.5-flash-lite', ...prefs },
    apiKey: async () => 'key',
    customBaseURL: async () => null,
    hasHostedEntitlement: false,
    makeId: () => 'id',
    ...overrides,
  });

  it('uses the separate text provider only for text-only requests', () => {
    const d = deps({ separateTextProviderEnabled: true, selectedTextAIProvider: 'Groq', selectedTextAIModel: 'llama-3.1-8b-instant' });
    expect(primarySelection(d, true).provider.id).toBe('gemini');
    expect(primarySelection(d, false).provider.id).toBe('groq');
    expect(primarySelection(d, false).model).toBe('llama-3.1-8b-instant');
  });

  it('ignores a fallback identical to the primary', () => {
    const d = deps({ aiFallbackEnabled: true, selectedFallbackAIProvider: 'Google Gemini', selectedFallbackAIModel: 'gemini-3.5-flash-lite' });
    expect(fallbackSelection(d, true)).toBeUndefined();
    const other = deps({ aiFallbackEnabled: true, selectedFallbackAIProvider: 'OpenAI', selectedFallbackAIModel: 'gpt-5.4-mini' });
    expect(fallbackSelection(other, true)?.provider.id).toBe('openai');
  });

  it('uses the text fallback settings only for text-only requests', () => {
    const imageOnly = deps({ aiFallbackEnabled: true, selectedFallbackAIProvider: 'OpenAI', selectedFallbackAIModel: 'gpt-5.4-mini' });
    expect(fallbackSelection(imageOnly, false)).toBeUndefined();
    expect(fallbackSelection(imageOnly, true)?.provider.id).toBe('openai');
    const text = deps({
      textAIFallbackEnabled: true,
      selectedTextFallbackAIProvider: 'Groq',
      selectedTextFallbackAIModel: 'llama-3.1-8b-instant',
    });
    expect(fallbackSelection(text, false)?.provider.id).toBe('groq');
    expect(fallbackSelection(text, true)).toBeUndefined();
  });

  it('falls back to the second provider after a primary failure and names both on double failure', async () => {
    const calls: string[] = [];
    const fetchImpl = fakeFetch((url) => {
      calls.push(url);
      if (url.includes('googleapis')) return new Response('{"error":{"message":"overloaded"}}', { status: 503 });
      return jsonResponse({ choices: [{ message: { content: '{"name":"Toast","calories":80,"protein":3,"carbs":15,"fat":1}' }, finish_reason: 'stop' }] });
    });
    const d = deps({ textAIFallbackEnabled: true, selectedTextFallbackAIProvider: 'OpenAI', selectedTextFallbackAIModel: 'gpt-5.4-mini' }, { fetchImpl });
    const analysis = await analyzeFood(d, { kind: 'text', text: 'toast' });
    expect(analysis.name).toBe('Toast');
    expect(calls).toHaveLength(2);

    const bothFail = deps({ textAIFallbackEnabled: true, selectedTextFallbackAIProvider: 'OpenAI', selectedTextFallbackAIModel: 'gpt-5.4-mini' }, {
      fetchImpl: fakeFetch(() => new Response('{}', { status: 503 })),
    });
    await expect(generate(bothFail, { prompt: 'p' }, { vision: false })).rejects.toThrowError(/Google Gemini and fallback OpenAI both failed/);
  });

  it('refuses hosted mode without an entitlement instead of calling the proxy', async () => {
    const fetchImpl = fakeFetch(() => {
      throw new Error('must not be called');
    });
    const d = deps({ aiAccessMode: 'hosted' }, { fetchImpl, hostedAppUserId: 'user' });
    await expect(generate(d, { prompt: 'p' }, { vision: false })).rejects.toMatchObject({ kind: 'hostedUnauthorized' });
  });

  it('routes hosted requests through the proxy with the app user id', async () => {
    let seenHeader: string | undefined;
    const fetchImpl = fakeFetch((url, init) => {
      seenHeader = (init.headers as Record<string, string>)['X-Fud-User-Id'];
      expect(url).toBe('https://fud-ai.app/api/hosted-ai/v1/generate');
      return jsonResponse({ text: 'hello from hosted' });
    });
    const d = deps({ aiAccessMode: 'hosted' }, { fetchImpl, hostedAppUserId: 'rc-user', hasHostedEntitlement: true });
    await expect(generate(d, { prompt: 'p' }, { vision: false })).resolves.toBe('hello from hosted');
    expect(seenHeader).toBe('rc-user');
  });
});
