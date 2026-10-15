#!/usr/bin/env python3
"""iOS release switches, build numbering and App Store Connect build readiness.

Subcommands (all print to stdout; `gates`/`build-number`/`wait-build` also write
key=value lines to $GITHUB_OUTPUT when it is set):

  gates              Validate the IOS_* repository variables and resolve what this
                     run may do. Missing values mean off; invalid values fail.
  build-number       Next CFBundleVersion: max(project value, highest ASC build + 1).
  version-preflight  Read-only, before anything is built: the App Store version
                     exists and is in a state the store step can edit/submit.
  wait-build         Poll App Store Connect until the build uploaded by THIS run is
                     processed (VALID, unexpired, matching app/version/build number,
                     uploaded during this run). Fails on timeout, processing
                     failure, ambiguity or mismatch and never falls back to
                     another build.

See store/README.md for what each switch controls.
"""
from __future__ import annotations

import argparse
import datetime as dt
import http.client
import os
import re
import sys
import time
import urllib.error
import urllib.parse
from pathlib import Path
from typing import Any, Callable, Mapping

ROOT = Path(__file__).resolve().parents[2]
PBXPROJ = ROOT / "ios" / "calorietracker.xcodeproj" / "project.pbxproj"
BUNDLE_ID = "com.apoorvdarshan.calorietracker"
TAG_PREFIX = "ios-v"

TRUE_VALUES = {"1", "true", "yes", "on"}
FALSE_VALUES = {"", "0", "false", "no", "off"}

MASTER = "IOS_RELEASE_AUTOMATION"
OPERATION_SWITCHES = (
    ("IOS_CREATE_VERSION", "create_version"),
    ("IOS_UPLOAD_BUILD", "upload_build"),
    ("IOS_UPLOAD_LISTING", "listing"),
    ("IOS_UPLOAD_WHATS_NEW", "whats_new"),
    ("IOS_UPLOAD_SCREENSHOTS", "screenshots"),
    ("IOS_SUBMIT_REVIEW", "submit"),
    ("IOS_PUBLIC_RELEASE", "public_release"),
)
POLL_SECONDS = ("IOS_BUILD_POLL_SECONDS", 30, 10, 600)
WAIT_MINUTES = ("IOS_BUILD_WAIT_MINUTES", 120, 10, 300)

# Builds this old (relative to this run's upload) cannot be the one we uploaded.
UPLOAD_CLOCK_SKEW_SECONDS = 600
TRANSIENT_HTTP_STATUSES = {408, 429, 500, 502, 503, 504}


class ReleaseError(Exception):
    """A release precondition failed; the run must stop without publishing."""


def parse_switch(name: str, raw: str | None) -> bool:
    value = (raw or "").strip().lower()
    if value in TRUE_VALUES:
        return True
    if value in FALSE_VALUES:
        return False
    raise ReleaseError(
        f"{name}={raw!r} is not a valid switch value; use true/false (or leave it unset)"
    )


def parse_bounded_int(spec: tuple[str, int, int, int], raw: str | None) -> int:
    name, default, low, high = spec
    value = (raw or "").strip()
    if not value:
        return default
    if not re.fullmatch(r"[0-9]+", value):
        raise ReleaseError(f"{name}={raw!r} must be a whole number between {low} and {high}")
    number = int(value)
    if not low <= number <= high:
        raise ReleaseError(f"{name}={number} must be between {low} and {high}")
    return number


def project_values(setting: str, pbxproj: Path = PBXPROJ) -> set[str]:
    text = pbxproj.read_text(encoding="utf-8")
    return set(re.findall(rf"\b{setting} = ([^;]+);", text))


def single_project_value(setting: str, pbxproj: Path = PBXPROJ) -> str:
    values = project_values(setting, pbxproj)
    if len(values) != 1:
        raise ReleaseError(
            f"{setting} must be identical across all targets; found {sorted(values)}"
        )
    return values.pop().strip().strip('"')


VERSION_PATTERN = re.compile(r"[0-9]+(\.[0-9]+){0,2}")


def resolve_gates(
    env: Mapping[str, str],
    *,
    event: str,
    ref_name: str,
    marketing_version: str,
    validation_version: str = "",
) -> dict[str, Any]:
    """Return what this run is allowed to do. Raises ReleaseError on bad config."""
    if event not in {"push", "workflow_dispatch"}:
        raise ReleaseError(f"unsupported event {event!r}")
    validation_version = validation_version.strip()
    if validation_version:
        if event != "workflow_dispatch":
            raise ReleaseError("a version override only applies to manual validation runs")
        if not VERSION_PATTERN.fullmatch(validation_version):
            raise ReleaseError(f"validation version {validation_version!r} must look like 7.2 or 7.2.1")

    # Parse every switch first so a typo fails even when the master is off.
    master = parse_switch(MASTER, env.get(MASTER))
    requested = {key: parse_switch(name, env.get(name)) for name, key in OPERATION_SWITCHES}
    poll_seconds = parse_bounded_int(POLL_SECONDS, env.get(POLL_SECONDS[0]))
    wait_minutes = parse_bounded_int(WAIT_MINUTES, env.get(WAIT_MINUTES[0]))
    notes: list[str] = []

    if event == "workflow_dispatch":
        # Manual runs only prove the build signs, exports and validates.
        resolved = {key: False for _, key in OPERATION_SWITCHES}
        mode = "validate"
        notes.append("validation run: build is archived, signed and validated only; nothing is uploaded")
        if validation_version:
            notes.append(
                f"validating as version {validation_version} (project is {marketing_version}); "
                "the project files are not changed"
            )
            marketing_version = validation_version
    else:
        if not ref_name.startswith(TAG_PREFIX):
            raise ReleaseError(f"release runs need an {TAG_PREFIX}* tag, got {ref_name!r}")
        tag_version = ref_name[len(TAG_PREFIX):]
        if tag_version != marketing_version:
            raise ReleaseError(
                f"tag {ref_name} does not match MARKETING_VERSION {marketing_version}; "
                "fix the tag or bump the version before releasing"
            )
        mode = "release"
        if master:
            if requested["submit"] and not requested["upload_build"]:
                raise ReleaseError(
                    "IOS_SUBMIT_REVIEW needs IOS_UPLOAD_BUILD: review submission only uses "
                    "the build uploaded by this run, never an older build"
                )
            if requested["public_release"] and not requested["submit"]:
                raise ReleaseError("IOS_PUBLIC_RELEASE only applies with IOS_SUBMIT_REVIEW on")
            resolved = dict(requested)
        else:
            ignored = [name for name, key in OPERATION_SWITCHES if requested[key]]
            if ignored:
                notes.append(f"{MASTER} is off, so these are ignored: {', '.join(ignored)}")
            resolved = {key: False for _, key in OPERATION_SWITCHES}

    resolved.update(
        {
            "mode": mode,
            "master": master and mode == "release",
            "build": mode == "validate" or resolved["upload_build"],
            "asc": any(resolved[k] for k in ("listing", "whats_new", "screenshots", "submit")),
            "poll_seconds": poll_seconds,
            "wait_minutes": wait_minutes,
            "marketing_version": marketing_version,
        }
    )
    resolved["notes"] = notes
    return resolved


def next_build_number(project_build: int, asc_build_versions: list[str]) -> int:
    numeric = [int(v) for v in asc_build_versions if re.fullmatch(r"[0-9]+", v or "")]
    highest = max(numeric, default=0)
    return max(project_build, highest + 1)


def parse_asc_date(value: str) -> float:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


class BuildState:
    WAITING = "waiting"
    READY = "ready"


def evaluate_builds(
    payload: dict[str, Any],
    *,
    app_id: str,
    marketing_version: str,
    build_number: str,
    uploaded_after: float,
) -> tuple[str, str, dict[str, Any] | None]:
    """Classify one poll. Returns (state, message, build). Raises on hard failures."""
    included = {
        (row.get("type"), row.get("id")): row for row in payload.get("included") or []
    }
    matches = []
    for row in payload.get("data") or []:
        attrs = row.get("attributes") or {}
        rels = row.get("relationships") or {}
        pre_ref = ((rels.get("preReleaseVersion") or {}).get("data")) or {}
        app_ref = ((rels.get("app") or {}).get("data")) or {}
        pre = included.get(("preReleaseVersions", pre_ref.get("id"))) or {}
        pre_attrs = pre.get("attributes") or {}
        if attrs.get("version") != build_number:
            continue
        if pre_attrs.get("version") != marketing_version or pre_attrs.get("platform") != "IOS":
            continue
        if app_ref.get("id") != app_id:
            raise ReleaseError(f"build {row.get('id')} belongs to another app ({app_ref.get('id')})")
        matches.append(row)

    if not matches:
        return BuildState.WAITING, "build not visible in App Store Connect yet", None
    if len(matches) > 1:
        ids = ", ".join(str(m.get("id")) for m in matches)
        raise ReleaseError(f"ambiguous: {len(matches)} builds match {marketing_version} ({build_number}): {ids}")

    build = matches[0]
    attrs = build.get("attributes") or {}
    uploaded = attrs.get("uploadedDate")
    # Fail closed: without an upload time the build cannot be proven to be this
    # run's upload, so it is never treated as ready (the wait times out instead).
    if not uploaded:
        return BuildState.WAITING, "App Store Connect has not reported the upload time yet", None
    try:
        uploaded_at = parse_asc_date(uploaded)
    except (AttributeError, TypeError, ValueError):
        raise ReleaseError(
            f"build {build_number} has an unreadable uploadedDate {uploaded!r}; "
            "refusing to use a build that cannot be matched to this run's upload"
        ) from None
    if uploaded_at < uploaded_after - UPLOAD_CLOCK_SKEW_SECONDS:
        raise ReleaseError(
            f"build {build_number} was uploaded at {uploaded}, before this run's upload; "
            "refusing to use an older build"
        )
    state = attrs.get("processingState")
    if state in {"FAILED", "INVALID"}:
        raise ReleaseError(f"App Store Connect processing ended in {state} for build {build_number}")
    if state != "VALID":
        return BuildState.WAITING, f"processing state {state or 'unknown'}", None
    if attrs.get("expired"):
        raise ReleaseError(f"build {build_number} is expired")
    return BuildState.READY, f"build {build_number} is VALID", build


def builds_query(app_id: str, build_number: str) -> str:
    return "/builds?" + urllib.parse.urlencode(
        {
            "filter[app]": app_id,
            "filter[version]": build_number,
            "include": "preReleaseVersion,app",
            "fields[builds]": "version,processingState,expired,uploadedDate,preReleaseVersion,app",
            "fields[preReleaseVersions]": "version,platform",
            "limit": "20",
        }
    )


def wait_for_build(
    client: Any,
    *,
    app_id: str,
    marketing_version: str,
    build_number: str,
    uploaded_after: float,
    deadline: float,
    poll_seconds: int,
    now: Callable[[], float] | None = None,
    sleep: Callable[[float], None] | None = None,
    is_transient: Callable[[BaseException], bool] | None = None,
) -> dict[str, Any]:
    """Poll until this run's build is processed; raise ReleaseError otherwise."""
    now = now or time.time
    sleep = sleep or time.sleep
    path = builds_query(app_id, build_number)
    while True:
        # The budget is total (upload + processing): a build first seen as ready
        # after the deadline is still a timeout, never a late success.
        if now() > deadline:
            raise ReleaseError(
                f"timed out waiting for {marketing_version} ({build_number}) to finish processing"
            )
        try:
            payload = client.get(path)
        except BaseException as exc:  # noqa: BLE001 - classified below
            if is_transient is None or not is_transient(exc):
                raise
            state, message, build = BuildState.WAITING, f"transient App Store Connect error: {exc}", None
        else:
            state, message, build = evaluate_builds(
                payload,
                app_id=app_id,
                marketing_version=marketing_version,
                build_number=build_number,
                uploaded_after=uploaded_after,
            )
        if state == BuildState.READY and build is not None:
            print(f"  {message} (id {build['id']})")
            return build
        remaining = deadline - now()
        print(f"  waiting: {message}; {max(0, int(remaining))}s left")
        if remaining <= 0:
            raise ReleaseError(
                f"timed out waiting for {marketing_version} ({build_number}) to finish processing"
            )
        sleep(min(poll_seconds, max(1.0, remaining)))


def is_transient_error(exc: BaseException) -> bool:
    """Network hiccups and App Store Connect overload are retried; anything else stops."""
    if isinstance(exc, urllib.error.HTTPError):
        return exc.code in TRANSIENT_HTTP_STATUSES
    status = getattr(exc, "status", None)
    if isinstance(exc, SystemExit):  # asc_release.AscHttpError carries the HTTP status
        return isinstance(status, int) and status in TRANSIENT_HTTP_STATUSES
    return isinstance(
        exc, (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException)
    )


def call_with_retries(
    fn: Callable[[], Any],
    *,
    deadline: float,
    poll_seconds: int,
    now: Callable[[], float] | None = None,
    sleep: Callable[[float], None] | None = None,
    is_transient: Callable[[BaseException], bool] = is_transient_error,
) -> Any:
    now = now or time.time
    sleep = sleep or time.sleep
    while True:
        try:
            return fn()
        except BaseException as exc:  # noqa: BLE001 - classified below
            remaining = deadline - now()
            if not is_transient(exc) or remaining <= 0:
                raise
            print(f"  transient App Store Connect error ({exc!r}); retrying")
            sleep(min(poll_seconds, max(1.0, remaining)))


def write_outputs(values: Mapping[str, Any]) -> None:
    lines = []
    for key, value in values.items():
        if isinstance(value, bool):
            value = "true" if value else "false"
        lines.append(f"{key}={value}")
    target = os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
    for line in lines:
        print(f"  {line}")


def asc_client() -> Any:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import asc_release  # noqa: PLC0415 - keeps `gates` free of PyJWT

    return asc_release, asc_release.client_from_environment()


def cmd_gates(_: argparse.Namespace) -> None:
    resolved = resolve_gates(
        os.environ,
        event=os.environ.get("GITHUB_EVENT_NAME", ""),
        ref_name=os.environ.get("GITHUB_REF_NAME", ""),
        marketing_version=single_project_value("MARKETING_VERSION"),
        validation_version=os.environ.get("VALIDATION_MARKETING_VERSION", ""),
    )
    notes = resolved.pop("notes")
    print("iOS release switches:")
    for note in notes:
        print(f"  note: {note}")
    write_outputs(resolved)


def cmd_build_number(_: argparse.Namespace) -> None:
    project_build = single_project_value("CURRENT_PROJECT_VERSION")
    if not re.fullmatch(r"[0-9]+", project_build):
        raise ReleaseError(f"CURRENT_PROJECT_VERSION must be a whole number, got {project_build!r}")
    asc_release, client = asc_client()
    app_id = asc_release.find_app(client, BUNDLE_ID)
    rows = asc_release.iter_collection(
        client,
        "/builds?" + urllib.parse.urlencode(
            {"filter[app]": app_id, "fields[builds]": "version", "limit": "200"}
        ),
    )
    versions = [(row.get("attributes") or {}).get("version", "") for row in rows]
    number = next_build_number(int(project_build), versions)
    print(f"project build {project_build}; {len(versions)} uploaded builds; next build {number}")
    write_outputs({"build_number": number})


def version_preflight(
    client: Any,
    asc: Any,
    *,
    version: str,
    metadata: bool,
    submit: bool,
    locale: str = "en-US",
) -> str:
    """Read-only: fail before anything is built when the App Store version cannot
    take this release. Returns the version's state.

    The X.Y version must already exist in App Store Connect (Prepare for
    Submission): with IOS_CREATE_VERSION on, `asc_release.py --create-version`
    creates a missing one in the step before this; otherwise create it in App
    Store Connect before tagging. Text can still be edited and submitted in
    READY_FOR_REVIEW (a draft submission left by an earlier attempt); any other
    state (waiting for or in review, released) cannot take a new build or
    listing change.
    """
    app_id = asc.find_app(client, BUNDLE_ID)
    record = asc.get_ios_app_store_version(client, app_id, version)
    state = (record.get("attributes") or {}).get("appStoreState") or ""
    allowed = asc.SUBMITTABLE_VERSION_STATES
    if state not in allowed:
        raise ReleaseError(
            f"App Store version {version} is {state or 'in an unknown state'}; a release can only "
            f"edit or submit a version in {', '.join(sorted(allowed))}. Create the next version in "
            "App Store Connect (Prepare for Submission), or turn the listing/What's New/screenshot/"
            "review switches off for this tag"
        )
    if metadata:
        # Fails with a clear message when the locale the listing step edits is missing.
        asc.localization_for_locale(
            client, f"/appStoreVersions/{record['id']}/appStoreVersionLocalizations", locale
        )
    return state


def cmd_version_preflight(args: argparse.Namespace) -> None:
    listing = parse_switch("UPLOAD_LISTING", os.environ.get("UPLOAD_LISTING"))
    whats_new = parse_switch("UPLOAD_WHATS_NEW", os.environ.get("UPLOAD_WHATS_NEW"))
    screenshots = parse_switch("UPLOAD_SCREENSHOTS", os.environ.get("UPLOAD_SCREENSHOTS"))
    submit = parse_switch("SUBMIT_IOS_REVIEW", os.environ.get("SUBMIT_IOS_REVIEW"))
    if not (listing or whats_new or screenshots or submit):
        print("no App Store Connect switch is on; the App Store version is not needed")
        return
    asc_release, client = asc_client()
    state = version_preflight(
        client,
        asc_release,
        version=args.version,
        metadata=listing or whats_new or screenshots,
        submit=submit,
    )
    print(f"App Store version {args.version} exists and is {state}: it can take this release")


def cmd_wait_build(args: argparse.Namespace) -> None:
    asc_release, client = asc_client()
    # The budget covers upload + processing, so it is counted from upload start.
    deadline = args.started_at + args.wait_minutes * 60
    print(
        f"waiting for {args.version} ({args.build}) — poll every {args.poll_seconds}s, "
        f"deadline {dt.datetime.fromtimestamp(deadline, dt.timezone.utc).isoformat()}"
    )
    app_id = call_with_retries(
        lambda: asc_release.find_app(client, BUNDLE_ID),
        deadline=deadline,
        poll_seconds=args.poll_seconds,
    )
    build = wait_for_build(
        client,
        app_id=app_id,
        marketing_version=args.version,
        build_number=str(args.build),
        uploaded_after=args.started_at,
        deadline=deadline,
        poll_seconds=args.poll_seconds,
        is_transient=is_transient_error,
    )
    write_outputs({"build_id": build["id"]})


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("gates").set_defaults(func=cmd_gates)
    sub.add_parser("build-number").set_defaults(func=cmd_build_number)
    preflight = sub.add_parser("version-preflight")
    preflight.add_argument("--version", required=True, help="marketing version, e.g. 7.2")
    preflight.set_defaults(func=cmd_version_preflight)
    wait = sub.add_parser("wait-build")
    wait.add_argument("--version", required=True)
    wait.add_argument("--build", required=True, type=int)
    wait.add_argument("--started-at", required=True, type=float, help="epoch seconds when this run began uploading")
    wait.add_argument("--wait-minutes", required=True, type=int)
    wait.add_argument("--poll-seconds", required=True, type=int)
    wait.set_defaults(func=cmd_wait_build)
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except ReleaseError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
