# Nutrition history import (issue #221)

Settings → Health & Data → Import nutrition history reads other apps' Health Connect
nutrition records on demand. Choose inclusive dates, grant read/history access,
preview, select one source app, and import. An empty result does not prove that
Zepp has exported its history: inspect the source records in Health Connect.

Imports are local snapshots, independent of Fud AI's existing one-time restoration
of its own records. Repeat imports skip records already imported, including meals
subsequently deleted locally. Source updates/deletions are not synchronized.
Uninstalling or clearing app data also clears the import ledger.

The source package and Health Connect record ID determine the local UUID. Nutrient
units match the existing Health Connect reader. Missing names use a localized
fallback; missing food mass is represented as one serving, without invented grams.
The log and ledger are saved in one DataStore transaction. Imported entries and
combined meals containing them are excluded at the Health Connect write boundary.
Intentionally re-logging an imported meal creates a new local meal without import
provenance. Imports from different source apps are not deduplicated by nutritional
values: select a single source to avoid shared-data duplicates.

History permission is requested only in this flow and only when the device supports
it. Without it, this UI conservatively accepts only the last 30 days, even if the
original permission grant would allow a longer period. Reading failure on any page
leaves the diary untouched. Existing app-owned restoration behavior is unchanged.

## Validation

Automated: `:app:testDebugUnitTest --tests '*HealthNutritionImportTest'` covers
identity, repeated imports, local deletion, local edits, duplicated pages, missing
names/macros, nutrient units, serialization, combined meals, and deliberate re-logs.

Device checks still required with real source data:

1. Confirm Zepp nutrition records exist in Health Connect, including an older meal.
2. Deny read access, preview, and confirm actionable feedback with no imported meals.
3. Grant nutrition/history access; choose a range and verify the preview's source,
   dates and counts. Import and compare daily calories/macros against the source.
4. Repeat the import; edit or delete one imported meal and repeat again. No duplicate
   or restored deleted meal should appear.
5. Edit an imported meal, reconnect Health Connect, and check that Fud AI has not
   written a second copy of it. Test combining an imported meal as well.
6. On a device without history support, confirm recent dates work and older ranges
   explain the access limitation. Revoke access while the preview is open and verify
   import is rejected until access is granted again.

This feature adds `READ_HEALTH_DATA_HISTORY`; include its use in the release's
Health Connect permission declaration when preparing the Play submission.
