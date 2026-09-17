import { describe, expect, it } from 'vitest';

import {
  assignAddMenuMethod,
  buildHomeAddMenu,
  hiddenAddMenuMethods,
  iosDefaultAddMenuConfig,
  parseAddMenuConfig,
  parseAddMenuConfigJson,
  parseHomeAddMenuAction,
  sanitizeAddMenuConfig,
  setAddMenuGroupCount,
  visibleAddMenuMethods,
} from '../src/domain/prefs/addMenu';

describe('AddMenuConfig', () => {
  it('matches the iOS default groups and drops Siri from Expo', () => {
    const methods = visibleAddMenuMethods(iosDefaultAddMenuConfig);
    expect(methods).toEqual(['copy_from_day', 'favorites', 'frequent', 'recent', 'manual', 'voice', 'text', 'barcode', 'photos', 'camera']);
    expect(methods).not.toContain('siri_phrases');
  });

  it('sanitizes unknown methods, empty groups, and a max of three groups', () => {
    const sanitized = sanitizeAddMenuConfig({
      version: 1,
      groups: [
        { id: 'a', name: '  Photos  ', methods: ['camera', 'camera', 'not-a-method' as never, 'siri_phrases'] },
        { id: 'b', name: '', methods: [] },
        { id: 'c', name: 'Text', methods: ['text'] },
        { id: 'd', name: 'Extra', methods: ['voice'] },
      ],
      flatMethods: ['manual'],
    });
    expect(sanitized.groups).toHaveLength(2);
    expect(sanitized.groups[0]).toMatchObject({ name: 'Photos', methods: ['camera'] });
    expect(sanitized.groups[1]).toMatchObject({ name: 'Text', methods: ['text'] });
    expect(sanitized.flatMethods).toEqual([]);
  });

  it('falls back to the iOS default when a configured menu sanitizes empty', () => {
    expect(parseAddMenuConfig({ version: 1, groups: [{ name: 'Empty', methods: ['siri_phrases'] }], flatMethods: [] })).toEqual(
      iosDefaultAddMenuConfig,
    );
  });

  it('parses native JSON and ignores corrupt blobs', () => {
    const raw = JSON.stringify({
      version: 1,
      groups: [{ id: 'g1', name: 'Photo & Scan', methods: ['camera', 'photos'] }],
      flatMethods: [],
    });
    expect(parseAddMenuConfigJson(raw).groups[0]?.methods).toEqual(['camera', 'photos']);
    expect(parseAddMenuConfigJson('not-json')).toEqual(iosDefaultAddMenuConfig);
  });
});

describe('buildHomeAddMenu', () => {
  it('orders fasting, water, then food groups like ContentView', () => {
    const items = buildHomeAddMenu({
      fastingTrackingEnabled: true,
      waterTrackingEnabled: true,
      hasActiveFast: false,
      waterUnit: 'ml',
    });
    expect(items.map((item) => item.id)).toEqual(['startFast', 'water', 'group:reuse-meal', 'group:describe-meal', 'group:photo-scan']);
    expect(items[1]?.children?.map((child) => child.id)).toEqual(['waterCustom', 'water:750', 'water:500', 'water:250']);
  });

  it('hides food methods while a fast is active and nests end/cancel', () => {
    const items = buildHomeAddMenu({
      fastingTrackingEnabled: true,
      waterTrackingEnabled: false,
      hasActiveFast: true,
      waterUnit: 'ml',
    });
    expect(items).toHaveLength(1);
    expect(items[0]?.children?.map((child) => child.id)).toEqual(['endFast', 'cancelFast']);
  });

  it('flattens to a method list when groups are set to zero', () => {
    const flat = setAddMenuGroupCount(iosDefaultAddMenuConfig, 0);
    expect(flat.groups).toEqual([]);
    expect(flat.flatMethods).toEqual(visibleAddMenuMethods(iosDefaultAddMenuConfig));
    expect(hiddenAddMenuMethods(flat)).toEqual([]);
  });

  it('keeps empty groups while raising the group count so Settings can assign methods', () => {
    const one = sanitizeAddMenuConfig({
      version: 1,
      groups: [{ id: 'photo-scan', name: 'Photo & Scan', methods: ['camera'] }],
      flatMethods: [],
    });
    const two = setAddMenuGroupCount(one, 2);
    expect(two.groups).toHaveLength(2);
    expect(two.groups[1]).toMatchObject({ name: 'New Group', methods: [] });
  });

  it('assigns a hidden method into a group', () => {
    const trimmed = sanitizeAddMenuConfig({
      version: 1,
      groups: [{ id: 'photo-scan', name: 'Photo & Scan', methods: ['camera'] }],
      flatMethods: [],
    });
    const next = assignAddMenuMethod(trimmed, 'photos', 'photo-scan');
    expect(next.groups[0]?.methods).toEqual(['camera', 'photos']);
    expect(hiddenAddMenuMethods(next)).toContain('voice');
  });

  it('parses action ids used by the Home + menu', () => {
    expect(parseHomeAddMenuAction('food:camera')).toEqual({ kind: 'food', method: 'camera' });
    expect(parseHomeAddMenuAction('water:250')).toEqual({ kind: 'water', milliliters: 250 });
    expect(parseHomeAddMenuAction('waterCustom')).toEqual({ kind: 'waterCustom' });
    expect(parseHomeAddMenuAction('group:reuse-meal')).toBeUndefined();
  });
});
