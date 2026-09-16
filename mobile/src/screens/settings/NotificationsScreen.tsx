/**
 * Settings → Notifications. Master switch plus custom breakfast / lunch / dinner times,
 * matching `NotificationSettingsView` / `NotificationManager.swift`.
 */

import { useState } from 'react';
import { Alert, Linking, ScrollView, Switch } from 'react-native';

import { BottomSheet } from '../../components/BottomSheet';
import { AppText, PrimaryButton } from '../../components/primitives';
import { SettingsRow, SettingsSection, SettingsToggleRow } from '../../components/SettingsRow';
import { StepperField } from '../../components/StepperField';
import { formatReminderTime, reminderTimesFromPrefs, type ReminderMeal, type ReminderTime } from '../../domain/prefs/reminders';
import { cancelMealReminders, mealRemindersFromPrefs, requestNotificationAuthorization, scheduleMealReminders } from '../../services/notifications';
import { setPreferences, usePreferences } from '../../state/appStores';
import { useTheme } from '../../theme';

export function NotificationsScreen() {
  const theme = useTheme();
  const prefs = usePreferences((p) => p);
  const reminders = reminderTimesFromPrefs(prefs);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<ReminderTime | null>(null);
  const [hour, setHour] = useState('8');
  const [minute, setMinute] = useState('0');

  const reschedule = async (nextPrefs = { ...prefs, ...{} }) => {
    await scheduleMealReminders(mealRemindersFromPrefs(nextPrefs));
  };

  const toggle = async (value: boolean) => {
    if (busy) return;
    setBusy(true);
    try {
      if (value) {
        const granted = await requestNotificationAuthorization();
        if (!granted) {
          Alert.alert('Notifications are off', 'Allow notifications for Fud AI in system settings to get meal reminders.', [
            { text: 'Not now', style: 'cancel' },
            { text: 'Open Settings', onPress: () => void Linking.openSettings() },
          ]);
          setPreferences({ notificationsEnabled: false });
          return;
        }
        await reschedule({ ...prefs, notificationsEnabled: true });
        setPreferences({ notificationsEnabled: true });
      } else {
        await cancelMealReminders();
        setPreferences({ notificationsEnabled: false });
      }
    } catch (error) {
      setPreferences({ notificationsEnabled: false });
      Alert.alert('Reminders', error instanceof Error ? error.message : 'Could not update meal reminders.');
    } finally {
      setBusy(false);
    }
  };

  const toggleMeal = async (id: ReminderMeal, enabled: boolean) => {
    const key = `${id}ReminderEnabled` as const;
    const next = { ...prefs, [key]: enabled };
    if (prefs.notificationsEnabled) {
      try {
        await reschedule(next);
        setPreferences({ [key]: enabled });
      } catch (error) {
        Alert.alert('Reminders', error instanceof Error ? error.message : 'Could not update that reminder.');
      }
      return;
    }
    setPreferences({ [key]: enabled });
  };

  const openTime = (reminder: ReminderTime) => {
    setHour(String(reminder.hour));
    setMinute(String(reminder.minute).padStart(1, '0'));
    setEditing(reminder);
  };

  const saveTime = async () => {
    if (!editing) return;
    const nextHour = Math.min(23, Math.max(0, Number.parseInt(hour, 10) || 0));
    const nextMinute = Math.min(59, Math.max(0, Number.parseInt(minute, 10) || 0));
    const hourKey = `${editing.id}ReminderHour` as const;
    const minuteKey = `${editing.id}ReminderMinute` as const;
    const next = { ...prefs, [hourKey]: nextHour, [minuteKey]: nextMinute };
    if (prefs.notificationsEnabled) {
      try {
        await reschedule(next);
        setPreferences({ [hourKey]: nextHour, [minuteKey]: nextMinute });
        setEditing(null);
      } catch (error) {
        Alert.alert('Reminders', error instanceof Error ? error.message : 'Could not update that time.');
      }
      return;
    }
    setPreferences({ [hourKey]: nextHour, [minuteKey]: nextMinute });
    setEditing(null);
  };

  return (
    <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl, backgroundColor: theme.colors.appBackground }}>
      <SettingsSection header="Meal Reminders" footer="Gentle local reminders at meal times. Nothing is sent to a server.">
        <SettingsToggleRow icon="bell" title="Meal Reminders" value={prefs.notificationsEnabled} onValueChange={(v) => void toggle(v)} />
      </SettingsSection>
      {prefs.notificationsEnabled ? (
        <SettingsSection header="Schedule" footer="Tap a meal to change its time. Turn a row off to skip that reminder.">
          {reminders.map((reminder) => (
            <SettingsRow
              key={reminder.id}
              icon={reminder.id === 'breakfast' ? 'sunrise.fill' : reminder.id === 'lunch' ? 'sun.max.fill' : 'moon.fill'}
              title={reminder.title}
              value={reminder.enabled ? formatReminderTime(reminder.hour, reminder.minute) : 'Off'}
              onPress={() => openTime(reminder)}
              trailing={
                <Switch
                  value={reminder.enabled}
                  onValueChange={(v) => void toggleMeal(reminder.id, v)}
                  trackColor={{ true: theme.colors.accent, false: theme.colors.fill }}
                  thumbColor="#FFFFFF"
                />
              }
            />
          ))}
        </SettingsSection>
      ) : null}

      <BottomSheet visible={editing !== null} title={editing?.title ?? 'Reminder'} onDismiss={() => setEditing(null)}>
        <AppText variant="footnote" tone="secondary">
          {editing?.body}
        </AppText>
        <StepperField value={hour} onChange={setHour} step={1} unit="hour" min={0} max={23} integerOnly fractionDigits={0} accessibilityLabel="Hour" />
        <StepperField value={minute} onChange={setMinute} step={5} unit="min" min={0} max={59} integerOnly fractionDigits={0} accessibilityLabel="Minute" />
        <PrimaryButton title="Save" onPress={() => void saveTime()} />
      </BottomSheet>
    </ScrollView>
  );
}
