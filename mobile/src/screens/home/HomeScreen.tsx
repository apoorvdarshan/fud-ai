import { BottomTabBarHeightContext } from '@react-navigation/bottom-tabs';
import { useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, AppState, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { NativeMenu } from '../../components/NativeMenu';
import { BarcodeLookupError, barcodeLookupMessages } from '../../domain/food/openFoodFacts';
import { Icon } from '../../components/Icon';
import { CalorieGauge } from '../../components/home/CalorieGauge';
import { FastingRow, FoodRow, WaterLogRow } from '../../components/home/DiaryRows';
import { MacroVerticalBar } from '../../components/home/MacroVerticalBar';
import { WeekEnergyStrip } from '../../components/home/WeekEnergyStrip';
import { AppText, Card, Divider, LinkButton, Row, Screen } from '../../components/primitives';
import { addDays, dayKey, isSameDay, startOfDay } from '../../domain/dates';
import {
  activeFast,
  caloriesOn,
  completedFastsOn,
  foodEntriesOn,
  isFavorite,
  waterEntriesOn,
  waterTotalOn,
} from '../../domain/diary/diaryState';
import { displayedHomeNutrients, homeNutrientGoal, homeNutrients } from '../../domain/diary/homeNutrients';
import { foodLogSortOrderDisplayName, homeDiaryMealGroups, type FoodLogSortOrder } from '../../domain/diary/mealGroups';
import { addMenuConfigFromPrefs, buildHomeAddMenu, parseHomeAddMenuAction } from '../../domain/prefs/addMenu';
import { makeFoodEntry, mealTypeDisplayName, type FoodEntry } from '../../domain/food/food';
import { parseHomeTopNutrients } from '../../domain/prefs/preferences';
import { dailyTargets } from '../../domain/profile/userProfile';
import { waterDisplayAmount, waterUnitSymbol } from '../../domain/water/water';
import { aiErrorMessage } from '../../domain/ai/errors';
import { foodEntryInputFromAnalysis, type FoodAnalysis, type FoodAnalysisKind } from '../../domain/food/analysis';
import { favoriteEntries, frequentEntries, recentEntries } from '../../domain/diary/diaryState';
import type { FastingSession } from '../../domain/fasting/fasting';
import type { WaterEntry } from '../../domain/water/water';
import { analyzeFood } from '../../services/aiClient';
import { deleteFoodImage, storeFoodImage } from '../../services/foodImageStore';
import { ImagePermissionError, pickImage, type ImageSource } from '../../services/imagePicker';
import { diaryStore, newId, setPreferences, useDiary, usePreferences, useProfile } from '../../state/appStores';
import { useTheme } from '../../theme';
import { lookupBarcode } from '../../services/openFoodFacts';
import { deleteFoodFromHealth, replaceFoodInHealth, writeFoodToHealth } from '../../services/health';
import { AnalyzingOverlay, FoodResultSheet, SavedMealsSheet, TextFoodInputSheet, type FoodResultSave, type SavedMealsMode } from './FoodAISheets';
import { BarcodeScannerSheet } from './BarcodeScannerSheet';
import { VoiceMealSheet } from './VoiceMealSheet';
import {
  CopyFromDaySheet,
  FastingStartSheet,
  ManualEntrySheet,
  NutritionDetailSheet,
  WaterCustomSheet,
} from './HomeSheets';

type Sheet =
  | 'waterCustom'
  | 'fastingStart'
  | 'manualEntry'
  | 'editFood'
  | 'describeMeal'
  | 'voiceMeal'
  | 'savedMeals'
  | 'copyFromDay'
  | 'review'
  | 'nutritionDetail'
  | 'barcodeScan'
  | null;

type DiaryTarget =
  | { kind: 'food'; entry: FoodEntry }
  | { kind: 'water'; entry: WaterEntry }
  | { kind: 'fasting'; session: FastingSession };

interface PendingAnalysis {
  kind: FoodAnalysisKind;
  imageUri?: string;
}

interface ReviewState {
  kind: FoodAnalysisKind;
  analysis: FoodAnalysis;
  imageUri?: string;
  /** The analyzed JPEG, kept until Save so the diary entry can keep its photo on disk. */
  imageBase64?: string;
}

/**
 * Home: week strip, calorie dome, nutrient bars, unified diary and the "+" menu. Every number
 * on screen is derived from one `DiaryState` read, so a water or fasting change re-renders the
 * pillar and the diary in the same pass (#369).
 */
export function HomeScreen() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  // Tab bar is absolutely positioned over the screen; its height already includes the bottom inset.
  const tabBarHeight = useContext(BottomTabBarHeightContext) ?? insets.bottom;
  const [selectedDate, setSelectedDate] = useState(() => new Date());
  const [sheet, setSheet] = useState<Sheet>(null);
  // Bumped every time a draft sheet opens so it remounts with empty fields (no stale drafts).
  const [sheetEpoch, setSheetEpoch] = useState(0);
  const [savedMealsMode, setSavedMealsMode] = useState<SavedMealsMode>('all');
  const [now, setNow] = useState(() => new Date());
  const [pending, setPending] = useState<PendingAnalysis | null>(null);
  const [review, setReview] = useState<ReviewState | null>(null);
  const [diaryTarget, setDiaryTarget] = useState<DiaryTarget | null>(null);
  const analysisAbort = useRef<AbortController | null>(null);

  const diary = useDiary((state) => state);
  const profile = useProfile((state) => state);
  const prefs = usePreferences((state) => state);

  const isToday = isSameDay(selectedDate, now);
  const active = activeFast(diary);

  // Keep `now` honest: refresh on foreground, at the next local midnight, and once a minute
  // while a fast is running (live elapsed read-out). Without this, an app left open or resumed
  // overnight would still call yesterday "Today's Diary" while logging into the new day.
  useEffect(() => {
    const refresh = () => setNow(new Date());
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    let timer: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      const current = new Date();
      const untilMidnight = startOfDay(addDays(current, 1)).getTime() - current.getTime() + 1_000;
      timer = setTimeout(
        () => {
          refresh();
          schedule();
        },
        active ? Math.min(60_000, untilMidnight) : untilMidnight,
      );
    };
    schedule();
    return () => {
      subscription.remove();
      if (timer) clearTimeout(timer);
    };
  }, [active]);

  // When the day rolls over while "today" is selected, follow it; an explicitly chosen past day stays.
  const today = dayKey(now);
  const previousToday = useRef(today);
  useEffect(() => {
    if (previousToday.current === today) return;
    const wasToday = previousToday.current;
    previousToday.current = today;
    setSelectedDate((selected) => (dayKey(selected) === wasToday ? new Date() : selected));
  }, [today]);

  const targets = useMemo(() => dailyTargets(profile), [profile]);
  const dayFood = useMemo(() => foodEntriesOn(diary, selectedDate), [diary, selectedDate]);
  const dayWater = useMemo(() => waterEntriesOn(diary, selectedDate), [diary, selectedDate]);
  const dayFasts = useMemo(
    () => [...completedFastsOn(diary, selectedDate), ...(isToday && active ? [active] : [])],
    [diary, selectedDate, isToday, active],
  );
  const groups = useMemo(
    () => homeDiaryMealGroups({ foodEntries: dayFood, waterEntries: dayWater, fastingSessions: dayFasts, order: prefs.foodLogSortOrder, now }),
    [dayFood, dayWater, dayFasts, prefs.foodLogSortOrder, now],
  );

  const openSheet = (next: Exclude<Sheet, null>) => {
    setSheetEpoch((epoch) => epoch + 1);
    setSheet(next);
  };

  const addMenuItems = useMemo(
    () =>
      buildHomeAddMenu({
        config: addMenuConfigFromPrefs(prefs.addMenuConfig),
        fastingTrackingEnabled: prefs.fastingTrackingEnabled,
        waterTrackingEnabled: prefs.waterTrackingEnabled,
        hasActiveFast: active !== undefined,
        waterUnit: prefs.waterUnit,
      }),
    [prefs.addMenuConfig, prefs.fastingTrackingEnabled, prefs.waterTrackingEnabled, prefs.waterUnit, active],
  );

  const sortMenuItems = useMemo(
    () =>
      (['standard', 'latestMealsFirst'] as const).map((order) => ({
        id: order,
        title: foodLogSortOrderDisplayName(order),
      })),
    [],
  );

  const nutrients = displayedHomeNutrients(parseHomeTopNutrients(prefs.homeTopNutrients), prefs.waterTrackingEnabled);
  const waterGoalDisplay = waterDisplayAmount(prefs.waterUnit, prefs.waterDailyGoalMl);
  const waterTotalDisplay = waterDisplayAmount(prefs.waterUnit, waterTotalOn(diary, selectedDate));

  /** Today logs at the current time; another day keeps that day's date with the current time. */
  const logDate = useCallback(() => {
    if (isToday) return new Date();
    const d = new Date(selectedDate);
    const current = new Date();
    d.setHours(current.getHours(), current.getMinutes(), current.getSeconds(), 0);
    return d;
  }, [isToday, selectedDate]);

  const endFast = () => diaryStore.dispatch({ type: 'fasting/end', endedAt: new Date().toISOString() });
  const cancelFast = () =>
    Alert.alert('Cancel Fast?', 'This removes the active fast from your diary.', [
      { text: 'Keep Fasting', style: 'cancel' },
      { text: 'Cancel Fast', style: 'destructive', onPress: () => diaryStore.dispatch({ type: 'fasting/cancelActive' }) },
    ]);

  // MARK: - AI food logging

  /** Runs one analysis; the overlay's Cancel aborts the request and the transport enforces a timeout. */
  const runAnalysis = useCallback(
    async (kind: FoodAnalysisKind, input: { text?: string; imageBase64?: string; imageUri?: string }) => {
      analysisAbort.current?.abort();
      const controller = new AbortController();
      analysisAbort.current = controller;
      setPending({ kind, ...(input.imageUri ? { imageUri: input.imageUri } : {}) });
      try {
        const analysis = await analyzeFood(
          {
            kind,
            ...(input.text ? { text: input.text } : {}),
            ...(input.imageBase64 ? { imagesBase64: [input.imageBase64] } : {}),
          },
          controller.signal,
        );
        if (controller.signal.aborted) return;
        setReview({ kind, analysis, ...(input.imageUri ? { imageUri: input.imageUri } : {}), ...(input.imageBase64 ? { imageBase64: input.imageBase64 } : {}) });
        setSheet('review');
      } catch (error) {
        if (controller.signal.aborted) return;
        Alert.alert('Analysis failed', aiErrorMessage(error));
      } finally {
        if (analysisAbort.current === controller) {
          analysisAbort.current = null;
          setPending(null);
        }
      }
    },
    [],
  );

  const cancelAnalysis = () => {
    analysisAbort.current?.abort();
    analysisAbort.current = null;
    setPending(null);
  };

  useEffect(() => () => analysisAbort.current?.abort(), []);

  const captureAndAnalyze = async (kind: 'photo' | 'nutritionLabel', source: ImageSource) => {
    try {
      const picked = await pickImage(source);
      if (!picked) return;
      void runAnalysis(kind, { imageBase64: picked.base64, imageUri: picked.uri });
    } catch (error) {
      Alert.alert(kind === 'photo' ? 'Scan Food' : 'Scan Label', error instanceof ImagePermissionError ? error.message : 'Could not load that photo.');
    }
  };

  const openCameraOrLibrary = (kind: 'photo' | 'nutritionLabel', source: ImageSource) => {
    void captureAndAnalyze(kind, source);
  };

  const confirmDeleteFood = (entry: FoodEntry) =>
    Alert.alert('Delete Entry?', entry.name, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => deleteFoodEntry(entry) },
    ]);

  const confirmDeleteWater = (entry: WaterEntry) =>
    Alert.alert('Delete Entry?', undefined, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => diaryStore.dispatch({ type: 'water/delete', id: entry.id }) },
    ]);

  const confirmDeleteFast = (session: FastingSession) =>
    Alert.alert('Delete Entry?', undefined, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => diaryStore.dispatch({ type: 'fasting/delete', id: session.id }) },
    ]);

  const openFoodEdit = (entry: FoodEntry) => {
    setDiaryTarget({ kind: 'food', entry });
    openSheet('editFood');
  };

  const foodRowMenu = (entry: FoodEntry) => {
    const favorited = isFavorite(diary, entry);
    return [
      { id: 'favorite', title: favorited ? 'Unfavorite' : 'Favorite', systemImage: favorited ? 'heart.slash.fill' : 'heart.fill' },
      { id: 'delete', title: 'Delete', systemImage: 'trash', destructive: true },
    ];
  };

  const waterRowMenu = [{ id: 'delete', title: 'Delete', systemImage: 'trash', destructive: true }];

  const fastingRowMenu = (session: FastingSession) =>
    session.endedAt === undefined
      ? [
          { id: 'end', title: 'End Fast', systemImage: 'stop.fill' },
          { id: 'cancel', title: 'Cancel Fast', systemImage: 'trash', destructive: true },
        ]
      : [{ id: 'delete', title: 'Delete', systemImage: 'trash', destructive: true }];

  const nutritionDetailRows = useMemo(() => {
    const ids = (Object.keys(homeNutrients) as (keyof typeof homeNutrients)[]).filter(
      (id) => id !== 'protein' && id !== 'carbs' && id !== 'fat' && homeNutrients[id].total(dayFood) > 0,
    );
    return ids.map((id) => {
      const def = homeNutrients[id];
      const value = def.total(dayFood);
      const goal = homeNutrientGoal(id, targets);
      return {
        id,
        label: def.displayName,
        value: value >= 10 ? String(Math.round(value)) : value.toFixed(1),
        unit: def.unit,
        ...(goal > 0 ? { goal: `${goal} ${def.unit}` } : {}),
      };
    });
  }, [dayFood, targets]);

  const saveReview = (result: FoodResultSave) => {
    if (!review) return;
    const id = newId();
    // Photo and label scans keep their picture like the native diary: the JPEG goes to disk
    // under the entry id and only the filename is persisted with the entry.
    const imageFilename = review.imageBase64 ? storeFoodImage(review.imageBase64, id) : undefined;
    const input = foodEntryInputFromAnalysis(result.analysis, review.kind, logDate().toISOString(), {
      ...(result.mealType ? { mealType: result.mealType } : {}),
      ...(result.customNote !== undefined ? { customNote: result.customNote } : {}),
      ...(imageFilename ? { imageFilename } : {}),
    });
    const entry = makeFoodEntry(input, id);
    diaryStore.dispatch({ type: 'food/add', entry });
    writeFoodToHealth(entry);
    setReview(null);
    setSheet(null);
  };

  const deleteFoodEntry = (entry: FoodEntry) => {
    diaryStore.dispatch({ type: 'food/delete', id: entry.id });
    deleteFoodImage(entry.imageFilename);
    deleteFoodFromHealth(entry.id);
  };

  const relogEntry = (entry: FoodEntry) => {
    const { id: _id, timestamp: _timestamp, imageFilename: _image, additionalImageFilenames: _images, ...rest } = entry;
    const next = makeFoodEntry({ ...rest, timestamp: logDate().toISOString() }, newId());
    diaryStore.dispatch({ type: 'food/add', entry: next });
    writeFoodToHealth(next);
    setSheet(null);
  };

  const lookupScannedBarcode = (code: string) => {
    setSheet(null);
    analysisAbort.current?.abort();
    const controller = new AbortController();
    analysisAbort.current = controller;
    setPending({ kind: 'barcode' });
    void lookupBarcode(code, controller.signal)
      .then((analysis) => {
        if (controller.signal.aborted) return;
        setReview({ kind: 'barcode', analysis });
        setSheet('review');
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const lookupError = error instanceof BarcodeLookupError ? error : new BarcodeLookupError('networkError');
        const buttons = lookupError.offersScanLabel
          ? [
              { text: 'Cancel', style: 'cancel' as const },
              { text: 'Scan Label', onPress: () => openCameraOrLibrary('nutritionLabel', 'camera') },
            ]
          : [{ text: 'OK' }];
        Alert.alert('Scan Barcode', lookupError.message || barcodeLookupMessages.networkError, buttons);
      })
      .finally(() => {
        if (analysisAbort.current === controller) {
          analysisAbort.current = null;
          setPending(null);
        }
      });
  };

  const handleAddMenu = (id: string) => {
    const action = parseHomeAddMenuAction(id);
    if (!action) return;
    switch (action.kind) {
      case 'startFast':
        openSheet('fastingStart');
        return;
      case 'endFast':
        endFast();
        return;
      case 'cancelFast':
        cancelFast();
        return;
      case 'water':
        diaryStore.dispatch({ type: 'water/add', entry: { id: newId(), date: logDate().toISOString(), milliliters: action.milliliters } });
        return;
      case 'waterCustom':
        openSheet('waterCustom');
        return;
      case 'food':
        switch (action.method) {
          case 'manual':
            setDiaryTarget(null);
            openSheet('manualEntry');
            return;
          case 'camera':
            openCameraOrLibrary('photo', 'camera');
            return;
          case 'photos':
            openCameraOrLibrary('photo', 'library');
            return;
          case 'text':
            openSheet('describeMeal');
            return;
          case 'voice':
            openSheet('voiceMeal');
            return;
          case 'barcode':
            openSheet('barcodeScan');
            return;
          case 'favorites':
            setSavedMealsMode('favorites');
            openSheet('savedMeals');
            return;
          case 'frequent':
            setSavedMealsMode('frequent');
            openSheet('savedMeals');
            return;
          case 'recent':
            setSavedMealsMode('recent');
            openSheet('savedMeals');
            return;
          case 'copy_from_day':
            openSheet('copyFromDay');
            return;
        }
    }
  };

  const copyFromDays = useMemo(() => {
    const selected = dayKey(selectedDate);
    const byDay = new Map<string, { date: Date; calories: number; count: number }>();
    for (const entry of diary.foodEntries) {
      const date = new Date(entry.timestamp);
      const key = dayKey(date);
      if (key === selected) continue;
      const current = byDay.get(key);
      if (current) {
        current.calories += entry.calories;
        current.count += 1;
      } else {
        byDay.set(key, { date, calories: entry.calories, count: 1 });
      }
    }
    return [...byDay.entries()]
      .sort((a, b) => b[0].localeCompare(a[0]))
      .map(([key, value]) => ({ dayKey: key, ...value }));
  }, [diary.foodEntries, selectedDate]);

  const copyFromDay = (from: Date) => {
    for (const entry of foodEntriesOn(diary, from)) {
      const { id: _id, timestamp: _timestamp, imageFilename: _image, additionalImageFilenames: _images, ...rest } = entry;
      const next = makeFoodEntry({ ...rest, timestamp: logDate().toISOString() }, newId());
      diaryStore.dispatch({ type: 'food/add', entry: next });
      writeFoodToHealth(next);
    }
    setSheet(null);
  };

  return (
    <Screen>
      {/* iOS: `.contentMargins(.bottom, 96)` is measured from the tab bar's safe area, so add the bar. */}
      <ScrollView contentContainerStyle={{ paddingBottom: tabBarHeight + 96, gap: theme.spacing.lg }} scrollIndicatorInsets={{ bottom: tabBarHeight }}>
        <View style={{ paddingTop: theme.spacing.sm }}>
          <WeekEnergyStrip selectedDate={selectedDate} onSelectDate={setSelectedDate} weekStartsOnMonday={prefs.weekStartsOnMonday} />
        </View>

        <View style={{ gap: theme.spacing.sm }}>
          <CalorieGauge eaten={caloriesOn(diary, selectedDate)} goal={targets.calories} />
          <Row style={{ alignItems: 'flex-start', paddingHorizontal: theme.spacing.lg, gap: 4 }}>
            {nutrients.map((id) => (
              <MacroVerticalBar
                key={id}
                label={homeNutrients[id].displayName}
                current={homeNutrients[id].total(dayFood)}
                goal={homeNutrientGoal(id, targets)}
                unit={homeNutrients[id].unit}
              />
            ))}
            {prefs.waterTrackingEnabled ? (
              <MacroVerticalBar label="Water" current={waterTotalDisplay} goal={waterGoalDisplay} unit={waterUnitSymbol(prefs.waterUnit)} />
            ) : null}
          </Row>
          <LinkButton
            title="View More  ›"
            variant="subheadline"
            style={{ alignSelf: 'center', opacity: 0.6 }}
            onPress={() => openSheet('nutritionDetail')}
          />
        </View>

        <View style={{ paddingHorizontal: theme.spacing.lg, gap: theme.spacing.lg }}>
          {groups.length === 0 ? (
            <View style={{ gap: 6 }}>
              <SectionHeader title={isToday ? "Today's Diary" : 'Diary'} />
              <Card>
                <AppText tone="secondary">No diary entries</AppText>
              </Card>
            </View>
          ) : (
            groups.map((group, index) => (
              <View key={group.id} style={{ gap: 6 }}>
                <Row style={{ justifyContent: 'space-between', paddingHorizontal: 4 }}>
                  <Row style={{ gap: 8 }}>
                    <SectionHeader title={mealTypeDisplayName(group.meal)} />
                    {index === 0 ? (
                      <NativeMenu
                        items={sortMenuItems}
                        onSelect={(id) => setPreferences({ foodLogSortOrder: id as FoodLogSortOrder })}
                        accessibilityLabel="Sort"
                        style={{ paddingLeft: 8 }}
                      >
                        <Row style={{ gap: 6 }}>
                          <Icon name="arrow.up.arrow.down" size={11} color={theme.colors.accent} />
                          <AppText variant="subheadlineSemibold" tone="accent">
                            Sort
                          </AppText>
                        </Row>
                      </NativeMenu>
                    ) : null}
                  </Row>
                  {group.foodEntries.length > 0 ? (
                    <View style={{ alignItems: 'flex-end', gap: 1 }}>
                      <AppText variant="subheadlineSemibold" tone="accent">
                        {group.totals.calories.toLocaleString()} kcal
                      </AppText>
                      <AppText variant="caption2" tone="secondary" weight="500">
                        {Math.round(group.totals.protein)}P · {Math.round(group.totals.carbs)}C · {Math.round(group.totals.fat)}F
                      </AppText>
                    </View>
                  ) : null}
                </Row>
                <Card padded={false} style={{ overflow: 'hidden' }}>
                  {group.items.map((item, i) => (
                    <View key={item.id}>
                      {i > 0 ? <Divider style={{ marginLeft: theme.spacing.lg + 68 }} /> : null}
                      <View style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 8 }}>
                        {item.kind === 'food' ? (
                          <NativeMenu
                            items={foodRowMenu(item.entry)}
                            trigger="longPress"
                            onPress={() => openFoodEdit(item.entry)}
                            onSelect={(id) => {
                              if (id === 'favorite') diaryStore.dispatch({ type: 'food/toggleFavorite', entry: item.entry });
                              if (id === 'delete') confirmDeleteFood(item.entry);
                            }}
                          >
                            <FoodRow entry={item.entry} isFavorite={isFavorite(diary, item.entry)} />
                          </NativeMenu>
                        ) : item.kind === 'water' ? (
                          <NativeMenu
                            items={waterRowMenu}
                            trigger="longPress"
                            onSelect={(id) => {
                              if (id === 'delete') confirmDeleteWater(item.entry);
                            }}
                          >
                            <WaterLogRow entry={item.entry} unit={prefs.waterUnit} />
                          </NativeMenu>
                        ) : (
                          <NativeMenu
                            items={fastingRowMenu(item.session)}
                            trigger="longPress"
                            onSelect={(id) => {
                              if (id === 'end') endFast();
                              if (id === 'cancel') cancelFast();
                              if (id === 'delete') confirmDeleteFast(item.session);
                            }}
                          >
                            <FastingRow session={item.session} now={now} />
                          </NativeMenu>
                        )}
                      </View>
                    </View>
                  ))}
                </Card>
              </View>
            ))
          )}
        </View>
      </ScrollView>

      {/* Floating "+" (60pt accent circle). iOS attaches UIMenu; Android anchors a glass dropdown. */}
      <NativeMenu
        items={addMenuItems}
        onSelect={handleAddMenu}
        accessibilityLabel="Add"
        testID="home.add"
        style={{
          position: 'absolute',
          right: theme.spacing.xl,
          bottom: tabBarHeight + theme.spacing.xl,
          width: theme.sizes.addButton,
          height: theme.sizes.addButton,
        }}
      >
        <View
          style={{
            width: theme.sizes.addButton,
            height: theme.sizes.addButton,
            borderRadius: theme.sizes.addButton / 2,
            backgroundColor: theme.colors.accent,
            alignItems: 'center',
            justifyContent: 'center',
            shadowColor: theme.colors.accent,
            shadowOpacity: 0.3,
            shadowRadius: 8,
            shadowOffset: { width: 0, height: 4 },
            elevation: 6,
          }}
        >
          <Icon name="plus" size={30} color={theme.colors.onAccent} />
        </View>
      </NativeMenu>
      <WaterCustomSheet
        key={`water-${sheetEpoch}`}
        visible={sheet === 'waterCustom'}
        unit={prefs.waterUnit}
        onDismiss={() => setSheet(null)}
        onAdd={(milliliters) => {
          diaryStore.dispatch({ type: 'water/add', entry: { id: newId(), date: logDate().toISOString(), milliliters } });
          setSheet(null);
        }}
      />
      <FastingStartSheet
        key={`fasting-${sheetEpoch}`}
        visible={sheet === 'fastingStart'}
        defaultGoalMinutes={prefs.fastingDefaultGoalMinutes}
        onDismiss={() => setSheet(null)}
        onStart={(goalMinutes) => {
          diaryStore.dispatch({ type: 'fasting/start', session: { id: newId(), startedAt: new Date().toISOString(), goalMinutes } });
          setSheet(null);
        }}
      />
      <ManualEntrySheet
        key={`manual-${sheetEpoch}`}
        visible={sheet === 'manualEntry'}
        logDate={logDate()}
        presentation="popover"
        onDismiss={() => setSheet(null)}
        onSave={(input) => {
          const entry = makeFoodEntry(input, newId());
          diaryStore.dispatch({ type: 'food/add', entry });
          writeFoodToHealth(entry);
          setSheet(null);
        }}
      />
      <ManualEntrySheet
        key={`edit-${sheetEpoch}`}
        visible={sheet === 'editFood' && diaryTarget?.kind === 'food'}
        logDate={logDate()}
        initial={diaryTarget?.kind === 'food' ? diaryTarget.entry : undefined}
        onDismiss={() => {
          setDiaryTarget(null);
          setSheet(null);
        }}
        onSave={(input) => {
          if (diaryTarget?.kind !== 'food') return;
          const previous = diaryTarget.entry;
          const { mealType: _meal, ...kept } = previous;
          const entry = makeFoodEntry(
            {
              ...kept,
              ...input,
              timestamp: previous.timestamp,
              source: previous.source,
              ...(previous.imageFilename ? { imageFilename: previous.imageFilename } : {}),
              ...(previous.additionalImageFilenames ? { additionalImageFilenames: previous.additionalImageFilenames } : {}),
            },
            previous.id,
          );
          diaryStore.dispatch({ type: 'food/update', entry });
          replaceFoodInHealth(entry);
          setDiaryTarget(null);
          setSheet(null);
        }}
      />
      <TextFoodInputSheet
        key={`text-${sheetEpoch}`}
        visible={sheet === 'describeMeal'}
        presentation="popover"
        onDismiss={() => setSheet(null)}
        onSubmit={(description) => {
          setSheet(null);
          void runAnalysis('text', { text: description });
        }}
      />
      <VoiceMealSheet
        key={`voice-${sheetEpoch}`}
        visible={sheet === 'voiceMeal'}
        presentation="popover"
        onDismiss={() => setSheet(null)}
        onTranscribed={(text) => {
          setSheet(null);
          void runAnalysis('voice', { text });
        }}
      />
      <BarcodeScannerSheet
        visible={sheet === 'barcodeScan'}
        onDismiss={() => setSheet(null)}
        onScan={(code) => lookupScannedBarcode(code)}
      />
      <SavedMealsSheet
        visible={sheet === 'savedMeals'}
        mode={savedMealsMode}
        favorites={favoriteEntries(diary)}
        recents={recentEntries(diary)}
        frequent={frequentEntries(diary)}
        onDismiss={() => setSheet(null)}
        onRelog={relogEntry}
      />
      <CopyFromDaySheet
        visible={sheet === 'copyFromDay'}
        days={copyFromDays}
        onDismiss={() => setSheet(null)}
        onCopy={copyFromDay}
      />
      <AnalyzingOverlay
        visible={pending !== null}
        imageUri={pending?.imageUri}
        message={pending?.kind === 'text' || pending?.kind === 'voice' ? 'Looking up nutrition...' : 'Analyzing your food...'}
        onCancel={cancelAnalysis}
      />
      <FoodResultSheet
        visible={sheet === 'review' && review !== null}
        analysis={review?.analysis}
        imageUri={review?.imageUri}
        onDismiss={() => {
          setReview(null);
          setSheet(null);
        }}
        onSave={saveReview}
      />
      <NutritionDetailSheet
        visible={sheet === 'nutritionDetail'}
        date={selectedDate}
        calories={caloriesOn(diary, selectedDate)}
        calorieGoal={targets.calories}
        protein={homeNutrients.protein.total(dayFood)}
        proteinGoal={targets.protein}
        carbs={homeNutrients.carbs.total(dayFood)}
        carbsGoal={targets.carbs}
        fat={homeNutrients.fat.total(dayFood)}
        fatGoal={targets.fat}
        waterEnabled={prefs.waterTrackingEnabled}
        waterMilliliters={waterTotalOn(diary, selectedDate)}
        waterGoalMilliliters={prefs.waterDailyGoalMl}
        waterUnit={prefs.waterUnit}
        detailRows={nutritionDetailRows}
        onDismiss={() => setSheet(null)}
      />
    </Screen>
  );
}

function SectionHeader({ title }: { title: string }) {
  return (
    <AppText variant="footnote" tone="secondary" style={{ textTransform: 'uppercase', letterSpacing: 0.3 }}>
      {title}
    </AppText>
  );
}

