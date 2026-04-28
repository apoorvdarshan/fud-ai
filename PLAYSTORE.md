# Play Store Listing

Google Play Console listing copy for Fud AI Android (current: v1.0.6 / versionCode 7). Each field is in a code block for easy copy-paste. Char counts are tracked because Play Console enforces hard caps and silently truncates anything over.

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
Google Gemini, OpenAI, Anthropic Claude, xAI Grok, Groq, OpenRouter, Together AI, Hugging Face, Fireworks AI, DeepInfra, Mistral, Ollama (local), or any OpenAI-compatible endpoint. Switch anytime. OpenRouter defaults to a free vision model — test without loading credits. Keys stored encrypted (AES-256). Add Custom AI Instructions to send region, diet, or brand context with every request. Set a Fallback Provider so the app auto-retries on overload or rate-limit errors.

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

### Other 14 languages
English-only on Play Console — non-English Play Store browsers (ar, az-AZ, de-DE, es-ES, fr-FR, hi-IN, it-IT, ja-JP, ko-KR, nl-NL, pt-BR, ro, ru-RU, zh-CN) see the English source as fallback. The in-app UI itself is fully translated into all 14 locales via per-locale `values-{lang}/strings.xml`, so the localization gap is only on the Play Store listing surface, not inside the app.

---

## 4. What's New (v1.0.6)

**500 char hard cap per language.** Paste the entire block below into Play Console's "Release notes" field — it auto-routes each `<lang-tag>` block to the matching locale.

```
<en-US>
• Custom AI Instructions — optional Settings text that gets sent with every AI request. Drop region, diet, or brand context once instead of repeating it per meal.
• Fallback AI Provider — opt-in second provider that auto-retries when your primary fails on overload or rate-limit. Pair a paid model with a free fallback for cheap reliability.
</en-US>

<ar>
• تعليمات AI مخصصة — نص اختياري في الإعدادات يُرسل مع كل طلب إلى الذكاء الاصطناعي. أضف سياق المنطقة أو النظام الغذائي أو العلامة التجارية مرة واحدة بدلاً من تكراره مع كل وجبة.
• مزود AI احتياطي — مزود ثانٍ اختياري يعيد المحاولة تلقائيًا عند فشل المزود الأساسي بسبب التحميل الزائد أو حد المعدل. اقرن نموذجًا مدفوعًا مع احتياطي مجاني للحصول على موثوقية بتكلفة منخفضة.
</ar>

<az-AZ>
• Fərdi AI Təlimatları — hər AI sorğusu ilə göndərilən isteğe bağlı Ayarlar mətni. Region, pəhriz və ya brend kontekstini hər yemək üçün təkrarlamaq əvəzinə bir dəfə əlavə edin.
• Ehtiyat AI Provayderi — əsas provayder yüklənmə və ya sürət limiti səbəbindən uğursuz olduqda avtomatik təkrar cəhd edən isteğe bağlı ikinci provayder. Ucuz etibarlılıq üçün pullu modeli pulsuz ehtiyatla cütləşdirin.
</az-AZ>

<de-DE>
• Benutzerdefinierte AI-Anweisungen — optionaler Einstellungstext, der mit jeder AI-Anfrage gesendet wird. Region, Ernährung oder Markenkontext einmal hinterlegen, statt es bei jeder Mahlzeit zu wiederholen.
• Fallback-AI-Anbieter — optionaler zweiter Anbieter, der automatisch erneut versucht, wenn dein Hauptanbieter wegen Überlastung oder Ratenbegrenzung ausfällt. Kombiniere ein kostenpflichtiges Modell mit einem kostenlosen Fallback für günstige Zuverlässigkeit.
</de-DE>

<es-ES>
• Instrucciones de AI personalizadas — texto opcional en Ajustes que se envía con cada solicitud a la AI. Indica una vez tu región, dieta o marcas favoritas en lugar de repetirlo en cada comida.
• Proveedor de AI de respaldo — segundo proveedor opcional que reintenta automáticamente cuando el principal falla por sobrecarga o límite de velocidad. Combina un modelo de pago con un respaldo gratuito para una fiabilidad económica.
</es-ES>

<fr-FR>
• Instructions AI personnalisées — texte optionnel dans les Paramètres envoyé avec chaque requête AI. Indique une fois ta région, ton régime ou tes marques préférées au lieu de le répéter à chaque repas.
• Fournisseur AI de secours — second fournisseur optionnel qui réessaie automatiquement lorsque ton fournisseur principal échoue pour cause de surcharge ou de limite de débit. Associe un modèle payant à un secours gratuit pour une fiabilité à moindre coût.
</fr-FR>

<hi-IN>
• कस्टम AI निर्देश — सेटिंग्स में वैकल्पिक टेक्स्ट जो हर AI अनुरोध के साथ भेजा जाता है। हर भोजन पर दोहराने के बजाय अपना क्षेत्र, डाइट या ब्रांड संदर्भ एक बार जोड़ें।
• फ़ॉलबैक AI प्रोवाइडर — वैकल्पिक दूसरा प्रोवाइडर जो आपके मुख्य प्रोवाइडर के ओवरलोड या रेट-लिमिट पर विफल होने पर अपने आप पुनः प्रयास करता है। सस्ती विश्वसनीयता के लिए पेड मॉडल के साथ मुफ़्त फ़ॉलबैक जोड़ें।
</hi-IN>

<it-IT>
• Istruzioni AI personalizzate — testo opzionale nelle Impostazioni inviato con ogni richiesta AI. Inserisci una volta il contesto di regione, dieta o marca invece di ripeterlo a ogni pasto.
• Provider AI di riserva — secondo provider opzionale che riprova automaticamente quando quello principale fallisce per sovraccarico o limite di velocità. Abbina un modello a pagamento a un fallback gratuito per un'affidabilità economica.
</it-IT>

<ja-JP>
• カスタムAI指示 — すべてのAIリクエストとともに送信される設定の任意テキスト。地域、食事、ブランドのコンテキストを食事ごとに繰り返す代わりに、一度だけ入力できます。
• フォールバックAIプロバイダー — メインプロバイダーが過負荷やレート制限で失敗したときに自動で再試行するオプションの2番目のプロバイダー。有料モデルと無料のフォールバックを組み合わせて、低コストで信頼性を確保。
</ja-JP>

<ko-KR>
• 맞춤 AI 지침 — 모든 AI 요청과 함께 전송되는 설정의 선택적 텍스트입니다. 지역, 식단, 브랜드 정보를 매 식사마다 반복하는 대신 한 번만 입력하세요.
• 대체 AI 제공자 — 기본 제공자가 과부하 또는 속도 제한으로 실패할 때 자동으로 재시도하는 선택적 두 번째 제공자입니다. 유료 모델과 무료 대체를 결합하여 저렴한 비용으로 안정성을 확보하세요.
</ko-KR>

<nl-NL>
• Aangepaste AI-instructies — optionele tekst in Instellingen die met elke AI-aanvraag wordt meegestuurd. Voeg regio-, dieet- of merkcontext één keer toe in plaats van het bij elke maaltijd te herhalen.
• Reserve-AI-provider — optionele tweede provider die automatisch opnieuw probeert wanneer je primaire provider faalt door overbelasting of een rate limit. Combineer een betaald model met een gratis reserve voor goedkope betrouwbaarheid.
</nl-NL>

<pt-BR>
• Instruções de AI personalizadas — texto opcional em Configurações enviado com cada solicitação à AI. Informe uma vez sua região, dieta ou marcas em vez de repetir a cada refeição.
• Provedor de AI de fallback — segundo provedor opcional que tenta novamente de forma automática quando o principal falha por sobrecarga ou limite de taxa. Combine um modelo pago com um fallback gratuito para confiabilidade barata.
</pt-BR>

<ro>
• Instrucțiuni AI personalizate — text opțional în Setări trimis cu fiecare cerere AI. Adaugă o singură dată contextul de regiune, dietă sau brand în loc să-l repeți la fiecare masă.
• Furnizor AI de rezervă — al doilea furnizor opțional care reîncearcă automat când cel principal eșuează din cauza supraîncărcării sau a limitei de viteză. Asociază un model plătit cu o rezervă gratuită pentru fiabilitate ieftină.
</ro>

<ru-RU>
• Пользовательские инструкции AI — необязательный текст в Настройках, отправляемый с каждым запросом к AI. Укажите регион, диету или бренды один раз вместо повторения для каждого приёма пищи.
• Резервный провайдер AI — необязательный второй провайдер, автоматически повторяющий запрос при сбое основного из-за перегрузки или ограничения скорости. Сочетайте платную модель с бесплатным резервом для дешёвой надёжности.
</ru-RU>

<zh-CN>
• 自定义 AI 指令 — 设置中的可选文本，会随每次 AI 请求一起发送。一次性添加地区、饮食或品牌偏好，无需在每餐时重复输入。
• 备用 AI 提供商 — 可选的第二个提供商，当主提供商因过载或速率限制失败时会自动重试。将付费模型与免费备用搭配，以低成本获得可靠性。
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
