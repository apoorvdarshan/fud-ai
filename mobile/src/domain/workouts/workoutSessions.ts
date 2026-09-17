/**
 * Strength workout log. Ported from `StrengthWorkoutSession.swift` (sessions, completed
 * exercises/sets, `StrengthWorkoutBurnEstimator`, `StrengthExerciseLiftHistory`) and the
 * persistence shape of `StrengthWorkoutStore.swift`, as one reducer-backed state.
 * Set fields stay strings like the native `StrengthCompletedSet` so partially typed sets
 * round-trip; a set counts as performed once `reps` parses to a positive count.
 */

import { dayKey, isSameDay } from '../dates';
import type { WeightUnit } from '../prefs/preferences';

export const rpeScales = ['strength', 'cr10', 'borg'] as const;
export type RPEScale = (typeof rpeScales)[number];

export function rpeScaleTitle(scale: RPEScale): string {
  switch (scale) {
    case 'strength':
      return 'Strength 1–10';
    case 'cr10':
      return 'CR10 0–10';
    case 'borg':
      return 'Borg 6–20';
  }
}

export const workoutSplits = ['fullBody', 'upperLower', 'pushPullLegs', 'broSplit', 'custom'] as const;
export type WorkoutSplit = (typeof workoutSplits)[number];

export function workoutSplitTitle(split: WorkoutSplit): string {
  switch (split) {
    case 'fullBody':
      return 'Full Body';
    case 'upperLower':
      return 'Upper / Lower';
    case 'pushPullLegs':
      return 'Push / Pull / Legs';
    case 'broSplit':
      return 'Body Part';
    case 'custom':
      return 'Custom';
  }
}

export interface WorkoutPreferences {
  split: WorkoutSplit;
  rpeScale: RPEScale;
  weightUnit: WeightUnit;
}

export const defaultWorkoutPreferences: WorkoutPreferences = { split: 'fullBody', rpeScale: 'strength', weightUnit: 'lbs' };

export interface CompletedSet {
  id: string;
  setNumber: number;
  weight: string;
  weightUnit: WeightUnit;
  reps: string;
  rpe: string;
  rpeScale?: RPEScale;
}

export interface CompletedExercise {
  id: string;
  itemID: string;
  name: string;
  targetMuscles: string[];
  equipment: string;
  sets: CompletedSet[];
  /** Saved exercise timer (`StrengthExerciseTimer.savedDurationSeconds`). */
  durationSeconds?: number;
}

export interface WorkoutSession {
  id: string;
  /** ISO-8601 of the diary day. */
  diaryDate: string;
  /** Stable `yyyy-MM-dd` so the day never moves with the time zone. */
  diaryDateKey: string;
  startedAt: string;
  completedAt: string;
  durationSeconds: number;
  exercises: CompletedExercise[];
  caloriesBurned?: number;
}

/** Repetitions as a positive integer, or undefined for blank / "0" / garbage — same rule as `(Int(reps) ?? 0) > 0` on iOS. */
export function performedReps(set: Pick<CompletedSet, 'reps'>): number | undefined {
  const reps = Number.parseInt(set.reps.trim(), 10);
  return Number.isFinite(reps) && reps > 0 ? reps : undefined;
}

export function isSetPerformed(set: Pick<CompletedSet, 'reps'>): boolean {
  return performedReps(set) !== undefined;
}

export function hasLoggedWork(exercise: Pick<DraftExercise, 'sets' | 'durationSeconds'>): boolean {
  return (exercise.durationSeconds ?? 0) > 0 || exercise.sets.some(isSetPerformed);
}

export function performedSetCount(session: WorkoutSession): number {
  return session.exercises.flatMap((e) => e.sets).filter(isSetPerformed).length;
}

export function repCount(session: WorkoutSession): number {
  return session.exercises.flatMap((e) => e.sets).reduce((sum, set) => sum + (performedReps(set) ?? 0), 0);
}

export function durationMinutes(session: WorkoutSession): number {
  return Math.max(0, Math.ceil(session.durationSeconds / 60));
}

// MARK: - Draft (the in-progress log for a day)

export interface DraftSet {
  id: string;
  weight: string;
  reps: string;
  rpe: string;
  /** Unit `weight` was typed in; tagged on edit so a later unit toggle cannot reinterpret 185 lbs as 185 kg. */
  weightUnit?: WeightUnit;
  /** Scale `rpe` was typed on, for the same reason. */
  rpeScale?: RPEScale;
}

export interface DraftExercise {
  id: string;
  itemID: string;
  name: string;
  targetMuscles: string[];
  equipment: string;
  category: string;
  sets: DraftSet[];
  /** Saved outdoor / cardio timer; empty `sets` like native `logQuickCardio`. */
  durationSeconds?: number;
}

export interface WorkoutDraft {
  dayKey: string;
  startedAt: string;
  exercises: DraftExercise[];
}

export interface WorkoutsState {
  sessions: readonly WorkoutSession[];
  drafts: Readonly<Record<string, WorkoutDraft>>;
  preferences: WorkoutPreferences;
  revision: number;
}

export const initialWorkoutsState: WorkoutsState = { sessions: [], drafts: {}, preferences: defaultWorkoutPreferences, revision: 0 };

export type WorkoutsAction =
  | { type: 'hydrate'; state: Partial<Omit<WorkoutsState, 'revision'>> }
  | { type: 'preferences/update'; patch: Partial<WorkoutPreferences> }
  | { type: 'draft/addExercise'; dayKey: string; exercise: DraftExercise; startedAt: string }
  | { type: 'draft/removeExercise'; dayKey: string; exerciseId: string }
  | { type: 'draft/addSet'; dayKey: string; exerciseId: string; set: DraftSet }
  | { type: 'draft/updateSet'; dayKey: string; exerciseId: string; set: DraftSet }
  | { type: 'draft/removeSet'; dayKey: string; exerciseId: string; setId: string }
  | { type: 'draft/discard'; dayKey: string }
  /** The weight-unit preference changed: convert every draft weight so the numbers still mean what was typed. */
  | { type: 'draft/convertWeightUnit'; from: WeightUnit; to: WeightUnit }
  | { type: 'session/finish'; dayKey: string; session: WorkoutSession }
  | { type: 'session/delete'; id: string }
  | { type: 'clearAll' };

function bump(state: WorkoutsState, patch: Partial<WorkoutsState>): WorkoutsState {
  return { ...state, ...patch, revision: state.revision + 1 };
}

function updateDraft(state: WorkoutsState, key: string, update: (draft: WorkoutDraft) => WorkoutDraft | undefined, startedAt?: string): WorkoutsState {
  const existing = state.drafts[key] ?? { dayKey: key, startedAt: startedAt ?? new Date().toISOString(), exercises: [] };
  const next = update(existing);
  const drafts = { ...state.drafts };
  if (next) drafts[key] = next;
  else delete drafts[key];
  return bump(state, { drafts });
}

export function workoutsReducer(state: WorkoutsState, action: WorkoutsAction): WorkoutsState {
  switch (action.type) {
    case 'hydrate':
      return {
        ...state,
        sessions: [...(action.state.sessions ?? state.sessions)].sort((a, b) => a.completedAt.localeCompare(b.completedAt)),
        drafts: action.state.drafts ?? state.drafts,
        preferences: { ...defaultWorkoutPreferences, ...(action.state.preferences ?? state.preferences) },
      };
    case 'preferences/update':
      return bump(state, { preferences: { ...state.preferences, ...action.patch } });
    case 'draft/addExercise':
      return updateDraft(
        state,
        action.dayKey,
        (draft) => (draft.exercises.some((e) => e.id === action.exercise.id) ? draft : { ...draft, exercises: [...draft.exercises, action.exercise] }),
        action.startedAt,
      );
    case 'draft/removeExercise':
      return updateDraft(state, action.dayKey, (draft) => ({ ...draft, exercises: draft.exercises.filter((e) => e.id !== action.exerciseId) }));
    case 'draft/addSet':
      return updateDraft(state, action.dayKey, (draft) => ({
        ...draft,
        exercises: draft.exercises.map((e) => (e.id === action.exerciseId ? { ...e, sets: [...e.sets, action.set] } : e)),
      }));
    case 'draft/updateSet':
      return updateDraft(state, action.dayKey, (draft) => ({
        ...draft,
        exercises: draft.exercises.map((e) => (e.id === action.exerciseId ? { ...e, sets: e.sets.map((s) => (s.id === action.set.id ? action.set : s)) } : e)),
      }));
    case 'draft/removeSet':
      return updateDraft(state, action.dayKey, (draft) => ({
        ...draft,
        exercises: draft.exercises.map((e) => (e.id === action.exerciseId ? { ...e, sets: e.sets.filter((s) => s.id !== action.setId) } : e)),
      }));
    case 'draft/discard':
      if (!state.drafts[action.dayKey]) return state;
      return updateDraft(state, action.dayKey, () => undefined);
    case 'draft/convertWeightUnit': {
      if (action.from === action.to || Object.keys(state.drafts).length === 0) return state;
      const drafts: Record<string, WorkoutDraft> = {};
      for (const [key, draft] of Object.entries(state.drafts)) {
        drafts[key] = { ...draft, exercises: draft.exercises.map((e) => ({ ...e, sets: e.sets.map((set) => convertDraftSetWeight(set, action.from, action.to)) })) };
      }
      return bump(state, { drafts });
    }
    case 'session/finish': {
      if (state.sessions.some((s) => s.id === action.session.id)) return state;
      const drafts = { ...state.drafts };
      delete drafts[action.dayKey];
      return bump(state, { sessions: [...state.sessions, action.session].sort((a, b) => a.completedAt.localeCompare(b.completedAt)), drafts });
    }
    case 'session/delete':
      if (!state.sessions.some((s) => s.id === action.id)) return state;
      return bump(state, { sessions: state.sessions.filter((s) => s.id !== action.id) });
    case 'clearAll':
      return bump(state, { sessions: [], drafts: {} });
  }
}

/** Format a converted load the way a user would type it: at most one decimal, no trailing `.0`. */
function formatDraftWeight(value: number): string {
  const rounded = Math.round(value * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

/**
 * Re-express one draft set's load in `to`. A set tagged with its own unit converts from that
 * tag (so converting twice is safe); an untagged legacy set is assumed to be in `from`.
 * Blank or unparsable loads are only re-tagged.
 */
export function convertDraftSetWeight(set: DraftSet, from: WeightUnit, to: WeightUnit): DraftSet {
  const sourceUnit = set.weightUnit ?? from;
  if (sourceUnit === to) return set.weightUnit === to ? set : { ...set, weightUnit: to };
  const value = Number.parseFloat(set.weight.replace(',', '.'));
  if (!Number.isFinite(value) || value <= 0) return { ...set, weightUnit: to };
  const converted = to === 'kg' ? value * KG_PER_LB : value / KG_PER_LB;
  return { ...set, weight: formatDraftWeight(converted), weightUnit: to };
}

/**
 * Build the immutable session from a draft when the user taps Finish. Each set is saved in
 * the unit / scale it was typed under (its tag), falling back to the current preferences for
 * legacy untagged sets.
 */
export function finishDraft(draft: WorkoutDraft, makeId: () => string, now: Date, preferences: WorkoutPreferences, bodyWeightKg: number): WorkoutSession {
  const started = new Date(draft.startedAt);
  const exercises: CompletedExercise[] = draft.exercises
    .map((exercise) => ({
      id: exercise.id,
      itemID: exercise.itemID,
      name: exercise.name,
      targetMuscles: exercise.targetMuscles,
      equipment: exercise.equipment,
      sets: exercise.sets.filter(isSetPerformed).map((set, index) => ({
        id: set.id,
        setNumber: index + 1,
        weight: set.weight,
        weightUnit: set.weightUnit ?? preferences.weightUnit,
        reps: set.reps,
        rpe: set.rpe,
        rpeScale: set.rpeScale ?? preferences.rpeScale,
      })),
      ...((exercise.durationSeconds ?? 0) > 0 ? { durationSeconds: exercise.durationSeconds } : {}),
    }))
    .filter((exercise) => exercise.sets.length > 0 || (exercise.durationSeconds ?? 0) > 0);
  const estimate = estimateBurn(exercises, bodyWeightKg, preferences.rpeScale);
  const diaryDate = new Date(`${draft.dayKey}T12:00:00`);
  return {
    id: makeId(),
    diaryDate: diaryDate.toISOString(),
    diaryDateKey: draft.dayKey,
    startedAt: draft.startedAt,
    completedAt: now.toISOString(),
    durationSeconds: Math.max(0, Math.round((now.getTime() - started.getTime()) / 1000)),
    exercises,
    ...(estimate ? { caloriesBurned: estimate.calories } : {}),
  };
}

// MARK: - Selectors

/** The most recently started unfinished draft, so a workout begun before midnight can still be finished or discarded. */
export function activeDraftKey(drafts: Readonly<Record<string, WorkoutDraft>>): string | undefined {
  let best: WorkoutDraft | undefined;
  for (const draft of Object.values(drafts)) {
    if (draft.exercises.length === 0) continue;
    if (!best || draft.startedAt > best.startedAt) best = draft;
  }
  return best?.dayKey;
}

export function sessionsOn(state: WorkoutsState, day: Date): WorkoutSession[] {
  const key = dayKey(day);
  return state.sessions.filter((s) => s.diaryDateKey === key || isSameDay(new Date(s.diaryDate), day));
}

export function completedSessions(state: WorkoutsState): WorkoutSession[] {
  return [...state.sessions].sort((a, b) => b.completedAt.localeCompare(a.completedAt));
}

/** `WorkoutBurnAggregation.isReliable` — 1…5000 kcal. */
export function isReliableBurn(calories: number | undefined): calories is number {
  return calories !== undefined && calories >= 1 && calories <= 5_000;
}

export function burnSessions(state: WorkoutsState): WorkoutSession[] {
  return state.sessions.filter((s) => isReliableBurn(s.caloriesBurned));
}

export interface WorkoutBurnDay {
  day: string;
  calories: number;
}

/**
 * Reliable burn per diary day. The shared log keeps every finished session (the native burn
 * calculator upserts a single record per day instead), so a day with two workouts sums both —
 * the same total the Workout Burn card shows for the range.
 */
export function dailyBurn(sessions: readonly WorkoutSession[]): WorkoutBurnDay[] {
  const byDay = new Map<string, number>();
  for (const session of sessions) {
    if (!isReliableBurn(session.caloriesBurned)) continue;
    byDay.set(session.diaryDateKey, (byDay.get(session.diaryDateKey) ?? 0) + session.caloriesBurned);
  }
  return [...byDay.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([day, calories]) => ({ day, calories }));
}

/** Range total for the Workout Burn card — derived from the same per-day series as the bars. */
export function totalBurn(sessions: readonly WorkoutSession[]): number {
  return dailyBurn(sessions).reduce((sum, day) => sum + day.calories, 0);
}

export interface LiftDay {
  day: string;
  bestWeightKg: number;
  totalReps: number;
  sets: number;
}

const KG_PER_LB = 1 / 2.204_622_621_8;

export function setWeightKg(set: Pick<CompletedSet, 'weight' | 'weightUnit'>): number {
  const value = Number.parseFloat(set.weight.replace(',', '.'));
  if (!Number.isFinite(value) || value <= 0) return 0;
  return set.weightUnit === 'kg' ? value : value * KG_PER_LB;
}

/** `StrengthExerciseLiftHistory` — per-day best load / reps for one exercise, newest first. */
export function liftHistory(sessions: readonly WorkoutSession[], itemID: string): LiftDay[] {
  const days = new Map<string, LiftDay>();
  for (const session of sessions) {
    for (const exercise of session.exercises) {
      if (exercise.itemID !== itemID) continue;
      const day = days.get(session.diaryDateKey) ?? { day: session.diaryDateKey, bestWeightKg: 0, totalReps: 0, sets: 0 };
      for (const set of exercise.sets) {
        const reps = performedReps(set);
        if (reps === undefined) continue;
        day.sets += 1;
        day.totalReps += reps;
        day.bestWeightKg = Math.max(day.bestWeightKg, setWeightKg(set));
      }
      days.set(session.diaryDateKey, day);
    }
  }
  return [...days.values()].sort((a, b) => b.day.localeCompare(a.day));
}

// MARK: - Burn estimate (`StrengthWorkoutBurnEstimator`, untimed sets)

export interface BurnEstimate {
  calories: number;
  performedSetCount: number;
  repCount: number;
}

function normalizedEffort(text: string, scale: RPEScale): number {
  const value = Number.parseFloat(text.replace(',', '.'));
  if (!Number.isFinite(value)) return 0.6;
  const normalized = scale === 'strength' ? (value - 1) / 9 : scale === 'cr10' ? value / 10 : (value - 6) / 14;
  return Math.min(Math.max(normalized, 0), 1);
}

function relativeLoad(set: Pick<CompletedSet, 'weight' | 'weightUnit'>, bodyWeightKg: number): number {
  const kg = setWeightKg(set);
  return kg > 0 ? Math.min(Math.max(kg / bodyWeightKg, 0), 2) : 0;
}

/** Moderate-intensity Compendium METs used by native `StrengthWorkoutBurnEstimator.timedMET`. */
function timedMET(itemID: string): number {
  switch (itemID) {
    case 'Walking_Treadmill':
    case 'Walking_Outdoor':
      return 3.8;
    case 'Jogging_Treadmill':
    case 'Running_Treadmill':
    case 'Running_Outdoor':
      return 8.5;
    default:
      return 5;
  }
}

/**
 * MET-based estimate: timed cardio uses saved duration × activity MET; untimed strength
 * uses ~2.75 s per rep plus 1.6 min recovery per set and a 0.75 min transition, MET 3.5–8.
 */
export function estimateBurn(exercises: readonly CompletedExercise[], bodyWeightKg: number, defaultScale: RPEScale): BurnEstimate | undefined {
  const safeBodyWeight = Number.isFinite(bodyWeightKg) ? Math.min(Math.max(bodyWeightKg, 35), 300) : 70;
  let performed = 0;
  let reps = 0;
  let activeMinutes = 0;
  let recoveryMinutes = 0;
  let effortTotal = 0;
  let loadTotal = 0;
  let exercisesWithWork = 0;
  let timedCalories = 0;
  for (const exercise of exercises) {
    const savedSeconds = exercise.durationSeconds ?? 0;
    const hasSavedDuration = savedSeconds > 0;
    if (hasSavedDuration) {
      timedCalories += (timedMET(exercise.itemID) * 3.5 * safeBodyWeight) / 200 * (savedSeconds / 60);
    }
    let inExercise = 0;
    for (const set of exercise.sets) {
      const rawReps = Number.parseInt(set.reps, 10);
      if (!Number.isFinite(rawReps) || rawReps <= 0) continue;
      const setReps = Math.min(rawReps, 100);
      performed += 1;
      reps += setReps;
      if (hasSavedDuration) continue;
      inExercise += 1;
      activeMinutes += Math.min(Math.max((setReps * 2.75) / 60, 0.3), 1.5);
      recoveryMinutes += 1.6;
      effortTotal += normalizedEffort(set.rpe, set.rpeScale ?? defaultScale);
      loadTotal += relativeLoad(set, safeBodyWeight);
    }
    if (inExercise > 0) exercisesWithWork += 1;
  }
  if (performed === 0 && timedCalories <= 0) return undefined;
  recoveryMinutes = Math.max(0, recoveryMinutes - 1.6);
  const estimatedMinutes = Math.max(4, activeMinutes + recoveryMinutes + exercisesWithWork * 0.75);
  const untimedSets = exercises.reduce((count, exercise) => {
    if ((exercise.durationSeconds ?? 0) > 0) return count;
    return count + exercise.sets.filter((set) => Number.parseInt(set.reps, 10) > 0).length;
  }, 0);
  const averageEffort = untimedSets > 0 ? effortTotal / untimedSets : 0;
  const averageLoad = untimedSets > 0 ? loadTotal / untimedSets : 0;
  const met = Math.min(Math.max(3.8 + 2.4 * averageEffort + 0.5 * averageLoad, 3.5), 8);
  const strengthCalories = untimedSets > 0 ? ((met * 3.5 * safeBodyWeight) / 200) * estimatedMinutes : 0;
  const calories = Math.min(strengthCalories + timedCalories, 5_000);
  return { calories: Math.min(Math.max(Math.round(calories), 1), 5_000), performedSetCount: performed, repCount: reps };
}
