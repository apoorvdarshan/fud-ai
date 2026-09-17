/**
 * Configurable Home + food menu. Mirrors `AddMenuConfig.swift` / `AddMenuConfig.kt`.
 * Native stores this under UserDefaults / DataStore key `addMenu.config`.
 */

import { formatWater, type WaterUnit } from '../water/water';
import {
  addMenuFoodLogMethods,
  foodLogMethodSystemImage,
  foodLogMethodTitle,
  isFoodLogMethod,
  type FoodLogMethod,
} from '../food/foodLogMethod';

export const ADD_MENU_CONFIG_STORAGE_KEY = 'addMenu.config';

export interface AddMenuGroupConfig {
  id: string;
  name: string;
  methods: FoodLogMethod[];
}

export interface AddMenuConfig {
  version: number;
  groups: AddMenuGroupConfig[];
  flatMethods: FoodLogMethod[];
}

export const ADD_MENU_CURRENT_VERSION = 1;

/** Pre-customization iOS Home + food menu (`AddMenuConfig.iOSDefault`). */
export const iosDefaultAddMenuConfig: AddMenuConfig = {
  version: ADD_MENU_CURRENT_VERSION,
  groups: [
    {
      id: 'reuse-meal',
      name: 'Reuse Meal',
      methods: ['copy_from_day', 'favorites', 'frequent', 'recent'],
    },
    {
      id: 'describe-meal',
      name: 'Describe Meal',
      methods: ['manual', 'voice', 'text'],
    },
    {
      id: 'photo-scan',
      name: 'Photo & Scan',
      methods: ['barcode', 'photos', 'camera'],
    },
  ],
  flatMethods: [],
};

export interface NativeMenuItem {
  id: string;
  title: string;
  systemImage?: string;
  destructive?: boolean;
  disabled?: boolean;
  /** iOS `UIMenu.Options.displayInline` — Android still nests these as a page. */
  displayInline?: boolean;
  children?: NativeMenuItem[];
}

export type HomeAddMenuAction =
  | { kind: 'startFast' }
  | { kind: 'endFast' }
  | { kind: 'cancelFast' }
  | { kind: 'water'; milliliters: number }
  | { kind: 'waterCustom' }
  | { kind: 'food'; method: Exclude<FoodLogMethod, 'siri_phrases'> };

const allowedMethods = new Set<FoodLogMethod>(addMenuFoodLogMethods);

function newGroupId(): string {
  return `group-${Math.random().toString(36).slice(2, 10)}`;
}

function filterMethods(methods: readonly string[], seen: Set<FoodLogMethod>): FoodLogMethod[] {
  const result: FoodLogMethod[] = [];
  for (const raw of methods) {
    if (!isFoodLogMethod(raw) || !allowedMethods.has(raw) || seen.has(raw)) continue;
    seen.add(raw);
    result.push(raw);
  }
  return result;
}

export function sanitizeAddMenuConfig(config: AddMenuConfig, options: { keepEmptyGroups?: boolean } = {}): AddMenuConfig {
  const seen = new Set<FoodLogMethod>();
  const trimmedGroups: AddMenuGroupConfig[] = config.groups.slice(0, 3).flatMap((group) => {
    const methods = filterMethods(group.methods, seen);
    if (methods.length === 0 && !options.keepEmptyGroups) return [];
    const name = group.name.trim() || 'Group';
    return [{ id: group.id.trim() || newGroupId(), name, methods }];
  });

  if (trimmedGroups.length === 0) {
    return {
      version: ADD_MENU_CURRENT_VERSION,
      groups: [],
      flatMethods: filterMethods(config.flatMethods, seen),
    };
  }

  return { version: ADD_MENU_CURRENT_VERSION, groups: trimmedGroups, flatMethods: [] };
}

export function parseAddMenuConfig(raw: unknown, options: { keepEmptyGroups?: boolean } = {}): AddMenuConfig {
  if (!raw || typeof raw !== 'object') return iosDefaultAddMenuConfig;
  const record = raw as Record<string, unknown>;
  const groupsRaw = Array.isArray(record.groups) ? record.groups : [];
  const groups: AddMenuGroupConfig[] = groupsRaw.flatMap((group) => {
    if (!group || typeof group !== 'object') return [];
    const g = group as Record<string, unknown>;
    if (typeof g.name !== 'string') return [];
    const methods = Array.isArray(g.methods) ? g.methods.filter((m): m is FoodLogMethod => typeof m === 'string' && isFoodLogMethod(m)) : [];
    return [{ id: typeof g.id === 'string' ? g.id : newGroupId(), name: g.name, methods }];
  });
  const flatRaw = Array.isArray(record.flatMethods)
    ? record.flatMethods.filter((m): m is string => typeof m === 'string')
    : [];
  const decoded: AddMenuConfig = {
    version: typeof record.version === 'number' ? record.version : ADD_MENU_CURRENT_VERSION,
    groups,
    flatMethods: filterMethods(flatRaw, new Set()),
  };
  const sanitized = sanitizeAddMenuConfig(decoded, options);
  if (sanitized.groups.length === 0 && sanitized.flatMethods.length === 0) {
    const hadConfiguredContent = decoded.groups.some((g) => g.methods.length > 0) || decoded.flatMethods.length > 0;
    return hadConfiguredContent ? iosDefaultAddMenuConfig : sanitized;
  }
  return sanitized;
}

export function parseAddMenuConfigJson(raw: string | null | undefined, options: { keepEmptyGroups?: boolean } = {}): AddMenuConfig {
  if (!raw) return iosDefaultAddMenuConfig;
  try {
    return parseAddMenuConfig(JSON.parse(raw) as unknown, options);
  } catch {
    return iosDefaultAddMenuConfig;
  }
}

export function usesFlatAddMenu(config: AddMenuConfig): boolean {
  return config.groups.length === 0;
}

export function visibleAddMenuMethods(config: AddMenuConfig): FoodLogMethod[] {
  return usesFlatAddMenu(config) ? config.flatMethods : config.groups.flatMap((g) => g.methods);
}

function foodItem(method: FoodLogMethod): NativeMenuItem | undefined {
  if (method === 'siri_phrases') return undefined;
  return {
    id: `food:${method}`,
    title: foodLogMethodTitle(method),
    systemImage: foodLogMethodSystemImage(method),
  };
}

function foodMenuItems(config: AddMenuConfig): NativeMenuItem[] {
  if (usesFlatAddMenu(config)) {
    return config.flatMethods.flatMap((method) => {
      const item = foodItem(method);
      return item ? [item] : [];
    });
  }
  return config.groups.flatMap((group) => {
    const children = group.methods.flatMap((method) => {
      const item = foodItem(method);
      return item ? [item] : [];
    });
    if (children.length === 0) return [];
    return [
      {
        id: `group:${group.id}`,
        title: group.name,
        systemImage: foodLogMethodSystemImage(group.methods[0] ?? 'camera'),
        children,
      },
    ];
  });
}

function waterMenuItems(unit: WaterUnit): NativeMenuItem[] {
  const glasses = (n: number, ml: number): NativeMenuItem => ({
    id: `water:${ml}`,
    title: `${n} ${n === 1 ? 'Glass' : 'Glasses'} (~${formatWater(unit, ml)})`,
    systemImage: 'drop.fill',
  });
  return [
    { id: 'waterCustom', title: 'Custom', systemImage: 'slider.horizontal.3' },
    glasses(3, 750),
    glasses(2, 500),
    glasses(1, 250),
  ];
}

/**
 * Home + menu tree matching `ContentView.configuredFoodAddMenuContent` plus fasting/water
 * sections. Same order on both platforms (iOS Menu layout).
 */
export function buildHomeAddMenu(input: {
  config?: AddMenuConfig;
  fastingTrackingEnabled: boolean;
  waterTrackingEnabled: boolean;
  hasActiveFast: boolean;
  waterUnit: WaterUnit;
}): NativeMenuItem[] {
  const config = input.config ?? iosDefaultAddMenuConfig;
  const items: NativeMenuItem[] = [];

  if (input.fastingTrackingEnabled) {
    if (input.hasActiveFast) {
      items.push({
        id: 'fasting',
        title: 'Fasting',
        systemImage: 'timer',
        children: [
          { id: 'endFast', title: 'End Fast', systemImage: 'stop.fill' },
          { id: 'cancelFast', title: 'Cancel Fast', systemImage: 'trash', destructive: true },
        ],
      });
    } else {
      items.push({ id: 'startFast', title: 'Start Fast', systemImage: 'timer' });
    }
  }

  if (input.waterTrackingEnabled) {
    items.push({
      id: 'water',
      title: 'Water',
      systemImage: 'drop.fill',
      children: waterMenuItems(input.waterUnit),
    });
  }

  if (!input.hasActiveFast) {
    items.push(...foodMenuItems(config));
  }

  return items;
}

export function addMenuConfigFromPrefs(raw: string | undefined, options: { keepEmptyGroups?: boolean } = {}): AddMenuConfig {
  return parseAddMenuConfigJson(raw, options);
}

export function serializeAddMenuConfig(config: AddMenuConfig, options: { keepEmptyGroups?: boolean } = {}): string {
  return JSON.stringify(sanitizeAddMenuConfig(config, options));
}

export function hiddenAddMenuMethods(config: AddMenuConfig): FoodLogMethod[] {
  const visible = new Set(visibleAddMenuMethods(config));
  return addMenuFoodLogMethods.filter((method) => !visible.has(method));
}

export function renameAddMenuGroup(config: AddMenuConfig, groupId: string, name: string): AddMenuConfig {
  return sanitizeAddMenuConfig({
    ...config,
    groups: config.groups.map((group) => (group.id === groupId ? { ...group, name } : group)),
  });
}

export function moveAddMenuGroup(config: AddMenuConfig, fromIndex: number, toIndex: number): AddMenuConfig {
  if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= config.groups.length || toIndex >= config.groups.length) {
    return config;
  }
  const groups = [...config.groups];
  const [moved] = groups.splice(fromIndex, 1);
  if (!moved) return config;
  groups.splice(toIndex, 0, moved);
  return sanitizeAddMenuConfig({ ...config, groups });
}

export function moveAddMenuMethod(config: AddMenuConfig, list: 'flat' | string, fromIndex: number, toIndex: number): AddMenuConfig {
  const move = <T,>(items: T[]): T[] => {
    if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= items.length || toIndex >= items.length) return items;
    const next = [...items];
    const [moved] = next.splice(fromIndex, 1);
    if (!moved) return items;
    next.splice(toIndex, 0, moved);
    return next;
  };
  if (list === 'flat') {
    return sanitizeAddMenuConfig({ ...config, flatMethods: move(config.flatMethods) });
  }
  return sanitizeAddMenuConfig({
    ...config,
    groups: config.groups.map((group) => (group.id === list ? { ...group, methods: move(group.methods) } : group)),
  });
}

export function removeAddMenuMethod(config: AddMenuConfig, list: 'flat' | string, method: FoodLogMethod): AddMenuConfig {
  if (list === 'flat') {
    return sanitizeAddMenuConfig({ ...config, flatMethods: config.flatMethods.filter((item) => item !== method) });
  }
  return sanitizeAddMenuConfig({
    ...config,
    groups: config.groups.map((group) => (group.id === list ? { ...group, methods: group.methods.filter((item) => item !== method) } : group)),
  });
}

export function assignAddMenuMethod(config: AddMenuConfig, method: FoodLogMethod, groupId: string): AddMenuConfig {
  const without = {
    ...config,
    flatMethods: config.flatMethods.filter((item) => item !== method),
    groups: config.groups.map((group) => ({ ...group, methods: group.methods.filter((item) => item !== method) })),
  };
  return sanitizeAddMenuConfig({
    ...without,
    groups: without.groups.map((group) => (group.id === groupId ? { ...group, methods: [...group.methods, method] } : group)),
  });
}

export function appendFlatAddMenuMethod(config: AddMenuConfig, method: FoodLogMethod): AddMenuConfig {
  if (config.flatMethods.includes(method)) return config;
  return sanitizeAddMenuConfig({ ...config, flatMethods: [...config.flatMethods, method] });
}

/** `AddMenuSettingsView.updateGroupCount` — 0 groups become a flat menu. */
export function setAddMenuGroupCount(config: AddMenuConfig, count: number): AddMenuConfig {
  const nextCount = Math.max(0, Math.min(3, Math.round(count)));
  if (nextCount === 0) {
    const visible = config.groups.length === 0 ? config.flatMethods : visibleAddMenuMethods(config);
    return sanitizeAddMenuConfig({ version: ADD_MENU_CURRENT_VERSION, groups: [], flatMethods: visible });
  }

  let groups = [...config.groups];
  if (groups.length === 0) {
    const flat = config.flatMethods;
    groups = flat.length === 0
      ? iosDefaultAddMenuConfig.groups.map((group) => ({ ...group, methods: [...group.methods] }))
      : [{ id: newGroupId(), name: 'New Group', methods: [...flat] }];
  }
  while (groups.length < nextCount) {
    groups.push({ id: newGroupId(), name: 'New Group', methods: [] });
  }
  while (groups.length > nextCount) {
    groups.pop();
  }
  return sanitizeAddMenuConfig({ version: ADD_MENU_CURRENT_VERSION, groups, flatMethods: [] }, { keepEmptyGroups: true });
}

export function parseHomeAddMenuAction(id: string): HomeAddMenuAction | undefined {
  if (id === 'startFast') return { kind: 'startFast' };
  if (id === 'endFast') return { kind: 'endFast' };
  if (id === 'cancelFast') return { kind: 'cancelFast' };
  if (id === 'waterCustom') return { kind: 'waterCustom' };
  if (id.startsWith('water:')) {
    const milliliters = Number.parseInt(id.slice('water:'.length), 10);
    if (Number.isFinite(milliliters) && milliliters > 0) return { kind: 'water', milliliters };
    return undefined;
  }
  if (id.startsWith('food:')) {
    const method = id.slice('food:'.length);
    if (isFoodLogMethod(method) && method !== 'siri_phrases') return { kind: 'food', method };
  }
  return undefined;
}
