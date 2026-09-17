import { describe, expect, it } from 'vitest';

import {
  activeFast,
  caloriesOn,
  completedFastsOn,
  diaryReducer,
  foodEntriesOn,
  initialDiaryState,
  frequentEntries,
  isFavorite,
  waterTotalOn,
  type DiaryAction,
  type DiaryState,
} from '../src/domain/diary/diaryState';
import { makeFoodEntry } from '../src/domain/food/food';
import { createStore } from '../src/state/createStore';

const day = new Date(2026, 8, 15, 12, 0, 0);
const iso = (h: number, m = 0) => new Date(2026, 8, 15, h, m, 0).toISOString();

function run(actions: DiaryAction[], from: DiaryState = initialDiaryState): DiaryState {
  return actions.reduce(diaryReducer, from);
}

describe('diaryReducer — one store for food, water and fasting (#369)', () => {
  it('keeps water and fasting when a food change lands right after them', () => {
    // The Android race: food pipeline snapshotted stale state and wrote it back over a newer
    // water/fasting update. With a single reducer, later actions always see earlier ones.
    const state = run([
      { type: 'water/add', entry: { id: 'w1', date: iso(9), milliliters: 250 } },
      { type: 'fasting/start', session: { id: 'f1', startedAt: iso(20), goalMinutes: 16 * 60 } },
      { type: 'food/add', entry: makeFoodEntry({ name: 'Oats', calories: 300, protein: 10, carbs: 50, fat: 5, source: 'manual', timestamp: iso(8) }, 'e1') },
    ]);

    expect(waterTotalOn(state, day)).toBe(250);
    expect(activeFast(state)?.id).toBe('f1');
    expect(caloriesOn(state, day)).toBe(300);
  });

  it('emits exactly once per mutation and never for no-ops', () => {
    const store = createStore(diaryReducer, initialDiaryState);
    let emissions = 0;
    store.subscribe(() => {
      emissions += 1;
    });

    store.dispatch({ type: 'water/add', entry: { id: 'w1', date: iso(9), milliliters: 250 } });
    store.dispatch({ type: 'water/add', entry: { id: 'w1', date: iso(9), milliliters: 250 } }); // duplicate id
    store.dispatch({ type: 'water/delete', id: 'missing' });
    store.dispatch({ type: 'fasting/end', endedAt: iso(10) }); // no active fast

    expect(emissions).toBe(1);
    expect(store.getState().revision).toBe(1);
  });

  it('queues diary writes while paused and replays them after the import commit', () => {
    const store = createStore(diaryReducer, initialDiaryState);
    const imported = makeFoodEntry(
      { name: 'Oats', calories: 300, protein: 10, carbs: 50, fat: 5, source: 'manual', timestamp: iso(8) },
      'imported',
    );
    const concurrent = makeFoodEntry(
      { name: 'Apple', calories: 80, protein: 0, carbs: 20, fat: 0, source: 'manual', timestamp: iso(9) },
      'concurrent',
    );
    store.pause();
    store.dispatch({ type: 'food/add', entry: concurrent });
    store.dispatch({ type: 'water/add', entry: { id: 'w-live', date: iso(9), milliliters: 200 } });
    expect(store.getState().foodEntries).toHaveLength(0);
    store.applyImmediate({ type: 'food/replaceAll', entries: [imported] });
    store.applyImmediate({ type: 'water/replaceAll', entries: [{ id: 'w-imported', date: iso(8), milliliters: 250 }] });
    store.resume();
    expect(store.getState().foodEntries.map((e) => e.id)).toEqual(['imported', 'concurrent']);
    expect(store.getState().waterEntries.map((e) => e.id)).toEqual(['w-imported', 'w-live']);
  });

  it('is idempotent on water entry id (retried Watch/widget transfers)', () => {
    const state = run([
      { type: 'water/add', entry: { id: 'same', date: iso(9), milliliters: 250 } },
      { type: 'water/add', entry: { id: 'same', date: iso(9, 5), milliliters: 500 } },
    ]);
    expect(state.waterEntries).toHaveLength(1);
    expect(waterTotalOn(state, day)).toBe(250);
  });

  it('rejects non-positive water amounts', () => {
    const state = run([{ type: 'water/add', entry: { id: 'w', date: iso(9), milliliters: 0 } }]);
    expect(state).toBe(initialDiaryState);
  });

  it('allows only one active fast and no overlapping sessions', () => {
    const state = run([
      { type: 'fasting/start', session: { id: 'f1', startedAt: iso(8), goalMinutes: 16 * 60 } },
      { type: 'fasting/start', session: { id: 'f2', startedAt: iso(9), goalMinutes: 16 * 60 } },
    ]);
    expect(state.fastingSessions.map((s) => s.id)).toEqual(['f1']);

    const ended = diaryReducer(state, { type: 'fasting/end', endedAt: iso(12) });
    expect(activeFast(ended)).toBeUndefined();
    expect(completedFastsOn(ended, day).map((s) => s.id)).toEqual(['f1']);

    // Overlaps the completed 08:00–12:00 fast.
    const overlapping = diaryReducer(ended, { type: 'fasting/start', session: { id: 'f3', startedAt: iso(11), goalMinutes: 60 } });
    expect(overlapping).toBe(ended);
  });

  it('clamps fasting goals and never ends a fast before it started', () => {
    const state = run([
      { type: 'fasting/start', session: { id: 'f1', startedAt: iso(8), goalMinutes: 5 } },
      { type: 'fasting/end', endedAt: iso(7) },
    ]);
    const session = state.fastingSessions[0];
    expect(session?.goalMinutes).toBe(60);
    expect(session?.endedAt).toBe(iso(8));
  });

  it('cancelActive removes only the active fast', () => {
    const state = run([
      { type: 'fasting/start', session: { id: 'f1', startedAt: iso(1), goalMinutes: 120 } },
      { type: 'fasting/end', endedAt: iso(3) },
      { type: 'fasting/start', session: { id: 'f2', startedAt: iso(20), goalMinutes: 16 * 60 } },
      { type: 'fasting/cancelActive' },
    ]);
    expect(state.fastingSessions.map((s) => s.id)).toEqual(['f1']);
  });

  it('updates, deletes and favorites food entries', () => {
    const entry = makeFoodEntry({ name: 'Greek Yogurt', calories: 150, protein: 15, carbs: 8, fat: 4, source: 'manual', timestamp: iso(8) }, 'e1');
    let state = run([{ type: 'food/add', entry }]);
    expect(foodEntriesOn(state, day)).toHaveLength(1);

    state = diaryReducer(state, { type: 'food/update', entry: { ...entry, calories: 200 } });
    expect(caloriesOn(state, day)).toBe(200);

    state = diaryReducer(state, { type: 'food/toggleFavorite', entry });
    expect(isFavorite(state, { name: 'greek yogurt ' })).toBe(true);

    state = diaryReducer(state, { type: 'food/delete', id: 'e1' });
    expect(state.foodEntries).toHaveLength(0);
  });

  it('assigns a meal type from the timestamp when none is given', () => {
    const breakfast = makeFoodEntry({ name: 'a', calories: 1, protein: 0, carbs: 0, fat: 0, source: 'manual', timestamp: iso(7) }, 'a');
    const dinner = makeFoodEntry({ name: 'b', calories: 1, protein: 0, carbs: 0, fat: 0, source: 'manual', timestamp: iso(19) }, 'b');
    const lateSnack = makeFoodEntry({ name: 'c', calories: 1, protein: 0, carbs: 0, fat: 0, source: 'manual', timestamp: iso(23, 30) }, 'c');
    expect(breakfast.mealType).toBe('breakfast');
    expect(dinner.mealType).toBe('dinner');
    expect(lateSnack.mealType).toBe('snack');
  });

  it('hydrates without bumping the revision', () => {
    const state = diaryReducer(initialDiaryState, {
      type: 'hydrate',
      state: { waterEntries: [{ id: 'w', date: iso(9), milliliters: 300 }] },
    });
    expect(state.revision).toBe(0);
    expect(waterTotalOn(state, day)).toBe(300);
  });

  it('ranks frequent meals by name+calories count', () => {
    const state = run([
      { type: 'food/add', entry: makeFoodEntry({ name: 'Oats', calories: 300, protein: 10, carbs: 50, fat: 5, source: 'manual', timestamp: iso(8) }, 'a') },
      { type: 'food/add', entry: makeFoodEntry({ name: 'Oats', calories: 300, protein: 10, carbs: 50, fat: 5, source: 'manual', timestamp: iso(9) }, 'b') },
      { type: 'food/add', entry: makeFoodEntry({ name: 'Eggs', calories: 180, protein: 12, carbs: 1, fat: 14, source: 'manual', timestamp: iso(10) }, 'c') },
    ]);
    const frequent = frequentEntries(state, 10, day);
    expect(frequent.map((e) => e.name)).toEqual(['Oats', 'Eggs']);
    expect(frequent[0]?.id).toBe('b');
  });
});
