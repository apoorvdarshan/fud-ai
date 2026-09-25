#!/usr/bin/env python3
"""Assign an already-uploaded Android version to Play open testing.

Play calls this track "beta". The binary is uploaded earlier to the production
track as a draft. This step reuses that version code in one new edit so testers
can install it without rolling production out.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

PACKAGE_NAME = "com.apoorvdarshan.calorietracker"
OPEN_TESTING_TRACK = "beta"


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def load_service_account_info() -> dict:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        fail("PLAY_SERVICE_ACCOUNT_JSON is not set")
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        fail(f"PLAY_SERVICE_ACCOUNT_JSON is not valid JSON: {exc}")


def release_notes(whats_new_dir: Path | None) -> list[dict[str, str]]:
    if whats_new_dir is None or not whats_new_dir.is_dir():
        return []
    notes: list[dict[str, str]] = []
    for path in sorted(whats_new_dir.glob("whatsnew-*")):
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            continue
        notes.append({"language": path.name.removeprefix("whatsnew-"), "text": text})
    return notes


def assign_open_testing(
    service,
    *,
    package_name: str,
    version_code: int,
    notes: list[dict[str, str]],
) -> None:
    edit = service.edits().insert(packageName=package_name, body={}).execute()
    edit_id = edit["id"]
    listed = service.edits().tracks().list(packageName=package_name, editId=edit_id).execute()
    names = [track.get("track") for track in listed.get("tracks", [])]
    if OPEN_TESTING_TRACK not in names:
        fail(
            "Play open testing track 'beta' was not found. "
            f"Create Open testing in Play Console first. Available tracks: {', '.join(str(name) for name in names)}"
        )
    release: dict = {
        "versionCodes": [str(version_code)],
        "status": "completed",
    }
    if notes:
        release["releaseNotes"] = notes
    service.edits().tracks().update(
        packageName=package_name,
        editId=edit_id,
        track=OPEN_TESTING_TRACK,
        body={"track": OPEN_TESTING_TRACK, "releases": [release]},
    ).execute()
    service.edits().commit(packageName=package_name, editId=edit_id).execute()
    print(f"Open testing now serves versionCode {version_code}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--package-name", default=PACKAGE_NAME)
    parser.add_argument("--whats-new-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if not args.version_code or args.version_code < 1:
        fail("--version-code is required")
    notes = release_notes(args.whats_new_dir)
    if args.dry_run:
        print(
            "dry-run: would set Play open testing (beta) to completed "
            f"for versionCode {args.version_code}"
        )
        return

    from google.oauth2 import service_account
    from googleapiclient.discovery import build

    creds = service_account.Credentials.from_service_account_info(
        load_service_account_info(),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    service = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
    assign_open_testing(
        service,
        package_name=args.package_name,
        version_code=args.version_code,
        notes=notes,
    )


if __name__ == "__main__":
    main()
