import { ai, voice } from "./guides-ai.mjs";
import { logging, nutrition, workouts } from "./guides-tracking.mjs";
import { sync, privacy, developer } from "./guides-platform.mjs";
import { comparisons } from "./guides-comparisons.mjs";

export const collections = [ai, voice, logging, nutrition, workouts, sync, privacy, comparisons, developer];

export const established = {
  scanner: ["AI food scanner overview", "/ai-food-scanner"],
  free: ["What’s free in Fud AI", "/free-ai-calorie-tracker"],
  source: ["Open-source calorie tracker", "/open-source-calorie-tracker"],
  cal: ["Fud AI vs Cal AI", "/vs-cal-ai"],
};
