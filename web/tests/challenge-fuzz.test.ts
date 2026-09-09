import fc from "fast-check";
import { describe, expect, it } from "vitest";
import {
  ChallengeValidationError,
  parseCreateProfileInput,
  parsePatchProfileInput,
  parseReportInput,
  parseWeeklyScoreInput,
} from "../challenge-validation";

const now = new Date("2026-08-31T12:00:00Z");
const parsers = [parseCreateProfileInput, parsePatchProfileInput, parseReportInput,
  (value: unknown) => parseWeeklyScoreInput(value, now)];
// fast-check reports a seed and shrink path on failure, allowing exact replay.
const options = { numRuns: 1000 };

describe("generated challenge API inputs", () => {
  it("handles arbitrary JSON without unexpected exceptions", () => {
    fc.assert(fc.property(fc.jsonValue(), value => {
      for (const parse of parsers) {
        try { parse(value); }
        catch (error) { expect(error).toBeInstanceOf(ChallengeValidationError); }
      }
    }), options);
  });

  it("round-trips valid score combinations", () => {
    fc.assert(fc.property(
      fc.tuple(fc.integer({ min: 0, max: 7 }), fc.integer({ min: 0, max: 7 }),
        fc.integer({ min: 0, max: 7 }), fc.integer({ min: 0, max: 7 })),
      fc.integer({ min: 0, max: 14000 }),
      ([activityDays, nutritionDays, consistencyDays, hydrationDays], activityKcal) => {
        const input = { weekStart: "2026-08-31", activityDays, nutritionDays,
          consistencyDays, hydrationDays, activityKcal,
          overallPoints: activityDays + nutritionDays + consistencyDays + hydrationDays };
        expect(parseWeeklyScoreInput(input, now)).toEqual(input);
        // Even a one-point forged aggregate must fail validation.
        expect(() => parseWeeklyScoreInput({ ...input, overallPoints: input.overallPoints + 1 }, now))
          .toThrow(ChallengeValidationError);
      }), options);
  });

  it("rejects arbitrary unrecognized profile fields", () => {
    fc.assert(fc.property(fc.string(), fc.jsonValue(), (key, value) => {
      fc.pre(!["displayName", "socialPlatform", "socialHandle", "acceptedRules", "eligibilityAccepted"].includes(key));
      const input = { displayName: "Taylor", acceptedRules: true, eligibilityAccepted: true, [key]: value };
      expect(() => parseCreateProfileInput(input)).toThrow(ChallengeValidationError);
    }), options);
  });

  it("rejects out-of-range activity values", () => {
    fc.assert(fc.property(fc.oneof(fc.integer({ max: -1 }), fc.integer({ min: 8 })), activityDays => {
      expect(() => parseWeeklyScoreInput({ weekStart: "2026-08-31", activityDays,
        nutritionDays: 0, consistencyDays: 0, hydrationDays: 0,
        activityKcal: 0, overallPoints: activityDays }, now)).toThrow(ChallengeValidationError);
    }), options);
  });
});
