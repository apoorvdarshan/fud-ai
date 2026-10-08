#!/usr/bin/env python3
"""Validate store/catalog JSON against basic IAP / RevenueCat invariants."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRODUCTS = ROOT / "store" / "catalog" / "products.json"
REVENUECAT = ROOT / "store" / "catalog" / "revenuecat.json"

CREDIT_PACKAGE_IDS = ("credits_50", "credits_150", "credits_400")
PLUS_PACKAGE_IDS = ("$rc_monthly", "$rc_annual")
PRO_PACKAGE_IDS = ("pro_monthly", "pro_yearly")

# Google Play: product ids are 1-40 chars of [a-z0-9_.] starting with a letter or
# digit; base plan ids are 1-63 chars of [a-z0-9-]. Both are permanent once created.
PLAY_PRODUCT_ID = re.compile(r"^[a-z0-9][a-z0-9_.]{0,39}$")
PLAY_BASE_PLAN_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def package_ids(offering: dict) -> set[str]:
    ids: set[str] = set()
    for package in offering.get("packages", []):
        ident = package.get("identifier")
        if not ident or not isinstance(ident, str):
            fail(f"offering {offering.get('identifier')!r} has invalid package: {package!r}")
        ids.add(ident)
    return ids


def require_packages(offering: dict, required: tuple[str, ...]) -> None:
    oid = offering.get("identifier", "?")
    have = package_ids(offering)
    missing = [pid for pid in required if pid not in have]
    if missing:
        fail(f"offering {oid!r} missing package identifiers: {', '.join(missing)}")


def play_ids(products: dict) -> set[str]:
    """Validates each item's `play` block and returns every RevenueCat-visible Play id."""
    seen: list[str] = []
    for group in ("subscriptions", "consumables", "tips"):
        for item in products[group]:
            pid = item["id"]
            play = item.get("play")
            if "android" not in item.get("platforms", []):
                if play is not None:
                    fail(f"{pid} has a play block but does not list android")
                continue
            if not isinstance(play, dict):
                fail(f"{pid} lists android but has no play block")
            if group == "subscriptions":
                sub = play.get("subscription_id", "")
                base = play.get("base_plan_id", "")
                if not PLAY_PRODUCT_ID.match(sub):
                    fail(f"{pid}: invalid Play subscription_id {sub!r}")
                if not PLAY_BASE_PLAN_ID.match(base):
                    fail(f"{pid}: invalid Play base_plan_id {base!r}")
                if play.get("revenuecat_product_id") != f"{sub}:{base}":
                    fail(f"{pid}: revenuecat_product_id must be '{sub}:{base}'")
                seen.append(f"{sub}:{base}")
            else:
                product_id = play.get("product_id", "")
                if not PLAY_PRODUCT_ID.match(product_id):
                    fail(f"{pid}: invalid Play product_id {product_id!r}")
                seen.append(product_id)
    if len(seen) != len(set(seen)):
        fail("duplicate Play product ids in products.json")
    return set(seen)


def main() -> None:
    products = json.loads(PRODUCTS.read_text())
    revenuecat = json.loads(REVENUECAT.read_text())

    ids: list[str] = []
    for group in ("subscriptions", "consumables", "tips"):
        if group not in products:
            fail(f"missing products.{group}")
        for item in products[group]:
            pid = item.get("id")
            if not pid or not isinstance(pid, str):
                fail(f"invalid id in {group}: {item!r}")
            if not pid.startswith("com.apoorvdarshan.calorietracker."):
                fail(f"unexpected product id prefix: {pid}")
            ids.append(pid)

    if len(ids) != len(set(ids)):
        fail("duplicate product ids in products.json")

    id_set = set(ids)
    play_set = play_ids(products)
    subscription_play_ids = {item["play"]["revenuecat_product_id"] for item in products["subscriptions"] if "play" in item}
    for ent in revenuecat.get("entitlements", []):
        eid = ent.get("id")
        if eid not in {"plus", "pro"}:
            fail(f"unexpected entitlement id: {eid}")
        for pid in ent.get("products", []):
            if pid not in id_set:
                fail(f"entitlement {eid} references unknown product {pid}")
        for pid in ent.get("play_products", []):
            # Only subscriptions may unlock an entitlement; a consumable attached
            # to plus/pro would grant a lifetime plan.
            if pid not in subscription_play_ids:
                fail(f"entitlement {eid} references unknown Play subscription {pid}")

    offerings = revenuecat.get("offerings", [])
    if not offerings:
        fail("revenuecat.offerings must be non-empty")

    by_id = {o.get("identifier"): o for o in offerings}
    for required in ("plus", "pro"):
        if required not in by_id:
            fail(f"missing offering identifier {required!r}")
        packages = by_id[required].get("packages", [])
        if not packages:
            fail(f"offering {required!r} must include at least one package")

    for offering in offerings:
        oid = offering.get("identifier")
        if not oid:
            fail("offering missing identifier")
        for package in offering.get("packages", []):
            pid = package.get("product_id")
            if pid not in id_set:
                fail(f"offering {oid} references unknown product {pid}")
            play_pid = package.get("play_product_id")
            if play_pid is not None and play_pid not in play_set:
                fail(f"offering {oid} references unknown Play product {play_pid}")

    require_packages(by_id["plus"], PLUS_PACKAGE_IDS + CREDIT_PACKAGE_IDS)
    require_packages(by_id["pro"], PRO_PACKAGE_IDS + CREDIT_PACKAGE_IDS)

    # Pro must include Plus for upgrade behavior documented in HOSTED_AI_REVENUECAT.md
    pro = next(e for e in revenuecat["entitlements"] if e["id"] == "pro")
    if "plus" not in pro.get("includes_entitlements", []):
        fail("pro entitlement must include plus")

    print(
        f"ok: {len(ids)} products ({len(play_set)} on Play), {len(revenuecat['entitlements'])} entitlements, "
        f"{len(revenuecat['offerings'])} offerings"
    )


if __name__ == "__main__":
    main()
