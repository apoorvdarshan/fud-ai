#!/usr/bin/env python3
"""Build the leftover unused-exercise batch (not approved, not in the current 300)."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VECTORS = ROOT / "shared/workout-vectors"
QA = ROOT / "artifacts/workout-visual-qa"
BATCHES = QA / "review-batches"
OUT_NAME = "cursor-wave-leftover"
OUT_DIR = BATCHES / OUT_NAME
OUT = OUT_DIR / f"{OUT_NAME}.json"

SKIP_BATCHES = ("cursor-wave-100", "cursor-wave-200", "cursor-wave-300")


def load(path: Path, fallback=None):
    if not path.is_file():
        return fallback
    return json.loads(path.read_text(encoding="utf-8"))


def approved_ids() -> set[str]:
    ids: set[str] = set()
    for path in QA.glob("*/human-decisions.json"):
        payload = load(path, {})
        for key, row in (payload.get("decisions") or {}).items():
            if isinstance(row, dict) and row.get("decision") in ("approve_candidate", "keep_original"):
                ids.add(key)
        for row in payload.get("exercises") or []:
            eid = row.get("exercise_id") or row.get("exerciseId")
            if eid:
                ids.add(eid)
    for path in BATCHES.glob("*/repair-pass-01/human-decisions.json"):
        payload = load(path, {})
        for key, row in (payload.get("decisions") or {}).items():
            if isinstance(row, dict) and row.get("decision") in ("approve_candidate", "keep_original"):
                ids.add(key)
    frozen = load(QA / "human-approved-candidates.json", {})
    for row in frozen.get("exercises") or []:
        eid = row.get("exercise_id") or row.get("exerciseId")
        if eid:
            ids.add(eid)
    return ids


def skip_ids() -> set[str]:
    ids = approved_ids()
    for name in SKIP_BATCHES:
        batch = load(BATCHES / name / f"{name}.json", {})
        ids.update(e["exerciseId"] for e in batch.get("exercises") or [])
    return ids


def frame_paths(exercise_id: str) -> list[str]:
    names = [f"{exercise_id}_{gender}_v2_{i}.png" for gender in ("male", "female") for i in range(4)]
    return [str((VECTORS / name).resolve()) for name in names]


def main() -> None:
    skip = skip_ids()
    library = load(VECTORS / "exercise-visual-manifest.json")["exercises"]
    selected = [row for row in library if row["exerciseId"] not in skip]
    missing = [row["exerciseId"] for row in selected if not (VECTORS / f"{row['exerciseId']}_male_v2_0.png").is_file()]
    if missing:
        raise SystemExit(f"missing source frames: {missing[:8]}")
    exercises = [
        {
            "index": index,
            "exerciseId": row["exerciseId"],
            "severity": "pending",
            "findings": [],
            "suggestedRoute": "script_fix_then_generateimage",
            "sourceFramePaths": frame_paths(row["exerciseId"]),
        }
        for index, row in enumerate(selected, start=1)
    ]
    shards = (len(exercises) + 9) // 10
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "batchName": OUT_NAME,
        "description": (
            "Leftover unused exercises after the combined 300 review. "
            "Skips approved IDs and cursor-wave-100/200/300. "
            "Script first, then GenerateImage on dirty frames."
        ),
        "exerciseCount": len(exercises),
        "shardCount": shards,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "skippedCount": len(skip),
        "exercises": exercises,
    }
    OUT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "batch": OUT_NAME,
                "exercises": len(exercises),
                "shards": shards,
                "last_shard_size": len(exercises) - (shards - 1) * 10,
                "first": exercises[0]["exerciseId"] if exercises else None,
                "last": exercises[-1]["exerciseId"] if exercises else None,
                "skipped": len(skip),
            }
        )
    )


if __name__ == "__main__":
    main()
