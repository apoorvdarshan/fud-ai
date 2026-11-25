import { describe, expect, it } from "vitest";

import {
  ChallengeValidationError,
  normalizeDisplayName,
  parseCreateProfileInput,
  parseLeaderboardQuery,
  parsePatchProfileInput,
  parseReportInput,
  parseWeeklyScoreInput,
  parseWeekStart,
} from "../challenge-validation";

const NOW = new Date("2026-08-31T12:00:00.000Z");
const PARTICIPANT_ID = "3f067343-323d-4f47-8bcf-1cf4e2ea8c0a";

describe("profile validation", () => {
  it("normalizes a Unicode display name and social handle", () => {
    expect(
      parseCreateProfileInput({
        displayName: "  Élodie   Singh  ",
        socialPlatform: "instagram",
        socialHandle: "Elodie.Fit",
        acceptedRules: true,
        eligibilityAccepted: true,
      }),
    ).toEqual({
      displayName: "Élodie Singh",
      social: { socialPlatform: "instagram", socialHandle: "elodie.fit" },
    });
  });

  it("requires both rules and 18+ eligibility acceptance", () => {
    expect(() =>
      parseCreateProfileInput({
        displayName: "Apoorv D",
        acceptedRules: true,
        eligibilityAccepted: false,
      }),
    ).toThrowError(ChallengeValidationError);
  });

  it("requires paired social fields and platform-specific handles", () => {
    expect(() =>
      parseCreateProfileInput({
        displayName: "Apoorv D",
        socialPlatform: "x",
        acceptedRules: true,
        eligibilityAccepted: true,
      }),
    ).toThrow(/supplied together/);
    expect(() =>
      parseCreateProfileInput({
        displayName: "Apoorv D",
        socialPlatform: "x",
        socialHandle: "@apoorv",
        acceptedRules: true,
        eligibilityAccepted: true,
      }),
    ).toThrow(/X handles/);
    expect(() =>
      parseCreateProfileInput({
        displayName: "Apoorv D",
        socialPlatform: "instagram",
        socialHandle: "two..dots",
        acceptedRules: true,
        eligibilityAccepted: true,
      }),
    ).toThrow(/Instagram handles/);
  });

  it("supports an authenticated patch that clears social fields", () => {
    expect(parsePatchProfileInput({ socialPlatform: null, socialHandle: null })).toEqual({
      social: null,
    });
  });

  it("treats an explicit null social pair on join as absent", () => {
    expect(
      parseCreateProfileInput({
        displayName: "Apoorv D",
        socialPlatform: null,
        socialHandle: null,
        acceptedRules: true,
        eligibilityAccepted: true,
      }).social,
    ).toBeNull();
  });

  it("rejects URLs, emails, impersonation, controls, and focused banned terms", () => {
    for (const name of [
      "example.com",
      "me@example.com",
      "Fud AI Support",
      "Official Fud AI",
      "Friendly Admin",
      "Nazi Runner",
      "White Power Runner",
      "bad\u0000name",
    ]) {
      expect(() => normalizeDisplayName(name), name).toThrowError(ChallengeValidationError);
    }
  });

  it("allows reasonable punctuation and non-Latin names", () => {
    expect(normalizeDisplayName("O’Connor-Jr. 7")).toBe("O’Connor-Jr. 7");
    expect(normalizeDisplayName("अनन्या १२")).toBe("अनन्या १२");
  });
});

describe("weekly score validation", () => {
  const validScore = {
    weekStart: "2026-08-31",
    overallPoints: 14,
    activityDays: 2,
    nutritionDays: 3,
    consistencyDays: 4,
    hydrationDays: 5,
    activityKcal: 4_200,
  };

  it("accepts bounded integers and a sum-consistent overall score", () => {
    expect(parseWeeklyScoreInput(validScore, NOW)).toEqual(validScore);
  });

  it("rejects inconsistent overall points, decimals, and excessive kcal", () => {
    expect(() =>
      parseWeeklyScoreInput({ ...validScore, overallPoints: 13 }, NOW),
    ).toThrow(/must equal/);
    expect(() =>
      parseWeeklyScoreInput({ ...validScore, activityDays: 2.5 }, NOW),
    ).toThrow(/integer/);
    expect(() =>
      parseWeeklyScoreInput({ ...validScore, activityKcal: 14_001 }, NOW),
    ).toThrow(/14000/);
  });

  it("accepts only Mondays in the current week boundary window", () => {
    expect(parseWeekStart("2026-08-31")).toBe("2026-08-31");
    expect(() => parseWeekStart("2026-08-30")).toThrow(/Monday/);
    expect(() => parseWeeklyScoreInput({ ...validScore, weekStart: "2026-08-17" }, NOW)).toThrow(
      /current challenge week/,
    );
    expect(
      parseWeeklyScoreInput({ ...validScore, weekStart: "2026-08-24" }, NOW).weekStart,
    ).toBe("2026-08-24");
    expect(
      parseWeeklyScoreInput({ ...validScore, weekStart: "2026-09-07" }, NOW).weekStart,
    ).toBe("2026-09-07");
  });
});

describe("leaderboard and report validation", () => {
  it("validates category, week window, limit, and unknown query fields", () => {
    expect(
      parseLeaderboardQuery(
        new URL(
          "https://fud-ai.app/api/challenge/v1/leaderboard?category=activity&weekStart=2026-08-31&limit=100",
        ),
        NOW,
      ),
    ).toEqual({ category: "activity", weekStart: "2026-08-31", limit: 100, offset: 0, period: "weekly" });
    expect(() =>
      parseLeaderboardQuery(
        new URL(
          "https://fud-ai.app/api/challenge/v1/leaderboard?category=activity&weekStart=2026-08-31&limit=101",
        ),
        NOW,
      ),
    ).toThrow(/limit/);
    expect(() =>
      parseLeaderboardQuery(
        new URL(
          "https://fud-ai.app/api/challenge/v1/leaderboard?category=activity&weekStart=2026-08-31&debug=1",
        ),
        NOW,
      ),
    ).toThrow(/Unknown query/);
  });

  it("accepts deep pages and rejects unsafe or repeated offsets", () => {
    const url = new URL("https://fud-ai.app/api/challenge/v1/leaderboard?category=overall&weekStart=2026-08-31");
    url.searchParams.set("offset", "940000");
    expect(parseLeaderboardQuery(url, NOW).offset).toBe(940000);
    for (const value of ["-1", "0.5", "", "9007199254740991", "1e3"]) {
      url.searchParams.set("offset", value);
      expect(() => parseLeaderboardQuery(url, NOW)).toThrow(/offset/);
    }
    url.searchParams.set("offset", "20");
    url.searchParams.append("offset", "40");
    expect(() => parseLeaderboardQuery(url, NOW)).toThrow(/repeated/);
  });

  it("accepts a bounded report and rejects invalid categories or controls", () => {
    expect(
      parseReportInput({
        reportedParticipantId: PARTICIPANT_ID,
        reason: "spam",
        details: "Repeated promotional name",
      }),
    ).toEqual({
      reportedParticipantId: PARTICIPANT_ID,
      reason: "spam",
      details: "Repeated promotional name",
    });
    expect(
      parseReportInput({
        reportedParticipantId: PARTICIPANT_ID,
        reason: "other",
        details: "First line\n\nSecond\tline",
      }).details,
    ).toBe("First line Second line");
    expect(() =>
      parseReportInput({ reportedParticipantId: PARTICIPANT_ID, reason: "made_up" }),
    ).toThrow(/reason/);
    expect(() =>
      parseReportInput({
        reportedParticipantId: PARTICIPANT_ID,
        reason: "other",
        details: "bad\u0000details",
      }),
    ).toThrow(/control/);
  });
});
