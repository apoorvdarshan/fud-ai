# Play Store Listing (ASO)

Play Store listing details for Fud AI v1.0.0. Each field is in a code block for easy copy-paste into the Play Console.

## App Name (30 chars max)
```
Fud AI - Calorie Tracker
```

## Short Description (80 chars max)
```
Snap, speak, or type a meal. AI logs the calories. Free & open source.
```

## Full Description (4000 chars max)
```
Fud AI makes calorie tracking effortless with AI-powered food recognition. Snap a photo, speak it, or type it — and get instant nutrition data: calories, protein, carbs, fats, and 9 micronutrients.

Coach: a multi-turn AI chat that sees your profile, weight history, and food log. Ask anything in plain English — "expected weight in 30 days?", "how's my protein this week?", "what should I eat tonight?".

Fud AI is free, open source, and privacy-first. Bring your own API key. All data stays on your device.

HOW TO USE
1) Set up your profile with goals + body stats
2) Snap, speak, or type a meal — review and save
3) Ask the Coach anything: trends, predictions, advice
4) Track progress on charts and home screen widgets

13 AI PROVIDERS SUPPORTED
• Google Gemini, OpenAI, Anthropic Claude, xAI Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama (local), and any OpenAI-compatible endpoint
• Switch providers and models anytime
• API keys stored encrypted on device (EncryptedSharedPreferences, AES-256)

5 SPEECH-TO-TEXT ENGINES
• Native Android, OpenAI Whisper, Groq, Deepgram, AssemblyAI

COACH
• Multi-turn AI chat tab
• Sees your full profile, weight trend, daily macro totals, and last 10 weight entries
• Goal-aware prompts — different chips for Lose / Gain / Maintain
• Predicts future weight from your real energy balance, not guesses

13 NUTRIENTS TRACKED
• Calories, protein, carbs, fat
• Sugar, added sugar, fiber, saturated fat, mono/polyunsaturated fat
• Cholesterol, sodium, potassium

PERSONALIZED GOALS
• BMR via Katch-McArdle (with body fat) or Mifflin-St Jeor
• TDEE with 6 activity levels
• Auto-calculated daily calorie, protein, carbs, and fat targets
• Fully customizable — override any value

PROGRESS & ANALYTICS
• Weight chart with trend line and goal weight
• Calorie trend chart with daily intake vs goal
• Macro averages over 1W, 1M, 3M, 6M, 1Y, or All Time

WIDGETS
• Calorie widget — pink-gradient ring with today's calories + macros
• Protein widget — protein progress at a glance
• Two sizes each (Small 2x2, Medium 4x2)
• Update the moment you log a meal

15 LANGUAGES
• Auto-selected by your phone's language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese (Simplified), Hindi, Arabic, Romanian, Azerbaijani

PRIVACY FIRST
• No account required — no sign-in, no cloud sync
• All data stored locally on your device
• No analytics, no ads, no tracking
• Open source under MIT License

HEALTH CONNECT
• Two-way sync for nutrition and weight
• External weight samples (Samsung Health, Withings, Fitbit via Health Connect) auto-imported
• Macros + 9 micronutrients written per meal
• Edits and deletes sync back — no orphan samples

I built Fud AI because tracking calories shouldn't feel like a chore. I want to make healthy eating simple and accessible for everyone.

If you have ideas or questions, reach out at apoorv@fud-ai.app or open an issue on GitHub.

NOTE: Fud AI does not offer medical advice. All nutritional estimates are AI-generated suggestions only. Please consult with a healthcare professional before making significant changes to your diet.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai
```

## Category
```
Primary: Health & Fitness
```

## Tags (pick 5 in Play Console)
```
Calorie counter
Nutrition
Macro tracker
Diet
Weight loss
```

## Contact Details
```
Email: apoorv@fud-ai.app
Website: https://fud-ai.app
Phone: (leave blank)
```

## Store Listing URLs
```
Privacy Policy: https://fud-ai.app/privacy.html
Terms of Service: https://fud-ai.app/terms.html
Marketing: https://fud-ai.app
```

## Data Safety Form

Walk through the Play Console wizard with these answers:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS to all AI/STT providers) |
| Do you provide a way for users to request that their data be deleted? | **Yes** — Settings → Delete All Data wipes profile, food log, weight history, chat, and API keys |

**Why "No" on collection:** Google's policy excludes "data sent to a server based on a user's explicit action and where the user is informed about the destination." Users explicitly pick an AI provider, paste their own API key, and the destination is named on-screen — that's user-initiated transfer to a user-chosen third party, not collection by Fud AI. The app has no servers, no analytics, and never sees the food data.

If a reviewer questions this, point them at the privacy policy at https://fud-ai.app/privacy.html which already discloses the third-party AI provider model.

## App Content Declarations

| Question | Answer |
|---|---|
| Target audience age | 13+ (general — no age-restricted content) |
| Ads | No |
| In-app purchases | No |
| Government app | No |
| News app | No |
| COVID-19 contact tracing | No |
| Content rating questionnaire | Complete in console — answer "No" to all violence/gambling/sexual content questions; expect Everyone / PEGI 3 rating |

## Screenshots (8 required, 1080×2400 portrait)

Capture from device with:
```
adb exec-out screencap -p > shot1.png
```

Recommended order (one screen per shot, captioned in the asset itself):
1. Home — today's macro rings + recent meals
2. Snap a photo → AI returns calories
3. Voice input — live transcription
4. Coach chat — "What should I eat tonight?"
5. Progress — weight chart with trend line
6. Saved Meals — Recents / Frequent / Favorites
7. Settings — AI Provider picker (showing 13 options)
8. Widget on home screen — Calorie + Protein

## Feature Graphic (1024×500)

Required by Play Console. Pink-gradient (#FF375F → #FF6B8A) background, app logo center, tagline "AI Calorie Tracker — snap, speak, or type" or similar.

## App Icon

Already in `app/src/main/res/mipmap-*` directories. Play Console pulls it from the AAB; no separate upload needed.

## Reviewer Notes (Internal Testing → Production review)
```
1) Free and open source — no sign-in, no subscriptions, no ads.
2) Bring-your-own-API-key model. To test: Settings → AI Provider → enter any valid Google Gemini key. Free key at https://aistudio.google.com/apikey
3) API keys stored locally in EncryptedSharedPreferences (AES-256). Fud AI has no servers and collects no data.
4) Health Connect integration is opt-in (Settings → Health Connect). Without granting, the app works fully offline (just no Samsung Health / Fitbit import).
5) Voice input requires microphone permission, snap-a-meal requires camera permission, both prompted on first use.
6) Test device used in development: iQOO Z9 5G (OriginOS 6, Android 15).
```

## Build Artifact

Upload `~/Documents/fudai-release/fudai-v1.0.0.aab` to:
```
Play Console → Production → Create new release → Upload AAB
```

## After Submission

- Internal testing track first (instant rollout, you + opt-in testers)
- Promote to Closed → Open → Production once smoke-tested
- Production review usually 1–7 days

## Identity (mirror of root README)

- Website: https://fud-ai.app
- App Store (iOS): https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726
- Future Play Store URL: https://play.google.com/store/apps/details?id=com.apoorvdarshan.calorietracker
- Email: apoorv@fud-ai.app
- X: @apoorvdarshan
- Donations: https://paypal.me/apoorvdarshan
