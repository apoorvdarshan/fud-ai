import type { ReactNode } from 'react';
import type { StyleProp, ViewStyle } from 'react-native';

import { isNativeMenuAvailable, NativeMenuHost } from '../../modules/native-menu';
import type { NativeMenuItem } from '../domain/prefs/addMenu';
import { AnchoredMenu } from './AnchoredMenu';

export type { NativeMenuItem };

interface NativeMenuProps {
  items: readonly NativeMenuItem[];
  trigger?: 'press' | 'longPress';
  onSelect: (id: string) => void;
  onPress?: () => void;
  accessibilityLabel?: string;
  testID?: string;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  children: ReactNode;
}

/**
 * System menu chrome. Development builds attach a real menu to the child: iOS `UIButton.menu`
 * / `UIMenu`, Android `PopupMenu` anchored to the view. Expo Go uses `AnchoredMenu`. Never
 * `ActionListSheet`.
 */
export function NativeMenu({
  items,
  trigger = 'press',
  onSelect,
  onPress,
  accessibilityLabel,
  testID,
  disabled,
  style,
  children,
}: NativeMenuProps) {
  if (isNativeMenuAvailable) {
    return (
      <NativeMenuHost
        items={items}
        trigger={trigger}
        onSelect={onSelect}
        onPress={onPress}
        accessibilityLabel={accessibilityLabel}
        testID={testID}
        disabled={disabled}
        style={style}
      >
        {children}
      </NativeMenuHost>
    );
  }
  return (
    <AnchoredMenu
      items={items}
      trigger={trigger}
      onSelect={onSelect}
      onPress={onPress}
      accessibilityLabel={accessibilityLabel}
      testID={testID}
      disabled={disabled}
      style={style}
    >
      {children}
    </AnchoredMenu>
  );
}
