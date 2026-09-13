/**
 * Strict allow-list for the hosted `/gemini` passthrough body.
 *
 * The coach tool-calling loop needs `contents`, `systemInstruction`, bounded
 * `generationConfig`, and plain `functionDeclarations`. Everything else —
 * Google Search grounding, code execution, URL context, cached content,
 * unbounded candidate counts, arbitrary safety settings — is rejected so a
 * subscriber cannot turn the proxy into a general-purpose Gemini key.
 *
 * The sanitizer rebuilds a fresh object containing only recognised keys; the
 * caller's object is never forwarded upstream as-is.
 */

export const GEMINI_REQUEST_LIMITS = {
  maxContents: 200,
  maxPartsPerContent: 32,
  maxTotalTextChars: 240_000,
  maxSystemInstructionChars: 64_000,
  maxInlineImages: 3,
  maxInlineImageBase64Chars: 8 * 1024 * 1024,
  maxFunctionDeclarations: 32,
  maxFunctionNameChars: 64,
  maxFunctionDescriptionChars: 2_000,
  maxFunctionParametersJsonChars: 16 * 1024,
  maxFunctionArgsJsonChars: 16 * 1024,
  maxFunctionResponseJsonChars: 64 * 1024,
  maxThoughtSignatureChars: 16 * 1024,
  maxJsonDepth: 12,
  maxOutputTokens: 8_192,
  maxTopK: 100,
  maxStopSequences: 5,
  maxStopSequenceChars: 64,
} as const;

const ALLOWED_INLINE_MIME_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/heic",
  "image/heif",
]);

const ALLOWED_RESPONSE_MIME_TYPES = new Set(["text/plain", "application/json"]);
const ALLOWED_FUNCTION_CALLING_MODES = new Set(["AUTO", "ANY", "NONE"]);
const FUNCTION_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/;

export type GeminiSanitizeResult =
  | { ok: true; body: Record<string, unknown> }
  | { ok: false; error: string };

type JsonObject = Record<string, unknown>;

class RejectError extends Error {}

function reject(path: string, reason: string): never {
  throw new RejectError(`${reason}:${path}`);
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function assertOnlyKeys(value: JsonObject, allowed: readonly string[], path: string): void {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) reject(`${path}.${key}`, "unsupported_field");
  }
}

function requireString(value: unknown, path: string, maxChars: number): string {
  if (typeof value !== "string") reject(path, "invalid_type");
  if (value.length > maxChars) reject(path, "too_long");
  return value;
}

function jsonDepth(value: unknown, depth = 0): number {
  if (depth > GEMINI_REQUEST_LIMITS.maxJsonDepth) return depth;
  if (Array.isArray(value)) {
    return value.reduce<number>((max, item) => Math.max(max, jsonDepth(item, depth + 1)), depth);
  }
  if (isObject(value)) {
    return Object.values(value).reduce<number>(
      (max, item) => Math.max(max, jsonDepth(item, depth + 1)),
      depth
    );
  }
  return depth;
}

/** Bounded opaque JSON object (function args/responses/parameter schemas). */
function boundedJsonObject(value: unknown, path: string, maxChars: number): JsonObject {
  if (!isObject(value)) reject(path, "invalid_type");
  const serialized = JSON.stringify(value);
  if (serialized.length > maxChars) reject(path, "too_long");
  if (jsonDepth(value) > GEMINI_REQUEST_LIMITS.maxJsonDepth) reject(path, "too_deep");
  return JSON.parse(serialized) as JsonObject;
}

type Budget = { textChars: number; inlineImages: number };

function sanitizePart(value: unknown, path: string, budget: Budget, allowSystemOnly: boolean): JsonObject {
  if (!isObject(value)) reject(path, "invalid_type");
  const allowed = allowSystemOnly
    ? ["text"]
    : ["text", "inlineData", "functionCall", "functionResponse", "thoughtSignature"];
  assertOnlyKeys(value, allowed, path);

  const payloadKeys = Object.keys(value).filter((key) => key !== "thoughtSignature");
  if (payloadKeys.length !== 1) reject(path, "part_must_have_one_payload");

  const part: JsonObject = {};
  if ("text" in value) {
    const text = requireString(value.text, `${path}.text`, GEMINI_REQUEST_LIMITS.maxTotalTextChars);
    budget.textChars += text.length;
    if (budget.textChars > GEMINI_REQUEST_LIMITS.maxTotalTextChars) reject(path, "text_budget_exceeded");
    part.text = text;
  }
  if ("inlineData" in value) {
    const inline = value.inlineData;
    if (!isObject(inline)) reject(`${path}.inlineData`, "invalid_type");
    assertOnlyKeys(inline, ["mimeType", "data"], `${path}.inlineData`);
    const mimeType = requireString(inline.mimeType, `${path}.inlineData.mimeType`, 64);
    if (!ALLOWED_INLINE_MIME_TYPES.has(mimeType)) reject(`${path}.inlineData.mimeType`, "unsupported_mime_type");
    const data = requireString(
      inline.data,
      `${path}.inlineData.data`,
      GEMINI_REQUEST_LIMITS.maxInlineImageBase64Chars
    );
    budget.inlineImages += 1;
    if (budget.inlineImages > GEMINI_REQUEST_LIMITS.maxInlineImages) reject(path, "too_many_images");
    part.inlineData = { mimeType, data };
  }
  if ("functionCall" in value) {
    const call = value.functionCall;
    if (!isObject(call)) reject(`${path}.functionCall`, "invalid_type");
    assertOnlyKeys(call, ["name", "args", "id"], `${path}.functionCall`);
    const name = requireString(call.name, `${path}.functionCall.name`, GEMINI_REQUEST_LIMITS.maxFunctionNameChars);
    if (!FUNCTION_NAME_PATTERN.test(name)) reject(`${path}.functionCall.name`, "invalid_function_name");
    const sanitized: JsonObject = { name };
    if (call.args !== undefined) {
      sanitized.args = boundedJsonObject(
        call.args,
        `${path}.functionCall.args`,
        GEMINI_REQUEST_LIMITS.maxFunctionArgsJsonChars
      );
    }
    if (call.id !== undefined) sanitized.id = requireString(call.id, `${path}.functionCall.id`, 128);
    part.functionCall = sanitized;
  }
  if ("functionResponse" in value) {
    const response = value.functionResponse;
    if (!isObject(response)) reject(`${path}.functionResponse`, "invalid_type");
    assertOnlyKeys(response, ["name", "response", "id"], `${path}.functionResponse`);
    const name = requireString(
      response.name,
      `${path}.functionResponse.name`,
      GEMINI_REQUEST_LIMITS.maxFunctionNameChars
    );
    if (!FUNCTION_NAME_PATTERN.test(name)) reject(`${path}.functionResponse.name`, "invalid_function_name");
    const sanitized: JsonObject = {
      name,
      response: boundedJsonObject(
        response.response,
        `${path}.functionResponse.response`,
        GEMINI_REQUEST_LIMITS.maxFunctionResponseJsonChars
      ),
    };
    if (response.id !== undefined) sanitized.id = requireString(response.id, `${path}.functionResponse.id`, 128);
    part.functionResponse = sanitized;
  }
  if ("thoughtSignature" in value) {
    part.thoughtSignature = requireString(
      value.thoughtSignature,
      `${path}.thoughtSignature`,
      GEMINI_REQUEST_LIMITS.maxThoughtSignatureChars
    );
  }
  return part;
}

function sanitizeContents(value: unknown, budget: Budget): JsonObject[] {
  if (!Array.isArray(value)) reject("contents", "invalid_type");
  if (value.length === 0) reject("contents", "empty");
  if (value.length > GEMINI_REQUEST_LIMITS.maxContents) reject("contents", "too_many");

  return value.map((entry, index) => {
    const path = `contents[${index}]`;
    if (!isObject(entry)) reject(path, "invalid_type");
    assertOnlyKeys(entry, ["role", "parts"], path);
    const content: JsonObject = {};
    if (entry.role !== undefined) {
      if (entry.role !== "user" && entry.role !== "model") reject(`${path}.role`, "invalid_role");
      content.role = entry.role;
    }
    if (!Array.isArray(entry.parts)) reject(`${path}.parts`, "invalid_type");
    if (entry.parts.length === 0) reject(`${path}.parts`, "empty");
    if (entry.parts.length > GEMINI_REQUEST_LIMITS.maxPartsPerContent) reject(`${path}.parts`, "too_many");
    content.parts = entry.parts.map((part, partIndex) =>
      sanitizePart(part, `${path}.parts[${partIndex}]`, budget, false)
    );
    return content;
  });
}

function sanitizeSystemInstruction(value: unknown): JsonObject {
  if (!isObject(value)) reject("systemInstruction", "invalid_type");
  assertOnlyKeys(value, ["role", "parts"], "systemInstruction");
  if (!Array.isArray(value.parts) || value.parts.length === 0) reject("systemInstruction.parts", "invalid_type");
  if (value.parts.length > GEMINI_REQUEST_LIMITS.maxPartsPerContent) reject("systemInstruction.parts", "too_many");
  const budget: Budget = { textChars: 0, inlineImages: 0 };
  const parts = value.parts.map((part, index) =>
    sanitizePart(part, `systemInstruction.parts[${index}]`, budget, true)
  );
  if (budget.textChars > GEMINI_REQUEST_LIMITS.maxSystemInstructionChars) {
    reject("systemInstruction", "too_long");
  }
  return { parts };
}

function sanitizeGenerationConfig(value: unknown): JsonObject {
  if (!isObject(value)) reject("generationConfig", "invalid_type");
  assertOnlyKeys(
    value,
    ["temperature", "topP", "topK", "maxOutputTokens", "candidateCount", "stopSequences", "responseMimeType"],
    "generationConfig"
  );
  const config: JsonObject = {};
  const numberIn = (key: string, min: number, max: number, integer = false): void => {
    const raw = value[key];
    if (raw === undefined) return;
    if (typeof raw !== "number" || !Number.isFinite(raw)) reject(`generationConfig.${key}`, "invalid_type");
    if (integer && !Number.isInteger(raw)) reject(`generationConfig.${key}`, "invalid_type");
    if (raw < min || raw > max) reject(`generationConfig.${key}`, "out_of_range");
    config[key] = raw;
  };
  numberIn("temperature", 0, 2);
  numberIn("topP", 0, 1);
  numberIn("topK", 1, GEMINI_REQUEST_LIMITS.maxTopK, true);
  numberIn("maxOutputTokens", 1, GEMINI_REQUEST_LIMITS.maxOutputTokens, true);
  numberIn("candidateCount", 1, 1, true);
  if (value.stopSequences !== undefined) {
    if (!Array.isArray(value.stopSequences)) reject("generationConfig.stopSequences", "invalid_type");
    if (value.stopSequences.length > GEMINI_REQUEST_LIMITS.maxStopSequences) {
      reject("generationConfig.stopSequences", "too_many");
    }
    config.stopSequences = value.stopSequences.map((sequence, index) =>
      requireString(sequence, `generationConfig.stopSequences[${index}]`, GEMINI_REQUEST_LIMITS.maxStopSequenceChars)
    );
  }
  if (value.responseMimeType !== undefined) {
    const mime = requireString(value.responseMimeType, "generationConfig.responseMimeType", 64);
    if (!ALLOWED_RESPONSE_MIME_TYPES.has(mime)) reject("generationConfig.responseMimeType", "unsupported_mime_type");
    config.responseMimeType = mime;
  }
  return config;
}

function sanitizeTools(value: unknown): JsonObject[] {
  if (!Array.isArray(value)) reject("tools", "invalid_type");
  if (value.length > 1) reject("tools", "too_many");
  return value.map((tool, index) => {
    const path = `tools[${index}]`;
    if (!isObject(tool)) reject(path, "invalid_type");
    assertOnlyKeys(tool, ["functionDeclarations"], path);
    if (!Array.isArray(tool.functionDeclarations)) reject(`${path}.functionDeclarations`, "invalid_type");
    if (tool.functionDeclarations.length === 0) reject(`${path}.functionDeclarations`, "empty");
    if (tool.functionDeclarations.length > GEMINI_REQUEST_LIMITS.maxFunctionDeclarations) {
      reject(`${path}.functionDeclarations`, "too_many");
    }
    const seen = new Set<string>();
    const functionDeclarations = tool.functionDeclarations.map((declaration, declarationIndex) => {
      const declarationPath = `${path}.functionDeclarations[${declarationIndex}]`;
      if (!isObject(declaration)) reject(declarationPath, "invalid_type");
      assertOnlyKeys(declaration, ["name", "description", "parameters"], declarationPath);
      const name = requireString(declaration.name, `${declarationPath}.name`, GEMINI_REQUEST_LIMITS.maxFunctionNameChars);
      if (!FUNCTION_NAME_PATTERN.test(name)) reject(`${declarationPath}.name`, "invalid_function_name");
      if (seen.has(name)) reject(`${declarationPath}.name`, "duplicate_function_name");
      seen.add(name);
      const sanitized: JsonObject = { name };
      if (declaration.description !== undefined) {
        sanitized.description = requireString(
          declaration.description,
          `${declarationPath}.description`,
          GEMINI_REQUEST_LIMITS.maxFunctionDescriptionChars
        );
      }
      if (declaration.parameters !== undefined) {
        sanitized.parameters = boundedJsonObject(
          declaration.parameters,
          `${declarationPath}.parameters`,
          GEMINI_REQUEST_LIMITS.maxFunctionParametersJsonChars
        );
      }
      return sanitized;
    });
    return { functionDeclarations };
  });
}

function sanitizeToolConfig(value: unknown): JsonObject {
  if (!isObject(value)) reject("toolConfig", "invalid_type");
  assertOnlyKeys(value, ["functionCallingConfig"], "toolConfig");
  const calling = value.functionCallingConfig;
  if (!isObject(calling)) reject("toolConfig.functionCallingConfig", "invalid_type");
  assertOnlyKeys(calling, ["mode", "allowedFunctionNames"], "toolConfig.functionCallingConfig");
  const config: JsonObject = {};
  if (calling.mode !== undefined) {
    const mode = requireString(calling.mode, "toolConfig.functionCallingConfig.mode", 16);
    if (!ALLOWED_FUNCTION_CALLING_MODES.has(mode)) reject("toolConfig.functionCallingConfig.mode", "invalid_mode");
    config.mode = mode;
  }
  if (calling.allowedFunctionNames !== undefined) {
    if (!Array.isArray(calling.allowedFunctionNames)) {
      reject("toolConfig.functionCallingConfig.allowedFunctionNames", "invalid_type");
    }
    if (calling.allowedFunctionNames.length > GEMINI_REQUEST_LIMITS.maxFunctionDeclarations) {
      reject("toolConfig.functionCallingConfig.allowedFunctionNames", "too_many");
    }
    config.allowedFunctionNames = calling.allowedFunctionNames.map((name, index) => {
      const path = `toolConfig.functionCallingConfig.allowedFunctionNames[${index}]`;
      const value = requireString(name, path, GEMINI_REQUEST_LIMITS.maxFunctionNameChars);
      if (!FUNCTION_NAME_PATTERN.test(value)) reject(path, "invalid_function_name");
      return value;
    });
  }
  return { functionCallingConfig: config };
}

/**
 * Validates a client-supplied `generateContent` body and returns a rebuilt
 * copy containing only allow-listed fields, or a machine-readable rejection
 * reason of the form `<reason>:<json.path>`.
 */
export function sanitizeGeminiRequestBody(input: unknown): GeminiSanitizeResult {
  try {
    if (!isObject(input)) reject("requestBody", "invalid_type");
    assertOnlyKeys(input, ["contents", "systemInstruction", "generationConfig", "tools", "toolConfig"], "requestBody");

    const budget: Budget = { textChars: 0, inlineImages: 0 };
    const body: JsonObject = { contents: sanitizeContents(input.contents, budget) };
    if (input.systemInstruction !== undefined) body.systemInstruction = sanitizeSystemInstruction(input.systemInstruction);
    if (input.generationConfig !== undefined) body.generationConfig = sanitizeGenerationConfig(input.generationConfig);
    if (input.tools !== undefined) body.tools = sanitizeTools(input.tools);
    if (input.toolConfig !== undefined) {
      if (input.tools === undefined) reject("toolConfig", "requires_tools");
      body.toolConfig = sanitizeToolConfig(input.toolConfig);
    }
    return { ok: true, body };
  } catch (error) {
    if (error instanceof RejectError) return { ok: false, error: error.message };
    throw error;
  }
}
