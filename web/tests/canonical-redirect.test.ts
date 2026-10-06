import { describe, expect, it } from "vitest";
import worker, { canonicalRedirect } from "../worker";

describe("canonical redirect", () => {
  it("sends the apex host, http, .html, and trailing slashes to the www URL", () => {
    const cases = [
      ["https://fud-ai.app/", "https://www.fud-ai.app/"],
      ["https://fud-ai.app/privacy", "https://www.fud-ai.app/privacy"],
      ["http://fud-ai.app/privacy", "https://www.fud-ai.app/privacy"],
      ["http://www.fud-ai.app/terms", "https://www.fud-ai.app/terms"],
      ["https://fud-ai.app/privacy.html", "https://www.fud-ai.app/privacy"],
      ["https://www.fud-ai.app/privacy.html", "https://www.fud-ai.app/privacy"],
      ["https://www.fud-ai.app/index.html", "https://www.fud-ai.app/"],
      ["https://www.fud-ai.app/privacy/", "https://www.fud-ai.app/privacy"],
      ["https://fud-ai.app/free-ai-calorie-tracker/?utm=share", "https://www.fud-ai.app/free-ai-calorie-tracker?utm=share"],
    ];
    for (const [from, to] of cases) {
      const response = canonicalRedirect(new Request(from));
      expect(response?.status, from).toBe(301);
      expect(response?.headers.get("Location"), from).toBe(to);
    }
  });

  it("leaves the canonical page and host-sensitive routes alone", () => {
    const stay = [
      "https://www.fud-ai.app/",
      "https://www.fud-ai.app/privacy",
      "https://www.fud-ai.app/privacy?ref=app",
      "https://fud-ai.app/api/discord/interactions",
      "https://fud-ai.app/api/hosted-ai/v1/models",
      "https://fud-ai.app/api/challenge/v1/leaderboard",
      "https://fud-ai.app/m/abcdefghijklmnopqrstuv",
      "https://fud-ai.app/.well-known/apple-app-site-association",
      "https://fud-ai.app/star-history.svg",
      "POST https://fud-ai.app/privacy.html",
    ];
    for (const entry of stay) {
      const request = entry.startsWith("POST ")
        ? new Request(entry.slice(5), { method: "POST" })
        : new Request(entry);
      expect(canonicalRedirect(request), entry).toBeNull();
    }
  });

  it("redirects before static assets are fetched", async () => {
    const response = await worker.fetch(new Request("https://fud-ai.app/terms.html"), {} as Env);
    expect(response.status).toBe(301);
    expect(response.headers.get("Location")).toBe("https://www.fud-ai.app/terms");
  });
});
