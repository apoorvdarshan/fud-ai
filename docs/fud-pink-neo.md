# Fud Pink Neo

The approved direction is **04 Flash**: Fud Pink neo-brutalist Home with a right-side rail. Native SwiftUI and Compose use solid electric pink `#FF006A`, strong frame lines, a split energy display, a pinned food logging action, and the user's own meal photos. The rail stays on the right; text and content layout still follow the current language.

## What the first pass changes

- **App shell:** Home, Progress, Coach, Settings, and Workouts use the right rail. Hidden-tab choices, Workouts mode, Settings reselect, update badge, onboarding, and system/assistant routes still use their existing state. Short windows can scroll the rail. The keyboard gives its space back to the content.
- **Home and diary:** native date picker and the existing week pager both drive the current diary date. The wide energy panel and narrow goal panel use the existing unit preference, goals, and burn summaries. The grid still shows the user's chosen nutrients; Water remains an extra summary. Larger text can reflow the nutrition panels into fewer columns. Food, water, and fasting still share the diary, with food-only nutrition totals.
- **Actions:** Log Food opens the existing configured add menu on each platform—photo, gallery, voice, text, barcode, manual, saved meals and other enabled actions. The rail's camera goes through the same camera shortcut as the launcher (today's diary, including the existing active-fast restrictions). The assistant and favorites remain available when enabled. Sorting, sharing, selection, combining, editing, and removal keep their existing handlers.
- **Appearance:** Flash uses near-white paper `#FFFDFC` in light mode and near-black paper `#131114` in dark mode. The energy panel, selected day/tab, and Log Food action use flat fills, not gradients. Home's voice shortcut is an outlined button; the live assistant still has its own controls. Dark gray, other accent choices, energy/volume units and localizations are preserved. Detailed Progress, Coach, Settings, Workouts, onboarding, and logging sheets still use their native layouts.

No change to persisted data, backup formats, nutrition calculations, AI provider selection, billing, or the server APIs.

## Verify before release

1. Open all five destinations, reselect Settings from a nested page, then hide and re-enable a tab. Exercise a system shortcut and an assistant route.
2. Pick a historical day, change weeks, and return to today. Compare Home, Nutrition Details, and persisted entries with kcal/kJ, custom nutrients, Water, and Fasting enabled.
3. Log via the dock and via the rail camera. Edit a result, cancel and retry capture, sort and share a meal, combine entries, and remove food/water/fasting entries using the existing confirmations.
4. Inspect both appearances, Dark gray, another accent, a small phone, a large font size, right-to-left layout, and an open keyboard. Confirm the first and last diary rows remain reachable and the rail does not obscure any control.
