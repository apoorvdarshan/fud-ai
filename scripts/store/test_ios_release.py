#!/usr/bin/env python3
"""Offline tests for iOS release switches, build waiting and build selection.

No network: every App Store Connect call goes to an in-memory fake.
Run: python3 -m unittest scripts/store/test_ios_release.py
"""
from __future__ import annotations

import hashlib
import io
import os
import plistlib
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import asc_release  # noqa: E402
import ios_release  # noqa: E402
import prepare_whats_new  # noqa: E402
import print_gates  # noqa: E402
import redact_credential_ids  # noqa: E402
import release_notes  # noqa: E402
from ios_release import ReleaseError, resolve_gates  # noqa: E402

TAG = "ios-v7.2"
VERSION = "7.2"
ALL_ON = {
    "IOS_RELEASE_AUTOMATION": "true",
    "IOS_CREATE_VERSION": "true",
    "IOS_UPLOAD_BUILD": "true",
    "IOS_UPLOAD_LISTING": "true",
    "IOS_UPLOAD_WHATS_NEW": "true",
    "IOS_UPLOAD_SCREENSHOTS": "true",
    "IOS_SUBMIT_REVIEW": "true",
    "IOS_PUBLIC_RELEASE": "true",
}
OPS = ("create_version", "upload_build", "listing", "whats_new", "screenshots", "submit", "public_release")


def gates(env: dict[str, str], *, event: str = "push", ref: str = TAG) -> dict:
    return resolve_gates(env, event=event, ref_name=ref, marketing_version=VERSION)


class GateTests(unittest.TestCase):
    def test_missing_settings_mean_everything_off(self) -> None:
        result = gates({})
        self.assertEqual(result["mode"], "release")
        self.assertFalse(result["master"])
        self.assertFalse(result["build"])
        self.assertFalse(result["asc"])
        for key in OPS:
            self.assertFalse(result[key], key)
        self.assertEqual(result["poll_seconds"], 30)
        self.assertEqual(result["wait_minutes"], 120)

    def test_master_off_ignores_operation_switches(self) -> None:
        result = gates({**ALL_ON, "IOS_RELEASE_AUTOMATION": "false"})
        for key in OPS:
            self.assertFalse(result[key], key)
        self.assertFalse(result["build"])
        self.assertTrue(any("ignored" in note for note in result["notes"]))

    def test_each_operation_is_independent(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "yes", "IOS_UPLOAD_LISTING": "on"})
        self.assertTrue(result["listing"])
        self.assertTrue(result["asc"])
        self.assertFalse(result["whats_new"])
        self.assertFalse(result["upload_build"])
        self.assertFalse(result["build"])

    def test_all_on(self) -> None:
        result = gates(ALL_ON)
        for key in OPS:
            self.assertTrue(result[key], key)
        self.assertTrue(result["build"])

    def test_invalid_values_are_rejected_even_when_master_is_off(self) -> None:
        for name in ("IOS_RELEASE_AUTOMATION", "IOS_SUBMIT_REVIEW", "IOS_UPLOAD_WHATS_NEW"):
            with self.subTest(name=name), self.assertRaisesRegex(ReleaseError, name):
                gates({name: "maybe"})

    def test_bad_timing_values_are_rejected(self) -> None:
        for env in (
            {"IOS_BUILD_POLL_SECONDS": "5"},
            {"IOS_BUILD_POLL_SECONDS": "thirty"},
            {"IOS_BUILD_POLL_SECONDS": "٣٠"},
            {"IOS_BUILD_WAIT_MINUTES": "0"},
            {"IOS_BUILD_WAIT_MINUTES": "999"},
        ):
            with self.subTest(env=env), self.assertRaises(ReleaseError):
                gates(env)
        result = gates({"IOS_BUILD_POLL_SECONDS": "45", "IOS_BUILD_WAIT_MINUTES": "90"})
        self.assertEqual((result["poll_seconds"], result["wait_minutes"]), (45, 90))

    def test_submit_requires_this_runs_build(self) -> None:
        env = {"IOS_RELEASE_AUTOMATION": "true", "IOS_SUBMIT_REVIEW": "true"}
        with self.assertRaisesRegex(ReleaseError, "IOS_UPLOAD_BUILD"):
            gates(env)

    def test_public_release_requires_submit(self) -> None:
        env = {"IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_BUILD": "true", "IOS_PUBLIC_RELEASE": "true"}
        with self.assertRaisesRegex(ReleaseError, "IOS_SUBMIT_REVIEW"):
            gates(env)

    def test_tag_must_match_marketing_version(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "does not match"):
            gates({}, ref="ios-v7.3")
        with self.assertRaisesRegex(ReleaseError, "ios-v"):
            gates({}, ref="v7.2")

    def test_manual_run_validates_only(self) -> None:
        result = gates(ALL_ON, event="workflow_dispatch", ref="main")
        self.assertEqual(result["mode"], "validate")
        self.assertTrue(result["build"])
        self.assertFalse(result["master"])
        self.assertFalse(result["asc"])
        for key in OPS:
            self.assertFalse(result[key], key)

    def test_manual_run_can_validate_a_future_version(self) -> None:
        result = resolve_gates({}, event="workflow_dispatch", ref_name="main",
                               marketing_version=VERSION, validation_version="7.3")
        self.assertEqual(result["mode"], "validate")
        self.assertEqual(result["marketing_version"], "7.3")
        self.assertFalse(result["asc"])
        default = resolve_gates({}, event="workflow_dispatch", ref_name="main", marketing_version=VERSION)
        self.assertEqual(default["marketing_version"], VERSION)

    def test_version_override_is_rejected_for_releases_and_bad_values(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "manual validation"):
            resolve_gates({}, event="push", ref_name=TAG, marketing_version=VERSION, validation_version="7.3")
        for bad in ("7.x", "v7.3", "7.3.1.2", "7.3 beta", "٧.٢"):
            with self.subTest(bad=bad), self.assertRaisesRegex(ReleaseError, "must look like"):
                resolve_gates({}, event="workflow_dispatch", ref_name="main",
                              marketing_version=VERSION, validation_version=bad)

    def test_create_version_needs_only_the_master_switch(self) -> None:
        result = gates({"IOS_RELEASE_AUTOMATION": "true", "IOS_CREATE_VERSION": "true"})
        self.assertTrue(result["create_version"])
        self.assertFalse(result["build"])
        self.assertFalse(result["asc"])
        self.assertFalse(gates({"IOS_CREATE_VERSION": "true"})["create_version"])
        self.assertFalse(gates(ALL_ON, event="workflow_dispatch", ref="main")["create_version"])
        with self.assertRaisesRegex(ReleaseError, "IOS_CREATE_VERSION"):
            gates({"IOS_CREATE_VERSION": "maybe"})

    def test_project_versions_are_consistent(self) -> None:
        self.assertRegex(ios_release.single_project_value("MARKETING_VERSION"), r"^\d+(\.\d+)*$")
        self.assertRegex(ios_release.single_project_value("CURRENT_PROJECT_VERSION"), r"^\d+$")
        with tempfile.TemporaryDirectory() as tmp:
            pbx = Path(tmp) / "project.pbxproj"
            pbx.write_text("MARKETING_VERSION = 1.0;\nMARKETING_VERSION = 1.1;\n")
            with self.assertRaisesRegex(ReleaseError, "identical"):
                ios_release.single_project_value("MARKETING_VERSION", pbx)


class ExportComplianceTests(unittest.TestCase):
    """ITSAppUsesNonExemptEncryption must be declared, or App Store Connect asks about every build
    and review submission fails with usesNonExemptEncryption missing (HTTP 409)."""

    KEY = "INFOPLIST_KEY_ITSAppUsesNonExemptEncryption"
    CODE = {"INFOPLIST_KEY_ITSEncryptionExportComplianceCode": "ABC123"}
    GENERATED = {"GENERATE_INFOPLIST_FILE": "YES"}
    APP_PLIST = {"INFOPLIST_FILE": "calorietracker/Info.plist", **GENERATED}

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / "calorietracker.xcodeproj").mkdir()
        (self.root / "calorietracker").mkdir()

    def plist(self, values: dict, *, binary: bool = False, name: str = "calorietracker/Info.plist") -> Path:
        fmt = plistlib.FMT_BINARY if binary else plistlib.FMT_XML
        path = self.root / name
        path.write_bytes(plistlib.dumps({"CFBundleName": "Fud AI", **values}, fmt=fmt))
        return path

    def project(self, app: dict[str, dict[str, str]], *, project: dict[str, dict[str, str]] | None = None,
                other: dict[str, dict[str, str]] | None = None) -> Path:
        """A minimal project.pbxproj: the app target, an optional other target, project-level settings."""
        ids = iter(f"{n:024X}" for n in range(1, 1000))
        objects: list[str] = []

        def config_list(owner: str, configs: dict[str, dict[str, str]]) -> str:
            list_id = next(ids)
            refs = []
            for name, settings in configs.items():
                config_id = next(ids)
                refs.append(f"\t\t\t\t{config_id} /* {name} */,\n")
                lines = "".join(f"\t\t\t\t{key} = {value};\n" for key, value in settings.items())
                objects.append(f"\t\t{config_id} /* {name} */ = {{\n\t\t\tisa = XCBuildConfiguration;\n"
                               f"\t\t\tbuildSettings = {{\n{lines}\t\t\t}};\n\t\t\tname = {name};\n\t\t}};\n")
            objects.append(f"\t\t{list_id} /* Build configuration list for {owner} */ = {{\n"
                           f"\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n{''.join(refs)}\t\t\t);\n"
                           "\t\t\tdefaultConfigurationName = Release;\n\t\t};\n")
            return list_id

        def target(name: str, configs: dict[str, dict[str, str]]) -> None:
            list_id = config_list(f'PBXNativeTarget "{name}"', configs)
            objects.append(f"\t\t{next(ids)} /* {name} */ = {{\n\t\t\tisa = PBXNativeTarget;\n"
                           f"\t\t\tbuildConfigurationList = {list_id} /* Build configuration list */;\n"
                           f"\t\t\tname = {name};\n\t\t\tproductType = \"com.apple.product-type.application\";\n\t\t}};\n")

        target("calorietracker", app)
        if other is not None:
            target("FudAIWatchApp", other)
        project_list = config_list('PBXProject "calorietracker"', project or {"Debug": {}, "Release": {}})
        objects.append(f"\t\t{next(ids)} /* Project object */ = {{\n\t\t\tisa = PBXProject;\n"
                       f"\t\t\tbuildConfigurationList = {project_list} /* Build configuration list */;\n\t\t}};\n")
        path = self.root / "calorietracker.xcodeproj" / "project.pbxproj"
        path.write_text("// !$*UTF8*$!\n{\n\tobjects = {\n" + "".join(objects) + "\t};\n}\n", encoding="utf-8")
        return path

    def check(self, app: dict[str, dict[str, str]], **kwargs) -> str:
        return ios_release.check_export_compliance(self.project(app, **kwargs))

    def both(self, settings: dict[str, str]) -> dict[str, dict[str, str]]:
        return {"Debug": dict(settings), "Release": dict(settings)}

    def test_the_app_declares_no_non_exempt_encryption(self) -> None:
        self.assertEqual(ios_release.check_export_compliance(), "NO")
        configs = ios_release.app_configurations()
        self.assertEqual(set(configs), {"Debug", "Release"})
        for name, settings in configs.items():
            self.assertEqual(settings.get("INFOPLIST_FILE"), "calorietracker/Info.plist", name)
        with (ios_release.PBXPROJ.parents[1] / "calorietracker" / "Info.plist").open("rb") as handle:
            self.assertIs(plistlib.load(handle)[ios_release.ENCRYPTION_KEY], False)

    def test_no_from_the_info_plist_or_every_configuration_passes(self) -> None:
        self.plist({"ITSAppUsesNonExemptEncryption": False})
        self.assertEqual(self.check(self.both(self.APP_PLIST)), "NO")
        self.assertEqual(self.check(self.both({**self.GENERATED, self.KEY: "NO"})), "NO")
        self.assertEqual(self.check(self.both({**self.APP_PLIST, self.KEY: "NO"})), "NO")
        # Inherited from the project-level configurations.
        self.assertEqual(self.check(self.both(self.GENERATED), project=self.both({self.KEY: "NO"})), "NO")
        self.assertEqual(self.check(self.both({"INFOPLIST_FILE": "\"$(SRCROOT)/calorietracker/Info.plist\""})), "NO")

    def test_a_configuration_without_the_declaration_fails(self) -> None:
        debug_only = {"Debug": {**self.GENERATED, self.KEY: "NO"}, "Release": dict(self.GENERATED)}
        with self.assertRaisesRegex(ReleaseError, "Release configuration does not declare export compliance"):
            self.check(debug_only)
        self.plist({})
        with self.assertRaisesRegex(ReleaseError, "does not declare export compliance"):
            self.check(self.both(self.APP_PLIST))
        # INFOPLIST_KEY_ settings only reach the Info.plist when Xcode generates it.
        with self.assertRaisesRegex(ReleaseError, "does not declare export compliance"):
            self.check(self.both({"GENERATE_INFOPLIST_FILE": "NO", self.KEY: "NO"}))

    def test_other_targets_do_not_count(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "does not declare export compliance"):
            self.check(self.both(self.GENERATED), other=self.both({**self.GENERATED, self.KEY: "NO"}))
        self.assertEqual(self.check(self.both({**self.GENERATED, self.KEY: "NO"}),
                                    other=self.both({**self.GENERATED, self.KEY: "maybe"})), "NO")

    def test_yes_needs_the_compliance_code(self) -> None:
        yes = {**self.GENERATED, self.KEY: "YES"}
        with self.assertRaisesRegex(ReleaseError, "needs ITSEncryptionExportComplianceCode"):
            self.check(self.both(yes))
        self.assertEqual(self.check(self.both({**yes, **self.CODE})), "YES")
        self.plist({"ITSAppUsesNonExemptEncryption": True, "ITSEncryptionExportComplianceCode": " "})
        with self.assertRaisesRegex(ReleaseError, "needs ITSEncryptionExportComplianceCode"):
            self.check(self.both(self.APP_PLIST))
        self.plist({"ITSAppUsesNonExemptEncryption": True, "ITSEncryptionExportComplianceCode": "ABC123"})
        self.assertEqual(self.check(self.both(self.APP_PLIST)), "YES")

    def test_mixed_unknown_or_non_boolean_values_fail(self) -> None:
        cases = [
            (self.both({**self.GENERATED, self.KEY: "maybe"}), None),
            (self.both({**self.GENERATED, self.KEY: "NO", f'"{self.KEY}[sdk=iphoneos*]"': "YES", **self.CODE}), None),
            (self.both(self.APP_PLIST), {"ITSAppUsesNonExemptEncryption": "NO"}),
            (self.both(self.APP_PLIST), {"ITSAppUsesNonExemptEncryption": "$(ENCRYPTION)"}),
            (self.both({**self.APP_PLIST, self.KEY: "NO"}),
             {"ITSAppUsesNonExemptEncryption": True, "ITSEncryptionExportComplianceCode": "X"}),
        ]
        for app, plist in cases:
            if plist is not None:
                self.plist(plist)
            with self.subTest(app=app, plist=plist), self.assertRaisesRegex(ReleaseError, "must be NO or YES"):
                self.check(app)
        split = {"Debug": {**self.GENERATED, self.KEY: "NO"}, "Release": {**self.GENERATED, self.KEY: "YES", **self.CODE}}
        with self.assertRaisesRegex(ReleaseError, "same in every calorietracker configuration"):
            self.check(split)

    def test_unreadable_project_inputs_fail(self) -> None:
        with self.assertRaisesRegex(ReleaseError, "cannot read"):
            self.check(self.both({"INFOPLIST_FILE": "calorietracker/Missing.plist"}))
        with self.assertRaisesRegex(ReleaseError, "cannot resolve INFOPLIST_FILE"):
            self.check(self.both({"INFOPLIST_FILE": "$(OTHER_DIR)/Info.plist"}))
        with self.assertRaisesRegex(ReleaseError, "expected one 'Missing' target"):
            ios_release.check_export_compliance(self.project(self.both(self.GENERATED)), target="Missing")
        with self.assertRaisesRegex(ReleaseError, "no build configurations"):
            self.check({})

    def test_archived_info_plist_is_checked_the_same_way(self) -> None:
        check = ios_release.check_archived_export_compliance
        self.assertEqual(check(self.plist({"ITSAppUsesNonExemptEncryption": False}, binary=True)), "NO")
        self.assertEqual(check(self.plist({"ITSAppUsesNonExemptEncryption": True,
                                           "ITSEncryptionExportComplianceCode": "ABC123"}, binary=True)), "YES")
        with self.assertRaisesRegex(ReleaseError, "archived app does not declare"):
            check(self.plist({}, binary=True))
        with self.assertRaisesRegex(ReleaseError, "needs ITSEncryptionExportComplianceCode"):
            check(self.plist({"ITSAppUsesNonExemptEncryption": True}, binary=True))
        broken = self.root / "Broken.plist"
        broken.write_bytes(b"not a plist")
        with self.assertRaisesRegex(ReleaseError, "cannot read"):
            check(broken)
        with self.assertRaisesRegex(ReleaseError, "cannot read"):
            check(self.root / "no-such-dir" / "Info.plist")

    def test_export_compliance_command_exit_codes(self) -> None:
        out = io.StringIO()
        with redirect_stdout(out):
            ios_release.main(["export-compliance", "--info-plist",
                              str(self.plist({"ITSAppUsesNonExemptEncryption": False}, binary=True))])
        self.assertIn("ITSAppUsesNonExemptEncryption = NO", out.getvalue())
        err = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(err), self.assertRaises(SystemExit) as exit_:
            ios_release.main(["export-compliance", "--info-plist", str(self.plist({}, binary=True))])
        self.assertEqual(exit_.exception.code, 1)
        self.assertIn("does not declare export compliance", err.getvalue())

    def test_gates_check_it_only_when_this_run_builds(self) -> None:
        cases = (
            ({"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF_NAME": "main"}, True),
            ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG,
              "IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_BUILD": "true"}, True),
            ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG,
              "IOS_RELEASE_AUTOMATION": "true", "IOS_UPLOAD_LISTING": "true"}, False),
            ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF_NAME": TAG}, False),
        )
        for env, builds in cases:
            missing = mock.patch.object(ios_release, "check_export_compliance",
                                        side_effect=ReleaseError("the app does not declare export compliance"))
            with self.subTest(env=env), missing as check, \
                    mock.patch.object(ios_release, "single_project_value", return_value=VERSION), \
                    mock.patch.dict(os.environ, env, clear=True), redirect_stdout(io.StringIO()), \
                    redirect_stderr(io.StringIO()) as err:
                if builds:
                    with self.assertRaises(SystemExit):
                        ios_release.main(["gates"])
                    self.assertIn("export compliance", err.getvalue())
                else:
                    ios_release.main(["gates"])
                    check.assert_not_called()


class BuildNumberTests(unittest.TestCase):
    def test_next_build_number(self) -> None:
        self.assertEqual(ios_release.next_build_number(39, ["13", "35", "12"]), 39)
        self.assertEqual(ios_release.next_build_number(39, ["13", "39"]), 40)
        self.assertEqual(ios_release.next_build_number(1, []), 1)
        self.assertEqual(ios_release.next_build_number(5, ["7.1", "abc", "9"]), 10)
        self.assertEqual(ios_release.next_build_number(5, ["٩٩"]), 5)  # only ASCII digits count


APP = "APP1"
STARTED = 1_800_000_000.0  # this run's upload start (epoch seconds)


def iso(epoch: float) -> str:
    import datetime as dt

    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).isoformat()


def build_row(
    build_id: str = "B1",
    *,
    number: str = "40",
    state: str = "VALID",
    expired: bool = False,
    uploaded: float | None = STARTED + 60,
    uploaded_raw: object = None,
    app: str = APP,
    pre: str = "P1",
) -> dict:
    attributes = {"version": number, "processingState": state, "expired": expired}
    if uploaded_raw is not None:
        attributes["uploadedDate"] = uploaded_raw
    elif uploaded is not None:
        attributes["uploadedDate"] = iso(uploaded)
    return {
        "type": "builds",
        "id": build_id,
        "attributes": attributes,
        "relationships": {
            "preReleaseVersion": {"data": {"type": "preReleaseVersions", "id": pre}},
            "app": {"data": {"type": "apps", "id": app}},
        },
    }


def pre_row(pre_id: str = "P1", version: str = VERSION, platform: str = "IOS") -> dict:
    return {"type": "preReleaseVersions", "id": pre_id, "attributes": {"version": version, "platform": platform}}


class FakeClock:
    def __init__(self, start: float) -> None:
        self.time = start
        self.sleeps: list[float] = []

    def now(self) -> float:
        return self.time

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.time += seconds


class SequenceClient:
    """Returns queued /builds payloads (or raises queued exceptions) per GET."""

    def __init__(self, responses: list) -> None:
        self.responses = list(responses)
        self.paths: list[str] = []

    def get(self, path: str) -> dict:
        self.paths.append(path)
        item = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(item, BaseException):
            raise item
        return item


def wait(client: SequenceClient, clock: FakeClock, *, minutes: int = 120, transient=None) -> dict:
    with redirect_stdout(io.StringIO()):
        return ios_release.wait_for_build(
            client,
            app_id=APP,
            marketing_version=VERSION,
            build_number="40",
            uploaded_after=STARTED,
            deadline=STARTED + minutes * 60,
            poll_seconds=30,
            now=clock.now,
            sleep=clock.sleep,
            is_transient=transient,
        )


class WaitForBuildTests(unittest.TestCase):
    def test_waits_through_missing_and_processing_then_returns_build(self) -> None:
        client = SequenceClient(
            [
                {"data": []},
                {"data": [build_row(state="PROCESSING")], "included": [pre_row()]},
                {"data": [build_row()], "included": [pre_row()]},
            ]
        )
        clock = FakeClock(STARTED + 120)
        build = wait(client, clock)
        self.assertEqual(build["id"], "B1")
        self.assertEqual(clock.sleeps, [30, 30])
        self.assertIn("filter%5Bversion%5D=40", client.paths[0])
        self.assertIn("filter%5Bapp%5D=APP1", client.paths[0])

    def test_times_out_without_using_another_build(self) -> None:
        client = SequenceClient([{"data": [build_row(state="PROCESSING")], "included": [pre_row()]}])
        clock = FakeClock(STARTED)
        with self.assertRaisesRegex(ReleaseError, "timed out"):
            wait(client, clock, minutes=10)
        self.assertLessEqual(clock.time, STARTED + 10 * 60 + 30)

    def test_ready_build_after_the_deadline_is_still_a_timeout(self) -> None:
        client = SequenceClient([{"data": [build_row()], "included": [pre_row()]}])
        clock = FakeClock(STARTED + 121 * 60)  # job started after the 120-minute budget
        with self.assertRaisesRegex(ReleaseError, "timed out"):
            wait(client, clock)
        self.assertEqual(client.paths, [], "must not poll once the budget is spent")

    def test_processing_failure_stops(self) -> None:
        for state in ("FAILED", "INVALID"):
            client = SequenceClient([{"data": [build_row(state=state)], "included": [pre_row()]}])
            with self.subTest(state=state), self.assertRaisesRegex(ReleaseError, state):
                wait(client, FakeClock(STARTED))

    def test_expired_build_is_rejected(self) -> None:
        client = SequenceClient([{"data": [build_row(expired=True)], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "expired"):
            wait(client, FakeClock(STARTED))

    def test_ambiguous_match_stops(self) -> None:
        client = SequenceClient(
            [{"data": [build_row("B1"), build_row("B2")], "included": [pre_row()]}]
        )
        with self.assertRaisesRegex(ReleaseError, "ambiguous"):
            wait(client, FakeClock(STARTED))

    def test_older_upload_is_never_substituted(self) -> None:
        old = build_row(uploaded=STARTED - 3600)
        client = SequenceClient([{"data": [old], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "older build"):
            wait(client, FakeClock(STARTED))

    def test_build_without_an_upload_time_is_never_ready(self) -> None:
        # Fail closed: a VALID build that cannot be proven to be this run's upload
        # keeps the wait going until the deadline instead of passing the check.
        for missing in (None, ""):
            client = SequenceClient(
                [{"data": [build_row(uploaded=None, uploaded_raw=missing)], "included": [pre_row()]}]
            )
            with self.subTest(missing=missing), self.assertRaisesRegex(ReleaseError, "timed out"):
                wait(client, FakeClock(STARTED), minutes=10)
        state, message, build = ios_release.evaluate_builds(
            {"data": [build_row(uploaded=None)], "included": [pre_row()]},
            app_id=APP, marketing_version=VERSION, build_number="40", uploaded_after=STARTED,
        )
        self.assertEqual((state, build), (ios_release.BuildState.WAITING, None))
        self.assertIn("upload time", message)

    def test_unreadable_upload_time_stops(self) -> None:
        for raw in ("yesterday", 1_800_000_060):
            client = SequenceClient([{"data": [build_row(uploaded_raw=raw)], "included": [pre_row()]}])
            with self.subTest(raw=raw), self.assertRaisesRegex(ReleaseError, "unreadable uploadedDate"):
                wait(client, FakeClock(STARTED))

    def test_other_versions_and_platforms_are_ignored(self) -> None:
        other_version = build_row("BX", pre="P2")
        other_platform = build_row("BY", pre="P3")
        client = SequenceClient(
            [
                {
                    "data": [other_version, other_platform],
                    "included": [pre_row("P2", "7.1"), pre_row("P3", VERSION, "MAC_OS")],
                },
                {"data": [other_version, build_row("B1")], "included": [pre_row("P2", "7.1"), pre_row()]},
            ]
        )
        self.assertEqual(wait(client, FakeClock(STARTED))["id"], "B1")

    def test_build_from_another_app_is_a_mismatch(self) -> None:
        client = SequenceClient([{"data": [build_row(app="OTHER")], "included": [pre_row()]}])
        with self.assertRaisesRegex(ReleaseError, "another app"):
            wait(client, FakeClock(STARTED))

    def test_transient_errors_are_retried_and_others_raise(self) -> None:
        transient = asc_release.AscHttpError(503, "unavailable")
        client = SequenceClient([transient, {"data": [build_row()], "included": [pre_row()]}])
        with redirect_stderr(io.StringIO()):
            build = wait(client, FakeClock(STARTED), transient=lambda exc: getattr(exc, "status", 0) == 503)
        self.assertEqual(build["id"], "B1")
        fatal = SequenceClient([asc_release.AscHttpError(401, "unauthorized")])
        with self.assertRaises(SystemExit):
            wait(fatal, FakeClock(STARTED), transient=lambda exc: getattr(exc, "status", 0) == 503)


class SelectionClient:
    """Fake ASC client for build verification and selection."""

    def __init__(self, *, build: dict, included: list, attached: str | None, sticky: bool = True) -> None:
        self.build = build
        self.included = included
        self.attached = attached
        self.sticky = sticky
        self.calls: list[tuple[str, str, dict | None]] = []

    def get(self, path: str) -> dict:
        self.calls.append(("GET", path, None))
        if path.startswith(f"/builds/{self.build['id']}"):
            return {"data": self.build, "included": self.included}
        if path.endswith("/build"):
            return {"data": {"type": "builds", "id": self.attached} if self.attached else None}
        raise AssertionError(f"unexpected GET {path}")

    def patch(self, path: str, body: dict) -> dict:
        self.calls.append(("PATCH", path, body))
        return {"data": body["data"]}

    def request(self, method: str, path: str, body: dict | None = None):
        self.calls.append((method, path, body))
        if method == "PATCH" and path.endswith("/relationships/build") and self.sticky:
            self.attached = body["data"]["id"]
        return None


def quiet(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*args, **kwargs)


class BuildSelectionTests(unittest.TestCase):
    def test_selects_and_reads_back_this_runs_build(self) -> None:
        client = SelectionClient(build=build_row("B9"), included=[pre_row()], attached="OLD")
        quiet(asc_release.select_build, client, "V1", "B9", VERSION, "40")
        self.assertEqual(client.attached, "B9")
        methods = [(m, p) for m, p, _ in client.calls]
        self.assertIn(("PATCH", "/appStoreVersions/V1/relationships/build"), methods)
        self.assertEqual(methods[-1], ("GET", "/appStoreVersions/V1/build"))

    def test_selection_that_does_not_stick_fails(self) -> None:
        client = SelectionClient(build=build_row("B9"), included=[pre_row()], attached="OLD", sticky=False)
        with self.assertRaises(SystemExit):
            quiet(asc_release.select_build, client, "V1", "B9", VERSION, "40")

    def test_unprocessed_expired_or_wrong_build_is_refused(self) -> None:
        cases = {
            "processing": (build_row("B9", state="PROCESSING"), [pre_row()]),
            "expired": (build_row("B9", expired=True), [pre_row()]),
            "number": (build_row("B9", number="41"), [pre_row()]),
            "version": (build_row("B9"), [pre_row(version="7.1")]),
        }
        for label, (build, included) in cases.items():
            client = SelectionClient(build=build, included=included, attached=None)
            with self.subTest(label), self.assertRaises(SystemExit):
                quiet(asc_release.select_build, client, "V1", "B9", VERSION, "40")
            self.assertFalse([c for c in client.calls if c[0] == "PATCH"], label)

    def test_release_type(self) -> None:
        for public, expected in ((True, "AFTER_APPROVAL"), (False, "MANUAL")):
            client = SelectionClient(build=build_row(), included=[], attached=None)
            quiet(asc_release.set_release_type, client, "V1", public_release=public)
            body = client.calls[-1][2]
            self.assertEqual(body["data"]["attributes"]["releaseType"], expected)


class TokenRefreshTests(unittest.TestCase):
    def test_token_is_reminted_during_long_waits(self) -> None:
        minted: list[str] = []

        def factory() -> str:
            minted.append(f"t{len(minted)}")
            return minted[-1]

        with mock.patch.object(asc_release.time, "monotonic", side_effect=[0, 100, 700, 700, 800]):
            client = asc_release.AscClient(token_factory=factory)
            self.assertEqual(client._authorization(), "Bearer t0")
            self.assertEqual(client._authorization(), "Bearer t1")
            self.assertEqual(client._authorization(), "Bearer t1")


class AscCliTests(unittest.TestCase):
    def run_cli(self, env: dict[str, str], *args: str) -> tuple[int, str]:
        out = io.StringIO()
        full_env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "SUBMIT_IOS_REVIEW", "PUBLIC_RELEASE"}}
        full_env.update(env)
        with mock.patch.dict(os.environ, full_env, clear=True), mock.patch.object(
            sys, "argv", ["asc_release.py", "--dry-run", *args]
        ), redirect_stdout(out), redirect_stderr(out):
            try:
                asc_release.main()
            except SystemExit as exc:
                return (exc.code if isinstance(exc.code, int) else 1), out.getvalue()
        return 0, out.getvalue()

    def test_everything_off_is_a_no_op(self) -> None:
        code, output = self.run_cli({})
        self.assertEqual(code, 0)
        self.assertIn("skipped", output)

    def test_submit_without_this_runs_build_fails(self) -> None:
        code, output = self.run_cli({"SUBMIT_IOS_REVIEW": "true"})
        self.assertEqual(code, 1)
        self.assertIn("--build-id", output)

    def test_public_release_without_submit_fails(self) -> None:
        code, _ = self.run_cli({"UPLOAD_LISTING": "true", "PUBLIC_RELEASE": "true"})
        self.assertEqual(code, 1)

    def test_only_the_fud_ai_bundle_is_supported(self) -> None:
        code, output = self.run_cli({"UPLOAD_LISTING": "true"}, "--bundle-id", "com.example.other")
        self.assertEqual(code, 1)
        self.assertIn("Fud AI iOS bundle", output)

    def test_whats_new_requires_text(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "ios" / "en-US").mkdir(parents=True)
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 1)
            self.assertIn("whats_new.txt", output)
            (Path(tmp) / "ios" / "en-US" / "whats_new.txt").write_text("Fixes\n")
            code, output = self.run_cli({"UPLOAD_WHATS_NEW": "true"}, "--metadata-dir", tmp)
            self.assertEqual(code, 0, output)

    def test_listing_excludes_whats_new_unless_enabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loc = Path(tmp) / "ios" / "en-US"
            loc.mkdir(parents=True)
            (loc / "description.txt").write_text("Desc")
            (loc / "whats_new.txt").write_text("New")
            for whats_new, expected in ((False, {"description"}), (True, {"description", "whatsNew"})):
                client = mock.Mock()
                client.get.return_value = {"data": [{"id": "LOC", "attributes": {"locale": "en-US"}}]}
                quiet(
                    asc_release.upload_listing,
                    client,
                    app_id=APP,
                    version_id="V1",
                    locale="en-US",
                    metadata_dir=Path(tmp),
                    include_listing=True,
                    include_whats_new=whats_new,
                )
                attrs = client.patch.call_args[0][1]["data"]["attributes"]
                self.assertEqual(set(attrs), expected)


class TransientErrorTests(unittest.TestCase):
    def test_real_retry_rule(self) -> None:
        import http.client
        import urllib.error

        transient = [
            http.client.RemoteDisconnected("closed"),
            ConnectionResetError("reset"),
            http.client.IncompleteRead(b""),
            urllib.error.URLError("dns"),
            TimeoutError("slow"),
            asc_release.AscHttpError(503, "busy"),
            asc_release.AscHttpError(429, "rate limited"),
            urllib.error.HTTPError("https://x", 502, "bad gateway", {}, None),
        ]
        fatal = [
            asc_release.AscHttpError(401, "unauthorized"),
            asc_release.AscHttpError(409, "conflict"),
            urllib.error.HTTPError("https://x", 404, "missing", {}, None),
            ValueError("bad"),
            ReleaseError("mismatch"),
            SystemExit(1),
        ]
        for exc in transient:
            with self.subTest(exc=repr(exc)):
                self.assertTrue(ios_release.is_transient_error(exc))
        for exc in fatal:
            with self.subTest(exc=repr(exc)):
                self.assertFalse(ios_release.is_transient_error(exc))


class CmdWaitBuildTests(unittest.TestCase):
    def test_deadline_counts_from_upload_start_and_app_lookup_is_retried(self) -> None:
        import http.client
        import argparse

        lookups = []

        def find_app(_client, bundle_id):
            lookups.append(bundle_id)
            if len(lookups) == 1:
                raise http.client.RemoteDisconnected("closed")
            return APP

        fake_module = mock.Mock(find_app=find_app)
        captured = {}

        def fake_wait(client, **kwargs):
            captured.update(kwargs)
            return {"id": "B9"}

        args = argparse.Namespace(
            version=VERSION, build=40, started_at=STARTED, wait_minutes=120, poll_seconds=30
        )
        with tempfile.NamedTemporaryFile("r", suffix=".out") as out, mock.patch.object(
            ios_release, "asc_client", return_value=(fake_module, object())
        ), mock.patch.object(ios_release, "wait_for_build", side_effect=fake_wait), mock.patch.object(
            ios_release.time, "sleep"
        ), mock.patch.object(
            ios_release.time, "time", return_value=STARTED + 30
        ), mock.patch.dict(os.environ, {"GITHUB_OUTPUT": out.name}), redirect_stdout(io.StringIO()):
            ios_release.cmd_wait_build(args)
            written = Path(out.name).read_text()
        self.assertEqual(lookups, [ios_release.BUNDLE_ID] * 2)
        self.assertEqual(captured["deadline"], STARTED + 120 * 60)
        self.assertEqual(captured["uploaded_after"], STARTED)
        self.assertEqual(captured["build_number"], "40")
        self.assertIs(captured["is_transient"], ios_release.is_transient_error)
        self.assertIn("build_id=B9", written)

    def test_app_lookup_gives_up_at_the_deadline(self) -> None:
        import http.client

        def boom():
            raise http.client.RemoteDisconnected("closed")

        clock = FakeClock(STARTED)
        with redirect_stdout(io.StringIO()), self.assertRaises(http.client.RemoteDisconnected):
            ios_release.call_with_retries(
                boom, deadline=STARTED + 90, poll_seconds=30, now=clock.now, sleep=clock.sleep
            )
        self.assertEqual(clock.sleeps, [30, 30, 30])


class FakeAsc:
    """App Store Connect stand-in for asc_release.main() (live path, no network)."""

    def __init__(self, *, state: str = "PREPARE_FOR_SUBMISSION", attached: str | None = "OLD",
                 revert_after_select: bool = False, live_text: dict | None = None,
                 live_shots: list[dict] | None = None, iaps: list[dict] | None = None) -> None:
        self.state = state
        self.attached = attached
        self.revert_after_select = revert_after_select
        self.live_text = dict(live_text or {})
        self.live_shots = live_shots
        self.iaps = iaps or []
        self.build_reads = 0
        self.calls: list[tuple[str, str]] = []

    def get(self, path: str) -> dict:
        self.calls.append(("GET", path))
        if path.startswith("/apps?"):
            return {"data": [{"id": APP, "attributes": {"bundleId": asc_release.DEFAULT_BUNDLE_ID}}]}
        if path.startswith(f"/apps/{APP}/appStoreVersions"):
            return {"data": [{"id": "V1", "attributes": {"versionString": VERSION, "appStoreState": self.state}}]}
        if path.startswith("/builds/B9"):
            return {"data": build_row("B9"), "included": [pre_row()]}
        if path == "/appStoreVersions/V1/build":
            self.build_reads += 1
            if self.revert_after_select and self.build_reads >= 2:
                return {"data": {"type": "builds", "id": "OLD"}}
            return {"data": {"type": "builds", "id": self.attached} if self.attached else None}
        if path.startswith(f"/apps/{APP}/reviewSubmissions"):
            return {"data": [{"id": "SUB1"}]}
        if path.startswith("/reviewSubmissions/SUB1/items"):
            return {"data": []}
        if path.startswith("/appStoreVersions/V1/appStoreVersionLocalizations"):
            return {"data": [{"id": "LOC1", "attributes": {"locale": "en-US", **self.live_text}}]}
        if path == "/appStoreVersionLocalizations/LOC1/appScreenshotSets":
            if self.live_shots is None:
                return {"data": []}
            return {"data": [{"id": "SET1", "attributes": {"screenshotDisplayType": "APP_IPHONE_67"}}]}
        if path == "/appScreenshotSets/SET1/appScreenshots":
            return {"data": [{"id": f"SHOT{i}", "attributes": shot} for i, shot in enumerate(self.live_shots or [])]}
        if path == f"/apps/{APP}/subscriptionGroups?limit=200":
            return {"data": []}
        if path == f"/apps/{APP}/inAppPurchasesV2?limit=200":
            return {"data": self.iaps}
        raise AssertionError(f"unexpected GET {path}")

    def post(self, path: str, body: dict) -> dict:
        self.calls.append(("POST", path))
        return {"data": {"id": "ITEM"}}

    def patch(self, path: str, body: dict) -> dict:
        self.calls.append(("PATCH", path))
        return {"data": body["data"]}

    def request(self, method: str, path: str, body: dict | None = None):
        self.calls.append((method, path))
        if method == "PATCH" and path == "/appStoreVersions/V1/relationships/build":
            self.attached = body["data"]["id"]
        return None


class LiveSubmitFlowTests(unittest.TestCase):
    def run_main(self, fake: FakeAsc) -> tuple[int, mock.Mock]:
        products = mock.Mock(return_value=asc_release.ProductSubmitResult("SUB1", 0, False))
        argv = ["asc_release.py", "--version", VERSION, "--build-id", "B9", "--build-number", "40"]
        env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "PUBLIC_RELEASE"}}
        env["SUBMIT_IOS_REVIEW"] = "true"
        code = 0
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(sys, "argv", argv), \
                mock.patch.object(asc_release, "client_from_environment", return_value=fake), \
                mock.patch.object(asc_release, "submit_in_app_purchases", products), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                asc_release.main()
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
        return code, products

    def test_selects_rechecks_then_submits(self) -> None:
        fake = FakeAsc()
        code, products = self.run_main(fake)
        self.assertEqual(code, 0)
        calls = fake.calls
        select = calls.index(("PATCH", "/appStoreVersions/V1/relationships/build"))
        submit = calls.index(("PATCH", "/reviewSubmissions/SUB1"))
        reads = [i for i, c in enumerate(calls) if c == ("GET", "/appStoreVersions/V1/build")]
        build_checks = [i for i, c in enumerate(calls) if c[0] == "GET" and c[1].startswith("/builds/B9")]
        self.assertEqual(len(reads), 2, "selection must be read back and rechecked")
        self.assertTrue(select < reads[0] < reads[1] < submit)
        self.assertEqual(len(build_checks), 2)
        self.assertTrue(build_checks[1] < reads[1] < submit, "build must be re-verified just before submit")
        self.assertIn(("PATCH", "/appStoreVersions/V1"), calls)  # release type
        products.assert_called_once()

    def test_selection_changed_before_submit_aborts(self) -> None:
        fake = FakeAsc(revert_after_select=True)
        code, _ = self.run_main(fake)
        self.assertEqual(code, 1)
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_waiting_for_review_with_another_build_aborts(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="OTHER")
        code, products = self.run_main(fake)
        self.assertEqual(code, 1)
        products.assert_not_called()
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_waiting_for_review_with_this_build_is_idempotent(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9")
        code, products = self.run_main(fake)
        self.assertEqual(code, 0)
        products.assert_called_once()
        self.assertNotIn(("PATCH", "/appStoreVersions/V1/relationships/build"), fake.calls)


def writes(fake: FakeAsc) -> list[tuple[str, str]]:
    return [call for call in fake.calls if call[0] != "GET"]


class FindAppTests(unittest.TestCase):
    BUNDLE = asc_release.DEFAULT_BUNDLE_ID

    def find(self, rows: list[dict]) -> str:
        client = mock.Mock()
        client.get.return_value = {"data": rows}
        return quiet(asc_release.find_app, client, self.BUNDLE)

    def test_only_an_exact_bundle_id_matches(self) -> None:
        rows = [
            {"id": "DEBUG", "attributes": {"bundleId": self.BUNDLE + ".debug"}},
            {"id": "APP", "attributes": {"bundleId": self.BUNDLE}},
        ]
        self.assertEqual(self.find(rows), "APP")

    def test_no_match_or_an_ambiguous_match_stops(self) -> None:
        exact = {"attributes": {"bundleId": self.BUNDLE}}
        for rows in (
            [],
            [{"id": "DEBUG", "attributes": {"bundleId": self.BUNDLE + ".debug"}}],
            [{"id": "NOATTRS"}],
            [{"id": "A", **exact}, {"id": "B", **exact}],
        ):
            with self.subTest(rows=rows), self.assertRaises(SystemExit):
                self.find(rows)


class PreflightClient:
    """Read-only App Store Connect fake: any write attempt fails the test."""

    def __init__(self, versions: list[dict], *, localization: bool = True) -> None:
        self.versions = versions
        self.localization = localization
        self.paths: list[str] = []

    def get(self, path: str) -> dict:
        self.paths.append(path)
        if path.startswith("/apps?"):
            return {"data": [{"id": APP, "attributes": {"bundleId": ios_release.BUNDLE_ID}}]}
        if path.startswith(f"/apps/{APP}/appStoreVersions"):
            return {"data": self.versions}
        if path.startswith("/appStoreVersions/V1/appStoreVersionLocalizations"):
            return {"data": [{"id": "LOC1"}] if self.localization else []}
        raise AssertionError(f"unexpected GET {path}")


def asc_version(state: str, version: str = VERSION) -> dict:
    return {"id": "V1", "attributes": {"versionString": version, "appStoreState": state}}


class VersionPreflightTests(unittest.TestCase):
    """IOS-REL-2: the App Store version is checked before anything is built."""

    def preflight(self, client: PreflightClient, *, metadata: bool = True, submit: bool = True) -> str:
        return quiet(
            ios_release.version_preflight, client, asc_release,
            version=VERSION, metadata=metadata, submit=submit,
        )

    def test_versions_the_store_step_can_use_pass(self) -> None:
        for state in ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "READY_FOR_REVIEW"):
            with self.subTest(state=state):
                self.assertEqual(self.preflight(PreflightClient([asc_version(state)])), state)

    def test_missing_version_fails_with_the_fix(self) -> None:
        err = io.StringIO()
        with redirect_stderr(err), redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
            ios_release.version_preflight(
                PreflightClient([asc_version("READY_FOR_SALE", "7.1")]), asc_release,
                version=VERSION, metadata=False, submit=True,
            )
        self.assertIn("create the version in App Store Connect first", err.getvalue())
        self.assertIn("IOS_CREATE_VERSION", err.getvalue())

    def test_versions_in_review_or_released_fail(self) -> None:
        for state in ("WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_DEVELOPER_RELEASE", "READY_FOR_SALE", ""):
            with self.subTest(state=state), self.assertRaisesRegex(ReleaseError, "Prepare for Submission"):
                self.preflight(PreflightClient([asc_version(state)]))

    def test_metadata_needs_the_locale(self) -> None:
        with self.assertRaises(SystemExit):
            self.preflight(PreflightClient([asc_version("PREPARE_FOR_SUBMISSION")], localization=False))
        client = PreflightClient([asc_version("PREPARE_FOR_SUBMISSION")], localization=False)
        self.assertEqual(self.preflight(client, metadata=False), "PREPARE_FOR_SUBMISSION")
        self.assertFalse([p for p in client.paths if "Localizations" in p])

    def test_command_reads_switches_and_skips_without_store_work(self) -> None:
        import argparse

        env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "SUBMIT_IOS_REVIEW"}}
        with mock.patch.dict(os.environ, {**env, "UPLOAD_LISTING": "false"}, clear=True), \
                mock.patch.object(ios_release, "asc_client") as client, redirect_stdout(io.StringIO()):
            ios_release.cmd_version_preflight(argparse.Namespace(version=VERSION))
        client.assert_not_called()
        with mock.patch.dict(os.environ, {**env, "SUBMIT_IOS_REVIEW": "maybe"}, clear=True), \
                self.assertRaises(ReleaseError):
            ios_release.cmd_version_preflight(argparse.Namespace(version=VERSION))
        fake = PreflightClient([asc_version("PREPARE_FOR_SUBMISSION")])
        out = io.StringIO()
        with mock.patch.dict(os.environ, {**env, "SUBMIT_IOS_REVIEW": "true"}, clear=True), \
                mock.patch.object(ios_release, "asc_client", return_value=(asc_release, fake)), redirect_stdout(out):
            ios_release.cmd_version_preflight(argparse.Namespace(version=VERSION))
        self.assertIn("exists and is PREPARE_FOR_SUBMISSION", out.getvalue())


class VersionCreationClient:
    """App Store Connect stand-in for `asc_release.py --create-version` (no network).

    Only the app lookup, the IOS version listing and POST /appStoreVersions are
    expected; any other call fails the test.
    """

    def __init__(self, *, versions: list[tuple[str, str]] | None = None,
                 app_id: str = asc_release.ASC_APP_ID, listed_after_create: bool = True) -> None:
        # The app's IOS App Store versions as (versionString, appStoreState).
        self.versions = [("7.1.2", "READY_FOR_SALE")] if versions is None else list(versions)
        self.app_id = app_id
        # False: the created version does not show up in the listing yet.
        self.listed_after_create = listed_after_create
        self.calls: list[tuple[str, str]] = []
        self.bodies: list[dict] = []

    def get(self, path: str) -> dict:
        self.calls.append(("GET", path))
        if path.startswith("/apps?"):
            return {"data": [{"id": self.app_id, "attributes": {"bundleId": asc_release.DEFAULT_BUNDLE_ID}}]}
        if path.startswith(f"/apps/{self.app_id}/appStoreVersions"):
            return {"data": [{"id": f"V{i}", "attributes": {"versionString": number, "appStoreState": state}}
                             for i, (number, state) in enumerate(self.versions)]}
        raise AssertionError(f"unexpected GET {path}")

    def post(self, path: str, body: dict) -> dict:
        self.calls.append(("POST", path))
        self.bodies.append(body)
        if path != "/appStoreVersions":
            raise AssertionError(f"unexpected POST {path}")
        if self.listed_after_create:
            self.versions.append((body["data"]["attributes"]["versionString"], "PREPARE_FOR_SUBMISSION"))
        return {"data": {"id": "NEW"}}

    def writes(self) -> list[tuple[str, str]]:
        return [call for call in self.calls if call[0] != "GET"]


class CreateVersionTests(unittest.TestCase):
    """--create-version (IOS_CREATE_VERSION): add the tag's App Store version only when it is missing."""

    ON = {"CREATE_IOS_VERSION": "true"}
    FLAGS = {"CREATE_IOS_VERSION", "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS",
             "SUBMIT_IOS_REVIEW", "PUBLIC_RELEASE"}

    def create(self, fake: VersionCreationClient, env: dict[str, str] | None = None, *args: str,
               version: str | None = VERSION) -> tuple[int, str]:
        argv = ["asc_release.py", "--create-version", *args]
        if version is not None:
            argv += ["--version", version]
        full_env = {k: v for k, v in os.environ.items() if k not in self.FLAGS}
        full_env.update(self.ON if env is None else env)
        out = io.StringIO()
        code = 0
        with mock.patch.dict(os.environ, full_env, clear=True), mock.patch.object(sys, "argv", argv), \
                mock.patch.object(asc_release, "client_from_environment", return_value=fake), \
                redirect_stdout(out), redirect_stderr(out):
            try:
                asc_release.main()
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
        return code, out.getvalue()

    def test_switch_off_makes_no_calls(self) -> None:
        fake = VersionCreationClient()
        code, output = self.create(fake, {})
        self.assertEqual(code, 0, output)
        self.assertIn("skipped", output)
        self.assertEqual(fake.calls, [])

    def test_existing_version_is_left_alone(self) -> None:
        for state in ("PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW", "WAITING_FOR_REVIEW", "READY_FOR_SALE"):
            with self.subTest(state=state):
                fake = VersionCreationClient(versions=[("7.1.2", "READY_FOR_SALE"), (VERSION, state)])
                code, output = self.create(fake)
                self.assertEqual(code, 0, output)
                self.assertIn(f"already exists ({state})", output)
                self.assertEqual(fake.writes(), [])

    def test_missing_version_is_created_for_this_app_and_read_back(self) -> None:
        fake = VersionCreationClient()
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.writes(), [("POST", "/appStoreVersions")])
        body = fake.bodies[0]["data"]
        self.assertEqual(body["type"], "appStoreVersions")
        self.assertEqual(body["attributes"], {"platform": "IOS", "versionString": VERSION})
        self.assertEqual(body["relationships"]["app"]["data"], {"type": "apps", "id": asc_release.ASC_APP_ID})
        post = fake.calls.index(("POST", "/appStoreVersions"))
        self.assertTrue(any(method == "GET" and "/appStoreVersions" in path
                            for method, path in fake.calls[post + 1:]), "the created version must be read back")
        self.assertIn(f"created App Store version {VERSION} (PREPARE_FOR_SUBMISSION)", output)

    def test_read_back_miss_names_the_created_version(self) -> None:
        fake = VersionCreationClient(listed_after_create=False)
        code, output = self.create(fake)
        self.assertEqual(code, 1, output)
        self.assertEqual(fake.writes(), [("POST", "/appStoreVersions")])
        self.assertIn(f"created App Store version {VERSION} (NEW), but reading it back found 0", output)
        self.assertNotIn("turn on IOS_CREATE_VERSION", output)

    def test_another_version_on_its_way_blocks_creation(self) -> None:
        for state in ("PREPARE_FOR_SUBMISSION", "WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_DEVELOPER_RELEASE",
                      "DEVELOPER_REJECTED", ""):
            with self.subTest(state=state):
                fake = VersionCreationClient(versions=[("7.1.2", "READY_FOR_SALE"), ("7.1.3", state)])
                code, output = self.create(fake)
                self.assertEqual(code, 1, output)
                self.assertIn(f"is still {state or 'in an unknown state'}", output)
                self.assertIn("nothing was changed", output)
                self.assertEqual(fake.writes(), [])

    def test_version_not_newer_than_an_existing_one_is_refused(self) -> None:
        # Numeric comparison: 7.10 is newer than 7.2, and 7.2.0 is the same version.
        for existing in ("7.3", "7.10", "7.2.0", "7.2.1", "8"):
            with self.subTest(existing=existing):
                fake = VersionCreationClient(versions=[(existing, "READY_FOR_SALE")])
                code, output = self.create(fake)
                self.assertEqual(code, 1, output)
                self.assertIn(f"not newer than the existing version {existing}", output)
                self.assertEqual(fake.writes(), [])

    def test_older_finished_versions_allow_creation(self) -> None:
        fake = VersionCreationClient(versions=[
            ("1.0", "REPLACED_WITH_NEW_VERSION"), ("6.9", "REMOVED_FROM_SALE"),
            ("7.0", "DEVELOPER_REMOVED_FROM_SALE"), ("7.1.10", "READY_FOR_SALE"),
        ])
        code, output = self.create(fake)
        self.assertEqual(code, 0, output)
        self.assertEqual(fake.writes(), [("POST", "/appStoreVersions")])

    def test_other_app_or_bad_version_fails_without_writing(self) -> None:
        fake = VersionCreationClient(app_id="1234567890")
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertIn(f"expected {asc_release.ASC_APP_ID}", output)
        self.assertEqual(fake.writes(), [])
        for bad in ("7.2-beta", "v7.2", "7.2.1.1", "7..2", "\u0667.\u0662"):
            with self.subTest(bad=bad):
                fake = VersionCreationClient()
                code, output = self.create(fake, version=bad)
                self.assertEqual(code, 1, output)
                self.assertIn("must look like", output)
                self.assertEqual(fake.calls, [])

    def test_ambiguous_version_fails_without_writing(self) -> None:
        fake = VersionCreationClient(versions=[(VERSION, "PREPARE_FOR_SUBMISSION"), (VERSION, "READY_FOR_SALE")])
        code, output = self.create(fake)
        self.assertEqual(code, 1)
        self.assertIn("ambiguous", output)
        self.assertEqual(fake.writes(), [])

    def test_needs_the_version_and_the_fud_ai_bundle(self) -> None:
        fake = VersionCreationClient()
        code, output = self.create(fake, version=None)
        self.assertEqual(code, 1)
        self.assertIn("needs --version", output)
        code, output = self.create(fake, None, "--bundle-id", "com.example.other")
        self.assertEqual(code, 1)
        self.assertIn("Fud AI iOS bundle", output)
        self.assertEqual(fake.calls, [])

    def test_dry_run_makes_no_calls(self) -> None:
        fake = VersionCreationClient()
        code, output = self.create(fake, None, "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertIn("would create", output)
        self.assertEqual(fake.calls, [])

    def test_dry_run_rejects_a_bad_version_like_the_live_run(self) -> None:
        fake = VersionCreationClient()
        code, output = self.create(fake, None, "--dry-run", version="7.2-beta")
        self.assertEqual(code, 1, output)
        self.assertIn("must look like", output)
        self.assertNotIn("would create", output)
        self.assertEqual(fake.calls, [])


class MetadataRerunTests(unittest.TestCase):
    """IOS-REL-3: re-running the store job after a partial review submission."""

    PNG = b"\x89PNG\r\n\x1a\n" + b"screenshot" * 4
    LISTING = {"UPLOAD_LISTING": "true", "UPLOAD_WHATS_NEW": "true"}
    SUBMIT = {"SUBMIT_IOS_REVIEW": "true"}

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        loc = self.root / "meta" / "ios" / "en-US"
        loc.mkdir(parents=True)
        (loc / "description.txt").write_text("Track food with AI\n")
        (loc / "whats_new.txt").write_text("Fixes.\n")
        self.shots = self.root / "shots"
        self.shots.mkdir()
        (self.shots / "01-home.png").write_bytes(self.PNG)
        self.same_text = {"description": "Track food with AI", "whatsNew": "Fixes."}
        self.same_shots = [{"fileName": "01-home.png", "fileSize": len(self.PNG),
                            "sourceFileChecksum": hashlib.md5(self.PNG).hexdigest()}]

    def run_main(self, fake: FakeAsc, env: dict[str, str]) -> tuple[int, mock.Mock]:
        products = mock.Mock(return_value=asc_release.ProductSubmitResult("SUB1", 0, False))
        argv = ["asc_release.py", "--version", VERSION, "--build-id", "B9", "--build-number", "40",
                "--metadata-dir", str(self.root / "meta"), "--screenshots-dir", str(self.shots)]
        full_env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "SUBMIT_IOS_REVIEW", "PUBLIC_RELEASE"}}
        full_env.update(env)
        code = 0
        with mock.patch.dict(os.environ, full_env, clear=True), mock.patch.object(sys, "argv", argv), \
                mock.patch.object(asc_release, "client_from_environment", return_value=fake), \
                mock.patch.object(asc_release, "submit_in_app_purchases", products), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                asc_release.main()
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
        return code, products

    def test_ready_for_review_rerun_updates_text_and_submits(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW", live_text={"description": "Old", "whatsNew": "Fixes."})
        code, _ = self.run_main(fake, {**self.LISTING, **self.SUBMIT})
        self.assertEqual(code, 0)
        self.assertIn(("PATCH", "/appStoreVersionLocalizations/LOC1"), fake.calls)
        self.assertIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_waiting_for_review_rerun_with_matching_text_is_idempotent(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9", live_text=self.same_text)
        code, products = self.run_main(fake, {**self.LISTING, **self.SUBMIT})
        self.assertEqual(code, 0)
        self.assertEqual(writes(fake), [])
        products.assert_called_once()

    def test_waiting_for_review_with_changed_text_fails_before_any_write(self) -> None:
        fake = FakeAsc(state="WAITING_FOR_REVIEW", attached="B9",
                       live_text={**self.same_text, "description": "Older copy"})
        for env in ({**self.LISTING, **self.SUBMIT}, self.LISTING):
            with self.subTest(env=env):
                code, products = self.run_main(fake, env)
                self.assertEqual(code, 1)
                self.assertEqual(writes(fake), [])
                products.assert_not_called()

    def test_locked_screenshots_are_compared_not_replaced(self) -> None:
        fake = FakeAsc(state="READY_FOR_REVIEW", live_shots=self.same_shots)
        code, _ = self.run_main(fake, {"UPLOAD_SCREENSHOTS": "true", **self.SUBMIT})
        self.assertEqual(code, 0)
        self.assertFalse([c for c in writes(fake) if "Screenshot" in c[1]])
        # A different file must stop the run before the text update writes anything.
        changed = [{**self.same_shots[0], "sourceFileChecksum": "0" * 32}]
        fake = FakeAsc(state="READY_FOR_REVIEW", live_shots=changed, live_text={"description": "Old"})
        code, _ = self.run_main(fake, {"UPLOAD_SCREENSHOTS": "true", **self.LISTING, **self.SUBMIT})
        self.assertEqual(code, 1)
        self.assertEqual(writes(fake), [])

    def test_editable_version_skips_what_already_matches(self) -> None:
        fake = FakeAsc(live_text=self.same_text, live_shots=self.same_shots)
        code, _ = self.run_main(fake, {**self.LISTING, "UPLOAD_SCREENSHOTS": "true"})
        self.assertEqual(code, 0)
        self.assertEqual(writes(fake), [])

    def test_other_states_still_refuse_metadata(self) -> None:
        for state in ("IN_REVIEW", "READY_FOR_SALE"):
            fake = FakeAsc(state=state, live_text={"description": "Old"})
            with self.subTest(state=state):
                code, _ = self.run_main(fake, self.LISTING)
                self.assertEqual(code, 1)
                self.assertEqual(writes(fake), [])


class InAppPurchaseOrderTests(unittest.TestCase):
    """IOS-REL-4: catalog IAPs reach App Review only after the final build recheck."""

    PRODUCT = "com.apoorvdarshan.calorietracker.credits.50"
    IAP = {"id": "IAP1", "attributes": {"name": "Credits 50", "productId": PRODUCT, "state": "READY_TO_SUBMIT"}}

    def run_submit(self, fake: FakeAsc) -> int:
        argv = ["asc_release.py", "--version", VERSION, "--build-id", "B9", "--build-number", "40"]
        env = {k: v for k, v in os.environ.items() if k not in {
            "UPLOAD_LISTING", "UPLOAD_WHATS_NEW", "UPLOAD_SCREENSHOTS", "PUBLIC_RELEASE"}}
        env["SUBMIT_IOS_REVIEW"] = "true"
        code = 0
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(sys, "argv", argv), \
                mock.patch.object(asc_release, "client_from_environment", return_value=fake), \
                mock.patch.object(asc_release, "intended_product_ids", return_value={self.PRODUCT}), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                asc_release.main()
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 1
        return code

    def test_iap_is_submitted_after_the_recheck_and_before_the_version(self) -> None:
        fake = FakeAsc(iaps=[self.IAP])
        self.assertEqual(self.run_submit(fake), 0)
        calls = fake.calls
        iap = calls.index(("POST", "/inAppPurchaseSubmissions"))
        reads = [i for i, c in enumerate(calls) if c == ("GET", "/appStoreVersions/V1/build")]
        submit = calls.index(("PATCH", "/reviewSubmissions/SUB1"))
        self.assertEqual(len(reads), 2)
        self.assertTrue(reads[1] < iap < submit)

    def test_failed_recheck_sends_nothing_to_app_review(self) -> None:
        fake = FakeAsc(iaps=[self.IAP], revert_after_select=True)
        self.assertEqual(self.run_submit(fake), 1)
        self.assertNotIn(("POST", "/inAppPurchaseSubmissions"), fake.calls)
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_failed_iap_submission_keeps_the_version_out_of_review(self) -> None:
        class Refusing(FakeAsc):
            def post(self, path: str, body: dict) -> dict:
                if path == "/inAppPurchaseSubmissions":
                    self.calls.append(("POST-REFUSED", path))
                    raise asc_release.AscHttpError(409, "conflict")
                return super().post(path, body)

        fake = Refusing(iaps=[self.IAP])
        self.assertEqual(self.run_submit(fake), 1)
        self.assertNotIn(("PATCH", "/reviewSubmissions/SUB1"), fake.calls)

    def test_deferred_collection_posts_nothing(self) -> None:
        fake = FakeAsc(iaps=[self.IAP])
        result = quiet(asc_release.submit_in_app_purchases, fake, APP,
                       intended_ids={self.PRODUCT}, submission_id="SUB1", defer_in_app_purchases=True)
        self.assertEqual(writes(fake), [])
        self.assertEqual(result.pending_in_app_purchases, [("IAP1", "Credits 50", self.PRODUCT)])
        self.assertEqual(quiet(asc_release.submit_deferred_in_app_purchases, fake, result), 1)
        self.assertEqual(writes(fake), [("POST", "/inAppPurchaseSubmissions")])
        self.assertEqual(result.pending_in_app_purchases, [])


class ScreenshotUploadTests(unittest.TestCase):
    def test_upload_records_the_file_checksum(self) -> None:
        data = b"\x89PNG\r\n\x1a\nimage"
        client = mock.Mock()
        client.post.return_value = {"data": {"id": "S1", "attributes": {"uploadOperations": [
            {"url": "https://upload.example", "offset": 0, "length": len(data), "method": "PUT", "requestHeaders": []}
        ]}}}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "01-home.png"
            path.write_bytes(data)
            quiet(asc_release.upload_screenshot_file, client, "SET1", path)
        attrs = client.patch.call_args[0][1]["data"]["attributes"]
        self.assertEqual(attrs, {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()})


class RedactionTests(unittest.TestCase):
    """IOS-REL-6: failure artifacts never carry the key or issuer ID."""

    KEY = "TESTKEY0001"
    ISSUER = "00000000-test-4000-8000-issuer000001"

    def secrets(self) -> dict[str, str]:
        return {"ASC_KEY_ID": self.KEY, "ASC_ISSUER_ID": self.ISSUER}

    def test_text_binary_and_utf16_copies_never_survive(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logs = Path(tmp) / "logs"
            dist = logs / "Fud.xcdistributionlogs"
            dist.mkdir(parents=True)
            (logs / "archive.log").write_text(
                f"-authenticationKeyID {self.KEY} -authenticationKeyIssuerID {self.ISSUER}\nAuthKey_{self.KEY}.p8\n"
            )
            (dist / "options.plist").write_bytes(
                b"bplist00\x00\x01" + self.KEY.encode() + b"\x00" + self.ISSUER.upper().encode() + b"\xff"
            )
            (dist / "upload.log").write_bytes(f"key {self.KEY}".encode("utf-16-le"))
            (logs / "clean.log").write_text("nothing to hide")
            outside = Path(tmp) / "outside.txt"
            outside.write_text(self.KEY)
            (logs / "link.txt").symlink_to(outside)

            changed, removed = redact_credential_ids.redact_folder(logs, self.secrets())

            text = (logs / "archive.log").read_text()
            self.assertEqual(
                text,
                "-authenticationKeyID <ASC_KEY_ID> -authenticationKeyIssuerID <ASC_ISSUER_ID>\nAuthKey_<ASC_KEY_ID>.p8\n",
            )
            plist = (dist / "options.plist").read_bytes()
            self.assertTrue(plist.startswith(b"bplist00\x00\x01<ASC_KEY_ID>\x00<ASC_ISSUER_ID>"))
            self.assertFalse((dist / "upload.log").exists(), "UTF-16 copies are removed")
            self.assertFalse((logs / "link.txt").exists(), "links out of the folder are removed")
            self.assertEqual(outside.read_text(), self.KEY, "the link target is untouched")
            self.assertEqual((logs / "clean.log").read_text(), "nothing to hide")
            self.assertEqual(changed, 2)
            self.assertEqual(len(removed), 2)
            for path in logs.rglob("*"):
                if path.is_file():
                    self.assertFalse(
                        redact_credential_ids.remaining_ids(path.read_bytes(), [self.KEY, self.ISSUER]), path
                    )

    def test_cli(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "export.log").write_text(f"--apiKey {self.KEY}")
            out = io.StringIO()
            with mock.patch.dict(os.environ, self.secrets()), redirect_stdout(out):
                self.assertEqual(redact_credential_ids.main(["redact", tmp]), 0)
            self.assertEqual((Path(tmp) / "export.log").read_text(), "--apiKey <ASC_KEY_ID>")
            with mock.patch.dict(os.environ, {"ASC_KEY_ID": "", "ASC_ISSUER_ID": ""}), redirect_stdout(io.StringIO()):
                self.assertEqual(redact_credential_ids.main(["redact", tmp]), 0)
            with redirect_stderr(io.StringIO()):
                self.assertEqual(redact_credential_ids.main(["redact"]), 2)


class PrintGatesTests(unittest.TestCase):
    """P-21: the printed summary never shows an invalid switch as off."""

    def run_print(self, env: dict[str, str]) -> str:
        clean = {k: v for k, v in os.environ.items() if not k.startswith(("IOS_", "ANDROID_", "STORE_"))}
        clean.update(env)
        out = io.StringIO()
        with mock.patch.dict(os.environ, clean, clear=True), \
                mock.patch.object(sys, "argv", ["print_gates.py", "--platform", "all"]), redirect_stdout(out):
            print_gates.main()
        return out.getvalue()

    def test_invalid_values_are_shown_as_invalid(self) -> None:
        output = self.run_print({"IOS_SUBMIT_REVIEW": "maybe", "ANDROID_RELEASE_AUTOMATION": " ON "})
        self.assertIn("IOS_SUBMIT_REVIEW=INVALID 'maybe' (a release run fails)", output)
        self.assertIn("ANDROID_RELEASE_AUTOMATION=true", output)
        self.assertIn("IOS_UPLOAD_BUILD=false (unset)", output)
        self.assertIn("invalid value", output)
        self.assertIn("one or more live gates are on", output)

    def test_create_version_switch_is_listed(self) -> None:
        output = self.run_print({"IOS_RELEASE_AUTOMATION": "true", "IOS_CREATE_VERSION": "true"})
        self.assertIn("IOS_CREATE_VERSION=true\n", output)
        self.assertIn("one or more live gates are on", output)
        self.assertIn("IOS_CREATE_VERSION=INVALID", self.run_print({"IOS_CREATE_VERSION": "maybe"}))

    def test_everything_off(self) -> None:
        output = self.run_print({"IOS_RELEASE_AUTOMATION": "false"})
        self.assertIn("IOS_RELEASE_AUTOMATION=false\n", output)
        self.assertIn("all gates off", output)
        self.assertNotIn("INVALID", output)


class ReleaseNotesTests(unittest.TestCase):
    TEXT = (
        "# Release notes\n\n## Unreleased\n\nWork in progress.\n\n"
        "## ios-v7.2\n\nShipped.\n\n## android-v7.2\n\nBeta.\n\n## ios-v7.3\n\n## v7.0\n\nOld.\n"
    )

    def run_cli(self, *args: str, text: str | None = TEXT) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as root:
            if text is not None:
                (Path(root) / "RELEASE_NOTES.md").write_text(text, encoding="utf-8")
            with mock.patch.object(release_notes, "ROOT", Path(root)), redirect_stdout(out), redirect_stderr(err):
                try:
                    release_notes.main(list(args))
                except SystemExit as exc:
                    return (exc.code if isinstance(exc.code, int) else 1), out.getvalue(), err.getvalue()
        return 0, out.getvalue(), err.getvalue()

    def test_exact_tag_sections(self) -> None:
        self.assertEqual(release_notes.notes_for_tag("ios-v7.2", self.TEXT), "Shipped.")
        self.assertEqual(release_notes.notes_for_tag("android-v7.2", self.TEXT), "Beta.")
        self.assertEqual(release_notes.notes_for_tag("v7.0", self.TEXT), "Old.")
        self.assertIsNone(release_notes.section_for_tag("ios-v7.4", self.TEXT))
        with self.assertRaisesRegex(ValueError, "Add a reviewed ## ios-v7.4"):
            release_notes.notes_for_tag("ios-v7.4", self.TEXT)

    def test_empty_section_and_unreleased_always_fail(self) -> None:
        for strict in (True, False):
            lookup = release_notes.notes_for_tag if strict else release_notes.section_for_tag
            with self.subTest(strict=strict):
                with self.assertRaisesRegex(ValueError, "empty"):
                    lookup("ios-v7.3", self.TEXT)
                with self.assertRaisesRegex(ValueError, "Unreleased"):
                    lookup("Unreleased", self.TEXT)

    def test_missing_notes_fail_unless_optional(self) -> None:
        code, out, _ = self.run_cli("ios-v7.4")
        self.assertEqual(code, 2)
        self.assertEqual(out, "")
        # A tag without notes still gets a GitHub Release (generated notes).
        code, out, err = self.run_cli("--if-present", "ios-v7.4")
        self.assertEqual((code, out), (0, ""))
        self.assertIn("no ## ios-v7.4 section", err)
        code, out, _ = self.run_cli("--if-present", "ios-v7.2")
        self.assertEqual((code, out), (0, "Shipped.\n"))
        code, _, _ = self.run_cli("--if-present", "ios-v7.3")
        self.assertEqual(code, 2, "an empty section is malformed even when notes are optional")

    def test_duplicate_sections_fail(self) -> None:
        text = self.TEXT + "\n## ios-v7.2\n\nShipped again.\n"
        for lookup in (release_notes.notes_for_tag, release_notes.section_for_tag):
            with self.subTest(lookup=lookup.__name__), self.assertRaisesRegex(ValueError, "2 ## ios-v7.2 sections"):
                lookup("ios-v7.2", text)
        self.assertEqual(release_notes.notes_for_tag("android-v7.2", text), "Beta.")
        code, out, err = self.run_cli("--if-present", "ios-v7.2", text=text)
        self.assertEqual((code, out), (2, ""))
        self.assertIn("keep exactly one", err)

    def test_missing_notes_file_is_missing_notes(self) -> None:
        code, out, err = self.run_cli("--if-present", "ios-v7.2", text=None)
        self.assertEqual((code, out), (0, ""))
        self.assertIn("no ## ios-v7.2 section", err)
        code, out, err = self.run_cli("ios-v7.2", text=None)
        self.assertEqual((code, out), (2, ""))
        self.assertIn("Add a reviewed ## ios-v7.2", err)


class IosWhatsNewTests(unittest.TestCase):
    def prepare(self, appstore: str) -> tuple[int, str, Path]:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "APPSTORE.md").write_text(appstore, encoding="utf-8")
        argv = ["prepare_whats_new.py", "--platform", "ios", "--tag", TAG, "--out", str(root / "out")]
        out = io.StringIO()
        with mock.patch.object(prepare_whats_new, "ROOT", root), mock.patch.object(sys, "argv", argv), \
                redirect_stdout(out), redirect_stderr(out):
            try:
                prepare_whats_new.main()
            except SystemExit as exc:
                return (exc.code if isinstance(exc.code, int) else 1), out.getvalue(), root
        return 0, out.getvalue(), root

    def test_writes_whats_new_for_asc(self) -> None:
        code, output, root = self.prepare("## What's New (v7.2)\n```\n  Fixes.\n```\n")
        self.assertEqual(code, 0, output)
        written = root / "out" / "ios" / "en-US" / "whats_new.txt"
        self.assertEqual(written.read_text(encoding="utf-8"), "Fixes.\n")

    def test_missing_empty_or_too_long_text_fails(self) -> None:
        cases = {
            "no heading": "## Description\n```\nApp\n```\n",
            "no fence": "## What's New\nFixes.\n## Privacy URL\n",
            "empty": "## What's New\n```\n\n```\n",
            "too long": "## What's New\n```\n" + "x" * 4001 + "\n```\n",
        }
        for label, text in cases.items():
            with self.subTest(label):
                code, _, _ = self.prepare(text)
                self.assertEqual(code, 1)
        code, output, _ = self.prepare("## What's New\n```\n" + "x" * 4000 + "\n```\n")
        self.assertEqual(code, 0, output)

    def test_markdown_is_rejected_plain_bullets_are_fine(self) -> None:
        for label, body in {
            "bold": "**New** logging",
            "link": "Read [the notes](https://fud-ai.app)",
            "code": "Use `Settings` to switch",
            "heading": "# What changed",
        }.items():
            with self.subTest(label):
                code, output, _ = self.prepare(f"## What's New\n```\n{body}\n```\n")
                self.assertEqual(code, 1)
                self.assertIn("plain text", output)
        plain = "NEW\n• Faster logging → fewer taps\n- Fixes\n5*3 = 15, snake_case stays"
        code, output, _ = self.prepare(f"## What's New\n```\n{plain}\n```\n")
        self.assertEqual(code, 0, output)


if __name__ == "__main__":
    unittest.main()
