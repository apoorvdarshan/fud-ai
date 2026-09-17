import { Pressable, View } from 'react-native';

import { useTheme } from '../theme';
import { BottomSheet } from './BottomSheet';
import { Icon, type SFSymbolName } from './Icon';
import { AppText, Card, Divider, Row } from './primitives';

export interface ActionListItem {
  id: string;
  title: string;
  subtitle?: string;
  icon?: SFSymbolName;
  destructive?: boolean;
  disabled?: boolean;
  onPress: () => void;
}

interface ActionListSheetProps {
  visible: boolean;
  title?: string;
  /** Optional explanatory line under the title (confirmation-dialog style). */
  message?: string;
  actions: readonly ActionListItem[];
  onDismiss: () => void;
  /** When true, tapping an action dismisses before `onPress` (default). */
  dismissOnAction?: boolean;
}

/**
 * Legacy action list sheet. Prefer `NativeMenu` for menus / context actions (v7 UIMenu and
 * Android anchored dropdown). Keep this only if a native surface is actually a `.sheet`.
 */
/** Native Modal slide dismiss is ~300ms; wait past it before opening camera / alerts / next sheets. */
const DISMISS_THEN_ACTION_MS = 320;

export function ActionListSheet({ visible, title, message, actions, onDismiss, dismissOnAction = true }: ActionListSheetProps) {
  const theme = useTheme();
  const run = (action: ActionListItem) => {
    if (action.disabled) return;
    if (dismissOnAction) onDismiss();
    // Wait for the Modal slide to finish — one rAF is not enough for camera/picker/alerts.
    setTimeout(() => action.onPress(), dismissOnAction ? DISMISS_THEN_ACTION_MS : 0);
  };

  return (
    <BottomSheet visible={visible} title={title} onDismiss={onDismiss} surface="background" detent="auto" scrollable>
      {message ? (
        <AppText variant="subheadline" tone="secondary">
          {message}
        </AppText>
      ) : null}
      <Card padded={false} style={{ overflow: 'hidden' }}>
        {actions.map((action, index) => (
          <View key={action.id}>
            {index > 0 ? <Divider style={{ marginLeft: theme.spacing.lg + (action.icon ? 36 : 0) }} /> : null}
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: action.disabled === true }}
              disabled={action.disabled}
              onPress={() => run(action)}
              style={({ pressed }) => ({
                backgroundColor: pressed ? theme.colors.fill : 'transparent',
                opacity: action.disabled ? 0.4 : 1,
              })}
            >
              <Row style={{ gap: 12, paddingHorizontal: theme.spacing.lg, minHeight: 52, paddingVertical: 10 }}>
                {action.icon ? (
                  <View style={{ width: 24, alignItems: 'center' }}>
                    <Icon name={action.icon} size={20} color={action.destructive ? theme.colors.destructive : theme.colors.accent} />
                  </View>
                ) : null}
                <View style={{ flex: 1, gap: 2 }}>
                  <AppText variant="body" weight="500" tone={action.destructive ? 'destructive' : 'primary'}>
                    {action.title}
                  </AppText>
                  {action.subtitle ? (
                    <AppText variant="caption" tone="secondary">
                      {action.subtitle}
                    </AppText>
                  ) : null}
                </View>
              </Row>
            </Pressable>
          </View>
        ))}
      </Card>
      <Pressable accessibilityRole="button" onPress={onDismiss} style={({ pressed }) => ({ opacity: pressed ? 0.6 : 1, alignItems: 'center', paddingVertical: 8 })}>
        <AppText variant="bodySemibold" tone="accent">
          Cancel
        </AppText>
      </Pressable>
    </BottomSheet>
  );
}
