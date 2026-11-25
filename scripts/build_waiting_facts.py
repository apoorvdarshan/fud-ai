#!/usr/bin/env python3
"""Rebuild the app's small offline fact catalog from a pinned USDA SR Legacy ZIP."""

import argparse
import csv
import hashlib
import io
import json
import zipfile
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "ios/calorietracker/Resources/WaitingFactsData/analysis_nutrition_facts.json"
FOODS = ROOT / "shared/waiting-facts/foods.tsv"
LABELS = ROOT / "shared/waiting-facts/labels.tsv"
ARCHIVE_SHA = "b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0"
ARCHIVE_ROOT = "FoodData_Central_sr_legacy_food_csv_2018-04"
# Skip missing amounts and negligible traces instead of turning them into claims.
# Each value is per 100 g edible portion. The ID fixes the nutrient definition and unit.
NUTRIENTS = {
    1003: ("protein", "G", "0.5"),
    1004: ("fat", "G", "0.5"),
    1005: ("carbs", "G", "1"),
    1051: ("water", "G", "2"),
    1079: ("fiber", "G", "0.5"),
    1087: ("calcium", "MG", "5"),
    1089: ("iron", "MG", "0.1"),
    1090: ("magnesium", "MG", "5"),
    1092: ("potassium", "MG", "20"),
    1093: ("sodium", "MG", "10"),
    1095: ("zinc", "MG", "0.1"),
    1106: ("vitamin_a", "UG", "3"),
    1109: ("vitamin_e", "MG", "0.1"),
    1114: ("vitamin_d", "UG", "0.1"),
    1162: ("vitamin_c", "MG", "0.5"),
    1177: ("folate", "UG", "3"),
    1178: ("vitamin_b12", "UG", "0.1"),
    1185: ("vitamin_k", "UG", "1"),
    2000: ("sugar", "G", "1"),
}
LOCALES = "en ar az cs de es fr hi it ja ko nl pl pt-BR ro ru tr uk zh-Hans".split()


def read_table(archive, name):
    with archive.open(f"{ARCHIVE_ROOT}/{name}.csv") as file:
        yield from csv.DictReader(io.TextIOWrapper(file, encoding="utf-8-sig"))


def rounded(amount):
    place = "1" if amount >= 100 else "0.1" if amount >= 1 else "0.01"
    return float(amount.quantize(Decimal(place), rounding=ROUND_HALF_UP))


def create(archive_path, old, labels):
    if hashlib.sha256(archive_path.read_bytes()).hexdigest() != ARCHIVE_SHA:
        raise ValueError("Not the pinned USDA SR Legacy April 2018 ZIP")
    rows = csv.reader(FOODS.read_text().splitlines()[1:], delimiter="\t")
    curated = {int(fid): (prep, english) for fid, prep, english, _ in rows}
    foods = {food["id"]: food for food in (labels or old)["foods"]}
    if foods.keys() != curated.keys():
        raise ValueError("Curated IDs and food label IDs do not match")
    with zipfile.ZipFile(archive_path) as archive:
        official = {int(row["fdc_id"]): row["description"] for row in read_table(archive, "food")}
        metadata = {int(row["id"]): row for row in read_table(archive, "nutrient")}
        assert all(metadata[nid]["unit_name"] == unit for nid, (_, unit, _) in NUTRIENTS.items())
        by_food = {fid: {} for fid in curated}
        for row in read_table(archive, "food_nutrient"):
            fid, nid = int(row["fdc_id"]), int(row["nutrient_id"])
            if fid not in curated or nid not in NUTRIENTS or not row["amount"]:
                continue
            key, _, minimum = NUTRIENTS[nid]
            amount = Decimal(row["amount"])
            if amount >= Decimal(minimum):
                if key in by_food[fid]:
                    raise ValueError(f"Duplicate USDA amount for {fid}/{nid}")
                by_food[fid][key] = rounded(amount)
    output = []
    for fid, (prep, english) in curated.items():
        food = foods[fid]
        names = food["names"]
        if names.keys() != set(LOCALES) or names["en"] != english or not all(names.values()):
            raise ValueError(f"Incomplete or inconsistent food labels: {fid}")
        output.append({
            "id": fid, "wikidata": food.get("wikidata", ""), "preparation": prep,
            "description": official[fid], "names": names,
            "values": {key: by_food[fid][key] for key, _, _ in NUTRIENTS.values() if key in by_food[fid]},
        })
    if sum(len(food["values"]) for food in output) < 1_500:
        raise ValueError("Catalog must contain at least 1,500 distinct measurements")
    revision = hashlib.sha256(json.dumps(output, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:16]
    return {"revision": revision, "foods": output}


def encode(catalog):
    # One row per known USDA entry keeps the bundled asset and git diffs small.
    rows = ",\n".join(
        "  " + json.dumps(food, ensure_ascii=False, separators=(",", ":"))
        for food in catalog["foods"]
    )
    return f'{{"revision":"{catalog["revision"]}","foods":[\n{rows}\n]}}\n'


def localized_resources():
    rows = list(csv.DictReader(LABELS.read_text().splitlines(), delimiter="\t"))
    assert {row["locale"] for row in rows} == set(LOCALES) and len(rows) == len(LOCALES)
    keys = [key for key in rows[0] if key != "locale"]
    android = ROOT / "android/app/src/main/res"
    strings = {key: {"localizations": {}} for key in keys}
    for row in rows:
        locale = row["locale"]
        qualifier = {"en": "", "pt-BR": "-pt-rBR", "zh-Hans": "-zh-rCN"}.get(locale, f"-{locale}")
        lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
        for key in keys:
            value = row[key]
            assert value and (key != "measure_per_100g" or all(value.count(ref) == 1 for ref in ("%1$s", "%2$s")))
            xml_value = escape(value.replace("'", r"\'"))
            lines.append(f'    <string name="waiting_fact_{key}">{xml_value}</string>')
            apple = value.replace("%1$s", "%1$@").replace("%2$s", "%2$@")
            strings[key]["localizations"][locale] = {"stringUnit": {"state": "translated", "value": apple}}
        yield android / f"values{qualifier}/nutrition_fact_labels.xml", "\n".join(lines + ["</resources>", ""])
    swift = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    yield CATALOG.parents[2] / "NutritionFactLabels.xcstrings", json.dumps(swift, ensure_ascii=False, indent=2) + "\n"


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("archive", type=Path, help="Pinned USDA SR Legacy CSV ZIP")
    args.add_argument("--labels", type=Path, help="Optional replacement reviewed display labels")
    args.add_argument("--check", action="store_true", help="Verify the committed bundle instead of rewriting it")
    options = args.parse_args()
    previous = CATALOG.read_text() if CATALOG.exists() else None
    updated = create(
        options.archive,
        json.loads(previous) if previous else None,
        json.loads(options.labels.read_text()) if options.labels else None,
    )
    contents = encode(updated)
    for output, data in [(CATALOG, contents), *localized_resources()]:
        if options.check:
            if not output.is_file() or data != output.read_text(encoding="utf-8"):
                args.error(f"{output.relative_to(ROOT)} differs from the source data")
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(data, encoding="utf-8")
    print(f'{len(updated["foods"])} foods, {sum(len(f["values"]) for f in updated["foods"])} USDA facts')


if __name__ == "__main__":
    main()
