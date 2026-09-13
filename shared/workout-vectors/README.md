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

The corpus is ~1.2 GB (7,000 PNGs at roughly 175 KB each). It is **never packaged
into a store binary**: Google Play caps the base module at 200 MB and the IPA would
grow by ~1.3 GB. Only `exercise-visual-manifest.json` (~0.75 MB) ships in the apps;
this directory is the single canonical copy of the frames (no iOS image-set copies).

Frames are delivered **on demand, per frame, over HTTPS** and cached on the device:

| Step | Android (`WorkoutFrameStore.kt`) | iOS (`WorkoutFrameStore.swift`) |
| --- | --- | --- |
| 1. device cache | `cacheDir/workout-vectors/v2/<name>.<digest>.png` | `Caches/WorkoutVectors/v2/<name>.<digest>.png` |
| 2. bundled sample | debug builds only (Gradle copies the sample pack into assets) | optional gitignored `calorietracker/WorkoutVectorsSample/` |
| 3. download | `BuildConfig.WORKOUT_VECTORS_BASE_URL` | `WorkoutFrameStore.defaultBaseURL` (Debug: `-WorkoutVectorsBaseURL` override) |

The download URL is `<base>/<name>.png?v=<digest>` where `<digest>` is the 16-hex
SHA-256 prefix recorded per frame in the manifest (`maleFrameDigests` /
`femaleFrameDigests`). The digest is the CDN cache key and the cache filename, and the
downloaded bytes are verified against it, so a repaired frame published under the same
name invalidates every cache automatically. A frame that cannot be produced (offline
before the first download, CDN unreachable) leaves the existing icon placeholder in
place — the same behaviour v6.1 shipped with. Failures are remembered for 60 s so an
offline user is not retried on every animation tick, and the device cache is trimmed to
~192 MB once it passes 256 MB. Opening one exercise costs ~0.7 MB (four frames for the
selected gender); no bulk download is required.

Default base URL: `https://assets.fud-ai.app/workout-vectors/v2` — an R2 bucket
(`fud-ai-assets`) connected to that custom domain. Provisioning + upload:

```sh
wrangler r2 bucket create fud-ai-assets
# Cloudflare dashboard → R2 → fud-ai-assets → Settings → Custom Domains → assets.fud-ai.app
# configure an rclone remote named "r2" (https://developers.cloudflare.com/r2/examples/rclone/)
python3 scripts/publish_workout_vectors.py --dry-run
python3 scripts/publish_workout_vectors.py
```

Frames upload with `Cache-Control: public, max-age=31536000, immutable`; the manifest
copy uploads with a five-minute lifetime for tooling that wants to read it remotely.

### Debug / development workflows

- **Android:** debug and debug2 builds bundle the sample pack listed in
  [`sample-pack.txt`](sample-pack.txt) (12 exercises, 96 frames, ~15 MB) as flat assets,
  so those exercises animate offline. `-PworkoutVectors=all` bundles the whole corpus for
  local QA, `-PworkoutVectors=none` gives release parity. Release builds refuse anything
  but the manifest. To test downloads against the corpus without a CDN, serve it locally
  and point the debug build at it in `android/local.properties`:

  ```sh
  python3 -m http.server -d shared/workout-vectors 8765
  # android/local.properties
  workout.vectors.base.url=http://10.0.2.2:8765
  ```

- **iOS:** run the same local server and add the launch argument
  `-WorkoutVectorsBaseURL http://localhost:8765` to the calorietracker scheme (ATS
  already allows local networking). Alternatively copy the sample pack into the
  gitignored developer folder, which the synchronized Xcode group picks up on the next
  build: `python3 scripts/build_workout_vectors_sample.py --output ios/calorietracker/WorkoutVectorsSample`.
  Delete that folder before archiving; `sync_workout_visual_assets.py --check` warns while
  it exists.

The original SVG pilot is retained for comparison but is no longer referenced by the
runtime manifest. Its deterministic generator validates only those legacy SVG files
and cannot overwrite the production manifest:

```sh
python3 scripts/generate_barbell_full_squat_svg_pilot.py --check
```

The sync command intentionally rejects partial corpora, unknown exercise IDs, any
`*_v2_*.imageset` reappearing in the iOS asset catalog, and anything other than the
complete 875-exercise/7,000-frame set (`Running_Outdoor` and `Walking_Outdoor` are
quick-log activities without illustrations). After changing a v2 sequence, regenerate
both runtime manifests (frame names + digests), validate, then publish:

```sh
python3 scripts/sync_workout_visual_assets.py
python3 scripts/sync_workout_visual_assets.py --check
python3 scripts/publish_workout_vectors.py
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
