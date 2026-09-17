/**
 * AI food-logging surfaces for Home: the "Describe Meal" input (`TextFoodInputView.swift`),
 * the analyzing overlay with a cancel (`AnalyzingView.swift`, #357), the review sheet
 * (`FoodResultView.swift`, reduced to name / calories / macros / serving / meal / note) and
 * Saved Meals (`RecentsView.swift`: favorites + recents to re-log).
 */

import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Image, Modal, Pressable, TextInput, View } from 'react-native';

import { BottomSheet } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { AppText, Card, Divider, LinkButton, PrimaryButton, Row } from '../../components/primitives';
import { scaledAnalysis } from '../../domain/ai/foodAnalysis';
import type { FoodAnalysis } from '../../domain/food/analysis';
import { mealTypeDisplayName, mealTypes, type FoodEntry, type MealType } from '../../domain/food/food';
import { useTheme } from '../../theme';

// MARK: - Describe meal

const textPlaceholders = [
  '2 eggs, toast with butter and a coffee',
  'Chipotle burrito bowl with chicken and rice',
  "Domino's pepperoni pizza, 2 slices",
  'Greek yogurt with granola and blueberries',
];

interface TextFoodInputSheetProps {
  visible: boolean;
  /** Voice entry reuses this sheet with the keyboard's dictation key. */
  voice?: boolean;
  onDismiss: () => void;
  onSubmit: (description: string) => void;
  presentation?: 'sheet' | 'popover';
}

export function TextFoodInputSheet({ visible, voice = false, onDismiss, onSubmit, presentation = 'sheet' }: TextFoodInputSheetProps) {
  const theme = useTheme();
  const [description, setDescription] = useState('');
  const [placeholderIndex, setPlaceholderIndex] = useState(0);

  useEffect(() => {
    if (!visible || description.length > 0) return;
    const timer = setInterval(() => setPlaceholderIndex((i) => (i + 1) % textPlaceholders.length), 2000);
    return () => clearInterval(timer);
  }, [visible, description.length]);

  const trimmed = description.trim();
  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title={voice ? 'Voice' : 'Describe Meal'} presentation={presentation}>
      {voice ? (
        <Row style={{ gap: 8 }}>
          <Icon name="mic" size={16} color={theme.colors.accent} />
          <AppText variant="footnote" tone="secondary" style={{ flex: 1 }}>
            Tap the microphone on your keyboard to dictate, then Analyze. On-device Whisper transcription stays in the native apps for now.
          </AppText>
        </Row>
      ) : null}
      <View style={{ backgroundColor: theme.colors.fill, borderRadius: theme.radii.control, padding: 12 }}>
        <TextInput
          value={description}
          onChangeText={setDescription}
          placeholder={textPlaceholders[placeholderIndex]}
          placeholderTextColor={theme.colors.tertiaryLabel}
          multiline
          autoFocus
          autoCorrect={false}
          accessibilityLabel="Meal description"
          style={[theme.text.body, { color: theme.colors.label, minHeight: 66, maxHeight: 140, paddingHorizontal: 6, paddingVertical: 10 }]}
        />
      </View>
      <PrimaryButton
        title="Analyze"
        disabled={trimmed.length === 0}
        onPress={() => {
          onSubmit(trimmed);
          setDescription('');
        }}
      />
      <LinkButton title="Cancel" variant="body" style={{ opacity: 0.7 }} onPress={onDismiss} />
    </BottomSheet>
  );
}

// MARK: - Analyzing overlay

interface AnalyzingOverlayProps {
  visible: boolean;
  imageUri?: string;
  message?: string;
  onCancel: () => void;
}

export function AnalyzingOverlay({ visible, imageUri, message = 'Analyzing your food...', onCancel }: AnalyzingOverlayProps) {
  const theme = useTheme();
  return (
    <Modal visible={visible} animationType="fade" onRequestClose={onCancel} statusBarTranslucent>
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: 24, backgroundColor: theme.colors.appBackground, padding: theme.spacing.xl }}>
        {imageUri ? (
          <Image source={{ uri: imageUri }} resizeMode="cover" style={{ width: 250, height: 250, borderRadius: theme.radii.card }} accessibilityIgnoresInvertColors />
        ) : (
          <Icon name="text.magnifyingglass" size={64} color={theme.colors.accent} />
        )}
        <ActivityIndicator size="large" color={theme.colors.accent} />
        <AppText variant="headline" tone="accent">
          {message}
        </AppText>
        <LinkButton title="Cancel" variant="body" style={{ opacity: 0.7, marginTop: 8 }} onPress={onCancel} />
      </View>
    </Modal>
  );
}

// MARK: - Review result

export interface FoodResultSave {
  analysis: FoodAnalysis;
  mealType?: MealType;
  customNote?: string;
}

interface FoodResultSheetProps {
  visible: boolean;
  analysis: FoodAnalysis | undefined;
  imageUri?: string;
  onDismiss: () => void;
  onSave: (result: FoodResultSave) => void;
}

function formatGrams(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

export function FoodResultSheet({ visible, analysis, imageUri, onDismiss, onSave }: FoodResultSheetProps) {
  const theme = useTheme();
  const [name, setName] = useState('');
  const [grams, setGrams] = useState('');
  const [meal, setMeal] = useState<MealType | undefined>(undefined);
  const [note, setNote] = useState('');

  useEffect(() => {
    if (!analysis) return;
    setName(analysis.name);
    setGrams(analysis.servingSizeIsKnown ? formatGrams(analysis.servingSizeGrams) : '');
    setMeal(undefined);
    setNote('');
  }, [analysis]);

  const scaled = useMemo(() => {
    if (!analysis) return undefined;
    const parsed = Number.parseFloat(grams.replace(',', '.'));
    return Number.isFinite(parsed) && parsed > 0 ? scaledAnalysis(analysis, parsed) : analysis;
  }, [analysis, grams]);

  if (!analysis || !scaled) return null;
  const canSave = name.trim().length > 0;

  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title="Review">
      <View style={{ alignItems: 'center', gap: 10 }}>
        {imageUri ? (
          <Image source={{ uri: imageUri }} resizeMode="cover" style={{ width: 120, height: 120, borderRadius: theme.radii.card }} accessibilityIgnoresInvertColors />
        ) : analysis.emoji ? (
          <AppText style={{ fontSize: 56, lineHeight: 64 }}>{analysis.emoji}</AppText>
        ) : null}
        <TextInput
          value={name}
          onChangeText={setName}
          accessibilityLabel="Food name"
          style={[theme.text.title2, { color: theme.colors.label, textAlign: 'center', paddingVertical: 4 }]}
        />
        <Row style={{ alignItems: 'flex-end', gap: 4 }}>
          <AppText variant="largeTitle" tone="accent" style={{ fontSize: 44, lineHeight: 50 }}>
            {scaled.calories.toLocaleString()}
          </AppText>
          <AppText variant="callout" tone="secondary" style={{ paddingBottom: 8 }}>
            kcal
          </AppText>
        </Row>
      </View>

      <Card padded={false} style={{ overflow: 'hidden' }}>
        <Row style={{ paddingVertical: 12 }}>
          <MacroCell label="Protein" value={scaled.protein} />
          <MacroCell label="Carbs" value={scaled.carbs} />
          <MacroCell label="Fat" value={scaled.fat} />
        </Row>
      </Card>

      <Card style={{ gap: 4 }}>
        <Row style={{ gap: 12, minHeight: 44 }}>
          <AppText variant="body" style={{ width: 96 }}>
            Serving
          </AppText>
          <TextInput
            value={grams}
            onChangeText={(v) => setGrams(v.replace(/[^0-9.,]/g, ''))}
            keyboardType="decimal-pad"
            placeholder={analysis.servingSizeIsKnown ? '0' : 'Unknown'}
            editable={analysis.servingSizeIsKnown}
            placeholderTextColor={theme.colors.placeholder}
            accessibilityLabel="Serving size in grams"
            style={[theme.text.body, { flex: 1, textAlign: 'right', color: theme.colors.label }]}
          />
          <AppText variant="body" tone="secondary">
            g
          </AppText>
        </Row>
        {scaled.selectedServingUnit && scaled.selectedServingQuantity ? (
          <AppText variant="caption" tone="secondary" align="right">
            ≈ {formatGrams(scaled.selectedServingQuantity)} {scaled.selectedServingUnit}
          </AppText>
        ) : null}
        <Divider />
        <Row style={{ gap: 12, minHeight: 44 }}>
          <AppText variant="body" style={{ width: 96 }}>
            Note
          </AppText>
          <TextInput
            value={note}
            onChangeText={setNote}
            placeholder="Optional"
            placeholderTextColor={theme.colors.placeholder}
            style={[theme.text.body, { flex: 1, textAlign: 'right', color: theme.colors.label }]}
          />
        </Row>
      </Card>

      {scaled.ingredients.length > 0 ? (
        <Card padded={false} style={{ overflow: 'hidden' }}>
          {scaled.ingredients.map((ingredient, index) => (
            <View key={ingredient.id}>
              {index > 0 ? <Divider style={{ marginLeft: theme.spacing.lg }} /> : null}
              <Row style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 10, gap: 12 }}>
                <View style={{ flex: 1 }}>
                  <AppText variant="subheadline" weight="500">
                    {ingredient.name}
                  </AppText>
                  <AppText variant="caption" tone="secondary">
                    {formatGrams(ingredient.grams)} g · {Math.round(ingredient.protein)}P · {Math.round(ingredient.carbs)}C · {Math.round(ingredient.fat)}F
                  </AppText>
                </View>
                <AppText variant="subheadlineSemibold" tone="accent">
                  {ingredient.calories} kcal
                </AppText>
              </Row>
            </View>
          ))}
        </Card>
      ) : null}

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
        title="Add to Diary"
        disabled={!canSave}
        onPress={() =>
          onSave({
            analysis: { ...scaled, name: name.trim() },
            ...(meal ? { mealType: meal } : {}),
            // Always explicit so a note the user cleared overrides one the analysis carried.
            customNote: note.trim(),
          })
        }
      />
      <LinkButton title="Discard" variant="body" style={{ opacity: 0.7 }} onPress={onDismiss} />
    </BottomSheet>
  );
}

function MacroCell({ label, value }: { label: string; value: number }) {
  return (
    <View style={{ flex: 1, alignItems: 'center', gap: 2 }}>
      <AppText variant="headline">{Math.round(value)}g</AppText>
      <AppText variant="caption" tone="secondary">
        {label}
      </AppText>
    </View>
  );
}

// MARK: - Saved meals

export type SavedMealsMode = 'favorites' | 'frequent' | 'recent' | 'all';

interface SavedMealsSheetProps {
  visible: boolean;
  favorites: readonly FoodEntry[];
  recents: readonly FoodEntry[];
  frequent?: readonly FoodEntry[];
  mode?: SavedMealsMode;
  onDismiss: () => void;
  onRelog: (entry: FoodEntry) => void;
}

export function SavedMealsSheet({ visible, favorites, recents, frequent = [], mode = 'all', onDismiss, onRelog }: SavedMealsSheetProps) {
  const theme = useTheme();
  const section = (title: string, entries: readonly FoodEntry[]) =>
    entries.length === 0 ? null : (
      <View style={{ gap: 6 }}>
        <AppText variant="footnote" tone="secondary" style={{ textTransform: 'uppercase', letterSpacing: 0.3, paddingHorizontal: 4 }}>
          {title}
        </AppText>
        <Card padded={false} style={{ overflow: 'hidden' }}>
          {entries.map((entry, index) => (
            <View key={entry.id}>
              {index > 0 ? <Divider style={{ marginLeft: theme.spacing.lg }} /> : null}
              <Pressable accessibilityRole="button" onPress={() => onRelog(entry)} style={({ pressed }) => ({ backgroundColor: pressed ? theme.colors.fill : 'transparent' })}>
                <Row style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 10, gap: 12 }}>
                  <AppText style={{ fontSize: 22, lineHeight: 28 }}>{entry.emoji ?? '🍽️'}</AppText>
                  <View style={{ flex: 1 }}>
                    <AppText variant="body" weight="500" numberOfLines={1}>
                      {entry.name}
                    </AppText>
                    <AppText variant="caption" tone="secondary">
                      {Math.round(entry.protein)}P · {Math.round(entry.carbs)}C · {Math.round(entry.fat)}F
                    </AppText>
                  </View>
                  <AppText variant="subheadlineSemibold" tone="accent">
                    {entry.calories} kcal
                  </AppText>
                </Row>
              </Pressable>
            </View>
          ))}
        </Card>
      </View>
    );

  const title =
    mode === 'favorites' ? 'Favorites' : mode === 'frequent' ? 'Frequent' : mode === 'recent' ? 'Recent' : 'Saved Meals';
  const showFavorites = mode === 'all' || mode === 'favorites';
  const showFrequent = mode === 'all' || mode === 'frequent';
  const showRecents = mode === 'all' || mode === 'recent';
  const empty =
    (!showFavorites || favorites.length === 0) &&
    (!showFrequent || frequent.length === 0) &&
    (!showRecents || recents.length === 0);

  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title={title}>
      {empty ? (
        <Card>
          <AppText tone="secondary">Meals you log appear here so you can add them again in one tap. Favorite a meal from the diary to pin it.</AppText>
        </Card>
      ) : null}
      {showFavorites ? section('Favorites', favorites) : null}
      {showFrequent ? section('Frequent', frequent) : null}
      {showRecents ? section('Recents', recents) : null}
    </BottomSheet>
  );
}
