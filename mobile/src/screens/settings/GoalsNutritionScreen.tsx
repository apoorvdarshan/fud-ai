/**
 * Settings → Goals & Nutrition. Mirrors the `goalsNutrition` category in
 * `ProfileComponents.swift`: goal, goal weight, weekly pace, the current daily targets with
 * per-macro custom overrides, and Recalculate Goals (clears overrides back to the formula).
 */

import { useState } from 'react';
import { Alert, ScrollView } from 'react-native';

import { BottomSheet } from '../../components/BottomSheet';
import { PickerSheet } from '../../components/PickerSheet';
import { AppText, PrimaryButton } from '../../components/primitives';
import { SettingsRow, SettingsSection, SettingsToggleRow } from '../../components/SettingsRow';
import { StepperField } from '../../components/StepperField';
import { displayWeight, formatWeight, isValidWeightKg, weightKgFromDisplay } from '../../domain/body/bodyState';
import { calculationMethodsSummary, goalSpeedTitle, planLimits, weeklyChangeKg, type GoalSpeed } from '../../domain/onboarding/onboarding';
import { dailyCalories, dailyTargets, weightGoalDisplayName, weightGoals, type WeightGoal } from '../../domain/profile/userProfile';
import { setAdaptiveGoalsEnabled } from '../../services/adaptiveGoals';
import { profileStore, setPreferences, usePreferences, useProfile } from '../../state/appStores';
import { useTheme } from '../../theme';

type Sheet = 'goal' | 'pace' | 'goalWeight' | 'calories' | 'protein' | 'carbs' | 'fat' | 'methods' | null;

function speedForRate(rate: number | undefined): GoalSpeed {
  if (rate === undefined) return 1;
  return rate <= 0.3 ? 0 : rate >= 0.9 ? 2 : 1;
}

export function GoalsNutritionScreen() {
  const theme = useTheme();
  const profile = useProfile((p) => p);
  const prefs = usePreferences((p) => p);
  const weightMetric = prefs.weightUnit === 'kg';
  const [sheet, setSheet] = useState<Sheet>(null);
  const [draft, setDraft] = useState('');
  const targets = dailyTargets(profile);
  const update = (patch: Partial<typeof profile>) => profileStore.dispatch({ type: 'update', patch });

  const open = (next: Exclude<Sheet, null>, value: string) => {
    setDraft(value);
    setSheet(next);
  };

  const recalculate = () =>
    Alert.alert('Recalculate Goals', 'Reset calories and macros to the formula for your current profile? Custom values are cleared.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Recalculate', onPress: () => profileStore.dispatch({ type: 'hydrate', profile: { ...profile, customCalories: undefined, customProtein: undefined, customCarbs: undefined, customFat: undefined } }) },
    ]);

  const macroRow = (label: string, key: 'calories' | 'protein' | 'carbs' | 'fat', custom: number | undefined, unit: string) => (
    <SettingsRow key={key} title={label} value={`${targets[key]} ${unit}${custom !== undefined ? ' · custom' : ''}`} onPress={() => open(key, String(targets[key]))} />
  );

  // Custom targets obey the same bounds as the onboarding plan (800–5000 kcal, 20–300 g protein…),
  // so Settings cannot pin a 0 kcal day that onboarding would refuse.
  const macroSheet = sheet === 'calories' || sheet === 'protein' || sheet === 'carbs' || sheet === 'fat' ? sheet : undefined;
  const macroLimits = macroSheet ? planLimits[macroSheet] : undefined;
  const macroDraft = Number.parseInt(draft, 10);
  const macroInvalid = macroLimits !== undefined && (!Number.isFinite(macroDraft) || macroDraft < macroLimits.min || macroDraft > macroLimits.max);

  const saveMacro = () => {
    if (!macroSheet || !macroLimits || macroInvalid) return;
    switch (macroSheet) {
      case 'calories':
        update({ customCalories: macroDraft });
        break;
      case 'protein':
        update({ customProtein: macroDraft });
        break;
      case 'carbs':
        update({ customCarbs: macroDraft });
        break;
      case 'fat':
        update({ customFat: macroDraft });
        break;
    }
    setPreferences({ onboardingPlanEdited: true });
    setSheet(null);
  };

  return (
    <>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl, backgroundColor: theme.colors.appBackground }}>
        <SettingsSection header="Goal">
          <SettingsRow icon="target" title="Goal" value={weightGoalDisplayName(profile.goal)} onPress={() => setSheet('goal')} />
          {profile.goal !== 'maintain' ? (
            <SettingsRow icon="scalemass.fill" title="Goal Weight" value={profile.goalWeightKg !== undefined ? formatWeight(profile.goalWeightKg, weightMetric) : 'Not set'} onPress={() => open('goalWeight', displayWeight(profile.goalWeightKg ?? profile.weightKg, weightMetric).toFixed(1))} />
          ) : null}
          {profile.goal !== 'maintain' ? <SettingsRow icon="hare.fill" title="Weekly Pace" value={goalSpeedTitle(speedForRate(profile.weeklyChangeKg))} onPress={() => setSheet('pace')} /> : null}
        </SettingsSection>

        <SettingsSection header="Daily Targets" footer={`Formula: ${dailyCalories(profile).toLocaleString()} kcal from your profile. Tap a value to override it; Recalculate returns to the formula.`}>
          {macroRow('Calories', 'calories', profile.customCalories, 'kcal')}
          {macroRow('Protein', 'protein', profile.customProtein, 'g')}
          {macroRow('Carbs', 'carbs', profile.customCarbs, 'g')}
          {macroRow('Fat', 'fat', profile.customFat, 'g')}
        </SettingsSection>

        <SettingsSection
          header="Adaptive Goals"
          footer="Once a week, Fud AI re-pins calories and macros to the current formula from your latest weight. This is not an on-device AI model. Turn it off to keep numbers you set by hand."
        >
          <SettingsToggleRow
            icon="arrow.triangle.2.circlepath.circle.fill"
            title="Adaptive Goals"
            value={prefs.adaptiveGoalsEnabled}
            onValueChange={(enabled) => {
              if (enabled) setPreferences({ onboardingPlanEdited: false });
              setAdaptiveGoalsEnabled(enabled);
            }}
          />
        </SettingsSection>
        <SettingsSection>
          <SettingsRow icon="arrow.triangle.2.circlepath.circle.fill" title="Recalculate Goals" onPress={recalculate} chevron={false} />
          <SettingsRow icon="book.fill" title="How is this calculated?" onPress={() => setSheet('methods')} chevron={false} />
        </SettingsSection>
      </ScrollView>

      <BottomSheet visible={sheet === 'methods'} title="Calculation methods" onDismiss={() => setSheet(null)} surface="card" detent="medium">
        <AppText variant="subheadline" tone="secondary">
          {calculationMethodsSummary(profile)}
        </AppText>
        <PrimaryButton title="Done" onPress={() => setSheet(null)} />
      </BottomSheet>

      <PickerSheet<WeightGoal>
        visible={sheet === 'goal'}
        title="Goal"
        options={weightGoals.map((g) => ({ value: g, label: weightGoalDisplayName(g) }))}
        selected={profile.goal}
        onSelect={(goal) => update(goal === 'maintain' ? { goal, goalWeightKg: undefined, weeklyChangeKg: undefined } : { goal, weeklyChangeKg: profile.weeklyChangeKg ?? 0.5 })}
        onDismiss={() => setSheet(null)}
      />
      <PickerSheet<'0' | '1' | '2'>
        visible={sheet === 'pace'}
        title="Weekly Pace"
        options={(['0', '1', '2'] as const).map((s) => ({ value: s, label: goalSpeedTitle(Number(s) as GoalSpeed), subtitle: `${weeklyChangeKg(Number(s) as GoalSpeed)} kg / week` }))}
        selected={String(speedForRate(profile.weeklyChangeKg)) as '0' | '1' | '2'}
        onSelect={(s) => update({ weeklyChangeKg: weeklyChangeKg(Number(s) as GoalSpeed) })}
        onDismiss={() => setSheet(null)}
      />
      <BottomSheet visible={sheet === 'goalWeight'} title="Goal Weight" onDismiss={() => setSheet(null)}>
        <StepperField value={draft} onChange={setDraft} step={0.1} unit={weightMetric ? 'kg' : 'lbs'} accessibilityLabel="Goal weight" />
        <PrimaryButton
          title="Save"
          onPress={() => {
            const kg = weightKgFromDisplay(Number.parseFloat(draft.replace(',', '.')), weightMetric);
            if (isValidWeightKg(kg)) update({ goalWeightKg: kg });
            setSheet(null);
          }}
        />
      </BottomSheet>
      <BottomSheet visible={macroSheet !== undefined} title={macroSheet ? macroSheet.charAt(0).toUpperCase() + macroSheet.slice(1) : ''} onDismiss={() => setSheet(null)}>
        <StepperField
          value={draft}
          onChange={setDraft}
          step={macroSheet === 'calories' ? planLimits.calories.step : 1}
          unit={macroSheet === 'calories' ? 'kcal' : 'g'}
          fractionDigits={0}
          integerOnly
          min={macroLimits?.min}
          max={macroLimits?.max}
          accessibilityLabel={macroSheet ?? ''}
        />
        <AppText variant="caption" tone={macroInvalid ? 'destructive' : 'secondary'} align="center" accessibilityLiveRegion="polite">
          {macroLimits ? `Between ${macroLimits.min.toLocaleString()} and ${macroLimits.max.toLocaleString()} ${macroSheet === 'calories' ? 'kcal' : 'g'}` : ''}
        </AppText>
        <PrimaryButton title="Save" disabled={macroInvalid} onPress={saveMacro} />
      </BottomSheet>
    </>
  );
}
