#!/usr/bin/env python3
"""Merge cursor-cloud-100 worker PNGs into candidates/images for review UI."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKERS = ROOT / "artifacts/workout-visual-qa/cursor-cloud-100/workers"
OUT = ROOT / "artifacts/workout-visual-qa/cursor-cloud-100/candidates/images"


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    copied = 0
    missing = []
    for result_path in sorted(WORKERS.glob("*/result.json")):
        result = json.loads(result_path.read_text(encoding="utf-8"))
        for frame in result.get("frames", []):
            src = Path(frame["candidatePath"])
            dst = OUT / frame["filename"]
            if not src.is_file():
                missing.append(str(src))
                continue
            shutil.copy2(src, dst)
            copied += 1
    report = {"copied_frames": copied, "missing": missing, "output": str(OUT)}
    report_path = ROOT / "artifacts/workout-visual-qa/cursor-cloud-100/collection-report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if missing:
        raise SystemExit(f"missing {len(missing)} candidate files")


if __name__ == "__main__":
    main()
