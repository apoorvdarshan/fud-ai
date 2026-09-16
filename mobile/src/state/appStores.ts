/**
 * App-wide stores and their persistence. Screens only ever read through `useStoreSelector` and
 * write through `dispatch`, never by mutating state.
 */

import { randomUUID } from 'expo-crypto';
import { useSyncExternalStore } from 'react';

import { bodyReducer, initialBodyState, latestWeight, type BodyAction, type BodyState, type WeightEntry } from '../domain/body/bodyState';
import { chatReducer, COACH_CHAT_STORAGE_KEY, initialChatState, type ChatAction, type ChatMessage, type ChatState } from '../domain/coach/coach';
import { diaryReducer, initialDiaryState, type DiaryAction, type DiaryState } from '../domain/diary/diaryState';
import { defaultPreferences, mergePreferences, type Preferences } from '../domain/prefs/preferences';
import { defaultUserProfile, type UserProfile } from '../domain/profile/userProfile';
import {
  applyCustomerInfo,
  initialPurchasesState,
  noopPurchases,
  type PackageLike,
  type PurchasesAdapter,
  type PurchasesState,
} from '../domain/purchases/revenueCat';
import { initialWorkoutsState, workoutsReducer, type WorkoutsAction, type WorkoutsState } from '../domain/workouts/workoutSessions';
import { createStore, type Store } from './createStore';
import { asyncKeyValueStore, readJSON, writeJSON, type KeyValueStore } from './persistence';

export const storageKeys = {
  diary: 'fudai.diary.v1',
  preferences: 'fudai.preferences.v1',
  profile: 'fudai.profile.v1',
  body: 'fudai.body.v1',
  workouts: 'fudai.workouts.v1',
  /** Same key as `ChatStore.swift` so the name lines up with the native UserDefaults blob. */
  chat: COACH_CHAT_STORAGE_KEY,
} as const;

export function newId(): string {
  return randomUUID();
}

// MARK: - Diary (food + water + fasting)

export const diaryStore: Store<DiaryState, DiaryAction> = createStore(diaryReducer, initialDiaryState);

// MARK: - Body (weight + body fat history)

export const bodyStore: Store<BodyState, BodyAction> = createStore(bodyReducer, initialBodyState);

// MARK: - Workouts (strength log + drafts + preferences)

export const workoutsStore: Store<WorkoutsState, WorkoutsAction> = createStore(workoutsReducer, initialWorkoutsState);

// MARK: - Coach chat

export const chatStore: Store<ChatState, ChatAction> = createStore(chatReducer, initialChatState);

// MARK: - Preferences

export type PreferencesAction = { type: 'set'; patch: Partial<Preferences> } | { type: 'hydrate'; preferences: Preferences };

function preferencesReducer(state: Preferences, action: PreferencesAction): Preferences {
  switch (action.type) {
    case 'set': {
      const next = { ...state, ...action.patch };
      return (Object.keys(action.patch) as (keyof Preferences)[]).every((k) => next[k] === state[k]) ? state : next;
    }
    case 'hydrate':
      return action.preferences;
  }
}

export const preferencesStore: Store<Preferences, PreferencesAction> = createStore(preferencesReducer, defaultPreferences);

export function setPreferences(patch: Partial<Preferences>): void {
  preferencesStore.dispatch({ type: 'set', patch });
}

// MARK: - Profile

export type ProfileAction = { type: 'update'; patch: Partial<UserProfile> } | { type: 'hydrate'; profile: UserProfile };

function profileReducer(state: UserProfile, action: ProfileAction): UserProfile {
  switch (action.type) {
    case 'update':
      return { ...state, ...action.patch };
    case 'hydrate':
      return action.profile;
  }
}

export const profileStore: Store<UserProfile, ProfileAction> = createStore(profileReducer, defaultUserProfile);

// MARK: - Weigh-ins (body history + profile stay aligned)

/**
 * `WeightStore.addEntry` / `deleteEntry` + `syncProfileWeightToLatest`: every weigh-in goes
 * through here so `UserProfile.weightKg` (BMR / TDEE / targets / Coach) always matches the
 * newest entry Progress shows. An empty history leaves the profile alone — the formulas
 * still need some weight.
 */
export function addWeighIn(entry: WeightEntry): void {
  bodyStore.dispatch({ type: 'weight/add', entry });
  syncProfileWeightToLatest();
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { writeWeightToHealth } = require('../services/health') as typeof import('../services/health');
    writeWeightToHealth(entry.weightKg, new Date(entry.date), entry.id);
  } catch {
    /* Expo Go / tests */
  }
}

export function deleteWeighIn(id: string): void {
  bodyStore.dispatch({ type: 'weight/delete', id });
  syncProfileWeightToLatest();
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { deleteWeightFromHealth } = require('../services/health') as typeof import('../services/health');
    deleteWeightFromHealth(id);
  } catch {
    /* Expo Go / tests */
  }
}

export function deleteBodyFatEntry(id: string): void {
  bodyStore.dispatch({ type: 'bodyFat/delete', id });
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { deleteBodyFatFromHealth } = require('../services/health') as typeof import('../services/health');
    deleteBodyFatFromHealth(id);
  } catch {
    /* Expo Go / tests */
  }
}

function syncProfileWeightToLatest(): void {
  const newest = latestWeight(bodyStore.getState());
  if (!newest) return;
  if (Math.abs(profileStore.getState().weightKg - newest.weightKg) > 0.01) {
    profileStore.dispatch({ type: 'update', patch: { weightKg: newest.weightKg } });
  }
}

// MARK: - Purchases (RevenueCat)

export type PurchasesAction =
  | { type: 'customerInfo'; info: Parameters<typeof applyCustomerInfo>[1] }
  | { type: 'offeringsLoading'; loading: boolean }
  | { type: 'offerings'; offerings: PurchasesState['offerings'] }
  | { type: 'error'; message: string };

function purchasesReducer(state: PurchasesState, action: PurchasesAction): PurchasesState {
  switch (action.type) {
    case 'customerInfo':
      return applyCustomerInfo(state, action.info);
    case 'offeringsLoading':
      return { ...state, isLoadingOfferings: action.loading };
    case 'offerings':
      return { ...state, offerings: action.offerings };
    case 'error':
      return { ...state, lastError: action.message };
  }
}

export const purchasesStore: Store<PurchasesState, PurchasesAction> = createStore(purchasesReducer, initialPurchasesState);

let purchasesAdapter: PurchasesAdapter = noopPurchases;

export function setPurchasesAdapter(adapter: PurchasesAdapter): void {
  purchasesAdapter = adapter;
}

export async function refreshCustomerInfo(): Promise<boolean> {
  try {
    const info = await purchasesAdapter.getCustomerInfo();
    purchasesStore.dispatch({ type: 'customerInfo', info });
    return true;
  } catch (error) {
    purchasesStore.dispatch({ type: 'error', message: error instanceof Error ? error.message : String(error) });
    return false;
  }
}

export async function loadOfferings(): Promise<void> {
  purchasesStore.dispatch({ type: 'offeringsLoading', loading: true });
  try {
    purchasesStore.dispatch({ type: 'offerings', offerings: await purchasesAdapter.getOfferings() });
  } catch (error) {
    purchasesStore.dispatch({ type: 'error', message: error instanceof Error ? error.message : String(error) });
  } finally {
    purchasesStore.dispatch({ type: 'offeringsLoading', loading: false });
  }
}

/** `RevenueCatManager.purchase(package:)`: resolves to whether an entitlement is now active. */
export async function purchasePackage(pkg: PackageLike): Promise<boolean> {
  try {
    const info = await purchasesAdapter.purchasePackage(pkg);
    purchasesStore.dispatch({ type: 'customerInfo', info });
    return purchasesStore.getState().hasHostedEntitlement;
  } catch (error) {
    purchasesStore.dispatch({ type: 'error', message: error instanceof Error ? error.message : String(error) });
    return false;
  }
}

/** `RevenueCatManager.restorePurchases()`: asks the store to re-sync, not just re-read the cache. */
export async function restorePurchases(): Promise<boolean> {
  try {
    const info = await purchasesAdapter.restorePurchases();
    purchasesStore.dispatch({ type: 'customerInfo', info });
    return purchasesStore.getState().hasHostedEntitlement;
  } catch (error) {
    purchasesStore.dispatch({ type: 'error', message: error instanceof Error ? error.message : String(error) });
    return false;
  }
}

// MARK: - React binding

export function useStoreSelector<S, A, T>(store: Store<S, A>, selector: (state: S) => T): T {
  return useSyncExternalStore(
    store.subscribe,
    () => selector(store.getState()),
    () => selector(store.getState()),
  );
}

export function useDiary<T>(selector: (state: DiaryState) => T): T {
  return useStoreSelector(diaryStore, selector);
}

export function useBody<T>(selector: (state: BodyState) => T): T {
  return useStoreSelector(bodyStore, selector);
}

export function useWorkouts<T>(selector: (state: WorkoutsState) => T): T {
  return useStoreSelector(workoutsStore, selector);
}

export function useChat<T>(selector: (state: ChatState) => T): T {
  return useStoreSelector(chatStore, selector);
}

export function usePreferences<T>(selector: (state: Preferences) => T): T {
  return useStoreSelector(preferencesStore, selector);
}

export function useProfile<T>(selector: (state: UserProfile) => T): T {
  return useStoreSelector(profileStore, selector);
}

export function usePurchases<T>(selector: (state: PurchasesState) => T): T {
  return useStoreSelector(purchasesStore, selector);
}

// MARK: - Hydration & persistence

interface PersistedDiary {
  foodEntries: DiaryState['foodEntries'];
  waterEntries: DiaryState['waterEntries'];
  fastingSessions: DiaryState['fastingSessions'];
  favoriteKeys: DiaryState['favoriteKeys'];
}

interface PersistedBody {
  weightEntries: BodyState['weightEntries'];
  bodyFatEntries: BodyState['bodyFatEntries'];
}

interface PersistedWorkouts {
  sessions: WorkoutsState['sessions'];
  drafts: WorkoutsState['drafts'];
  preferences: WorkoutsState['preferences'];
}

export type PersistenceFailure = { phase: 'read' | 'write'; key: string; error: unknown };
export type PersistenceFailureListener = (failure: PersistenceFailure) => void;

const persistenceFailureListeners = new Set<PersistenceFailureListener>();
const reportedPersistenceFailures = new Set<string>();

/** Subscribe to storage failures (for a banner / diagnostics). Returns an unsubscribe. */
export function onPersistenceFailure(listener: PersistenceFailureListener): () => void {
  persistenceFailureListeners.add(listener);
  return () => {
    persistenceFailureListeners.delete(listener);
  };
}

/** Storage failures are never swallowed: logged once per key+phase and fanned out to listeners. */
function reportPersistenceFailure(failure: PersistenceFailure): void {
  const signature = `${failure.phase}:${failure.key}`;
  if (!reportedPersistenceFailures.has(signature)) {
    reportedPersistenceFailures.add(signature);
    console.error(`[fudai] storage ${failure.phase} failed for "${failure.key}"`, failure.error);
  }
  persistenceFailureListeners.forEach((listener) => listener(failure));
}

interface StoreRead<T> {
  value: T | undefined;
  /** False when the read rejected; that store is then kept in memory only so the blob on disk is never clobbered. */
  readable: boolean;
}

async function readStore<T>(kv: KeyValueStore, key: string): Promise<StoreRead<T>> {
  try {
    return { value: await readJSON<T>(kv, key), readable: true };
  } catch (error) {
    reportPersistenceFailure({ phase: 'read', key, error });
    return { value: undefined, readable: false };
  }
}

/**
 * Load persisted state, then persist every subsequent change. Writes are coalesced per store
 * with a short debounce so rapid taps (three glasses of water) become one write.
 *
 * Never rejects for storage reasons: each store is read independently, a failed read falls
 * back to defaults (and is not persisted, so the unreadable blob survives for recovery), and
 * every failure goes through `reportPersistenceFailure`.
 */
export async function hydrateAndPersistStores(kv: KeyValueStore = asyncKeyValueStore): Promise<() => void> {
  const [diary, preferences, profile, body, workouts, chat] = await Promise.all([
    readStore<PersistedDiary>(kv, storageKeys.diary),
    readStore<Partial<Preferences>>(kv, storageKeys.preferences),
    readStore<UserProfile>(kv, storageKeys.profile),
    readStore<PersistedBody>(kv, storageKeys.body),
    readStore<PersistedWorkouts>(kv, storageKeys.workouts),
    readStore<ChatMessage[]>(kv, storageKeys.chat),
  ]);
  if (Array.isArray(chat.value)) chatStore.dispatch({ type: 'hydrate', messages: chat.value });

  if (diary.value) diaryStore.dispatch({ type: 'hydrate', state: diary.value });
  preferencesStore.dispatch({ type: 'hydrate', preferences: mergePreferences(preferences.value) });
  if (profile.value) profileStore.dispatch({ type: 'hydrate', profile: { ...defaultUserProfile, ...profile.value } });
  if (body.value) bodyStore.dispatch({ type: 'hydrate', state: body.value });
  if (workouts.value) workoutsStore.dispatch({ type: 'hydrate', state: workouts.value });

  const unsubscribes: (() => void)[] = [];
  if (diary.readable) {
    unsubscribes.push(
      persistOnChange(diaryStore, kv, storageKeys.diary, (state): PersistedDiary => ({
        foodEntries: state.foodEntries,
        waterEntries: state.waterEntries,
        fastingSessions: state.fastingSessions,
        favoriteKeys: state.favoriteKeys,
      })),
    );
  }
  if (preferences.readable) unsubscribes.push(persistOnChange(preferencesStore, kv, storageKeys.preferences, (state) => state));
  if (profile.readable) unsubscribes.push(persistOnChange(profileStore, kv, storageKeys.profile, (state) => state));
  if (body.readable) {
    unsubscribes.push(
      persistOnChange(bodyStore, kv, storageKeys.body, (state): PersistedBody => ({ weightEntries: state.weightEntries, bodyFatEntries: state.bodyFatEntries })),
    );
  }
  if (workouts.readable) {
    unsubscribes.push(
      persistOnChange(workoutsStore, kv, storageKeys.workouts, (state): PersistedWorkouts => ({ sessions: state.sessions, drafts: state.drafts, preferences: state.preferences })),
    );
  }
  if (chat.readable) unsubscribes.push(persistOnChange(chatStore, kv, storageKeys.chat, (state) => state.messages));

  return () => unsubscribes.forEach((fn) => fn());
}

function persistOnChange<S, A>(
  store: Store<S, A>,
  kv: KeyValueStore,
  key: string,
  project: (state: S) => unknown,
  debounceMs = 150,
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  // Set when the last write rejected; the next change (or the dispose flush) rewrites the full
  // snapshot, so a transient failure only delays persistence instead of dropping it.
  let pendingRetry = false;

  const flush = (state: S): Promise<void> =>
    writeJSON(kv, key, project(state)).then(
      () => {
        pendingRetry = false;
      },
      (error: unknown) => {
        pendingRetry = true;
        reportPersistenceFailure({ phase: 'write', key, error });
      },
    );

  const unsubscribe = store.subscribe((state) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = undefined;
      void flush(state);
    }, debounceMs);
  });
  return () => {
    if (timer || pendingRetry) {
      if (timer) clearTimeout(timer);
      timer = undefined;
      void flush(store.getState());
    }
    unsubscribe();
  };
}
