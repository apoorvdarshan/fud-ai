#!/usr/bin/env python3
"""Extract human-approved repair decisions into a committed freeze manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BATCHES = ROOT / "artifacts/workout-visual-qa/review-batches"
VECTORS = ROOT / "shared/workout-vectors"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/workout-visual-qa/human-approved-candidates.json")
    args = parser.parse_args()

    exercises: dict[str, dict] = {}
    sources: list[tuple[Path, str]] = []
    for decisions_path in sorted(BATCHES.glob("batch-*/repair-pass-01/human-decisions.json")):
        sources.append((decisions_path, decisions_path.parts[-3]))
    for batch_id in ("cursor-pilot-10", "cursor-cloud-100"):
        path = ROOT / f"artifacts/workout-visual-qa/{batch_id}/human-decisions.json"
        if path.is_file():
            sources.append((path, batch_id))
    for decisions_path, batch in sources:
        payload = json.loads(decisions_path.read_text(encoding="utf-8"))
        for exercise_id, row in payload.get("decisions", {}).items():
            if row.get("decision") != "approve_candidate":
                continue
            entry = {
                "exercise_id": exercise_id,
                "batch": batch,
                "reviewed_at": row.get("reviewedAt"),
                "notes": row.get("notes", ""),
                "source_hashes": row.get("sourceHashes", []),
                "candidate_hashes": row.get("candidateHashes", []),
            }
            if exercise_id in exercises:
                raise SystemExit(f"duplicate approve_candidate for {exercise_id}")
            exercises[exercise_id] = entry

    manifest = {
        "verified_at": datetime.now(timezone.utc).isoformat(),
        "approved_count": len(exercises),
        "scope": "Human-approved repair candidates — frozen, do not re-repair or overwrite",
        "exercises": sorted(exercises.values(), key=lambda row: (row["batch"], row["exercise_id"])),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.output} ({manifest['approved_count']} exercises)")


if __name__ == "__main__":
    main()
