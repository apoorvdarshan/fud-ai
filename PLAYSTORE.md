# Play Store Listing

Google Play Console listing copy for Fud AI Android (current: v1.2 / versionCode 18). Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

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
Fud AI makes calorie tracking effortless with AI-powered food recognition. Snap a photo, scan a barcode, speak it, or type it — get instant nutrition: calories, protein, carbs, fats, and 9 micronutrients.

NEW in v1.2: Barcode logging on Android, Copy from Day, editable food date/time, Coach photo/camera attachments, and Gemini Flash Lite GA.

Free, open source, privacy-first. Bring your own API key. All data stays on your device.

HOW TO USE
1) Set up your profile with goals + body stats
2) Snap, scan, speak, type, or manually enter a meal — review and save
3) Ask Coach anything: trends, predictions, advice
4) Track progress on charts and home screen widgets

9 WAYS TO LOG A MEAL
• Photo — AI identifies food and returns nutrition
• Photo + Note — add context before AI analysis
• Nutrition Label — scan package nutrition facts
• Barcode — look up packaged foods with Open Food Facts
• From Photos — analyze an existing image
• From Photos + Note — add context to a library photo
• Voice — 5 STT engines with language selection
• Text — describe in plain language, AI parses it
• Manual Entry — name + calories + macros + meal type
• Saved Meals — re-log recents, frequent meals, and favorites
• Copy from Day — copy meals from another date

BODY COMPOSITION TRACKING
Log body fat % over time, set a goal %, and see it alongside weight on the unified Progress chart. Health Connect can auto-import samples from Withings, Renpho, Samsung Health, Google Fit, and more. "Use Body Fat for BMR" toggles Katch-McArdle ↔ Mifflin-St Jeor without losing the value.

13 AI PROVIDERS
Google Gemini, OpenAI, Anthropic Claude, xAI Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama, or any OpenAI-compatible endpoint. Switch anytime. Keys are stored encrypted. Add Custom AI Instructions for region, diet, or brand context. Set a Fallback Provider so requests retry on overload or rate-limit errors.

6 SPEECH-TO-TEXT ENGINES
Native Android, Gemini, OpenAI Whisper, Groq, Deepgram, AssemblyAI. Choose Provider Auto, Use Device Language, or a fixed language.

COACH
Multi-turn chat that sees your profile, weight, body fat, and food log. Ask "what was my weight in March?" or "how's my protein this week?" — Coach pulls the date range it needs via on-demand tools. You can also attach a camera photo or photo-library image to a Coach message.

SMART DAILY REMINDERS
Log Weight, Log Body Fat, Streak, and Daily Summary reminders skip firing on days you've already logged.

PERSONALIZED GOALS
BMR via Katch-McArdle or Mifflin-St Jeor. TDEE with 6 activity levels. Auto-calculated calorie + protein + carbs + fat targets — fully customizable.

OPTIONAL NUTRIENT GOALS
Set fiber, sugar, saturated fat, cholesterol, sodium, and potassium goals separately from the macro calculator. Use AI Estimate from your profile, or set goals manually. Home cards can show macros or selected detailed nutrients.

PROGRESS
Unified Weight / Body Fat chart with trend lines + goal overlays. Calorie trend vs goal. Macro averages over 1W, 1M, 3M, 6M, 1Y, All Time.

WIDGETS
Calorie and Protein widgets refresh when you log a meal.

SAVED MEALS + SEARCH
Recents, Frequent, and Favorites tabs. Search filters each tab separately.

15 LANGUAGES
Auto-selected by phone language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese, Hindi, Arabic, Romanian, Azerbaijani.

PRIVACY FIRST
No account, no sign-in, no cloud sync, no analytics, no ads, no tracking. Local-only. MIT licensed.

HEALTH CONNECT
Two-way sync for nutrition, weight, body fat. Macros + 9 micronutrients per meal. Edits and deletes sync back.

NOTE: Not medical advice. All nutritional estimates are AI-generated. Consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

### Other 14 languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pt-BR, ro, ru-RU, zh-CN) see the English source as fallback. The in-app UI itself is fully translated into all 14 locales via per-locale `values-{lang}/strings.xml`, so the localization gap is only on the Play Store listing surface, not inside the app.

---

## 4. What's New (v1.2)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• Barcode logging for packaged foods with Open Food Facts.
• Copy meals from another date.
• Edit a food's logged date and time.
• Attach camera or photo-library images in Coach.
• Gemini Flash Lite moved to the GA model.
</en-US>

<ar>
• تسجيل الباركود للأطعمة المعلبة عبر Open Food Facts.
• انسخ الوجبات من يوم آخر.
• عدّل تاريخ ووقت تسجيل الطعام.
• أرفق صور الكاميرا أو المعرض في Coach.
• تحديث Gemini Flash Lite إلى إصدار GA.
</ar>

<az-AZ>
• Paketli qidalar üçün Open Food Facts barkod qeydi.
• Yeməkləri başqa gündən kopyalayın.
• Yeməyin qeyd tarixini və vaxtını redaktə edin.
• Coach-da kamera və qalereya şəkli əlavə edin.
• Gemini Flash Lite GA modelinə keçirildi.
</az-AZ>

<de-DE>
• Barcode-Logging mit Open Food Facts.
• Mahlzeiten von einem anderen Tag kopieren.
• Datum und Uhrzeit eines Lebensmittels bearbeiten.
• Kamera- oder Galerie-Bilder in Coach anhängen.
• Gemini Flash Lite nutzt jetzt das GA-Modell.
</de-DE>

<es-ES>
• Registro por código de barras con Open Food Facts.
• Copia comidas desde otro día.
• Edita fecha y hora de un alimento.
• Adjunta fotos de cámara o galería en Coach.
• Gemini Flash Lite pasa al modelo GA.
</es-ES>

<fr-FR>
• Journal par code-barres avec Open Food Facts.
• Copiez des repas depuis un autre jour.
• Modifiez la date et l'heure d'un aliment.
• Ajoutez des photos caméra/galerie dans Coach.
• Gemini Flash Lite passe au modèle GA.
</fr-FR>

<hi-IN>
• Open Food Facts से barcode food logging.
• किसी और दिन से meals copy करें.
• Food की logged date और time edit करें.
• Coach में camera या gallery image attach करें.
• Gemini Flash Lite अब GA model पर है.
</hi-IN>

<it-IT>
• Log con codice a barre tramite Open Food Facts.
• Copia pasti da un altro giorno.
• Modifica data e ora di un alimento.
• Allega foto da camera o galleria in Coach.
• Gemini Flash Lite ora usa il modello GA.
</it-IT>

<ja-JP>
• Open Food Factsでバーコード記録。
• 別の日から食事をコピーできます。
• 食品の記録日と時刻を編集できます。
• Coachにカメラ/写真を添付できます。
• Gemini Flash LiteをGAモデルに更新。
</ja-JP>

<ko-KR>
• Open Food Facts 바코드 식품 기록.
• 다른 날짜의 식사를 복사하세요.
• 음식의 기록 날짜와 시간을 편집하세요.
• Coach에 카메라/사진을 첨부하세요.
• Gemini Flash Lite가 GA 모델로 전환되었습니다.
</ko-KR>

<nl-NL>
• Barcode-loggen met Open Food Facts.
• Kopieer maaltijden van een andere dag.
• Bewerk datum en tijd van een maaltijd.
• Voeg camera- of galerijbeelden toe in Coach.
• Gemini Flash Lite gebruikt nu het GA-model.
</nl-NL>

<pt-BR>
• Registro por código de barras com Open Food Facts.
• Copie refeições de outro dia.
• Edite data e hora de um alimento.
• Anexe fotos da câmera ou galeria no Coach.
• Gemini Flash Lite agora usa o modelo GA.
</pt-BR>

<ro>
• Logare prin cod de bare cu Open Food Facts.
• Copiază mese din altă zi.
• Editează data și ora unui aliment.
• Atașează poze din cameră sau galerie în Coach.
• Gemini Flash Lite folosește acum modelul GA.
</ro>

<ru-RU>
• Запись по штрихкоду через Open Food Facts.
• Копируйте еду из другого дня.
• Меняйте дату и время записи еды.
• Прикрепляйте фото с камеры или галереи в Coach.
• Gemini Flash Lite переведен на GA-модель.
</ru-RU>

<zh-CN>
• 使用 Open Food Facts 条码记录包装食品。
• 从其他日期复制餐食。
• 编辑食物的记录日期和时间。
• 在 Coach 中附加相机或相册图片。
• Gemini Flash Lite 已切换到 GA 模型。
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
