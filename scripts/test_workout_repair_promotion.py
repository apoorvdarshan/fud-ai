"""Safety tests for exact, all-or-nothing reviewed workout asset promotion."""

import copy
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from PIL import Image

from promote_reviewed_workout_repairs import (
    AssetLayout, apply_plan, digest, expected_names, preflight,
)


class WorkoutRepairPromotionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory(prefix="workout-promotion-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.layout = AssetLayout(self.root)
        self.exercise_id = "Example_Exercise"
        self.names = expected_names(self.exercise_id)
        self.old = self.png((30, 50, 70, 255))
        self.new = self.png((80, 90, 100, 255))
        self.layout.database.parent.mkdir(parents=True)
        self.layout.database.write_text(json.dumps([{"id": self.exercise_id}]))
        entry = {"exerciseId": self.exercise_id, "format": "png", "frameCount": 4,
                 "representativeFrameIndex": 2, "maleFrames": self.names[:4], "femaleFrames": self.names[4:]}
        manifest = json.dumps({"exercises": [entry]}).encode()
        self.layout.shared.mkdir(parents=True)
        self.layout.shared_manifest.write_bytes(manifest)
        self.layout.ios_manifest.parent.mkdir(parents=True)
        self.layout.ios_manifest.write_bytes(manifest)
        self.candidates = self.root / "experiments"
        self.candidates.mkdir()
        record = self.root / "review.md"
        record.write_text("Reviewed all eight frames on dark and light backgrounds. Accepted background and framing.")
        frames = []
        for name in self.names:
            (shared,) = self.layout.destinations(name)
            shared.write_bytes(self.old)
            candidate = self.candidates / (name + ".png")
            candidate.write_bytes(self.new)
            frames.append({"asset_name": name, "candidate_path": str(candidate),
                           "candidate_sha256": digest(self.new), "original_shared_sha256": digest(self.old)})
        self.review = {"schema_version": 1, "exercises": [{"exercise_id": self.exercise_id,
                       "acceptance": "background_and_framing", "review_record_path": str(record),
                       "review_record_sha256": digest(record.read_bytes()), "frames": frames}]}
        self.review_path = self.root / "review.json"
        self.save_review()

    def png(self, color, *, size=(1024, 768), mode="RGBA"):
        image = Image.new("RGBA", size, (0, 0, 0, 0))
        image.paste(color, (20, 20, 120, 120))
        if mode != "RGBA":
            image = image.convert(mode)
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        return buffer.getvalue()

    def save_review(self):
        self.review_path.write_text(json.dumps(self.review))

    def plan(self):
        return preflight(self.review_path, self.root, expected_count=1)

    def snapshot(self):
        return {str(path.relative_to(self.root)): path.read_bytes() for path in self.root.rglob("*") if path.is_file()}

    def assert_originals_unchanged(self):
        for name in self.names:
            for destination in self.layout.destinations(name):
                self.assertEqual(destination.read_bytes(), self.old)

    def test_check_is_read_only_and_covers_entire_reference_set(self):
        before = self.snapshot()
        plan = self.plan()
        self.assertEqual(len(plan.frames), 8)
        self.assertEqual(plan.full_reference_count, 8)
        self.assertEqual(before, self.snapshot())

    def test_missing_or_duplicate_review_frame_is_rejected(self):
        original = copy.deepcopy(self.review)
        for frames in (original["exercises"][0]["frames"][:-1],
                       original["exercises"][0]["frames"][:-1] + original["exercises"][0]["frames"][:1]):
            self.review["exercises"][0]["frames"] = frames
            self.save_review()
            with self.assertRaises(ValueError):
                self.plan()
        self.assert_originals_unchanged()

    def test_nonaccepted_exercise_and_changed_review_record_are_rejected(self):
        self.review["exercises"][0]["acceptance"] = "background_only"
        self.save_review()
        with self.assertRaises(ValueError):
            self.plan()
        self.review["exercises"][0]["acceptance"] = "background_and_framing"
        self.save_review()
        (self.root / "review.md").write_text("Changed after acceptance")
        with self.assertRaises(ValueError):
            self.plan()

    def test_candidate_and_original_hash_mismatches_reject_everything(self):
        last = self.review["exercises"][0]["frames"][-1]
        last["candidate_sha256"] = "0" * 64
        self.save_review()
        with self.assertRaises(ValueError):
            self.plan()
        last["candidate_sha256"] = digest(self.new)
        last["original_shared_sha256"] = "0" * 64
        self.save_review()
        with self.assertRaises(ValueError):
            self.plan()
        self.assert_originals_unchanged()

    def test_wrong_dimensions_or_non_rgba_candidate_is_rejected(self):
        frame = self.review["exercises"][0]["frames"][-1]
        for data in (self.png((10, 20, 30, 255), size=(512, 384)), self.png((10, 20, 30, 255), mode="RGB")):
            Path(frame["candidate_path"]).write_bytes(data)
            frame["candidate_sha256"] = digest(data)
            self.save_review()
            with self.assertRaises(ValueError):
                self.plan()
        self.assert_originals_unchanged()

    def test_opaque_or_empty_rgba_candidate_is_rejected(self):
        frame = self.review["exercises"][0]["frames"][-1]
        for color in ((30, 40, 50, 255), (0, 0, 0, 0)):
            image = Image.new("RGBA", (1024, 768), color)
            data = io.BytesIO()
            image.save(data, format="PNG")
            Path(frame["candidate_path"]).write_bytes(data.getvalue())
            frame["candidate_sha256"] = digest(data.getvalue())
            self.save_review()
            with self.assertRaises(ValueError):
                self.plan()

    def test_unreviewed_catalog_exercise_is_also_validated(self):
        other = "Unreviewed_Exercise"
        names = expected_names(other)
        self.layout.database.write_text(json.dumps([{"id": self.exercise_id}, {"id": other}]))
        manifest = json.loads(self.layout.shared_manifest.read_text())
        manifest["exercises"].append({"exerciseId": other, "format": "png", "frameCount": 4,
                                     "representativeFrameIndex": 2, "maleFrames": names[:4], "femaleFrames": names[4:]})
        encoded = json.dumps(manifest).encode()
        self.layout.shared_manifest.write_bytes(encoded)
        self.layout.ios_manifest.write_bytes(encoded)
        for name in names:
            (shared,) = self.layout.destinations(name)
            shared.write_bytes(self.old)
        preflight(self.review_path, self.root, expected_count=2)
        self.layout.destinations(names[-1])[0].unlink()
        with self.assertRaises(ValueError):
            preflight(self.review_path, self.root, expected_count=2)

    def test_stale_ios_imageset_is_rejected(self):
        imageset = self.layout.catalog / (self.names[-1] + ".imageset")
        imageset.mkdir(parents=True)
        (imageset / (self.names[-1] + ".png")).write_bytes(self.old)
        with self.assertRaises(ValueError):
            self.plan()

    def test_frameless_activities_are_tolerated_in_database(self):
        self.layout.database.write_text(json.dumps([{"id": self.exercise_id}, {"id": "Running_Outdoor"}]))
        self.assertEqual(len(self.plan().frames), 8)

    def test_manifest_reference_mismatch_is_rejected(self):
        manifest = json.loads(self.layout.shared_manifest.read_text())
        manifest["exercises"][0]["femaleFrames"][-1] = "Wrong_female_v2_3"
        encoded = json.dumps(manifest).encode()
        self.layout.shared_manifest.write_bytes(encoded)
        self.layout.ios_manifest.write_bytes(encoded)
        with self.assertRaises(ValueError):
            self.plan()

    def test_apply_copies_exact_reviewed_bytes_and_preserves_originals(self):
        result = apply_plan(self.plan())
        self.assertEqual(result["platform_png_writes"], 8)
        backup = Path(result["backup_directory"])
        for name in self.names:
            for destination in self.layout.destinations(name):
                self.assertEqual(destination.read_bytes(), self.new)
            self.assertEqual((backup / "shared" / (name + ".png")).read_bytes(), self.old)
        self.assertFalse((backup / "ios").exists())
        recovery = json.loads((backup / "recovery.json").read_text())
        self.assertEqual(recovery["status"], "applied")
        self.assertEqual(len(recovery["copies"]), 8)

    def test_changed_candidate_after_preflight_aborts_before_any_writes(self):
        plan = self.plan()
        plan.frames[-1].candidate_path.write_bytes(self.old)
        before = self.snapshot()
        with self.assertRaises(ValueError):
            apply_plan(plan)
        self.assertEqual(before, self.snapshot())
        self.assert_originals_unchanged()

    def test_changed_original_after_preflight_aborts_before_any_writes(self):
        plan = self.plan()
        plan.frames[-1].destinations[0].write_bytes(self.new)
        before = self.snapshot()
        with self.assertRaises(ValueError):
            apply_plan(plan)
        self.assertEqual(before, self.snapshot())

    def test_io_failure_rolls_back_already_replaced_pngs(self):
        original_replace = os.replace
        calls = 0

        def fail_third(source, destination):
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError("simulated replacement failure")
            return original_replace(source, destination)

        plan = self.plan()
        with patch("promote_reviewed_workout_repairs.os.replace", side_effect=fail_third):
            with self.assertRaises(RuntimeError):
                apply_plan(plan)
        self.assert_originals_unchanged()
        records = list(self.root.glob("artifacts/workout-visual-qa/promotion-backups/*/recovery.json"))
        self.assertEqual(len(records), 1)
        self.assertEqual(json.loads(records[0].read_text())["status"], "rolled_back")
        self.assertFalse(list(self.layout.shared.glob(".*.promotion-*")))


if __name__ == "__main__":
    unittest.main()
