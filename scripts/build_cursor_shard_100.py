#!/usr/bin/env python3
"""Build the cursor-shard-100 review manifest (100 unapproved exercises)."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VECTORS = ROOT / "shared/workout-vectors"
BATCHES = ROOT / "artifacts/workout-visual-qa/review-batches"
QA = ROOT / "artifacts/workout-visual-qa"
OUT_DIR = BATCHES / "cursor-shard-100"
OUT = OUT_DIR / "cursor-shard-100.json"

DECISION_FILES = [
    QA / "cursor-cloud-100-done30/human-decisions.json",
    QA / "cursor-next-10/human-decisions.json",
    QA / "cursor-pilot-10/human-decisions.json",
    QA / "cursor-promoted-5/human-decisions.json",
    QA / "human-approved-candidates.json",
]


def frame_paths(exercise_id: str) -> list[str]:
    names = [f"{exercise_id}_{gender}_v2_{i}.png" for gender in ("male", "female") for i in range(4)]
    return [str((VECTORS / name).resolve()) for name in names]


def excluded_ids() -> set[str]:
    ids: set[str] = set()
    for path in DECISION_FILES:
        if not path.is_file():
            continue
        payload = json.loads(path.read_text(encoding="utf-8"))
        if "decisions" in payload:
            ids.update(payload["decisions"])
        for row in payload.get("exercises", []):
            eid = row.get("exercise_id") or row.get("exerciseId")
            if eid:
                ids.add(eid)
    for decisions_path in BATCHES.glob("batch-*/repair-pass-01/human-decisions.json"):
        payload = json.loads(decisions_path.read_text(encoding="utf-8"))
        ids.update(payload.get("decisions", {}))
    return ids


def load_exercises(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["exercises"]


def entry_from(src: dict, index: int) -> dict:
    exercise_id = src["exerciseId"]
    return {
        "index": index,
        "exerciseId": exercise_id,
        "severity": src.get("severity") or "pending",
        "findings": src.get("findings") or [],
        "suggestedRoute": src.get("suggestedRoute") or "script_fix",
        "sourceFramePaths": frame_paths(exercise_id),
    }


def main() -> None:
    excluded = excluded_ids()
    cloud = load_exercises(BATCHES / "cursor-cloud-100/cursor-cloud-100.json")
    leftover = [e for e in cloud if e["index"] >= 41 and e["exerciseId"] not in excluded]

    batch08 = load_exercises(BATCHES / "batch-08/batch-08.json")
    after_medium: list[dict] = []
    seen_medium = False
    leftover_ids = {e["exerciseId"] for e in leftover}
    for row in batch08:
        if row["exerciseId"] == "Incline_Push-Up_Medium":
            seen_medium = True
            continue
        if seen_medium and row["exerciseId"] not in excluded and row["exerciseId"] not in leftover_ids:
            after_medium.append(row)

    used = excluded | leftover_ids | {e["exerciseId"] for e in after_medium}
    for n in range(1, 9):
        used.update(e["exerciseId"] for e in load_exercises(BATCHES / f"batch-{n:02d}/batch-{n:02d}.json"))
    for extra in ("cursor-cloud-100", "cursor-pilot-10", "cursor-next-10", "cursor-promoted-5", "cursor-cloud-100-done30"):
        path = BATCHES / extra / f"{extra}.json"
        if path.is_file():
            used.update(e["exerciseId"] for e in load_exercises(path))

    library = load_exercises(VECTORS / "exercise-visual-manifest.json")
    extras: list[dict] = []
    for row in library:
        eid = row["exerciseId"]
        if eid in used:
            continue
        extras.append({"exerciseId": eid, "severity": "pending", "findings": [], "suggestedRoute": "script_fix"})
        if len(leftover) + len(after_medium) + len(extras) >= 100:
            break

    selected = leftover + after_medium + extras
    if len(selected) != 100:
        raise SystemExit(f"expected 100 exercises, got {len(selected)}")

    missing = [eid for src in selected for eid in [src["exerciseId"]] if not (VECTORS / f"{eid}_male_v2_0.png").is_file()]
    if missing:
        raise SystemExit(f"missing source frames: {missing}")

    exercises = [entry_from(src, index) for index, src in enumerate(selected, start=1)]
    batch = {
        "batchName": "cursor-shard-100",
        "description": (
            "Next 100 unapproved exercises after done30/next-10. "
            "cloud-100 indices 41-100 plus the following 40 library IDs. "
            "Script-only shards; no Cloud Agents."
        ),
        "exerciseCount": 100,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "excludedReviewedCount": len(excluded),
        "workspaceRoot": str(ROOT.resolve()),
        "sourceRoot": str(VECTORS.resolve()),
        "exercises": exercises,
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(batch, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(exercises)} exercises)")
    print("first", exercises[0]["exerciseId"], "last", exercises[-1]["exerciseId"])


if __name__ == "__main__":
    main()
