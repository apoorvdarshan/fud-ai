# Release notes

Historical summaries below are preserved from the store copy at each release tag,
with early Android summaries reconstructed from the corresponding changes.
They describe those versions, not necessarily the current product. Tags with the
same marketing version can share a feature summary while identifying different builds.

## Unreleased

- Added automated web and Android checks before GitHub release workflows proceed.
- Added generated-input tests for challenge API validation.
- Fixed Android resource access during Compose updates and completed history-counter plural forms.


## v6.1

Fud AI 6.1 — optional intermittent fasting, clearer tracking, and reliability improvements.

NEW
• Optional fasting tracking, disabled by default, with a custom 1–168 hour goal.
• Start, end, or cancel from the Home + menu; active timers persist across app restarts.
• Review, edit, or delete completed fasting sessions without changing calories or macros.
• Receive an optional one-time local alert when the goal is reached. The fast continues until you end it.
• Coach can review explicitly logged fasting history when relevant and never infers a fast from missing meal logs.

IMPROVED
• Fasting records use a separate local store and are never written to Apple Health.
• Disabling fasting cancels an active timer while preserving completed history.
• Reliability and security updates across the app and bundled web tooling.

Existing logs, goals, Health data, widgets, workout history, and BYOK settings are preserved during a normal update.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v6.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v6.1)


## android-v6.1

• Optional fasting tracker with a persistent timer, custom 1–168 hour goal, editable history, and an optional local goal alert.
• Start, end, or cancel from Home. Fasting stays separate from nutrition and Health Connect; Coach can review only explicitly logged fasts.
• Reliability and security improvements.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v6.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v6.1)


## v6.0

Fud AI 6.0 — workout logging on iPhone, faster meal reuse, and updated AI access.

NEW
• Workout diary and logger with weekly navigation, sets, reps, weight, RPE, and no required timer.
• Calculate a day's workout calorie burn, review or delete burn history in Progress, and optionally sync it with Apple Health.
• Switch between the diary and the 873-exercise library. Your last view persists; new installs start in the diary.
• Coach can retrieve workout plans, preferences, completed sessions, and logged sets when answering training questions.
• Choose a water unit and see compact water progress on Apple Watch when water tracking is enabled.

IMPROVED
• Saved Meal destinations open directly; Recent and Frequent use rolling history windows, and copied foods use the current time and meal boundary.
• Food-diary exports now include every stored nutrient.
• Current supported AI model presets, legacy Gemini migration, and configurable timeouts for Ollama/custom endpoints.
• More reliable provider responses, local vision requests, widgets, settings rendering, image cleanup, and keyboard dismissal.

Existing logs, goals, Health data, widgets, and BYOK settings are preserved during a normal update.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v6.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v6.0)


## android-v6.0

• New workout diary/logger: plan exercises, enter sets, reps, weight and RPE, estimate daily calorie burn, and review workout history.
• Switch between the logger and 873-exercise library; your last view stays selected.
• Faster meal reuse, complete nutrient export, and selectable water units.
• Updated AI model presets, configurable local/custom timeouts, Health Connect workout sync, and reliability fixes.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v6.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v6.0)


## v5.11

Fud AI 5.11 — more reliable widgets, faster logging, optional water tracking, and clearer goals.

NEW
• Camera and Photos now accept up to 10 images in one analysis. Add an optional note, review every image, and keep Retry available if the AI request fails.
• A cleaner grouped add menu: Photo & Scan, Describe Meal, Reuse Meal, and optional Water.
• Optional water tracking is off by default. Choose a daily goal, log one to three glasses or a custom amount, see progress below calories, enable a local reminder, and add a separate Water widget to Home or Lock Screen.
• Six clearer activity levels describe work and training without requiring step counts.
• Customize the times that Breakfast, Lunch, Dinner, and Snack begin.
• Automatic meal assignment follows those custom time boundaries.

IMPROVED
• Home and Lock Screen calorie, protein, and water widgets now use more reliable shared updates so current values appear consistently after app updates.
• Photo previews now fit the complete image on both the capture review and food review screens.
• Weekly weight-change controls consistently follow your selected unit during onboarding and later edits.
• Health settings now offer Manage Access so permissions are easier to review or change.
• Water and nutrition widgets keep values comfortably inside their gauges.
• General refinements and fixes across logging and settings.

No data migration is required. Existing logs, goals, Health data, widgets, and BYOK settings are preserved. Water tracking stays off until you enable it.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v5.11) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v5.11)


## v5.1

Fud AI 5.1 — faster logging, optional water tracking, and clearer goals.

NEW
• Camera and Photos now accept up to 10 images in one analysis. Add an optional note, review every image, and keep Retry available if the AI request fails.
• A cleaner grouped add menu: Photo & Scan, Describe Meal, Reuse Meal, and optional Water.
• Optional water tracking is off by default. Choose a daily goal, log one to three glasses or a custom amount, see progress below calories, enable a local reminder, and add a separate Water widget to Home or Lock Screen.
• Six clearer activity levels describe work and training without requiring step counts.
• Customize the times that Breakfast, Lunch, Dinner, and Snack begin.
• Automatic meal assignment follows those custom time boundaries.

IMPROVED
• Photo previews now fit the complete image on both the capture review and food review screens.
• Weekly weight-change controls consistently follow your selected unit during onboarding and later edits.
• Health settings now offer Manage Access so permissions are easier to review or change.
• Water and nutrition widgets keep values comfortably inside their gauges.
• General refinements and fixes across logging and settings.

No data migration is required. Existing logs, goals, Health data, widgets, and BYOK settings are preserved. Water tracking stays off until you enable it.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v5.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v5.1)


## android-v3.1

• Analyze up to 10 camera or library photos together, add an optional note, and retry failed requests.
• Optional local water tracking: custom goal, glass shortcuts, reminder, progress, and a separate Water widget.
• Six clearer activity levels and customizable Breakfast, Lunch, Dinner, and Snack times.
• Complete image previews, corrected weekly-change units, configurable meal defaults, and easier Health Connect access.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v3.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v3.1)


## v5.0-build30

Fud AI 5.0 — Workouts, a Home redesign, and fully free.

NEW
• Workouts tab: browse an 873-exercise library with photos, primary/secondary muscle and equipment filters, search, sort, and per-exercise detail pages.
• Home redesign: speedometer calorie gauge, vertical macro bars, four nutrient cards, and a floating add button. Widgets, Lock Screen, and Apple Watch match the new design and follow your chosen nutrient cards.
• Fud AI is now fully free — the Premium subscription has been removed. Bring your own AI key (a free Gemini key takes a minute). No ads; there's an optional Tip Jar in Settings → About if you'd like to support development.
• Ask Coach by voice with the new inline recorder.
• Share any meal as a link that opens straight in Fud AI.
• Export your food diary as JSON, Markdown, or CSV from Settings.
• Reprocess a logged meal with AI: adjust its name, serving, or note and re-analyze.
• Camera + Camera now keeps both shots, stitched side by side.
• 10 new theme colors (18 total), picked inline in Settings.
• Reinstalled or got a new iPhone? Your food log, weight, and body fat now restore automatically from Apple Health.

IMPROVED
• Adaptive Goals and Energy Burn have graduated from Experimental and are on by default for new installs.
• Body fat history: review and delete individual entries, just like weight.
• Height and weight units can be set independently.
• Progress charts stay readable across dense, multi-year ranges.
• Refreshed onboarding: a quick feature tour, clearer AI and privacy notes, and your plan ready at the end.
• Meal sections show combined nutrition totals; exercise search keeps filters pinned; the keyboard dismisses as you scroll.
• General refinements and fixes.

No data migration is required. Existing logs, goals, widgets, and BYOK settings are preserved. If you had a Premium subscription, cancel it from your Apple ID subscription settings — this version no longer uses it.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v5.0-build30) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v5.0-build30)


## v5.0-build29

Fud AI 5.0 — Workouts, a Home redesign, and fully free.

NEW
• Workouts tab: browse an 873-exercise library with photos, primary/secondary muscle and equipment filters, search, sort, and per-exercise detail pages.
• Home redesign: speedometer calorie gauge, vertical macro bars, four nutrient cards, and a floating add button. Widgets, Lock Screen, and Apple Watch match the new design and follow your chosen nutrient cards.
• Fud AI is now fully free — the Premium subscription has been removed. Bring your own AI key (a free Gemini key takes a minute). No ads; there's an optional Tip Jar in Settings → About if you'd like to support development.
• Ask Coach by voice with the new inline recorder.
• Share any meal as a link that opens straight in Fud AI.
• Export your food diary as JSON, Markdown, or CSV from Settings.
• Reprocess a logged meal with AI: adjust its name, serving, or note and re-analyze.
• Camera + Camera now keeps both shots, stitched side by side.
• 10 new theme colors (18 total), picked inline in Settings.
• Reinstalled or got a new iPhone? Your food log, weight, and body fat now restore automatically from Apple Health.

IMPROVED
• Adaptive Goals and Energy Burn have graduated from Experimental and are on by default for new installs.
• Body fat history: review and delete individual entries, just like weight.
• Height and weight units can be set independently.
• Progress charts stay readable across dense, multi-year ranges.
• Refreshed onboarding: a quick feature tour, clearer AI and privacy notes, and your plan ready at the end.
• Meal sections show combined nutrition totals; exercise search keeps filters pinned; the keyboard dismisses as you scroll.
• General refinements and fixes.

No data migration is required. Existing logs, goals, widgets, and BYOK settings are preserved. If you had a Premium subscription, cancel it from your Apple ID subscription settings — this version no longer uses it.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v5.0-build29) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v5.0-build29)


## v5.0-build28

Fud AI 5.0 — Workouts, a Home redesign, and fully free.

NEW
• Workouts tab: browse an 873-exercise library with photos, primary/secondary muscle and equipment filters, search, sort, and per-exercise detail pages.
• Home redesign: speedometer calorie gauge, vertical macro bars, four nutrient cards, and a floating add button. Widgets, Lock Screen, and Apple Watch match the new design and follow your chosen nutrient cards.
• Fud AI is now fully free — the Premium subscription has been removed. Bring your own AI key (a free Gemini key takes a minute). A small banner ad keeps the app free, and there's an optional Tip Jar in Settings → About if you'd like to support development.
• Ask Coach by voice with the new inline recorder.
• Share any meal as a link that opens straight in Fud AI.
• Export your food diary as JSON, Markdown, or CSV from Settings.
• Reprocess a logged meal with AI: adjust its name, serving, or note and re-analyze.
• Camera + Camera now keeps both shots, stitched side by side.
• 10 new theme colors (18 total), picked inline in Settings.
• Reinstalled or got a new iPhone? Your food log, weight, and body fat now restore automatically from Apple Health.

IMPROVED
• Adaptive Goals and Energy Burn have graduated from Experimental and are on by default for new installs.
• Body fat history: review and delete individual entries, just like weight.
• Height and weight units can be set independently.
• Progress charts stay readable across dense, multi-year ranges.
• Refreshed onboarding: a quick feature tour, clearer AI and privacy notes, and your plan ready at the end.
• Meal sections show combined nutrition totals; exercise search keeps filters pinned; the keyboard dismisses as you scroll.
• General refinements and fixes.

No data migration is required. Existing logs, goals, widgets, and BYOK settings are preserved. If you had a Premium subscription, cancel it from your Apple ID subscription settings — this version no longer uses it.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v5.0-build28) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v5.0-build28)


## android-v3.0.4

• New Workouts tab: 873 exercises with photos, filters, search, and detail pages.
• Home redesign: speedometer gauge, macro bars, nutrient cards, floating add button — widgets match.
• Ask Coach by voice, share meals as links, export your diary, reprocess meals with AI.
• 10 new theme colors, body-fat history, independent units.
• Reinstalled? Your food log, weight, and body fat restore from Health Connect.
• Adaptive Goals and Energy Burn on by default.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v3.0.4) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v3.0.4)


## android-v3.0.3

• New Workouts tab: 873 exercises with photos, filters, search, and detail pages.
• Home redesign: speedometer gauge, macro bars, nutrient cards, floating add button — widgets match.
• Ask Coach by voice, share meals as links, export your diary, reprocess meals with AI.
• 10 new theme colors, body-fat history, independent units.
• Reinstalled? Your food log, weight, and body fat restore from Health Connect.
• Adaptive Goals and Energy Burn on by default.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v3.0.3) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v3.0.3)


## android-v3.0.2

• New Workouts tab: 873 exercises with photos, filters, search, and detail pages.
• Home redesign: speedometer gauge, macro bars, nutrient cards, floating add button — widgets match.
• Ask Coach by voice, share meals as links, export your diary, reprocess meals with AI.
• 10 new theme colors, body-fat history, independent units.
• Reinstalled? Your food log, weight, and body fat restore from Health Connect.
• Adaptive Goals and Energy Burn on by default. A small banner ad keeps Fud AI free.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v3.0.2) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v3.0.2)


## v5.0

Fud AI 5.0 — Workouts, a Home redesign, and fully free.

NEW
• Workouts tab: browse an 873-exercise library with photos, primary/secondary muscle and equipment filters, search, sort, and per-exercise detail pages.
• Home redesign: speedometer calorie gauge, vertical macro bars, four nutrient cards, and a floating add button. Widgets, Lock Screen, and Apple Watch match the new design and follow your chosen nutrient cards.
• Fud AI is now fully free — the Premium subscription has been removed. Bring your own AI key (a free Gemini key takes a minute). A small banner ad keeps the app free, and there's an optional Tip Jar in Settings → About if you'd like to support development.
• Ask Coach by voice with the new inline recorder.
• Share any meal as a link that opens straight in Fud AI — on iPhone or Android.
• Export your food diary as JSON, Markdown, or CSV from Settings.
• Reprocess a logged meal with AI: adjust its name, serving, or note and re-analyze.
• Camera + Camera now keeps both shots, stitched side by side.
• 10 new theme colors (18 total), picked inline in Settings.
• Reinstalled or got a new iPhone? Your food log, weight, and body fat now restore automatically from Apple Health.

IMPROVED
• Adaptive Goals and Energy Burn have graduated from Experimental and are on by default for new installs.
• Body fat history: review and delete individual entries, just like weight.
• Height and weight units can be set independently.
• Progress charts stay readable across dense, multi-year ranges.
• Refreshed onboarding: a quick feature tour, clearer AI and privacy notes, and your plan ready at the end.
• Meal sections show combined nutrition totals; exercise search keeps filters pinned; the keyboard dismisses as you scroll.
• General refinements and fixes.

No data migration is required. Existing logs, goals, widgets, and BYOK settings are preserved. If you had a Premium subscription, cancel it from your Apple ID subscription settings — this version no longer uses it.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v5.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v5.0)


## android-v3.0.1

• New Workouts tab: 873 exercises with photos, filters, search, and detail pages.
• Home redesign: speedometer gauge, macro bars, nutrient cards, floating add button — widgets match.
• Ask Coach by voice, share meals as links, export your diary, reprocess meals with AI.
• 10 new theme colors, body-fat history, independent units.
• Reinstalled? Your food log, weight, and body fat restore from Health Connect.
• Adaptive Goals and Energy Burn on by default. A small banner ad keeps Fud AI free.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v3.0.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v3.0.1)


## android-v3.0

• New Workouts tab: 873 exercises with photos, filters, search, and detail pages.
• Home redesign: speedometer gauge, macro bars, nutrient cards, floating add button — widgets match.
• Ask Coach by voice, share meals as links, export your diary, reprocess meals with AI.
• 10 new theme colors, body-fat history, independent units.
• Reinstalled? Your food log, weight, and body fat restore from Health Connect.
• Adaptive Goals and Energy Burn on by default. A small banner ad keeps Fud AI free.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v3.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v3.0)


## v4.4-build25

Fud AI 4.4 — Smarter, AI-powered goals.

NEW
• Recalculate Goals now uses AI. It sets your daily calories and macros from your profile — and as you keep logging, refines them from your real intake and weight trend (a true "hit and trial" estimate of your maintenance). Works with Fud AI Premium or your own provider key, and falls back to the standard formula when AI isn't available.
• Recalculate also refreshes your optional nutrient targets — fiber, sugar, sodium, and more.
• Onboarding now sets up your AI up front (Premium, or bring your own provider, model, and key) and builds your starting plan with AI.
• Lock any calculated calorie or macro target so Recalculate and Adaptive keep it fixed — reset to auto-balance anytime.
• New optional body measurements (waist, hips, neck, and more) in Settings → Personal Info feed Recalculate Goals and the Coach.

IMPROVED
• Energy Burn Goals is now part of Adaptive Goals — one simpler toggle. When Apple Health is connected, your weekly auto-correction also factors the calories you actually burned.
• Swipe left or right on the calorie area of Home to move between days; the week strip follows along.
• Coach replies now render formatted text — headings, bold, and bullet lists — so guidance is easier to read.
• Weight goals read clearer: Lose / Cutting, Maintain / Recomp, and Gain / Bulking.
• Get notified when a new version is available, with an App Updates toggle in Notifications.
• Food log times now follow your phone's 12- or 24-hour clock.
• General refinements and fixes.

No data migration is required. Existing logs, goals, widgets, Premium, and BYOK settings are preserved.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v4.4-build25) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v4.4-build25)


## android-v2.3.0

• Recalculate Goals is now AI-powered — it sets your calories and macros from your profile and refines them from your logged intake and weight trend. Falls back to the standard formula offline.
• Onboarding now sets up your AI provider and key, then builds your starting plan with AI.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v2.3.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v2.3.0)


## android-v2.2.2

• Text food entry keeps the keyboard open while examples rotate.
• Native Android voice now falls back when on-device language support is missing. Try Groq (Whisper) or Deepgram for more consistent transcription.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v2.2.2) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v2.2.2)


## v4.3-build24

Fud AI 4.3 — Apple Intelligence fallback and Polish localization fixes.

NEW
• Text, voice-transcribed, and Siri food logging now try the active AI access path first: Premium Gemini, or your BYOK provider plus configured fallback.
• On supported iPhones, Apple Intelligence can run as a final on-device fallback when those provider attempts fail.
• Apple Intelligence fallback keeps the existing serving-unit review behavior and skips unsupported scripts.
• Photo scans, nutrition label scans, and Coach continue using the configured AI access path.
• Polish now covers dynamic nutrient names, meal types, Settings labels, and locale-aware date/time display.
• Polish "Log" wording has been corrected to "Dodaj" / "Dodaj wagę" where appropriate.

No data migration is required. Existing logs, goals, widgets, Premium, and BYOK settings are preserved.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v4.3-build24) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v4.3-build24)


## android-v2.2.1

• Review Food keeps the Log button visible on narrow Android screens.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v2.2.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v2.2.1)


## v4.1-build21

Fud AI 4.1 — clearer widgets and Experimental goal tools.

NEW
• Review Food now has a Nutrition lock: unlock to correct calories, macros, and detailed nutrients before logging, then lock again so serving changes scale from those edits.
• Review Food now has a What if? check that previews macro impact and can ask AI for a practical suggestion before logging.
• Siri Shortcuts on iOS can log food, read today's calories, and log weight. Phrase examples are now in Settings → Siri Phrases.
• Energy Burn Goals and Adaptive Goals are now clearly marked Experimental in Settings.
• Adaptive Goals can make a small weekly calorie correction from your weight trend while keeping pinned macros intact.
• Activity Level now shows protein targets in g/kg body weight, or an equivalent lean-mass multiplier when body fat is set.
• Lock Screen rectangular widgets now show current / goal values in clean metric rows.
• Lock Screen circular widgets now use larger value-first previews for faster reading.
• Lock Screen calories, carbs, and fiber now use distinct icons instead of repeated leaves.
• The calorie rectangular widget shows calories plus all three selected Home nutrients.
• The iOS Home Screen widget gallery keeps the small Fud AI Protein widget while removing its duplicate medium Home widget.
• The separate nutrient Lock Screen widget no longer offers a duplicate rectangular option.
• iOS AI access is BYOK-only again; legacy hosted AI access has been removed.

No data migration is required. Existing logs, goals, widgets, and BYOK settings are preserved.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v4.1-build21) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v4.1-build21)


## android-v2.2

• Energy Burn Goals auto-refresh once per day on open.
• Uses recent completed Health Connect burn data from 14 days.
• Adaptive Goals (Experimental) can add a weekly trend correction.
• Activity Level shows protein g/kg; body-fat entries switch it to lean-mass equivalent.
• Nutrition lock lets you edit calories/macros before logging.
• What if? in Review Food previews macro impact and AI suggestion before logging.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v2.2) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v2.2)


## v4.0-build16

Fud AI 4.0 — Apple Watch, faster sharing, and clearer nutrition totals.

NEW
• Added Apple Watch app and complications for calories and macros at a glance.
• Added iOS Share Extension support so food photos can be sent into Fud AI from Photos and other apps.
• Added optional Apple Health energy-burn goals in Goal Settings.
• Macro and nutrient totals now keep decimal precision across logs, Home, widgets, and View More.
• Weight and body-fat progress now show average and net change summaries.
• Added Gemini 3.5 Flash and a default grams setting with an in-app explanation.

Macros remain editable after energy-burn goal estimates, BYOK mode remains available, and existing logged data is preserved on update.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v4.0-build16) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v4.0-build16)


## android-v2.1.0

• Health Connect can estimate calorie goals from energy burn; macros stay editable.
• Decimal macro grams are preserved in logs, Home, nutrition details, and widgets.
• Progress shows current, goal, net change, and average for weight/body fat.
• Quantity fields support comma decimals, clearer cursor/clear behavior, and tap-out keyboard dismiss.
• Removed unused nutrition-read permission; future-day logging no longer crashes with Health Connect.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v2.1.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v2.1.0)


## v3.6-build15

Fud AI 3.5 — faster packaged-food logging and cleaner Plus access.

NEW
• Added barcode logging for packaged foods using Open Food Facts.
• Added a weekly Fud AI Plus plan alongside monthly and yearly.
• Fud AI Plus voice now uses Deepgram through the secure proxy.
• Added a 60-second cap for Plus voice recordings.
• Added a one-time update popup for barcode + Plus changes.
• Added direct App Store subscription management.
• Added image attachments in Coach chat.

BYOK mode remains available, and existing calorie/macro goal calculations are unchanged.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.6-build15) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.6-build15)


## android-v2.0.0

• Barcode logging for packaged foods with Open Food Facts.
• Copy meals from another date.
• Edit a food's logged date and time.
• Attach camera or photo-library images in Coach.
• Gemini Flash Lite moved to the GA model.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v2.0.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v2.0.0)


## v3.5-build13

Fud AI 3.5 — faster packaged-food logging and cleaner Plus access.

NEW
• Added barcode logging for packaged foods using Open Food Facts.
• Added a weekly Fud AI Plus plan alongside monthly and yearly.
• Fud AI Plus voice now uses Deepgram through the secure proxy.
• Added a 60-second cap for Plus voice recordings.
• Added a one-time update popup for barcode + Plus changes.
• Added direct App Store subscription management.
• Added image attachments in Coach chat.

BYOK mode remains available, and existing calorie/macro goal calculations are unchanged.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.5-build13) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.5-build13)


## android-v1.2

• Barcode logging for packaged foods with Open Food Facts.
• Copy meals from another date.
• Edit a food's logged date and time.
• Attach camera or photo-library images in Coach.
• Gemini Flash Lite moved to the GA model.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.2) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.2)


## v3.4.1-build12

Fud AI 3.4.1 — more ways to log and track.

NEW
• Added From Photos + Note, so existing meal photos can include extra context before AI analysis.
• Added customizable Home nutrient cards beyond protein, carbs, and fat.
• Added optional nutrient goals for fiber, sugar, saturated fat, cholesterol, sodium, and potassium.
• Added AI estimation for those optional nutrient goals based on your profile.
• Added Ko-fi support link in About.

These goals are separate from the calorie, protein, carb, and fat calculator, so macro recalculation stays unchanged.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.4.1-build12) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.4.1-build12)


## android-v1.1.1

• Add notes to photo-library meals.
• Customize Home nutrient cards and set optional nutrient goals.
• AI can estimate fiber, sugar, sodium, potassium, and other goals.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.1.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.1.1)


## v3.4.0-build11

Fud AI 3.4.0 — Fud AI Plus.

NEW
• Added optional Fud AI Plus for no-key food scans, voice logging, and Coach.
• Bring Your Own Key remains available as the free default mode.
• Added daily Plus usage limits in Settings.
• Added speech language selection for Plus voice logging.

Also includes theme colors with matching app icons, Instagram in About, smarter serving units like slices/pieces/ml, optional food log sorting, smoother quantity editing, update checks, and richer Coach food context.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.4.0-build11) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.4.0-build11)


## android-v1.1.0

• New food log sort option for latest meal order.
• Easier serving unit edits, Gemini speech-to-text, and GPT-5/OpenAI fixes.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.1.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.1.0)


## android-v1.0.10

• Theme color launcher switching is more reliable after changing colors.
• Cold start splash better matches the selected launcher color.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.10) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.10)


## v3.3.2-build10

Fud AI 3.3.2 — personalization polish.

NEW
• Added theme color selection in Settings.
• The home screen app icon now changes to match your selected theme color.

Also includes recent improvements: Instagram link in About, per-provider speech language selection, smoother quantity editing with Done keyboard controls, About update checks, and richer Coach food context.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.3.2-build10) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.3.2-build10)


## android-v1.0.9

• New Theme Color setting: pick pink/red, red, orange, green, mint, teal, blue, or purple.
• Android launcher icon now follows your selected theme color.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.9) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.9)


## v3.3.1-build9

Fud AI 3.3.1 — small About and contact polish.

NEW
• Added a Follow on Instagram link in About.
• Updated contact links so users can find Fud AI updates more easily.

Also includes the v3.3 improvements: per-provider speech language selection, smoother quantity editing with Done keyboard controls, About update checks, and richer Coach food context.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.3.1-build9) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.3.1-build9)


## android-v1.0.8

• About now includes Follow on Instagram for Fud AI updates and support.
• Keeps the v1.0.7 improvements: STT language controls, richer Coach context, update badge, and keyboard/nav polish.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.8) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.8)


## v3.3-build8

Fud AI 3.3 — better voice language control, smoother food edits, and in-app update checking.

NEW
• Speech-to-text language selection — each STT provider now has its own language setting. Native iOS defaults to Use iPhone Language; AI providers keep Provider Auto unless a specific language is selected.
• Provider-specific voice hints — OpenAI Whisper, Groq Whisper, Deepgram, and AssemblyAI receive the selected language where their API supports it, helping non-English meal dictation land closer to what you said.
• About update check — About now shows your installed version, checks the App Store version, shows a dot when an update is available, and opens the App Store update page from the app.

Polish
• Faster quantity correction in Review Food — the cursor starts at the end of the amount, the clear button keeps the field focused, and the numeric keyboard gets a Done button.
• The Edit Food screen gets the same quantity keyboard polish as Review Food.
• Tapping outside the food quantity field dismisses the keyboard without closing the food card.

Bug fixes
• Reduces accidental card dismissals while correcting serving size or quantity.
• Coach gets richer food context for common meal questions.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.3-build8) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.3-build8)


## android-v1.0.7

• Pick a speech language per STT provider: Provider Auto, Use Device Language, or a fixed language.
• Coach now has today/timezone plus richer meal details for better answers.
• About checks Play Store updates and shows a tab dot when one is available.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.7) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.7)


## v3.2

Fud AI 3.2 — body fat tracking, Coach reaches your full history, and smart daily reminders.

NEW
• Body fat tracking — log readings over time, set a goal %, see your composition trend on the Progress chart alongside weight. Optional: only shown if you opt in during onboarding or set a value in Settings → Profile.
• Apple Health body fat sync — readings flow both ways with Apple Health. Smart-scale data (Withings, Renpho, Eufy, etc.) auto-imports into Fud AI. First time you enable HK sync, years of historical scale data backfills into the chart.
• Unified Weight / Body Fat chart — segmented toggle on the Progress card lets you flip between metrics. Swipe horizontally on the chart to switch.
• Coach gets your full history — instead of seeing only the last 10 weights or 14 days of food, Coach can now fetch any date range on demand. Ask "what was my weight in March?" or "show me my body fat trend over the last 6 months" and it pulls exactly what it needs.
• Use Body Fat for BMR toggle — non-destructive escape hatch in Settings → Profile. When your body fat reading is stale, flip off to fall back to Mifflin-St Jeor without losing the value.
• Log Weight + Log Body Fat reminders — two new smart daily nudges (Settings → Notifications). Skip firing on days you've already logged. Body fat default off (most people don't measure daily).
• Search saved meals — search bar in the Saved Meals sheet filters Recents / Frequent / Favorites separately.
• Decimal weight pickers — pick 72.4 kg or 158.3 lbs without typing in onboarding's Height & Weight + Goal Weight steps.

Polish
• Onboarding loader is now a single brand-pink gradient (was pink → blue).
• Restacked Onboarding Height & Weight imperial layout so the lbs wheel stops collapsing on narrow columns.
• OpenRouter now defaults to a free vision model so you can test the integration without loading credits.

Bug fixes
• Favorites no longer lose their image when the source food log entry is deleted.
• Gender no longer flips to "Other" for users without a HealthKit biological sex value.
• Photo + text analysis is more reliable — better tolerance for AI responses with prose or markdown around the JSON, fewer "Could not understand the AI response" errors.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.2) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.2)


## v3.2-build7

Fud AI 3.2 — body fat tracking, Coach reaches your full history, and smart daily reminders.

NEW
• Body fat tracking — log readings over time, set a goal %, see your composition trend on the Progress chart alongside weight. Optional: only shown if you opt in during onboarding or set a value in Settings → Profile.
• Apple Health body fat sync — readings flow both ways with Apple Health. Smart-scale data (Withings, Renpho, Eufy, etc.) auto-imports into Fud AI. First time you enable HK sync, years of historical scale data backfills into the chart.
• Unified Weight / Body Fat chart — segmented toggle on the Progress card lets you flip between metrics. Swipe horizontally on the chart to switch.
• Coach gets your full history — instead of seeing only the last 10 weights or 14 days of food, Coach can now fetch any date range on demand. Ask "what was my weight in March?" or "show me my body fat trend over the last 6 months" and it pulls exactly what it needs.
• Use Body Fat for BMR toggle — non-destructive escape hatch in Settings → Profile. When your body fat reading is stale, flip off to fall back to Mifflin-St Jeor without losing the value.
• Log Weight + Log Body Fat reminders — two new smart daily nudges (Settings → Notifications). Skip firing on days you've already logged. Body fat default off (most people don't measure daily).
• Search saved meals — search bar in the Saved Meals sheet filters Recents / Frequent / Favorites separately.
• Decimal weight pickers — pick 72.4 kg or 158.3 lbs without typing in onboarding's Height & Weight + Goal Weight steps.
• Custom AI Instructions — optional text box in Settings → AI Provider. Anything you put there (region, dietary preferences, athletic goals, brand preferences) is sent with every AI request, so you don't have to repeat context for every meal.
• Fallback AI Provider — opt-in toggle in Settings → AI Provider. If your primary provider fails (overload, rate limit, network error), the app auto-retries with a second provider you configure. Pair a paid model as primary with a free model as fallback for cheap reliability.
• Calculation Methods — every formula behind your calorie target, BMR, TDEE, and macro split is now documented in-app with peer-reviewed citations (Mifflin-St Jeor, Katch-McArdle, FAO/WHO/UNU activity multipliers, Hall 2011 energy balance, Morton 2018 protein meta-analysis). Reachable from the Plan onboarding step or Settings → Goals & Nutrition.
• AI Analysis Notice — clear in-app prompt before any food photo, voice transcript, or text description is sent to your selected AI provider. Names the provider, lists what's sent, and gives an explicit Allow / Not Now choice.

Polish
• Onboarding loader is now a single brand-pink gradient (was pink → blue).
• Restacked Onboarding Height & Weight imperial layout so the lbs wheel stops collapsing on narrow columns.
• OpenRouter now defaults to a free vision model so you can test the integration without loading credits.

Bug fixes
• Favorites no longer lose their image when the source food log entry is deleted.
• Gender no longer flips to "Other" for users without a HealthKit biological sex value.
• Photo + text analysis is more reliable — better tolerance for AI responses with prose or markdown around the JSON, fewer "Could not understand the AI response" errors.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.2-build7) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.2-build7)


## v3.2-build6

Fud AI 3.2 — body fat tracking, Coach reaches your full history, and smart daily reminders.

NEW
• Body fat tracking — log readings over time, set a goal %, see your composition trend on the Progress chart alongside weight. Optional: only shown if you opt in during onboarding or set a value in Settings → Profile.
• Apple Health body fat sync — readings flow both ways with Apple Health. Smart-scale data (Withings, Renpho, Eufy, etc.) auto-imports into Fud AI. First time you enable HK sync, years of historical scale data backfills into the chart.
• Unified Weight / Body Fat chart — segmented toggle on the Progress card lets you flip between metrics. Swipe horizontally on the chart to switch.
• Coach gets your full history — instead of seeing only the last 10 weights or 14 days of food, Coach can now fetch any date range on demand. Ask "what was my weight in March?" or "show me my body fat trend over the last 6 months" and it pulls exactly what it needs.
• Use Body Fat for BMR toggle — non-destructive escape hatch in Settings → Profile. When your body fat reading is stale, flip off to fall back to Mifflin-St Jeor without losing the value.
• Log Weight + Log Body Fat reminders — two new smart daily nudges (Settings → Notifications). Skip firing on days you've already logged. Body fat default off (most people don't measure daily).
• Search saved meals — search bar in the Saved Meals sheet filters Recents / Frequent / Favorites separately.
• Decimal weight pickers — pick 72.4 kg or 158.3 lbs without typing in onboarding's Height & Weight + Goal Weight steps.
• Custom AI Instructions — optional text box in Settings → AI Provider. Anything you put there (region, dietary preferences, athletic goals, brand preferences) is sent with every AI request, so you don't have to repeat context for every meal.
• Fallback AI Provider — opt-in toggle in Settings → AI Provider. If your primary provider fails (overload, rate limit, network error), the app auto-retries with a second provider you configure. Pair a paid model as primary with a free model as fallback for cheap reliability.
• Calculation Methods — every formula behind your calorie target, BMR, TDEE, and macro split is now documented in-app with peer-reviewed citations (Mifflin-St Jeor, Katch-McArdle, FAO/WHO/UNU activity multipliers, Hall 2011 energy balance, Morton 2018 protein meta-analysis). Reachable from the Plan onboarding step or Settings → Goals & Nutrition.
• AI Analysis Notice — clear in-app prompt before any food photo, voice transcript, or text description is sent to your selected AI provider. Names the provider, lists what's sent, and gives an explicit Allow / Not Now choice.

Polish
• Onboarding loader is now a single brand-pink gradient (was pink → blue).
• Restacked Onboarding Height & Weight imperial layout so the lbs wheel stops collapsing on narrow columns.
• OpenRouter now defaults to a free vision model so you can test the integration without loading credits.

Bug fixes
• Favorites no longer lose their image when the source food log entry is deleted.
• Gender no longer flips to "Other" for users without a HealthKit biological sex value.
• Photo + text analysis is more reliable — better tolerance for AI responses with prose or markdown around the JSON, fewer "Could not understand the AI response" errors.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.2-build6) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.2-build6)


## v3.2-build5

Fud AI 3.2 — body fat tracking, Coach reaches your full history, and smart daily reminders.

NEW
• Body fat tracking — log readings over time, set a goal %, see your composition trend on the Progress chart alongside weight. Optional: only shown if you opt in during onboarding or set a value in Settings → Profile.
• Apple Health body fat sync — readings flow both ways with Apple Health. Smart-scale data (Withings, Renpho, Eufy, etc.) auto-imports into Fud AI. First time you enable HK sync, years of historical scale data backfills into the chart.
• Unified Weight / Body Fat chart — segmented toggle on the Progress card lets you flip between metrics. Swipe horizontally on the chart to switch.
• Coach gets your full history — instead of seeing only the last 10 weights or 14 days of food, Coach can now fetch any date range on demand. Ask "what was my weight in March?" or "show me my body fat trend over the last 6 months" and it pulls exactly what it needs.
• Use Body Fat for BMR toggle — non-destructive escape hatch in Settings → Profile. When your body fat reading is stale, flip off to fall back to Mifflin-St Jeor without losing the value.
• Log Weight + Log Body Fat reminders — two new smart daily nudges (Settings → Notifications). Skip firing on days you've already logged. Body fat default off (most people don't measure daily).
• Search saved meals — search bar in the Saved Meals sheet filters Recents / Frequent / Favorites separately.
• Decimal weight pickers — pick 72.4 kg or 158.3 lbs without typing in onboarding's Height & Weight + Goal Weight steps.
• Custom AI Instructions — optional text box in Settings → AI Provider. Anything you put there (region, dietary preferences, athletic goals, brand preferences) is sent with every AI request, so you don't have to repeat context for every meal.
• Fallback AI Provider — opt-in toggle in Settings → AI Provider. If your primary provider fails (overload, rate limit, network error), the app auto-retries with a second provider you configure. Pair a paid model as primary with a free model as fallback for cheap reliability.

Polish
• Onboarding loader is now a single brand-pink gradient (was pink → blue).
• Restacked Onboarding Height & Weight imperial layout so the lbs wheel stops collapsing on narrow columns.
• OpenRouter now defaults to a free vision model so you can test the integration without loading credits.

Bug fixes
• Favorites no longer lose their image when the source food log entry is deleted.
• Gender no longer flips to "Other" for users without a HealthKit biological sex value.
• Photo + text analysis is more reliable — better tolerance for AI responses with prose or markdown around the JSON, fewer "Could not understand the AI response" errors.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.2-build5) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.2-build5)


## android-v1.0.6

• OpenRouter now defaults to a free vision model so you can test the integration without loading credits.
• Fewer "Could not understand the AI response" errors — better tolerance for AI responses with prose or markdown around the JSON.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.6) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.6)


## android-v1.0.5

Made the free OpenRouter model the default preset and improved parsing of AI responses containing prose or Markdown around JSON. This reduces failed food analysis on supported providers.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.5) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.5)


## android-v1.0.4

Added body-fat tracking, Coach tool calling, smarter reminders and saved-meal search. Body-fat charts now include grid and axis labels.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.4) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.4)


## android-v1.0.3

Added a daily weight-log reminder and decimal weight pickers for onboarding and weight logging.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.3) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.3)


## v3.1

Fud AI 3.1 — manual entry, a dedicated Protein widget, and Gemini 3.

NEW
• Manual Entry — type a meal directly when you don't need AI: name, calories, protein, carbs, fat. Tag it Breakfast / Lunch / Dinner / Snack / Other from the same picker the AI flow uses. Defaults to the meal type for the time of day.
• Protein widget — track protein progress on its own. 5 sizes (Small, Medium, Circular, Rectangular, Inline) — same families as the Calorie widget. Add both for at-a-glance daily protein + calories without opening the app.
• Gemini 3 models — Gemini 3.1 Flash Lite, Gemini 3.1 Pro, and Gemini 3 Flash now available in the AI Provider picker. Pick the one that matches your speed/quality preference.

Polish
• Active tab in the bottom tab bar now tints pink to match the brand.
• Manual Entry's meal type picker matches the AI review sheet's style for a consistent feel.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.1)


## android-v1.0.2

Fixed the Home add-food button being invisible in light mode.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.2) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.2)


## android-v1.0.1

Replaced exact-alarm reminders with inexact reminders and removed the exact-alarm permissions. Reminder delivery may occur a few minutes after the chosen time.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.1) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.1)


## android-v1.0.0

Initial Android release: photo, text and voice food logging, a nutrition coach, saved meals, local tracking and configurable AI providers.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/android-v1.0.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/android-v1.0.0)


## v3.0

Security: removed the unused legacy authentication manager and moved Gemini API
credentials from URL query parameters to request headers. This addresses the
cleartext storage/transmission issues detected by CodeQL in April 2026.
[Fix details](https://github.com/apoorvdarshan/fud-ai/commit/c0d6c750a).

Fud AI 3.0 — your AI nutrition coach, now in 15 languages.

NEW
• Coach tab — multi-turn AI chat that sees your profile, weight trend, and food log. Ask "expected weight in 30 days?", "how's my protein this week?", "what should I eat tonight?" — it answers in plain English.
• Saved Meals — log a past meal in one tap. Recents, Frequent, and Favorites, with drag-to-reorder and swipe-to-delete.
• Home Screen + Lock Screen widgets — 5 sizes (Small, Medium, Circular, Rectangular, Inline). Update the moment you log a meal — no need to open the app.
• Camera + Note — add a short description to a food photo for sharper AI accuracy.
• Share the App — send Fud AI to a friend in one tap, with a personalized message.
• 15 languages — auto-selected by your iPhone's language setting.

AI + Voice
• 5 new AI providers: Hugging Face, Fireworks AI, DeepInfra, Mistral (Pixtral), and a Custom OpenAI-compatible endpoint. 13 providers total.
• 5 speech-to-text engines: Native iOS, OpenAI Whisper, Groq, Deepgram, AssemblyAI.
• Anthropic refreshed — Claude Sonnet 4.6 default, Opus 4.7, Haiku 4.5.
• Transient provider overloads (503/429/529) retry automatically with exponential backoff — short spikes resolve invisibly.

Apple Health
• Now writes macros AND 9 micronutrients per logged meal (fiber, sugar, saturated fat, cholesterol, sodium, potassium, and more).
• External weight samples (Apple Watch, scales, Health app) auto-import in the background.
• Your edits and deletes sync back — no orphan samples.

Goals & Progress
• Recalculate Goals button — snap back to formula defaults anytime.
• Per-macro pin / auto-balance — lock what you care about, let the rest balance.
• Protein targets aligned with ISSN 2017 research, with a +0.2 g/kg bump during cutting.
• Compact weight history with full-screen detail view.

Polish
• Fixed a silent save failure that could lose newly logged meals on certain accounts.
• Picker sheets open at your current value — no more flash-to-default.
• Onboarding now includes a friendly AI-provider setup step.
• Delete All Data is local-only — your Apple Health data is yours and never touched.
• Tons of UI polish across Home, Progress, Coach, and Saved Meals.

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v3.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v3.0)


## v2.0

Fud AI is now free, open source, and privacy-first.

- Bring your own AI — supports Gemini, OpenAI, Claude, xAI Grok, Groq, OpenRouter, Together AI, and Ollama
- API keys stored securely in iOS Keychain
- No account required — no sign-in, no cloud sync
- All data stays on your device
- Voice input — speak your meals hands-free
- Scrollable week calendar — swipe to browse past weeks
- Configurable week start day (Sunday or Monday)
- Progress charts expanded: 1W, 1M, 3M, 6M, 1Y, All Time
- Now supports iOS 17.6+

[Release](https://github.com/apoorvdarshan/fud-ai/releases/tag/v2.0) · [Tagged source](https://github.com/apoorvdarshan/fud-ai/tree/v2.0)
