import { describe, expect, it } from 'vitest';

import { bodyReducer, entriesInRange, formatWeight, initialBodyState, isBodyFatEntry, isWeightEntry, latestWeight, weightKgFromDisplay } from '../src/domain/body/bodyState';
import { makeFoodEntry } from '../src/domain/food/food';
import {
  availableProgressMetrics,
  computeFoodRangeStats,
  dayAxis,
  downsampleTrend,
  loggingStats,
  netChange,
  timeRangeDates,
  timeRangeDays,
  trendAxis,
  trendDomain,
  type TrendPoint,
} from '../src/domain/progress/progress';

const day = (offset: number, hour = 9) => {
  const d = new Date(2026, 8, 16, hour, 0, 0, 0);
  d.setDate(d.getDate() + offset);
  return d;
};
const now = day(0, 12);

describe('body state', () => {
  it('keeps entries sorted, rejects out-of-range values and duplicate ids, seeds only once', () => {
    let state = bodyReducer(initialBodyState, { type: 'weight/add', entry: { id: 'b', date: day(-1).toISOString(), weightKg: 80 } });
    state = bodyReducer(state, { type: 'weight/add', entry: { id: 'a', date: day(-3).toISOString(), weightKg: 81 } });
    expect(state.weightEntries.map((e) => e.id)).toEqual(['a', 'b']);
    expect(latestWeight(state)?.id).toBe('b');

    expect(bodyReducer(state, { type: 'weight/add', entry: { id: 'c', date: day(0).toISOString(), weightKg: 5 } })).toBe(state);
    expect(bodyReducer(state, { type: 'weight/add', entry: { id: 'a', date: day(0).toISOString(), weightKg: 70 } })).toBe(state);
    expect(bodyReducer(state, { type: 'weight/seedIfEmpty', entry: { id: 'seed', date: day(0).toISOString(), weightKg: 70 } })).toBe(state);
    const seeded = bodyReducer(initialBodyState, { type: 'weight/seedIfEmpty', entry: { id: 'seed', date: day(0).toISOString(), weightKg: 70 } });
    expect(seeded.weightEntries).toHaveLength(1);

    expect(bodyReducer(state, { type: 'bodyFat/add', entry: { id: 'f', date: day(0).toISOString(), bodyFatFraction: 0.9 } })).toBe(state);
    const withFat = bodyReducer(state, { type: 'bodyFat/add', entry: { id: 'f', date: day(0).toISOString(), bodyFatFraction: 0.22 } });
    expect(withFat.bodyFatEntries).toHaveLength(1);
  });

  it('drops malformed persisted records on hydrate instead of throwing', () => {
    const state = bodyReducer(initialBodyState, {
      type: 'hydrate',
      state: {
        weightEntries: [null, { id: 'x' }, { id: 'no-date', weightKg: 80 }, { id: 'bad-kg', date: day(0).toISOString(), weightKg: 'heavy' }, { id: 'ok', date: day(-1).toISOString(), weightKg: 80 }] as unknown as never,
        bodyFatEntries: 'not an array' as unknown as never,
      },
    });
    expect(state.weightEntries.map((e) => e.id)).toEqual(['ok']);
    expect(state.bodyFatEntries).toEqual([]);
    expect(isWeightEntry({ id: 'a', date: 'd', weightKg: 70 })).toBe(true);
    expect(isBodyFatEntry({ id: 'a', date: 'd', bodyFatFraction: 0.9 })).toBe(false);
  });

  it('filters by local calendar day and converts units', () => {
    const entries = [
      { id: '1', date: day(-10).toISOString(), weightKg: 80 },
      { id: '2', date: day(-2, 23).toISOString(), weightKg: 79 },
      { id: '3', date: day(0, 1).toISOString(), weightKg: 78 },
    ];
    const { start, end } = timeRangeDates('1W', now);
    expect(entriesInRange(entries, start, end).map((e) => e.id)).toEqual(['2', '3']);
    expect(formatWeight(80, false)).toBe('176.4 lbs');
    expect(weightKgFromDisplay(176.37, false)).toBeCloseTo(80, 1);
  });
});

describe('time range & metrics', () => {
  it('matches the iOS day counts and inclusive window', () => {
    expect(timeRangeDays('1W')).toBe(7);
    expect(timeRangeDays('All')).toBe(3650);
    const { start, end } = timeRangeDates('1M', now);
    expect(end.getHours()).toBe(0);
    expect(Math.round((end.getTime() - start.getTime()) / 86_400_000)).toBe(29);
  });

  it('only offers Body Fat and Workouts when there is data', () => {
    expect(availableProgressMetrics({ bodyFatAvailable: false, workoutBurnAvailable: false })).toEqual(['weight']);
    expect(availableProgressMetrics({ bodyFatAvailable: true, workoutBurnAvailable: true })).toEqual(['weight', 'bodyFat', 'workouts']);
  });
});

describe('trend helpers', () => {
  const series = (count: number, spanDays: number): TrendPoint[] =>
    Array.from({ length: count }, (_, i) => ({ time: day(-spanDays).getTime() + (i * spanDays * 86_400_000) / (count - 1), value: 80 - i * 0.01 }));

  it('passes sparse series through and buckets dense ones', () => {
    const sparse = series(20, 30);
    expect(downsampleTrend(sparse)).toEqual(sparse);
    const dense = series(500, 700);
    const sampled = downsampleTrend(dense);
    expect(sampled.length).toBeLessThanOrEqual(24);
    expect(sampled.length).toBeGreaterThan(10);
    expect(sampled[0]!.value).toBeGreaterThan(sampled[sampled.length - 1]!.value);
  });

  it('derives axis strides from the date span and pads the domain', () => {
    expect(trendAxis(series(7, 6)).strideDays).toBe(1);
    expect(trendAxis(series(30, 29)).strideDays).toBe(5);
    expect(trendAxis(series(90, 89)).strideDays).toBe(14);
    const yearly: TrendPoint[] = [
      { time: new Date(2025, 0, 1).getTime(), value: 80 },
      { time: new Date(2026, 5, 1).getTime(), value: 75 },
    ];
    expect(trendAxis(yearly).showsYear).toBe(true);
    expect(trendDomain([80, 82], 70, [0, 200])).toEqual([68, 84]);
    expect(trendDomain([], undefined, [0, 200])).toEqual([0, 200]);
    expect(netChange(yearly)).toBe(-5);
  });
});

describe('food range stats & streaks', () => {
  const food = (id: string, offset: number, calories: number, extra: Partial<Parameters<typeof makeFoodEntry>[0]> = {}) =>
    makeFoodEntry({ name: id, calories, protein: 20, carbs: 30, fat: 10, source: 'manual', timestamp: day(offset).toISOString(), ...extra }, id);
  const targets = { calories: 2000, protein: 150, carbs: 200, fat: 60 };

  it('buckets by day inside the window and averages over logged days only', () => {
    const entries = [food('a', 0, 600, { fiber: 10 }), food('b', 0, 400), food('c', -1, 1800, { fiber: 5 }), food('old', -40, 999), food('future', 3, 999)];
    const stats = computeFoodRangeStats(entries, 30, targets, {}, now);
    expect(stats.dailyCalories.map((d) => d.calories)).toEqual([1800, 1000]);
    expect(stats.loggedDays).toBe(2);
    expect(stats.avgProtein).toBe(30);
    const fiber = stats.nutrientItems.find((i) => i.id === 'fiber');
    expect(fiber?.current).toBe(7.5);
    expect(fiber?.goal).toBe(0);
    expect(stats.nutrientItems.some((i) => i.id === 'sodium')).toBe(false);
  });

  it('reports an empty window without dividing by zero', () => {
    expect(computeFoodRangeStats([], 7, targets, {}, now)).toMatchObject({ loggedDays: 0, avgProtein: 0, dailyCalories: [] });
  });

  it('computes current and best streaks and days on target', () => {
    const entries = [food('1', 0, 1500), food('2', -1, 2500), food('3', -2, 1900), food('4', -5, 1200), food('5', -6, 1300), food('6', -7, 1000)];
    const stats = loggingStats(entries, 2000, now);
    expect(stats.currentStreak).toBe(3);
    expect(stats.bestStreak).toBe(3);
    expect(stats.daysOnTarget).toBe(5);
    expect(stats.totalEntries).toBe(6);
    expect(loggingStats([food('y', -1, 100)], 2000, now).currentStreak).toBe(1);
  });
});

describe('weekly challenge score', () => {
  it('counts Monday-based week days within goal bands and caps points at 28', async () => {
    const { weeklyChallengeScore, weeklyChallengeWeek } = await import('../src/domain/progress/weeklyChallenge');
    // 2026-09-16 is a Wednesday; the challenge week starts Monday 2026-09-14.
    const wednesday = new Date(2026, 8, 16, 12);
    expect(weeklyChallengeWeek(wednesday).key).toBe('2026-09-14');
    const iso = (d: number, h = 9) => new Date(2026, 8, d, h).toISOString();
    const score = weeklyChallengeScore({
      now: wednesday,
      foods: [
        { date: iso(14), calories: 1900 },
        { date: iso(15), calories: 1200 },
        { date: iso(16), calories: 2100 },
        { date: iso(13), calories: 2000 },
        { date: iso(17), calories: 2000 },
      ],
      water: [
        { date: iso(14), milliliters: 1500 },
        { date: iso(14), milliliters: 600 },
        { date: iso(15), milliliters: 500 },
      ],
      activities: [{ date: iso(15), calories: 2500 }, { date: iso(16) }],
      calorieGoal: 2000,
      hydrationEnabled: true,
      hydrationGoalMilliliters: 2000,
    });
    expect(score).toEqual({
      weekStart: '2026-09-14',
      // iso(16) has no calorie estimate — native scoring requires calories > 0.
      activityDays: 1,
      nutritionDays: 2,
      consistencyDays: 3,
      hydrationDays: 1,
      overallPoints: 7,
      activityKcal: 2000,
    });
  });
});

describe('day axis', () => {
  it('places bars at their calendar offset so sparse days leave gaps, and spaces labels', () => {
    const axis = dayAxis(['2026-09-30', '2026-09-01', '2026-09-02']);
    expect(axis.dayCount).toBe(30);
    expect(axis.offset('2026-09-01')).toBe(0);
    expect(axis.offset('2026-09-02')).toBe(1);
    expect(axis.offset('2026-09-30')).toBe(29);
    // ~5 labels, calendar-spaced from the first plotted day.
    expect(axis.ticks).toEqual(['2026-09-01', '2026-09-07', '2026-09-13', '2026-09-19', '2026-09-25']);

    const week = dayAxis(['2026-09-10', '2026-09-16']);
    expect(week.dayCount).toBe(7);
    expect(week.ticks).toHaveLength(7);

    const single = dayAxis(['2026-09-16']);
    expect(single).toMatchObject({ dayCount: 1, ticks: ['2026-09-16'] });
    expect(dayAxis([])).toMatchObject({ dayCount: 1, ticks: [] });
  });
});
