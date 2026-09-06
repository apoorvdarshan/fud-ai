#!/usr/bin/env python3
"""Merge shard worker PNGs into candidates/images for the review UI."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BATCH = "cursor-cloud-100"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch", default=DEFAULT_BATCH, help="Review batch name")
    parser.add_argument("--indices", help="Comma-separated manifest indices to include (e.g. 1,2,21)")
    parser.add_argument(
        "--all",
        action="store_true",
        help="Include exercises that are not status=ready_for_review",
    )
    parser.add_argument(
        "--out",
        help="Output directory for candidate PNGs",
    )
    args = parser.parse_args()
    workers = ROOT / "artifacts/workout-visual-qa" / args.batch / "workers"
    out = Path(args.out) if args.out else ROOT / "artifacts/workout-visual-qa" / args.batch / "candidates/images"
    allowed = {int(x) for x in args.indices.split(",")} if args.indices else None
    include_all = args.all or allowed is not None

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    copied = 0
    missing = []
    skipped = 0
    for result_path in sorted(workers.glob("*/result.json")):
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if allowed is not None and result.get("index") not in allowed:
            continue
        if not include_all and result.get("status") != "ready_for_review":
            skipped += 1
            continue
        for frame in result.get("frames", []):
            src = Path(frame["candidatePath"])
            if not src.is_file():
                src = result_path.parent / frame["filename"]
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
