#!/usr/bin/env python3
"""Print store automation gate status. Never mutates stores.

What a run may actually do is resolved by `ios_release.py gates` and
`android_release.py gates`, which also reject invalid values. This summary shows
an invalid value as INVALID instead of reading it as off.
"""
from __future__ import annotations

import argparse
import os

GATES = {
    "ios": [
        "IOS_RELEASE_AUTOMATION",
        "IOS_CREATE_VERSION",
        "IOS_UPLOAD_BUILD",
        "IOS_UPLOAD_LISTING",
        "IOS_UPLOAD_WHATS_NEW",
        "IOS_UPLOAD_SCREENSHOTS",
        "IOS_SUBMIT_REVIEW",
        "IOS_PUBLIC_RELEASE",
    ],
    "android": [
        "ANDROID_RELEASE_AUTOMATION",
        "ANDROID_UPLOAD_LISTING",
        "ANDROID_UPLOAD_WHATS_NEW",
        "ANDROID_UPLOAD_SCREENSHOTS",
        "ANDROID_INTERNAL_TESTING",
        "ANDROID_CLOSED_TESTING",
        "ANDROID_PRODUCTION_ROLLOUT",
    ],
    "shared": ["STORE_SYNC_REVENUECAT"],
}
TRUE_VALUES = {"1", "true", "yes", "on"}
FALSE_VALUES = {"", "0", "false", "no", "off"}


def describe(name: str) -> tuple[str, bool]:
    """(shown value, on) for one switch, the way the release gates read it."""
    raw = os.environ.get(name, "")
    value = raw.strip().lower()
    if value in TRUE_VALUES:
        return "true", True
    if value in FALSE_VALUES:
        return ("false" if value else "false (unset)"), False
    return f"INVALID {raw!r} (a release run fails)", False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("ios", "android", "all"), default="all")
    args = parser.parse_args()
    groups = ["ios", "android"] if args.platform == "all" else [args.platform]
    groups.append("shared")

    print("Store automation gates (false = setup-only / safe):")
    any_on = False
    any_invalid = False
    for group in groups:
        print(f"  [{group}]")
        for name in GATES[group]:
            shown, on = describe(name)
            any_on = any_on or on
            any_invalid = any_invalid or shown.startswith("INVALID")
            print(f"    {name}={shown}")
    if "ios" in groups and not describe("IOS_RELEASE_AUTOMATION")[1]:
        print("  note: IOS_RELEASE_AUTOMATION is off, so every iOS store operation is skipped")
    if "android" in groups and not describe("ANDROID_RELEASE_AUTOMATION")[1]:
        print("  note: ANDROID_RELEASE_AUTOMATION is off, so android-v* tags build and release nothing")
    if any_invalid:
        print("WARNING: one or more switches have an invalid value; release runs will fail until it is fixed")
    if not any_on:
        print("all gates off — no live upload/listing/submit/rollout/sync will run")
    else:
        print("WARNING: one or more live gates are on")


if __name__ == "__main__":
    main()
