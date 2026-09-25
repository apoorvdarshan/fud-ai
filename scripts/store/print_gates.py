#!/usr/bin/env python3
"""Print store automation gate status. Never mutates stores."""
from __future__ import annotations

import os

GATES = [
    "STORE_UPLOAD_WHATS_NEW",
    "STORE_UPLOAD_LISTING",
    "STORE_UPLOAD_SCREENSHOTS",
    "STORE_PRODUCTION_ROLLOUT",
    "STORE_SUBMIT_IOS_REVIEW",
    "STORE_SYNC_REVENUECAT",
]


def enabled(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def main() -> None:
    print("Store automation gates (false = setup-only / safe):")
    any_on = False
    for name in GATES:
        on = enabled(name)
        any_on = any_on or on
        print(f"  {name}={'true' if on else 'false'}")
    if not any_on:
        print("all gates off — no live listing/submit/rollout/sync will run")
    else:
        print("WARNING: one or more live gates are on")


if __name__ == "__main__":
    main()
