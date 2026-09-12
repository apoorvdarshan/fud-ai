import { describe, expect, it } from "vitest";
import { handleHostedAIRequest } from "../hosted-ai-api";

const SECRET = "test-secret";

function env(overrides: Record<string, string> = {}) {
  return {
    FUD_HOSTED_AI_APP_SECRET: SECRET,
    GEMINI_API_KEY: "gemini-key",
    DEEPGRAM_API_KEY: "dg-key",
    ...overrides,
  };
}

describe("hosted-ai-api auth", () => {
  it("rejects missing authorization", async () => {
    const response = await handleHostedAIRequest(
      new Request("https://fud-ai.app/api/hosted-ai/v1/generate", { method: "POST" }),
      env()
    );
    expect(response.status).toBe(401);
  });

  it("rejects invalid authorization", async () => {
    const response = await handleHostedAIRequest(
      new Request("https://fud-ai.app/api/hosted-ai/v1/generate", {
        method: "POST",
        headers: { Authorization: "Bearer wrong" },
      }),
      env()
    );
    expect(response.status).toBe(401);
  });

  it("returns 400 for missing prompt on generate", async () => {
    const response = await handleHostedAIRequest(
      new Request("https://fud-ai.app/api/hosted-ai/v1/generate", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${SECRET}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({}),
      }),
      env()
    );
    expect(response.status).toBe(400);
  });

  it("returns 503 when gemini key missing", async () => {
    const response = await handleHostedAIRequest(
      new Request("https://fud-ai.app/api/hosted-ai/v1/generate", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${SECRET}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ prompt: "hello" }),
      }),
      env({ GEMINI_API_KEY: "" })
    );
    expect(response.status).toBe(503);
  });
});
