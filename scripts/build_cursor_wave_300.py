#!/usr/bin/env python3
"""Combine wave-100 + wave-200 into one review batch for a single localhost UI."""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "artifacts/workout-visual-qa"
BATCHES = QA / "review-batches"
OUT_NAME = "cursor-wave-300"
SOURCES = ("cursor-wave-100", "cursor-wave-200")


def load_json(path: Path, fallback):
    if not path.is_file():
        return fallback
    return json.loads(path.read_text(encoding="utf-8"))


def hardlink_or_copy(src: Path, dst: Path) -> None:
    if dst.exists() or dst.is_symlink():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        dst.write_bytes(src.read_bytes())


def main() -> None:
    exercises = []
    decisions: dict = {}
    out_images = QA / OUT_NAME / "candidates" / "images"
    if out_images.exists():
        for old in out_images.glob("*"):
            old.unlink()
    out_images.mkdir(parents=True, exist_ok=True)
    copied = 0
    for name in SOURCES:
        batch = load_json(BATCHES / name / f"{name}.json", None)
        if not batch or not batch.get("exercises"):
            raise SystemExit(f"missing {name} manifest")
        for exercise in batch["exercises"]:
            row = dict(exercise)
            row["index"] = exercise["index"] if name == "cursor-wave-100" else 100 + exercise["index"]
            row["sourceBatch"] = name
            exercises.append(row)
        src_images = QA / name / "candidates" / "images"
        for png in sorted(src_images.glob("*.png")):
            hardlink_or_copy(png, out_images / png.name)
            copied += 1
        source_decisions = load_json(QA / name / "human-decisions.json", {}).get("decisions") or {}
        decisions.update(source_decisions)

    ids = [e["exerciseId"] for e in exercises]
    if len(ids) != len(set(ids)):
        raise SystemExit("duplicate exercise IDs in combined batch")
    if copied < 2400:
        raise SystemExit(f"expected 2400 candidate frames, copied {copied}")

    out_dir = BATCHES / OUT_NAME
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "batchName": OUT_NAME,
        "description": "Combined wave-100 redo + wave-200. One localhost review queue.",
        "exerciseCount": len(exercises),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceBatches": list(SOURCES),
        "exercises": exercises,
    }
    (out_dir / f"{OUT_NAME}.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    (QA / OUT_NAME / "human-decisions.json").write_text(
        json.dumps({"batch": OUT_NAME, "decisions": decisions, "updatedAt": payload["generatedAt"]}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"batch": OUT_NAME, "exercises": len(exercises), "frames": copied, "seeded_decisions": len(decisions)}))


if __name__ == "__main__":
    main()
