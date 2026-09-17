import { useCallback, useMemo, useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View, useWindowDimensions, type StyleProp, type ViewStyle } from 'react-native';

import type { ReactNode } from 'react';

import { GlassChrome } from '../../modules/glass-chrome';
import type { NativeMenuItem } from '../domain/prefs/addMenu';
import { useTheme } from '../theme';
import { Icon, sfSymbolToIonicon, type SFSymbolName } from './Icon';
import { AppText, Row } from './primitives';

const MENU_WIDTH = 238;
const MENU_RADIUS = 22;

interface AnchoredMenuProps {
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

interface AnchorBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

function isSymbol(name: string | undefined): name is SFSymbolName {
  return name !== undefined && name in sfSymbolToIonicon;
}

/**
 * Glass-styled dropdown anchored to a child, matching Android `SheetGlassDropdownMenu`.
 * Used on Android and as the Expo Go / unlinked-module fallback on iOS.
 */
export function AnchoredMenu({
  items,
  trigger = 'press',
  onSelect,
  onPress,
  accessibilityLabel,
  testID,
  disabled = false,
  style,
  children,
}: AnchoredMenuProps) {
  const theme = useTheme();
  const { width: windowWidth, height: windowHeight } = useWindowDimensions();
  const anchorRef = useRef<View>(null);
  const [open, setOpen] = useState(false);
  const [anchor, setAnchor] = useState<AnchorBox | null>(null);
  const [stack, setStack] = useState<NativeMenuItem[][]>([]);
  const longPressUsed = useRef(false);

  const visibleItems = stack[stack.length - 1] ?? items;

  const measureAndOpen = useCallback(() => {
    anchorRef.current?.measureInWindow((x, y, width, height) => {
      setAnchor({ x, y, width, height });
      setStack([]);
      setOpen(true);
    });
  }, []);

  const dismiss = () => {
    setOpen(false);
    setStack([]);
    // Delay so a same-gesture `onPress` after long-press still sees the flag and is ignored.
    setTimeout(() => {
      longPressUsed.current = false;
    }, 400);
  };

  const choose = (item: NativeMenuItem) => {
    if (item.disabled) return;
    if (item.children && item.children.length > 0) {
      setStack((current) => [...current, item.children ?? []]);
      return;
    }
    dismiss();
    onSelect(item.id);
  };

  const menuPosition = useMemo(() => {
    if (!anchor) return { right: 16, bottom: 96 };
    const margin = 12;
    const preferredLeft = anchor.x + anchor.width - MENU_WIDTH;
    const left = Math.min(Math.max(margin, preferredLeft), Math.max(margin, windowWidth - MENU_WIDTH - margin));
    const spaceAbove = anchor.y;
    const estimatedHeight = Math.min(visibleItems.length * 52 + (stack.length > 0 ? 52 : 0) + 16, Math.min(360, windowHeight - 24));
    if (spaceAbove > estimatedHeight + 12) {
      return { left, bottom: windowHeight - anchor.y + 8 };
    }
    return { left, top: anchor.y + anchor.height + 8 };
  }, [anchor, windowWidth, windowHeight, visibleItems.length, stack.length]);

  return (
    <View ref={anchorRef} collapsable={false} style={style}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel}
        accessibilityState={{ disabled }}
        testID={testID}
        disabled={disabled}
        onPress={() => {
          if (disabled) return;
          if (trigger === 'press') {
            measureAndOpen();
            return;
          }
          if (longPressUsed.current) {
            longPressUsed.current = false;
            return;
          }
          onPress?.();
        }}
        onLongPress={() => {
          if (disabled || trigger !== 'longPress') return;
          longPressUsed.current = true;
          measureAndOpen();
        }}
        delayLongPress={280}
      >
        {children}
      </Pressable>
      <Modal visible={open} transparent animationType="fade" onRequestClose={dismiss} statusBarTranslucent>
        <Pressable accessibilityLabel="Dismiss menu" onPress={dismiss} style={StyleSheet.absoluteFill} />
        <View
          pointerEvents="box-none"
          style={[
            StyleSheet.absoluteFill,
            { justifyContent: 'flex-end', alignItems: 'flex-end' },
          ]}
        >
          <View style={[{ position: 'absolute', width: MENU_WIDTH }, menuPosition]}>
            <GlassChrome
              interactive
              cornerRadius={MENU_RADIUS}
              fallbackColor={theme.scheme === 'dark' ? 'rgba(20,20,22,0.95)' : 'rgba(250,243,238,0.98)'}
              style={{
                borderRadius: MENU_RADIUS,
                overflow: 'hidden',
                borderWidth: 0.8,
                borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.12)' : 'rgba(255,255,255,0.7)',
                paddingVertical: 5,
                maxHeight: Math.min(360, windowHeight - 24),
              }}
            >
              <ScrollView bounces={false} keyboardShouldPersistTaps="handled">
              {stack.length > 0 ? (
                <MenuRow
                  icon="chevron.left"
                  title="Back"
                  onPress={() => setStack((current) => current.slice(0, -1))}
                />
              ) : null}
              {visibleItems.map((item) => (
                <MenuRow
                  key={item.id}
                  icon={isSymbol(item.systemImage) ? item.systemImage : undefined}
                  title={item.title}
                  destructive={item.destructive}
                  disabled={item.disabled}
                  submenu={Boolean(item.children && item.children.length > 0)}
                  onPress={() => choose(item)}
                />
              ))}
              </ScrollView>
            </GlassChrome>
          </View>
        </View>
      </Modal>
    </View>
  );
}

function MenuRow({
  icon,
  title,
  destructive,
  disabled,
  submenu,
  onPress,
}: {
  icon?: SFSymbolName;
  title: string;
  destructive?: boolean;
  disabled?: boolean;
  submenu?: boolean;
  onPress: () => void;
}) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: disabled === true }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => ({
        opacity: disabled ? 0.4 : pressed ? 0.7 : 1,
        paddingHorizontal: 7,
        paddingVertical: 1,
      })}
    >
      <Row
        style={{
          minHeight: 48,
          paddingHorizontal: 10,
          paddingVertical: 12,
          gap: 10,
          borderRadius: 14,
          backgroundColor: 'transparent',
        }}
      >
        {icon ? (
          <View style={{ width: 20, alignItems: 'center' }}>
            <Icon name={icon} size={18} color={destructive ? theme.colors.destructive : theme.colors.accent} />
          </View>
        ) : null}
        <AppText variant="body" weight="500" tone={destructive ? 'destructive' : 'primary'} style={{ flex: 1 }}>
          {title}
        </AppText>
        {submenu ? <Icon name="chevron.right" size={16} color={theme.colors.tertiaryLabel} /> : null}
      </Row>
    </Pressable>
  );
}
