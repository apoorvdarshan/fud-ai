PRAGMA foreign_keys = ON;

CREATE TABLE challenge_period_scores (
  participant_id TEXT NOT NULL REFERENCES challenge_participants(participant_id) ON DELETE CASCADE,
  period TEXT NOT NULL CHECK(period IN ('daily', 'monthly')),
  period_start TEXT NOT NULL CHECK(length(period_start) = 10),
  overall_points INTEGER NOT NULL CHECK(overall_points BETWEEN 0 AND 185),
  activity_days INTEGER NOT NULL CHECK(activity_days BETWEEN 0 AND 31),
  nutrition_days INTEGER NOT NULL CHECK(nutrition_days BETWEEN 0 AND 31),
  consistency_days INTEGER NOT NULL CHECK(consistency_days BETWEEN 0 AND 31),
  hydration_days INTEGER NOT NULL CHECK(hydration_days BETWEEN 0 AND 31),
  balanced_days INTEGER NOT NULL CHECK(balanced_days BETWEEN 0 AND 31),
  longest_streak INTEGER NOT NULL CHECK(longest_streak BETWEEN 0 AND 31),
  rules_version TEXT NOT NULL,
  rules_accepted_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(participant_id, period, period_start),
  CHECK(nutrition_days <= consistency_days),
  CHECK(overall_points = activity_days + nutrition_days + consistency_days + hydration_days
    + balanced_days + max(0, longest_streak - 1))
);

CREATE INDEX challenge_period_rank_idx
  ON challenge_period_scores(period, period_start, overall_points DESC, balanced_days DESC, longest_streak DESC);
