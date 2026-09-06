#!/usr/bin/env python3
"""Run background cleanup for one cursor-cloud-100 shard (10 exercises)."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "artifacts/workout-visual-qa/review-batches/cursor-cloud-100/cursor-cloud-100.json"
VECTORS = ROOT / "shared/workout-vectors"
BATCH_ROOT = ROOT / "artifacts/workout-visual-qa/cursor-cloud-100"
WORKERS = BATCH_ROOT / "workers"
STAGING = BATCH_ROOT / "staging"
VENV_ROOT = Path("/tmp/workout-repair-venv")
VENV = VENV_ROOT / "bin" / "python"
REPAIR = ROOT / "scripts/repair_workout_visual_backgrounds.py"
AUDIT = ROOT / "artifacts/workout-visual-qa/audit.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def repair_python() -> str:
    if VENV.is_file():
        return str(VENV)
    return sys.executable


def ensure_venv() -> None:
    if VENV.is_file():
        try:
            subprocess.run([str(VENV), "-c", "import cv2"], check=True, capture_output=True)
            return
        except subprocess.CalledProcessError:
            shutil.rmtree(VENV_ROOT)
    python = shutil.which("python3.13") or shutil.which("python3.12") or shutil.which("python3.11") or sys.executable
    subprocess.run([python, "-m", "venv", str(VENV_ROOT)], check=True)
    subprocess.run(
        [str(VENV), "-m", "pip", "install", "-q", "-r", str(ROOT / "scripts/requirements-workout-repair.txt")],
        check=True,
    )


def shard_exercises(shard: int, per_shard: int = 10) -> list[dict]:
    batch = json.loads(MANIFEST.read_text(encoding="utf-8"))
    start = (shard - 1) * per_shard
    end = start + per_shard
    return batch["exercises"][start:end]


def run_exercise(entry: dict, shard: int) -> dict:
    exercise_id = entry["exerciseId"]
    index = entry["index"]
    worker_dir = WORKERS / f"{index:03d}"
    worker_dir.mkdir(parents=True, exist_ok=True)
    stage = STAGING / f"{index:03d}"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    py = repair_python()
    cmd = [
        py,
        str(REPAIR),
        "--exercise",
        exercise_id,
        "--output",
        str(stage),
    ]
    if AUDIT.is_file():
        cmd.extend(["--priority-audit", str(AUDIT)])
    subprocess.run(cmd, check=False)

    frames = []
    changed = 0
    for gender in ("male", "female"):
        for i in range(4):
            name = f"{exercise_id}_{gender}_v2_{i}.png"
            source = VECTORS / name
            staged = stage / "images" / name
            candidate = worker_dir / name
            if not source.is_file():
                raise FileNotFoundError(source)
            if staged.is_file():
                shutil.copy2(staged, candidate)
                method = "script_background_cleanup"
                changed += 1 if sha256(source) != sha256(staged) else 0
            else:
                shutil.copy2(source, candidate)
                method = "unchanged"
            frames.append(
                {
                    "filename": name,
                    "sourcePath": str(source.resolve()),
                    "sourceSha256": sha256(source),
                    "candidatePath": str(candidate.resolve()),
                    "candidateSha256": sha256(candidate),
                    "method": method,
                    "visuallyReviewed": False,
                }
            )

    status = "ready_for_review" if changed else "needs_more_work"
    result = {
        "exerciseId": exercise_id,
        "shard": shard,
        "index": index,
        "status": status,
        "findings": entry.get("findings") or [],
        "unresolved": [] if changed else ["Script pass made no pixel changes; may need Cursor GenerateImage"],
        "frames": frames,
    }
    (worker_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shard", type=int, help="Shard number 1–10")
    parser.add_argument("--index", type=int, help="Single manifest index (1–100)")
    args = parser.parse_args()
    if bool(args.shard) == bool(args.index):
        raise SystemExit("pass exactly one of --shard or --index")
    ensure_venv()
    batch = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if args.index:
        entries = [e for e in batch["exercises"] if e["index"] == args.index]
        if len(entries) != 1:
            raise SystemExit(f"expected 1 exercise for index {args.index}, got {len(entries)}")
        shard = (args.index - 1) // 10 + 1
        result = run_exercise(entries[0], shard)
        print(f"index {args.index}: {entries[0]['exerciseId']} -> {result['status']}")
        return
    if not 1 <= args.shard <= 10:
        raise SystemExit("shard must be 1–10")
    entries = shard_exercises(args.shard)
    if len(entries) != 10:
        raise SystemExit(f"expected 10 exercises, got {len(entries)}")
    summary = []
    for entry in entries:
        summary.append(run_exercise(entry, args.shard))
        print(f"shard {args.shard}: {entry['exerciseId']} -> {summary[-1]['status']}")
    out = BATCH_ROOT / f"shard-{args.shard:02d}-summary.json"
    out.write_text(json.dumps({"shard": args.shard, "exercises": summary}, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
