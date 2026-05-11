# Play Store Listing

Google Play Console listing copy for Fud AI Android (current: v1.1.1 / versionCode 16). Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

**Where to paste each field in Play Console:**
- App name / Short description / Full description → Grow → Store presence → **Main store listing** (default English) and Grow → Store presence → **Custom store listings** → Manage translations (per-language overrides)
- What's new → **Releases → Production / Closed testing → Create new release → Release notes** field (paste the entire `<lang-tag>` block; Play Console parses tags automatically)

---

## 1. App Name

**30 char hard cap per language.** Brand name stays as `Fud AI` untranslated; the descriptor after the dash is what gets localized. English-only on Play Console — non-English Play Store browsers see the English source as fallback.

### English (en-US) — 24 chars
```
Fud AI - Calorie Tracker
```

---

## 2. Short Description

**80 char hard cap per language. Cannot include price/promotion keywords ("free", "discount", "sale", "best", "#1", etc.) — Play Console will block promotion of the listing.** Live Play Store currently has "Snap, speak, or type a meal. AI logs the calories. Free & open source." which triggers the warning; replacement below drops "Free" while keeping the same rhythm. English-only on Play Console — non-English Play Store browsers see the English source as fallback.

### English (en-US) — 63 chars
```
Snap, speak, or type a meal. AI logs the calories. Open source.
```

---

## 3. Full Description

**4000 char hard cap per language.** This is the long-form "About this app" copy. English-only on Play Console — non-English Play Store browsers see the English source as fallback (deliberate decision; the in-app UI is fully translated via per-locale `values-{lang}/strings.xml` so users still get a localized experience once installed).

### English (en-US)
```
Fud AI makes calorie tracking effortless with AI-powered food recognition. Snap a photo, speak it, or type it — get instant nutrition: calories, protein, carbs, fats, and 9 micronutrients.

NEW in v1.1.1: Add notes to photo-library meals, customize Home nutrient cards, set optional nutrient goals, estimate those goals with AI, and open Ko-fi from About.

Free, open source, privacy-first. Bring your own API key. All data stays on your device.

HOW TO USE
1) Set up your profile with goals + body stats
2) Snap, speak, type, or manually enter a meal — review and save
3) Ask Coach anything: trends, predictions, advice
4) Track progress on charts and home screen widgets

9 WAYS TO LOG A MEAL
• Photo — AI identifies the food and returns nutrition
• Photo + Note — add context before AI analysis
• Nutrition Label — scan package nutrition facts
• From Photos — analyze an existing image
• From Photos + Note — add context to a library photo
• Voice — 5 STT engines with per-provider language selection
• Text — describe in plain language, AI parses it
• Manual Entry — name + calories + macros + meal type, no AI needed
• Saved Meals — re-log recents, frequent meals, and favorites

BODY COMPOSITION TRACKING
Log body fat % over time, set a goal %, see it graphed alongside weight on the unified Progress chart. Health Connect sync auto-imports samples from Withings, Renpho, Samsung Health, Google Fit. "Use Body Fat for BMR" toggles Katch-McArdle ↔ Mifflin-St Jeor without losing the value.

13 AI PROVIDERS
Google Gemini, OpenAI, Anthropic Claude, xAI Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama (local), or any OpenAI-compatible endpoint. Switch anytime. OpenRouter defaults to a free vision model — test without loading credits. Keys stored encrypted (AES-256). Add Custom AI Instructions to send region, diet, or brand context with every request. Set a Fallback Provider so the app auto-retries on overload or rate-limit errors.

6 SPEECH-TO-TEXT ENGINES
Native Android, Gemini, OpenAI Whisper, Groq, Deepgram, AssemblyAI. Choose Provider Auto, Use Device Language, or a fixed language.

COACH (TOOL CALLING)
Multi-turn chat that sees your profile, weight, body fat, and food log. Ask "what was my weight in March?" or "how's my protein this week?" — Coach pulls the date range it needs via 5 on-demand tools. It now understands today's date/timezone and richer meal details. Goal-aware chips for Lose / Gain / Maintain.

SMART DAILY REMINDERS
Log Weight, Log Body Fat, Streak, Daily Summary — all skip firing on days you've already logged, so fully-tracking users get effectively zero pings.

PERSONALIZED GOALS
BMR via Katch-McArdle (with body fat) or Mifflin-St Jeor. TDEE with 6 activity levels. Auto-calculated calorie + protein + carbs + fat targets — fully customizable.

OPTIONAL NUTRIENT GOALS
Set fiber, sugar, saturated fat, cholesterol, sodium, and potassium goals separately from the macro calculator. Use AI Estimate to suggest values from your profile, or manually pick each goal with the same wheel picker used for macros. Home cards can show macros or selected detailed nutrients.

PROGRESS
Unified Weight / Body Fat chart with trend lines + goal overlays. Calorie trend vs goal. Macro averages over 1W, 1M, 3M, 6M, 1Y, All Time.

WIDGETS
Calorie widget (pink-gradient ring with today's calories + macros) and Protein widget — both in Small 2x2 and Medium 4x2, refresh the moment you log a meal.

SAVED MEALS + SEARCH
Recents, Frequent, and Favorites tabs. Search bar filters each tab separately — substring, case-insensitive, diacritic-insensitive.

15 LANGUAGES
Auto-selected by phone language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese, Hindi, Arabic, Romanian, Azerbaijani.

PRIVACY FIRST
No account, no sign-in, no cloud sync, no analytics, no ads, no tracking. Local-only. MIT licensed.

HEALTH CONNECT
Two-way sync for nutrition, weight, body fat. Macros + 9 micronutrients per meal. Edits and deletes sync back.

Built solo because tracking calories shouldn't feel like a chore. Reach out at apoorv@fud-ai.app, GitHub, or Instagram @fudai.app.

NOTE: Not medical advice. All nutritional estimates are AI-generated. Consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai
```

### Other 14 languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pt-BR, ro, ru-RU, zh-CN) see the English source as fallback. The in-app UI itself is fully translated into all 14 locales via per-locale `values-{lang}/strings.xml`, so the localization gap is only on the Play Store listing surface, not inside the app.

---

## 4. What's New (v1.1.1)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• Add notes to photo-library meals.
• Customize Home nutrient cards and set optional nutrient goals.
• AI can estimate fiber, sugar, sodium, potassium, and other goals.
</en-US>

<ar>
• أضف ملاحظات إلى صور الوجبات من المعرض.
• خصص بطاقات العناصر الغذائية وحدد أهدافًا اختيارية.
• يمكن للذكاء الاصطناعي تقدير أهداف الألياف والسكر والصوديوم.
</ar>

<az-AZ>
• Qalereyadan seçilən yeməklərə qeyd əlavə edin.
• Əsas nutrient kartlarını və əlavə hədəfləri dəyişin.
• AI lif, şəkər, natrium və digər hədəfləri təxmin edə bilər.
</az-AZ>

<de-DE>
• Notizen zu Fotos aus der Galerie hinzufügen.
• Home-Nährstoffkarten und optionale Ziele anpassen.
• KI schätzt Ziele für Ballaststoffe, Zucker, Natrium und mehr.
</de-DE>

<es-ES>
• Añade notas a fotos de comidas de la galería.
• Personaliza tarjetas de nutrientes y objetivos opcionales.
• La IA estima fibra, azúcar, sodio y más.
</es-ES>

<fr-FR>
• Ajoutez des notes aux photos de repas.
• Personnalisez les cartes nutriments et objectifs optionnels.
• L’IA estime fibres, sucre, sodium et plus.
</fr-FR>

<hi-IN>
• Gallery photos पर meal notes जोड़ें.
• Home nutrient cards और optional goals customize करें.
• AI fiber, sugar, sodium और बाकी goals estimate कर सकता है.
</hi-IN>

<it-IT>
• Aggiungi note alle foto dei pasti.
• Personalizza le schede nutrienti e gli obiettivi opzionali.
• L’IA stima fibre, zucchero, sodio e altro.
</it-IT>

<ja-JP>
• 写真ライブラリの食事にメモを追加できます。
• ホームの栄養カードと任意目標をカスタマイズ。
• AIが食物繊維、糖分、ナトリウムなどを推定。
</ja-JP>

<ko-KR>
• 사진 보관함 음식에 메모를 추가하세요.
• 홈 영양 카드와 선택 영양 목표를 설정하세요.
• AI가 섬유질, 당, 나트륨 등을 추정합니다.
</ko-KR>

<nl-NL>
• Voeg notities toe aan maaltijdfoto’s.
• Pas Home-nutriëntkaarten en optionele doelen aan.
• AI schat vezels, suiker, natrium en meer.
</nl-NL>

<pt-BR>
• Adicione notas a fotos de refeições.
• Personalize cartões de nutrientes e metas opcionais.
• A IA estima fibra, açúcar, sódio e mais.
</pt-BR>

<ro>
• Adaugă note la fotografiile meselor.
• Personalizează cardurile de nutrienți și obiectivele opționale.
• AI estimează fibre, zahăr, sodiu și altele.
</ro>

<ru-RU>
• Добавляйте заметки к фото еды из галереи.
• Настройте карточки нутриентов и доп. цели.
• ИИ оценит клетчатку, сахар, натрий и другое.
</ru-RU>

<zh-CN>
• 为相册中的餐食照片添加备注。
• 自定义首页营养卡片和可选营养目标。
• AI 可估算纤维、糖、钠等目标。
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
