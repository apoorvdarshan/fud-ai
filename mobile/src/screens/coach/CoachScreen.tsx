/**
 * Coach tab. Mirrors `ChatView.swift`: inline "Coach" title with a reset button, the hero
 * empty state with a 2×2 prompt grid, the message list with assistant badge / typing
 * indicator / inline error, horizontal prompt chips above the composer, and the rounded
 * composer with attach (+), multi-line field and gradient send button. Voice dictation uses
 * the keyboard mic; on-device Whisper recording stays native for now.
 */

import { BottomTabBarHeightContext } from '@react-navigation/bottom-tabs';
import { LinearGradient } from 'expo-linear-gradient';
import { useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Image, KeyboardAvoidingView, Platform, Pressable, ScrollView, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { NativeMenu } from '../../components/NativeMenu';
import { Icon } from '../../components/Icon';
import { GlassChrome } from '../../../modules/glass-chrome';
import { AppText, Row, Screen } from '../../components/primitives';
import { aiErrorMessage } from '../../domain/ai/errors';
import { buildCoachSystemPrompt, contextMessages, suggestedPrompts, type ChatMessage } from '../../domain/coach/coach';
import type { ChatTurn } from '../../domain/ai/transport';
import { generateText } from '../../services/aiClient';
import { ImagePermissionError, pickImage, thumbnailJPEGBase64, type PickedImage } from '../../services/imagePicker';
import { chatStore, newId, useBody, useChat, useDiary, usePreferences, useProfile, useWorkouts } from '../../state/appStores';
import { useTheme } from '../../theme';

const COMPOSER_CONTROL = 40;

export function CoachScreen() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const tabBarHeight = useContext(BottomTabBarHeightContext) ?? insets.bottom;
  const messages = useChat((s) => s.messages);
  const profile = useProfile((s) => s);
  const prefs = usePreferences((s) => s);
  const body = useBody((s) => s);
  const diary = useDiary((s) => s);
  const workouts = useWorkouts((s) => s);

  const [draft, setDraft] = useState('');
  const [attachment, setAttachment] = useState<PickedImage | undefined>(undefined);
  const [isSending, setIsSending] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | undefined>(undefined);
  const attachMenuItems = [
    { id: 'camera', title: 'Camera', systemImage: 'camera.fill' as const },
    { id: 'library', title: 'Photo Library', systemImage: 'photo.on.rectangle' as const },
  ];
  const scrollRef = useRef<ScrollView>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);
  useEffect(() => {
    const timer = setTimeout(() => scrollRef.current?.scrollToEnd({ animated: true }), 50);
    return () => clearTimeout(timer);
  }, [messages.length, isSending]);

  const prompts = useMemo(() => suggestedPrompts(profile.goal, workouts.sessions.length > 0), [profile.goal, workouts.sessions.length]);
  const canSend = (draft.trim().length > 0 || attachment !== undefined) && !isSending;

  const send = async (text = draft) => {
    const trimmed = text.trim();
    if ((trimmed.length === 0 && !attachment) || isSending) return;
    // Like `ChatView.send()`: the ≤1600 px JPEG goes to the model once, the history keeps only
    // a ≤700 px thumbnail so the persisted conversation stays small.
    let thumbnail: string | undefined;
    if (attachment) {
      try {
        thumbnail = await thumbnailJPEGBase64(attachment);
      } catch {
        setErrorMessage('Failed to process the image.');
        return;
      }
    }
    const userMessage: ChatMessage = {
      id: newId(),
      role: 'user',
      content: trimmed || 'What do you see in this photo?',
      timestamp: new Date().toISOString(),
      ...(thumbnail ? { attachmentImageBase64: thumbnail } : {}),
    };
    // History is text-only for every provider, hosted included, exactly like `ChatService`:
    // earlier photos are described by the turns around them, never re-uploaded.
    const history: ChatTurn[] = contextMessages(chatStore.getState()).map((m) => ({ role: m.role, text: m.content }));
    const uploadImage = attachment?.base64;
    chatStore.dispatch({ type: 'append', message: userMessage });
    setDraft('');
    setAttachment(undefined);
    setErrorMessage(undefined);
    setIsSending(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const systemInstruction = buildCoachSystemPrompt({
        profile,
        weights: body.weightEntries,
        bodyFats: body.bodyFatEntries,
        foods: diary.foodEntries,
        fastingSessions: diary.fastingSessions,
        workoutSessions: workouts.sessions,
        heightMetric: prefs.heightUnit === 'cm',
        weightMetric: prefs.weightUnit === 'kg',
        ...(prefs.aiUserContext.trim() ? { userContext: prefs.aiUserContext } : {}),
      });
      const reply = await generateText(
        {
          prompt: userMessage.content,
          history,
          systemInstruction,
          jsonResponse: false,
          ...(uploadImage ? { imagesBase64: [uploadImage] } : {}),
        },
        { vision: uploadImage !== undefined, signal: controller.signal },
      );
      if (controller.signal.aborted) return;
      chatStore.dispatch({ type: 'append', message: { id: newId(), role: 'assistant', content: reply.trim(), timestamp: new Date().toISOString() } });
    } catch (error) {
      if (!controller.signal.aborted) setErrorMessage(aiErrorMessage(error));
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
        setIsSending(false);
      }
    }
  };

  const attach = async (source: 'camera' | 'library') => {
    try {
      const picked = await pickImage(source);
      if (picked) {
        setAttachment(picked);
        setErrorMessage(undefined);
      }
    } catch (error) {
      setErrorMessage(error instanceof ImagePermissionError ? error.message : 'Could not load that photo.');
    }
  };

  const confirmReset = () =>
    Alert.alert('Reset Chat', "Clear all messages and start fresh? This can't be undone.", [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Reset',
        style: 'destructive',
        onPress: () => {
          abortRef.current?.abort();
          chatStore.dispatch({ type: 'reset' });
          setErrorMessage(undefined);
          setIsSending(false);
        },
      },
    ]);

  return (
    <Screen>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} keyboardVerticalOffset={0} style={{ flex: 1 }}>
        {/* Inline navigation title with the reset control, as on iOS. */}
        <Row style={{ justifyContent: 'center', minHeight: 44, paddingHorizontal: theme.spacing.lg }}>
          <AppText variant="headline">Coach</AppText>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Reset Chat"
            disabled={messages.length === 0}
            onPress={confirmReset}
            style={{ position: 'absolute', right: theme.spacing.lg, width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}
          >
            <Icon name="arrow.counterclockwise" size={20} color={messages.length === 0 ? theme.colors.secondaryLabel : theme.colors.accent} />
          </Pressable>
        </Row>

        {messages.length === 0 ? (
          <EmptyState prompts={prompts} disabled={isSending} onPrompt={(prompt) => void send(prompt)} />
        ) : (
          <ScrollView ref={scrollRef} keyboardDismissMode="on-drag" contentContainerStyle={{ paddingTop: 14, paddingBottom: 16, gap: 14 }}>
            {messages.map((message) => (
              <MessageBubble key={message.id} message={message} />
            ))}
            {isSending ? (
              <Row style={{ alignItems: 'flex-start', gap: 8, paddingHorizontal: theme.spacing.lg }}>
                <AssistantBadge />
                <View style={{ paddingHorizontal: 14, paddingVertical: 12, borderTopLeftRadius: 8, borderRadius: 18, backgroundColor: theme.colors.appCard }}>
                  <TypingIndicator />
                </View>
              </Row>
            ) : null}
            {errorMessage ? <ErrorBanner message={errorMessage} /> : null}
          </ScrollView>
        )}

        {messages.length === 0 && errorMessage ? <ErrorBanner message={errorMessage} /> : null}

        {messages.length > 0 ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: theme.spacing.lg, paddingVertical: 6, gap: 8, alignItems: 'center' }}>
            <View style={{ width: 32, height: 44, alignItems: 'center', justifyContent: 'center' }}>
              <View style={{ width: 32, height: 32, borderRadius: 16, backgroundColor: theme.accentAlpha(0.08), alignItems: 'center', justifyContent: 'center' }}>
                <Icon name="sparkles" size={13} color={theme.colors.accent} />
              </View>
            </View>
            {prompts.map((chip) => (
              <Pressable
                key={chip}
                accessibilityRole="button"
                disabled={isSending}
                onPress={() => void send(chip)}
                style={({ pressed }) => ({
                  minHeight: 44,
                  paddingHorizontal: 14,
                  justifyContent: 'center',
                  borderRadius: theme.radii.pill,
                  backgroundColor: theme.accentAlpha(pressed ? 0.18 : 0.1),
                  borderWidth: 0.6,
                  borderColor: theme.accentAlpha(0.3),
                  opacity: isSending ? 0.5 : 1,
                })}
              >
                <AppText variant="footnote" tone="accent" weight="500">
                  {chip}
                </AppText>
              </Pressable>
            ))}
          </ScrollView>
        ) : null}

        {/* Composer */}
        <View style={{ gap: 8, paddingHorizontal: 12, paddingTop: 4, paddingBottom: tabBarHeight + 10 }}>
          {attachment ? (
            <Row style={{ gap: 10, paddingHorizontal: 12, paddingVertical: 9, borderRadius: 20, backgroundColor: theme.colors.appCard, borderWidth: 0.7, borderColor: theme.accentAlpha(0.18) }}>
              <Image source={{ uri: attachment.uri }} style={{ width: 62, height: 62, borderRadius: 14 }} accessibilityIgnoresInvertColors />
              <View style={{ flex: 1, gap: 3 }}>
                <AppText variant="subheadlineSemibold">Image attached</AppText>
                <AppText variant="caption" tone="secondary">
                  Send with your Coach message
                </AppText>
              </View>
              <Pressable accessibilityRole="button" accessibilityLabel="Remove image" onPress={() => setAttachment(undefined)} style={{ width: 36, height: 36, borderRadius: 18, backgroundColor: theme.colors.fill, alignItems: 'center', justifyContent: 'center' }}>
                <Icon name="xmark" size={14} color={theme.colors.secondaryLabel} />
              </Pressable>
            </Row>
          ) : null}
          <GlassChrome
            interactive
            cornerRadius={24}
            fallbackColor={theme.colors.appCard}
            style={{
              borderRadius: 24,
              overflow: 'hidden',
              borderWidth: 0.8,
              borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.08)',
              shadowColor: '#000',
              shadowOpacity: 0.18,
              shadowRadius: 14,
              shadowOffset: { width: 0, height: 6 },
              elevation: 4,
            }}
          >
          <Row
            style={{
              alignItems: 'flex-end',
              gap: 8,
              paddingLeft: 6,
              paddingRight: 5,
              paddingVertical: 4,
            }}
          >
            <NativeMenu
              items={attachMenuItems}
              disabled={isSending}
              onSelect={(id) => {
                if (isSending) return;
                if (id === 'camera') void attach('camera');
                if (id === 'library') void attach('library');
              }}
              accessibilityLabel="Attach photo"
              style={{ width: COMPOSER_CONTROL, height: COMPOSER_CONTROL }}
            >
              <View style={{ width: COMPOSER_CONTROL, height: COMPOSER_CONTROL, alignItems: 'center', justifyContent: 'center', opacity: isSending ? 0.4 : 1 }}>
                <Icon name={attachment ? 'photo.fill' : 'plus.circle.fill'} size={26} color={theme.colors.accent} />
              </View>
            </NativeMenu>
            <TextInput
              value={draft}
              onChangeText={setDraft}
              placeholder="Ask Coach…"
              placeholderTextColor={theme.colors.placeholder}
              multiline
              accessibilityLabel="Message"
              style={[theme.text.body, { flex: 1, color: theme.colors.label, paddingHorizontal: 8, paddingVertical: 10, maxHeight: 120 }]}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Send"
              disabled={!canSend}
              onPress={() => void send()}
              style={{ width: COMPOSER_CONTROL, height: COMPOSER_CONTROL, borderRadius: COMPOSER_CONTROL / 2, overflow: 'hidden' }}
            >
              {canSend ? (
                <LinearGradient colors={theme.colors.accentGradient} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
                  <Icon name="arrow.up" size={18} color={theme.colors.onAccent} />
                </LinearGradient>
              ) : (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: theme.colors.fill }}>
                  <Icon name={isSending ? 'arrow.up' : 'mic'} size={18} color={isSending ? theme.colors.onAccent : theme.colors.accent} />
                </View>
              )}
            </Pressable>
          </Row>
          </GlassChrome>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

// MARK: - Pieces

function EmptyState({ prompts, disabled, onPrompt }: { prompts: string[]; disabled: boolean; onPrompt: (prompt: string) => void }) {
  const theme = useTheme();
  return (
    <ScrollView keyboardDismissMode="interactive" showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 8, paddingBottom: 24 }}>
      <View style={{ height: 54 }} />
      <View style={{ alignItems: 'center', paddingBottom: 18 }}>
        <View style={{ width: 92, height: 92, borderRadius: 46, backgroundColor: theme.colors.appCard, borderWidth: 0.8, borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.2)' : 'rgba(0,0,0,0.06)', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="bubble.left.and.bubble.right.fill" size={38} color={theme.colors.accent} />
        </View>
      </View>
      <View style={{ gap: 8, alignItems: 'center' }}>
        <AppText variant="title2" weight="600">
          Ask your Coach
        </AppText>
        <AppText variant="subheadline" tone="secondary" align="center" style={{ paddingHorizontal: 24, lineHeight: 22 }}>
          Your coach can see your nutrition, goals, and workout diary. Ask about food, progress, recovery, or your training plan.
        </AppText>
      </View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10, paddingHorizontal: 16, paddingTop: 26 }}>
        {prompts.slice(0, 4).map((prompt) => (
          <Pressable
            key={prompt}
            accessibilityRole="button"
            disabled={disabled}
            onPress={() => onPrompt(prompt)}
            style={({ pressed }) => ({
              width: '47%',
              flexGrow: 1,
              minHeight: 70,
              padding: 12,
              borderRadius: theme.radii.card,
              backgroundColor: theme.colors.appCard,
              borderWidth: 0.6,
              borderColor: theme.accentAlpha(0.18),
              opacity: pressed ? 0.7 : 1,
            })}
          >
            <Row style={{ alignItems: 'flex-start', gap: 9 }}>
              <Icon name="arrow.up.right" size={11} color={theme.colors.accent} style={{ marginTop: 3 }} />
              <AppText variant="footnote" weight="500" style={{ flex: 1 }} numberOfLines={3}>
                {prompt}
              </AppText>
            </Row>
          </Pressable>
        ))}
      </View>
    </ScrollView>
  );
}

function AssistantBadge() {
  const theme = useTheme();
  return (
    <View style={{ width: 28, height: 28, borderRadius: 14, marginTop: 8, backgroundColor: theme.colors.appCard, borderWidth: 0.5, borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.18)' : 'rgba(0,0,0,0.06)', alignItems: 'center', justifyContent: 'center' }}>
      <Icon name="sparkles" size={11} color={theme.colors.accent} />
    </View>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const theme = useTheme();
  const isUser = message.role === 'user';
  return (
    <Row style={{ alignItems: 'flex-start', gap: 8, paddingHorizontal: theme.spacing.lg }}>
      {isUser ? <View style={{ flex: 1, minWidth: 48 }} /> : <AssistantBadge />}
      <View
        style={{
          maxWidth: '82%',
          gap: 9,
          paddingHorizontal: 14,
          paddingVertical: 10,
          borderTopLeftRadius: isUser ? 20 : 8,
          borderTopRightRadius: isUser ? 8 : 20,
          borderBottomLeftRadius: 20,
          borderBottomRightRadius: 20,
          backgroundColor: isUser ? theme.colors.accent : theme.colors.appCard,
          borderWidth: isUser ? 0 : 0.5,
          borderColor: theme.scheme === 'dark' ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.06)',
        }}
      >
        {message.attachmentImageBase64 ? (
          <Image source={{ uri: `data:image/jpeg;base64,${message.attachmentImageBase64}` }} style={{ width: 180, height: 180, borderRadius: 14 }} accessibilityIgnoresInvertColors />
        ) : null}
        <AppText variant="body" tone={isUser ? 'onAccent' : 'primary'} selectable>
          {message.content}
        </AppText>
      </View>
      {isUser ? null : <View style={{ flex: 1, minWidth: 48 }} />}
    </Row>
  );
}

function TypingIndicator() {
  const theme = useTheme();
  const [phase, setPhase] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setPhase((p) => (p + 1) % 3), 350);
    return () => clearInterval(timer);
  }, []);
  return (
    <Row style={{ gap: 5 }}>
      {[0, 1, 2].map((i) => (
        <View key={i} style={{ width: 7, height: 7, borderRadius: 3.5, backgroundColor: theme.colors.accent, opacity: phase === i ? 1 : 0.3, transform: [{ scale: phase === i ? 1.15 : 1 }] }} />
      ))}
    </Row>
  );
}

function ErrorBanner({ message }: { message: string }) {
  const theme = useTheme();
  return (
    <Row style={{ alignItems: 'flex-start', gap: 8, marginHorizontal: theme.spacing.lg, paddingHorizontal: 14, paddingVertical: 10, borderRadius: 14, backgroundColor: theme.colors.appCard, borderWidth: 0.6, borderColor: 'rgba(255,59,48,0.25)' }}>
      <Icon name="exclamationmark.triangle.fill" size={14} color={theme.colors.destructive} style={{ marginTop: 1 }} />
      <AppText variant="caption" tone="destructive" weight="500" style={{ flex: 1 }} accessibilityLiveRegion="polite">
        {message}
      </AppText>
    </Row>
  );
}
