# Store release automation

GitHub Actions plumbing for App Store + Play Store releases, including listing
copy, What's New, screenshots, and the IAP / subscription / tip / credit catalog
(RevenueCat).

**An `android-v*` tag publishes to Play Open testing.** Testers can install that build. Production is left alone unless `STORE_PRODUCTION_ROLLOUT` is on, and that switch is off. When it is on, the same upload also publishes production for real. There is no production draft. Create the Open testing track in Play Console once before the first tag. iOS still only creates a GitHub Release; Xcode Cloud uploads the binary. Listing, screenshots, and App Review stay off unless you enable those variables.

## Current vs planned

| Step | iOS today | Android today | Automation (gates OFF) |
|------|-----------|---------------|------------------------|
| Tag → quality | `v*` → GitHub Release | `android-v*` → Open testing | unchanged |
| Binary upload | Xcode Cloud → ASC | GHA → Play Open testing (`beta`), live | unchanged for iOS |
| What's New | manual paste | optional via `STORE_UPLOAD_WHATS_NEW` on the Play upload | prepared locally every tag |
| Listing / screenshots | manual | manual | **wired** (`asc_release.py`, `play_listing.py`) |
| Submit for review / production | manual | off unless `STORE_PRODUCTION_ROLLOUT` | **wired, OFF** (`STORE_SUBMIT_IOS_REVIEW`, `STORE_PRODUCTION_ROLLOUT`) |
| IAP / subs / tips / credits | ASC + RevenueCat console | not shipped yet | versioned `store/catalog/` + validate CI |
| RevenueCat sync | manual | n/a | dry-run in CI; `STORE_SYNC_REVENUECAT=true` **fails closed** |

## Safe defaults (do not flip until ready)

Repository **Variables** (Settings → Secrets and variables → Actions → Variables):

| Variable | Default | When `true` |
|----------|---------|-------------|
| `STORE_UPLOAD_WHATS_NEW` | unset / false | Attach English What's New on the Play upload |
| `STORE_UPLOAD_LISTING` | unset / false | Upload title/description (Play + ASC listing + iOS What's New) |
| `STORE_UPLOAD_SCREENSHOTS` | unset / false | Upload phone / 6.7" screenshots (see below) |
| `STORE_PRODUCTION_ROLLOUT` | unset / false | Also publish the Play production track, live |
| `STORE_SUBMIT_IOS_REVIEW` | unset / false | Submit the editable ASC version for review |
| `STORE_SYNC_REVENUECAT` | unset / false | **Fails the workflow** — RevenueCat write/sync is not implemented yet |

Copy names from [`gates.env.example`](gates.env.example). Leave unset or `false`
for setup-only runs.

## Secrets

Already used by release workflows:

- `ANDROID_KEYSTORE_*`, `PLAY_SERVICE_ACCOUNT_JSON`

For ASC metadata / submit (when iOS store gates are on):

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | ASC JWT (`asc_release.py`) |
| `APP_STORE_CONNECT_ISSUER_ID` | ASC JWT |
| `APP_STORE_CONNECT_API_KEY_P8` | ASC private key (`.p8` body) |

Later for catalog automation:

| Secret | Used for |
|--------|----------|
| `REVENUECAT_SECRET_API_KEY` | Catalog sync / product verification |

## Scripts

| Script | Role |
|--------|------|
| [`scripts/store/prepare_listing_text.py`](../scripts/store/prepare_listing_text.py) | `APPSTORE.md` / `PLAYSTORE.md` → `store/metadata/` |
| [`scripts/store/prepare_whats_new.py`](../scripts/store/prepare_whats_new.py) | What's New for Play + iOS |
| [`scripts/store/stage_screenshots.py`](../scripts/store/stage_screenshots.py) | `web/assets/screenshots/*.png` → `store/metadata/screenshots/` |
| [`scripts/store/asc_release.py`](../scripts/store/asc_release.py) | ASC listing, 6.7" screenshots, submit for review |
| [`scripts/store/play_listing.py`](../scripts/store/play_listing.py) | Play listing text + phone screenshots |
| [`scripts/store/sync_revenuecat_catalog.py`](../scripts/store/sync_revenuecat_catalog.py) | Validate catalog; fail if sync gate on |

When any live iOS or Play listing gate is on, release workflows install
[`requirements-store.txt`](../requirements-store.txt) (PyJWT, Google API client).

Environment flags consumed by the publisher scripts (set by workflows from repo
variables): `UPLOAD_LISTING`, `UPLOAD_SCREENSHOTS`, `SUBMIT_IOS_REVIEW`.

## Canonical catalog

- [`catalog/products.json`](catalog/products.json) — App Store + Play product IDs (subs, credits, tips)
- [`catalog/revenuecat.json`](catalog/revenuecat.json) — entitlements + offerings

Source of truth for product IDs stays aligned with [`docs/HOSTED_AI_REVENUECAT.md`](../docs/HOSTED_AI_REVENUECAT.md).

## Metadata layout

```
store/metadata/
  play/en-US/          # title, short/full description, whatsnew (generated)
  ios/en-US/           # description, keywords, promotional_text, whats_new
  screenshots/
    play/phone/        # Play phoneScreenshots (staged from web assets)
    ios/6.7/           # ASC APP_IPHONE_67 slot
```

Marketing PNGs live in [`web/assets/screenshots/`](../web/assets/screenshots/).
Release workflows run `stage_screenshots.py` when `STORE_UPLOAD_SCREENSHOTS`
is true, copying those PNGs into `store/metadata/screenshots/` until you replace
them with store-tailored exports.

## How to enable later

1. Keep `APPSTORE.md` / `PLAYSTORE.md` / `RELEASE_NOTES.md` in sync; run the prepare scripts locally if needed.
2. Confirm ASC + Play secrets are present.
3. Flip only the variables you want (e.g. start with `STORE_UPLOAD_LISTING`).
4. Tag as usual (`vX.Y` / `android-vX.Y`). Do **not** set `STORE_PRODUCTION_ROLLOUT` or
   `STORE_SUBMIT_IOS_REVIEW` until you intend a real store submission.

## Dry-run

Local:

```bash
python3 scripts/store/validate_catalog.py
python3 scripts/store/prepare_whats_new.py --platform all --tag android-v6.1 --out /tmp/whatsnew
python3 scripts/store/prepare_listing_text.py --platform all --out /tmp/listing
python3 scripts/store/stage_screenshots.py --dry-run
UPLOAD_LISTING=true python3 scripts/store/asc_release.py --dry-run --metadata-dir store/metadata
UPLOAD_LISTING=true python3 scripts/store/play_listing.py --dry-run --metadata-dir store/metadata
STORE_SYNC_REVENUECAT=false python3 scripts/store/sync_revenuecat_catalog.py
```

The **Store automation (dry-run)** workflow (`workflow_dispatch` or PR paths) validates
the catalog, prepares metadata, runs `--dry-run` on publisher scripts, and **never**
calls App Store Connect or Play APIs.
