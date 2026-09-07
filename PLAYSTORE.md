# Play Store Listing

Google Play Console listing copy for Fud AI Android v6.1 / versionCode 34. Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

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

NEW in v6.1: progressive multi-photo analysis follows ingredients added to one plate. Meals include editable ingredient breakdowns and serving fields include a calculator. You can also import diary JSON, configure app shortcuts, inspect exact weight-chart values, and preview exercises.

Nutrition adds custom goals, caffeine, creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB. Optional fasting adds persistent timers, editable history, goals, alerts, and Coach context. Android also adds a measured daily calorie summary and clearer Health Connect guidance.

Open source, privacy-first. Bring your own API key.

WAYS TO LOG A MEAL
• Camera — take up to 10 photos, add an optional note
• Photos — import up to 10 images, add an optional note
• Barcode — Open Food Facts lookup
• Voice — 6 STT engines
• Text — describe it, AI parses it
• Manual Entry
• Saved Meals — recents, frequent, favorites
• Copy from Day — copy meals from another date

AI PROVIDERS
Use Gemini, OpenAI, Claude, Grok, Groq, OpenRouter, Together, Hugging Face, Fireworks, DeepInfra, Mistral, Ollama, or an OpenAI-compatible endpoint.

6 SPEECH-TO-TEXT ENGINES
Native Android, Gemini, OpenAI Whisper, Groq, Deepgram, or AssemblyAI, with automatic or fixed language handling.

COACH
Multi-turn chat can access your profile, goals, food log, progress, workouts, and explicitly logged fasts when requested. It never assumes a missing meal means you fasted. Images are supported.

REVIEW BEFORE LOGGING
Unlock Nutrition to correct calories, macros, and detailed nutrients before saving; serving changes then scale from your edits. What if? previews today's macro impact and can ask AI for a suggestion.

WORKOUTS
Plan by day; log sets, reps, weight, and RPE. Browse 873 exercises with photos, filters, search, sorting, and details.

PERSONALIZED GOALS
BMR and TDEE calculators, six activity levels, automatic or editable macro targets, and customizable meal-time boundaries.

OPTIONAL NUTRIENT GOALS
Set expanded nutrient goals separately from the macro calculator — fiber, sugar, fats, sodium, vitamins, minerals, and more. Use AI Estimate or set them manually. Home cards can show macros or selected nutrients.

WIDGETS
Separate Calorie, Protein, Today, and Water widgets in the Home speedometer style. They refresh from local snapshots when you log.

OPTIONAL WATER TRACKING
Off by default. Set your own daily goal, quick-log one to three glasses or a custom amount, view progress below calories, schedule a local reminder, and use the dedicated Water widget. Water history stays on your device and is not sent to Health Connect.

OPTIONAL FASTING TRACKING
Off by default. Choose a 1–168 hour goal, start/end/cancel from the Home + menu, keep the timer across app restarts, optionally receive a local goal alert, and edit completed sessions. Fasting stays on your device and is not sent to Health Connect.

15 LANGUAGES
Auto-selected by phone language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese, Hindi, Arabic, Romanian, Azerbaijani, Polish.

PRIVACY FIRST
Core tracking needs no account and Fud AI has no analytics, behavioral tracking, or ads. The optional 18+ Weekly Challenge creates a first-party challenge profile and uploads only a display name, optional single social handle, anonymous participant ID, and weekly aggregate milestones—never raw food, weight, meal, workout, or Health Connect records. Android backup may apply under system settings. Keys are encrypted; AI/STT requests go directly to your provider. MIT licensed.

HEALTH CONNECT
Optional sync for nutrition, weight, body fat, and calculated workout calories, plus energy reads for goal estimates. Records can restore from Health Connect after reinstall. Fasting is local-only.

NOTE: Not medical advice. Estimates are AI-generated; consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

### Other 14 languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pt-BR, ro, ru-RU, zh-CN) see the English source as fallback. The app includes 14 localized interfaces; newer strings may temporarily use the English fallback.

---

## 4. What's New (v6.1 / versionCode 34)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• Progressive multi-photo meals, editable ingredient breakdowns, fiber entry, and a serving calculator.
• Custom nutrient goals plus caffeine, creatine, and other performance compounds.
• Drag across weight charts, preview exercises, import diary JSON, and configure app shortcuts.
• Optional fasting with persistent timers and editable history.
• Android adds measured daily calorie summaries, clearer Health Connect guidance, and smarter reminder behavior.
</en-US>

<ar>
• تحليل تدريجي لصور الوجبات، مكونات قابلة للتعديل، حاسبة حصص، وأهداف موسعة للعناصر الغذائية.
• فحص تفاعلي للرسوم، معاينة التمارين، استيراد سجل JSON، واختصارات قابلة للتخصيص.
• صيام اختياري، وملخص يومي للسعرات، وإرشادات أوضح لـ Health Connect.
</ar>

<az-AZ>
• Proqressiv çoxşəkilli yeməklər, redaktə olunan inqrediyentlər, porsiya kalkulyatoru və geniş qida məqsədləri.
• İnteraktiv qrafiklər, məşq önizləməsi, JSON gündəlik idxalı və fərdiləşən qısayollar.
• İstəyə bağlı oruc, gündəlik kalori xülasəsi və daha aydın Health Connect dəstəyi.
</az-AZ>

<de-DE>
• Progressive Mehrfoto-Mahlzeiten, bearbeitbare Zutaten, Portionsrechner und erweiterte Nährstoffziele.
• Interaktive Diagramme, Übungsvorschau, JSON-Tagebuchimport und anpassbare App-Kurzbefehle.
• Optionales Fasten, tägliche Kalorienbilanz und klarere Health-Connect-Hinweise.
</de-DE>

<es-ES>
• Comidas progresivas con varias fotos, ingredientes editables, calculadora de porciones y objetivos nutricionales ampliados.
• Gráficos interactivos, vista previa de ejercicios, importación JSON y accesos directos configurables.
• Ayuno opcional, resumen calórico diario y mejores indicaciones de Health Connect.
</es-ES>

<fr-FR>
• Repas progressifs multi-photos, ingrédients modifiables, calculateur de portions et objectifs nutritionnels étendus.
• Graphiques interactifs, aperçu des exercices, import JSON et raccourcis configurables.
• Jeûne facultatif, bilan calorique quotidien et indications Health Connect plus claires.
</fr-FR>

<hi-IN>
• प्रोग्रेसिव मल्टी-फोटो भोजन, संपादन योग्य सामग्री, सर्विंग कैलकुलेटर और विस्तृत पोषक लक्ष्य।
• इंटरैक्टिव चार्ट, व्यायाम पूर्वावलोकन, JSON डायरी आयात और कस्टम ऐप शॉर्टकट।
• वैकल्पिक उपवास, दैनिक कैलोरी सारांश और बेहतर Health Connect मार्गदर्शन।
</hi-IN>

<it-IT>
• Pasti progressivi multi-foto, ingredienti modificabili, calcolatore porzioni e obiettivi nutrizionali estesi.
• Grafici interattivi, anteprima esercizi, importazione diario JSON e scorciatoie configurabili.
• Digiuno opzionale, riepilogo calorico giornaliero e indicazioni Health Connect più chiare.
</it-IT>

<ja-JP>
• 段階的な複数写真の食事解析、編集可能な材料、分量計算機、拡張栄養目標。
• グラフの詳細確認、運動プレビュー、JSON日記インポート、設定可能なショートカット。
• 任意の断食記録、毎日のカロリー収支、より明確なHealth Connect案内。
</ja-JP>

<ko-KR>
• 단계별 다중 사진 식사, 편집 가능한 재료, 섭취량 계산기와 확장 영양 목표.
• 대화형 차트, 운동 미리보기, JSON 식단 가져오기와 맞춤 앱 바로가기.
• 선택적 단식, 일일 칼로리 요약과 더 명확한 Health Connect 안내.
</ko-KR>

<nl-NL>
• Progressieve maaltijden met meerdere foto's, bewerkbare ingrediënten, portiecalculator en uitgebreide voedingsdoelen.
• Interactieve grafieken, oefeningpreview, JSON-import en instelbare app-snelkoppelingen.
• Optioneel vasten, dagelijkse caloriesamenvatting en duidelijkere Health Connect-uitleg.
</nl-NL>

<pt-BR>
• Refeições progressivas com várias fotos, ingredientes editáveis, calculadora de porções e metas nutricionais ampliadas.
• Gráficos interativos, prévia de exercícios, importação JSON e atalhos configuráveis.
• Jejum opcional, resumo calórico diário e orientações mais claras do Health Connect.
</pt-BR>

<ro>
• Mese progresive cu mai multe poze, ingrediente editabile, calculator de porții și obiective nutritive extinse.
• Grafice interactive, previzualizare exerciții, import jurnal JSON și scurtături configurabile.
• Post opțional, rezumat caloric zilnic și îndrumări Health Connect mai clare.
</ro>

<ru-RU>
• Пошаговый анализ нескольких фото еды, редактируемые ингредиенты, калькулятор порций и расширенные цели нутриентов.
• Интерактивные графики, просмотр упражнений, импорт JSON и настраиваемые ярлыки.
• Необязательное голодание, дневной баланс калорий и понятные подсказки Health Connect.
</ru-RU>

<zh-CN>
• 渐进式多图餐食、可编辑食材、份量计算器和扩展营养目标。
• 交互式图表、动作预览、JSON 日记导入和可配置应用快捷方式。
• 可选断食、每日热量收支摘要，以及更清晰的 Health Connect 指引。
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
- **Ads**: No — v3.0.3 removed the AdMob banner and the ads SDK entirely. Set "contains ads" to No, and set the Advertising ID declaration to No (the `AD_ID` permission is gone from the manifest).
- **Content rating**: Everyone (E)
- **Target audience**: 13+
- **News app**: No
- **COVID-19 contact tracing**: No
- **Data safety**: Core tracking has no Fud AI account, analytics, advertising, or behavioral tracking. Do not declare Advertising ID. Most app data, including fasting history, is local, and API keys are stored in EncryptedSharedPreferences. The optional 18+ Weekly Challenge is a first-party collection for app functionality: declare **Personal info → Name** (the chosen display name), **Personal info → User IDs** (the random participant ID and optional X or Instagram handle), and **Health and fitness → Fitness info** (weekly aggregate activity calories and qualifying activity, nutrition, consistency, and hydration day counts). These fields are optional to collect because joining is optional, encrypted in transit, not shared with third parties, and deletable from Leave Challenge or Delete All Data. No date of birth is read or uploaded; the age gate stores only acceptance. Do not declare raw food names, meals, weight, workout details, or Health Connect records as challenge-backend collection because those never leave the device for this feature. User-initiated AI/STT requests send selected photos/text/audio directly to the configured provider; Coach requests may include explicitly logged fasting context when relevant; barcode lookup sends the barcode to Open Food Facts; optional shared-meal links place selected meal data in the URL; optional Health Connect sync reads/writes the declared health types. Complete the Play form according to Google's current definitions for these direct user-initiated transfers rather than broadly claiming that no data is processed. Network requests use HTTPS except a user-configured local/custom endpoint may use the URL the user supplies. Delete All Data removes local app data and first requests deletion of the remote challenge profile; if offline, the encrypted deletion credential is retained and deletion retries on the next launch. It does not delete Health Connect records.
- **Government app**: No
- **Financial features**: No
- **Health features**: Yes — nutrition, body measurements, energy-based goals, calculated workout calories, optional local water/fasting tracking, and an optional 18+ Weekly Challenge. Challenge qualification is calculated locally and uploads only weekly totals: overall points, qualifying activity/nutrition/consistency/hydration day counts, and activity calories capped at 2,000 per day; it never uploads raw logs or ranks weight loss. Health Connect permissions are READ/WRITE nutrition, weight, body fat, and active calories burned, plus READ total calories burned. Water and fasting history are local and are not written to Health Connect. Explain restore/backfill, Energy Burn Goals, calculated workout-burn sync, and the separate opt-in challenge aggregate in the permissions/declaration material, and keep the in-app rationale/Manage Access flow aligned with the privacy policy.
