/**
 * Settings → Speech-to-Text. Cloud Whisper (OpenAI / Groq) actually transcribes recorded
 * audio. On-device Whisper Base stays listed but is not selectable — CoreML binaries are
 * not bundled in the shared app.
 */

import { ScrollView } from 'react-native';

import { AppText } from '../../components/primitives';
import { SettingsRow, SettingsSection } from '../../components/SettingsRow';
import { speechProviderFromRawValue, speechProviders, type SpeechProviderId } from '../../domain/ai/speech';
import { setPreferences, usePreferences } from '../../state/appStores';
import { useTheme } from '../../theme';

export function SpeechToTextScreen() {
  const theme = useTheme();
  const selected = speechProviderFromRawValue(usePreferences((p) => p.selectedSpeechProvider));

  return (
    <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl, backgroundColor: theme.colors.appBackground }}>
      <SettingsSection
        header="Transcription"
        footer="Describe Meal records on this device, then sends the clip to the selected provider. Audio leaves the device. This is not the keyboard microphone."
      >
        {(Object.keys(speechProviders) as SpeechProviderId[]).map((id) => {
          const provider = speechProviders[id];
          const isSelected = selected.id === provider.id;
          return (
            <SettingsRow
              key={id}
              icon={provider.selectable ? 'waveform' : 'info.circle'}
              title={provider.displayName}
              subtitle={provider.subtitle}
              value={provider.selectable ? (isSelected ? 'Selected' : undefined) : 'Unsupported'}
              chevron={false}
              onPress={provider.selectable ? () => setPreferences({ selectedSpeechProvider: provider.rawValue }) : undefined}
            />
          );
        })}
      </SettingsSection>
      <AppText variant="footnote" tone="tertiary" align="center">
        Add the matching API key in Settings → AI Access. On-device Whisper Base and Apple Intelligence stay in the native iOS app.
      </AppText>
    </ScrollView>
  );
}
