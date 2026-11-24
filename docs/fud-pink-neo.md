# Fud Pink Neo

The approved direction is **04 Flash**: Fud Pink neo-brutalist Home with a right-side rail. Native SwiftUI and Compose use solid electric pink `#FF006A`, strong frame lines, a split energy display, a pinned food logging action, and the user's own meal photos. The rail stays on the right; text and content layout still follow the current language.

## What the first pass changes

- **App shell:** Home, Progress, Weekly Challenge, Coach, Settings, Workout Log, Exercise Library, About, and Support Fud AI have their own right-rail destinations. About contains App & Updates, Help & Feedback, Community, and Legal; Support contains the tip jar and other ways to support the app. About, Support, and the Plus / Pro shortcut (for eligible BYOK users) end the scrollable list. Live Voice is pinned below it, unless the in-app voice shortcut is hidden. The existing Progress and Workouts visibility settings still control their respective pairs.
- **Home and diary:** native date picker and the existing week pager both drive the current diary date. The wide energy panel and narrow goal panel use the existing unit preference, goals, and burn summaries. The grid still shows the user's chosen nutrients; Water remains an extra summary. Larger text can reflow the nutrition panels into fewer columns. Food, water, and fasting still share the diary, with food-only nutrition totals.
- **Actions:** Equal square shortcuts beside Log Food default to Camera, Barcode, Text Input, and Favorites. Home displays as many as fit while reserving room for Log Food. Reassign them in Settings → App Settings → + Menu → Home Shortcuts. The full menu defaults to Photo & Scan, Describe Meal, then Reuse Meal on both platforms; custom orders are preserved. The pinned Live Voice waveform is unboxed, has no visible label, and opens the assistant from the current tab; its idle animation respects reduced motion. Sorting, sharing, selection, combining, editing, and removal keep their existing handlers.
- **Appearance:** Flash uses near-white paper `#FFFDFC` in light mode and neutral carbon `#121214` in dark mode. Dark panels (`#19191B`) and the rail (`#18181A`) stay close to the paper so the pale rules, not purple or pink washes, define their edges. The energy panel, selected day/tab, and Log Food action retain solid `#FF006A`. The shared style also applies to Progress cards, Coach messages and composer, Workouts controls, Settings, onboarding, and in-app food, water, fasting, backup, workout, and subscription presentations. Existing routes and layouts are retained. Photos, exercise illustrations, and live voice feedback retain their information-bearing visuals. Dark gray, other accent choices, energy/volume units and localizations are preserved.

No change to persisted data, backup formats, nutrition calculations, AI provider selection, billing, or the server APIs.

## Completion criteria

Flash is an app-wide theme, not a Home or navigation skin. A screen or presentation is not complete just because it inherits the app background. Verify the actual visible typography, action states, borders, and fills in light and dark, including in nested dropdowns.

| Area | Include in visual and interaction review |
| --- | --- |
| Home | Day picker, grouped and standalone Add Food methods, sort and serving-unit menus, analysis and result, diary edits and confirmations, water and fasting, saved meals, and nutrition picker |
| Progress | Every destination, chart details, weight and body-fat editors, body measurements, workout history, and challenges |
| Coach and voice | Empty, conversation, composing and attachment menus, voice panels, access notices, and confirmations |
| Workouts | Both modes, exercise details, filters and sorting, date and set controls, routines, and all pickers and editors |
| Settings and onboarding | Every category and onboarding step, appearance and unit selectors, model/provider menus, data import/export, subscription and credits, and first-run/update prompts |

**Selectors stay anchored:** On iOS, keep native Liquid Glass menus for Log Food and its groups, diary sort, meal type and units, Coach attachments, workout preferences, and other simple choices. The triggers and surrounding page use the Flash theme; the system owns the open menu. On Android, Log Food and common Settings choices (including Training Split, RPE Scale, and Rest Timer) use outlined, scrollable anchored dropdowns. Tapping a logging method can still open its actual editor or capture flow; opening the selector itself does not present a sheet.

**Known remaining coverage:** Remaining screen-specific surfaces, decorative gradients, and unverified screens on both platforms are open work. Do not mark the redesign complete until both platforms have been audited against the table. Preserve appropriate iOS Liquid Glass for menus, alerts and toolbar actions, along with OS-owned UI such as permission prompts, keyboards, system file/photo pickers, and share sheets.

### Presentation review

- Keep one visual hierarchy inside a presentation: a readable heading, one prominent action, and quieter secondary tasks. Prefer a single frame and subtle row rules over framing every row in a framed sheet. Use solid accents for selection and progress; keep gradients only when they help read a chart, photo, or live voice state.
- Standard iOS app sheets and popovers share `FlashPresentation`; the sheet body still needs its own layout review. Native menus, Cancel/Done/navigation toolbars, alerts, and system share and web controllers are intentional platform surfaces. Full-screen camera and scanner previews keep legible controls over the image rather than taking on the surrounding page's paper color.
- Standard Android sheets and alerts use `FlashPresentations`; anchored selectors stay anchored. Custom dialogs need separate review: the nutrient picker keeps its action row outside the scrollable list; photo-loading dialogs share the Flash panel; camera, barcode and photo-viewer content keep a neutral preview background.
- Workout text review uses dividers between exercises instead of a stack of cards on both platforms. The action rows and set fields reflow at large text sizes. Android's Health Connect nutrition import uses the shared alert frame, and the custom-exercise fields keep their anchored menus and selection behavior.
- Test an actual change, not just opening a surface. The nutrient-picker regressions remove a selected nutrient, save, and verify the summary updates while Water remains. The iOS provider regression chooses Apple Intelligence from the native menu and checks the resulting system model without requiring the device model to be available.

### Responsive layout pass

- Food editors must render a single action surface. Throughout iOS, existing native Liquid Glass controls (including toolbar Cancel, Save, and Log) stay native; do not add filled Flash buttons inside them. Apply Flash to the app-owned page and sheet content around those controls. On Android, editor actions wrap when measured space or font scale requires it.
- Enter a long food name, try alternate serving units, open the keyboard, and verify Cancel, Save/Log, and any secondary action are visible, distinct, and tappable. Repeat with a photo and without one. Check that section edges and the pinned header do not obscure editable fields while scrolling.
- At accessibility text sizes, verify calendar dates remain single-line, recap labels can wrap, Coach's message input has its own usable width, and workout controls reserve scroll space instead of covering the diary. Automated layout cases check narrow Android editor actions and iOS editor button geometry; they do not replace device review.
- Check Android text scaling with the right rail present: the diary title and its actions need separate lines when space runs out, and Progress controls and workout stats must wrap rather than shrink or clip. Verify system icon contrast when app appearance differs from the device setting.
- Keep native iOS controls where they provide navigation, focus, or platform affordances. The body around them still needs a Flash surface, consistent insets, and readable text at the selected size.

## Android performance check

Startup reads one preferences snapshot off the UI thread and no longer waits indefinitely for a non-null profile. Diary filtering and sorting run off the UI thread, and the Home energy meter draws its 20 segments without re-laying out the panels on each animation frame.

Peer-tab switches no longer cross-fade two full screens. Navigating Home saves the inactive tab's state just like switching to another peer tab, while nested navigation retains its transitions. Measure first and repeat visits separately on physical hardware; removing the animation does not make first-time chart, diary, or exercise loading free.

On an API 36 emulator with 1,000 synthetic diary entries, six requested cold launches per build gave a median first-frame time of 1.57 s before (five completed; one timed out) and 1.40 s after (six completed). The scrolling results varied across rounds, so this does not establish a smoother scroll or a speedup on a physical phone. Repeat cold launch, tab-switch, and diary scrolling measurements on the same Android phone before making those claims. For startup, compare both `am start -S -W` and the updated build's `Fully drawn` log; for scrolling, reset and compare `dumpsys gfxinfo`.

## iPhone tab responsiveness

Home keeps recent day lookups warm and draws the animated energy meter in one pass. Coach builds only nearby chat rows and opens at the latest message without an extra jump. Progress builds offscreen cards lazily and keeps matching nutrition aggregates when revisiting the tab. Workouts no longer cross-fades its two modes, and opening the logger no longer walks all past sets to check every cached exercise summary. These changes remove work from navigation; verify first and repeat visits with a real diary and conversation before reporting a hardware latency improvement.

## Verify before release

1. Open the rail destinations, reselect Settings or About from a nested page, then hide and re-enable the Progress and Workouts pairs. Confirm that Progress keeps its range across tabs and uses it for all charts, and Exercise Library keeps its search. Verify that a BYOK user with verified no active plan can open and dismiss the Plus / Pro paywall without changing AI mode, and that the shortcut is absent for active subscribers. Exercise a system shortcut and an assistant route.
2. Pick a historical day, change weeks, and return to today. Compare Home, Nutrition Details, and persisted entries with kcal/kJ, custom nutrients, Water, and Fasting enabled.
   The Home calendar should open in a compact popup. Swipe from the calorie panel, Water, or View More to move one day; a vertical drag should only scroll. A tap on View More opens Nutrition Details, but a swipe over it never opens the sheet, including when the day cannot change. The week strip and diary rows keep their separate gestures. In App Settings, turn Haptics off and verify app-generated feedback stops without disabling the system notification fallback for workout rest timers.
3. Check that the Home dock shows equal squares and leaves Log Food readable at standard and large text. Reassign a shortcut in + Menu and confirm it persists and opens the selected flow. Open Camera from either the dock or Log Food. Edit a result, cancel and retry capture, sort and share a meal, combine entries, and remove food/water/fasting entries using the existing confirmations. Open Live Voice from a non-Home tab with no provider configured and confirm the access explanation can be dismissed back to that tab.
4. Inspect both appearances, Dark gray, another accent, a small phone, a large font size, right-to-left layout, and an open keyboard. Confirm the diary's first and last rows remain reachable and the rail stays on the physical right without widening the active page. Native sheets and other full-screen presentations may cover the rail; dismissing them returns to the same layout.
5. Inspect an app panel and a popup from each area: Home logging and edit, Progress and weight logging, Coach and attachment actions, Workouts and the exercise picker, Settings and a preference picker, and onboarding and its first dialog. Inspect the hosted plan picker without starting a purchase.
6. On a fresh iOS launch, tap Log Food and verify its anchored native menu shows tappable options. Open a group, dismiss and reopen, then open Manual Entry. If all methods have been intentionally hidden, the menu should show an empty state. In Settings → Workout, open Rest Timer, choose a value, and confirm it updates without opening a sheet. Check the equivalent Android menu and saved value.
7. On a narrow Android phone and iPhone, check the on-device model status, license/source links and download controls with standard and large text. Links and actions should move as units rather than breaking words into narrow columns. Check long provider and model names on the same pages without changing credentials or starting a download.
