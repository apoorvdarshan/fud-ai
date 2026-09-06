#!/usr/bin/env python3
"""Rewrite Mac paths in batch JSON to the current repo root."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def normalize(obj, root: Path):
    if isinstance(obj, str):
        if "/shared/workout-vectors/" in obj.replace("\\", "/"):
            name = Path(obj).name
            return str((root / "shared/workout-vectors" / name).resolve())
        return obj.replace("/Users/apoorvdarshan/fud-ai", str(root))
    if isinstance(obj, list):
        return [normalize(x, root) for x in obj]
    if isinstance(obj, dict):
        return {k: normalize(v, root) for k, v in obj.items()}
    return obj


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json_file", type=Path)
    args = parser.parse_args()
    payload = json.loads(args.json_file.read_text(encoding="utf-8"))
    args.json_file.write_text(json.dumps(normalize(payload, ROOT), indent=2) + "\n", encoding="utf-8")
    print(f"normalized {args.json_file}")


if __name__ == "__main__":
    main()
