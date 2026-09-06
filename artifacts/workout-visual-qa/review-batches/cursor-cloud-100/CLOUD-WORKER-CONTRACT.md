# Cursor Cloud 100 — worker contract

One shard owns **10 exercises**, all eight frames each (male/female, frames 0–3).

## Inputs

- Canonical PNGs: `shared/workout-vectors/` (resolve by basename if manifest paths differ)
- Manifest: `artifacts/workout-visual-qa/review-batches/cursor-cloud-100/cursor-cloud-100.json`
- Exercise mechanics: `ios/calorietracker/Resources/FreeExerciseDB/dist/exercises.json`

## Repair order

1. **Script first** — `scripts/repair_workout_visual_backgrounds.py --exercise <ID> …` per exercise
2. **Cursor `GenerateImage`** only when script leaves visible edge/background/anatomy defects (use `reference_image_paths` pointing at source frame; surgical edit, not full redraw)
3. **Green-chroma** — `scripts/key_workout_chroma.py` when a generated insert needs real alpha
4. Never use Codex `imagegen`, ChatGPT Imagen, or Hugging Face generation

## Outputs (per exercise)

Write under `artifacts/workout-visual-qa/cursor-cloud-100/workers/NNN/` (NNN = manifest index, zero-padded):

- Eight candidate PNGs with **exact source basenames**
- `result.json`:

```json
{
  "exerciseId": "Example_Exercise",
  "shard": 1,
  "index": 1,
  "status": "ready_for_review",
  "findings": [],
  "unresolved": [],
  "frames": [
    {
      "filename": "Example_Exercise_male_v2_0.png",
      "sourcePath": "...",
      "sourceSha256": "...",
      "candidatePath": "...",
      "candidateSha256": "...",
      "method": "script_background_cleanup",
      "visuallyReviewed": true
    }
  ]
}
```

Status values: `ready_for_review`, `needs_more_work`, `blocked`.

## Rules

- `shared/workout-vectors/` is **read-only** — never overwrite production
- Preserve illustration style, person, equipment, pose sequence
- Reject painted checkerboard as transparency
- Do not promote to app assets
- Do not touch exercises listed in `human-approved-candidates.json`

## Shard command

```sh
python3 scripts/run_cloud_worker_shard.py --shard 1
```

Shards 1–10 cover manifest indices 1–10, 11–20, …, 91–100.
