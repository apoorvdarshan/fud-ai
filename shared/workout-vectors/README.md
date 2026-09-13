# Workout illustration assets

This directory is the canonical source for gender-aware exercise animation frames.
The runtime manifest supports authored SVG and transparent PNG frame sets.

## Naming contract

Each complete exercise set uses:

```text
<exercise-id>_male_<version>_<frame>.<format>
<exercise-id>_female_<version>_<frame>.<format>
```

Each manifest entry contains 3–5 ordered frames with matching male and female sets.
The apps select an authored animation only when both variants and every packaged file
are complete. Upstream Free Exercise DB JPEG photos are no longer shipped; the
production corpus covers every catalogue exercise.
`exercise-visual-manifest.json` is the runtime source of truth for format, frame names,
frame count, and the representative static frame. The current production v2 PNG sets
use four frames, with the exact motion adapted to each exercise:

1. setup or neutral start
2. first action phase
3. transition, return, or maximum-range phase
4. mirrored action or controlled return phase

All v2 PNG sequences use the Barbell Full Squat visual language: realistic hand-drawn
dark ink contours, natural anatomy and cel/painterly shading, charcoal-black training
kit, restrained muted-red accents, transparent cutouts, and consistent framing weight.
The production corpus contains exactly 875 exercises and 7,000 PNG frames: four male
and four female frames for every exercise in the bundled FreeExerciseDB catalogue.

Personal Info selects the male or female set. The existing `Other` convention uses
the male artwork until a dedicated inclusive visual set is designed.

## Platform packaging and runtime delivery

**Product decision: frames are local.** The corpus is ~1.2 GB (7,000 PNGs at roughly
175 KB each) and the complete set is **packaged into both apps** so every exercise
animates offline with no network dependency — there is no required CDN and shipping
builds never contact `assets.fud-ai.app`. This directory stays the single canonical
copy: neither platform keeps a second checked-in copy of the frames (no iOS
`*.imageset`s), both consume this folder directly at build time.

| | Android | iOS |
| --- | --- | --- |
| Packaging | `app/build.gradle.kts` task `prepare<Variant>WorkoutVectorAssets` copies `*_v2_*.png` + the manifest into a generated assets directory (flat `assets/<name>.png`) for **every** build type, release included | the app target's **Copy Workout Frames** run-script phase (`ios/scripts/copy_workout_frames.sh`) copies `*_v2_*.png` + the manifest → `calorietracker.app/workout-vectors/<name>.png` |
| Filtering | only the manifest and `*_v2_*.png` are packaged; the copied set must equal the manifest's frame sequences or the build fails | same filter and same manifest check; `README.md`, `sample-pack.txt` and the SVG pilot never enter the IPA |
| Manifest | `exercise-visual-manifest.json` asset | `Assets.xcassets/ExerciseVisualManifest.dataset` (byte-identical) |
| Runtime | `WorkoutFrameStore.kt` (Coil fetcher) | `WorkoutFrameStore.swift` |
| Resolution order | 1. device cache → 2. **bundled asset** → 3. download, only if `BuildConfig.WORKOUT_VECTORS_BASE_URL` is non-empty | 1. device cache → 2. **bundled `workout-vectors/`** → 3. download, only if a base URL is configured |
| Download default | `""` in every build type (disabled) | `nil` (disabled); no default base URL exists in code |
| Debug opt-in | `workout.vectors.base.url=…` in `android/local.properties` (debug/debug2 only) | launch argument `-WorkoutVectorsBaseURL …` (Debug only) |

Bundled frames are trusted as-is (the APK/AAB and the IPA are code-signed, and the
packaging steps copy the frames byte-for-byte — real copies, never hard links or
symlinks, so build outputs can be cleaned without touching this directory);
`sync_workout_visual_assets.py --check` and both packaging steps verify at build time
that the bundled set is exactly the manifest's frame sequences, and the iOS unit tests
confirm the bundled bytes match the manifest digests and that no tooling files leaked
into the bundle. The per-frame 16-hex SHA-256 prefixes in the manifest
(`maleFrameDigests` / `femaleFrameDigests`) remain the cache filename and integrity
check for the device cache and the optional download path
(`<base>/<name>.png?v=<digest>`), so that path stays safe to enable. On both platforms a
cached file is served only after it verifies (size, PNG signature, digest); anything
else is deleted and the bundled frame is used, so a corrupt cache entry can never hide
a bundled frame behind the placeholder.

A frame that is genuinely missing from the corpus leaves the existing icon placeholder
in place; failures are remembered for 60 s so a missing frame is not retried on every
animation tick.

### Known ship blocker: store size

- **Google Play:** the AAB base module is now ~1.2 GB, far above Play's 200 MB base
  module cap; Play will reject the upload as-is. **The owner accepts this for now** in
  exchange for offline, dependency-free images. Sideloaded / GitHub-release APKs are
  unaffected. Options when this must be resolved: Play Asset Delivery (install-time
  asset pack), or on-device download of a subset — neither is wired up today.
- **App Store:** the IPA grows by ~1.2 GB. This is within Apple's 4 GB limit, but
  users on cellular see the >200 MB download prompt and the first install takes
  noticeably longer.

### Debug / development workflows

- **Android:** every build type bundles the whole corpus by default. To iterate faster
  locally, debug and debug2 builds accept `-PworkoutVectors=sample` (the 12 exercises /
  96 frames listed in [`sample-pack.txt`](sample-pack.txt), ~15 MB) or
  `-PworkoutVectors=none` (manifest only). Release builds refuse both overrides. When
  frames are not bundled, the debug build can fall back to downloads from a local server:

  ```sh
  python3 -m http.server -d shared/workout-vectors 8765
  # android/local.properties
  workout.vectors.base.url=http://10.0.2.2:8765
  ```

- **iOS:** the Copy Workout Frames phase runs for Debug and Release alike, so both always
  bundle the whole corpus (incrementally — unchanged frames are not re-copied); no
  developer sample folder is needed. To exercise the download path, run the same local
  server and add the launch argument `-WorkoutVectorsBaseURL http://localhost:8765` to
  the calorietracker scheme (ATS already allows local networking).

- **Optional CDN mirror:** `scripts/publish_workout_vectors.py` can still upload the
  corpus to an R2 bucket for the debug download path, but nothing in a shipping build
  depends on it.

The original SVG pilot is retained for comparison but is no longer referenced by the
runtime manifest. Its deterministic generator validates only those legacy SVG files
and cannot overwrite the production manifest:

```sh
python3 scripts/generate_barbell_full_squat_svg_pilot.py --check
```

The sync command intentionally rejects partial corpora, unknown exercise IDs, any
`*_v2_*.imageset` reappearing in the iOS asset catalog, an Xcode project whose app
target no longer runs the Copy Workout Frames phase (checked by following the project's
object references, not by string search) or that copies this raw directory as a folder
reference, and anything other than the complete 875-exercise/7,000-frame set
(`Running_Outdoor` and `Walking_Outdoor` are quick-log activities without
illustrations). After changing a v2 sequence, regenerate both runtime manifests (frame
names + digests) and validate; the next app build picks the frames up automatically:

```sh
python3 scripts/sync_workout_visual_assets.py
python3 scripts/sync_workout_visual_assets.py --check
```

For pixel-level QA, install Pillow and run the following check. It decodes every
frame and rejects invisible artwork, fully opaque exports, and solid-color masks
accidentally saved in place of the illustration:

```sh
python3 scripts/verify_workout_visual_pixels.py
```

This basic pixel check does **not** prove that enclosed background gaps are clean
or that consecutive frames have consistent scale. The September 2026 quality
review found baked checkerboards inside otherwise transparent PNGs. Read-only
candidate triage is available with:

```sh
python3 scripts/audit_workout_visual_quality.py
```

See `artifacts/workout-visual-qa/README.md` for numerical versus visual-review
coverage and rejected built-in repair attempts. Do not treat candidate flags as
accepted fixes. Workout list, grid, and diary thumbnails continue to animate;
the image rollout must not disable existing animation behavior.

The one-time importer used to reconstruct the canonical corpus from completed local
generation batches validates every source before writing and copies only exact
canonical filenames:

```sh
python3 scripts/import_workout_visual_assets.py --dry-run
python3 scripts/import_workout_visual_assets.py
```

## Torso Rotation recovery

The original final exports for this exercise contained black alpha-mask silhouettes.
The eight replacements use the built-in image tool to edit the full-color batch-094
source illustrations, which show the exercise ball required by the database.
The shared prompt for each gender/frame was:

> Remove the baked white/light-gray checkerboard background, preserving the exact
> athlete, full-color hand-drawn ink/cel shading, charcoal outfit with muted-red
> trim, exercise ball, body proportions, foot positions, and torso/ball motion.
> Return a genuinely transparent 1024x768 landscape PNG with the entire person and
> ball visible, centered with clear margins. No crop, text, grid, watermark,
> decorative background, or multiple panels.

Male frame 1 additionally requested that the head look toward the ball on image-left.
Final packaging preserves existing alpha, removes a residual neutral perimeter when
the tool returns RGB, and resizes onto the standard transparent 1024x768 canvas.
