import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { REACHED_AT_ASSIGNMENT, handleChallengeRequest, leaderboardRankingSelect } from "../challenge-api";

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
    ["tied-late", 14, 7, 7, 0, 0, 9000, "2026-09-02T00:00:00Z"],
    ["tied-early", 14, 7, 7, 0, 0, 100, "2026-09-01T00:00:00Z"],
    ["more-activity", 14, 7, 0, 7, 0, 50, "2026-09-03T00:00:00Z"],
    ["higher-overall", 16, 4, 4, 4, 4, 10, "2026-09-04T00:00:00Z"],
    ["more-balanced", 14, 7, 3, 2, 2, 10, "2026-09-06T00:00:00Z"],
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
             ${leaderboardRankingSelect(category)}
      FROM p
      LEFT JOIN s ON s.participant_id = p.participant_id
      ORDER BY rank ASC
    `).all() as Array<{ participant_id: string; rank: number }>;
    database.close();
    return rows;
  }

  it("gives every person their own place on overall", () => {
    const rows = places("overall");
    expect(rows.map((row) => row.rank)).toEqual([1, 2, 3, 4, 5]);
    expect(new Set(rows.map((row) => row.rank)).size).toBe(rows.length);
    expect(rows.map((row) => row.participant_id)).toEqual([
      "higher-overall",
      "more-balanced",
      "tied-early",
      "tied-late",
      "more-activity",
    ]);
  });

  it("keeps activity days first, and rewards balanced habits rather than extra calories", () => {
    const rows = places("activity");
    expect(rows.map((row) => row.participant_id)).toEqual([
      "more-balanced",
      "tied-early",
      "tied-late",
      "more-activity",
      "higher-overall",
    ]);
    expect(rows.map((row) => row.rank)).toEqual([1, 2, 3, 4, 5]);
  });

  it("keeps the original time when the same totals are saved again", () => {
    const database = new DatabaseSync(":memory:");
    database.exec(`
      CREATE TABLE challenge_weekly_scores (
        participant_id TEXT NOT NULL,
        week_start TEXT NOT NULL,
        overall_points INTEGER NOT NULL,
        activity_days INTEGER NOT NULL,
        nutrition_days INTEGER NOT NULL,
        consistency_days INTEGER NOT NULL,
        hydration_days INTEGER NOT NULL,
        activity_kcal INTEGER NOT NULL,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (participant_id, week_start)
      );
    `);
    database.prepare(
      `INSERT INTO challenge_weekly_scores (
        participant_id, week_start, overall_points, activity_days, nutrition_days,
        consistency_days, hydration_days, activity_kcal, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(participant_id, week_start) DO UPDATE SET
        overall_points = excluded.overall_points,
        activity_days = excluded.activity_days,
        nutrition_days = excluded.nutrition_days,
        consistency_days = excluded.consistency_days,
        hydration_days = excluded.hydration_days,
        activity_kcal = excluded.activity_kcal,
        updated_at = ${REACHED_AT_ASSIGNMENT}`,
    ).run("runner", "2026-09-21", 10, 3, 3, 2, 2, 400, "2026-09-22T00:00:00Z");
    database.prepare(
      `INSERT INTO challenge_weekly_scores (
        participant_id, week_start, overall_points, activity_days, nutrition_days,
        consistency_days, hydration_days, activity_kcal, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(participant_id, week_start) DO UPDATE SET
        overall_points = excluded.overall_points,
        activity_days = excluded.activity_days,
        nutrition_days = excluded.nutrition_days,
        consistency_days = excluded.consistency_days,
        hydration_days = excluded.hydration_days,
        activity_kcal = excluded.activity_kcal,
        updated_at = ${REACHED_AT_ASSIGNMENT}`,
    ).run("runner", "2026-09-21", 10, 3, 3, 2, 2, 900, "2026-09-24T00:00:00Z");
    const same = database.prepare(
      "SELECT updated_at FROM challenge_weekly_scores WHERE participant_id = ?",
    ).all("runner") as Array<{ updated_at: string }>;
    database.prepare(
      `INSERT INTO challenge_weekly_scores (
        participant_id, week_start, overall_points, activity_days, nutrition_days,
        consistency_days, hydration_days, activity_kcal, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(participant_id, week_start) DO UPDATE SET
        overall_points = excluded.overall_points,
        activity_days = excluded.activity_days,
        nutrition_days = excluded.nutrition_days,
        consistency_days = excluded.consistency_days,
        hydration_days = excluded.hydration_days,
        activity_kcal = excluded.activity_kcal,
        updated_at = ${REACHED_AT_ASSIGNMENT}`,
    ).run("runner", "2026-09-21", 11, 4, 3, 2, 2, 400, "2026-09-25T00:00:00Z");
    const improved = database.prepare(
      "SELECT updated_at FROM challenge_weekly_scores WHERE participant_id = ?",
    ).all("runner") as Array<{ updated_at: string }>;
    database.close();
    expect(same[0]?.updated_at).toBe("2026-09-22T00:00:00Z");
    expect(improved[0]?.updated_at).toBe("2026-09-25T00:00:00Z");
  });
});

describe("server-side challenge pages", () => {
  it("walks past 100 ranked users and keeps the viewer separate", async () => {
    const db = new DatabaseSync(":memory:");
    try {
      db.exec(readFileSync("migrations/0001_weekly_challenge.sql", "utf8"));
      db.exec(readFileSync("migrations/0007_challenge_periods.sql", "utf8"));
      const now = new Date();
      const today = now.toISOString().slice(0, 10);
      const monday = new Date(now);
      monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
      const week = monday.toISOString().slice(0, 10);
      const insert = db.prepare(`
        INSERT INTO challenge_participants VALUES (?, ?, ?, NULL, NULL, ?, 'test', ?, 'test', ?, ?, ?)
      `);
      for (let index = 1; index <= 137; index++) {
        const id = `runner-${String(index).padStart(3, "0")}`;
        insert.run(id, String(index).padStart(64, "0"), `Runner ${index}`, today, today, today, today, today);
      }
      const env = testEnvironment();
      type SQLValue = string | number;
      const session = {
        prepare: (sql: string) => ({
          bind: (...bindings: SQLValue[]) => ({
            first: async () => ({ participant_id: "runner-137" }),
            run: async () => db.prepare(sql).run(...bindings),
            all: () => {
              const statement = db.prepare(sql);
              if (/^\s*(WITH|SELECT)/i.test(sql)) return { results: statement.all(...bindings) };
              statement.run(...bindings);
              return { results: [] };
            },
          }),
        }),
        batch: async (queries: { all: () => unknown }[]) => queries.map((query) => query.all()),
      };
      env.CHALLENGE_DB = { withSession: () => session } as unknown as Env["CHALLENGE_DB"];
      const request = (path: string, method = "GET", body?: unknown) => handleChallengeRequest(
        new Request(`https://fud-ai.app/api/challenge/v1/${path}`, {
          method,
          headers: { Authorization: `Bearer ${"a".repeat(43)}`, "Content-Type": "application/json" },
          ...(body !== undefined && { body: JSON.stringify(body) }),
        }), env,
      );
      for (const [offset, length] of [[0, 20], [20, 20], [100, 20], [120, 17], [140, 0]] as const) {
        const page = await request(`leaderboard?category=overall&weekStart=${week}&limit=20&offset=${offset}`);
        expect(page.status).toBe(200);
        const data = await page.json() as { rankings: { rank: number }[]; totalCount: number; viewer: { rank: number } };
        expect(data.totalCount).toBe(137);
        expect(data.viewer.rank).toBe(137);
        expect(data.rankings.map(({ rank }) => rank)).toEqual(Array.from({ length }, (_, i) => offset + i + 1));
      }

      const daily = `leaderboard?period=daily&periodStart=${today}&category=overall&limit=20`;
      await expect((await request(daily)).json()).resolves.toMatchObject({ totalCount: 0, rankings: [], viewer: null });
      const score = { period: "daily", periodStart: today, acceptedRules: true, activityDays: 1,
        nutritionDays: 1, consistencyDays: 1, hydrationDays: 0, balancedDays: 1, longestStreak: 1 };
      expect((await request("period-score", "PUT", { ...score, acceptedRules: false })).status).toBe(400);
      expect((await request("period-score", "PUT", score)).status).toBe(200);
      await expect((await request(daily)).json()).resolves.toMatchObject({
        totalCount: 1, period: "daily", viewer: { score: 4, balancedDays: 1, rank: 1 },
      });
      expect((await request("period-score?period=daily", "DELETE")).status).toBe(200);
      await expect((await request(daily)).json()).resolves.toMatchObject({ totalCount: 0, viewer: null });
      await expect((await request(`leaderboard?category=activity&weekStart=${week}`)).json())
        .resolves.toMatchObject({ totalCount: 137 });
    } finally {
      db.close();
    }
  });
});
