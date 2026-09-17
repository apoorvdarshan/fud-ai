import { requireNativeModule, requireNativeViewManager } from 'expo-modules-core';
import React, { useMemo } from 'react';
import { Platform, type StyleProp, type ViewStyle } from 'react-native';

import type { NativeMenuItem } from '../../../src/domain/prefs/addMenu';

export interface NativeMenuViewProps {
  items: readonly NativeMenuItem[];
  /** `press` = UIMenu as the tap action (Home +). `longPress` = context menu; tap emits `onPress`. */
  trigger?: 'press' | 'longPress';
  onSelect: (id: string) => void;
  onPress?: () => void;
  accessibilityLabel?: string;
  testID?: string;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  children?: React.ReactNode;
}

interface NativeProps {
  itemsJson: string;
  trigger: 'press' | 'longPress';
  onSelect: (event: { nativeEvent: { id: string } }) => void;
  onPress: () => void;
  accessibilityLabel?: string;
  testID?: string;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  children?: React.ReactNode;
}

interface NativeMenuModule {
  isNativeMenuAvailable: boolean;
}

function loadNative(): { View: React.ComponentType<NativeProps>; module: NativeMenuModule } | undefined {
  if (Platform.OS !== 'ios' && Platform.OS !== 'android') return undefined;
  try {
    return {
      View: requireNativeViewManager<NativeProps>('NativeMenu'),
      module: requireNativeModule<NativeMenuModule>('NativeMenu'),
    };
  } catch {
    return undefined;
  }
}

const native = loadNative();

/** True when the local module is linked (dev / store build). Expo Go falls back to AnchoredMenu. */
export const isNativeMenuAvailable: boolean = native?.module.isNativeMenuAvailable ?? false;

export function NativeMenuHost({
  items,
  trigger = 'press',
  onSelect,
  onPress,
  accessibilityLabel,
  testID,
  disabled,
  style,
  children,
}: NativeMenuViewProps) {
  const itemsJson = useMemo(() => JSON.stringify(items), [items]);
  if (!native) return null;
  return (
    <native.View
      itemsJson={itemsJson}
      trigger={trigger}
      onSelect={(event) => onSelect(event.nativeEvent.id)}
      onPress={() => onPress?.()}
      accessibilityLabel={accessibilityLabel}
      testID={testID}
      disabled={disabled}
      style={style}
    >
      {children}
    </native.View>
  );
}
