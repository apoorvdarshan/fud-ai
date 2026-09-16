/**
 * Voice meal transcription. The sheet records with expo-audio; this module sends the file to
 * OpenAI or Groq Whisper. On-device Whisper Base is not bundled. Always times out.
 */

import { requestRecordingPermissionsAsync, setAudioModeAsync } from 'expo-audio';
import { Platform } from 'react-native';

import { API_KEY_SECRET_PREFIX } from '../domain/ai/settings';
import {
  RECORDING_MAX_MS,
  speechErrors,
  speechProviderFromRawValue,
  speechProviders,
  TRANSCRIPTION_TIMEOUT_MS,
} from '../domain/ai/speech';
import { preferencesStore } from '../state/appStores';
import { secureSecretStore } from '../state/persistence';

export class SpeechError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SpeechError';
  }
}

export async function requestMicrophonePermission(): Promise<boolean> {
  try {
    const result = await requestRecordingPermissionsAsync();
    return result.granted;
  } catch {
    return false;
  }
}

export async function prepareRecording(): Promise<void> {
  await setAudioModeAsync({ allowsRecording: true, playsInSilentMode: true });
}

export async function stopAudioMode(): Promise<void> {
  await setAudioModeAsync({ allowsRecording: false, playsInSilentMode: true });
}

/** Transcribe a recorded audio file. `uri` is a local file from the recorder. */
export async function transcribeAudioFile(uri: string, signal?: AbortSignal): Promise<string> {
  const provider = speechProviderFromRawValue(preferencesStore.getState().selectedSpeechProvider);
  if (!provider.selectable || !provider.transcriptionURL) {
    throw new SpeechError(speechErrors.unsupported);
  }
  const keyName = provider.id === 'groq' ? 'Groq' : 'OpenAI';
  const apiKey = await secureSecretStore.get(API_KEY_SECRET_PREFIX + keyName).catch(() => null);
  if (!apiKey) throw new SpeechError(speechErrors.missingKey);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TRANSCRIPTION_TIMEOUT_MS);
  const onAbort = () => controller.abort();
  signal?.addEventListener('abort', onAbort);

  try {
    const form = new FormData();
    form.append('model', provider.model);
    form.append(
      'file',
      {
        uri,
        name: 'meal.m4a',
        type: Platform.OS === 'ios' ? 'audio/m4a' : 'audio/mp4',
      } as unknown as Blob,
    );

    const response = await fetch(provider.transcriptionURL, {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}` },
      body: form,
      signal: controller.signal,
    });
    if (!response.ok) throw new SpeechError(speechErrors.failed);
    const json = (await response.json()) as { text?: string };
    const text = json.text?.trim() ?? '';
    if (!text) throw new SpeechError(speechErrors.empty);
    return text;
  } catch (error) {
    if (error instanceof SpeechError) throw error;
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError') {
      throw new SpeechError(speechErrors.timeout);
    }
    throw new SpeechError(speechErrors.failed);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

export { RECORDING_MAX_MS, speechProviderFromRawValue, speechProviders };
