import { ScrollView } from 'react-native';

import { AppText, Screen } from '../../components/primitives';
import { SettingsRow, SettingsSection, SettingsToggleRow } from '../../components/SettingsRow';
import { rpeScaleTitle, rpeScales, workoutSplitTitle, workoutSplits, type RPEScale, type WorkoutSplit } from '../../domain/workouts/workoutSessions';
import { setPreferences, usePreferences, useWorkouts, workoutsStore } from '../../state/appStores';
import { useTheme } from '../../theme';

/** Settings → Workout (`WorkoutLoggingSettingsSection`). */
export function WorkoutSettingsScreen() {
  const theme = useTheme();
  const walkRun = usePreferences((p) => p.walkRunQuickLogEnabled);
  const preferences = useWorkouts((s) => s.preferences);

  const cycle = <T,>(options: readonly T[], current: T): T => {
    const index = options.indexOf(current);
    return options[(index + 1) % options.length] ?? current;
  };

  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection
          header="Workout"
          footer="Off by default. When enabled, Walking and Running appear in the Workouts + menu for quick outdoor logging."
        >
          <SettingsToggleRow
            icon="figure.walk"
            title="Walk & Run"
            value={walkRun}
            onValueChange={(value) => setPreferences({ walkRunQuickLogEnabled: value })}
          />
          <SettingsRow
            icon="square.grid.2x2.fill"
            title="Training Split"
            value={workoutSplitTitle(preferences.split)}
            chevron={false}
            onPress={() => workoutsStore.dispatch({ type: 'preferences/update', patch: { split: cycle(workoutSplits, preferences.split) as WorkoutSplit } })}
          />
          <SettingsRow
            icon="gauge.with.dots.needle.50percent"
            title="RPE Scale"
            value={rpeScaleTitle(preferences.rpeScale)}
            chevron={false}
            onPress={() => workoutsStore.dispatch({ type: 'preferences/update', patch: { rpeScale: cycle(rpeScales, preferences.rpeScale) as RPEScale } })}
          />
        </SettingsSection>
        <AppText variant="footnote" tone="secondary" style={{ paddingHorizontal: theme.spacing.lg, lineHeight: 20 }}>
          Strength 1–10: lifting effort based on how many reps you had left.{'\n'}
          CR10 0–10: general effort from rest to maximum.{'\n'}
          Borg 6–20: endurance effort linked to breathing and heart rate.
        </AppText>
      </ScrollView>
    </Screen>
  );
}
