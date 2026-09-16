/**
 * Speech-to-text providers. Matches `SpeechProvider.swift` raw values so Settings and backups
 * line up. On-device Whisper Base is listed but not selectable in the shared app (huge CoreML
 * binaries). Cloud Whisper (OpenAI / Groq) actually transcribes recorded audio.
 */

export const speechProviderIds = ['whisperBase', 'openai', 'groq'] as const;
export type SpeechProviderId = (typeof speechProviderIds)[number];

export interface SpeechProviderDefinition {
  id: SpeechProviderId;
  rawValue: string;
  displayName: string;
  subtitle: string;
  /** Cloud transcription endpoint. Undefined for on-device-only options. */
  transcriptionURL?: string;
  model: string;
  selectable: boolean;
}

export const speechProviders: Record<SpeechProviderId, SpeechProviderDefinition> = {
  whisperBase: {
    id: 'whisperBase',
    rawValue: 'Whisper Base (On-Device)',
    displayName: 'Whisper Base (On-Device)',
    subtitle: 'Apple-only CoreML model. Not bundled in the shared app — use OpenAI or Groq.',
    model: 'whisper-base',
    selectable: false,
  },
  openai: {
    id: 'openai',
    rawValue: 'OpenAI Whisper',
    displayName: 'OpenAI Whisper',
    subtitle: 'Cloud transcription with your OpenAI API key. Audio leaves the device.',
    transcriptionURL: 'https://api.openai.com/v1/audio/transcriptions',
    model: 'whisper-1',
    selectable: true,
  },
  groq: {
    id: 'groq',
    rawValue: 'Groq (Whisper)',
    displayName: 'Groq (Whisper)',
    subtitle: 'Cloud Whisper Large v3 via Groq. Fast, uses your Groq API key.',
    transcriptionURL: 'https://api.groq.com/openai/v1/audio/transcriptions',
    model: 'whisper-large-v3',
    selectable: true,
  },
};

export const SPEECH_PROVIDER_KEY = 'selectedSpeechProvider';
export const TRANSCRIPTION_TIMEOUT_MS = 30_000;
export const RECORDING_MAX_MS = 60_000;

export function speechProviderFromRawValue(raw: string | undefined): SpeechProviderDefinition {
  const match = Object.values(speechProviders).find((provider) => provider.rawValue === raw);
  if (match?.selectable) return match;
  return speechProviders.openai;
}

export const speechErrors = {
  missingKey: 'Add an API key for this speech provider in Settings → AI Access.',
  unsupported: 'On-device Whisper Base is not available in the shared app. Choose OpenAI Whisper or Groq.',
  timeout: 'Transcription took too long. Try a shorter recording.',
  empty: 'Could not hear that. Try recording again.',
  permission: 'Microphone access is needed to describe a meal by voice.',
  failed: 'Transcription failed. Check your connection and API key.',
} as const;
