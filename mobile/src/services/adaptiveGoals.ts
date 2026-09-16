/**
 * Weekly Adaptive Goals. Pins custom targets to the current formula — not an AI model.
 */

import {
  applyFormulaAdaptiveGoals,
  markAdaptiveGoalsChecked,
  parseAdaptiveTargetSnapshot,
  restoreAdaptiveTargets,
  serializeAdaptiveTargetSnapshot,
  shouldCheckAdaptiveGoals,
  snapshotFromProfile,
} from '../domain/profile/adaptiveGoals';
import { preferencesStore, profileStore, setPreferences } from '../state/appStores';

export function applyAdaptiveGoalsIfDue(now: Date = new Date()): boolean {
  const prefs = preferencesStore.getState();
  if (!prefs.hasCompletedOnboarding || !prefs.adaptiveGoalsEnabled || prefs.onboardingPlanEdited) return false;
  if (!shouldCheckAdaptiveGoals(prefs.adaptiveGoalsLastCheckDay, now)) return false;
  const profile = profileStore.getState();
  const previous = snapshotFromProfile(profile);
  const next = applyFormulaAdaptiveGoals(profile, now);
  profileStore.dispatch({ type: 'hydrate', profile: next });
  setPreferences({
    adaptiveGoalsPreviousTargets: serializeAdaptiveTargetSnapshot(previous),
    adaptiveGoalsLastCheckDay: markAdaptiveGoalsChecked(now),
  });
  return true;
}

export function setAdaptiveGoalsEnabled(enabled: boolean): void {
  const prefs = preferencesStore.getState();
  if (!enabled) {
    const snapshot = parseAdaptiveTargetSnapshot(prefs.adaptiveGoalsPreviousTargets);
    if (snapshot && !prefs.onboardingPlanEdited) {
      profileStore.dispatch({ type: 'hydrate', profile: restoreAdaptiveTargets(profileStore.getState(), snapshot) });
    }
    setPreferences({ adaptiveGoalsEnabled: false });
    return;
  }
  setPreferences({ adaptiveGoalsEnabled: true });
  applyAdaptiveGoalsIfDue();
}
