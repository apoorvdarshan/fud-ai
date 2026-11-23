# Fud Pink Neo

The approved direction is **04 Flash**: Fud Pink neo-brutalist Home with a right-side rail. Native SwiftUI and Compose use solid electric pink `#FF006A`, strong frame lines, a split energy display, a pinned food logging action, and the user's own meal photos. The rail stays on the right; text and content layout still follow the current language.

## What the first pass changes

- **App shell:** Home, Progress, Coach, Settings, and Workouts use the right rail. Hidden-tab choices, Workouts mode, Settings reselect, update badge, onboarding, and system/assistant routes still use their existing state. Short windows can scroll the rail. The keyboard gives its space back to the content.
- **Home and diary:** native date picker and the existing week pager both drive the current diary date. The wide energy panel and narrow goal panel use the existing unit preference, goals, and burn summaries. The grid still shows the user's chosen nutrients; Water remains an extra summary. Larger text can reflow the nutrition panels into fewer columns. Food, water, and fasting still share the diary, with food-only nutrition totals.
- **Actions:** Log Food opens the existing configured add menu on each platform—photo, gallery, voice, text, barcode, manual, saved meals and other enabled actions. The rail's camera goes through the same camera shortcut as the launcher (today's diary, including the existing active-fast restrictions). The assistant and favorites remain available when enabled. Sorting, sharing, selection, combining, editing, and removal keep their existing handlers.
- **Appearance:** Flash uses near-white paper `#FFFDFC` in light mode and near-black paper `#131114` in dark mode. The energy panel, selected day/tab, and Log Food action use flat fills, not gradients. The shared style also applies to Progress cards, Coach messages and composer, Workouts controls, Settings, onboarding, and in-app food, water, fasting, backup, workout, and subscription presentations. Existing routes and layouts are retained. Photos, exercise illustrations, and live voice feedback retain their information-bearing visuals. Dark gray, other accent choices, energy/volume units and localizations are preserved.

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

**App-owned menus migrated on iOS:** Log Food (including grouped, standalone, water, and fasting actions), diary sort, meal type and serving units, ingredient intake, Coach attachments, workout single-choice preferences, diary export range, and Settings appearance, theme color, week start, energy/water units, and share-link format. Menu selections hand off to the existing destinations after the Flash panel dismisses.

**Known remaining coverage:** iOS still uses native-styled menus, menu pickers, context menus, and alerts for other app-owned actions. Treat these, remaining screen-specific decorative gradients, and unverified screens on both platforms as open work. Do not mark the redesign complete until both platforms have been audited against the table. Only genuinely OS-owned UI, such as permission prompts, keyboards, system file/photo pickers, and share sheets, is exempt.

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

## Verify before release

1. Open all five destinations, reselect Settings from a nested page, then hide and re-enable a tab. Exercise a system shortcut and an assistant route.
2. Pick a historical day, change weeks, and return to today. Compare Home, Nutrition Details, and persisted entries with kcal/kJ, custom nutrients, Water, and Fasting enabled.
3. Log via the dock and via the rail camera. Edit a result, cancel and retry capture, sort and share a meal, combine entries, and remove food/water/fasting entries using the existing confirmations.
4. Inspect both appearances, Dark gray, another accent, a small phone, a large font size, right-to-left layout, and an open keyboard. Confirm the first and last diary rows remain reachable and the rail does not obscure any control.
5. Inspect an app panel and a popup from each area: Home logging and edit, Progress and weight logging, Coach and attachment actions, Workouts and the exercise picker, Settings and a preference picker, and onboarding and its first dialog. Inspect the hosted plan picker without starting a purchase.
6. On a fresh iOS launch, open Log Food and confirm the first visible frame has tappable options. Open a group, go back, dismiss and reopen, then open Manual Entry. If all methods have been intentionally hidden, the menu should show an empty state instead of a blank panel.
