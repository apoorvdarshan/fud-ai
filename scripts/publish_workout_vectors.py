#!/usr/bin/env python3
"""Publish the workout frame corpus to a Cloudflare R2 bucket (optional tooling).

Shipping builds bundle the corpus locally and never contact a CDN; this script only
exists for the optional debug download path (`WorkoutFrameStore` with an explicit
base URL override), which requests frames as `<base-url>/<name>.png?v=<digest>`,
where `<digest>` comes from `exercise-visual-manifest.json`. Objects are therefore
uploaded flat (`workout-vectors/v2/<name>.png`) with a long immutable cache
lifetime; a repaired frame gets a new digest and thus a new cache key automatically.

Prerequisites (one-time):

    wrangler r2 bucket create fud-ai-assets
    # Connect the bucket to the public custom domain used by the apps
    # (default: assets.fud-ai.app) in the Cloudflare dashboard: R2 -> bucket ->
    # Settings -> Custom Domains.
    # Configure an rclone remote named "r2" with the account's S3 credentials:
    # https://developers.cloudflare.com/r2/examples/rclone/

Usage:

    python3 scripts/sync_workout_visual_assets.py --check
    python3 scripts/publish_workout_vectors.py --dry-run
    python3 scripts/publish_workout_vectors.py

Only rclone is invoked; nothing is written to the repository.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SHARED_DIRECTORY = REPOSITORY_ROOT / "shared" / "workout-vectors"
DEFAULT_REMOTE = "r2"
DEFAULT_BUCKET = "fud-ai-assets"
DEFAULT_PREFIX = "workout-vectors/v2"
FRAME_CACHE_CONTROL = "public, max-age=31536000, immutable"
MANIFEST_CACHE_CONTROL = "public, max-age=300"


def run(command: list[str], *, dry_run: bool) -> None:
    print("$ " + " ".join(command))
    if dry_run:
        command = command + ["--dry-run"]
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--remote", default=DEFAULT_REMOTE, help="rclone remote name (default: %(default)s)")
    parser.add_argument("--bucket", default=DEFAULT_BUCKET, help="R2 bucket (default: %(default)s)")
    parser.add_argument("--prefix", default=DEFAULT_PREFIX, help="Object key prefix (default: %(default)s)")
    parser.add_argument("--dry-run", action="store_true", help="Show what rclone would transfer.")
    arguments = parser.parse_args()

    if shutil.which("rclone") is None:
        print("error: rclone is required (https://rclone.org/install/)", file=sys.stderr)
        return 1

    check = subprocess.run(
        [sys.executable, str(REPOSITORY_ROOT / "scripts" / "sync_workout_visual_assets.py"), "--check"],
        cwd=REPOSITORY_ROOT,
    )
    if check.returncode != 0:
        print("error: corpus validation failed; fix the corpus/manifest before publishing", file=sys.stderr)
        return check.returncode

    destination = f"{arguments.remote}:{arguments.bucket}/{arguments.prefix}"
    try:
        run(
            [
                "rclone", "copy", str(SHARED_DIRECTORY), destination,
                "--include", "*_v2_*.png",
                "--checksum",
                "--transfers", "32",
                "--header-upload", f"Cache-Control: {FRAME_CACHE_CONTROL}",
                "--header-upload", "Content-Type: image/png",
                "--progress",
            ],
            dry_run=arguments.dry_run,
        )
        run(
            [
                "rclone", "copyto",
                str(SHARED_DIRECTORY / "exercise-visual-manifest.json"),
                f"{destination}/exercise-visual-manifest.json",
                "--header-upload", f"Cache-Control: {MANIFEST_CACHE_CONTROL}",
                "--header-upload", "Content-Type: application/json",
            ],
            dry_run=arguments.dry_run,
        )
    except subprocess.CalledProcessError as error:
        print(f"error: rclone exited with {error.returncode}", file=sys.stderr)
        return error.returncode

    print(f"published workout vectors to {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
