#!/usr/bin/env python3
"""Validate the v2 workout illustration corpus and regenerate its runtime manifests.

`shared/workout-vectors` is the single canonical copy of the ~7,000 authored PNG
frames. The frames are *not* packaged into the store binaries: both apps bundle
only `exercise-visual-manifest.json` and fetch individual frames on demand from
the workout-vector CDN (see `shared/workout-vectors/README.md`). This script:

1. validates the corpus (complete male/female 4-frame sets, 1024x768 RGBA PNGs,
   one set per catalogue exercise),
2. writes the shared manifest and the byte-identical iOS `ExerciseVisualManifest`
   data set, including a per-frame content digest used for CDN cache busting and
   on-device download verification,
3. rejects any generated `*_v2_*.imageset` left in the iOS asset catalog (the
   catalog must never carry the frame corpus again).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import sys
from collections import defaultdict
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SHARED_DIRECTORY = REPOSITORY_ROOT / "shared" / "workout-vectors"
IOS_CATALOG = REPOSITORY_ROOT / "ios" / "calorietracker" / "Assets.xcassets"
EXERCISE_DATABASE = (
    REPOSITORY_ROOT
    / "ios"
    / "calorietracker"
    / "Resources"
    / "FreeExerciseDB"
    / "dist"
    / "exercises.json"
)
SHARED_MANIFEST = SHARED_DIRECTORY / "exercise-visual-manifest.json"
IOS_MANIFEST = (
    IOS_CATALOG
    / "ExerciseVisualManifest.dataset"
    / "exercise-visual-manifest.json"
)
SAMPLE_PACK_LIST = SHARED_DIRECTORY / "sample-pack.txt"
IOS_DEVELOPER_SAMPLE = REPOSITORY_ROOT / "ios" / "calorietracker" / "WorkoutVectorsSample"
FRAME_COUNT = 4
FRAME_INDICES = tuple(range(FRAME_COUNT))
GENDERS = ("male", "female")
EXPECTED_EXERCISE_COUNT = 875
EXPECTED_ASSET_COUNT = EXPECTED_EXERCISE_COUNT * len(GENDERS) * FRAME_COUNT
# Catalogue entries that are quick-log activities rather than illustrated exercises.
FRAMELESS_EXERCISE_IDS = frozenset({"Running_Outdoor", "Walking_Outdoor"})
# Hex prefix of the SHA-256 that the apps use as a cache key / integrity check.
DIGEST_LENGTH = 16
ASSET_PATTERN = re.compile(
    r"^(?P<exercise_id>.+)_(?P<gender>male|female)_v2_(?P<frame>[0-9]+)\.png$"
)
ASSET_STEM_PATTERN = re.compile(
    r"^(?P<exercise_id>.+)_(?P<gender>male|female)_v2_(?P<frame>[0-9]+)$"
)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Validate the corpus, manifests, and catalog without writing files.",
    )
    return parser.parse_args()


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPOSITORY_ROOT))
    except ValueError:
        return str(path)


def frame_digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:DIGEST_LENGTH]


def png_metadata(data: bytes, asset: Path) -> tuple[int, int, bool]:
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError(f"not a PNG: {display_path(asset)}")

    offset = len(PNG_SIGNATURE)
    width = height = color_type = None
    has_transparency_chunk = False
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk_data = data[offset + 8 : offset + 8 + length]
        if len(chunk_data) != length:
            raise ValueError(f"truncated PNG: {display_path(asset)}")
        if chunk_type == b"IHDR":
            width, height, _, color_type, _, _, _ = struct.unpack(">IIBBBBB", chunk_data)
        elif chunk_type == b"tRNS":
            has_transparency_chunk = True
        elif chunk_type == b"IEND":
            break
        offset += 12 + length

    if width is None or height is None or color_type is None:
        raise ValueError(f"missing PNG header: {display_path(asset)}")
    has_alpha = color_type in (4, 6) or has_transparency_chunk
    return width, height, has_alpha


def expected_exercise_ids() -> set[str]:
    document = json.loads(EXERCISE_DATABASE.read_text())
    if not isinstance(document, list):
        raise ValueError("exercise database root must be an array")

    exercise_ids: list[str] = []
    for index, exercise in enumerate(document):
        if not isinstance(exercise, dict):
            raise ValueError(f"exercise database item {index} must be an object")
        exercise_id = exercise.get("id")
        if not isinstance(exercise_id, str) or not exercise_id:
            raise ValueError(f"exercise database item {index} has no valid id")
        exercise_ids.append(exercise_id)

    unique_ids = set(exercise_ids)
    if len(unique_ids) != len(exercise_ids):
        raise ValueError("exercise database contains duplicate ids")
    missing_frameless = FRAMELESS_EXERCISE_IDS - unique_ids
    if missing_frameless:
        raise ValueError(
            "frameless allow-list references unknown ids: "
            + summarize_values(missing_frameless)
        )
    illustrated_ids = unique_ids - FRAMELESS_EXERCISE_IDS
    if len(illustrated_ids) != EXPECTED_EXERCISE_COUNT:
        raise ValueError(
            f"exercise database must contain {EXPECTED_EXERCISE_COUNT} illustrated "
            f"ids, found {len(illustrated_ids)}"
        )
    return illustrated_ids


def summarize_values(values: set[str], *, limit: int = 12) -> str:
    ordered = sorted(values)
    displayed = ", ".join(ordered[:limit])
    if len(ordered) > limit:
        displayed += f", ... (+{len(ordered) - limit} more)"
    return displayed


def serialized_json(document: object) -> str:
    return json.dumps(document, indent=2) + "\n"


def discover_sequences() -> dict[str, dict[str, dict[int, Path]]]:
    sequences: dict[str, dict[str, dict[int, Path]]] = defaultdict(
        lambda: defaultdict(dict)
    )
    for asset in sorted(SHARED_DIRECTORY.glob("*_v2_*.png")):
        match = ASSET_PATTERN.fullmatch(asset.name)
        if match is None:
            raise ValueError(f"invalid v2 asset name: {asset.name}")
        frame = int(match.group("frame"))
        exercise_id = match.group("exercise_id")
        gender = match.group("gender")
        if frame in sequences[exercise_id][gender]:
            raise ValueError(f"duplicate frame: {asset.name}")
        sequences[exercise_id][gender][frame] = asset
    if not sequences:
        raise ValueError("no v2 workout illustration sequences found")
    return sequences


def validate_sequence(
    exercise_id: str,
    by_gender: dict[str, dict[int, Path]],
) -> dict[str, list[str]]:
    """Validate one exercise and return its per-gender frame digests."""
    if set(by_gender) != set(GENDERS):
        raise ValueError(f"{exercise_id}: male and female sets must both be present")
    digests: dict[str, list[str]] = {}
    for gender in GENDERS:
        frames = by_gender[gender]
        if set(frames) != set(FRAME_INDICES):
            raise ValueError(
                f"{exercise_id} {gender}: expected frames {FRAME_INDICES}, "
                f"found {tuple(sorted(frames))}"
            )
        digests[gender] = []
        for frame in FRAME_INDICES:
            asset = frames[frame]
            if asset.is_symlink():
                raise ValueError(f"{asset.name}: canonical frames must not be symlinks")
            data = asset.read_bytes()
            width, height, has_alpha = png_metadata(data, asset)
            if (width, height) != (1024, 768):
                raise ValueError(
                    f"{asset.name}: expected 1024x768, found {width}x{height}"
                )
            if not has_alpha:
                raise ValueError(f"{asset.name}: PNG has no alpha channel")
            digests[gender].append(frame_digest(data))
    return digests


def validate_sequence_inventory(
    sequences: dict[str, dict[str, dict[int, Path]]],
    expected_ids: set[str],
) -> None:
    actual_ids = set(sequences)
    if actual_ids != expected_ids:
        missing = expected_ids - actual_ids
        unexpected = actual_ids - expected_ids
        details: list[str] = []
        if missing:
            details.append(
                f"missing {len(missing)} ids ({summarize_values(missing)})"
            )
        if unexpected:
            details.append(
                f"unexpected {len(unexpected)} ids "
                f"({summarize_values(unexpected)})"
            )
        raise ValueError(
            "shared workout sequence ids differ from database: "
            + "; ".join(details)
        )

    asset_count = sum(
        len(frames)
        for by_gender in sequences.values()
        for frames in by_gender.values()
    )
    if asset_count != EXPECTED_ASSET_COUNT:
        raise ValueError(
            f"shared corpus must contain {EXPECTED_ASSET_COUNT} PNG assets, "
            f"found {asset_count}"
        )


def validate_sample_pack(sequences: dict[str, dict[str, dict[int, Path]]]) -> list[str]:
    if not SAMPLE_PACK_LIST.is_file():
        raise ValueError(f"missing sample pack list: {display_path(SAMPLE_PACK_LIST)}")
    ids: list[str] = []
    for raw_line in SAMPLE_PACK_LIST.read_text().splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        if line not in sequences:
            raise ValueError(f"sample pack references unknown exercise: {line}")
        if line in ids:
            raise ValueError(f"sample pack lists {line} twice")
        ids.append(line)
    if not ids:
        raise ValueError("sample pack list must name at least one exercise")
    return ids


def generated_catalog_imagesets() -> list[Path]:
    """Frame image sets that older syncs generated into the iOS asset catalog."""
    stale: list[Path] = []
    for imageset in sorted(IOS_CATALOG.glob("*_v2_*.imageset")):
        if ASSET_STEM_PATTERN.fullmatch(imageset.stem) is not None:
            stale.append(imageset)
    return stale


def enforce_catalog_has_no_frames(*, check: bool) -> int:
    stale = generated_catalog_imagesets()
    if not stale:
        return 0
    if check:
        raise ValueError(
            f"iOS asset catalog still contains {len(stale)} generated workout frame "
            "imagesets; frames must not ship in the app binary. Run "
            "scripts/sync_workout_visual_assets.py (without --check) to remove them."
        )
    for imageset in stale:
        shutil.rmtree(imageset)
    return len(stale)


def manifest_document(
    sequences: dict[str, dict[str, dict[int, Path]]],
    digests: dict[str, dict[str, list[str]]],
) -> dict[str, object]:
    entries: list[dict[str, object]] = []
    for exercise_id in sorted(sequences):
        entries.append(
            {
                "exerciseId": exercise_id,
                "format": "png",
                "femaleFrameDigests": digests[exercise_id]["female"],
                "femaleFrames": [
                    sequences[exercise_id]["female"][frame].stem
                    for frame in FRAME_INDICES
                ],
                "frameCount": FRAME_COUNT,
                "maleFrameDigests": digests[exercise_id]["male"],
                "maleFrames": [
                    sequences[exercise_id]["male"][frame].stem
                    for frame in FRAME_INDICES
                ],
                "representativeFrameIndex": 2,
            }
        )
    return {"exercises": entries, "schemaVersion": 1}


def sync_manifest(document: dict[str, object], *, check: bool) -> None:
    expected = serialized_json(document)
    for manifest in (SHARED_MANIFEST, IOS_MANIFEST):
        if check:
            if not manifest.is_file() or manifest.read_text() != expected:
                raise ValueError(
                    f"manifest is out of date: {manifest.relative_to(REPOSITORY_ROOT)}"
                )
        elif not manifest.is_file() or manifest.read_text() != expected:
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text(expected)


def main() -> int:
    arguments = parse_arguments()
    try:
        expected_ids = expected_exercise_ids()
        sequences = discover_sequences()
        validate_sequence_inventory(sequences, expected_ids)
        digests = {
            exercise_id: validate_sequence(exercise_id, by_gender)
            for exercise_id, by_gender in sequences.items()
        }
        sample_ids = validate_sample_pack(sequences)
        removed = enforce_catalog_has_no_frames(check=arguments.check)
        sync_manifest(manifest_document(sequences, digests), check=arguments.check)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    mode = "validated" if arguments.check else "synced"
    print(
        f"{mode} {len(sequences)} exercise sequences, "
        f"{len(sequences) * len(GENDERS) * FRAME_COUNT} PNG assets, "
        f"{len(sample_ids)} sample-pack exercises"
    )
    if removed:
        print(f"removed {removed} generated frame imagesets from the iOS asset catalog")
    if IOS_DEVELOPER_SAMPLE.is_dir():
        print(
            f"warning: {display_path(IOS_DEVELOPER_SAMPLE)} exists (gitignored developer "
            "sample); delete it before archiving a release build",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
