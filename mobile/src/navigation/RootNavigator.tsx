import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { DarkTheme, DefaultTheme, NavigationContainer, type Theme as NavigationTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useMemo } from 'react';
import { Platform, StyleSheet, View } from 'react-native';

import { GlassChrome } from '../../modules/glass-chrome';
import { Icon, type SFSymbolName } from '../components/Icon';
import { CoachScreen } from '../screens/coach/CoachScreen';
import { HomeScreen } from '../screens/home/HomeScreen';
import { ProgressScreen } from '../screens/progress/ProgressScreen';
import { WorkoutsScreen } from '../screens/workouts/WorkoutsScreen';
import { AddMenuSettingsScreen } from '../screens/settings/AddMenuSettingsScreen';
import { AppUpdatesScreen, CommunityScreen, HelpFeedbackScreen, LegalScreen, SupportScreen } from '../screens/settings/AboutScreens';
import { AIAccessScreen } from '../screens/settings/AIAccessScreen';
import { AIProvidersScreen } from '../screens/settings/AIProvidersScreen';
import { AppSettingsScreen } from '../screens/settings/AppSettingsScreen';
import { GoalsNutritionScreen } from '../screens/settings/GoalsNutritionScreen';
import { HealthDataScreen } from '../screens/settings/HealthDataScreen';
import { NotificationsScreen } from '../screens/settings/NotificationsScreen';
import { PersonalInfoScreen } from '../screens/settings/PersonalInfoScreen';
import { SettingsScreen } from '../screens/settings/SettingsScreen';
import { SpeechToTextScreen } from '../screens/settings/SpeechToTextScreen';
import { TrackingRemindersScreen } from '../screens/settings/TrackingRemindersScreen';
import { WorkoutSettingsScreen } from '../screens/settings/WorkoutSettingsScreen';
import { useTheme } from '../theme';
import type { RootTabParamList, SettingsStackParamList } from './types';

const Tab = createBottomTabNavigator<RootTabParamList>();
const SettingsStack = createNativeStackNavigator<SettingsStackParamList>();

const tabIcons: Record<keyof RootTabParamList, SFSymbolName> = {
  Home: 'house.fill',
  Progress: 'chart.bar.fill',
  Coach: 'bubble.left.and.bubble.right.fill',
  Settings: 'gearshape.fill',
  Workouts: 'dumbbell',
};

function SettingsStackScreen() {
  const theme = useTheme();
  return (
    <SettingsStack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: theme.colors.appBackground },
        headerShadowVisible: false,
        headerTintColor: theme.colors.accent,
        headerTitleStyle: { color: theme.colors.label, fontWeight: '600' },
        headerBackButtonDisplayMode: 'minimal',
        contentStyle: { backgroundColor: theme.colors.appBackground },
      }}
    >
      <SettingsStack.Screen name="SettingsHome" component={SettingsScreen} options={{ title: 'Settings', headerLargeTitle: true }} />
      <SettingsStack.Screen name="PersonalInfo" component={PersonalInfoScreen} options={{ title: 'Personal Info' }} />
      <SettingsStack.Screen name="GoalsNutrition" component={GoalsNutritionScreen} options={{ title: 'Goals & Nutrition' }} />
      <SettingsStack.Screen name="AIAccess" component={AIAccessScreen} options={{ title: 'AI Access' }} />
      <SettingsStack.Screen name="AIProviders" component={AIProvidersScreen} options={{ title: 'AI Providers & Fallbacks' }} />
      <SettingsStack.Screen name="TrackingReminders" component={TrackingRemindersScreen} options={{ title: 'Tracking & Reminders' }} />
      <SettingsStack.Screen name="Notifications" component={NotificationsScreen} options={{ title: 'Notifications' }} />
      <SettingsStack.Screen name="AppSettings" component={AppSettingsScreen} options={{ title: 'App Settings' }} />
      <SettingsStack.Screen name="AddMenu" component={AddMenuSettingsScreen} options={{ title: '+ Menu' }} />
      <SettingsStack.Screen name="Workout" component={WorkoutSettingsScreen} options={{ title: 'Workout' }} />
      <SettingsStack.Screen name="HealthData" component={HealthDataScreen} options={{ title: 'Health & Data' }} />
      <SettingsStack.Screen name="SpeechToText" component={SpeechToTextScreen} options={{ title: 'Speech-to-Text' }} />
      <SettingsStack.Screen name="AppUpdates" component={AppUpdatesScreen} options={{ title: 'App & Updates' }} />
      <SettingsStack.Screen name="Support" component={SupportScreen} options={{ title: 'Support Fud AI' }} />
      <SettingsStack.Screen name="HelpFeedback" component={HelpFeedbackScreen} options={{ title: 'Help & Feedback' }} />
      <SettingsStack.Screen name="Community" component={CommunityScreen} options={{ title: 'Community' }} />
      <SettingsStack.Screen name="Legal" component={LegalScreen} options={{ title: 'Legal' }} />
    </SettingsStack.Navigator>
  );
}

/**
 * Tab bar background. iOS gets native glass (the one platform-specific material in the app);
 * Android gets the same bar with a solid card color. Layout, icons and labels are identical.
 */
function TabBarBackground() {
  const theme = useTheme();
  if (Platform.OS === 'ios') {
    return <GlassChrome interactive style={StyleSheet.absoluteFill} fallbackColor={theme.colors.appCard} />;
  }
  return <View style={[StyleSheet.absoluteFill, { backgroundColor: theme.colors.appCard }]} />;
}

/** Five tabs in the iOS order: Home, Progress, Coach, Settings, Workouts. */
export function RootNavigator() {
  const theme = useTheme();

  const navigationTheme = useMemo<NavigationTheme>(() => {
    const base = theme.scheme === 'dark' ? DarkTheme : DefaultTheme;
    return {
      ...base,
      colors: {
        ...base.colors,
        primary: theme.colors.accent,
        background: theme.colors.appBackground,
        card: theme.colors.appCard,
        text: theme.colors.label,
        border: theme.colors.separator,
      },
    };
  }, [theme]);

  return (
    <NavigationContainer theme={navigationTheme}>
      <Tab.Navigator
        screenOptions={({ route }) => ({
          headerShown: false,
          tabBarActiveTintColor: theme.colors.accent,
          tabBarInactiveTintColor: theme.colors.secondaryLabel,
          tabBarBackground: TabBarBackground,
          tabBarStyle: {
            position: 'absolute',
            borderTopWidth: StyleSheet.hairlineWidth,
            borderTopColor: theme.colors.separator,
            backgroundColor: 'transparent',
            elevation: 0,
          },
          tabBarLabelStyle: { ...theme.text.caption2, fontWeight: '500' },
          tabBarIcon: ({ color, size }) => <Icon name={tabIcons[route.name]} size={size} color={color} />,
        })}
      >
        <Tab.Screen name="Home" component={HomeScreen} />
        <Tab.Screen name="Progress" component={ProgressScreen} />
        <Tab.Screen name="Coach" component={CoachScreen} />
        <Tab.Screen name="Settings" component={SettingsStackScreen} />
        <Tab.Screen name="Workouts" component={WorkoutsScreen} />
      </Tab.Navigator>
    </NavigationContainer>
  );
}
