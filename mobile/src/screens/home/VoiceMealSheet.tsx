/**
 * Describe Meal by voice. Records on-device, then transcribes with OpenAI / Groq Whisper.
 * Never hangs: recording caps at 60s, transcription at 30s.
 */

import { RecordingPresets, useAudioRecorder, useAudioRecorderState } from 'expo-audio';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';

import { BottomSheet } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { AppText, LinkButton, PrimaryButton, Row } from '../../components/primitives';
import { speechErrors, speechProviderFromRawValue } from '../../domain/ai/speech';
import { prepareRecording, RECORDING_MAX_MS, requestMicrophonePermission, SpeechError, stopAudioMode, transcribeAudioFile } from '../../services/speech';
import { usePreferences } from '../../state/appStores';
import { useTheme } from '../../theme';

interface VoiceMealSheetProps {
  visible: boolean;
  onDismiss: () => void;
  onTranscribed: (text: string) => void;
  presentation?: 'sheet' | 'popover';
}

type Phase = 'idle' | 'starting' | 'recording' | 'transcribing';

export function VoiceMealSheet({ visible, onDismiss, onTranscribed, presentation = 'sheet' }: VoiceMealSheetProps) {
  const theme = useTheme();
  const providerRaw = usePreferences((p) => p.selectedSpeechProvider);
  const provider = speechProviderFromRawValue(providerRaw);
  const recorder = useAudioRecorder(RecordingPresets.HIGH_QUALITY);
  const recorderState = useAudioRecorderState(recorder);
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<string | null>(null);
  const timeout = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const recorderRef = useRef(recorder);
  recorderRef.current = recorder;
  const visibleRef = useRef(visible);
  visibleRef.current = visible;
  const startGeneration = useRef(0);
  const startingRef = useRef(false);

  const abortRecording = async () => {
    if (timeout.current) clearTimeout(timeout.current);
    try {
      await recorderRef.current.stop();
    } catch {
      /* already stopped */
    }
    await stopAudioMode().catch(() => undefined);
  };

  const cancelPendingStart = () => {
    startGeneration.current += 1;
    startingRef.current = false;
    setPhase('idle');
    setError(null);
    void abortRecording();
  };

  useEffect(() => {
    if (!visible) cancelPendingStart();
  }, [visible]);

  useEffect(() => {
    return () => {
      startGeneration.current += 1;
      void abortRecording();
    };
  }, []);

  const stopRecording = async () => {
    if (timeout.current) clearTimeout(timeout.current);
    try {
      await recorder.stop();
    } catch {
      /* already stopped */
    }
    await stopAudioMode().catch(() => undefined);
    const uri = recorder.uri;
    if (!uri) {
      setPhase('idle');
      setError(speechErrors.empty);
      return;
    }
    setPhase('transcribing');
    try {
      const text = await transcribeAudioFile(uri);
      onTranscribed(text);
    } catch (err) {
      setError(err instanceof SpeechError ? err.message : speechErrors.failed);
      setPhase('idle');
    }
  };

  const startRecording = async () => {
    if (startingRef.current || phase === 'starting' || phase === 'recording' || phase === 'transcribing') return;
    startingRef.current = true;
    const generation = ++startGeneration.current;
    const isCurrent = () => generation === startGeneration.current;
    const abortIfDismissed = async () => {
      if (!isCurrent()) return true;
      if (visibleRef.current) return false;
      await abortRecording();
      startingRef.current = false;
      return true;
    };

    setError(null);
    setPhase('starting');
    const granted = await requestMicrophonePermission();
    if (await abortIfDismissed()) return;
    if (!granted) {
      startingRef.current = false;
      setPhase('idle');
      setError(speechErrors.permission);
      return;
    }
    try {
      await prepareRecording();
      if (await abortIfDismissed()) return;
      await recorder.prepareToRecordAsync();
      if (await abortIfDismissed()) return;
      recorder.record();
      if (await abortIfDismissed()) return;
      startingRef.current = false;
      setPhase('recording');
      timeout.current = setTimeout(() => {
        void stopRecording();
      }, RECORDING_MAX_MS);
    } catch {
      if (!isCurrent()) return;
      await abortRecording();
      startingRef.current = false;
      if (visibleRef.current) {
        setError(speechErrors.failed);
        setPhase('idle');
      }
    }
  };

  return (
    <BottomSheet visible={visible} onDismiss={onDismiss} title="Voice" presentation={presentation}>
      <Row style={{ gap: 8 }}>
        <Icon name="waveform" size={16} color={theme.colors.accent} />
        <AppText variant="footnote" tone="secondary" style={{ flex: 1 }}>
          Records on this device, then transcribes with {provider.displayName}. Audio is sent to that provider — not a keyboard mic, and not on-device Whisper Base.
        </AppText>
      </Row>
      <View style={{ alignItems: 'center', gap: 16, paddingVertical: 12 }}>
        {phase === 'transcribing' ? <ActivityIndicator color={theme.colors.accent} /> : null}
        <AppText variant="headline" tone="accent">
          {phase === 'recording' ? 'Listening…' : phase === 'transcribing' || phase === 'starting' ? 'Working…' : 'Tap to record your meal'}
        </AppText>
        {recorderState.isRecording ? (
          <AppText variant="caption" tone="secondary">
            {Math.round(recorderState.durationMillis / 1000)}s
          </AppText>
        ) : null}
        {error ? (
          <AppText variant="caption" tone="destructive" align="center">
            {error}
          </AppText>
        ) : null}
      </View>
      {phase === 'recording' ? (
        <PrimaryButton title="Stop & Transcribe" onPress={() => void stopRecording()} />
      ) : (
        <PrimaryButton title={phase === 'idle' ? 'Record' : 'Working…'} disabled={phase !== 'idle'} onPress={() => void startRecording()} />
      )}
      <LinkButton title="Cancel" variant="body" style={{ opacity: 0.7 }} onPress={onDismiss} />
    </BottomSheet>
  );
}
