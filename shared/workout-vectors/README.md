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

## Platform packaging

- Android merges this directory into the app's flat asset root and renders authored
  SVG or PNG frames in full color. It validates all packaged filenames against the
  shared manifest before exposing a set.
- iOS compiles byte-identical copies into image sets under
  `calorietracker/Assets.xcassets` and packages a byte-identical manifest as the
  `ExerciseVisualManifest.dataset`; raw masters stay outside the iOS target to avoid
  bundling every frame twice.

The original SVG pilot is retained for comparison but is no longer referenced by the
runtime manifest. Its deterministic generator validates only those legacy SVG files
and cannot overwrite the production manifest:

```sh
python3 scripts/generate_barbell_full_squat_svg_pilot.py --check
```

The sync command intentionally rejects partial corpora, unknown exercise IDs, stale
iOS image sets, and anything other than the complete 875-exercise/7,000-frame set.
After changing a v2 sequence, sync the iOS catalog copies and both runtime manifests,
then run the same command in validation-only mode:

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
