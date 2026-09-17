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
 * System menu chrome. iOS development builds attach a real `UIMenu` to the child
 * (`UIButton.menu`). Android and Expo Go use an anchored glass dropdown with the same tree —
 * never `ActionListSheet`.
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
