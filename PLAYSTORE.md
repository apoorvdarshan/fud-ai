# Play Store Listing

Google Play Console listing copy for Fud AI Android v7.0 / versionCode 35. Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

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

NEW in v7.0: daily steps and burn/deficit on Home; Adaptive Goals on by default; Walk/Run quick log; custom exercises with set autofill; meal date/time on review; configurable Home + actions; pinch-zoom photos with optional gallery save; Czech and Ukrainian (18 languages).

Nutrition adds custom goals plus caffeine, creatine, and other performance compounds. Optional fasting adds timers, editable history, goals, alerts, and Coach context. Android includes a daily calorie summary and clearer Health Connect guidance.

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
Multi-turn chat can access profile, goals, food log, progress, workouts, and logged fasts when requested. It never assumes a missing meal means you fasted. Images supported.

REVIEW BEFORE LOGGING
Unlock Nutrition to correct calories, macros, and detailed nutrients before saving; serving changes then scale from your edits. What if? previews today's macro impact and can ask AI for a suggestion.

WORKOUTS
Plan by day; log sets, reps, weight, and RPE. Create custom exercises, autofill sets, and quick-log Walk/Run. Browse 877 exercises with photos, filters, search, sorting, and details.

PERSONALIZED GOALS
BMR and TDEE calculators, six activity levels, automatic or editable macro targets, and customizable meal-time boundaries.

OPTIONAL NUTRIENT GOALS
Set expanded nutrient goals separately from the macro calculator — fiber, sugar, fats, sodium, vitamins, minerals, and more. Use AI Estimate or set manually.

WIDGETS
Separate Calorie, Protein, Today, and Water widgets in the Home speedometer style. They refresh from local snapshots when you log.

OPTIONAL WATER TRACKING
Off by default. Set a daily goal, quick-log glasses or a custom amount, view progress below calories, schedule a reminder, and use the Water widget. Water stays on-device and is not sent to Health Connect.

OPTIONAL FASTING TRACKING
Off by default. Choose a 1–168 hour goal, start/end/cancel from Home +, keep the timer across restarts, optional local alerts, and edit completed sessions. Fasting stays on-device and is not sent to Health Connect.

18 LANGUAGES
Auto-selected by phone language: English, Spanish, French, German, Italian, Portuguese (BR), Dutch, Russian, Japanese, Korean, Chinese, Hindi, Arabic, Romanian, Azerbaijani, Polish, Czech, Ukrainian.

PRIVACY FIRST
Core tracking needs no account; no analytics, behavioral tracking, or ads. The optional 18+ Weekly Challenge uploads only a display name, optional social handle, anonymous ID, and weekly aggregate milestones—never raw food, weight, meals, workouts, or Health Connect records. Keys encrypted; AI/STT requests go to your provider. MIT licensed.

HEALTH CONNECT
Optional sync for nutrition, weight, body fat, and calculated workout calories, plus energy and daily step reads for Home and goal estimates. Records can restore after reinstall. Fasting is local-only.

NOTE: Not medical advice. Estimates are AI-generated; consult a healthcare professional before significant diet changes.

Terms: https://fud-ai.app/terms.html
Privacy: https://fud-ai.app/privacy.html
Source: https://github.com/apoorvdarshan/fud-ai

```

### Other languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, cs-CZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pl-PL, pt-BR, ro, ru-RU, uk, zh-CN) see the English source as fallback. The app includes 18 localized interfaces; newer strings may temporarily use the English fallback.

---

## 4. What's New (v7.0 / versionCode 35)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• Daily steps on Home, burn/deficit line, Adaptive Goals on by default.
• Walk/Run quick log, custom exercises, set autofill, and lift history.
• Set meal date/time on review; configure Home + menu actions.
• Pinch-zoom photos and optional save-to-gallery after logging.
• Czech & Ukrainian (18 languages); Drive backup sign-out/switch.
• Faster Workouts, smoother thumbs, Health Connect retry, reliability fixes.
</en-US>

<ar>
• خطوات يومية في الصفحة الرئيسية، خط حرق/عجز، وأهداف تكيفية مفعّلة افتراضياً.
• تسجيل المشي/الجري، تمارين مخصصة، تعبئة المجموعات تلقائياً، وسجل الرفعات.
• تعيين تاريخ/وقت الوجبة؛ تخصيص قائمة +؛ تكبير الصور وحفظها اختيارياً.
• التشيكية والأوكرانية (18 لغة)، وتحسينات النسخ الاحتياطي والموثوقية.
</ar>

<az-AZ>
• Əsas ekranda gündəlik addımlar, yanma/kəsir xətti və standart Adaptive Goals.
• Gəzinti/qaçış qeydi, fərdi məşqlər, set avtomatik doldurma və qaldırma tarixçəsi.
• Yemək tarix/saatını təyin et; + menyunu fərdiləşdir; fotoları böyüdüb saxla.
• Çex və ukrayna (18 dil); Drive ehtiyat nüsxəsi və etibarlılıq təkmilləşmələri.
</az-AZ>

<cs-CZ>
• Denní kroky na Domově, řádek výdeje/deficitu a Adaptive Goals ve výchozím stavu.
• Rychlé logování chůze/běhu, vlastní cviky, autofill sad a historie zdvihů.
• Datum/čas jídla při kontrole; nastavitelné menu +; zoom fotek a uložení do galerie.
• Čeština a ukrajinština (18 jazyků); Drive záloha a spolehlivost.
</cs-CZ>

<de-DE>
• Tägliche Schritte auf Start, Verbrauchs-/Defizitlinie, Adaptive Goals standardmäßig an.
• Walk/Run-Schnelllog, eigene Übungen, Satz-Autofill und Hebehistorie.
• Mahlzeit Datum/Uhrzeit setzen; + Menü anpassen; Fotos zoomen und optional speichern.
• Tschechisch & Ukrainisch (18 Sprachen); Drive-Backup und Zuverlässigkeit.
</de-DE>

<es-ES>
• Pasos diarios en Inicio, línea de gasto/déficit y Adaptive Goals activado por defecto.
• Registro rápido de caminata/carrera, ejercicios propios, autocompletar series e historial.
• Fecha/hora de la comida; menú + configurable; zoom de fotos y guardado opcional.
• Checo y ucraniano (18 idiomas); copia Drive y más fiabilidad.
</es-ES>

<fr-FR>
• Pas quotidiens sur Accueil, ligne dépense/déficit, Adaptive Goals activé par défaut.
• Journal rapide marche/course, exercices perso, autofill des séries et historique.
• Date/heure du repas; menu + configurable; zoom photos et enregistrement optionnel.
• Tchèque et ukrainien (18 langues); sauvegarde Drive et fiabilité.
</fr-FR>

<hi-IN>
• होम पर दैनिक कदम, बर्न/डेफिसिट लाइन, Adaptive Goals डिफ़ॉल्ट रूप से चालू।
• वॉक/रन क्विक लॉग, कस्टम एक्सरसाइज़, सेट ऑटोफिल और लिफ्ट इतिहास।
• समीक्षा पर भोजन तिथि/समय; Home + मेनू कॉन्फ़िगर; फ़ोटो ज़ूम और वैकल्पिक सेव।
• चेक और यूक्रेनियन (18 भाषाएँ); Drive बैकअप और विश्वसनीयता सुधार।
</hi-IN>

<it-IT>
• Passi giornalieri in Home, linea consumo/deficit e Adaptive Goals attivo di default.
• Log rapido camminata/corsa, esercizi personalizzati, autofill serie e storico alzate.
• Data/ora pasto in revisione; menu + configurabile; zoom foto e salvataggio opzionale.
• Ceco e ucraino (18 lingue); backup Drive e affidabilità.
</it-IT>

<ja-JP>
• ホームに日間歩数、消費/不足ライン、Adaptive Goals をデフォルトでオン。
• ウォーク/ランのクイック記録、カスタム種目、セット自動入力、挙上履歴。
• レビューで食事の日時を設定、+メニューを構成、写真のズームと任意保存。
• チェコ語・ウクライナ語（18言語）、Driveバックアップと信頼性向上。
</ja-JP>

<ko-KR>
• 홈에 일일 걸음, 소모/부족 라인, Adaptive Goals 기본 켜짐.
• 걷기/달리기 빠른 기록, 사용자 운동, 세트 자동 채우기, 리프트 기록.
• 검토에서 식사 날짜/시간, Home + 메뉴 구성, 사진 확대와 선택 저장.
• 체코어·우크라이나어(18개 언어), Drive 백업과 안정성 개선.
</ko-KR>

<nl-NL>
• Dagelijkse stappen op Home, verbrandings-/tekortlijn, Adaptive Goals standaard aan.
• Walk/Run-snelregistratie, eigen oefeningen, set-autofill en tilgeschiedenis.
• Maaltijd datum/tijd bij review; + menu instellen; foto’s zoomen en optioneel opslaan.
• Tsjechisch & Oekraïens (18 talen); Drive-backup en betrouwbaarheid.
</nl-NL>

<pl-PL>
• Dzienne kroki na Home, linia spalania/deficytu, Adaptive Goals domyślnie włączone.
• Szybki log spaceru/biegu, własne ćwiczenia, autofill serii i historia podnoszenia.
• Data/godzina posiłku przy przeglądzie; konfigurowalne menu +; zoom zdjęć i opcjonalny zapis.
• Czeski i ukraiński (18 języków); kopia Drive i większa niezawodność.
</pl-PL>

<pt-BR>
• Passos diários na Home, linha de gasto/déficit e Adaptive Goals ligado por padrão.
• Log rápido de caminhada/corrida, exercícios próprios, autofill de séries e histórico.
• Data/hora da refeição na revisão; menu + configurável; zoom de fotos e salvamento opcional.
• Tcheco e ucraniano (18 idiomas); backup do Drive e mais confiabilidade.
</pt-BR>

<ro>
• Pași zilnici pe Home, linie consum/deficit, Adaptive Goals activat implicit.
• Log rapid mers/alergare, exerciții personalizate, autofill seturi și istoric ridicări.
• Dată/oră masă la review; meniu + configurabil; zoom foto și salvare opțională.
• Cehă și ucraineană (18 limbi); backup Drive și fiabilitate.
</ro>

<ru-RU>
• Дневные шаги на Главной, линия расхода/дефицита, Adaptive Goals включён по умолчанию.
• Быстрый лог ходьбы/бега, свои упражнения, автозаполнение подходов и история подъёмов.
• Дата/время еды при проверке; настраиваемое меню +; зум фото и опциональное сохранение.
• Чешский и украинский (18 языков); резервная копия Drive и надёжность.
</ru-RU>

<uk>
• Щоденні кроки на Головній, лінія витрати/дефіциту, Adaptive Goals увімкнено за замовчуванням.
• Швидкий лог ходьби/бігу, власні вправи, автозаповнення підходів і історія підйомів.
• Дата/час їжі при перегляді; налаштоване меню +; зум фото та опційне збереження.
• Чеська й українська (18 мов); резервна копія Drive та надійність.
</uk>

<zh-CN>
• 首页显示每日步数、消耗/缺口线，Adaptive Goals 默认开启。
• 步行/跑步快记、自定义动作、组数自动填充与举重历史。
• 审核时设置用餐日期/时间；配置 Home + 菜单；照片缩放与可选保存。
• 捷克语与乌克兰语（共 18 种语言）；Drive 备份与可靠性改进。
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
- **Health features**: Yes — nutrition, body measurements, energy-based goals, calculated workout calories, optional daily step reads for Home, optional local water/fasting tracking, and an optional 18+ Weekly Challenge. Challenge qualification is calculated locally and uploads only weekly totals: overall points, qualifying activity/nutrition/consistency/hydration day counts, and activity calories capped at 2,000 per day; it never uploads raw logs or ranks weight loss. Health Connect permissions are READ/WRITE nutrition, weight, body fat, and active calories burned, plus READ total calories burned and steps. Water and fasting history are local and are not written to Health Connect. Explain restore/backfill, Energy Burn Goals, calculated workout-burn sync, daily steps display, and the separate opt-in challenge aggregate in the permissions/declaration material, and keep the in-app rationale/Manage Access flow aligned with the privacy policy.
