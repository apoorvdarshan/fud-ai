import { describe, expect, it } from "vitest";
import { DISCORD_INTERACTIONS_PATH, verifyDiscordSignature } from "../discord-interactions";

describe("discord interactions", () => {
  it("exports the interactions path", () => {
    expect(DISCORD_INTERACTIONS_PATH).toBe("/api/discord/interactions");
  });

  it("rejects garbage signatures", async () => {
    const ok = await verifyDiscordSignature(
      "{}",
      "00".repeat(64),
      "0",
      "0ce6e6014282b489ce0930fad550e978c7d623cc71040d14250223110a9a9f57",
    );
    expect(ok).toBe(false);
  });
});
