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
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--indices", help="Comma-separated manifest indices to include (e.g. 1,2,21)")
    parser.add_argument(
        "--out",
        default=str(OUT),
        help="Output directory for candidate PNGs",
    )
    args = parser.parse_args()
    allowed = {int(x) for x in args.indices.split(",")} if args.indices else None
    out = Path(args.out)

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    copied = 0
    missing = []
    skipped = 0
    for result_path in sorted(WORKERS.glob("*/result.json")):
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if allowed is not None and result.get("index") not in allowed:
            continue
        if allowed is None and result.get("status") != "ready_for_review":
            skipped += 1
            continue
        for frame in result.get("frames", []):
            src = Path(frame["candidatePath"])
            dst = out / frame["filename"]
            if not src.is_file():
                missing.append(str(src))
                continue
            shutil.copy2(src, dst)
            copied += 1
    report = {"copied_frames": copied, "missing": missing, "skipped_not_ready": skipped, "output": str(out)}
    report_path = out.parent / "collection-report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if missing:
        raise SystemExit(f"missing {len(missing)} candidate files")


if __name__ == "__main__":
    main()
