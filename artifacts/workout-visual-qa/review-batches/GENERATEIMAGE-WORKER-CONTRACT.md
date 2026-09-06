# GenerateImage worker contract (wave-100 v2 + wave-200)

One Cloud Agent owns **10 exercises**, all 8 frames (male/female × 0–3). Script cleanup alone is **not** enough. If a frame still looks dirty on a dark background, you must use Cursor `GenerateImage`.

## Repair order

1. Run `python3 scripts/run_cloud_worker_shard.py --batch <BATCH> --shard N` (or per-exercise `--index`) so BiRefNet/rembg candidates exist.
2. **Open every candidate PNG** in that shard. View as if on a dark background.
3. If you see leftover floor/matte, pale fringe, baked checkerboard, clipped hands, melted anatomy, extra limbs, or dirty plate/bar edges → **Cursor `GenerateImage`** with `reference_image_paths` = that frame (script candidate, or source if candidate is worse).
4. Surgical edit only: same person, clothes, pose, equipment, 1024×768-class composition. Do **not** restyle or replace the athlete.
5. Prefer a flat vivid green `#00FF00` backdrop on generated inserts, then `python3 scripts/key_workout_chroma.py` for real alpha. Reject painted checkerboard.
6. If GenerateImage makes it worse (face swap, missing bench, new anatomy), keep the script candidate and mark that frame unresolved.

## Never

- Do not overwrite `shared/workout-vectors/`
- Do not open a pull request
- Do not spawn more Cloud Agents
- Do not skip visual inspection after the script
- Do not touch `Goblet_Squat` (already approved)

## Outputs

Force-add this shard’s `workers/NNN/` PNGs + `result.json` + `shard-NN-summary.json`. Set `method` to `generateimage_surgical` when you replaced a frame. `visuallyReviewed: true` on every frame you looked at.

Push a **new branch** only. No `main` push. No PR.
