# Voice assistant (the orb)

A live, speech-to-speech assistant that can do anything a person can do in Fud AI:
open the camera and log what it sees, scan barcodes, pick photos, search and log
saved foods, edit or delete entries, log water, fasting, weight, body fat and
workouts, change settings, move between screens, and answer like Coach. It talks
while it works, and the user sees each step happen in the real app.

This folder is the contract shared by the iOS app, the Android app and the
Worker (`web/`). Change it here first, then on every platform.

- `tools.json`: the assistant's actions (name, description, parameters, effect,
  confirmation). Both apps bundle an identical copy
  (`ios/calorietracker/VoiceAssistant/VoiceAssistantTools.json`,
  `android/app/src/main/assets/voice_assistant_tools.json`);
  `scripts/check_voice_assistant_tools.py` fails CI when a copy drifts.

## Providers

| Choice | Transport | Who pays |
|---|---|---|
| Fud AI (Plus/Pro) | Worker relay to Gemini Live (`gemini-3.8-live`) | Subscription voice minutes, then credits |
| Gemini (own key) | Gemini Live WebSocket, key in `x-goog-api-key` header | User |
| OpenAI (own key) | Realtime WebSocket, `Authorization: Bearer` | User |
| xAI (own key) | Grok Voice realtime WebSocket, `Authorization: Bearer` | User |
| OpenAI GPT-Live (own OpenAI key) | GPT-Live WebSocket (`wss://api.openai.com/v1/live/sessions`), `Authorization: Bearer`; tools run through Responses delegation to `gpt-6-luna` | User |
| Qwen (own workspace key) | Qwen Omni Realtime WebSocket (`wss://{workspace}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime`, Singapore), `Authorization: Bearer` | User |

Settings offer **Automatic** (default): the selected own-key provider when its key
is saved, otherwise Fud AI hosting when subscribed, otherwise an explanation of
both options. Gemini, OpenAI and xAI reuse keys in AI Providers. For Qwen,
enter the Singapore workspace ID and its matching key in Voice Assistant
settings. The workspace ID must be one DNS label. The key is stored separately
in the platform credential store and sent only in the Authorization header.
Qwen chat uses its own key; it does not configure Qwen Live. An explicitly
chosen but incomplete Qwen setup never starts a Hosted session.

Built-in models (first is the default): Gemini `gemini-3.8-live`,
`gemini-3.8-live-extended-thinking`, `gemini-3.1-flash-live-preview`;
OpenAI `gpt-realtime-2.1-mini`,
`gpt-realtime-2.1`; xAI `grok-voice-latest`, `grok-voice-3.0`; Qwen
`qwen3.8-omni-flash-realtime`; GPT-Live `gpt-live-1`, `gpt-live-1.1`. Extended
Thinking sends low thinking and non-blocking tools on the BYOK route only.
Hosted sessions always use the Worker's model. GPT-Live 1.1 is opt-in and does
not add camera or change the delegated tool model.

Voices: Gemini Kore (default), Puck, Charon, Fenrir, Aoede, Leda, Orus, Zephyr,
Sulafat, Achird, Despina, Gacrux; OpenAI marin (default), cedar, coral, sage,
shimmer, ash, ballad, alloy, echo, verse; xAI ara (default), eve, rex, sal, leo;
Qwen Tina (default), Cindy, Raymond, Katerina, Ryan, Mia, Jennifer, Aiden, Sohee,
Lenn; GPT-Live quartz (default), ripple, vesper, willow, stone, gleam, meridian,
bossa, tempo, beacon, delta, cinder.

Camera frames the model can see: Gemini, Qwen and hosted `continuous` (one JPEG
per second while the camera is open, ≤ 768 px long side; Qwen drops frames over
256 KB and takes them with `input_image_buffer.append` after audio has started); OpenAI `per_turn` (the
latest frame with each user turn and before capture decisions); xAI and GPT-Live
`none` (the model asks the user to say when the food is in frame).

## Session

- Audio: 16-bit mono PCM. Gemini/hosted/Qwen: 16 kHz in, 24 kHz out. OpenAI/xAI/
  GPT-Live: 24 kHz both ways. Echo cancellation on (iOS voice processing, Android
  `VOICE_COMMUNICATION` + `AcousticEchoCanceler`). Speaking over the assistant
  stops its playback (OpenAI also gets `conversation.item.truncate`).
- Ends after 45 s with no speech either way (not while a tool is waiting for
  the user), after 15 minutes, when the user closes it, on `end_conversation`,
  or when the app goes to the background.
- Server messages can arrive as text or binary WebSocket frames; decode both
  as UTF-8 JSON.
- Gemini sends `toolCall.functionCalls[{id,name,args}]` and accepts
  `toolResponse.functionResponses[{id,name,response}]`; it may send
  `toolCallCancellation.ids`. OpenAI/xAI/Qwen send
  `response.function_call_arguments.done {call_id,name,arguments}` and accept
  `conversation.item.create {type: function_call_output, call_id, output}`
  followed by `response.create`.
- Tools are declared at setup: Gemini `tools: [{functionDeclarations}]`;
  OpenAI/xAI/Qwen `session.tools: [{type: "function", name, description,
  parameters}]`, with `tool_choice: "auto"` for OpenAI/xAI. Qwen's tools go
  in a second `session.update` and its input transcription
  (`qwen3-asr-flash-realtime`) in a third; Qwen reads only audio-transcript
  deltas as captions and drops camera frames until audio has been sent. The declared set is `tools.json`
  plus the Coach data tools; tools marked `"workout": true` and the Coach
  workout tools are left out when the workout diary is turned off.

### GPT-Live

GPT-Live starts with `session.start` (model, instructions, voice, and
`delegation: {type: "responses", responses: {model, instructions, tools,
tool_choice}}`), acknowledged by `session.started`. Audio goes up as
`session.input_audio.append` and comes back as `session.output_audio.delta`;
captions are `session.input_transcript.delta` / `session.output_transcript.delta`.
The backend's Responses events arrive inside `response.event`: function calls
in `response.output_item.done`, the end in `response.completed`. Results go back
as `response.item.create {function_call_output}` followed by `response.create`,
and card taps as a `response.item.create` user message. GPT-Live reports no turn
ends, so a turn ends when the user speaks after the assistant; a backend
completion never ends a spoken turn on iOS.

### Instructions

Built per session from: a short persona (Fud AI's assistant and coach, warm and
brief, speaks the user's language), the same profile/goal/forecast summary
Coach uses, today's totals, the rules below, and the capabilities of this
session (camera frames, auto-capture, confirm-before-saving).

Rules given to the model: briefly announce visible app actions, then do
them; perform read-only lookups without a preamble; use tools for anything
in the app instead of telling the user to tap;
never invent numbers (use the data tools); one question at a time; after a
draft, read back the main items and total calories; never call a tool with
`user_confirmed: true` unless the user agreed to that exact change.

“How am I doing?” is a check on the user's progress, not the assistant's
identity. Look up today's diary with `get_app_state` before answering and say
when nothing was logged. The mascot's name is only spoken when explicitly
asked for its name or identity; personality changes delivery, not the answer.

## Tool conventions

- Results are JSON objects: `{"ok": true, ...}` or
  `{"ok": false, "error": "<code>", "message": "<short English text>"}`.
  Codes: `invalid_arguments`, `not_found`, `unsupported`, `cancelled`,
  `timeout`, `camera_unavailable`, `permission_denied`, `analysis_failed`,
  `quota_exceeded`, `confirmation_required`, `workout_diary_off`.
- `effect`: `read` (no change), `navigate` (moves the UI), `write` (adds or
  changes data), `destructive` (removes data).
- `confirm`: `never`; `always` (destructive); `setting` (only when **Confirm
  before saving** is on, the default). When confirmation is needed and
  `user_confirmed` is not true, nothing changes; the app shows a card with the
  pending change and Confirm/Cancel buttons and returns
  `{"ok": false, "error": "confirmation_required", "summary": "..."}`. The model
  asks the user and calls again with `user_confirmed: true`. Tapping Confirm on
  the card performs the change and tells the model through the next turn
  (`clientContent` text for Gemini, a user text item for OpenAI/xAI).
- Writes show a card with **Undo** for 8 seconds (log_food, update_food_entry,
  log_water, log_weight, log_body_fat, start_fast, end_fast, log_workout,
  log_cardio, update_setting restore the previous state). Deletes have no undo
  because they were confirmed.
- Drafts (`draft_id`) live for the session. A draft result lists items
  (name, serving, calories, protein_g, carbs_g, fat_g) and totals, and appears
  as a card the user can also tap to log.
- Tools that wait for the user (`pick_photos`, `scan_barcode`) time out after
  90 s with `timeout`; closing the picker/scanner returns `cancelled`.
- `capture_photo` releases its camera before food analysis, including failed or
  cancelled capture attempts. Late callbacks cannot close a replacement camera.
  Photo picking closes any camera first; barcode scanning closes its scanner
  on completion, cancellation or timeout. These transitions keep the voice
  conversation running. The model is told to close a camera task when finished
  and reopen only when another camera task needs it.
- Applying a food save also dismisses an open food camera, covering logs made
  without a capture draft. Awaiting save confirmation does not dismiss it.
- `open_screen` switches between all nine rail destinations and can open a
  section of Settings or About. `get_app_state` reports the actual destination,
  not its old parent tab. `set_diary_date` opens a day in Home or Workout Log.
  The assistant minimizes so the destination is visible.
  Tabs the user hid in Settings > App Settings > Tab Bar (Progress, Coach,
  Workouts) return `unsupported` and are listed in `get_app_state` as
  `hidden_screens`. Progress also hides Weekly Challenge; Workouts also hides
  Exercise Library.
- Food analysis, barcode lookup and workout parsing use the app's existing
  pipelines and AI settings (hosted analysis spends hosted actions as today).
- Dates are the user's local calendar; `today`/`yesterday` accepted where noted.

## UI

- Entry points: the pinned mascot on the right rail; iOS App Shortcut / Siri phrase
  ("Talk to Fud AI"), usable from the Action Button, plus a Control Center
  control; Android Quick Settings tile, launcher shortcut and a home-screen
  widget. All open the app straight into a listening session.
- The pinned shortcut shows the selected mascot with no visible label, box,
  or background. The accessible button name and touch target remain in place.
- Home keeps meal calories and macros beneath the meal title on the leading
  side, away from the floating controls. The diary remains visible behind the
  buttons and can scroll its final entry above them.
- Overlay states: **expanded** (dimmed background, large mascot, live captions,
  camera panel when open, cards) and **compact** (a floating mascot with a
  caption pill above the tab bar, used while the app navigates or the user is
  looking at a screen). Tapping the bubble expands; an X ends the session.
- Mascots breathe, hop and double-blink at idle, nod while listening, tilt
  while thinking, and bounce and move their mouths with assistant playback.
  This is audio-level animation, not phoneme timing. Reduced Motion keeps
  the character still. Settings → Voice Assistant shows eight static
  illustrations and lets users customize the selected character.
- Settings → Voice Assistant: provider, model (own key), voice, response
  language (app language by default / automatic / a chosen one), auto-capture
  photos (default on), confirm before saving, save conversations to Coach, voice minutes left
  (hosted), and how to open it from the system (Action Button/Siri/Control
  Center or tile/shortcut/widget).
- Each finished turn is added to the Coach chat history when **Save
  conversations to Coach** is on (default on). This also lets the active
  provider read up to 12 recent Coach messages, with text capped at 600
  characters per message and no attachments, when a follow-up needs it. Chats
  are not a substitute for the app's logs. The food diary tool indicates
  whether an entry has a photo without sending the photo itself.
- Turning off that setting disables new history lookups and saving. Clearing
  Coach chat ends the current Live Voice session, so a new session can't use
  the cleared text or write its unfinished turn back. It doesn't erase the
  app's diary or retract messages already sent to an AI provider.
- **Preview Voice**, beside the speaking-voice selection, plays a short sample
  using the selected live provider, model and voice. It reuses the saved API key
  for own-key providers or the existing hosted plan; hosted previews use the
  normal voice allowance and metering. The sample follows the selected response
  language (Automatic uses the app language).
- Preview is playback-only: it requests no microphone access and sends no
  profile, diary, conversation history or app tools. **Stop Preview**, leaving
  settings, changing the selection, or backgrounding the app cancels it. The
  connection closes as soon as generation finishes, while already received
  audio finishes playing; a 30-second deadline bounds the entire preview.
- All new text is localized in every app language.

## Hosted relay (Worker)

`wss://fud-ai.app/api/hosted-ai/v1/live`, same identity headers as the other
hosted routes (`X-Fud-User-Id`, plus `X-Fud-Platform: ios|android`).

1. Before upgrading, the Worker checks the id, rate limits, the RevenueCat
   plan and the voice balance. Failures are plain HTTP JSON responses like the
   other routes: 401 `invalid_user_id`, 403 `subscription_required`, 402
   `voice_quota_exceeded`, 429 `rate_limited`, 503 `voice_unavailable`.
2. After the upgrade the client speaks the Gemini Live wire protocol. The first
   message must be `setup`; the Worker replaces `model`, and allows only
   `generationConfig` (response modalities, speech/voice config),
   `systemInstruction` (≤ 60 000 chars), `tools` (function declarations only,
   ≤ 64, ≤ 64 KB), input/output transcription, context window compression,
   session resumption and realtime input config. Later client messages may
   only be `realtimeInput`, `clientContent` or `toolResponse`, each ≤ 1 MB.
3. Server messages are forwarded unchanged. The Worker adds its own:
   `{"fudVoice": {"secondsRemaining": n}}` after setup and every 60 s, and
   `{"fudVoice": {"error": "voice_quota_exceeded" | "session_limit"}}` before
   closing with code 4402 (out of minutes) or 4408 (15-minute session limit).
   An upstream failure closes with 1011.
4. Metering: from `setupComplete` until close, in 15-second blocks charged at
   the start of each block. Blocks come from the monthly voice allowance
   (Plus 20 min, Pro 100 min, UTC calendar month), then from the credit bank at
   1 credit per block (4 credits a minute). Each block has an idempotent billing
   receipt, so retrying a committed debit cannot charge twice. A charge that
   cannot be confirmed within five seconds closes the session. The 15-minute
   cap runs independently of database work.
5. `GET /quota` also returns `voice: {month, secondsUsed, secondsLimit,
   creditBank}`.
6. Hosted session-resumption handles are opaque, signed wrappers bound to the
   subscriber. They expire after two hours and become invalid if the hosted
   Gemini key rotates. Clients return them unchanged; raw provider handles,
   modified handles, and handles from another subscriber are rejected.

## Privacy

Voice audio, captions, camera frames sent to the model and tool results go to
the chosen provider (Google for hosted). The Worker stores usage counters and
billing receipts, not conversation content. Receipts expire after 30 days.
The privacy policy describes this.
