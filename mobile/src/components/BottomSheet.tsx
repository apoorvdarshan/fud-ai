import React, { useEffect, useRef } from 'react';
import {
  Animated,
  KeyboardAvoidingView,
  Modal,
  PanResponder,
  Platform,
  Pressable,
  ScrollView,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { GlassChrome } from '../../modules/glass-chrome';
import { useTheme } from '../theme';
import { AppText, Row } from './primitives';

export type BottomSheetDetent = 'auto' | 'medium' | 'large';

interface BottomSheetProps {
  visible: boolean;
  title?: string;
  onDismiss: () => void;
  children: React.ReactNode;
  /** Optional trailing action in the header (e.g. Done). */
  trailing?: React.ReactNode;
  /**
   * Sheet chrome: `card` matches elevated SwiftUI sheets; `background` matches Home forms that
   * nest `appCard` groups inside (Add menu, manual entry).
   */
  surface?: 'card' | 'background';
  /** Rough detent height — `auto` sizes to content up to 85%. */
  detent?: BottomSheetDetent;
  /** Extra style on the sheet panel (e.g. tighter padding for action lists). */
  contentStyle?: StyleProp<ViewStyle>;
  /** When false, children are not wrapped in a ScrollView (caller manages scroll). */
  scrollable?: boolean;
  /**
   * `sheet` is SwiftUI `.sheet`. `popover` is the compact Home text/voice/manual chrome
   * (`.popover` + `.presentationCompactAdaptation(.popover)`), not a full-width sheet.
   */
  presentation?: 'sheet' | 'popover';
}

const detentMaxHeight: Record<BottomSheetDetent, `${number}%` | undefined> = {
  auto: '85%',
  medium: '55%',
  large: '92%',
};

const hairline = Platform.select({ ios: 0.33, android: 0.5, default: 0.5 }) ?? 0.5;

/**
 * Card-style sheet anchored to the bottom, the same on both platforms. Stands in for SwiftUI
 * `.sheet`. Menus and context actions belong on `NativeMenu`, not this component.
 */
export function BottomSheet({
  visible,
  title,
  onDismiss,
  children,
  trailing,
  surface = 'background',
  detent = 'auto',
  contentStyle,
  scrollable = true,
  presentation = 'sheet',
}: BottomSheetProps) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const translateY = useRef(new Animated.Value(0)).current;
  const onDismissRef = useRef(onDismiss);
  onDismissRef.current = onDismiss;

  useEffect(() => {
    if (visible) translateY.setValue(0);
  }, [visible, translateY]);

  const panResponder = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_, gesture) => gesture.dy > 8 && Math.abs(gesture.dy) > Math.abs(gesture.dx),
      onPanResponderMove: (_, gesture) => {
        if (gesture.dy > 0) translateY.setValue(gesture.dy);
      },
      onPanResponderRelease: (_, gesture) => {
        if (gesture.dy > 100 || gesture.vy > 0.9) {
          // Leave translateY where the drag ended so Modal's slide-out doesn't snap back first.
          // Reset happens when `visible` becomes true again (useEffect above).
          Animated.timing(translateY, { toValue: 420, duration: 160, useNativeDriver: true }).start(() => {
            onDismissRef.current();
          });
          return;
        }
        Animated.spring(translateY, { toValue: 0, useNativeDriver: true, bounciness: 4 }).start();
      },
      onPanResponderTerminate: () => {
        Animated.spring(translateY, { toValue: 0, useNativeDriver: true, bounciness: 4 }).start();
      },
    }),
  ).current;

  const backgroundColor = surface === 'card' ? theme.colors.appCard : theme.colors.appBackground;
  const body = scrollable ? (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={[{ padding: theme.spacing.lg, gap: theme.spacing.md }, contentStyle]}>
      {children}
    </ScrollView>
  ) : (
    <View style={[{ padding: theme.spacing.lg, gap: theme.spacing.md }, contentStyle]}>{children}</View>
  );

  if (presentation === 'popover') {
    return (
      <Modal visible={visible} transparent animationType="fade" onRequestClose={onDismiss} statusBarTranslucent>
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1, justifyContent: 'flex-end', alignItems: 'flex-end' }}>
          <Pressable accessibilityLabel="Dismiss" onPress={onDismiss} style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: 'rgba(0,0,0,0.28)' }} />
          <View
            style={{
              width: 360,
              maxWidth: '92%',
              marginRight: theme.spacing.xl,
              marginBottom: Math.max(insets.bottom, theme.spacing.xl) + 84,
              maxHeight: '78%',
            }}
          >
            <GlassChrome
              interactive
              cornerRadius={theme.radii.cardLarge}
              fallbackColor={theme.colors.appCard}
              style={{
                borderRadius: theme.radii.cardLarge,
                overflow: 'hidden',
                borderWidth: hairline,
                borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.06)',
              }}
            >
              {title || trailing ? (
                <Row style={{ paddingHorizontal: theme.spacing.lg, paddingTop: 16, paddingBottom: 4, justifyContent: 'space-between' }}>
                  <AppText variant="headline">{title ?? ''}</AppText>
                  {trailing}
                </Row>
              ) : null}
              {body}
            </GlassChrome>
          </View>
        </KeyboardAvoidingView>
      </Modal>
    );
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onDismiss} statusBarTranslucent>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1, justifyContent: 'flex-end' }}>
        <Pressable accessibilityLabel="Dismiss" onPress={onDismiss} style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: 'rgba(0,0,0,0.35)' }} />
        <Animated.View
          style={{
            backgroundColor,
            borderTopLeftRadius: theme.radii.cardLarge,
            borderTopRightRadius: theme.radii.cardLarge,
            paddingBottom: Math.max(insets.bottom, theme.spacing.lg),
            maxHeight: detentMaxHeight[detent],
            transform: [{ translateY }],
            borderTopWidth: hairline,
            borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.06)',
          }}
        >
          <View {...panResponder.panHandlers} style={{ alignItems: 'center', paddingTop: 10, paddingBottom: 4 }}>
            <View style={{ width: 36, height: 5, borderRadius: 3, backgroundColor: theme.colors.tertiaryLabel }} />
          </View>
          {title || trailing ? (
            <Row style={{ paddingHorizontal: theme.spacing.lg, paddingTop: 8, paddingBottom: 4, justifyContent: 'space-between' }}>
              <AppText variant="headline">{title ?? ''}</AppText>
              {trailing}
            </Row>
          ) : null}
          {body}
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
