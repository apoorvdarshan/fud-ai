#!/usr/bin/env python3
"""App Store Connect metadata, screenshots, and submit-for-review (gated in CI).

Submitting for review covers both the App Store version and any Ready-to-Submit
in-app purchases / auto-renewable subscriptions. Since the 2026 workflow change
these live on separate resources (`/v1/subscriptionSubmissions`,
`/v1/inAppPurchaseSubmissions`); they must go out with the version for a
first-time subscription submission or App Review rejects the build."""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
ASC_BASE = "https://api.appstoreconnect.apple.com/v1"
DEFAULT_BUNDLE_ID = "com.apoorvdarshan.calorietracker"
IPHONE_67_DISPLAY = "APP_IPHONE_67"
# App Store Connect per screenshot-set limit (6.7" display type).
APP_SCREENSHOT_SET_MAX = 10
EDITABLE_VERSION_STATES = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
}
# READY_FOR_REVIEW means the version already sits in a draft review submission
# (for example after a failed submit); it can still be submitted.
SUBMITTABLE_VERSION_STATES = EDITABLE_VERSION_STATES | {"READY_FOR_REVIEW"}


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def truthy(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def read_text(path: Path) -> str | None:
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8").strip()
    return text or None


class AscClient:
    def __init__(self, token: str) -> None:
        self._token = token

    def request(
        self,
        method: str,
        path: str,
        *,
        body: dict[str, Any] | None = None,
        raw_body: bytes | None = None,
        content_type: str = "application/json",
        accept: str = "application/json",
    ) -> dict[str, Any] | None:
        url = path if path.startswith("http") else f"{ASC_BASE}{path}"
        headers = {
            "Authorization": f"Bearer {self._token}",
            "Accept": accept,
        }
        data: bytes | None = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = content_type
        elif raw_body is not None:
            data = raw_body
            headers["Content-Type"] = content_type

        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = resp.read()
                if not payload:
                    return None
                if accept != "application/json":
                    return {"raw": payload}
                return json.loads(payload.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            fail(f"ASC {method} {path} → HTTP {exc.code}: {detail[:2000]}")

    def get(self, path: str) -> dict[str, Any]:
        result = self.request("GET", path)
        assert result is not None
        return result

    def patch(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        result = self.request("PATCH", path, body=body)
        assert result is not None
        return result

    def post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        result = self.request("POST", path, body=body)
        assert result is not None
        return result

    def upload_bytes(
        self, url: str, data: bytes, headers: dict[str, str], *, method: str = "PUT"
    ) -> None:
        req = urllib.request.Request(
            url, data=data, method=method.upper(), headers=headers
        )
        try:
            with urllib.request.urlopen(req, timeout=300):
                return
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            fail(f"ASC asset upload → HTTP {exc.code}: {detail[:1000]}")


def make_asc_token(key_id: str, issuer_id: str, private_key: str) -> str:
    import jwt

    now = int(time.time())
    payload = {
        "iss": issuer_id,
        "iat": now,
        "exp": now + 1200,
        "aud": "appstoreconnect-v1",
    }
    headers = {"kid": key_id, "typ": "JWT"}
    return jwt.encode(payload, private_key, algorithm="ES256", headers=headers)


def load_credentials() -> tuple[str, str, str]:
    key_id = os.environ.get("APP_STORE_CONNECT_API_KEY_ID", "").strip()
    issuer_id = os.environ.get("APP_STORE_CONNECT_ISSUER_ID", "").strip()
    key_p8 = os.environ.get("APP_STORE_CONNECT_API_KEY_P8", "").strip()
    if not key_id or not issuer_id or not key_p8:
        fail(
            "missing ASC credentials — set APP_STORE_CONNECT_API_KEY_ID, "
            "APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_API_KEY_P8"
        )
    key_p8 = key_p8.replace("\\n", "\n")
    return key_id, issuer_id, key_p8


def find_app(client: AscClient, bundle_id: str) -> str:
    query = urllib.parse.urlencode({"filter[bundleId]": bundle_id})
    payload = client.get(f"/apps?{query}")
    data = payload.get("data") or []
    if not data:
        fail(f"no App Store Connect app for bundle id {bundle_id}")
    return data[0]["id"]


def get_ios_app_store_version(
    client: AscClient, app_id: str, version_string: str
) -> dict[str, Any]:
    # Prefer the app relationship listing — some ASC API keys cannot
    # GET_COLLECTION on /appStoreVersions with filter[app]=… (403).
    matches: list[dict[str, Any]] = []
    path: str | None = (
        f"/apps/{app_id}/appStoreVersions?"
        + urllib.parse.urlencode({"limit": "50", "filter[platform]": "IOS"})
    )
    while path:
        payload = client.get(path)
        for row in payload.get("data") or []:
            attrs = row.get("attributes") or {}
            if attrs.get("versionString") == version_string:
                matches.append(row)
        next_url = (payload.get("links") or {}).get("next")
        if not next_url:
            break
        path = next_url
    if not matches:
        fail(
            f"no IOS App Store version {version_string!r} for app {app_id} "
            "(create the version in App Store Connect first)"
        )
    if len(matches) > 1:
        fail(
            f"ambiguous App Store versions for {version_string!r} on IOS — "
            f"got {len(matches)} records"
        )
    return matches[0]


def localization_for_locale(
    client: AscClient, collection_path: str, locale: str
) -> dict[str, Any]:
    query = urllib.parse.urlencode({"filter[locale]": locale, "limit": "1"})
    payload = client.get(f"{collection_path}?{query}")
    data = payload.get("data") or []
    if not data:
        fail(f"no localization {locale} at {collection_path}")
    return data[0]


def get_app_info_localization(client: AscClient, app_id: str, locale: str) -> dict[str, Any]:
    infos = client.get(f"/apps/{app_id}/appInfos")
    info_rows = infos.get("data") or []
    if not info_rows:
        fail("app has no appInfos")
    info_id = info_rows[0]["id"]
    return localization_for_locale(
        client, f"/appInfos/{info_id}/appInfoLocalizations", locale
    )


def upload_listing(
    client: AscClient,
    *,
    app_id: str,
    version_id: str,
    locale: str,
    metadata_dir: Path,
) -> None:
    del app_id  # app info (name/subtitle/privacy) locked while prior version is live
    loc_dir = metadata_dir / "ios" / locale

    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    version_attrs: dict[str, str] = {}
    for field, filename in (
        ("description", "description.txt"),
        ("keywords", "keywords.txt"),
        ("promotionalText", "promotional_text.txt"),
        ("whatsNew", "whats_new.txt"),
        ("marketingUrl", "marketing_url.txt"),
        ("supportUrl", "support_url.txt"),
    ):
        value = read_text(loc_dir / filename)
        if value:
            version_attrs[field] = value
    if version_attrs:
        client.patch(
            f"/appStoreVersionLocalizations/{version_loc['id']}",
            {
                "data": {
                    "type": "appStoreVersionLocalizations",
                    "id": version_loc["id"],
                    "attributes": version_attrs,
                }
            },
        )
        print(f"  updated version localization ({locale}): {', '.join(version_attrs)}")


def screenshot_sets_for_localization(
    client: AscClient, version_loc_id: str
) -> list[dict[str, Any]]:
    payload = client.get(
        f"/appStoreVersionLocalizations/{version_loc_id}/appScreenshotSets"
    )
    return payload.get("data") or []


def ensure_screenshot_set(
    client: AscClient, version_loc_id: str, display_type: str
) -> dict[str, Any]:
    for item in screenshot_sets_for_localization(client, version_loc_id):
        if (item.get("attributes") or {}).get("screenshotDisplayType") == display_type:
            return item
    created = client.post(
        "/appScreenshotSets",
        {
            "data": {
                "type": "appScreenshotSets",
                "attributes": {"screenshotDisplayType": display_type},
                "relationships": {
                    "appStoreVersionLocalization": {
                        "data": {
                            "type": "appStoreVersionLocalizations",
                            "id": version_loc_id,
                        }
                    }
                },
            }
        },
    )
    return created["data"]


def list_screenshot_ids(client: AscClient, screenshot_set_id: str) -> list[str]:
    payload = client.get(f"/appScreenshotSets/{screenshot_set_id}/appScreenshots")
    return [shot["id"] for shot in payload.get("data") or []]


def delete_screenshots(client: AscClient, screenshot_ids: list[str]) -> None:
    for shot_id in screenshot_ids:
        client.request("DELETE", f"/appScreenshots/{shot_id}")


def upload_screenshot_file(
    client: AscClient, screenshot_set_id: str, image_path: Path
) -> None:
    raw = image_path.read_bytes()
    reserved = client.post(
        "/appScreenshots",
        {
            "data": {
                "type": "appScreenshots",
                "attributes": {
                    "fileName": image_path.name,
                    "fileSize": len(raw),
                },
                "relationships": {
                    "appScreenshotSet": {
                        "data": {"type": "appScreenshotSets", "id": screenshot_set_id}
                    }
                },
            }
        },
    )
    shot = reserved["data"]
    shot_id = shot["id"]
    upload_ops = (shot.get("attributes") or {}).get("uploadOperations") or []
    if not upload_ops:
        fail(f"ASC returned no uploadOperations for {image_path.name}")
    for op in upload_ops:
        offset = int(op.get("offset", 0))
        length = int(op["length"])
        chunk = raw[offset : offset + length]
        headers = {h["name"]: h["value"] for h in op.get("requestHeaders") or []}
        method = (op.get("method") or "PUT").upper()
        client.upload_bytes(op["url"], chunk, headers, method=method)
    client.patch(
        f"/appScreenshots/{shot_id}",
        {
            "data": {
                "type": "appScreenshots",
                "id": shot_id,
                "attributes": {"uploaded": True},
            }
        },
    )
    print(f"  uploaded screenshot {image_path.name}")


def upload_screenshots(
    client: AscClient,
    *,
    version_id: str,
    locale: str,
    screenshots_dir: Path,
) -> None:
    version_loc = localization_for_locale(
        client, f"/appStoreVersions/{version_id}/appStoreVersionLocalizations", locale
    )
    version_loc_id = version_loc["id"]
    pngs = sorted(screenshots_dir.glob("*.png"))
    if not pngs:
        fail(f"no PNG screenshots in {screenshots_dir}")

    shot_set = ensure_screenshot_set(client, version_loc_id, IPHONE_67_DISPLAY)
    set_id = shot_set["id"]
    if len(pngs) > APP_SCREENSHOT_SET_MAX:
        fail(
            f"too many PNGs ({len(pngs)}) for ASC 6.7\" set "
            f"(max {APP_SCREENSHOT_SET_MAX})"
        )

    # Old ASC ids still on the set; pop front when deleting for capacity or final cleanup.
    pending_old_ids = list_screenshot_ids(client, set_id)

    # Upload replacements one at a time. ASC rejects new reservations when the set is
    # at capacity, so delete one pending old screenshot before each upload when full.
    # If a mid-run upload fails, the live set may be mixed (some olds removed, some news
    # uploaded); fix the error and re-run, or restore screenshots in App Store Connect.
    for path in pngs:
        current_ids = list_screenshot_ids(client, set_id)
        if len(current_ids) >= APP_SCREENSHOT_SET_MAX:
            if not pending_old_ids:
                fail(
                    f"ASC screenshot set at capacity ({APP_SCREENSHOT_SET_MAX}) "
                    f"with no tracked old screenshots left to replace"
                )
            delete_screenshots(client, [pending_old_ids.pop(0)])
        upload_screenshot_file(client, set_id, path)

    if pending_old_ids:
        delete_screenshots(client, pending_old_ids)
        print(f"  removed {len(pending_old_ids)} previous ASC screenshot(s)")


def iter_collection(client: AscClient, path: str) -> list[dict[str, Any]]:
    """Return every record in an ASC collection, following links.next."""
    rows: list[dict[str, Any]] = []
    while path:
        payload = client.get(path)
        rows.extend(payload.get("data") or [])
        next_url = (payload.get("links") or {}).get("next")
        if not next_url:
            break
        path = next_url
    return rows


def intended_product_ids() -> set[str]:
    """Product IDs the release is allowed to submit, from the canonical catalog."""
    catalog = json.loads(
        (ROOT / "store" / "catalog" / "products.json").read_text(encoding="utf-8")
    )
    ids: set[str] = set()
    for group in ("subscriptions", "consumables", "tips"):
        for item in catalog.get(group) or []:
            pid = item.get("id")
            if isinstance(pid, str) and pid:
                ids.add(pid)
    return ids


class ProductSubmitError(Exception):
    """A Ready-to-Submit product could not be added to App Review."""


def submit_in_app_purchases(
    client: AscClient,
    app_id: str,
    *,
    intended_ids: set[str] | None = None,
) -> int:
    """Attach Ready-to-Submit subscriptions / IAPs to App Review.

    Since the 2026 App Store Connect submission workflow, in-app purchases and
    auto-renewable subscriptions are submitted through dedicated resources
    (`/v1/subscriptionSubmissions`, `/v1/inAppPurchaseSubmissions`) rather than
    the Review Submission Item list, which only accepts the App Store version,
    version experiments, custom product pages, and app events. A first-time
    subscription submission must therefore be created in the same release as the
    version, or App Review rejects the build.

    Only products listed in `store/catalog/products.json` are considered, so an
    unrelated Ready-to-Submit plan, credit pack, or tip is not dragged into this
    release. Returns the number of products submitted for review.
    """
    if intended_ids is None:
        intended_ids = intended_product_ids()

    submitted = 0
    failures: list[str] = []
    if not intended_ids:
        print("  catalog has no products to submit")
        return 0

    for group in iter_collection(
        client, f"/apps/{app_id}/subscriptionGroups?limit=200"
    ):
        for sub in iter_collection(
            client, f"/subscriptionGroups/{group['id']}/subscriptions?limit=200"
        ):
            attrs = sub.get("attributes") or {}
            pid = attrs.get("productId") or ""
            if pid not in intended_ids:
                continue
            name = attrs.get("name") or sub["id"]
            if attrs.get("state") != "READY_TO_SUBMIT":
                continue
            try:
                client.post(
                    "/subscriptionSubmissions",
                    {
                        "data": {
                            "type": "subscriptionSubmissions",
                            "relationships": {
                                "subscription": {
                                    "data": {"type": "subscriptions", "id": sub["id"]}
                                }
                            },
                        }
                    },
                )
            except SystemExit:
                failures.append(f"subscription {name} ({pid})")
                continue
            submitted += 1
            print(f"  subscription {name}: submitted for review")

    for iap in iter_collection(client, f"/apps/{app_id}/inAppPurchasesV2?limit=200"):
        attrs = iap.get("attributes") or {}
        pid = attrs.get("productId") or ""
        if pid not in intended_ids:
            continue
        if attrs.get("state") != "READY_TO_SUBMIT":
            continue
        name = attrs.get("name") or iap["id"]
        try:
            client.post(
                "/inAppPurchaseSubmissions",
                {
                    "data": {
                        "type": "inAppPurchaseSubmissions",
                        "relationships": {
                            "inAppPurchaseV2": {
                                "data": {"type": "inAppPurchases", "id": iap["id"]}
                            }
                        },
                    }
                },
            )
        except SystemExit:
            failures.append(f"in-app purchase {name} ({pid})")
            continue
        submitted += 1
        print(f"  in-app purchase {name}: submitted for review")

    print(f"  in-app purchases / subscriptions submitted: {submitted}")
    if failures:
        raise ProductSubmitError(
            "these required Ready-to-Submit products were not added to "
            "App Review: "
            + "; ".join(failures)
            + " (a removed subscription version stays DEVELOPER_REJECTED in "
            "App Store Connect and must be cleared before resubmitting)"
        )
    return submitted


def submit_for_review(
    client: AscClient, app_id: str, version_id: str, version_state: str = ""
) -> None:
    # Reuse a draft submission when one already exists (an earlier attempt can
    # leave a READY_FOR_REVIEW submission that was never submitted).
    submission_id: str | None = None
    existing = client.get(
        f"/apps/{app_id}/reviewSubmissions?"
        + urllib.parse.urlencode({"filter[state]": "READY_FOR_REVIEW", "limit": "5"})
    )
    for row in existing.get("data") or []:
        submission_id = row["id"]
        break
    if submission_id is None:
        submission = client.post(
            "/reviewSubmissions",
            {
                "data": {
                    "type": "reviewSubmissions",
                    "relationships": {
                        "app": {"data": {"type": "apps", "id": app_id}}
                    },
                }
            },
        )
        submission_id = submission["data"]["id"]

    items = client.get(f"/reviewSubmissions/{submission_id}/items?limit=50")
    item_version_ids = set()
    for item in items.get("data") or []:
        related = ((item.get("relationships") or {}).get("appStoreVersion") or {}).get(
            "data"
        ) or {}
        if related.get("id"):
            item_version_ids.add(related["id"])
    # READY_FOR_REVIEW means the version is already held by this draft submission.
    already_added = version_state == "READY_FOR_REVIEW" or version_id in item_version_ids
    if not already_added:
        client.post(
            "/reviewSubmissionItems",
            {
                "data": {
                    "type": "reviewSubmissionItems",
                    "relationships": {
                        "reviewSubmission": {
                            "data": {"type": "reviewSubmissions", "id": submission_id}
                        },
                        "appStoreVersion": {
                            "data": {"type": "appStoreVersions", "id": version_id}
                        },
                    },
                }
            },
        )

    # Submitting is a PATCH with submitted=true; there is no /submit action.
    client.request(
        "PATCH",
        f"/reviewSubmissions/{submission_id}",
        body={
            "data": {
                "type": "reviewSubmissions",
                "id": submission_id,
                "attributes": {"submitted": True},
            }
        },
    )
    print(
        f"  submitted App Store version {version_id} for review "
        f"(submission {submission_id})"
    )


class _RecordingClient:
    """Offline AscClient stand-in for the dry-run self-check."""

    def __init__(self, collections: dict[str, list[dict[str, Any]]]) -> None:
        self._collections = collections
        self.posts: list[tuple[str, dict[str, Any]]] = []

    def get(self, path: str) -> dict[str, Any]:
        return {"data": self._collections.get(path, [])}

    def post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        self.posts.append((path, body))
        return {"data": {"id": "fake"}}

    def patch(self, path: str, body: dict[str, Any]) -> dict[str, Any]:  # pragma: no cover
        raise AssertionError(f"unexpected PATCH {path}")

    def request(self, *args: Any, **kwargs: Any) -> Any:  # pragma: no cover
        raise AssertionError("unexpected request")


def dry_run_self_check() -> None:
    """Exercise the product-submission path offline so dry-run covers it."""
    app_id = "APP"
    groups_path = f"/apps/{app_id}/subscriptionGroups?limit=200"
    subs_path = "/subscriptionGroups/G1/subscriptions?limit=200"
    iaps_path = f"/apps/{app_id}/inAppPurchasesV2?limit=200"
    ids = {
        "plus.monthly": "com.apoorvdarshan.calorietracker.plus.monthly",
        "plus.yearly": "com.apoorvdarshan.calorietracker.plus.yearly",
        "pro.monthly": "com.apoorvdarshan.calorietracker.pro.monthly",
        "pro.yearly": "com.apoorvdarshan.calorietracker.pro.yearly",
    }
    fake = _RecordingClient(
        {
            groups_path: [{"id": "G1"}],
            subs_path: [
                {
                    "id": "S1",
                    "attributes": {
                        "name": name,
                        "productId": pid,
                        "state": "READY_TO_SUBMIT",
                    },
                }
                for name, pid in ids.items()
            ]
            + [
                {
                    "id": "S0",
                    "attributes": {
                        "name": "Legacy",
                        "productId": "com.apoorvdarshan.calorietracker.legacy",
                        "state": "READY_TO_SUBMIT",
                    },
                }
            ],
            iaps_path: [],
        }
    )
    submitted = submit_in_app_purchases(fake, app_id, intended_ids=set(ids.values()))
    assert submitted == 4, f"expected 4 subscriptions, got {submitted}"
    submitted_ids = {
        body["data"]["relationships"]["subscription"]["data"]["id"] for _, body in fake.posts
    }
    assert submitted_ids == {"S1"}, f"unexpected submitted products: {submitted_ids}"
    assert all(
        body["data"]["type"] == "subscriptionSubmissions" for _, body in fake.posts
    ), "wrong request type"
    print("  dry-run self-check: product submission selection OK")


def validate_local_inputs(
    *,
    upload_listing: bool,
    upload_screenshots: bool,
    metadata_dir: Path,
    screenshots_dir: Path,
    locale: str,
) -> None:
    if upload_listing:
        loc_dir = metadata_dir / "ios" / locale
        if not loc_dir.is_dir():
            fail(f"metadata dir missing: {loc_dir}")
    if upload_screenshots:
        if not screenshots_dir.is_dir():
            fail(f"screenshots dir missing: {screenshots_dir}")
        if not any(screenshots_dir.glob("*.png")):
            fail(f"no PNG files in {screenshots_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--metadata-dir",
        type=Path,
        default=ROOT / "store" / "metadata",
        help="Prepared listing + what's new tree",
    )
    parser.add_argument(
        "--screenshots-dir",
        type=Path,
        default=ROOT / "store" / "metadata" / "screenshots" / "ios" / "6.7",
    )
    parser.add_argument("--locale", default="en-US")
    parser.add_argument("--bundle-id", default=DEFAULT_BUNDLE_ID)
    parser.add_argument(
        "--version",
        help="Marketing version from release tag (e.g. 1.2.3 when tag is v1.2.3)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate flags and local files only — no ASC HTTP calls",
    )
    args = parser.parse_args()

    do_listing = truthy("UPLOAD_LISTING")
    do_screenshots = truthy("UPLOAD_SCREENSHOTS")
    do_submit = truthy("SUBMIT_IOS_REVIEW")

    if not (do_listing or do_screenshots or do_submit):
        print(
            "ASC release skipped — UPLOAD_LISTING / UPLOAD_SCREENSHOTS / "
            "SUBMIT_IOS_REVIEW are off"
        )
        return

    validate_local_inputs(
        upload_listing=do_listing,
        upload_screenshots=do_screenshots,
        metadata_dir=args.metadata_dir,
        screenshots_dir=args.screenshots_dir,
        locale=args.locale,
    )

    if args.dry_run:
        print("ASC dry-run — local inputs OK; no API calls")
        print(
            f"  bundle={args.bundle_id} listing={do_listing} "
            f"screenshots={do_screenshots} submit={do_submit}"
        )
        if do_submit:
            dry_run_self_check()
        return

    key_id, issuer_id, key_p8 = load_credentials()
    token = make_asc_token(key_id, issuer_id, key_p8)
    client = AscClient(token)

    if not args.version:
        fail("--version is required for live ASC calls (pass marketing version from tag)")

    app_id = find_app(client, args.bundle_id)
    version = get_ios_app_store_version(client, app_id, args.version)
    version_id = version["id"]
    state = (version.get("attributes") or {}).get("appStoreState") or ""
    print(
        f"ASC app={app_id} version={args.version} id={version_id} state={state}"
    )

    if state == "WAITING_FOR_REVIEW":
        if do_submit and not (do_listing or do_screenshots):
            # The version is already held by a live submission; still make sure
            # no Ready-to-Submit product was left behind by an earlier partial run.
            print(
                "ASC submit skipped — version already WAITING_FOR_REVIEW (idempotent)"
            )
            try:
                submit_in_app_purchases(client, app_id)
            except ProductSubmitError as exc:
                fail(str(exc))
            print("ASC release step finished")
            return
        if do_listing or do_screenshots:
            fail(
                f"cannot update listing/screenshots while version {args.version!r} "
                "is WAITING_FOR_REVIEW"
            )
        do_submit = False

    if do_listing or do_screenshots:
        if state not in EDITABLE_VERSION_STATES:
            fail(
                f"version {args.version!r} is not editable for metadata (state={state}); "
                f"expected one of: {', '.join(sorted(EDITABLE_VERSION_STATES))}"
            )

    if do_submit and state not in SUBMITTABLE_VERSION_STATES:
        fail(
            f"version {args.version!r} cannot be submitted for review (state={state})"
        )

    if do_listing:
        print("uploading ASC listing metadata…")
        upload_listing(
            client,
            app_id=app_id,
            version_id=version_id,
            locale=args.locale,
            metadata_dir=args.metadata_dir,
        )

    if do_screenshots:
        print('uploading ASC 6.7" screenshots…')
        upload_screenshots(
            client,
            version_id=version_id,
            locale=args.locale,
            screenshots_dir=args.screenshots_dir,
        )

    if do_submit:
        print("submitting in-app purchases / subscriptions for review…")
        try:
            products_submitted = submit_in_app_purchases(client, app_id)
        except ProductSubmitError as exc:
            # A first-time subscription must ship with the version, so a failed
            # required product blocks the whole submission rather than letting
            # the version enter review without it.
            fail(f"{exc} Not submitting the app version.")
        print("submitting for App Store review…")
        submit_for_review(client, app_id, version_id, state)
        print(f"  {products_submitted} product(s) went out with the version")

    print("ASC release step finished")


if __name__ == "__main__":
    main()
