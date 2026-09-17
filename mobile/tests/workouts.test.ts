import { describe, expect, it } from 'vitest';

import {
  emptyExerciseFilter,
  exerciseCatalog,
  exerciseFilterOptions,
  exerciseInstructions,
  filterExercises,
  frameURL,
  hasActiveFilters,
  metadataSummary,
  metadataTitle,
  normalizedFrameDigest,
  representativeFrameURL,
} from '../src/domain/workouts/exerciseLibrary';
import {
  activeDraftKey,
  convertDraftSetWeight,
  dailyBurn,
  estimateBurn,
  finishDraft,
  hasLoggedWork,
  initialWorkoutsState,
  isReliableBurn,
  isSetPerformed,
  liftHistory,
  performedSetCount,
  repCount,
  totalBurn,
  workoutsReducer,
  type CompletedExercise,
  type WorkoutDraft,
} from '../src/domain/workouts/workoutSessions';

describe('exercise catalog', () => {
  const catalog = exerciseCatalog();

  it('bundles the FreeExerciseDB corpus with Title Case metadata and CDN frame names', () => {
    expect(catalog.length).toBeGreaterThan(850);
    const situp = catalog.find((i) => i.id === '3_4_Sit-Up');
    expect(situp).toMatchObject({ name: '3/4 Sit-Up', level: 'Beginner', equipment: 'Body Only', category: 'Strength', primaryMuscles: ['Abdominals'] });
    expect(metadataSummary(situp!)).toBe('Strength - Pull - Compound');
    // The manifest digest rides along as the cache key, exactly like `WorkoutFrameStore.remoteURL`.
    expect(situp!.maleFrameDigests).toHaveLength(situp!.frameCount);
    expect(representativeFrameURL(situp!, 'male')).toBe(`https://assets.fud-ai.app/workout-vectors/v2/3_4_Sit-Up_male_v2_2.png?v=${situp!.maleFrameDigests[2]}`);
    expect(frameURL(situp!, 'female', 0)).toBe(`https://assets.fud-ai.app/workout-vectors/v2/3_4_Sit-Up_female_v2_0.png?v=${situp!.femaleFrameDigests[0]}`);
    expect(frameURL(situp!, 'female', 9)).toBeUndefined();
    // Without a usable digest the URL is the bare frame, never `?v=`.
    expect(frameURL({ id: 'X', frameCount: 1, maleFrameDigests: ['not hex'] }, 'male', 0)).toBe('https://assets.fud-ai.app/workout-vectors/v2/X_male_v2_0.png');
    expect(normalizedFrameDigest('9FA2E5292159AFF0')).toBe('9fa2e5292159aff0');
    expect(normalizedFrameDigest('abc')).toBeUndefined();
    expect(exerciseInstructions('3_4_Sit-Up').length).toBeGreaterThan(3);
    expect(exerciseInstructions('nope')).toEqual([]);
    expect(catalog.map((i) => i.name)).toEqual([...catalog.map((i) => i.name)].sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' })));
  });

  it('title-cases metadata like ExerciseLibraryItem.metadataTitle', () => {
    expect(metadataTitle('e-z curl bar')).toBe('E-Z Curl Bar');
    expect(metadataTitle('  ')).toBe('Unspecified');
    expect(metadataTitle(null)).toBe('Unspecified');
  });

  it('filters by tokens and option sets, then sorts', () => {
    const options = exerciseFilterOptions(catalog);
    expect(options.levels).toEqual(['Beginner', 'Intermediate', 'Expert']);
    expect(options.equipment).toContain('Dumbbell');
    expect(options.primaryMuscles).toContain('Chest');

    const chestDumbbell = filterExercises(catalog, { ...emptyExerciseFilter, searchText: 'press', equipment: ['Dumbbell'], primaryMuscles: ['Chest'] });
    expect(chestDumbbell.length).toBeGreaterThan(0);
    expect(chestDumbbell.every((i) => i.equipment === 'Dumbbell' && i.primaryMuscles.includes('Chest') && i.searchableText.includes('press'))).toBe(true);

    const byLevel = filterExercises(catalog.slice(0, 40), { ...emptyExerciseFilter, sort: 'level' });
    const levels = byLevel.map((i) => i.level);
    const order = ['Beginner', 'Intermediate', 'Expert'];
    expect(levels.map((l) => order.indexOf(l))).toEqual([...levels.map((l) => order.indexOf(l))].sort((a, b) => a - b));
    expect(hasActiveFilters(emptyExerciseFilter)).toBe(false);
    expect(hasActiveFilters({ ...emptyExerciseFilter, sort: 'level' })).toBe(true);
  });
});

describe('workout sessions', () => {
  const day = '2026-09-16';
  const now = new Date(2026, 8, 16, 18, 30);
  let n = 0;
  const id = () => `id-${(n += 1)}`;

  const draft: WorkoutDraft = {
    dayKey: day,
    startedAt: new Date(2026, 8, 16, 17, 40).toISOString(),
    exercises: [
      {
        id: 'ex1',
        itemID: 'Barbell_Bench_Press_-_Medium_Grip',
        name: 'Barbell Bench Press',
        targetMuscles: ['Chest'],
        equipment: 'Barbell',
        category: 'Strength',
        sets: [
          { id: 's1', weight: '185', reps: '8', rpe: '7' },
          { id: 's2', weight: '185', reps: '8', rpe: '8' },
          { id: 's3', weight: '', reps: '', rpe: '' },
        ],
      },
      { id: 'ex2', itemID: 'Plank', name: 'Plank', targetMuscles: ['Abdominals'], equipment: 'Body Only', category: 'Strength', sets: [{ id: 's4', weight: '', reps: '', rpe: '' }] },
    ],
  };

  it('builds a session from a draft, dropping unperformed sets and exercises, with a burn estimate', () => {
    const session = finishDraft(draft, id, now, { split: 'fullBody', rpeScale: 'strength', weightUnit: 'lbs' }, 80);
    expect(session.diaryDateKey).toBe(day);
    expect(session.durationSeconds).toBe(50 * 60);
    expect(session.exercises).toHaveLength(1);
    expect(session.exercises[0]?.sets.map((s) => s.setNumber)).toEqual([1, 2]);
    expect(session.exercises[0]?.sets[0]?.weightUnit).toBe('lbs');
    expect(isReliableBurn(session.caloriesBurned)).toBe(true);
    expect(session.caloriesBurned).toBeGreaterThan(20);
    expect(session.caloriesBurned).toBeLessThan(200);
  });

  it('treats "0" reps as not performed everywhere', () => {
    expect(isSetPerformed({ reps: '0' })).toBe(false);
    expect(isSetPerformed({ reps: ' ' })).toBe(false);
    expect(isSetPerformed({ reps: '8' })).toBe(true);
    const zeroed: WorkoutDraft = { ...draft, exercises: [{ ...draft.exercises[0]!, sets: [{ id: 'z', weight: '100', reps: '0', rpe: '7' }, { id: 'k', weight: '100', reps: '5', rpe: '7' }] }] };
    const session = finishDraft(zeroed, id, now, { split: 'fullBody', rpeScale: 'strength', weightUnit: 'lbs' }, 80);
    expect(session.exercises[0]?.sets.map((s) => s.id)).toEqual(['k']);
    expect(performedSetCount(session)).toBe(1);
    expect(repCount(session)).toBe(5);
  });

  it('saves each set in the unit and scale it was typed under, and converts drafts on a unit toggle', () => {
    const typedInLbs: WorkoutDraft = {
      ...draft,
      exercises: [{ ...draft.exercises[0]!, sets: [{ id: 'a', weight: '185', reps: '8', rpe: '7', weightUnit: 'lbs', rpeScale: 'strength' }, { id: 'b', weight: '90', reps: '8', rpe: '15' }] }],
    };
    // Preferences flipped to kg / Borg after set "a" was typed: "a" keeps its tags, legacy "b" takes the preferences.
    const session = finishDraft(typedInLbs, id, now, { split: 'fullBody', rpeScale: 'borg', weightUnit: 'kg' }, 80);
    expect(session.exercises[0]?.sets[0]).toMatchObject({ weight: '185', weightUnit: 'lbs', rpeScale: 'strength' });
    expect(session.exercises[0]?.sets[1]).toMatchObject({ weight: '90', weightUnit: 'kg', rpeScale: 'borg' });

    expect(convertDraftSetWeight({ id: 'a', weight: '185', reps: '8', rpe: '', weightUnit: 'lbs' }, 'lbs', 'kg')).toMatchObject({ weight: '83.9', weightUnit: 'kg' });
    expect(convertDraftSetWeight({ id: 'a', weight: '100', reps: '8', rpe: '' }, 'kg', 'lbs')).toMatchObject({ weight: '220.5', weightUnit: 'lbs' });
    // Already in the target unit: only the tag is (re)applied; blanks are never invented.
    expect(convertDraftSetWeight({ id: 'a', weight: '60', reps: '8', rpe: '', weightUnit: 'kg' }, 'lbs', 'kg')).toMatchObject({ weight: '60', weightUnit: 'kg' });
    expect(convertDraftSetWeight({ id: 'a', weight: '', reps: '8', rpe: '' }, 'lbs', 'kg')).toEqual({ id: 'a', weight: '', reps: '8', rpe: '', weightUnit: 'kg' });

    let state = workoutsReducer(initialWorkoutsState, { type: 'draft/addExercise', dayKey: day, exercise: typedInLbs.exercises[0]!, startedAt: draft.startedAt });
    state = workoutsReducer(state, { type: 'draft/convertWeightUnit', from: 'lbs', to: 'kg' });
    expect(state.drafts[day]?.exercises[0]?.sets.map((s) => [s.weight, s.weightUnit])).toEqual([
      ['83.9', 'kg'],
      ['40.8', 'kg'],
    ]);
    expect(workoutsReducer(state, { type: 'draft/convertWeightUnit', from: 'kg', to: 'kg' })).toBe(state);
  });

  it('resumes the most recently started unfinished draft even after the day changed', () => {
    expect(activeDraftKey({})).toBeUndefined();
    const yesterday = { ...draft, dayKey: '2026-09-15', startedAt: new Date(2026, 8, 15, 23, 40).toISOString() };
    const empty: WorkoutDraft = { dayKey: '2026-09-16', startedAt: new Date(2026, 8, 16, 8).toISOString(), exercises: [] };
    expect(activeDraftKey({ [yesterday.dayKey]: yesterday, [empty.dayKey]: empty })).toBe('2026-09-15');
    expect(activeDraftKey({ [yesterday.dayKey]: yesterday, [draft.dayKey]: draft })).toBe(day);
  });

  it('keeps outdoor walk/run as a timer, not strength reps', () => {
    const outdoor: WorkoutDraft = {
      dayKey: day,
      startedAt: draft.startedAt,
      exercises: [
        {
          id: 'walk',
          itemID: 'Walking_Outdoor',
          name: 'Walking',
          targetMuscles: ['cardio'],
          equipment: 'body only',
          category: 'cardio',
          sets: [],
          durationSeconds: 30 * 60,
        },
      ],
    };
    expect(hasLoggedWork(outdoor.exercises[0]!)).toBe(true);
    const session = finishDraft(outdoor, id, now, { split: 'fullBody', rpeScale: 'strength', weightUnit: 'lbs' }, 80);
    expect(session.exercises).toHaveLength(1);
    expect(session.exercises[0]).toMatchObject({ itemID: 'Walking_Outdoor', durationSeconds: 1800, sets: [] });
    const burn = estimateBurn(session.exercises, 80, 'strength')!;
    expect(burn.repCount).toBe(0);
    expect(burn.calories).toBe(160);
  });

  it('estimates nothing without performed sets and scales with effort', () => {
    const exercises: CompletedExercise[] = [
      { id: 'e', itemID: 'x', name: 'X', targetMuscles: [], equipment: '', sets: [{ id: 'a', setNumber: 1, weight: '100', weightUnit: 'kg', reps: '5', rpe: '9', rpeScale: 'strength' }] },
    ];
    const hard = estimateBurn(exercises, 80, 'strength')!;
    const easy = estimateBurn([{ ...exercises[0]!, sets: [{ ...exercises[0]!.sets[0]!, rpe: '2', weight: '20' }] }], 80, 'strength')!;
    expect(hard.calories).toBeGreaterThan(easy.calories);
    expect(hard.performedSetCount).toBe(1);
    expect(hard.repCount).toBe(5);
    expect(estimateBurn([{ ...exercises[0]!, sets: [{ ...exercises[0]!.sets[0]!, reps: '' }] }], 80, 'strength')).toBeUndefined();
  });

  it('reduces drafts and sessions, and aggregates burn and lift history', () => {
    let state = workoutsReducer(initialWorkoutsState, { type: 'draft/addExercise', dayKey: day, exercise: draft.exercises[0]!, startedAt: draft.startedAt });
    state = workoutsReducer(state, { type: 'draft/updateSet', dayKey: day, exerciseId: 'ex1', set: { id: 's3', weight: '190', reps: '6', rpe: '9' } });
    expect(state.drafts[day]?.exercises[0]?.sets[2]?.reps).toBe('6');
    state = workoutsReducer(state, { type: 'draft/removeSet', dayKey: day, exerciseId: 'ex1', setId: 's1' });
    expect(state.drafts[day]?.exercises[0]?.sets).toHaveLength(2);

    const session = finishDraft(state.drafts[day]!, id, now, { split: 'fullBody', rpeScale: 'strength', weightUnit: 'lbs' }, 80);
    state = workoutsReducer(state, { type: 'session/finish', dayKey: day, session });
    expect(state.drafts[day]).toBeUndefined();
    expect(state.sessions).toHaveLength(1);
    expect(workoutsReducer(state, { type: 'session/finish', dayKey: day, session })).toBe(state);

    const older = { ...session, id: 'older', diaryDateKey: '2026-09-14', completedAt: new Date(2026, 8, 14, 18).toISOString(), caloriesBurned: 150 };
    const dupe = { ...session, id: 'dupe', completedAt: new Date(2026, 8, 16, 19).toISOString(), caloriesBurned: 999 };
    const unreliable = { ...session, id: 'unreliable', exercises: [], completedAt: new Date(2026, 8, 16, 20).toISOString(), caloriesBurned: 0 };
    state = workoutsReducer(state, { type: 'hydrate', state: { sessions: [...state.sessions, older, dupe, unreliable] } });
    // Two workouts on one day add up (and match the card total); a 0 kcal record is not reliable.
    expect(dailyBurn(state.sessions)).toEqual([
      { day: '2026-09-14', calories: 150 },
      { day: day, calories: 999 + session.caloriesBurned! },
    ]);
    expect(totalBurn(state.sessions)).toBe(150 + 999 + session.caloriesBurned!);

    const history = liftHistory(state.sessions, 'Barbell_Bench_Press_-_Medium_Grip');
    expect(history[0]?.day).toBe(day);
    expect(history[0]?.bestWeightKg).toBeCloseTo(190 / 2.2046, 1);
    // Two records on the same day (the original and the hydrated duplicate) both count.
    expect(history[0]?.totalReps).toBe(28);
    expect(history[1]).toMatchObject({ day: '2026-09-14', totalReps: 14 });

    state = workoutsReducer(state, { type: 'session/delete', id: 'dupe' });
    expect(state.sessions.some((s) => s.id === 'dupe')).toBe(false);
  });
});
