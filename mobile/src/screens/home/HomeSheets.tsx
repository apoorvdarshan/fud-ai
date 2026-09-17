import { useState } from 'react';
import { Pressable, TextInput, View } from 'react-native';

import { fastingSettings, formatFastGoal } from '../../domain/fasting/fasting';
import { mealTypeDisplayName, mealTypes, type FoodEntry, type MealType, type NewFoodEntryInput } from '../../domain/food/food';
import { formatWater, millilitersFromDisplayedValue, waterDisplayValue, waterUnitSymbol, type WaterUnit } from '../../domain/water/water';
import { useTheme } from '../../theme';
import { BottomSheet } from '../../components/BottomSheet';
import { Icon, type SFSymbolName } from '../../components/Icon';
import { AppText, Card, Divider, PrimaryButton, Row } from '../../components/primitives';

// MARK: - Water custom amount

interface WaterCustomSheetProps {
  visible: boolean;
  unit: WaterUnit;
  onDismiss: () => void;
  onAdd: (milliliters: number) => void;
}

export function WaterCustomSheet({ visible, unit, onDismiss, onAdd }: WaterCustomSheetProps) {
  const theme = useTheme();
  const [amount, setAmount] = useState('');
  const parsed = Number.parseFloat(amount.replace(',', '.'));
  const milliliters = Number.isFinite(parsed) && parsed > 0 ? millilitersFromDisplayedValue(unit, parsed) : undefined;
  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title="How much water?">
      <Card>
        <Row style={{ gap: 8 }}>
          <TextInput
            value={amount}
            onChangeText={(v) => setAmount(v.replace(/[^0-9.,]/g, ''))}
            keyboardType={unit === 'ml' ? 'number-pad' : 'decimal-pad'}
            placeholder="Custom amount"
            placeholderTextColor={theme.colors.placeholder}
            style={[theme.text.title2, { flex: 1, color: theme.colors.label, paddingVertical: 4 }]}
            autoFocus
          />
          <AppText variant="headline" tone="secondary">
            {waterUnitSymbol(unit)}
          </AppText>
        </Row>
      </Card>
      <PrimaryButton
        title={milliliters ? `Add ${formatWater(unit, milliliters)}` : 'Add Water'}
        disabled={!milliliters}
        onPress={() => {
          if (!milliliters) return;
          onAdd(milliliters);
          setAmount('');
        }}
      />
    </BottomSheet>
  );
}

// MARK: - Fasting start

interface FastingStartSheetProps {
  visible: boolean;
  defaultGoalMinutes: number;
  onDismiss: () => void;
  onStart: (goalMinutes: number) => void;
}

export function FastingStartSheet({ visible, defaultGoalMinutes, onDismiss, onStart }: FastingStartSheetProps) {
  const theme = useTheme();
  const [goalMinutes, setGoalMinutes] = useState(defaultGoalMinutes);
  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title="Start Fast">
      <AppText variant="subheadline" tone="secondary">
        Choose a goal. You can end the fast any time from Home.
      </AppText>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {fastingSettings.commonGoalHours.map((hours) => {
          const minutes = hours * 60;
          const selected = minutes === goalMinutes;
          return (
            <Pressable
              key={hours}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              onPress={() => setGoalMinutes(minutes)}
              style={{
                paddingHorizontal: 14,
                paddingVertical: 8,
                borderRadius: theme.radii.pill,
                backgroundColor: selected ? theme.colors.accent : theme.accentAlpha(0.12),
              }}
            >
              <AppText variant="subheadlineSemibold" tone={selected ? 'onAccent' : 'accent'}>
                {formatFastGoal(minutes)}
              </AppText>
            </Pressable>
          );
        })}
      </View>
      <PrimaryButton title={`Start ${formatFastGoal(goalMinutes)} Fast`} onPress={() => onStart(goalMinutes)} />
    </BottomSheet>
  );
}

// MARK: - Manual food entry

interface ManualEntrySheetProps {
  visible: boolean;
  logDate: Date;
  onDismiss: () => void;
  onSave: (input: NewFoodEntryInput) => void;
  /** Compact Home + popover (native `.popover`) instead of a full-width sheet. */
  presentation?: 'sheet' | 'popover';
  /** Prefill when editing an existing diary row. */
  initial?: FoodEntry;
}

export function ManualEntrySheet({ visible, logDate, onDismiss, onSave, presentation = 'sheet', initial }: ManualEntrySheetProps) {
  const theme = useTheme();
  const [name, setName] = useState(initial?.name ?? '');
  const [calories, setCalories] = useState(initial ? String(initial.calories) : '');
  const [protein, setProtein] = useState(initial && initial.protein > 0 ? String(initial.protein) : '');
  const [carbs, setCarbs] = useState(initial && initial.carbs > 0 ? String(initial.carbs) : '');
  const [fat, setFat] = useState(initial && initial.fat > 0 ? String(initial.fat) : '');
  const [meal, setMeal] = useState<MealType | undefined>(initial?.mealType);

  const number = (v: string) => {
    const n = Number.parseFloat(v.replace(',', '.'));
    return Number.isFinite(n) && n >= 0 ? n : 0;
  };
  const canSave = name.trim().length > 0 && calories.trim().length > 0;

  const reset = () => {
    setName('');
    setCalories('');
    setProtein('');
    setCarbs('');
    setFat('');
    setMeal(undefined);
  };

  const field = (label: string, value: string, onChange: (v: string) => void, unit?: string) => (
    <Row style={{ gap: 12, minHeight: 44 }}>
      <AppText variant="body" style={{ width: 88 }}>
        {label}
      </AppText>
      <TextInput
        value={value}
        onChangeText={(v) => onChange(v.replace(/[^0-9.,]/g, ''))}
        keyboardType="decimal-pad"
        placeholder="0"
        placeholderTextColor={theme.colors.placeholder}
        style={[theme.text.body, { flex: 1, textAlign: 'right', color: theme.colors.label }]}
      />
      {unit ? (
        <AppText variant="body" tone="secondary">
          {unit}
        </AppText>
      ) : null}
    </Row>
  );

  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title={initial ? 'Edit Food' : 'Manual Entry'} presentation={presentation}>
      <Card style={{ gap: 4 }}>
        <Row style={{ gap: 12, minHeight: 44 }}>
          <AppText variant="body" style={{ width: 88 }}>
            Name
          </AppText>
          <TextInput
            value={name}
            onChangeText={setName}
            placeholder="e.g. Greek yogurt"
            placeholderTextColor={theme.colors.placeholder}
            style={[theme.text.body, { flex: 1, textAlign: 'right', color: theme.colors.label }]}
          />
        </Row>
        <Divider />
        {field('Calories', calories, setCalories, 'kcal')}
        <Divider />
        {field('Protein', protein, setProtein, 'g')}
        <Divider />
        {field('Carbs', carbs, setCarbs, 'g')}
        <Divider />
        {field('Fat', fat, setFat, 'g')}
      </Card>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {mealTypes.map((m) => {
          const selected = m === meal;
          return (
            <Pressable
              key={m}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              onPress={() => setMeal(selected ? undefined : m)}
              style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: theme.radii.pill, backgroundColor: selected ? theme.colors.accent : theme.accentAlpha(0.12) }}
            >
              <AppText variant="footnoteSemibold" tone={selected ? 'onAccent' : 'accent'}>
                {mealTypeDisplayName(m)}
              </AppText>
            </Pressable>
          );
        })}
      </View>
      <PrimaryButton
        title="Save"
        disabled={!canSave}
        onPress={() => {
          onSave({
            name: name.trim(),
            calories: Math.round(number(calories)),
            protein: number(protein),
            carbs: number(carbs),
            fat: number(fat),
            source: initial?.source ?? 'manual',
            timestamp: initial?.timestamp ?? logDate.toISOString(),
            ...(meal ? { mealType: meal } : {}),
          });
          reset();
        }}
      />
    </BottomSheet>
  );
}

// MARK: - Nutrition detail (Home "View More")

interface NutritionDetailSheetProps {
  visible: boolean;
  date: Date;
  calories: number;
  calorieGoal: number;
  protein: number;
  proteinGoal: number;
  carbs: number;
  carbsGoal: number;
  fat: number;
  fatGoal: number;
  waterEnabled: boolean;
  waterMilliliters: number;
  waterGoalMilliliters: number;
  waterUnit: WaterUnit;
  /** Extra nutrient rows already formatted for display. */
  detailRows: readonly { id: string; label: string; value: string; unit: string; goal?: string }[];
  onDismiss: () => void;
}

function NutritionRow({ icon, label, value, unit, goal }: { icon: SFSymbolName; label: string; value: string; unit: string; goal?: string }) {
  const theme = useTheme();
  return (
    <Row style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 12, gap: 12 }}>
      <Icon name={icon} size={18} color={theme.colors.accent} />
      <AppText variant="body" style={{ flex: 1 }}>
        {label}
      </AppText>
      <View style={{ alignItems: 'flex-end' }}>
        <AppText variant="bodySemibold">
          {value}
          <AppText variant="caption" tone="secondary">
            {' '}
            {unit}
          </AppText>
        </AppText>
        {goal ? (
          <AppText variant="caption2" tone="secondary">
            of {goal}
          </AppText>
        ) : null}
      </View>
    </Row>
  );
}

/** Reduced `NutritionDetailView` — macros + optional nutrients for the selected day. */
export function NutritionDetailSheet({
  visible,
  date,
  calories,
  calorieGoal,
  protein,
  proteinGoal,
  carbs,
  carbsGoal,
  fat,
  fatGoal,
  waterEnabled,
  waterMilliliters,
  waterGoalMilliliters,
  waterUnit,
  detailRows,
  onDismiss,
}: NutritionDetailSheetProps) {
  const theme = useTheme();
  const dayLabel = date.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
  const waterUnitLabel = waterUnitSymbol(waterUnit);
  return (
    <BottomSheet visible={visible} title="Nutrition" onDismiss={onDismiss} detent="large" surface="background">
      <AppText variant="subheadline" tone="secondary">
        {dayLabel}
      </AppText>
      {waterEnabled ? (
        <Card padded={false} style={{ overflow: 'hidden' }}>
          <NutritionRow
            icon="drop.fill"
            label="Water"
            value={waterDisplayValue(waterUnit, waterMilliliters)}
            unit={waterUnitLabel}
            goal={`${waterDisplayValue(waterUnit, waterGoalMilliliters)} ${waterUnitLabel}`}
          />
        </Card>
      ) : null}
      <View style={{ gap: 6 }}>
        <AppText variant="footnote" tone="secondary" style={{ textTransform: 'uppercase', letterSpacing: 0.3, paddingHorizontal: 4 }}>
          Macros
        </AppText>
        <Card padded={false} style={{ overflow: 'hidden' }}>
          <NutritionRow icon="flame.fill" label="Calories" value={calories.toLocaleString()} unit="kcal" goal={`${calorieGoal.toLocaleString()} kcal`} />
          <Divider style={{ marginLeft: theme.spacing.lg + 30 }} />
          <NutritionRow icon="p.circle.fill" label="Protein" value={String(Math.round(protein))} unit="g" goal={`${proteinGoal} g`} />
          <Divider style={{ marginLeft: theme.spacing.lg + 30 }} />
          <NutritionRow icon="c.circle.fill" label="Carbs" value={String(Math.round(carbs))} unit="g" goal={`${carbsGoal} g`} />
          <Divider style={{ marginLeft: theme.spacing.lg + 30 }} />
          <NutritionRow icon="f.circle.fill" label="Fat" value={String(Math.round(fat))} unit="g" goal={`${fatGoal} g`} />
        </Card>
      </View>
      {detailRows.length > 0 ? (
        <View style={{ gap: 6 }}>
          <AppText variant="footnote" tone="secondary" style={{ textTransform: 'uppercase', letterSpacing: 0.3, paddingHorizontal: 4 }}>
            Detailed Nutrition
          </AppText>
          <Card padded={false} style={{ overflow: 'hidden' }}>
            {detailRows.map((row, index) => (
              <View key={row.id}>
                {index > 0 ? <Divider style={{ marginLeft: theme.spacing.lg + 30 }} /> : null}
                <NutritionRow icon="list.bullet.circle" label={row.label} value={row.value} unit={row.unit} goal={row.goal} />
              </View>
            ))}
          </Card>
        </View>
      ) : null}
    </BottomSheet>
  );
}

// MARK: - Copy from day (native `.sheet`)

export interface CopyFromDayOption {
  dayKey: string;
  date: Date;
  calories: number;
  count: number;
}

interface CopyFromDaySheetProps {
  visible: boolean;
  days: readonly CopyFromDayOption[];
  onDismiss: () => void;
  onCopy: (day: Date) => void;
}

export function CopyFromDaySheet({ visible, days, onDismiss, onCopy }: CopyFromDaySheetProps) {
  const theme = useTheme();
  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title="Copy from Day">
      {days.length === 0 ? (
        <Card>
          <AppText tone="secondary">No other days with food yet. Log a meal, then you can copy that day here.</AppText>
        </Card>
      ) : (
        <Card padded={false} style={{ overflow: 'hidden' }}>
          {days.map((day, index) => (
            <View key={day.dayKey}>
              {index > 0 ? <Divider style={{ marginLeft: theme.spacing.lg }} /> : null}
              <Pressable
                accessibilityRole="button"
                onPress={() => onCopy(day.date)}
                style={({ pressed }) => ({ backgroundColor: pressed ? theme.colors.fill : 'transparent' })}
              >
                <Row style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 12, gap: 12 }}>
                  <View style={{ flex: 1 }}>
                    <AppText variant="body" weight="500">
                      {day.date.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })}
                    </AppText>
                    <AppText variant="caption" tone="secondary">
                      {day.count} {day.count === 1 ? 'entry' : 'entries'}
                    </AppText>
                  </View>
                  <AppText variant="subheadlineSemibold" tone="accent">
                    {day.calories.toLocaleString()} kcal
                  </AppText>
                </Row>
              </Pressable>
            </View>
          ))}
        </Card>
      )}
    </BottomSheet>
  );
}
