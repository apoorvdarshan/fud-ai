#!/usr/bin/env python3
"""Copy the sample pack of workout frames (shared/workout-vectors/sample-pack.txt)
into a directory.

Both apps bundle the complete corpus, so this is only a developer convenience: for
example to serve a small subset from a local HTTP server while testing the optional
debug download path of `WorkoutFrameStore`:

    python3 scripts/build_workout_vectors_sample.py --output /tmp/workout-vectors-sample
    python3 -m http.server -d /tmp/workout-vectors-sample 8765

Android's `-PworkoutVectors=sample` performs the equivalent selection in Gradle.
`--all` copies the complete 7,000-frame corpus instead (~1.2 GB). The output
contains only `<name>.png` frames; the manifest is already bundled.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SHARED_DIRECTORY = REPOSITORY_ROOT / "shared" / "workout-vectors"
SAMPLE_PACK_LIST = SHARED_DIRECTORY / "sample-pack.txt"
GENDERS = ("male", "female")
FRAME_COUNT = 4


def sample_exercise_ids() -> list[str]:
    ids: list[str] = []
    for raw_line in SAMPLE_PACK_LIST.read_text().splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if line and line not in ids:
            ids.append(line)
    return ids


def sample_frame_files() -> list[Path]:
    files: list[Path] = []
    for exercise_id in sample_exercise_ids():
        for gender in GENDERS:
            for frame in range(FRAME_COUNT):
                files.append(SHARED_DIRECTORY / f"{exercise_id}_{gender}_v2_{frame}.png")
    return files


def copy_if_changed(source: Path, destination: Path) -> bool:
    if not source.is_file():
        raise FileNotFoundError(f"missing sample frame: {source}")
    if (
        destination.is_file()
        and destination.stat().st_size == source.stat().st_size
        and destination.stat().st_mtime >= source.stat().st_mtime
    ):
        return False
    shutil.copyfile(source, destination)
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--all", action="store_true", help="Copy every frame, not just the sample pack.")
    arguments = parser.parse_args()

    output: Path = arguments.output
    output.mkdir(parents=True, exist_ok=True)

    wanted = sorted(SHARED_DIRECTORY.glob("*_v2_*.png")) if arguments.all else sample_frame_files()
    wanted_names = {path.name for path in wanted}

    removed = 0
    for existing in output.iterdir():
        if existing.name not in wanted_names:
            if existing.is_dir():
                shutil.rmtree(existing)
            else:
                existing.unlink()
            removed += 1

    copied = 0
    try:
        for source in wanted:
            if copy_if_changed(source, output / source.name):
                copied += 1
    except FileNotFoundError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"workout vector sample: {len(wanted)} frames in {output} (copied {copied}, removed {removed})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
