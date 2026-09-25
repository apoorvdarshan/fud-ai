#!/usr/bin/env python3
"""Add a processed iOS build to the External TestFlight group.

Xcode Cloud uploads the binary. This step waits until Apple finishes processing
that build, adds it to the External group, and sends beta review when Apple
still requires it. It does not submit the App Store version or any subscription.
"""
from __future__ import annotations

import argparse
import os
import time

from asc_release import (
    AscClient,
    fail,
    find_app,
    load_credentials,
    make_asc_token,
)

BUNDLE_ID = "com.apoorvdarshan.calorietracker"
EXTERNAL_GROUP_NAME = "External"
BETA_DESCRIPTION = (
    "Fud AI logs meals from a photo, barcode, voice, or text, "
    "and tracks calories and nutrition on your iPhone."
)
ALREADY_IN_REVIEW = {
    "WAITING_FOR_BETA_REVIEW",
    "IN_BETA_REVIEW",
    "BETA_APPROVED",
    "IN_BETA_TESTING",
    "IN_EXPORT_COMPLIANCE_REVIEW",
}


def pages(client: AscClient, path: str):
    seen = 0
    while path and seen < 10:
        payload = client.get(path)
        yield payload
        path = (payload.get("links") or {}).get("next")
        seen += 1


def ci_build_number(client: AscClient, app_id: str, commit: str) -> str | None:
    product = client.get(f"/apps/{app_id}/ciProduct")
    product_id = (product.get("data") or {}).get("id")
    if not product_id:
        fail("no Xcode Cloud product for this app")
    path = f"/ciProducts/{product_id}/buildRuns?limit=20"
    for runs in pages(client, path):
        for run in runs.get("data") or []:
            attrs = run.get("attributes") or {}
            sha = ((attrs.get("sourceCommit") or {}).get("commitSha") or "")
            if sha == commit and attrs.get("completionStatus") == "SUCCEEDED":
                number = attrs.get("number")
                return str(number) if number is not None else None
    return None


def processed_build(client: AscClient, app_id: str, marketing: str, build_number: str) -> str | None:
    payload = client.get(
        f"/builds?filter[app]={app_id}&filter[version]={build_number}"
        "&sort=-uploadedDate&limit=5"
        "&fields[builds]=version,processingState,uploadedDate"
    )
    for build in payload.get("data") or []:
        attrs = build.get("attributes") or {}
        if attrs.get("processingState") != "VALID":
            continue
        pre = client.get(
            f"/builds/{build['id']}/preReleaseVersion?fields[preReleaseVersions]=version"
        )
        version = ((pre.get("data") or {}).get("attributes") or {}).get("version")
        if version == marketing:
            return build["id"]
    return None


def ensure_beta_description(client: AscClient, app_id: str) -> None:
    locs = client.get(f"/apps/{app_id}/betaAppLocalizations")
    for loc in locs.get("data") or []:
        attrs = loc.get("attributes") or {}
        if attrs.get("locale") != "en-US":
            continue
        if (attrs.get("description") or "").strip():
            return
        client.patch(
            f"/betaAppLocalizations/{loc['id']}",
            {
                "data": {
                    "type": "betaAppLocalizations",
                    "id": loc["id"],
                    "attributes": {"description": BETA_DESCRIPTION},
                }
            },
        )
        print("filled the existing TestFlight beta description")
        return
    client.post(
        "/betaAppLocalizations",
        {
            "data": {
                "type": "betaAppLocalizations",
                "attributes": {
                    "locale": "en-US",
                    "description": BETA_DESCRIPTION,
                    "feedbackEmail": "apoorvdarshan@gmail.com",
                },
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
            }
        },
    )
    print("set TestFlight beta description")


def external_group_id(client: AscClient, app_id: str) -> str:
    for groups in pages(client, f"/apps/{app_id}/betaGroups?limit=20"):
        for group in groups.get("data") or []:
            attrs = group.get("attributes") or {}
            if attrs.get("name") == EXTERNAL_GROUP_NAME and not attrs.get("isInternalGroup"):
                return group["id"]
    fail(
        f"TestFlight group {EXTERNAL_GROUP_NAME!r} was not found. "
        "Create that external group in App Store Connect first."
    )


def assign_build(client: AscClient, group_id: str, build_id: str) -> None:
    existing = client.get(f"/betaGroups/{group_id}/builds?fields[builds]=version&limit=20")
    if any(build.get("id") == build_id for build in existing.get("data") or []):
        print(f"build {build_id} is already in {EXTERNAL_GROUP_NAME}")
        return
    client.request(
        "POST",
        f"/betaGroups/{group_id}/relationships/builds",
        body={"data": [{"type": "builds", "id": build_id}]},
    )
    print(f"added build {build_id} to {EXTERNAL_GROUP_NAME}")


def declare_exempt_encryption(client: AscClient, build_id: str) -> None:
    build = client.get(f"/builds/{build_id}?fields[builds]=usesNonExemptEncryption")
    uses = ((build.get("data") or {}).get("attributes") or {}).get("usesNonExemptEncryption")
    if uses is None:
        client.patch(
            f"/builds/{build_id}",
            {
                "data": {
                    "type": "builds",
                    "id": build_id,
                    "attributes": {"usesNonExemptEncryption": False},
                }
            },
        )


def external_state(client: AscClient, build_id: str) -> str | None:
    detail = client.get(
        f"/builds/{build_id}/buildBetaDetail"
        "?fields[buildBetaDetails]=externalBuildState"
    )
    return ((detail.get("data") or {}).get("attributes") or {}).get("externalBuildState")


def submit_if_needed(client: AscClient, app_id: str, build_id: str) -> None:
    declare_exempt_encryption(client, build_id)
    state = external_state(client, build_id)
    if state == "MISSING_EXPORT_COMPLIANCE":
        declare_exempt_encryption(client, build_id)
        state = external_state(client, build_id)
    print(f"external TestFlight state: {state}")
    if state in ALREADY_IN_REVIEW:
        return
    if state != "READY_FOR_BETA_SUBMISSION":
        fail(f"build is not ready for external TestFlight ({state})")
    ensure_beta_description(client, app_id)
    created = client.post(
        "/betaAppReviewSubmissions",
        {
            "data": {
                "type": "betaAppReviewSubmissions",
                "relationships": {"build": {"data": {"type": "builds", "id": build_id}}},
            }
        },
    )
    review = ((created.get("data") or {}).get("attributes") or {}).get("betaReviewState")
    print(f"submitted external TestFlight review ({review})")


def wait_for_build(new_client, app_id: str, marketing: str, commit: str) -> str:
    deadline = time.time() + 50 * 60
    while True:
        client = new_client()
        number = ci_build_number(client, app_id, commit)
        build_id = processed_build(client, app_id, marketing, number) if number else None
        if build_id:
            return build_id
        if time.time() > deadline:
            fail(f"timed out waiting for processed build {marketing} ({commit})")
        print(f"waiting for Xcode Cloud build {marketing} to finish processing")
        time.sleep(30)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="Marketing version, e.g. 7.1")
    parser.add_argument("--commit", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.dry_run:
        print(
            "dry-run: would add the processed "
            f"{args.version} build to TestFlight group {EXTERNAL_GROUP_NAME} "
            "and submit beta review if Apple still requires it"
        )
        return
    if not args.commit:
        fail("--commit is required")

    key_id, issuer_id, key_p8 = load_credentials()

    def new_client() -> AscClient:
        return AscClient(make_asc_token(key_id, issuer_id, key_p8))

    client = new_client()
    app_id = find_app(client, BUNDLE_ID)
    build_id = wait_for_build(new_client, app_id, args.version, args.commit)
    client = new_client()
    group_id = external_group_id(client, app_id)
    assign_build(client, group_id, build_id)
    submit_if_needed(client, app_id, build_id)


if __name__ == "__main__":
    main()
