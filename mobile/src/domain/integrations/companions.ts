/**
 * Seams for Apple Health / Health Connect (`HealthKitManager.swift`), the watchOS app
 * (`WatchSnapshotSync.swift`) and home / lock-screen widgets (`WidgetSnapshotWriter.swift`).
 */

import type { MobilePlatform } from '../ai/providers';

export type CompanionId = 'health' | 'watch' | 'widgets' | 'siri';

export type CompanionAvailability = 'nativeOnly' | 'available' | 'connected' | 'denied';

export interface CompanionStatus {
  id: CompanionId;
  title: string;
  availability: CompanionAvailability;
  /** One-line explanation shown under the row. */
  detail: string;
}

export function healthServiceName(platform: MobilePlatform): string {
  return platform === 'ios' ? 'Apple Health' : 'Health Connect';
}

export interface CompanionConnection {
  health?: CompanionAvailability;
  widgets?: CompanionAvailability;
  watch?: CompanionAvailability;
  siri?: CompanionAvailability;
}

/** What Settings → Health & Data lists for this platform and build. */
export function companionStatuses(platform: MobilePlatform, connected: CompanionConnection = {}): CompanionStatus[] {
  const health = healthServiceName(platform);
  const healthAvailability = connected.health ?? 'available';
  const widgetAvailability = connected.widgets ?? 'available';
  const statuses: CompanionStatus[] = [
    {
      id: 'health',
      title: health,
      availability: healthAvailability,
      detail: healthDetail(healthAvailability, health),
    },
    {
      id: 'widgets',
      title: platform === 'ios' ? 'Home & Lock Screen Widgets' : 'Home Screen Widgets',
      availability: widgetAvailability,
      detail: widgetDetail(platform, widgetAvailability),
    },
  ];
  if (platform === 'ios') {
    const watchAvailability = connected.watch ?? 'available';
    statuses.push(
      {
        id: 'watch',
        title: 'Apple Watch',
        availability: watchAvailability,
        detail: watchDetail(watchAvailability),
      },
      {
        id: 'siri',
        title: 'Siri & Shortcuts',
        availability: connected.siri ?? 'nativeOnly',
        detail: 'App Intents for logging food and water by voice ship with the native iOS app. The shared Expo build cannot register those intents.',
      },
    );
  }
  return statuses;
}

function healthDetail(availability: CompanionAvailability, health: string): string {
  switch (availability) {
    case 'connected':
      return `Nutrition, weight and body measurements sync with ${health}.`;
    case 'denied':
      return `${health} permission was denied. Enable it in system settings to sync.`;
    case 'available':
      return `Turn on the switch to request ${health} permission and sync nutrition, weight and measurements.`;
    case 'nativeOnly':
      return `${health} is not available on this device.`;
  }
}

function widgetDetail(platform: MobilePlatform, availability: CompanionAvailability): string {
  if (availability === 'connected' || availability === 'available') {
    return platform === 'ios'
      ? 'Today’s totals are written to the App Group the native widget and watch targets already read. Adding or changing widget UI still requires those native extension targets.'
      : 'Today’s totals are persisted as a snapshot. Android home-screen widgets still require the native widget target.';
  }
  return 'Widget extensions read a snapshot the app writes; they ship with the native builds.';
}

function watchDetail(availability: CompanionAvailability): string {
  if (availability === 'connected' || availability === 'available') {
    return 'The same App Group snapshot the native watchOS app already reads is updated whenever Home, water or fasting changes. The watch UI itself remains the native watch target.';
  }
  return 'The watchOS companion pairs with the native iOS app. No shared-app watch target exists.';
}

export function companionAvailabilityLabel(availability: CompanionAvailability): string {
  switch (availability) {
    case 'nativeOnly':
      return 'Native app only';
    case 'available':
      return 'Available';
    case 'connected':
      return 'Connected';
    case 'denied':
      return 'Denied';
  }
}

// MARK: - Health sync seam (`HealthKitManager`)

export interface HealthSample {
  /** ISO-8601. */
  date: string;
  value: number;
}

export interface HealthNutritionTotals {
  calories: number;
  protein: number;
  carbs: number;
  fat: number;
  name?: string;
  entryId?: string;
}

export type HealthAuthorizationStatus = 'unavailable' | 'notDetermined' | 'denied' | 'authorized';

export interface HealthSync {
  readonly isAvailable: boolean;
  authorizationStatus(): Promise<HealthAuthorizationStatus>;
  requestAuthorization(): Promise<boolean>;
  writeWeight(kg: number, date: Date, entryId?: string): Promise<void>;
  writeBodyFat(fraction: number, date: Date, entryId?: string): Promise<void>;
  writeNutrition(day: Date, totals: HealthNutritionTotals): Promise<void>;
  deleteNutrition(entryId: string): Promise<void>;
  deleteWeight(entryId: string): Promise<void>;
  deleteBodyFat(entryId: string): Promise<void>;
  readSteps(day: Date): Promise<number | undefined>;
}

export class HealthUnavailableError extends Error {
  constructor(platform: MobilePlatform) {
    super(`${healthServiceName(platform)} sync is not available in this build.`);
    this.name = 'HealthUnavailableError';
  }
}

/** Default until a Health bridge module exists: authorization is refused, writes are no-ops. */
export function unavailableHealthSync(platform: MobilePlatform): HealthSync {
  return {
    isAvailable: false,
    async authorizationStatus() {
      return 'unavailable';
    },
    async requestAuthorization() {
      return false;
    },
    async writeWeight() {
      throw new HealthUnavailableError(platform);
    },
    async writeBodyFat() {
      throw new HealthUnavailableError(platform);
    },
    async writeNutrition() {
      throw new HealthUnavailableError(platform);
    },
    async deleteNutrition() {
      throw new HealthUnavailableError(platform);
    },
    async deleteWeight() {
      throw new HealthUnavailableError(platform);
    },
    async deleteBodyFat() {
      throw new HealthUnavailableError(platform);
    },
    async readSteps() {
      return undefined;
    },
  };
}

// MARK: - Watch / widget snapshot seam (`WidgetSnapshot`, `WatchSnapshotSync`)

/** The subset of Home state the native widgets and watch app render. */
export interface CompanionSnapshot {
  /** `yyyy-MM-dd` local day. */
  day: string;
  caloriesEaten: number;
  calorieGoal: number;
  protein: number;
  carbs: number;
  fat: number;
  waterMilliliters: number;
  waterGoalMilliliters: number;
  /** ISO-8601 when a fast is running. */
  fastStartedAt?: string;
  fastGoalMinutes?: number;
  accentId: string;
}

export interface WidgetNutrientValue {
  id: string;
  label: string;
  shortLabel: string;
  unit: string;
  iconName: string;
  value: number;
  goal: number;
}

/** Codable shape written to the App Group (`widget_snapshot_v1.json`). Dates are Apple reference intervals. */
export interface WidgetSnapshotPayload {
  date: number;
  dayStart: number;
  calories: number;
  calorieGoal: number;
  protein: number;
  proteinGoal: number;
  carbs: number;
  carbsGoal: number;
  fat: number;
  fatGoal: number;
  homeNutrients: WidgetNutrientValue[];
  waterTrackingEnabled: boolean;
  waterCurrentMl: number;
  waterGoalMl: number;
  waterUnitRaw: string;
  themeStartHex: number;
  themeEndHex: number;
}

export interface CompanionSnapshotWriter {
  readonly isAvailable: boolean;
  write(snapshot: WidgetSnapshotPayload): Promise<void>;
  clear(): Promise<void>;
}

export const noopSnapshotWriter: CompanionSnapshotWriter = {
  isAvailable: false,
  async write() {},
  async clear() {},
};
