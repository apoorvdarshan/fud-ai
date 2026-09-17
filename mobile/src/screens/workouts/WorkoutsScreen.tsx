/**
 * Workouts tab. Mirrors `WorkoutsView.swift`: a persisted Log / Library mode
 * (`WorkoutTabMode`, default Log), the strength log for today (`WorkoutLogView` reduced to
 * exercises with weight / reps / RPE sets, add from the library, Finish with a burn estimate,
 * and recent sessions) and the exercise library browser. Same layout and AppColors on both
 * platforms.
 */

import { BottomTabBarHeightContext } from '@react-navigation/bottom-tabs';
import { useContext, useMemo, useState } from 'react';
import { Alert, Pressable, ScrollView, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { NativeMenu } from '../../components/NativeMenu';
import { BottomSheet } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { AppText, Card, Divider, PrimaryButton, Row, Screen, SecondaryButton } from '../../components/primitives';
import { SegmentedControl } from '../../components/SegmentedControl';
import { latestWeight } from '../../domain/body/bodyState';
import { dateFromDayKey, dayKey, isSameDay } from '../../domain/dates';
import type { ExerciseLibraryItem } from '../../domain/workouts/exerciseLibrary';
import {
  activeDraftKey,
  completedSessions,
  durationMinutes,
  finishDraft,
  hasLoggedWork,
  isSetPerformed,
  performedSetCount,
  repCount,
  rpeScaleTitle,
  rpeScales,
  type DraftExercise,
  type DraftSet,
  type RPEScale,
  type WorkoutSession,
} from '../../domain/workouts/workoutSessions';
import type { WeightUnit } from '../../domain/prefs/preferences';
import { newId, setPreferences, useBody, usePreferences, useProfile, useWorkouts, workoutsStore } from '../../state/appStores';
import { useTheme } from '../../theme';
import { ExerciseLibrary } from './ExerciseLibrary';

type Mode = 'log' | 'library';

export function WorkoutsScreen() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const tabBarHeight = useContext(BottomTabBarHeightContext) ?? insets.bottom;
  const [mode, setMode] = useState<Mode>('log');
  const [picking, setPicking] = useState(false);
  const [outdoor, setOutdoor] = useState<'walking' | 'running' | null>(null);
  const walkRun = usePreferences((p) => p.walkRunQuickLogEnabled);
  const profile = useProfile((p) => p);
  const drafts = useWorkouts((s) => s.drafts);
  // An unfinished workout stays reachable after midnight: the log opens the most recent draft
  // (usually today's) and only a brand-new workout is keyed to the current day.
  const today = activeDraftKey(drafts) ?? dayKey(new Date());

  const pickExercise = (item: ExerciseLibraryItem) => {
    const exercise: DraftExercise = {
      id: newId(),
      itemID: item.id,
      name: item.name,
      targetMuscles: item.primaryMuscles,
      equipment: item.equipment,
      category: item.category,
      sets: [{ id: newId(), weight: '', reps: '', rpe: '' }],
    };
    workoutsStore.dispatch({ type: 'draft/addExercise', dayKey: today, exercise, startedAt: new Date().toISOString() });
    setPicking(false);
    setMode('log');
  };

  return (
    <Screen>
      <View style={{ paddingHorizontal: 20, paddingTop: 10, gap: 10 }}>
        <Row style={{ justifyContent: 'center', minHeight: 32 }}>
          <AppText variant="headline">{picking ? 'Add Exercise' : 'Workouts'}</AppText>
          {picking ? (
            <Pressable accessibilityRole="button" onPress={() => setPicking(false)} style={{ position: 'absolute', right: 0 }}>
              <AppText variant="bodySemibold" tone="accent">
                Done
              </AppText>
            </Pressable>
          ) : null}
        </Row>
        {!picking ? (
          <SegmentedControl<Mode>
            segments={[
              { value: 'log', label: 'Log' },
              { value: 'library', label: 'Library' },
            ]}
            selected={mode}
            onSelect={setMode}
            accessibilityLabel="Workouts mode"
          />
        ) : null}
      </View>
      {picking || mode === 'library' ? (
        <ExerciseLibrary sex={profile.gender === 'female' ? 'female' : 'male'} onPick={pickExercise} bottomInset={tabBarHeight} />
      ) : (
        <WorkoutLog
          dayKey={today}
          onAddExercise={() => setPicking(true)}
          onOutdoor={walkRun ? setOutdoor : undefined}
          bottomInset={tabBarHeight}
        />
      )}
      <BottomSheet visible={outdoor !== null} title={outdoor === 'running' ? 'Running' : 'Walking'} onDismiss={() => setOutdoor(null)} surface="card">
        <AppText variant="subheadline" tone="secondary" align="center">
          How long did you go?
        </AppText>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10, justifyContent: 'center' }}>
          {[15, 30, 45, 60].map((minutes) => (
            <Pressable
              key={minutes}
              accessibilityRole="button"
              onPress={() => {
                const name = outdoor === 'running' ? 'Running' : 'Walking';
                const itemId = outdoor === 'running' ? 'Running_Outdoor' : 'Walking_Outdoor';
                const exercise: DraftExercise = {
                  id: newId(),
                  itemID: itemId,
                  name,
                  targetMuscles: ['cardio'],
                  equipment: 'body only',
                  category: 'cardio',
                  sets: [],
                  durationSeconds: minutes * 60,
                };
                workoutsStore.dispatch({ type: 'draft/addExercise', dayKey: today, exercise, startedAt: new Date().toISOString() });
                setOutdoor(null);
                setMode('log');
              }}
              style={{ minWidth: 72, paddingVertical: 12, borderRadius: 12, backgroundColor: theme.accentAlpha(0.12), alignItems: 'center' }}
            >
              <AppText variant="headline" tone="accent">
                {minutes}
              </AppText>
              <AppText variant="caption" tone="secondary">
                min
              </AppText>
            </Pressable>
          ))}
        </View>
      </BottomSheet>
    </Screen>
  );
}

// MARK: - Log

function AddExerciseControl({
  title,
  onAddExercise,
  onOutdoor,
  stretch,
}: {
  title: string;
  onAddExercise: () => void;
  onOutdoor?: (kind: 'walking' | 'running') => void;
  stretch?: boolean;
}) {
  if (!onOutdoor) {
    return stretch ? <PrimaryButton title={title} style={{ alignSelf: 'stretch' }} onPress={onAddExercise} /> : <SecondaryButton title={title} onPress={onAddExercise} />;
  }
  const button = stretch ? <PrimaryButton title={title} style={{ alignSelf: 'stretch' }} onPress={onAddExercise} /> : <SecondaryButton title={title} onPress={onAddExercise} />;
  return (
    <NativeMenu
      items={[
        { id: 'library', title: 'Add Exercise', systemImage: 'plus.circle.fill' },
        { id: 'walking', title: 'Walking', systemImage: 'figure.walk' },
        { id: 'running', title: 'Running', systemImage: 'figure.run' },
      ]}
      onSelect={(id) => {
        if (id === 'walking' || id === 'running') onOutdoor(id);
        else onAddExercise();
      }}
    >
      {button}
    </NativeMenu>
  );
}

function WorkoutLog({
  dayKey: today,
  onAddExercise,
  onOutdoor,
  bottomInset,
}: {
  dayKey: string;
  onAddExercise: () => void;
  onOutdoor?: (kind: 'walking' | 'running') => void;
  bottomInset: number;
}) {
  const theme = useTheme();
  const workouts = useWorkouts((s) => s);
  const body = useBody((s) => s);
  const profile = useProfile((p) => p);
  const prefs = usePreferences((p) => p);
  const draft = workouts.drafts[today];
  const sessions = useMemo(() => completedSessions(workouts), [workouts]);
  const preferences = { ...workouts.preferences, weightUnit: prefs.weightUnit };
  // Same rule as Finish and the saved session: a set with "0" reps is not performed.
  const performed = draft?.exercises.flatMap((e) => e.sets).filter(isSetPerformed).length ?? 0;
  const hasWork = draft?.exercises.some(hasLoggedWork) ?? false;
  const draftDate = dateFromDayKey(today);
  const isToday = isSameDay(draftDate, new Date());
  const [savedSummary, setSavedSummary] = useState<string | null>(null);
  const [inspecting, setInspecting] = useState<WorkoutSession | null>(null);

  const toggleWeightUnit = () => {
    const next = prefs.weightUnit === 'kg' ? 'lbs' : 'kg';
    // Convert what is already typed so 185 lbs reads 83.9 kg instead of becoming 185 kg.
    workoutsStore.dispatch({ type: 'draft/convertWeightUnit', from: prefs.weightUnit, to: next });
    setPreferences({ weightUnit: next });
  };

  const finish = () => {
    if (!draft || !hasWork) return;
    const session = finishDraft(draft, newId, new Date(), preferences, latestWeight(body)?.weightKg ?? profile.weightKg);
    workoutsStore.dispatch({ type: 'session/finish', dayKey: today, session });
    const summary = session.caloriesBurned
      ? `${performedSetCount(session)} sets · ${repCount(session)} reps · ~${session.caloriesBurned} kcal estimated`
      : `${performedSetCount(session)} sets · ${repCount(session)} reps`;
    setSavedSummary(summary);
  };

  const discard = () =>
    Alert.alert('Discard workout?', isToday ? 'This removes every exercise you added today.' : 'This removes every exercise in this unfinished workout.', [
      { text: 'Keep', style: 'cancel' },
      { text: 'Discard', style: 'destructive', onPress: () => workoutsStore.dispatch({ type: 'draft/discard', dayKey: today }) },
    ]);

  const cycleScale = () => {
    const index = rpeScales.indexOf(workouts.preferences.rpeScale);
    const next: RPEScale = rpeScales[(index + 1) % rpeScales.length] ?? 'strength';
    workoutsStore.dispatch({ type: 'preferences/update', patch: { rpeScale: next } });
  };

  return (
    <>
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ padding: 20, gap: 18, paddingBottom: bottomInset + 32 }} scrollIndicatorInsets={{ bottom: bottomInset }}>
        <Row style={{ justifyContent: 'space-between' }}>
          <View>
            <AppText variant="title2">{isToday ? 'Today' : 'Unfinished workout'}</AppText>
            <AppText variant="subheadline" tone="secondary">
              {draftDate.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}
            </AppText>
          </View>
          <Row style={{ gap: 8 }}>
            <Pressable accessibilityRole="button" accessibilityLabel="Weight unit" onPress={toggleWeightUnit} style={{ paddingHorizontal: 10, paddingVertical: 6, borderRadius: theme.radii.pill, backgroundColor: theme.accentAlpha(0.12) }}>
              <AppText variant="footnoteSemibold" tone="accent">
                {prefs.weightUnit}
              </AppText>
            </Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel="RPE scale" onPress={cycleScale} style={{ paddingHorizontal: 10, paddingVertical: 6, borderRadius: theme.radii.pill, backgroundColor: theme.accentAlpha(0.12) }}>
              <AppText variant="footnoteSemibold" tone="accent">
                {rpeScaleTitle(workouts.preferences.rpeScale)}
              </AppText>
            </Pressable>
          </Row>
        </Row>

        {!draft || draft.exercises.length === 0 ? (
          <Card style={{ alignItems: 'center', gap: 12, paddingVertical: 28 }}>
            <View style={{ width: 72, height: 72, borderRadius: 36, backgroundColor: theme.accentAlpha(0.12), alignItems: 'center', justifyContent: 'center' }}>
              <Icon name="figure.strengthtraining.traditional" size={32} color={theme.colors.accent} />
            </View>
            <AppText variant="headline">Start today's workout</AppText>
            <AppText variant="subheadline" tone="secondary" align="center">
              Add exercises from the library, log weight, reps and RPE per set, then finish to save it to your diary.
            </AppText>
            <AddExerciseControl title="Add Exercise" onAddExercise={onAddExercise} onOutdoor={onOutdoor} stretch />
          </Card>
        ) : (
          <>
            {draft.exercises.map((exercise) => (
              <DraftExerciseCard key={exercise.id} dayKey={today} exercise={exercise} weightUnit={prefs.weightUnit} rpeScale={workouts.preferences.rpeScale} />
            ))}
            <AddExerciseControl title="Add Exercise" onAddExercise={onAddExercise} onOutdoor={onOutdoor} />
            <PrimaryButton title={performed > 0 ? `Finish Workout · ${performed} ${performed === 1 ? 'set' : 'sets'}` : 'Finish Workout'} disabled={!hasWork} onPress={finish} />
            <Pressable accessibilityRole="button" onPress={discard} style={{ alignSelf: 'center' }}>
              <AppText variant="footnoteSemibold" tone="destructive">
                Discard
              </AppText>
            </Pressable>
          </>
        )}

        {sessions.length > 0 ? (
          <View style={{ gap: 6 }}>
            <AppText variant="footnote" tone="secondary" style={{ textTransform: 'uppercase', letterSpacing: 0.3, paddingHorizontal: 4 }}>
              History
            </AppText>
            <Card padded={false} style={{ overflow: 'hidden' }}>
              {sessions.slice(0, 20).map((session, index) => (
                <View key={session.id}>
                  {index > 0 ? <Divider style={{ marginLeft: theme.spacing.lg }} /> : null}
                  <SessionRow session={session} onInspect={() => setInspecting(session)} />
                </View>
              ))}
            </Card>
          </View>
        ) : null}
      </ScrollView>

      <BottomSheet visible={savedSummary !== null} title="Workout saved" onDismiss={() => setSavedSummary(null)} surface="card">
        <AppText variant="subheadline" tone="secondary" align="center">
          {savedSummary}
        </AppText>
        <PrimaryButton title="Done" onPress={() => setSavedSummary(null)} />
      </BottomSheet>

      <BottomSheet visible={inspecting !== null} title={inspecting ? new Date(inspecting.diaryDate).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }) : 'Workout'} onDismiss={() => setInspecting(null)}>
        {inspecting ? (
          <>
            <AppText variant="subheadline" tone="secondary">
              {inspecting.exercises.map((e) => `${e.name} · ${e.sets.length} sets`).join('\n')}
            </AppText>
            <SecondaryButton
              title="Delete"
              onPress={() =>
                Alert.alert('Delete workout?', undefined, [
                  { text: 'Cancel', style: 'cancel' },
                  {
                    text: 'Delete',
                    style: 'destructive',
                    onPress: () => {
                      workoutsStore.dispatch({ type: 'session/delete', id: inspecting.id });
                      setInspecting(null);
                    },
                  },
                ])
              }
            />
          </>
        ) : null}
      </BottomSheet>
    </>
  );
}

function SessionRow({ session, onInspect }: { session: WorkoutSession; onInspect: () => void }) {
  const theme = useTheme();
  const remove = () =>
    Alert.alert('Delete workout?', undefined, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => workoutsStore.dispatch({ type: 'session/delete', id: session.id }) },
    ]);
  return (
    <NativeMenu
      items={[{ id: 'delete', title: 'Delete', systemImage: 'trash', destructive: true }]}
      trigger="longPress"
      onPress={onInspect}
      onSelect={(id) => {
        if (id === 'delete') remove();
      }}
    >
      <Row style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 12, gap: 12 }}>
        <View style={{ flex: 1, gap: 2 }}>
          <AppText variant="body" weight="500">
            {new Date(session.diaryDate).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })}
          </AppText>
          <AppText variant="caption" tone="secondary" numberOfLines={1}>
            {session.exercises.map((e) => e.name).join(', ')}
          </AppText>
          <AppText variant="caption" tone="secondary">
            {performedSetCount(session)} sets · {repCount(session)} reps · {durationMinutes(session)} min
          </AppText>
        </View>
        {session.caloriesBurned ? (
          <AppText variant="subheadlineSemibold" tone="accent">
            ~{session.caloriesBurned} kcal
          </AppText>
        ) : null}
      </Row>
    </NativeMenu>
  );
}

function DraftExerciseCard({ dayKey: today, exercise, weightUnit, rpeScale }: { dayKey: string; exercise: DraftExercise; weightUnit: WeightUnit; rpeScale: RPEScale }) {
  const theme = useTheme();
  const update = (set: DraftSet) => workoutsStore.dispatch({ type: 'draft/updateSet', dayKey: today, exerciseId: exercise.id, set });
  const rpeHint = rpeScale === 'borg' ? '6–20' : rpeScale === 'cr10' ? '0–10' : '1–10';
  return (
    <Card style={{ gap: 10 }}>
      <Row style={{ justifyContent: 'space-between', gap: 8 }}>
        <View style={{ flex: 1 }}>
          <AppText variant="headline" numberOfLines={2}>
            {exercise.name}
          </AppText>
          <AppText variant="caption" tone="secondary">
            {[...exercise.targetMuscles, exercise.equipment].filter(Boolean).join(' · ')}
          </AppText>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Remove exercise"
          onPress={() => workoutsStore.dispatch({ type: 'draft/removeExercise', dayKey: today, exerciseId: exercise.id })}
          style={{ width: 32, height: 32, alignItems: 'center', justifyContent: 'center' }}
        >
          <Icon name="trash" size={18} color={theme.colors.destructive} />
        </Pressable>
      </Row>
      {(exercise.durationSeconds ?? 0) > 0 ? (
        <AppText variant="subheadlineSemibold" tone="accent">
          {Math.round((exercise.durationSeconds ?? 0) / 60)} min
        </AppText>
      ) : null}
      {exercise.sets.length === 0 ? null : (
      <>
      <Row style={{ gap: 8, paddingHorizontal: 4 }}>
        <AppText variant="caption2Semibold" tone="secondary" style={{ width: 32 }}>
          SET
        </AppText>
        <AppText variant="caption2Semibold" tone="secondary" style={{ flex: 1, textAlign: 'center' }}>
          {weightUnit.toUpperCase()}
        </AppText>
        <AppText variant="caption2Semibold" tone="secondary" style={{ flex: 1, textAlign: 'center' }}>
          REPS
        </AppText>
        <AppText variant="caption2Semibold" tone="secondary" style={{ flex: 1, textAlign: 'center' }}>
          RPE {rpeHint}
        </AppText>
        <View style={{ width: 28 }} />
      </Row>
      {exercise.sets.map((set, index) => (
        <Row key={set.id} style={{ gap: 8, paddingHorizontal: 4 }}>
          <AppText variant="subheadlineSemibold" style={{ width: 32 }}>
            {index + 1}
          </AppText>
          <SetField value={set.weight} placeholder="—" onChange={(weight) => update({ ...set, weight, weightUnit })} />
          <SetField value={set.reps} placeholder="0" onChange={(reps) => update({ ...set, reps: reps.replace(/[^0-9]/g, '') })} />
          <SetField value={set.rpe} placeholder="—" onChange={(rpe) => update({ ...set, rpe, rpeScale })} />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Remove set"
            onPress={() => workoutsStore.dispatch({ type: 'draft/removeSet', dayKey: today, exerciseId: exercise.id, setId: set.id })}
            style={{ width: 28, height: 36, alignItems: 'center', justifyContent: 'center' }}
          >
            <Icon name="minus" size={16} color={theme.colors.secondaryLabel} />
          </Pressable>
        </Row>
      ))}
      <Pressable
        accessibilityRole="button"
        onPress={() => {
          const last = exercise.sets[exercise.sets.length - 1];
          workoutsStore.dispatch({
            type: 'draft/addSet',
            dayKey: today,
            exerciseId: exercise.id,
            set: {
              id: newId(),
              weight: last?.weight ?? '',
              reps: '',
              rpe: last?.rpe ?? '',
              ...(last?.weight ? { weightUnit: last.weightUnit ?? weightUnit } : {}),
              ...(last?.rpe ? { rpeScale: last.rpeScale ?? rpeScale } : {}),
            },
          });
        }}
        style={({ pressed }) => ({ alignSelf: 'flex-start', opacity: pressed ? 0.6 : 1 })}
      >
        <Row style={{ gap: 6 }}>
          <Icon name="plus.circle.fill" size={16} color={theme.colors.accent} />
          <AppText variant="subheadlineSemibold" tone="accent">
            Add Set
          </AppText>
        </Row>
      </Pressable>
      </>
      )}
    </Card>
  );
}

function SetField({ value, placeholder, onChange }: { value: string; placeholder: string; onChange: (value: string) => void }) {
  const theme = useTheme();
  return (
    <TextInput
      value={value}
      onChangeText={(v) => onChange(v.replace(/[^0-9.,]/g, ''))}
      keyboardType="decimal-pad"
      placeholder={placeholder}
      placeholderTextColor={theme.colors.placeholder}
      selectTextOnFocus
      style={[theme.text.body, { flex: 1, height: 36, textAlign: 'center', color: theme.colors.label, borderRadius: 8, backgroundColor: theme.colors.fill, paddingVertical: 0 }]}
    />
  );
}
