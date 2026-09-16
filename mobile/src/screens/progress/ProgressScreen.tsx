/**
 * Progress tab. Mirrors `ProgressTabView` in `ContentView.swift` with the sections from
 * `ProgressComponents.swift` / `ProgressMetricViews.swift`: overview pills (My Progress /
 * Weekly Challenge), time-range segments, metric segments, the Weight / Body Fat / Workouts
 * chart card with stat badges, history link, then calories, macro and nutrient averages
 * for the range. Weekly Challenge scores locally; the leaderboard is a remote seam.
 */

import { BottomTabBarHeightContext } from '@react-navigation/bottom-tabs';
import { useContext, useEffect, useMemo, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, Switch, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { BottomSheet } from '../../components/BottomSheet';
import { PickerSheet } from '../../components/PickerSheet';

import { BarChart } from '../../components/charts/BarChart';
import { TrendLineChart } from '../../components/charts/TrendLineChart';
import { Icon, type SFSymbolName } from '../../components/Icon';
import { AppText, Card, PrimaryButton, Row, Screen } from '../../components/primitives';
import { ProgressBarRow } from '../../components/ProgressBarRow';
import { PillTabs, SegmentedControl } from '../../components/SegmentedControl';
import { displayWeight, entriesInRange, latestBodyFat, latestWeight } from '../../domain/body/bodyState';
import {
  availableProgressMetrics,
  average,
  computeFoodRangeStats,
  downsampleTrend,
  formatSigned,
  loggingStats,
  netChange,
  progressMetricTitle,
  progressOverviewModes,
  progressOverviewModeTitle,
  timeRangeDates,
  timeRangeDays,
  timeRanges,
  type ProgressMetric,
  type ProgressOverviewMode,
  type TimeRange,
  type TrendPoint,
} from '../../domain/progress/progress';
import { weeklyChallengeScore, weeklyChallengeWeek } from '../../domain/progress/weeklyChallenge';
import { dailyTargets } from '../../domain/profile/userProfile';
import { burnSessions, dailyBurn, durationMinutes, performedSetCount, totalBurn } from '../../domain/workouts/workoutSessions';
import { writeBodyFatToHealth } from '../../services/health';
import {
  fetchLeaderboard,
  joinWeeklyChallenge,
  leaveWeeklyChallenge,
  loadChallengeProfile,
} from '../../services/weeklyChallenge';
import { addWeighIn, bodyStore, deleteBodyFatEntry, deleteWeighIn, newId, setPreferences, useBody, useDiary, usePreferences, useProfile, useWorkouts, workoutsStore } from '../../state/appStores';
import {
  weeklyChallengeCategories,
  weeklyChallengeSocialPlatforms,
  weeklyChallengeValidationMessages,
  validateWeeklyChallengeProfile,
  type WeeklyChallengeCategory,
  type WeeklyChallengeLeaderboardResponse,
  type WeeklyChallengePublicProfile,
  type WeeklyChallengeSocialPlatform,
} from '../../domain/progress/weeklyChallengeApi';
import { useTheme } from '../../theme';
import { BodyFatHistorySheet, LogBodyFatSheet, LogWeightSheet, WeightHistorySheet } from './ProgressSheets';

type Sheet = 'logWeight' | 'logBodyFat' | 'weightHistory' | 'bodyFatHistory' | null;

export function ProgressScreen() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const tabBarHeight = useContext(BottomTabBarHeightContext) ?? insets.bottom;
  const [mode, setMode] = useState<ProgressOverviewMode>('myProgress');
  const [range, setRange] = useState<TimeRange>('1W');
  const [metric, setMetric] = useState<ProgressMetric>('weight');
  const [sheet, setSheet] = useState<Sheet>(null);
  const [sheetEpoch, setSheetEpoch] = useState(0);

  const body = useBody((s) => s);
  const diary = useDiary((s) => s);
  const profile = useProfile((s) => s);
  const prefs = usePreferences((s) => s);
  const workouts = useWorkouts((s) => s);
  const useMetric = prefs.weightUnit === 'kg';
  const unit = useMetric ? 'kg' : 'lbs';

  const targets = useMemo(() => dailyTargets(profile), [profile]);
  const { start, end } = useMemo(() => timeRangeDates(range), [range]);
  const weightEntries = useMemo(() => entriesInRange(body.weightEntries, start, end), [body.weightEntries, start, end]);
  const bodyFatEntries = useMemo(() => entriesInRange(body.bodyFatEntries, start, end), [body.bodyFatEntries, start, end]);
  const burn = useMemo(() => burnSessions(workouts), [workouts]);
  const burnInRange = useMemo(
    () => burn.filter((s) => entriesInRange([{ date: s.diaryDate }], start, end).length > 0),
    [burn, start, end],
  );

  const showsBodyFat = body.bodyFatEntries.length > 0 || profile.bodyFatPercentage !== undefined || profile.goalBodyFatPercentage !== undefined;
  const metrics = availableProgressMetrics({ bodyFatAvailable: showsBodyFat, workoutBurnAvailable: burn.length > 0 });
  useEffect(() => {
    if (!metrics.includes(metric)) setMetric('weight');
  }, [metrics, metric]);

  const foodStats = useMemo(() => computeFoodRangeStats(diary.foodEntries, timeRangeDays(range), targets), [diary.foodEntries, range, targets]);
  const stats = useMemo(() => loggingStats(diary.foodEntries, targets.calories), [diary.foodEntries, targets.calories]);

  const openSheet = (next: Exclude<Sheet, null>) => {
    setSheetEpoch((e) => e + 1);
    setSheet(next);
  };

  const currentWeightKg = latestWeight(body)?.weightKg ?? profile.weightKg;
  const currentBodyFat = latestBodyFat(body)?.bodyFatFraction ?? profile.bodyFatPercentage ?? 0.2;

  return (
    <Screen>
      <View style={{ paddingHorizontal: theme.spacing.lg, paddingTop: 10, paddingBottom: 4 }}>
        <PillTabs<ProgressOverviewMode>
          tabs={progressOverviewModes.map((m) => ({
            value: m,
            label: progressOverviewModeTitle(m),
            icon: <Icon name={m === 'myProgress' ? 'chart.line.uptrend.xyaxis' : 'trophy.fill'} size={14} color={m === mode ? theme.colors.onAccent : theme.colors.secondaryLabel} />,
          }))}
          selected={mode}
          onSelect={setMode}
        />
      </View>

      {mode === 'myProgress' ? (
        <ScrollView contentContainerStyle={{ paddingVertical: theme.spacing.lg, paddingHorizontal: theme.spacing.lg, gap: 18, paddingBottom: tabBarHeight + 32 }} scrollIndicatorInsets={{ bottom: tabBarHeight }}>
          <SegmentedControl<TimeRange> segments={timeRanges.map((r) => ({ value: r, label: r }))} selected={range} onSelect={setRange} accessibilityLabel="Time Range" />
          {metrics.length > 1 ? (
            <SegmentedControl<ProgressMetric> segments={metrics.map((m) => ({ value: m, label: progressMetricTitle(m) }))} selected={metric} onSelect={setMetric} accessibilityLabel="Progress metric" />
          ) : null}

          {metric === 'weight' ? (
            <TrendCard
              title="Weight"
              actionLabel="Log Weight"
              onAction={() => openSheet('logWeight')}
              emptyText="Log your first weight to see trends"
              points={weightEntries.map((e) => ({ time: new Date(e.date).getTime(), value: displayWeight(e.weightKg, useMetric) }))}
              goal={profile.goalWeightKg !== undefined ? displayWeight(profile.goalWeightKg, useMetric) : undefined}
              current={latestWeight(body) ? displayWeight(latestWeight(body)!.weightKg, useMetric) : undefined}
              unit={unit}
              format={(v) => `${v.toFixed(1)} ${unit}`}
              fallbackDomain={useMetric ? [40, 120] : [90, 260]}
              historyCount={body.weightEntries.length}
              onHistory={() => openSheet('weightHistory')}
            />
          ) : null}

          {metric === 'bodyFat' ? (
            <TrendCard
              title="Body Fat"
              actionLabel="Log Body Fat"
              onAction={() => openSheet('logBodyFat')}
              emptyText="Log your first body-fat reading to see trends"
              points={bodyFatEntries.map((e) => ({ time: new Date(e.date).getTime(), value: e.bodyFatFraction * 100 }))}
              goal={profile.goalBodyFatPercentage !== undefined ? profile.goalBodyFatPercentage * 100 : undefined}
              current={latestBodyFat(body) ? latestBodyFat(body)!.bodyFatFraction * 100 : profile.bodyFatPercentage !== undefined ? profile.bodyFatPercentage * 100 : undefined}
              unit="%"
              format={(v) => `${v.toFixed(1)}%`}
              fallbackDomain={[5, 45]}
              historyCount={body.bodyFatEntries.length}
              onHistory={() => openSheet('bodyFatHistory')}
            />
          ) : null}

          {metric === 'workouts' ? (
            <ProgressCard title="Workout Burn" trailing={<AppText variant="subheadline" tone="secondary" weight="500">{`${totalBurn(burnInRange).toLocaleString()} kcal`}</AppText>}>
              {burnInRange.length === 0 ? (
                <EmptyChart text="No workouts with a calorie estimate in this range" />
              ) : (
                <>
                  <BarChart
                    data={dailyBurn(burnInRange).map((d) => ({ id: d.day, value: d.calories }))}
                  />
                  <View style={{ gap: 8 }}>
                    {[...burnInRange].reverse().slice(0, 5).map((session) => (
                      <Row key={session.id} style={{ justifyContent: 'space-between' }}>
                        <View>
                          <AppText variant="subheadline" weight="500">
                            {new Date(session.diaryDate).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })}
                          </AppText>
                          <AppText variant="caption" tone="secondary">
                            {session.exercises.length} exercises · {performedSetCount(session)} sets · {durationMinutes(session)} min
                          </AppText>
                        </View>
                        <Pressable
                          accessibilityRole="button"
                          onLongPress={() =>
                            Alert.alert('Delete workout?', undefined, [
                              { text: 'Cancel', style: 'cancel' },
                              { text: 'Delete', style: 'destructive', onPress: () => workoutsStore.dispatch({ type: 'session/delete', id: session.id }) },
                            ])
                          }
                        >
                          <AppText variant="subheadlineSemibold" tone="accent">
                            {session.caloriesBurned} kcal
                          </AppText>
                        </Pressable>
                      </Row>
                    ))}
                  </View>
                  <AppText variant="caption2" tone="tertiary">
                    Estimates are for the diary only and never change your calorie or macro targets.
                  </AppText>
                </>
              )}
            </ProgressCard>
          ) : null}

          <ProgressCard
            title="Calories"
            trailing={
              foodStats.dailyCalories.length > 0 ? (
                <AppText variant="subheadline" tone="secondary" weight="500">
                  Avg: {Math.round(average(foodStats.dailyCalories.map((d) => d.calories))).toLocaleString()} kcal
                </AppText>
              ) : undefined
            }
          >
            {foodStats.dailyCalories.length === 0 ? (
              <EmptyChart text="No food logged yet" />
            ) : (
              <BarChart
                data={foodStats.dailyCalories.map((d) => ({ id: d.day, value: d.calories }))}
                goal={targets.calories}
              />
            )}
          </ProgressCard>

          <ProgressCard title="Macro Averages">
            <ProgressBarRow label="Protein" current={foodStats.avgProtein} goal={targets.protein} />
            <ProgressBarRow label="Carbs" current={foodStats.avgCarbs} goal={targets.carbs} />
            <ProgressBarRow label="Fat" current={foodStats.avgFat} goal={targets.fat} />
          </ProgressCard>

          {foodStats.nutrientItems.length > 0 ? (
            <ProgressCard title="Nutrient Averages">
              {foodStats.nutrientItems.map((item) => (
                <ProgressBarRow key={item.id} label={item.label} current={item.current} goal={item.goal} unit={item.unit} />
              ))}
            </ProgressCard>
          ) : null}

          <ProgressCard title="Streaks & Stats">
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
              <StatTile icon="flame.fill" label="Current Streak" value={`${stats.currentStreak} days`} />
              <StatTile icon="trophy.fill" label="Best Streak" value={`${stats.bestStreak} days`} />
              <StatTile icon="target" label="Days on Target" value={String(stats.daysOnTarget)} />
              <StatTile icon="fork.knife" label="Total Entries" value={String(stats.totalEntries)} />
            </View>
          </ProgressCard>
        </ScrollView>
      ) : (
        <WeeklyChallengePane tabBarHeight={tabBarHeight} />
      )}

      <LogWeightSheet
        key={`weight-${sheetEpoch}`}
        visible={sheet === 'logWeight'}
        currentWeightKg={currentWeightKg}
        unit={prefs.weightUnit}
        onChangeUnit={(weightUnit) => setPreferences({ weightUnit })}
        onDismiss={() => setSheet(null)}
        onSave={(weightKg) => {
          // Also moves `profile.weightKg`, like `WeightStore.addEntry`, so targets and Coach follow the scale.
          addWeighIn({ id: newId(), date: new Date().toISOString(), weightKg });
          if (profile.goalWeightKg !== undefined && profile.goal !== 'maintain') {
            const reached = profile.goal === 'lose' ? weightKg <= profile.goalWeightKg : weightKg >= profile.goalWeightKg;
            if (reached) {
              Alert.alert(
                'Congratulations!',
                "You've reached your goal weight! Head to Settings to switch your goal (Maintain, Lose, or Gain) and tap Recalculate Goals to refresh your targets.",
                [{ text: 'Keep Going' }],
              );
            }
          }
          setSheet(null);
        }}
      />
      <LogBodyFatSheet
        key={`fat-${sheetEpoch}`}
        visible={sheet === 'logBodyFat'}
        currentFraction={currentBodyFat}
        onDismiss={() => setSheet(null)}
        onSave={(bodyFatFraction) => {
          const entry = { id: newId(), date: new Date().toISOString(), bodyFatFraction };
          bodyStore.dispatch({ type: 'bodyFat/add', entry });
          writeBodyFatToHealth(bodyFatFraction, new Date(), entry.id);
          setSheet(null);
        }}
      />
      <WeightHistorySheet
        visible={sheet === 'weightHistory'}
        entries={body.weightEntries}
        useMetric={useMetric}
        onDismiss={() => setSheet(null)}
        onDelete={(entry) => deleteWeighIn(entry.id)}
      />
      <BodyFatHistorySheet
        visible={sheet === 'bodyFatHistory'}
        entries={body.bodyFatEntries}
        onDismiss={() => setSheet(null)}
        onDelete={(entry) => deleteBodyFatEntry(entry.id)}
      />
    </Screen>
  );
}

// MARK: - Cards

function ProgressCard({ title, trailing, children }: { title: string; trailing?: React.ReactNode; children: React.ReactNode }) {
  const theme = useTheme();
  // `progressCardStyle`: appCard, radius 20, hairline accent stroke.
  return (
    <Card style={{ gap: 12, borderRadius: theme.radii.cardLarge, borderWidth: 0.75, borderColor: theme.accentAlpha(0.09) }}>
      <Row style={{ justifyContent: 'space-between' }}>
        <AppText variant="headline">{title}</AppText>
        {trailing}
      </Row>
      {children}
    </Card>
  );
}

function EmptyChart({ text }: { text: string }) {
  return (
    <View style={{ height: 120, alignItems: 'center', justifyContent: 'center' }}>
      <AppText variant="subheadline" tone="secondary" align="center">
        {text}
      </AppText>
    </View>
  );
}

interface TrendCardProps {
  title: string;
  actionLabel: string;
  onAction: () => void;
  emptyText: string;
  points: TrendPoint[];
  goal: number | undefined;
  current: number | undefined;
  unit: string;
  format: (value: number) => string;
  fallbackDomain: [number, number];
  historyCount: number;
  onHistory: () => void;
}

/** `WeightChartSection` / `BodyFatChartSection` + the history link underneath. */
function TrendCard({ title, actionLabel, onAction, emptyText, points, goal, current, unit, format, fallbackDomain, historyCount, onHistory }: TrendCardProps) {
  const theme = useTheme();
  const sorted = useMemo(() => [...points].sort((a, b) => a.time - b.time), [points]);
  const plotted = useMemo(() => downsampleTrend(sorted), [sorted]);
  return (
    <View style={{ gap: 12 }}>
      <ProgressCard
        title={title}
        trailing={
          <Pressable accessibilityRole="button" onPress={onAction} style={({ pressed }) => ({ opacity: pressed ? 0.6 : 1 })}>
            <Row style={{ gap: 4 }}>
              <Icon name="plus.circle.fill" size={16} color={theme.colors.accent} />
              <AppText variant="subheadline" tone="accent" weight="500">
                {actionLabel}
              </AppText>
            </Row>
          </Pressable>
        }
      >
        {sorted.length === 0 ? (
          <EmptyChart text={emptyText} />
        ) : (
          <>
            <Row style={{ gap: 8 }}>
              {current !== undefined ? <StatBadge label="Current" value={format(current)} /> : null}
              {goal !== undefined ? <StatBadge label="Goal" value={format(goal)} /> : null}
              <StatBadge label="Net Change" value={formatSigned(netChange(sorted), unit)} />
              <StatBadge label="Average" value={format(average(sorted.map((p) => p.value)))} />
            </Row>
            <TrendLineChart points={plotted} goal={goal} formatValue={(v) => `${v.toFixed(1)}`} fallbackDomain={fallbackDomain} />
          </>
        )}
      </ProgressCard>
      {historyCount > 0 ? (
        <Pressable accessibilityRole="button" onPress={onHistory} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}>
          <Card>
            <Row style={{ gap: 12 }}>
              <Icon name="list.bullet.rectangle" size={18} color={theme.colors.accent} />
              <View style={{ flex: 1 }}>
                <AppText variant="subheadlineSemibold">{title} History</AppText>
                <AppText variant="caption" tone="secondary">
                  {historyCount} {historyCount === 1 ? 'entry' : 'entries'} · tap to view or delete
                </AppText>
              </View>
              <Icon name="chevron.right" size={14} color={theme.colors.tertiaryLabel} />
            </Row>
          </Card>
        </Pressable>
      ) : null}
    </View>
  );
}

function StatBadge({ label, value }: { label: string; value: string }) {
  const theme = useTheme();
  return (
    <View style={{ flex: 1, alignItems: 'center', gap: 2, paddingHorizontal: 4, paddingVertical: 8, borderRadius: 10, backgroundColor: theme.accentAlpha(0.055) }}>
      <AppText variant="subheadlineSemibold" numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
        {value}
      </AppText>
      <AppText variant="caption2" tone="secondary" numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75}>
        {label}
      </AppText>
    </View>
  );
}

function StatTile({ icon, label, value }: { icon: SFSymbolName; label: string; value: string }) {
  const theme = useTheme();
  return (
    <View style={{ width: '47%', flexGrow: 1, alignItems: 'center', gap: 6, paddingVertical: 12, borderRadius: theme.radii.control, backgroundColor: theme.accentAlpha(0.08) }}>
      <Icon name={icon} size={22} color={theme.colors.accent} />
      <AppText variant="title3" weight="700">
        {value}
      </AppText>
      <AppText variant="caption" tone="secondary" align="center">
        {label}
      </AppText>
    </View>
  );
}

// MARK: - Weekly Challenge

/**
 * `WeeklyChallengeView`, scored on device. Leaderboard accounts call the in-repo
 * `WeeklyChallengeAPIClient` (`https://fud-ai.app/api/challenge/v1`).
 */
function WeeklyChallengePane({ tabBarHeight }: { tabBarHeight: number }) {
  const theme = useTheme();
  const diary = useDiary((s) => s);
  const profile = useProfile((s) => s);
  const prefs = usePreferences((s) => s);
  const workouts = useWorkouts((s) => s);
  const targets = useMemo(() => dailyTargets(profile), [profile]);
  const week = weeklyChallengeWeek(new Date());
  const score = useMemo(
    () =>
      weeklyChallengeScore({
        foods: diary.foodEntries.map((e) => ({ date: e.timestamp, calories: e.calories })),
        water: diary.waterEntries.map((e) => ({ date: e.date, milliliters: e.milliliters })),
        activities: workouts.sessions.map((s) => ({ date: s.diaryDate, ...(s.caloriesBurned !== undefined ? { calories: s.caloriesBurned } : {}) })),
        calorieGoal: targets.calories,
        hydrationEnabled: prefs.waterTrackingEnabled,
        hydrationGoalMilliliters: prefs.waterDailyGoalMl,
      }),
    [diary, workouts.sessions, targets.calories, prefs.waterTrackingEnabled, prefs.waterDailyGoalMl],
  );
  const weekLabel = `${week.start.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} – ${week.end.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`;
  const [account, setAccount] = useState<WeeklyChallengePublicProfile | null>(null);
  const [board, setBoard] = useState<WeeklyChallengeLeaderboardResponse | null>(null);
  const [category, setCategory] = useState<WeeklyChallengeCategory>('overall');
  const [joinOpen, setJoinOpen] = useState(false);
  const [displayName, setDisplayName] = useState(profile.name ?? '');
  const [social, setSocial] = useState<WeeklyChallengeSocialPlatform | undefined>(undefined);
  const [handle, setHandle] = useState('');
  const [acceptedRules, setAcceptedRules] = useState(false);
  const [eligibilityAccepted, setEligibilityAccepted] = useState(false);
  const [socialPicker, setSocialPicker] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadChallengeProfile().then(setAccount);
  }, []);

  useEffect(() => {
    if (!account) {
      setBoard(null);
      return;
    }
    let cancelled = false;
    setBusy(true);
    void fetchLeaderboard(category)
      .then((next) => {
        if (!cancelled) setBoard(next);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Could not load the leaderboard.');
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });
    return () => {
      cancelled = true;
    };
  }, [account, category, score.overallPoints]);

  const pillar = (label: string, days: number, icon: SFSymbolName) => (
    <View key={label} style={{ flex: 1, alignItems: 'center', gap: 6, paddingVertical: 12, borderRadius: theme.radii.control, backgroundColor: theme.accentAlpha(0.08) }}>
      <Icon name={icon} size={20} color={theme.colors.accent} />
      <AppText variant="title3" weight="700">
        {days}/7
      </AppText>
      <AppText variant="caption" tone="secondary" align="center">
        {label}
      </AppText>
    </View>
  );

  const join = async () => {
    const validated = validateWeeklyChallengeProfile(displayName, social, handle);
    if (!validated.ok) {
      setError(weeklyChallengeValidationMessages[validated.error]);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await joinWeeklyChallenge({
        ...validated.profile,
        acceptedRules,
        eligibilityAccepted,
      });
      setAccount(created.profile);
      setJoinOpen(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not join the leaderboard.');
    } finally {
      setBusy(false);
    }
  };

  const leave = () =>
    Alert.alert('Leave the leaderboard?', 'Your public profile and token are removed from this device.', [
      { text: 'Stay', style: 'cancel' },
      {
        text: 'Leave',
        style: 'destructive',
        onPress: () => {
          void leaveWeeklyChallenge()
            .then(() => {
              setAccount(null);
              setBoard(null);
            })
            .catch((err: unknown) => {
              setError(err instanceof Error ? err.message : 'Remote challenge data was not deleted. Check your connection and try again.');
            });
        },
      },
    ]);

  return (
    <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: 18, paddingBottom: tabBarHeight + 32 }} scrollIndicatorInsets={{ bottom: tabBarHeight }}>
      <ProgressCard title="This Week" trailing={<AppText variant="subheadline" tone="secondary" weight="500">{weekLabel}</AppText>}>
        <Row style={{ alignItems: 'flex-end', gap: 6 }}>
          <AppText variant="largeTitle" tone="accent" style={{ fontSize: 48, lineHeight: 54 }}>
            {score.overallPoints}
          </AppText>
          <AppText variant="callout" tone="secondary" style={{ paddingBottom: 8 }}>
            / 28 points
          </AppText>
        </Row>
        <Row style={{ gap: 8 }}>
          {pillar('Consistency', score.consistencyDays, 'fork.knife')}
          {pillar('Nutrition', score.nutritionDays, 'target')}
        </Row>
        <Row style={{ gap: 8 }}>
          {pillar('Hydration', score.hydrationDays, 'drop.fill')}
          {pillar('Activity', score.activityDays, 'dumbbell.fill')}
        </Row>
        <AppText variant="caption" tone="secondary">
          One point per day you log food, land within ±15% of your calorie goal, hit your water goal, or record a workout. {score.activityKcal.toLocaleString()} kcal of activity so far.
        </AppText>
      </ProgressCard>

      <ProgressCard
        title="Leaderboard"
        trailing={
          account ? (
            <Pressable accessibilityRole="button" onPress={leave}>
              <AppText variant="subheadline" tone="destructive" weight="500">
                Leave
              </AppText>
            </Pressable>
          ) : undefined
        }
      >
        {account ? (
          <View style={{ gap: 12 }}>
            <SegmentedControl<WeeklyChallengeCategory>
              segments={weeklyChallengeCategories.map((c) => ({ value: c, label: c.charAt(0).toUpperCase() + c.slice(1) }))}
              selected={category}
              onSelect={setCategory}
              accessibilityLabel="Leaderboard category"
            />
            <AppText variant="caption" tone="secondary">
              Playing as {account.displayName}
              {account.socialHandle ? ` · ${account.socialPlatform === 'x' ? 'X' : 'Instagram'} @${account.socialHandle}` : ''}
            </AppText>
            {board?.rankings.length ? (
              board.rankings.slice(0, 20).map((row) => (
                <Row key={row.participantId} style={{ justifyContent: 'space-between' }}>
                  <AppText variant="subheadline" weight={row.isViewer ? '700' : '400'}>
                    {row.rank}. {row.displayName}
                  </AppText>
                  <AppText variant="subheadlineSemibold" tone="accent">
                    {row.score}
                  </AppText>
                </Row>
              ))
            ) : (
              <AppText variant="caption" tone="secondary">
                {busy ? 'Updating…' : 'No rankings for this week yet. Your local score will upload when you open this tab.'}
              </AppText>
            )}
          </View>
        ) : (
          <View style={{ gap: 12 }}>
            <AppText variant="caption" tone="secondary">
              Your score is computed on this device. Join to create a challenge profile and appear on the public board.
            </AppText>
            <PrimaryButton
              title="Join Leaderboard"
              onPress={() => {
                setAcceptedRules(false);
                setEligibilityAccepted(false);
                setJoinOpen(true);
              }}
            />
          </View>
        )}
        {error ? (
          <AppText variant="caption" tone="destructive">
            {error}
          </AppText>
        ) : null}
      </ProgressCard>

      <BottomSheet visible={joinOpen} title="Join Leaderboard" onDismiss={() => setJoinOpen(false)}>
        <AppText variant="footnote" tone="secondary">
          2–40 letters or numbers. Optional X or Instagram handle — no @ or URL.
        </AppText>
        <TextInput
          value={displayName}
          onChangeText={setDisplayName}
          placeholder="Display name"
          placeholderTextColor={theme.colors.tertiaryLabel}
          style={[theme.text.body, { color: theme.colors.label, backgroundColor: theme.colors.fill, borderRadius: theme.radii.control, padding: 12 }]}
        />
        <SettingsLikeRow
          title="Social"
          value={social ? (social === 'x' ? 'X' : 'Instagram') : 'None'}
          onPress={() => setSocialPicker(true)}
        />
        {social ? (
          <TextInput
            value={handle}
            onChangeText={setHandle}
            placeholder="handle"
            autoCapitalize="none"
            placeholderTextColor={theme.colors.tertiaryLabel}
            style={[theme.text.body, { color: theme.colors.label, backgroundColor: theme.colors.fill, borderRadius: theme.radii.control, padding: 12 }]}
          />
        ) : null}
        <Row style={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <AppText variant="body" style={{ flex: 1, paddingRight: 12 }}>
            I am 18 or older
          </AppText>
          <Switch
            value={eligibilityAccepted}
            onValueChange={setEligibilityAccepted}
            trackColor={{ true: theme.colors.accent, false: theme.colors.fill }}
            thumbColor="#FFFFFF"
          />
        </Row>
        <Row style={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <AppText variant="body" style={{ flex: 1, paddingRight: 12 }}>
            I agree to the Community Rules
          </AppText>
          <Switch
            value={acceptedRules}
            onValueChange={setAcceptedRules}
            trackColor={{ true: theme.colors.accent, false: theme.colors.fill }}
            thumbColor="#FFFFFF"
          />
        </Row>
        <Pressable onPress={() => void Linking.openURL('https://fud-ai.app/terms.html#community-rules')}>
          <AppText variant="footnote" tone="accent">
            Read Community Rules
          </AppText>
        </Pressable>
        <AppText variant="caption" tone="secondary">
          Fud AI does not read or upload your date of birth for this confirmation.
        </AppText>
        <PrimaryButton title={busy ? 'Joining…' : 'Create profile'} disabled={busy || !acceptedRules || !eligibilityAccepted} onPress={() => void join()} />
      </BottomSheet>
      <PickerSheet<WeeklyChallengeSocialPlatform | 'none'>
        visible={socialPicker}
        title="Social"
        options={[
          { value: 'none', label: 'No social link' },
          ...weeklyChallengeSocialPlatforms.map((p) => ({ value: p, label: p === 'x' ? 'X' : 'Instagram' })),
        ]}
        selected={social ?? 'none'}
        onSelect={(value) => setSocial(value === 'none' ? undefined : value)}
        onDismiss={() => setSocialPicker(false)}
      />
    </ScrollView>
  );
}

function SettingsLikeRow({ title, value, onPress }: { title: string; value: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1, paddingVertical: 8 })}>
      <Row style={{ justifyContent: 'space-between' }}>
        <AppText variant="body">{title}</AppText>
        <AppText variant="body" tone="secondary">
          {value}
        </AppText>
      </Row>
    </Pressable>
  );
}
