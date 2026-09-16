import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useNavigation } from '@react-navigation/native';
import { useState } from 'react';
import { ScrollView, View } from 'react-native';

import { ComingSoonSheet } from '../../components/ComingSoonSheet';
import type { SFSymbolName } from '../../components/Icon';
import { AppText, Screen } from '../../components/primitives';
import { SettingsRow, SettingsSection } from '../../components/SettingsRow';
import { useProfile } from '../../state/appStores';
import { useTheme } from '../../theme';
import type { SettingsStackParamList } from '../../navigation/types';

/** `ProfileSettingsCategory` — same order and icons as iOS Settings. */
interface Category {
  id: string;
  title: string;
  icon: SFSymbolName;
  route?: keyof SettingsStackParamList;
}

const preferenceCategories: Category[] = [
  { id: 'personalInfo', title: 'Personal Info', icon: 'person.crop.circle', route: 'PersonalInfo' },
  { id: 'goalsNutrition', title: 'Goals & Nutrition', icon: 'target', route: 'GoalsNutrition' },
  { id: 'trackingReminders', title: 'Tracking & Reminders', icon: 'timer', route: 'TrackingReminders' },
  { id: 'notifications', title: 'Notifications', icon: 'bell', route: 'Notifications' },
  { id: 'aiAccess', title: 'AI Access', icon: 'key.horizontal', route: 'AIAccess' },
  { id: 'aiProviders', title: 'AI Providers & Fallbacks', icon: 'sparkles' },
  { id: 'speechToText', title: 'Speech-to-Text', icon: 'waveform', route: 'SpeechToText' },
  { id: 'appPreferences', title: 'App Settings', icon: 'slider.horizontal.3', route: 'AppSettings' },
  { id: 'workout', title: 'Workout', icon: 'dumbbell' },
  { id: 'healthData', title: 'Health & Data', icon: 'heart', route: 'HealthData' },
  { id: 'dataManagement', title: 'Data Management', icon: 'externaldrive', route: 'HealthData' },
];

const appInfoCategories: Category[] = [
  { id: 'appUpdates', title: 'App & Updates', icon: 'arrow.triangle.2.circlepath.circle.fill' },
  { id: 'support', title: 'Support Fud AI', icon: 'heart.fill' },
  { id: 'helpFeedback', title: 'Help & Feedback', icon: 'exclamationmark.bubble.fill' },
  { id: 'community', title: 'Community', icon: 'person.3.fill' },
  { id: 'legal', title: 'Legal', icon: 'lock.shield.fill' },
];

export function SettingsScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const profile = useProfile((p) => p);
  const displayName = profile.name?.trim() || 'User';
  const initials = displayName
    .split(' ')
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
  const [comingSoon, setComingSoon] = useState<{ title: string; icon: SFSymbolName } | null>(null);

  const open = (category: Category) => () => {
    if (category.route) {
      navigation.navigate(category.route);
    } else {
      setComingSoon({ title: category.title, icon: category.icon });
    }
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, paddingBottom: 40, gap: theme.spacing.xl }}>
        <View style={{ alignItems: 'center', gap: 10, paddingTop: theme.spacing.sm }}>
          <View style={{ width: 72, height: 72, borderRadius: 36, backgroundColor: theme.accentAlpha(0.12), alignItems: 'center', justifyContent: 'center' }}>
            <AppText variant="title2" tone="accent" weight="700">
              {initials}
            </AppText>
          </View>
          <AppText variant="title3">{displayName}</AppText>
        </View>

        <SettingsSection header="Preferences">
          {preferenceCategories.map((c) => (
            <SettingsRow key={c.id} icon={c.icon} title={c.title} onPress={open(c)} testID={`settings.category.${c.id}`} />
          ))}
        </SettingsSection>

        <SettingsSection header="About">
          {appInfoCategories.map((c) => (
            <SettingsRow key={c.id} icon={c.icon} title={c.title} onPress={open(c)} />
          ))}
        </SettingsSection>

        <AppText variant="footnote" tone="tertiary" align="center">
          Fud AI 7.1 (38)
        </AppText>
      </ScrollView>

      <ComingSoonSheet
        visible={comingSoon !== null}
        title={comingSoon?.title ?? ''}
        icon={comingSoon?.icon}
        message="This section is being ported to the shared app. Open it in the native Fud AI app for the full settings."
        onDismiss={() => setComingSoon(null)}
      />
    </Screen>
  );
}
