# Facts shown while analysis runs

Both apps bundle the same small catalog: 1,965 distinct nutrient measurements
across 140 foods, plus five existing editorial facts. A shuffled deck advances
once per displayed wait, keeps the reading stable while progress changes, and
persists its position locally. No API call, diary access, recommendation, or extra
delay in completing analysis is involved.

## Sources and updates

- Values come from [USDA FoodData Central SR Legacy, April 2018](https://fdc.nal.usda.gov/download-datasets/).
  USDA data are in the public domain ([licensing and attribution](https://fdc.nal.usda.gov/faq/)).
  `foods.tsv` pairs a specific FDC ID with the consumer name and preparation.
  The bundle keeps each original USDA description. FoodData Central entries can
  be inspected at `https://fdc.nal.usda.gov/food-details/<id>/nutrients`.
- Food display names were adapted from [Wikidata's CC0 structured labels](https://www.wikidata.org/wiki/Wikidata:Licensing)
  and edited for food names and variants. If a reliable, specific local label
  was unavailable, the exact English product name is used, not an ambiguous
  translation. App labels, preparation, number and unit formatting are localized.
- The amounts are per 100 g edible food as represented in the source (not
  per serving). Zero/missing and small trace values are excluded; recorded
  values are rounded to at most two decimal places. Cards avoid personalized
  advice, daily targets, or nutrient-rich/health claims.

Rebuild or verify against the pinned USDA ZIP (6 MB), without any API key:

```sh
curl --fail --location -o /tmp/fud-usda-sr-legacy.zip \
  https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
python3 scripts/build_waiting_facts.py /tmp/fud-usda-sr-legacy.zip --check
```

Omit `--check` to update generated resources. To update food names or add
foods, also pass `--labels <json>` containing `foods` with `id`, `wikidata`
and `names` for each curated entry (same shape as the committed bundle).
`labels.tsv` maintains the translated preparation and measurement format
strings for both app bundles.
