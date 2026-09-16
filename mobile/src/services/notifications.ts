/**
 * Meal reminders. Mirrors `NotificationManager.swift` (`requestAuthorization`,
 * `scheduleMealReminders`): repeating daily local notifications. Local-only.
 */

import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

import { scheduledReminders, type ReminderPreferenceSlice, type ReminderTime } from '../domain/prefs/reminders';

export interface MealReminder {
  id: 'meal.breakfast' | 'meal.lunch' | 'meal.dinner';
  title: string;
  body: string;
  hour: number;
  minute: number;
}

export const defaultMealReminders: readonly MealReminder[] = [
  { id: 'meal.breakfast', title: 'Breakfast Time', body: "Don't forget to log your breakfast!", hour: 8, minute: 0 },
  { id: 'meal.lunch', title: 'Lunch Time', body: 'Snap a photo to keep tracking!', hour: 12, minute: 0 },
  { id: 'meal.dinner', title: 'Dinner Time', body: 'Log your dinner to stay on track!', hour: 19, minute: 0 },
];

const MEAL_CHANNEL = 'meal-reminders';

function notificationId(reminder: Pick<ReminderTime, 'id'>): MealReminder['id'] {
  return `meal.${reminder.id}`;
}

/** Android 13+ only shows the permission prompt once the app owns a notification channel. */
async function ensureMealChannel(): Promise<void> {
  if (Platform.OS !== 'android') return;
  await Notifications.setNotificationChannelAsync(MEAL_CHANNEL, { name: 'Meal reminders', importance: Notifications.AndroidImportance.DEFAULT });
}

export async function requestNotificationAuthorization(): Promise<boolean> {
  try {
    await ensureMealChannel();
    const current = await Notifications.getPermissionsAsync();
    if (current.granted) return true;
    if (!current.canAskAgain) return false;
    const requested = await Notifications.requestPermissionsAsync();
    return requested.granted;
  } catch (error) {
    console.warn('[fudai] notification permission request failed', error);
    return false;
  }
}

export function mealRemindersFromPrefs(prefs: ReminderPreferenceSlice): MealReminder[] {
  return scheduledReminders(prefs).map((reminder) => ({
    id: notificationId(reminder),
    title: reminder.title,
    body: reminder.body,
    hour: reminder.hour,
    minute: reminder.minute,
  }));
}

/**
 * All-or-nothing: either every reminder is scheduled or none is. A failure part-way cancels
 * what was already created before rejecting, so `notificationsEnabled` and the OS schedule
 * never disagree.
 */
export async function scheduleMealReminders(reminders: readonly MealReminder[] = defaultMealReminders): Promise<void> {
  await ensureMealChannel();
  await cancelMealReminders();
  try {
    for (const reminder of reminders) {
      await Notifications.scheduleNotificationAsync({
        identifier: reminder.id,
        content: { title: reminder.title, body: reminder.body, sound: 'default' },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.DAILY,
          hour: reminder.hour,
          minute: reminder.minute,
          ...(Platform.OS === 'android' ? { channelId: MEAL_CHANNEL } : {}),
        },
      });
    }
  } catch (error) {
    await cancelMealReminders();
    throw error;
  }
}

export async function cancelMealReminders(): Promise<void> {
  await Promise.all(defaultMealReminders.map((r) => Notifications.cancelScheduledNotificationAsync(r.id).catch(() => undefined)));
}
