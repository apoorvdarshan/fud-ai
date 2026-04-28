# Play Store Listing

Google Play Console listing copy for Fud AI Android (current: v1.0.5 / versionCode 6). Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

**Where to paste each field in Play Console:**
- App name / Short description / Full description → Grow → Store presence → **Main store listing** (default English) and Grow → Store presence → **Custom store listings** → Manage translations (per-language overrides)
- What's new → **Releases → Production / Closed testing → Create new release → Release notes** field (paste the entire `<lang-tag>` block; Play Console parses tags automatically)

---

## 1. App Name

**30 char hard cap per language.** Brand name stays as `Fud AI` untranslated; the descriptor after the dash is what gets localized. Per-language translations handled by Play Console's free **Machine translation** service (Grow → Store presence → Translations service → Machine translation) — paste the English source here, run translate, all 13 supported locales auto-fill.

### English (en-US) — 24 chars
```
Fud AI - Calorie Tracker
```

---

## 2. Short Description

**80 char hard cap per language. Cannot include price/promotion keywords ("free", "discount", "sale", "best", "#1", etc.) — Play Console will block promotion of the listing.** Live Play Store currently has "Snap, speak, or type a meal. AI logs the calories. Free & open source." which triggers the warning; replacement below drops "Free" while keeping the same rhythm. Per-language translations handled by Play Console's free Machine translation service same as App Name.

### English (en-US) — 63 chars
```
Snap, speak, or type a meal. AI logs the calories. Open source.
```

---

## 3. Full Description

**4000 char hard cap per language.** This is the long-form "About this app" copy. Currently maintained in English only on Play Console — if you want to translate into the other 14 languages, request a translation pass (3000+ chars × 14 langs = ~45k chars of content, deliberate decision because most users see the English fallback anyway).

### English (en-US)
```
Fud AI makes calorie tracking effortless with AI-powered food recognition. Snap a photo, speak it, or type it — get instant nutrition: calories, protein, carbs, fats, and 9 micronutrients.

NEW: Body fat tracking with goal + history + Health Connect sync, Coach reaches your full history on demand, smart daily reminders that skip days you've already logged, search across Saved Meals.

Coach: multi-turn AI chat that sees your profile, weight history, body fat history, and full food log. Ask anything in plain English — "what was my weight in March?", "how's my protein this week?", "body fat trend over the last 60 days?".

Fud AI is free, open source, privacy-first. Bring your own API key. All data stays on your device.

HOW TO USE
1) Set up your profile with goals + body stats
2) Snap, speak, type, or manually enter a meal — review and save
3) Ask Coach anything: trends, predictions, advice
4) Track progress on charts and home screen widgets

4 WAYS TO LOG A MEAL
• Photo — AI identifies the food and returns nutrition
• Voice — 5 STT engines (native Android or remote)
• Text — describe in plain language, AI parses it
• Manual Entry — name + calories + macros + meal type, no AI needed

BODY COMPOSITION TRACKING
Log body fat % over time, set a goal %, see it graphed alongside weight on the unified Progress chart (segmented toggle + swipe to switch). Bidirectional Health Connect sync — Withings, Renpho, Eufy, Samsung Health, Google Fit auto-import. "Use Body Fat for BMR" toggle flips between Katch-McArdle and Mifflin-St Jeor without losing the value.

13 AI PROVIDERS
Google Gemini, OpenAI, Anthropic Claude, xAI Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama (local), or any OpenAI-compatible endpoint. Switch anytime. OpenRouter now defaults to a free vision model — test without loading credits. Keys stored encrypted (AES-256).

5 SPEECH-TO-TEXT ENGINES
Native Android, OpenAI Whisper, Groq, Deepgram, AssemblyAI.

COACH (TOOL CALLING)
Multi-turn chat with on-demand access to your full history via 5 tools: weight history, body fat history, calorie totals, food entries, data summary — all date-range aware. Goal-aware chips for Lose / Gain / Maintain.

SMART DAILY REMINDERS
Log Weight, Log Body Fat, Streak, Daily Summary — all skip firing on days you've already logged the metric, so fully-tracking users get effectively zero pings.

PERSONALIZED GOALS
BMR via Katch-McArdle (with body fat) or Mifflin-St Jeor. TDEE with 6 activity levels. Auto-calculated calorie + protein + carbs + fat targets — fully customizable.

PROGRESS
Unified Weight / Body Fat chart with trend lines and goal overlays. Calorie trend vs goal. Macro averages over 1W, 1M, 3M, 6M, 1Y, All Time.

WIDGETS
Calorie widget (pink-gradient ring with today's calories + macros) and Protein widget — both in Small 2x2 and Medium 4x2, refresh the moment you log a meal.

SAVED MEALS + SEARCH
Recents, Frequent, and Favorites tabs. Search bar filters each tab separately — substring, case-insensitive, diacritic-insensitive.

15 LANGUAGES
Auto-selected by phone language. English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese (Simplified), Hindi, Arabic, Romanian, Azerbaijani.

PRIVACY FIRST
No account, no sign-in, no cloud sync, no analytics, no ads, no tracking. Local-only. MIT licensed.

HEALTH CONNECT
Two-way sync for nutrition, weight, body fat. Macros + 9 micronutrients written per meal. Edits and deletes sync back.

I built Fud AI because tracking calories shouldn't feel like a chore. I want to make healthy eating simple for everyone. Reach out at apoorv@fud-ai.app or open an issue on GitHub.

NOTE: Fud AI does not offer medical advice. All nutritional estimates are AI-generated suggestions only. Please consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai
```

### Other 13 languages
Handled by Play Console's free **Machine translation** service (Grow → Store presence → Translations service → Machine translation → translate from en-US into the 13 supported locales: ar, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pt-BR, ro, ru-RU, zh-CN). Re-run the translation whenever the English source changes (every Android version bump that touches the Full Description). Azerbaijani is intentionally skipped because Play's machine translation doesn't support az-AZ — Azerbaijani users will see the English Play Store listing but the in-app UI is fully translated via values-az/strings.xml.

---

## 4. What's New (v1.0.5)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• OpenRouter now defaults to a free vision model so you can test the integration without loading credits.
• Fewer "Could not understand the AI response" errors — better tolerance for AI responses with prose or markdown around the JSON.
</en-US>

<ar>
• يستخدم OpenRouter الآن نموذج رؤية مجاني افتراضيًا حتى تتمكن من تجربة التكامل دون شحن الرصيد.
• عدد أقل من أخطاء "تعذّر فهم استجابة الذكاء الاصطناعي" — تحسين تحمّل الاستجابات التي تحتوي على نص أو تنسيق Markdown حول JSON.
</ar>

<az-AZ>
• OpenRouter indi standart olaraq pulsuz bir görmə modelindən istifadə edir, beləliklə kredit yükləmədən inteqrasiyanı sınaqdan keçirə bilərsiniz.
• Daha az "Süni intellekt cavabını başa düşmək mümkün olmadı" xətası — JSON ətrafında mətn və ya Markdown olan AI cavablarına daha yaxşı uyğunluq.
</az-AZ>

<de-DE>
• OpenRouter verwendet jetzt standardmäßig ein kostenloses Vision-Modell, sodass du die Integration testen kannst, ohne Guthaben aufzuladen.
• Weniger „KI-Antwort konnte nicht verstanden werden"-Fehler — bessere Verarbeitung von KI-Antworten mit Text oder Markdown um das JSON.
</de-DE>

<es-ES>
• OpenRouter ahora usa un modelo de visión gratuito por defecto, para que puedas probar la integración sin cargar créditos.
• Menos errores de "No se pudo entender la respuesta de la IA" — mejor tolerancia para respuestas con texto o markdown alrededor del JSON.
</es-ES>

<fr-FR>
• OpenRouter utilise désormais par défaut un modèle de vision gratuit pour tester l'intégration sans recharger de crédits.
• Moins d'erreurs « Impossible de comprendre la réponse de l'IA » — meilleure tolérance pour les réponses contenant du texte ou du markdown autour du JSON.
</fr-FR>

<hi-IN>
• OpenRouter अब डिफ़ॉल्ट रूप से एक मुफ़्त विज़न मॉडल का उपयोग करता है, जिससे आप क्रेडिट लोड किए बिना इंटीग्रेशन टेस्ट कर सकते हैं।
• "AI प्रतिक्रिया समझ नहीं आई" त्रुटियाँ कम — JSON के आसपास टेक्स्ट या मार्कडाउन वाली AI प्रतिक्रियाओं के लिए बेहतर सहनशीलता।
</hi-IN>

<it-IT>
• OpenRouter ora utilizza per impostazione predefinita un modello di visione gratuito, così puoi testare l'integrazione senza caricare crediti.
• Meno errori "Impossibile comprendere la risposta dell'IA" — migliore tolleranza per le risposte AI con testo o markdown attorno al JSON.
</it-IT>

<ja-JP>
• OpenRouterはデフォルトで無料のビジョンモデルを使用するようになり、クレジットをチャージせずに統合をテストできます。
• 「AIの応答を理解できませんでした」エラーが減少 — JSONの周りに文章やMarkdownが含まれるAI応答にもより柔軟に対応。
</ja-JP>

<ko-KR>
• 이제 OpenRouter가 기본적으로 무료 비전 모델을 사용하므로 크레딧을 충전하지 않고도 통합을 테스트할 수 있습니다.
• "AI 응답을 이해할 수 없습니다" 오류 감소 — JSON 주변에 텍스트나 마크다운이 있는 AI 응답을 더 잘 처리합니다.
</ko-KR>

<nl-NL>
• OpenRouter gebruikt nu standaard een gratis visiemodel, zodat je de integratie kunt testen zonder credits op te laden.
• Minder "Kan het AI-antwoord niet begrijpen"-fouten — betere verwerking van AI-antwoorden met tekst of markdown rond de JSON.
</nl-NL>

<pt-BR>
• O OpenRouter agora usa por padrão um modelo de visão gratuito, para que você possa testar a integração sem carregar créditos.
• Menos erros de "Não foi possível entender a resposta da IA" — melhor tolerância a respostas com texto ou markdown ao redor do JSON.
</pt-BR>

<ro>
• OpenRouter folosește acum implicit un model de viziune gratuit, astfel încât poți testa integrarea fără a încărca credite.
• Mai puține erori „Răspunsul AI nu a putut fi înțeles" — toleranță îmbunătățită pentru răspunsuri AI cu text sau markdown în jurul JSON-ului.
</ro>

<ru-RU>
• OpenRouter теперь по умолчанию использует бесплатную модель распознавания изображений, так что вы можете протестировать интеграцию без пополнения баланса.
• Меньше ошибок «Не удалось понять ответ ИИ» — улучшенная обработка ответов ИИ с текстом или Markdown вокруг JSON.
</ru-RU>

<zh-CN>
• OpenRouter 现在默认使用免费的视觉模型，您可以在不充值的情况下测试集成。
• "无法理解 AI 响应"错误减少 — 对包含文本或 Markdown 的 AI 响应（JSON 前后）有更好的兼容性。
</zh-CN>
```

---

## 5. Categorization

```
App category: Health & Fitness
Tags: Calorie tracker, Nutrition, AI, Food tracker
```

## 6. Contact details

```
Email: apoorv@fud-ai.app
Phone: (omit — optional, US-only enforcement)
Website: https://fud-ai.app
Privacy policy: https://fud-ai.app/privacy.html
```

## 7. App content declarations

These are one-time setup in Play Console → Policy → App content. Don't drift from these answers across submissions:

- **Privacy policy URL**: https://fud-ai.app/privacy.html
- **App access**: All functionality available without restrictions
- **Ads**: No
- **Content rating**: Everyone (E)
- **Target audience**: 13+
- **News app**: No
- **COVID-19 contact tracing**: No
- **Data safety**: All processing on-device. No data collected/shared. API keys stored in EncryptedSharedPreferences. Encryption in transit when calling AI provider APIs (HTTPS). User can request deletion via in-app "Delete All Data" — no server data exists.
- **Government app**: No
- **Financial features**: No
- **Health features**: Yes — fitness/nutrition tracking. Local-only.
