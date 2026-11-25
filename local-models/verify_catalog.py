#!/usr/bin/env python3
"""Validate the pinned Gemma LiteRT-LM artifact without downloading it."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class ValidationError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def read_catalog(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValidationError(f"Cannot read {path}: {error}") from error
    require(isinstance(value, dict), "catalog must be a JSON object")
    return value


def validate_catalog(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    require(catalog.get("schemaVersion") == 1, "schemaVersion must be 1")
    models = catalog.get("models")
    require(isinstance(models, list) and len(models) == 2, "catalog must contain the two Gemma variants")
    for model, variant, ram_gib, size_bytes, sha256 in zip(
        models,
        ("E2B", "E4B"),
        (8, 12),
        (2588147712, 3659530240),
        (
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        ),
    ):
        validate_model(model, variant, ram_gib, size_bytes, sha256)
    policy = catalog.get("downloadPolicy")
    require(isinstance(policy, dict), "downloadPolicy is required")
    require(policy.get("checksumAlgorithm") == "sha256", "checksum algorithm must be sha256")
    require(policy.get("minimumHeadroomBytes") == 256 * 1024**2, "minimum headroom must be 256 MiB")
    require(policy.get("headroomPercent") == 10, "headroom must be 10 percent")
    return models


def validate_model(model: dict[str, Any], variant: str, ram_gib: int, size_bytes: int, expected_sha256: str) -> None:
    require(model.get("id") == f"gemma-4-{variant.lower()}-it-litertlm", "unexpected model id")
    require(model.get("platforms") == ["android", "ios"], "Gemma download must be shared by Android and iOS")
    require(model.get("runtime") == {"family": "LiteRT-LM", "version": "0.16.0"}, "unexpected runtime")
    require(model.get("format") == "litertlm", "unexpected model format")

    memory = model.get("memoryPolicy")
    require(isinstance(memory, dict), "memoryPolicy is required")
    require(memory.get("minimumPhysicalMemoryBytes") == ram_gib * 1024**3, f"RAM gate must be {ram_gib} GiB")

    artifact = model.get("artifact")
    require(isinstance(artifact, dict), "artifact is required")
    repository = artifact.get("repository")
    revision = artifact.get("revision")
    filename = artifact.get("filename")
    require(repository == f"litert-community/gemma-4-{variant}-it-litert-lm", "unexpected repository")
    require(isinstance(revision, str) and REVISION_RE.fullmatch(revision) is not None, "revision must be a full commit SHA")
    require(filename == f"gemma-4-{variant}-it.litertlm", "unexpected filename")
    require(isinstance(filename, str) and filename == Path(filename).name and ".." not in filename, "unsafe filename")
    expected_url = f"https://huggingface.co/{repository}/resolve/{revision}/{urllib.parse.quote(filename)}"
    require(artifact.get("url") == expected_url, "artifact URL must be pinned to its revision")
    require(artifact.get("sizeBytes") == size_bytes, "unexpected artifact size")
    sha256 = artifact.get("sha256")
    require(isinstance(sha256, str) and SHA256_RE.fullmatch(sha256) is not None, "invalid SHA-256")
    require(sha256 == expected_sha256, "unexpected SHA-256")
    require(artifact.get("access") == {"anonymous": True, "gated": False, "supportsByteRanges": True}, "unexpected access policy")
    require(model.get("license", {}).get("spdx") == "Apache-2.0", "unexpected license")


def fetch_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "FudAI-Catalog-Validator/1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except Exception as error:
        raise ValidationError(f"Cannot fetch {url}: {error}") from error


def validate_online(model: dict[str, Any]) -> None:
    artifact = model["artifact"]
    repository = artifact["repository"]
    revision = artifact["revision"]
    api_url = f"https://huggingface.co/api/models/{repository}/revision/{revision}?blobs=true"
    metadata = fetch_json(api_url)
    require(metadata.get("sha") == revision, "revision metadata mismatch")
    require(metadata.get("private") is False, "repository became private")
    require(metadata.get("gated") is False, "repository became gated")
    require((metadata.get("cardData") or {}).get("license") == "apache-2.0", "remote license changed")

    remote = next((item for item in metadata.get("siblings", []) if item.get("rfilename") == artifact["filename"]), None)
    require(remote is not None, "artifact disappeared")
    lfs = remote.get("lfs") or {}
    require(lfs.get("size") == artifact["sizeBytes"], "remote size changed")
    require(lfs.get("sha256") == artifact["sha256"], "remote checksum changed")

    request = urllib.request.Request(artifact["url"], method="HEAD", headers={"User-Agent": "FudAI-Catalog-Validator/1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content_length = int(response.headers.get("Content-Length", "0"))
            accept_ranges = response.headers.get("Accept-Ranges", "").lower()
    except Exception as error:
        raise ValidationError(f"Anonymous HEAD failed: {error}") from error
    require(content_length == artifact["sizeBytes"], "anonymous content length changed")
    require(accept_ranges == "bytes", "byte-range resume is no longer advertised")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().with_name("catalog.v1.json"))
    parser.add_argument("--online", action="store_true")
    args = parser.parse_args()
    try:
        models = validate_catalog(read_catalog(args.catalog))
        if args.online:
            for model in models:
                validate_online(model)
    except ValidationError as error:
        print(f"catalog invalid: {error}", file=sys.stderr)
        return 1
    print("catalog valid: 2 models, 2 pinned artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
