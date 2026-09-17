import { useEffect, useMemo, useState } from 'react';
import { Platform, Pressable, ScrollView, TextInput, View } from 'react-native';

import { Icon } from '../../components/Icon';
import { PickerSheet } from '../../components/PickerSheet';
import { AppText, Row, Screen } from '../../components/primitives';
import { SettingsRow, SettingsSection, SettingsToggleRow } from '../../components/SettingsRow';
import {
  aiProviders,
  apiKeyPlaceholder,
  apiKeyShapeHint,
  defaultModel,
  defaultTextModel,
  textProviders,
  visionProviders,
  type AIProviderDefinition,
  type AIProviderId,
  type MobilePlatform,
} from '../../domain/ai/providers';
import { apiKeySecretName, customBaseURLKey, resolveTextSelection, resolveVisionSelection } from '../../domain/ai/settings';
import { setPreferences, usePreferences } from '../../state/appStores';
import { asyncKeyValueStore, secureSecretStore } from '../../state/persistence';
import { useTheme } from '../../theme';

const platform: MobilePlatform = Platform.OS === 'ios' ? 'ios' : 'android';

type PickerKind = 'vision' | 'visionModel' | 'text' | 'textModel' | 'imageFallback' | 'imageFallbackModel' | 'textFallback' | 'textFallbackModel';

function selectableVision(): AIProviderDefinition[] {
  return visionProviders(platform).filter((provider) => provider.apiFormat !== 'onDevice' && provider.apiFormat !== 'liteRTLocal');
}

function selectableText(): AIProviderDefinition[] {
  return textProviders(platform).filter((provider) => provider.apiFormat !== 'onDevice' && provider.apiFormat !== 'liteRTLocal');
}

function optionsFor(providers: AIProviderDefinition[]) {
  return providers.map((provider) => ({ value: provider.id, label: provider.displayName }));
}

function modelOptions(provider: AIProviderDefinition, text: boolean) {
  const models = text ? provider.textModels : provider.models;
  return models.map((model) => ({ value: model, label: model }));
}

/**
 * Settings → AI Providers & Fallbacks. Cloud providers and keys are editable here.
 * Apple Intelligence and on-device Gemma stay listed as native-only.
 */
export function AIProvidersScreen() {
  const theme = useTheme();
  const prefs = usePreferences((p) => p);
  const vision = resolveVisionSelection(platform, prefs.selectedAIProvider, prefs.selectedAIModel);
  const text = resolveTextSelection(platform, prefs.selectedTextAIProvider, prefs.selectedTextAIModel);
  const imageFallback = resolveVisionSelection(platform, prefs.selectedFallbackAIProvider, prefs.selectedFallbackAIModel);
  const textFallback = resolveTextSelection(platform, prefs.selectedTextFallbackAIProvider, prefs.selectedTextFallbackAIModel);

  const [picker, setPicker] = useState<PickerKind | null>(null);
  const [visionKey, setVisionKey] = useState('');
  const [textKey, setTextKey] = useState('');
  const [imageFallbackKey, setImageFallbackKey] = useState('');
  const [textFallbackKey, setTextFallbackKey] = useState('');
  const [visionURL, setVisionURL] = useState('');
  const [showKey, setShowKey] = useState<Record<string, boolean>>({});

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      const [vKey, tKey, iKey, tfKey, vURL] = await Promise.all([
        secureSecretStore.get(apiKeySecretName(vision.provider)),
        secureSecretStore.get(apiKeySecretName(text.provider)),
        secureSecretStore.get(apiKeySecretName(imageFallback.provider)),
        secureSecretStore.get(apiKeySecretName(textFallback.provider)),
        asyncKeyValueStore.get(customBaseURLKey(vision.provider)),
      ]);
      if (cancelled) return;
      setVisionKey(vKey ?? '');
      setTextKey(tKey ?? '');
      setImageFallbackKey(iKey ?? '');
      setTextFallbackKey(tfKey ?? '');
      setVisionURL(vURL ?? '');
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [vision.provider.id, text.provider.id, imageFallback.provider.id, textFallback.provider.id]);

  const persistKey = async (provider: AIProviderDefinition, value: string) => {
    if (value.trim()) await secureSecretStore.set(apiKeySecretName(provider), value.trim());
    else await secureSecretStore.remove(apiKeySecretName(provider));
  };

  const selectVision = (id: AIProviderId) => {
    const provider = aiProviders[id];
    setPreferences({ selectedAIProvider: provider.rawValue, selectedAIModel: defaultModel(provider) || defaultTextModel(provider) });
  };

  const selectText = (id: AIProviderId) => {
    const provider = aiProviders[id];
    setPreferences({ selectedTextAIProvider: provider.rawValue, selectedTextAIModel: defaultTextModel(provider) });
  };

  const selectImageFallback = (id: AIProviderId) => {
    const provider = aiProviders[id];
    setPreferences({ selectedFallbackAIProvider: provider.rawValue, selectedFallbackAIModel: defaultModel(provider) || defaultTextModel(provider) });
  };

  const selectTextFallback = (id: AIProviderId) => {
    const provider = aiProviders[id];
    setPreferences({ selectedTextFallbackAIProvider: provider.rawValue, selectedTextFallbackAIModel: defaultTextModel(provider) });
  };

  const pickerConfig = useMemo(() => {
    switch (picker) {
      case 'vision':
        return { title: 'Provider', options: optionsFor(selectableVision()), selected: vision.provider.id, onSelect: selectVision };
      case 'visionModel':
        return { title: 'Model', options: modelOptions(vision.provider, false), selected: vision.model, onSelect: (model: string) => setPreferences({ selectedAIModel: model }) };
      case 'text':
        return { title: 'Text Provider', options: optionsFor(selectableText()), selected: text.provider.id, onSelect: selectText };
      case 'textModel':
        return { title: 'Text Model', options: modelOptions(text.provider, true), selected: text.model, onSelect: (model: string) => setPreferences({ selectedTextAIModel: model }) };
      case 'imageFallback':
        return { title: 'Image Fallback', options: optionsFor(selectableVision()), selected: imageFallback.provider.id, onSelect: selectImageFallback };
      case 'imageFallbackModel':
        return { title: 'Fallback Model', options: modelOptions(imageFallback.provider, false), selected: imageFallback.model, onSelect: (model: string) => setPreferences({ selectedFallbackAIModel: model }) };
      case 'textFallback':
        return { title: 'Text Fallback', options: optionsFor(selectableText()), selected: textFallback.provider.id, onSelect: selectTextFallback };
      case 'textFallbackModel':
        return { title: 'Fallback Model', options: modelOptions(textFallback.provider, true), selected: textFallback.model, onSelect: (model: string) => setPreferences({ selectedTextFallbackAIModel: model }) };
      default:
        return undefined;
    }
  }, [picker, vision, text, imageFallback, textFallback]);

  return (
    <Screen edges={['left', 'right']}>
      <ScrollView contentContainerStyle={{ padding: theme.spacing.lg, gap: theme.spacing.xl }} keyboardShouldPersistTaps="handled">
        <SettingsSection header="AI Provider" footer="Photos, labels, and typed meals use this vision provider. On-device Apple Intelligence and Gemma stay in the native apps.">
          <SettingsRow icon="cpu" title="Provider" value={vision.provider.displayName} onPress={() => setPicker('vision')} />
          <SettingsRow icon="brain" title="Model" value={vision.model || 'Custom'} onPress={() => setPicker('visionModel')} />
          {vision.provider.requiresAPIKey ? (
            <KeyRow
              label="API Key"
              value={visionKey}
              placeholder={apiKeyPlaceholder(vision.provider)}
              hint={apiKeyShapeHint(vision.provider)}
              revealed={!!showKey.vision}
              onToggle={() => setShowKey((s) => ({ ...s, vision: !s.vision }))}
              onChange={(value) => {
                setVisionKey(value);
                void persistKey(vision.provider, value);
              }}
            />
          ) : null}
          {vision.provider.id === 'ollama' || vision.provider.requiresCustomEndpoint ? (
            <View style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 10 }}>
              <AppText variant="caption" tone="secondary">
                {vision.provider.requiresCustomEndpoint ? 'Base URL' : 'Server URL'}
              </AppText>
              <TextInput
                value={visionURL}
                onChangeText={(value) => {
                  setVisionURL(value);
                  void (value.trim() ? asyncKeyValueStore.set(customBaseURLKey(vision.provider), value.trim()) : asyncKeyValueStore.remove(customBaseURLKey(vision.provider)));
                }}
                placeholder={vision.provider.baseURL || 'https://your-endpoint.com/v1'}
                placeholderTextColor={theme.colors.placeholder}
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="url"
                style={[theme.text.body, { color: theme.colors.label, minHeight: 36 }]}
              />
            </View>
          ) : null}
        </SettingsSection>

        <SettingsSection header="Text AI" footer="Optional separate provider for typed meals and coach chat.">
          <SettingsToggleRow
            title="Separate Text Provider"
            value={prefs.separateTextProviderEnabled}
            onValueChange={(value) => setPreferences({ separateTextProviderEnabled: value })}
          />
          {prefs.separateTextProviderEnabled ? (
            <>
              <SettingsRow title="Provider" value={text.provider.displayName} onPress={() => setPicker('text')} />
              <SettingsRow title="Model" value={text.model || 'Custom'} onPress={() => setPicker('textModel')} />
              {text.provider.requiresAPIKey ? (
                <KeyRow
                  label="API Key"
                  value={textKey}
                  placeholder={apiKeyPlaceholder(text.provider)}
                  revealed={!!showKey.text}
                  onToggle={() => setShowKey((s) => ({ ...s, text: !s.text }))}
                  onChange={(value) => {
                    setTextKey(value);
                    void persistKey(text.provider, value);
                  }}
                />
              ) : null}
            </>
          ) : null}
        </SettingsSection>

        <SettingsSection header="Image AI Fallback" footer="Used when the primary vision provider fails.">
          <SettingsToggleRow title="Enable Image Fallback" value={prefs.aiFallbackEnabled} onValueChange={(value) => setPreferences({ aiFallbackEnabled: value })} />
          {prefs.aiFallbackEnabled ? (
            <>
              <SettingsRow title="Provider" value={imageFallback.provider.displayName} onPress={() => setPicker('imageFallback')} />
              <SettingsRow title="Model" value={imageFallback.model || 'Custom'} onPress={() => setPicker('imageFallbackModel')} />
              {imageFallback.provider.requiresAPIKey ? (
                <KeyRow
                  label="API Key"
                  value={imageFallbackKey}
                  placeholder={apiKeyPlaceholder(imageFallback.provider)}
                  revealed={!!showKey.imageFallback}
                  onToggle={() => setShowKey((s) => ({ ...s, imageFallback: !s.imageFallback }))}
                  onChange={(value) => {
                    setImageFallbackKey(value);
                    void persistKey(imageFallback.provider, value);
                  }}
                />
              ) : null}
            </>
          ) : null}
        </SettingsSection>

        <SettingsSection header="Text AI Fallback">
          <SettingsToggleRow
            title="Enable Text Fallback"
            value={prefs.textAIFallbackEnabled}
            onValueChange={(value) => setPreferences({ textAIFallbackEnabled: value })}
          />
          {prefs.textAIFallbackEnabled ? (
            <>
              <SettingsRow title="Provider" value={textFallback.provider.displayName} onPress={() => setPicker('textFallback')} />
              <SettingsRow title="Model" value={textFallback.model || 'Custom'} onPress={() => setPicker('textFallbackModel')} />
              {textFallback.provider.requiresAPIKey ? (
                <KeyRow
                  label="API Key"
                  value={textFallbackKey}
                  placeholder={apiKeyPlaceholder(textFallback.provider)}
                  revealed={!!showKey.textFallback}
                  onToggle={() => setShowKey((s) => ({ ...s, textFallback: !s.textFallback }))}
                  onChange={(value) => {
                    setTextFallbackKey(value);
                    void persistKey(textFallback.provider, value);
                  }}
                />
              ) : null}
            </>
          ) : null}
        </SettingsSection>

        <SettingsSection header="Context" footer="Optional notes sent with every request, like dietary restrictions.">
          <View style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 10 }}>
            <TextInput
              value={prefs.aiUserContext}
              onChangeText={(aiUserContext) => setPreferences({ aiUserContext })}
              placeholder="e.g. vegetarian, lactose-free"
              placeholderTextColor={theme.colors.placeholder}
              multiline
              style={[theme.text.body, { color: theme.colors.label, minHeight: 72, textAlignVertical: 'top' }]}
            />
          </View>
        </SettingsSection>

        <SettingsSection header="On-device" footer="These run only in the native iOS / Android apps. The shared app does not bundle Apple Intelligence or Gemma.">
          <SettingsRow icon="sparkles" title={aiProviders.appleIntelligence.displayName} value="Native only" chevron={false} />
          <SettingsRow icon="sparkles" title={aiProviders.gemma4Local.displayName} value="Native only" chevron={false} />
        </SettingsSection>
      </ScrollView>

      {pickerConfig ? (
        <PickerSheet
          visible
          title={pickerConfig.title}
          options={pickerConfig.options}
          selected={pickerConfig.selected}
          onSelect={(value) => pickerConfig.onSelect(value as AIProviderId)}
          onDismiss={() => setPicker(null)}
        />
      ) : null}
    </Screen>
  );
}

function KeyRow({
  label,
  value,
  placeholder,
  hint,
  revealed,
  onToggle,
  onChange,
}: {
  label: string;
  value: string;
  placeholder: string;
  hint?: string;
  revealed: boolean;
  onToggle: () => void;
  onChange: (value: string) => void;
}) {
  const theme = useTheme();
  return (
    <View style={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 10, gap: 4 }}>
      <Row style={{ gap: 8 }}>
        <AppText variant="body" weight="500" style={{ width: 80 }}>
          {label}
        </AppText>
        <TextInput
          value={value}
          onChangeText={onChange}
          placeholder={placeholder}
          placeholderTextColor={theme.colors.placeholder}
          secureTextEntry={!revealed}
          autoCapitalize="none"
          autoCorrect={false}
          textContentType="password"
          style={[theme.text.body, { flex: 1, color: theme.colors.label }]}
        />
        <Pressable accessibilityLabel={revealed ? 'Hide key' : 'Show key'} onPress={onToggle}>
          <Icon name={revealed ? 'eye.fill' : 'eye.slash.fill'} size={16} color={theme.colors.secondaryLabel} />
        </Pressable>
      </Row>
      {hint ? (
        <AppText variant="caption" tone="tertiary">
          {hint}
        </AppText>
      ) : null}
    </View>
  );
}
