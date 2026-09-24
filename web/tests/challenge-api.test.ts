import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";

import { handleChallengeRequest, leaderboardOrder } from "../challenge-api";

function testEnvironment(participant: unknown = null): Env {
  const session = {
    prepare: () => ({
      bind: () => ({
        first: async () => participant,
        run: async () => ({ success: true }),
      }),
    }),
  };
  const rateLimiter = {
    limit: async () => ({ success: true }),
  };

  return {
    CHALLENGE_DB: {
      withSession: () => session,
    },
    CHALLENGE_API_RATE_LIMITER: rateLimiter,
    CHALLENGE_CREATE_RATE_LIMITER: rateLimiter,
  } as unknown as Env;
}

describe("challenge API error responses", () => {
  it("turns an asynchronously rejected authentication into the documented 401 JSON", async () => {
    const response = await handleChallengeRequest(
      new Request(
        "https://fud-ai.app/api/challenge/v1/leaderboard"
          + "?category=overall&weekStart=2026-08-31&limit=100",
        {
          headers: {
            Authorization: `Bearer ${"a".repeat(43)}`,
          },
        },
      ),
      testEnvironment(),
    );

    expect(response.status).toBe(401);
    expect(response.headers.get("Content-Type")).toContain("application/json");
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "unauthorized" },
    });
  });

  it("keeps profile deletion idempotent after the participant row is gone", async () => {
    const request = () => new Request("https://fud-ai.app/api/challenge/v1/profile", {
      method: "DELETE",
      headers: { Authorization: `Bearer ${"b".repeat(43)}` },
    });
    const environment = testEnvironment();

    const first = await handleChallengeRequest(request(), environment);
    const retry = await handleChallengeRequest(request(), environment);

    expect(first.status).toBe(200);
    expect(retry.status).toBe(200);
    await expect(retry.json()).resolves.toEqual({ deleted: true });
  });

  it("turns asynchronous body validation failures into the documented 400 JSON", async () => {
    const response = await handleChallengeRequest(
      new Request("https://fud-ai.app/api/challenge/v1/profile", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          displayName: "QA Runner",
          acceptedRules: false,
          eligibilityAccepted: true,
        }),
      }),
      testEnvironment(),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      error: {
        code: "validation_error",
        fields: ["acceptedRules"],
      },
    });
  });
});

describe("leaderboard places", () => {
  const people = [
    ["tied-late", 14, 7, 7, 0, 0, 100, "2026-09-02T00:00:00Z"],
    ["tied-early", 14, 7, 7, 0, 0, 100, "2026-09-01T00:00:00Z"],
    ["more-activity", 14, 7, 0, 7, 0, 50, "2026-09-03T00:00:00Z"],
    ["higher-overall", 16, 4, 4, 4, 4, 10, "2026-09-04T00:00:00Z"],
  ] as const;

  function places(category: "overall" | "activity" | "nutrition" | "consistency" | "hydration") {
    const database = new DatabaseSync(":memory:");
    database.exec(`
      CREATE TABLE p (
        participant_id TEXT PRIMARY KEY,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE s (
        participant_id TEXT PRIMARY KEY,
        overall_points INTEGER NOT NULL,
        activity_days INTEGER NOT NULL,
        nutrition_days INTEGER NOT NULL,
        consistency_days INTEGER NOT NULL,
        hydration_days INTEGER NOT NULL,
        activity_kcal INTEGER NOT NULL,
        updated_at TEXT NOT NULL
      );
    `);
    const insertParticipant = database.prepare(
      "INSERT INTO p (participant_id, updated_at) VALUES (?, ?)",
    );
    const insertScore = database.prepare(
      `INSERT INTO s (
        participant_id, overall_points, activity_days, nutrition_days,
        consistency_days, hydration_days, activity_kcal, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    );
    for (const [id, overall, activity, nutrition, consistency, hydration, kcal, updated] of people) {
      insertParticipant.run(id, updated);
      insertScore.run(id, overall, activity, nutrition, consistency, hydration, kcal, updated);
    }
    const rows = database.prepare(`
      SELECT p.participant_id AS participant_id,
             ROW_NUMBER() OVER (ORDER BY ${leaderboardOrder(category)}) AS rank
      FROM p
      LEFT JOIN s ON s.participant_id = p.participant_id
      ORDER BY rank ASC
    `).all() as Array<{ participant_id: string; rank: number }>;
    database.close();
    return rows;
  }

  it("gives every person their own place on overall", () => {
    const rows = places("overall");
    expect(rows.map((row) => row.rank)).toEqual([1, 2, 3, 4]);
    expect(new Set(rows.map((row) => row.rank)).size).toBe(rows.length);
    expect(rows.map((row) => row.participant_id)).toEqual([
      "higher-overall",
      "tied-early",
      "tied-late",
      "more-activity",
    ]);
  });

  it("keeps activity days ahead of calories, then the other counts", () => {
    const rows = places("activity");
    expect(rows.map((row) => row.participant_id)).toEqual([
      "tied-early",
      "tied-late",
      "more-activity",
      "higher-overall",
    ]);
    expect(rows.map((row) => row.rank)).toEqual([1, 2, 3, 4]);
  });
});
