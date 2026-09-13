#!/usr/bin/env python3
"""Promote exact, hash-reviewed complete workout repair sets into the shared corpus.

Default / --check is strictly read-only. --apply first validates EVERY reference,
candidate and pre-change hash, then stages exact PNG bytes and retains a unique
backup/recovery manifest before replacing the canonical shared/workout-vectors
frames. It does not edit app code, image pixels, database IDs, or either visual
manifest. shared/workout-vectors is the only copy of the frames (the apps fetch
them on demand from the CDN), so after a successful apply run
`python3 scripts/sync_workout_visual_assets.py` to refresh the per-frame digests
in both manifests, then `scripts/publish_workout_vectors.py` to upload.

Review JSON schema (all paths are absolute or relative to --repo):
  {"schema_version": 1, "exercises": [{
    "exercise_id": "Band_Pull_Apart", "acceptance": "background_and_framing",
    "review_record_path": "artifacts/.../review.md",
    "review_record_sha256": "<64 lowercase hex characters>",
    "frames": [{"asset_name": "Band_Pull_Apart_male_v2_0",
      "candidate_path": "artifacts/.../Band_Pull_Apart_male_v2_0.png",
      "candidate_sha256": "<reviewed candidate hash>",
      "original_shared_sha256": "<pre-change canonical hash>"}, ... all 8]
  }]}

Requires Pillow. Examples:
  python scripts/promote_reviewed_workout_repairs.py --review <review.json>
  python scripts/promote_reviewed_workout_repairs.py --review <review.json> --apply

Backups default to artifacts/workout-visual-qa/promotion-backups/<unique-run>/.
An apply is not a Git commit or installation. The caller owns that workflow.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_EXERCISES = 875
# Quick-log activities in the catalogue that intentionally have no illustration set.
FRAMELESS_EXERCISE_IDS = frozenset({"Running_Outdoor", "Walking_Outdoor"})
HASH = re.compile(r"^[0-9a-f]{64}$")
GENDERS = ("male", "female")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> tuple[bytes, dict | list]:
    data = path.read_bytes()
    return data, json.loads(data)


def resolve_input(repo: Path, value: str, label: str) -> Path:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label}: missing path")
    path = Path(value)
    resolved = (path if path.is_absolute() else repo / path).resolve(strict=True)
    if not resolved.is_file():
        raise ValueError(f"{label}: not a regular file: {resolved}")
    return resolved


def expected_names(exercise_id: str) -> list[str]:
    if not isinstance(exercise_id, str) or not exercise_id or "/" in exercise_id or "\\" in exercise_id or exercise_id in {".", ".."}:
        raise ValueError(f"invalid exercise ID: {exercise_id!r}")
    return [f"{exercise_id}_{gender}_v2_{frame}" for gender in GENDERS for frame in range(4)]


@dataclass(frozen=True)
class AssetLayout:
    repo: Path

    @property
    def shared(self) -> Path:
        return self.repo / "shared/workout-vectors"

    @property
    def catalog(self) -> Path:
        return self.repo / "ios/calorietracker/Assets.xcassets"

    @property
    def shared_manifest(self) -> Path:
        return self.shared / "exercise-visual-manifest.json"

    @property
    def ios_manifest(self) -> Path:
        return self.catalog / "ExerciseVisualManifest.dataset/exercise-visual-manifest.json"

    @property
    def database(self) -> Path:
        return self.repo / "ios/calorietracker/Resources/FreeExerciseDB/dist/exercises.json"

    def destinations(self, name: str) -> tuple[Path]:
        # Only the shared corpus holds frames; the iOS catalog carries just the manifest.
        return (self.shared / (name + ".png"),)


@dataclass(frozen=True)
class PromotionFrame:
    exercise_id: str
    asset_name: str
    candidate_path: Path
    candidate_sha256: str
    original_sha256: str
    candidate_bytes: bytes
    original_bytes: bytes
    destinations: tuple[Path, ...]


@dataclass(frozen=True)
class PromotionPlan:
    layout: AssetLayout
    review_path: Path
    review_bytes: bytes
    frames: tuple[PromotionFrame, ...]
    exercise_ids: tuple[str, ...]
    guards: tuple[tuple[Path, str], ...]
    full_reference_count: int


def validate_candidate_png(data: bytes, label: str) -> None:
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "PNG" or image.mode != "RGBA" or image.size != (1024, 768):
            raise ValueError(f"{label}: candidate must be a 1024x768 RGBA PNG")
        if getattr(image, "n_frames", 1) != 1:
            raise ValueError(f"{label}: candidate must contain one animation frame, not APNG")
        image.load()  # Decode the complete file, not just its header.
        low, high = image.getchannel("A").getextrema()
        if low != 0 or high < 224:
            raise ValueError(f"{label}: candidate requires visible artwork and actual transparency")


def validate_entire_catalog(layout: AssetLayout, expected_count: int) -> tuple[dict, list[tuple[Path, str]], int]:
    db_bytes, database = read_json(layout.database)
    if not isinstance(database, list) or any(not isinstance(entry, dict) for entry in database):
        raise ValueError("exercise database must be an array of objects")
    ids = [entry.get("id") for entry in database]
    for exercise_id in ids:
        expected_names(exercise_id)
    if len(set(ids)) != len(ids):
        raise ValueError("database must not contain duplicate exercise IDs")
    ids = [exercise_id for exercise_id in ids if exercise_id not in FRAMELESS_EXERCISE_IDS]
    if len(ids) != expected_count:
        raise ValueError(f"database must contain exactly {expected_count} illustrated exercise IDs")
    shared_bytes, manifest = read_json(layout.shared_manifest)
    ios_bytes = layout.ios_manifest.read_bytes()
    if shared_bytes != ios_bytes:
        raise ValueError("shared and iOS visual manifests are not byte-identical")
    if not isinstance(manifest, dict) or not isinstance(manifest.get("exercises"), list):
        raise ValueError("invalid visual manifest structure")
    entries = manifest["exercises"]
    if any(not isinstance(entry, dict) for entry in entries):
        raise ValueError("visual manifest entries must be objects")
    manifest_ids = [entry.get("exerciseId") for entry in entries]
    if len(manifest_ids) != len(ids) or len(set(manifest_ids)) != len(manifest_ids) or set(manifest_ids) != set(ids):
        raise ValueError("visual manifest IDs must exactly match the exercise database")
    guards = [(layout.database, digest(db_bytes)), (layout.shared_manifest, digest(shared_bytes)),
              (layout.ios_manifest, digest(ios_bytes))]
    seen_names = set()
    for entry in entries:
        exercise_id = entry["exerciseId"]
        if entry.get("format") != "png" or entry.get("frameCount") != 4 or entry.get("representativeFrameIndex") not in range(4):
            raise ValueError(f"{exercise_id}: expected a complete four-frame PNG manifest entry")
        names = expected_names(exercise_id)
        for index, gender in enumerate(GENDERS):
            if entry.get(gender + "Frames") != names[index * 4:(index + 1) * 4]:
                raise ValueError(f"{exercise_id}: wrong or incomplete {gender} frame references")
        for name in names:
            if name in seen_names:
                raise ValueError(f"duplicate manifest reference: {name}")
            seen_names.add(name)
            (shared,) = layout.destinations(name)
            if shared.is_symlink() or not shared.is_file() or not shared.resolve().is_relative_to(layout.shared.resolve()):
                raise ValueError(f"missing or symlinked canonical frame: {shared}")
            if (layout.catalog / (name + ".imageset")).exists():
                raise ValueError(f"{name}: stale iOS image set; frames must not live in the asset catalog")
    return {entry["exerciseId"]: entry for entry in entries}, guards, len(seen_names)


def preflight(review_path: Path, repo: Path = ROOT, *, expected_count: int = EXPECTED_EXERCISES) -> PromotionPlan:
    """Read-only: checks the complete catalog and all requested frames before returning."""
    layout = AssetLayout(repo.resolve())
    review_path = review_path.resolve(strict=True)
    review_bytes, review = read_json(review_path)
    if not isinstance(review, dict) or review.get("schema_version") != 1:
        raise ValueError("review manifest requires schema_version 1")
    exercises = review.get("exercises")
    if not isinstance(exercises, list) or not exercises:
        raise ValueError("review manifest must contain at least one complete exercise")
    catalog, guards, reference_count = validate_entire_catalog(layout, expected_count)
    guards.append((review_path, digest(review_bytes)))
    accepted_ids, frames, candidate_paths = set(), [], set()
    for entry in exercises:
        if not isinstance(entry, dict):
            raise ValueError("review exercises must be objects")
        exercise_id = entry.get("exercise_id")
        expected = set(expected_names(exercise_id))
        if exercise_id not in catalog or exercise_id in accepted_ids:
            raise ValueError(f"unknown or repeated reviewed exercise: {exercise_id}")
        if entry.get("acceptance") != "background_and_framing":
            raise ValueError(f"{exercise_id}: requires explicit background_and_framing acceptance")
        record = resolve_input(layout.repo, entry.get("review_record_path"), "review record")
        record_hash = entry.get("review_record_sha256")
        if not isinstance(record_hash, str) or not HASH.fullmatch(record_hash) or digest(record.read_bytes()) != record_hash:
            raise ValueError(f"{exercise_id}: human review record hash mismatch")
        guards.append((record, record_hash))
        reviewed_frames = entry.get("frames")
        if not isinstance(reviewed_frames, list) or len(reviewed_frames) != 8 or any(not isinstance(item, dict) for item in reviewed_frames):
            raise ValueError(f"{exercise_id}: exactly eight reviewed frames are required")
        actual_names = [item.get("asset_name") for item in reviewed_frames]
        if any(not isinstance(name, str) for name in actual_names) or len(set(actual_names)) != 8 or set(actual_names) != expected:
            raise ValueError(f"{exercise_id}: review must cover each male/female frame 0–3 exactly once")
        for item in reviewed_frames:
            name = item["asset_name"]
            candidate = resolve_input(layout.repo, item.get("candidate_path"), name)
            if candidate in candidate_paths or candidate.name != name + ".png":
                raise ValueError(f"{name}: candidate path reused or filename mismatched")
            if candidate.is_relative_to(layout.shared.resolve()) or candidate.is_relative_to(layout.catalog.resolve()):
                raise ValueError(f"{name}: candidate must be an external reviewed experiment, not a canonical asset")
            candidate_hash, original_hash = item.get("candidate_sha256"), item.get("original_shared_sha256")
            if not all(isinstance(value, str) and HASH.fullmatch(value) for value in (candidate_hash, original_hash)):
                raise ValueError(f"{name}: invalid candidate/original SHA-256")
            candidate_data = candidate.read_bytes()
            if digest(candidate_data) != candidate_hash:
                raise ValueError(f"{name}: candidate hash differs from the accepted review")
            validate_candidate_png(candidate_data, name)
            destinations = layout.destinations(name)
            originals = [path.read_bytes() for path in destinations]
            if any(digest(data) != original_hash for data in originals):
                raise ValueError(f"{name}: original shared bytes changed since review")
            frames.append(PromotionFrame(exercise_id, name, candidate, candidate_hash, original_hash,
                                         candidate_data, originals[0], destinations))
            candidate_paths.add(candidate)
        accepted_ids.add(exercise_id)
    return PromotionPlan(layout, review_path, review_bytes, tuple(frames), tuple(sorted(accepted_ids)),
                         tuple(guards), reference_count)


def verify_plan_unchanged(plan: PromotionPlan) -> None:
    for path, expected in plan.guards:
        if not path.is_file() or digest(path.read_bytes()) != expected:
            raise ValueError(f"preflight metadata/review changed before apply: {path}")
    for frame in plan.frames:
        if digest(frame.candidate_path.read_bytes()) != frame.candidate_sha256:
            raise ValueError(f"candidate changed before apply: {frame.candidate_path}")
        for destination in frame.destinations:
            if destination.is_symlink() or digest(destination.read_bytes()) != frame.original_sha256:
                raise ValueError(f"canonical frame changed before apply: {destination}")


def stage_bytes(destination: Path, data: bytes, mode: int) -> Path:
    descriptor, temporary = tempfile.mkstemp(prefix="." + destination.name + ".promotion-", dir=destination.parent)
    path = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(path, mode)
        return path
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def durable_write(path: Path, data: bytes) -> None:
    """Flush originals/recovery records before any canonical PNG replacement."""
    with path.open("wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def apply_plan(plan: PromotionPlan, backup_root: Path | None = None) -> dict:
    """Apply a fully checked plan; preserve backups and roll back this helper's partial writes."""
    backup_root = (backup_root or plan.layout.repo / "artifacts/workout-visual-qa/promotion-backups").resolve()
    for protected in (plan.layout.shared.resolve(), plan.layout.catalog.resolve()):
        if backup_root == protected or backup_root.is_relative_to(protected):
            raise ValueError("backups must not be stored inside a canonical asset directory")
    verify_plan_unchanged(plan)  # No backup/temp/canonical writes before all checks pass.
    backup_root.mkdir(parents=True, exist_ok=True)
    prefix = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-")
    backup_dir = Path(tempfile.mkdtemp(prefix=prefix, dir=backup_root))
    recovery = {"schema_version": 1, "status": "prepared", "review_manifest_sha256": digest(plan.review_bytes),
                "exercise_ids": list(plan.exercise_ids), "copies": []}
    durable_write(backup_dir / "review-manifest.json", plan.review_bytes)
    staged, replaced = [], []
    try:
        for frame in plan.frames:
            for platform, destination in zip(("shared",), frame.destinations):
                backup = backup_dir / platform / (frame.asset_name + ".png")
                backup.parent.mkdir(parents=True, exist_ok=True)
                durable_write(backup, frame.original_bytes)
                if digest(backup.read_bytes()) != frame.original_sha256:
                    raise OSError(f"backup verification failed: {backup}")
                mode = stat.S_IMODE(destination.stat().st_mode)
                temporary = stage_bytes(destination, frame.candidate_bytes, mode)
                staged.append((temporary, destination, frame, mode))
                recovery["copies"].append({"destination": str(destination), "backup": str(backup),
                                           "original_sha256": frame.original_sha256,
                                           "candidate_sha256": frame.candidate_sha256})
        recovery_path = backup_dir / "recovery.json"
        durable_write(recovery_path, (json.dumps(recovery, indent=2) + "\n").encode())
        verify_plan_unchanged(plan)  # Catch changes during staging, still before PNG replacements.
        for temporary, destination, frame, mode in staged:
            if digest(destination.read_bytes()) != frame.original_sha256:
                raise ValueError(f"canonical frame changed during apply: {destination}")
            os.replace(temporary, destination)
            replaced.append((destination, frame, mode))
        for destination, frame, _ in replaced:
            if digest(destination.read_bytes()) != frame.candidate_sha256:
                raise OSError(f"post-write verification failed: {destination}")
        recovery["status"] = "applied"
        durable_write(recovery_path, (json.dumps(recovery, indent=2) + "\n").encode())
    except BaseException as error:
        rollback_errors = []
        for destination, frame, mode in reversed(replaced):
            restore = None
            try:
                # Never overwrite a third-party edit that raced this helper.
                if digest(destination.read_bytes()) != frame.candidate_sha256:
                    raise OSError("destination changed externally; original retained in backup")
                restore = stage_bytes(destination, frame.original_bytes, mode)
                os.replace(restore, destination)
            except BaseException as rollback_error:
                rollback_errors.append(f"{destination}: {rollback_error}")
            finally:
                if restore is not None:
                    restore.unlink(missing_ok=True)
        recovery.update({"status": "rollback_needs_attention" if rollback_errors else "rolled_back",
                         "error": str(error), "rollback_errors": rollback_errors})
        durable_write(backup_dir / "recovery.json", (json.dumps(recovery, indent=2) + "\n").encode())
        raise RuntimeError(f"promotion failed; recovery record: {backup_dir / 'recovery.json'}; {error}") from error
    finally:
        for temporary, _, _, _ in staged:
            temporary.unlink(missing_ok=True)
    return {"status": "applied", "exercises": len(plan.exercise_ids), "frames": len(plan.frames),
            "platform_png_writes": len(replaced), "backup_directory": str(backup_dir),
            "note": "Existing shared PNGs replaced with exact reviewed bytes; originals retained in the backup. "
                    "No app code or manifest changed: run scripts/sync_workout_visual_assets.py to refresh frame "
                    "digests, then scripts/publish_workout_vectors.py to upload."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--review", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=ROOT)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--check", action="store_true", help="Read-only validation; the default.")
    actions.add_argument("--apply", action="store_true", help="Promote all exact reviewed frames after complete preflight and backups.")
    parser.add_argument("--backup-root", type=Path)
    args = parser.parse_args()
    if args.backup_root and not args.apply:
        parser.error("--backup-root is only meaningful with --apply")
    plan = preflight(args.review, args.repo)
    result = apply_plan(plan, args.backup_root) if args.apply else {
        "status": "check_passed_no_writes", "exercises": len(plan.exercise_ids), "frames": len(plan.frames),
        "would_replace_platform_pngs": len(plan.frames),
        "full_catalog_references_verified": plan.full_reference_count,
        "exercise_ids": list(plan.exercise_ids), "review_manifest_sha256": digest(plan.review_bytes),
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
