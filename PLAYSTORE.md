# Play Store Listing

Google Play Console listing copy for Fud AI Android (current: v2.0.0 / versionCode 19). Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

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
Fud AI makes calorie tracking effortless with AI-powered food recognition. Snap a photo, scan a barcode, speak it, or type it — get instant nutrition: calories, protein, carbs, fats, vitamins, minerals, and more.

NEW in v2.0.0: A major Android UI refresh, Camera + Camera logging, in-app camera capture, Barcode, expanded nutrient goals, Coach image attachments, better Health Connect sync, safer swipes, cached thumbnails, and onboarding layout fixes.

Free, open source, privacy-first. Bring your own API key. All data stays on your device.

HOW TO USE
1) Set up your profile with goals + body stats
2) Snap, scan, speak, type, or manually enter a meal — review and save
3) Ask Coach anything: trends, predictions, advice
4) Track progress on charts and home screen widgets

11 WAYS TO LOG A MEAL
• Photo — AI identifies food and returns nutrition
• Photo + Note — add context before AI analysis
• Photo + Photo — combine two images in one analysis
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
Log body fat % over time, set a goal %, and see it alongside weight on the unified Progress chart. Health Connect can auto-import samples from compatible apps and scales.

13 AI PROVIDERS
Google Gemini, OpenAI, Anthropic Claude, xAI Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama, or any OpenAI-compatible endpoint. Switch anytime. Keys are stored encrypted.

6 SPEECH-TO-TEXT ENGINES
Native Android, Gemini, OpenAI Whisper, Groq, Deepgram, AssemblyAI. Choose Provider Auto, Use Device Language, or a fixed language.

COACH
Multi-turn chat that sees your profile, weight, body fat, and food log. Ask "what was my weight in March?" or "how's my protein this week?" — Coach pulls the date range it needs via on-demand tools. You can also attach a camera photo or photo-library image to a Coach message.

PERSONALIZED GOALS
BMR via Katch-McArdle or Mifflin-St Jeor. TDEE with 6 activity levels. Auto-calculated calorie + protein + carbs + fat targets — fully customizable.

OPTIONAL NUTRIENT GOALS
Set expanded nutrient goals separately from the macro calculator: fiber, sugar, fats, cholesterol, sodium, potassium, calcium, iron, magnesium, zinc, vitamins, folate, omega-3, and more when available. Use AI Estimate from your profile, or set goals manually. Home cards can show macros or selected detailed nutrients.

PROGRESS
Unified Weight / Body Fat chart with trend lines + goal overlays. Calorie trend vs goal. Macro averages over 1W, 1M, 3M, 6M, 1Y, All Time.

WIDGETS
Calorie and nutrient widgets refresh when you log a meal.

15 LANGUAGES
Auto-selected by phone language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese, Hindi, Arabic, Romanian, Azerbaijani.

PRIVACY FIRST
No account, no sign-in, no cloud sync, no analytics, no ads, no tracking. Local-only. MIT licensed.

HEALTH CONNECT
Sync nutrition, weight, and body fat with permission reconciliation and backfill support. Edits and deletes sync back where supported.

NOTE: Not medical advice. All nutritional estimates are AI-generated. Consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

### Other 14 languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pt-BR, ro, ru-RU, zh-CN) see the English source as fallback. The in-app UI itself is fully translated into all 14 locales via per-locale `values-{lang}/strings.xml`, so the localization gap is only on the Play Store listing surface, not inside the app.

---

## 4. What's New (v2.0.0)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• Major Android UI refresh with cleaner menus, sheets, dialogs, and light mode.
• Added Barcode, Camera + Camera, and in-app camera capture.
• Expanded optional nutrients, goals, and Home cards.
• Improved Health Connect sync, safer swipes, saved meals, drafts, thumbnails, and onboarding.
</en-US>

<ar>
• تحديث كبير لواجهة Android مع قوائم ونوافذ أوضح.
• إضافة الباركود وCamera + Camera والكاميرا داخل التطبيق.
• توسيع المغذيات الاختيارية والأهداف وبطاقات Home.
• تحسين Health Connect والسحب الآمن والوجبات المحفوظة والمسودات والصور المصغرة.
</ar>

<az-AZ>
• Daha təmiz menyular, pəncərələr və işıq rejimi ilə böyük Android UI yeniləməsi.
• Barkod, Camera + Camera və tətbiqdaxili kamera əlavə edildi.
• Əlavə qida hədəfləri və Home kartları genişləndirildi.
• Health Connect, swipe təhlükəsizliyi, saxlanmış yeməklər, qaralamalar və miniatürlər yaxşılaşdırıldı.
</az-AZ>

<de-DE>
• Großes Android-UI-Update mit saubereren Menüs, Sheets, Dialogen und Light Mode.
• Barcode, Camera + Camera und In-App-Kamera hinzugefügt.
• Erweiterte Nährstoffe, Ziele und Home-Karten.
• Health Connect, sichereres Wischen, gespeicherte Mahlzeiten, Entwürfe und Thumbnails verbessert.
</de-DE>

<es-ES>
• Gran renovación de Android con menús, hojas, diálogos y modo claro más limpios.
• Añadidos Barcode, Camera + Camera y cámara integrada.
• Nutrientes, objetivos y tarjetas de Home ampliados.
• Mejoras en Health Connect, gestos, comidas guardadas, borradores y miniaturas.
</es-ES>

<fr-FR>
• Grande refonte Android avec menus, feuilles, dialogues et mode clair plus propres.
• Ajout de Barcode, Camera + Camera et caméra intégrée.
• Nutriments, objectifs et cartes Home élargis.
• Health Connect, gestes, repas enregistrés, brouillons et miniatures améliorés.
</fr-FR>

<hi-IN>
• Android UI refresh: cleaner menus, sheets, dialogs और light mode.
• Barcode, Camera + Camera और in-app camera जोड़ा गया.
• Optional nutrients, goals और Home cards बढ़ाए गए.
• Health Connect, safer swipes, saved meals, drafts, thumbnails और onboarding बेहतर.
</hi-IN>

<it-IT>
• Grande refresh Android con menu, sheet, dialoghi e modalità chiara più puliti.
• Aggiunti Barcode, Camera + Camera e fotocamera in-app.
• Nutrienti opzionali, obiettivi e carte Home ampliati.
• Migliorati Health Connect, swipe, pasti salvati, bozze e miniature.
</it-IT>

<ja-JP>
• Android UIを大幅刷新。メニュー、シート、ダイアログ、ライトモードを改善。
• Barcode、Camera + Camera、アプリ内カメラを追加。
• 任意栄養素、目標、Homeカードを拡張。
• Health Connect、スワイプ、保存済み食事、下書き、サムネイルを改善。
</ja-JP>

<ko-KR>
• Android UI 대폭 개선: 메뉴, 시트, 대화상자, 라이트 모드 정리.
• Barcode, Camera + Camera, 앱 내 카메라 추가.
• 선택 영양소, 목표, Home 카드 확장.
• Health Connect, 스와이프, 저장된 식사, 초안, 썸네일 개선.
</ko-KR>

<nl-NL>
• Grote Android UI-refresh met schonere menu's, sheets, dialogen en lichte modus.
• Barcode, Camera + Camera en in-app camera toegevoegd.
• Uitgebreide nutriënten, doelen en Home-kaarten.
• Health Connect, swipes, opgeslagen maaltijden, concepten en thumbnails verbeterd.
</nl-NL>

<pt-BR>
• Grande atualização visual no Android com menus, folhas, diálogos e modo claro melhores.
• Adicionados Barcode, Camera + Camera e câmera no app.
• Nutrientes, metas e cartões Home expandidos.
• Health Connect, gestos, refeições salvas, rascunhos e miniaturas melhorados.
</pt-BR>

<ro>
• Refresh major Android cu meniuri, panouri, dialoguri și mod luminos mai curate.
• Adăugate Barcode, Camera + Camera și cameră în aplicație.
• Nutrienți opționali, obiective și carduri Home extinse.
• Health Connect, swipe-uri, mese salvate, ciorne și miniaturi îmbunătățite.
</ro>

<ru-RU>
• Большое обновление Android UI: чище меню, панели, диалоги и светлая тема.
• Добавлены Barcode, Camera + Camera и камера внутри приложения.
• Расширены нутриенты, цели и Home-карточки.
• Улучшены Health Connect, свайпы, сохраненные блюда, черновики и миниатюры.
</ru-RU>

<zh-CN>
• Android 界面大更新：菜单、弹窗、对话框和浅色模式更清爽。
• 新增 Barcode、Camera + Camera 和应用内相机。
• 扩展可选营养素、目标和 Home 卡片。
• 改进 Health Connect、滑动、保存餐食、草稿、缩略图和引导流程。
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
