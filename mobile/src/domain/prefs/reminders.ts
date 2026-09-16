/**
 * Custom meal-reminder times. Keys match `NotificationSettingsView` / `NotificationManager.swift`.
 */

export const reminderIds = ['breakfast', 'lunch', 'dinner'] as const;
export type ReminderMeal = (typeof reminderIds)[number];

export interface ReminderTime {
  id: ReminderMeal;
  title: string;
  body: string;
  enabled: boolean;
  hour: number;
  minute: number;
}

export const defaultReminderTimes: Record<ReminderMeal, { hour: number; minute: number; enabled: boolean; title: string; body: string }> = {
  breakfast: { hour: 8, minute: 0, enabled: true, title: 'Breakfast Time', body: "Don't forget to log your breakfast!" },
  lunch: { hour: 12, minute: 0, enabled: true, title: 'Lunch Time', body: 'Snap a photo to keep tracking!' },
  dinner: { hour: 19, minute: 0, enabled: true, title: 'Dinner Time', body: 'Log your dinner to stay on track!' },
};

export const reminderPreferenceKeys = {
  breakfastEnabled: 'breakfastReminderEnabled',
  breakfastHour: 'breakfastReminderHour',
  breakfastMinute: 'breakfastReminderMinute',
  lunchEnabled: 'lunchReminderEnabled',
  lunchHour: 'lunchReminderHour',
  lunchMinute: 'lunchReminderMinute',
  dinnerEnabled: 'dinnerReminderEnabled',
  dinnerHour: 'dinnerReminderHour',
  dinnerMinute: 'dinnerReminderMinute',
} as const;

export function clampHour(value: number | undefined, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(23, Math.max(0, Math.trunc(value!)));
}

export function clampMinute(value: number | undefined, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(59, Math.max(0, Math.trunc(value!)));
}

export function formatReminderTime(hour: number, minute: number, locale?: string): string {
  const date = new Date();
  date.setHours(hour, minute, 0, 0);
  return date.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' });
}

export interface ReminderPreferenceSlice {
  breakfastReminderEnabled: boolean;
  breakfastReminderHour: number;
  breakfastReminderMinute: number;
  lunchReminderEnabled: boolean;
  lunchReminderHour: number;
  lunchReminderMinute: number;
  dinnerReminderEnabled: boolean;
  dinnerReminderHour: number;
  dinnerReminderMinute: number;
}

export function reminderTimesFromPrefs(prefs: ReminderPreferenceSlice): ReminderTime[] {
  return reminderIds.map((id) => {
    const defaults = defaultReminderTimes[id];
    return {
      id,
      title: defaults.title,
      body: defaults.body,
      enabled: prefs[`${id}ReminderEnabled`],
      hour: clampHour(prefs[`${id}ReminderHour`], defaults.hour),
      minute: clampMinute(prefs[`${id}ReminderMinute`], defaults.minute),
    };
  });
}

export function scheduledReminders(prefs: ReminderPreferenceSlice): ReminderTime[] {
  return reminderTimesFromPrefs(prefs).filter((reminder) => reminder.enabled);
}
