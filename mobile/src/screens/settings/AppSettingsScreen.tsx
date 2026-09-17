import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useState } from 'react';
import { Pressable, ScrollView, View } from 'react-native';

import { ComingSoonSheet } from '../../components/ComingSoonSheet';
import { AppText, Card, Screen } from '../../components/primitives';
import { SettingsRow, SettingsSection, SettingsToggleRow } from '../../components/SettingsRow';
import type { AppearanceMode } from '../../domain/prefs/preferences';
import type { SettingsStackParamList } from '../../navigation/types';
import { setPreferences, usePreferences } from '../../state/appStores';
import { appThemeColor, appThemeColorIds, appThemeColors, useTheme } from '../../theme';

const appearanceModes: { value: AppearanceMode; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
];

/** Settings → App Settings: appearance, accent color, week start. */
export function AppSettingsScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const prefs = usePreferences((p) => p);
  const accentId = appThemeColor(prefs.appThemeColor);
  const [quickActions, setQuickActions] = useState(false);

  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection header="Appearance">
          <SettingsRow
            title="Theme"
            value={appearanceModes.find((m) => m.value === prefs.appearanceMode)?.label}
            onPress={() => {
              const index = appearanceModes.findIndex((m) => m.value === prefs.appearanceMode);
              setPreferences({ appearanceMode: appearanceModes[(index + 1) % appearanceModes.length]?.value ?? 'system' });
            }}
            chevron={false}
          />
          <SettingsRow title="Accent Color" value={appThemeColors[accentId].displayName} chevron={false} />
        </SettingsSection>

        <Card style={{ gap: 12 }}>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12, justifyContent: 'center' }}>
            {appThemeColorIds.map((id) => {
              const selected = id === accentId;
              return (
                <Pressable
                  key={id}
                  accessibilityRole="button"
                  accessibilityLabel={appThemeColors[id].displayName}
                  accessibilityState={{ selected }}
                  onPress={() => setPreferences({ appThemeColor: id })}
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 18,
                    backgroundColor: appThemeColors[id].start,
                    borderWidth: selected ? 3 : 0,
                    borderColor: theme.colors.label,
                  }}
                />
              );
            })}
          </View>
          <AppText variant="footnote" tone="secondary" align="center">
            The accent drives the calorie dome, bars, buttons and tab tint — on both platforms.
          </AppText>
        </Card>

        <SettingsSection header="Calendar">
          <SettingsToggleRow title="Week Starts on Monday" value={prefs.weekStartsOnMonday} onValueChange={(v) => setPreferences({ weekStartsOnMonday: v })} />
        </SettingsSection>

        <SettingsSection header="Shortcuts">
          <SettingsRow
            icon="bolt.fill"
            title="Quick Actions"
            value="Customize"
            onPress={() => setQuickActions(true)}
          />
          <SettingsRow icon="plus.circle.fill" title="+ Menu" value="Customize" onPress={() => navigation.navigate('AddMenu')} />
        </SettingsSection>

        <SettingsSection header="AI Setup" footer="Change the provider, model, key or Hosted AI plan without repeating onboarding.">
          <SettingsRow title="Redo AI Setup" onPress={() => navigation.navigate('AIAccess')} />
        </SettingsSection>
      </ScrollView>
      <ComingSoonSheet
        visible={quickActions}
        title="Quick Actions"
        icon="bolt.fill"
        message="App-icon shortcuts and Siri App Intents are native-only. Long-press the Fud AI icon on the home screen in the native app to customize them."
        onDismiss={() => setQuickActions(false)}
      />
    </Screen>
  );
}
