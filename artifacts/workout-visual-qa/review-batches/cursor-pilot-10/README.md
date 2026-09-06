# Cursor pilot — 10 exercises

First repair pass using **local Python background cleanup** (not Codex Imagen).

## Exercises (batch-06 indices 1–10)

1. Cable_Shoulder_Press
2. Cable_Shrugs
3. Calf-Machine_Shoulder_Shrug
4. Car_Drivers
5. Childs_Pose
6. Chin_To_Chest_Stretch
7. Clean_and_Press
8. Clean_Deadlift
9. Clean_from_Blocks
10. Clean_Shrug

**89 approved exercises are frozen** in [`../human-approved-candidates.json`](../human-approved-candidates.json) — not touched.

## Run background repair

```sh
/tmp/fudai-workout-matting.FCLmtL/venv/bin/python scripts/repair_workout_visual_backgrounds.py \
  --exercise Cable_Shoulder_Press --exercise Cable_Shrugs \
  --exercise Calf-Machine_Shoulder_Shrug --exercise Car_Drivers \
  --exercise Childs_Pose --exercise Chin_To_Chest_Stretch \
  --exercise Clean_and_Press --exercise Clean_Deadlift \
  --exercise Clean_from_Blocks --exercise Clean_Shrug \
  --output artifacts/workout-visual-qa/cursor-pilot-10/candidates \
  --priority-audit artifacts/workout-visual-qa/audit.json --previews
```

## Review (localhost UI)

```sh
node scripts/serve_cursor_pilot_review.mjs
```

Open **http://127.0.0.1:8766** — animated before/after on dark + light backgrounds. Buttons save to `cursor-pilot-10/human-decisions.json` (does not touch app images).

Or static PNG contact sheets:

## Next (after your visual check)

- **0px changed frames** → Cursor `GenerateImage` with source frame as reference (surgical edge/anatomy fix)
- **Alignment drift** → `workout_frame_alignment.py` once background is acceptable
- **Human approve** → add to manifest; production promotion only when you ask
