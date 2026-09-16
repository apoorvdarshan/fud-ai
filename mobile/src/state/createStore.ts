/**
 * Minimal external store: a reducer, one immutable state, synchronous listeners. React reads it
 * through `useSyncExternalStore`, so every dispatch is exactly one emission — the property the
 * unified diary relies on (#369).
 */

export interface Store<S, A> {
  getState(): S;
  dispatch(action: A): void;
  subscribe(listener: (state: S) => void): () => void;
  /** Replace state wholesale (hydration from disk). Emits once. */
  replace(state: S): void;
  /** Queue later writes until `resume()` so a long await cannot clobber them. */
  pause(): void;
  resume(): void;
  /** Apply now even while paused. Queued writes replay on top after `resume()`. */
  applyImmediate(action: A): void;
}

export function createStore<S, A>(reducer: (state: S, action: A) => S, initialState: S): Store<S, A> {
  let state = initialState;
  const listeners = new Set<(state: S) => void>();
  let holds = 0;
  const queued: Array<{ kind: 'dispatch'; action: A } | { kind: 'replace'; state: S }> = [];

  const emit = () => {
    for (const listener of listeners) listener(state);
  };

  const applyDispatch = (action: A) => {
    const next = reducer(state, action);
    if (next === state) return;
    state = next;
    emit();
  };

  const applyReplace = (next: S) => {
    if (next === state) return;
    state = next;
    emit();
  };

  return {
    getState: () => state,
    dispatch(action) {
      if (holds > 0) {
        queued.push({ kind: 'dispatch', action });
        return;
      }
      applyDispatch(action);
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    replace(next) {
      if (holds > 0) {
        queued.push({ kind: 'replace', state: next });
        return;
      }
      applyReplace(next);
    },
    applyImmediate(action) {
      applyDispatch(action);
    },
    pause() {
      holds += 1;
    },
    resume() {
      holds = Math.max(0, holds - 1);
      if (holds > 0) return;
      const batch = queued.splice(0);
      for (const item of batch) {
        if (item.kind === 'dispatch') applyDispatch(item.action);
        else applyReplace(item.state);
      }
    },
  };
}
