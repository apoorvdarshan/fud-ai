import { Linking, Platform, ScrollView, Share } from 'react-native';

import { AppText, Screen } from '../../components/primitives';
import { SettingsRow, SettingsSection } from '../../components/SettingsRow';
import { fudLinks, shareFudMessage } from '../../domain/prefs/fudLinks';
import { useTheme } from '../../theme';

const APP_VERSION = '7.1 (38)';

function open(url: string) {
  void Linking.openURL(url);
}

function storeUrl(): string {
  return Platform.OS === 'ios' ? fudLinks.appStore : fudLinks.playStore;
}

/** Settings → About → App & Updates. */
export function AppUpdatesScreen() {
  const theme = useTheme();
  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection>
          <SettingsRow icon="arrow.triangle.2.circlepath.circle.fill" title="Version" value={APP_VERSION} chevron={false} />
          <SettingsRow icon="chevron.left.forwardslash.chevron.right" title="Open Source (MIT)" onPress={() => open(fudLinks.github)} />
          <SettingsRow
            icon="checkmark.seal.fill"
            title="OpenSSF Best Practices"
            subtitle="Passing · project 14553"
            onPress={() => open(fudLinks.openSSFBestPractices)}
          />
          <SettingsRow icon="shield.checkered" title="OpenSSF Scorecard" subtitle="Security health score" onPress={() => open(fudLinks.openSSFScorecard)} />
        </SettingsSection>
        <AppText variant="footnote" tone="tertiary" align="center">
          Made by Apoorv Darshan{'\n'}with care, for everyone
        </AppText>
      </ScrollView>
    </Screen>
  );
}

/** Settings → About → Support Fud AI. */
export function SupportScreen() {
  const theme = useTheme();
  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection>
          <SettingsRow icon="star.fill" title="Rate the App" onPress={() => open(storeUrl())} />
          <SettingsRow
            icon="square.and.arrow.up.fill"
            title="Share the App"
            onPress={() => void Share.share({ message: shareFudMessage })}
          />
          <SettingsRow icon="star.circle.fill" title="Star on GitHub" onPress={() => open(fudLinks.github)} />
          <SettingsRow icon="hand.thumbsup.fill" title="Vote on Product Hunt" onPress={() => open(fudLinks.productHunt)} />
        </SettingsSection>
      </ScrollView>
    </Screen>
  );
}

/** Settings → About → Help & Feedback. */
export function HelpFeedbackScreen() {
  const theme = useTheme();
  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection>
          <SettingsRow icon="exclamationmark.bubble.fill" title="Report an Issue" onPress={() => open(fudLinks.bugReport)} />
          <SettingsRow icon="lightbulb.fill" title="Request a Feature" onPress={() => open(fudLinks.featureRequest)} />
          <SettingsRow icon="envelope.fill" title="Contact Us" onPress={() => open(fudLinks.email)} />
        </SettingsSection>
      </ScrollView>
    </Screen>
  );
}

/** Settings → About → Community. */
export function CommunityScreen() {
  const theme = useTheme();
  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection>
          <SettingsRow icon="bubble.left.and.bubble.right.fill" title="Join Discord" onPress={() => open(fudLinks.discord)} />
          <SettingsRow icon="at" title="Follow on X" onPress={() => open(fudLinks.x)} />
          <SettingsRow icon="briefcase.fill" title="Follow on LinkedIn" onPress={() => open(fudLinks.linkedIn)} />
          <SettingsRow icon="camera.fill" title="Follow on Instagram" onPress={() => open(fudLinks.instagram)} />
        </SettingsSection>
      </ScrollView>
    </Screen>
  );
}

/** Settings → About → Legal. */
export function LegalScreen() {
  const theme = useTheme();
  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }}>
        <SettingsSection>
          <SettingsRow icon="lock.shield.fill" title="Privacy Policy" onPress={() => open(fudLinks.privacy)} />
          <SettingsRow icon="doc.text.fill" title="Terms of Service" onPress={() => open(fudLinks.terms)} />
          <SettingsRow
            icon="building.2.fill"
            title="Udyam Registered"
            subtitle="UDYAM-DL-06-0225072 · Micro enterprise"
            onPress={() => open(fudLinks.udyam)}
          />
        </SettingsSection>
      </ScrollView>
    </Screen>
  );
}
