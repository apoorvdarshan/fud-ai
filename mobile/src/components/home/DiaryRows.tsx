import React from 'react';
import { Image, Pressable, View } from 'react-native';

import { fastDurationSeconds, formatFastDuration, formatFastGoal, isFastActive, type FastingSession } from '../../domain/fasting/fasting';
import type { FoodEntry } from '../../domain/food/food';
import { formatWater, type WaterEntry, type WaterUnit } from '../../domain/water/water';
import { foodImageURI } from '../../services/foodImageStore';
import { useTheme } from '../../theme';
import { Icon } from '../Icon';
import { AppText, Row } from '../primitives';
import { formatMacroValue } from './MacroVerticalBar';

function timeString(date: Date): string {
  return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/** 56pt thumbnail slot with the translucent material look used by every diary row. */
function Thumb({ children }: { children: React.ReactNode }) {
  const theme = useTheme();
  return (
    <View
      style={{
        width: 56,
        height: 56,
        borderRadius: 12,
        backgroundColor: theme.colors.fill,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      {children}
    </View>
  );
}

function MacroPill({ label, value }: { label: string; value: number }) {
  const theme = useTheme();
  return (
    <View style={{ paddingHorizontal: 6, paddingVertical: 2, borderRadius: theme.radii.pill, backgroundColor: theme.accentAlpha(0.08) }}>
      <AppText variant="caption2" tone="secondary" weight="500">
        {label} {formatMacroValue(value)}g
      </AppText>
    </View>
  );
}

function servingText(entry: FoodEntry): string | undefined {
  const grams = entry.servingSizeGrams;
  if (grams === undefined) {
    const quantity = entry.selectedServingQuantity ?? 1;
    return `${formatMacroValue(quantity)} Serving`;
  }
  const formatted = Number.isInteger(grams) ? `${grams}` : grams.toFixed(1);
  const unit = entry.selectedServingUnit;
  const quantity = entry.selectedServingQuantity;
  if (unit && quantity && quantity > 0 && !['g', 'gram', 'grams'].includes(unit.trim().toLowerCase())) {
    return `${formatMacroValue(quantity)} ${unit} (~${formatted}g)`;
  }
  return `${formatted}g`;
}

interface FoodRowProps {
  entry: FoodEntry;
  isFavorite: boolean;
  onPress?: () => void;
  onLongPress?: () => void;
}

export function FoodRow({ entry, isFavorite, onPress, onLongPress }: FoodRowProps) {
  const theme = useTheme();
  const serving = servingText(entry);
  const body = (
      <Row style={{ gap: 12, paddingVertical: 4, alignItems: 'center' }}>
        {entry.imageFilename ? (
          // `FoodEntryThumbnailView`: the meal photo takes the thumbnail slot when there is one.
          <Image source={{ uri: foodImageURI(entry.imageFilename) }} resizeMode="cover" style={{ width: 56, height: 56, borderRadius: 12, backgroundColor: theme.colors.fill }} accessibilityIgnoresInvertColors />
        ) : (
          <Thumb>
            {entry.emoji ? (
              <AppText style={{ fontSize: 28, lineHeight: 34 }}>{entry.emoji}</AppText>
            ) : (
              <Icon name="fork.knife" size={22} color={theme.colors.accent} />
            )}
          </Thumb>
        )}
        <View style={{ flex: 1, gap: 3 }}>
          <Row style={{ justifyContent: 'space-between', gap: 8 }}>
            <Row style={{ gap: 4, flexShrink: 1 }}>
              <AppText variant="body" weight="500" style={{ flexShrink: 1 }}>
                {entry.name}
              </AppText>
              {isFavorite ? <Icon name="heart.fill" size={11} color={theme.colors.accent} /> : null}
            </Row>
            <AppText variant="caption" tone="tertiary">
              {timeString(new Date(entry.timestamp))}
            </AppText>
          </Row>
          <Row style={{ gap: 6 }}>
            <AppText variant="subheadlineSemibold" tone="accent">
              {entry.calories.toLocaleString()} kcal
            </AppText>
            {serving ? (
              <>
                <AppText tone="tertiary">·</AppText>
                <AppText variant="caption" tone="secondary">
                  {serving}
                </AppText>
              </>
            ) : null}
          </Row>
          <Row style={{ gap: 8 }}>
            <MacroPill label="P" value={entry.protein} />
            <MacroPill label="C" value={entry.carbs} />
            <MacroPill label="F" value={entry.fat} />
          </Row>
        </View>
      </Row>
    );
  if (!onPress && !onLongPress) return body;
  return (
    <Pressable onPress={onPress} onLongPress={onLongPress} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}>
      {body}
    </Pressable>
  );
}

export function WaterLogRow({ entry, unit }: { entry: WaterEntry; unit: WaterUnit }) {
  return (
    <Row style={{ gap: 12, paddingVertical: 4 }} accessibilityLabel={`Water, ${formatWater(unit, entry.milliliters)}, logged ${timeString(new Date(entry.date))}`}>
      <Thumb>
        <AppText style={{ fontSize: 28, lineHeight: 34 }}>💧</AppText>
      </Thumb>
      <View style={{ flex: 1, gap: 4 }}>
        <AppText variant="bodySemibold">Water</AppText>
        <AppText variant="caption" tone="secondary">
          {timeString(new Date(entry.date))}
        </AppText>
      </View>
      <AppText variant="bodySemibold" tone="accent">
        {formatWater(unit, entry.milliliters)}
      </AppText>
    </Row>
  );
}

interface FastingRowProps {
  session: FastingSession;
  now: Date;
  onPress?: () => void;
}

export function FastingRow({ session, now, onPress }: FastingRowProps) {
  const theme = useTheme();
  const active = isFastActive(session);
  const elapsed = fastDurationSeconds(session, now);
  const goalSeconds = session.goalMinutes * 60;
  const progress = goalSeconds > 0 ? Math.min(elapsed / goalSeconds, 1) : 0;
  const body = (
      <Row style={{ gap: 12, paddingVertical: 4 }}>
        <Thumb>
          <Icon name="timer" size={24} color={theme.colors.accent} />
        </Thumb>
        <View style={{ flex: 1, gap: 4 }}>
          <Row style={{ justifyContent: 'space-between' }}>
            <AppText variant="bodySemibold">{active ? 'Fasting' : 'Fast'}</AppText>
            <AppText variant="caption" tone="tertiary">
              {active ? `Goal ${formatFastGoal(session.goalMinutes)}` : timeString(new Date(session.endedAt ?? session.startedAt))}
            </AppText>
          </Row>
          {active ? (
            <View style={{ height: 6, borderRadius: 3, backgroundColor: theme.accentAlpha(0.12), overflow: 'hidden' }}>
              <View style={{ width: `${Math.max(4, progress * 100)}%`, height: '100%', backgroundColor: theme.colors.accent }} />
            </View>
          ) : null}
          <AppText variant="caption" tone="secondary">
            {active
              ? `${formatFastDuration(elapsed)} elapsed · started ${timeString(new Date(session.startedAt))}`
              : `${formatFastDuration(elapsed)} · goal ${formatFastGoal(session.goalMinutes)}`}
          </AppText>
        </View>
        <AppText variant="bodySemibold" tone="accent">
          {formatFastDuration(elapsed)}
        </AppText>
      </Row>
    );
  if (!onPress) return body;
  return (
    <Pressable onPress={onPress} style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}>
      {body}
    </Pressable>
  );
}
