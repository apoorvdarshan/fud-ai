# Store releases

Fud AI's App Store and Google Play releases run on
[apoorvdarshan/store-release](https://github.com/apoorvdarshan/store-release), a
shared set of reusable workflows and Python tooling that started as this
repository's own pipeline. Three thin caller workflows pin it by commit SHA, with
the version in a `# vX.Y.Z` comment (v0.3.0 today):

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| [`ios-release.yml`](../.github/workflows/ios-release.yml) | `ios-vX.Y` tag | release run; the tag must equal `MARKETING_VERSION` |
| | **Run workflow** | validation only: archive, cloud-signed export and App Store Connect validation of the IPA, plus a read-only store plan. Never uploads |
| [`android-release.yml`](../.github/workflows/android-release.yml) | `android-vX.Y` tag | release run; the tag must equal `versionName` |
| | **Run workflow** | validation only: the signed AAB and APK, verified, plus a read-only store plan. Never uploads, signs with Sigstore or releases |
| [`store-checks.yml`](../.github/workflows/store-checks.yml) | pull requests and `main` pushes that touch store files, **Run workflow** | checks the store data, [`store-release.toml`](store-release.toml) and the callers against the pinned version. Never uploads |

**Nothing reaches a store unless its switch is on**, and merging never releases:
only a tag push does. The callers are rendered by store-release
(`run.py callers render`); Store checks fails when one is edited by hand or pins
anything but a store-release release. Dependabot moves all three pins to a new
store-release release in one PR of its own.

How the pipeline behaves is documented in store-release (v0.3.0):
[switches](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/switches.md),
[secrets](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/secrets.md),
[jobs and checks](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/workflows.md),
[store plan](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/plan.md),
[re-runs](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/rerun.md),
[verifying downloads](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/verification.md),
[the config file](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/config.md)
and [how a new release reaches this repository](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/release-process.md#how-apps-receive-a-release).
This file keeps only Fud AI's own facts.

## What this repository keeps

- [`store-release.toml`](store-release.toml): where the store files are and Fud AI's
  fixed identities: bundle id and package `com.apoorvdarshan.calorietracker`, App
  Store Connect app `6758935726`, team `23RV7FYH36`, the Xcode to build with, and the
  pinned Android upload certificate.
- The switches (repository variables) and credentials (repository secrets) below.
- The store text: [`APPSTORE.md`](../APPSTORE.md) (App Store listing and What's
  New), [`PLAYSTORE.md`](../PLAYSTORE.md) (Play listing and What's New) and
  [`RELEASE_NOTES.md`](../RELEASE_NOTES.md) (GitHub Release bodies).
- The catalog, screenshots and images under `store/`, and Fud AI's own catalog rules
  in [`scripts/ci/check_catalog.py`](../scripts/ci/check_catalog.py).
- The gates a tag run must pass first: [`ios-tests.yml`](../.github/workflows/ios-tests.yml)
  (iOS unit and UI tests; a test bundle that runs no tests fails) and
  [`quality.yml`](../.github/workflows/quality.yml) (web checks, Android unit tests,
  Release lint and APK, and both emulator UI test shards). iOS tags need both;
  Android tags need Quality checks.

## Current switches

Repository **Variables** (Settings → Secrets and variables → Actions → Variables),
as of 2026-10-21. Missing means off; any value other than
`true/false/yes/no/on/off/1/0` fails the run.

| Variable | Value |
|----------|-------|
| `IOS_RELEASE_AUTOMATION` | `true` |
| `IOS_CREATE_VERSION` | `true` |
| `IOS_UPLOAD_BUILD` | `true` |
| `IOS_UPLOAD_LISTING`, `IOS_UPLOAD_WHATS_NEW`, `IOS_UPLOAD_SCREENSHOTS`, `IOS_UPLOAD_CREATIVE_ASSETS`, `IOS_SUBMIT_REVIEW`, `IOS_PUBLIC_RELEASE` | not set (off) |
| `IOS_BUILD_POLL_SECONDS`, `IOS_BUILD_WAIT_MINUTES` | not set (30 s, 120 min) |
| `ANDROID_RELEASE_AUTOMATION` | `true` |
| `ANDROID_UPLOAD_LISTING` | `true` |
| `ANDROID_UPLOAD_WHATS_NEW`, `ANDROID_UPLOAD_SCREENSHOTS` | `false` |
| `ANDROID_INTERNAL_TESTING`, `ANDROID_CLOSED_TESTING`, `ANDROID_PRODUCTION_ROLLOUT` | `false` |
| `STORE_SYNC_REVENUECAT` | not set (off; on fails the run, since RevenueCat sync is not implemented) |

With these values:

- An `ios-vX.Y` tag creates App Store version X.Y when App Store Connect does not have
  it yet, builds, signs and uploads the binary, and waits until App Store Connect has
  processed it. The listing, What's New, screenshots, header and search results
  images and review submission are left alone. The GitHub Release
  `iOS X.Y (build N)` is created, marked Latest when X.Y is the highest iOS version.
- An `android-vX.Y` tag builds, verifies and Sigstore-signs the AAB and APK, updates
  the Play title, short and full description from `PLAYSTORE.md`, uploads to no Play
  track, and creates the GitHub Release as a pre-release.

Each **Run workflow** prints this plan for the switches as they are at that moment
(the `store-plan` job's summary). Try `ANDROID_INTERNAL_TESTING` before
`ANDROID_PRODUCTION_ROLLOUT`, and leave `IOS_SUBMIT_REVIEW` and `IOS_PUBLIC_RELEASE`
off until you intend that release.

## Secrets

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | Admin-role App Store Connect API key: build numbers, cloud-managed distribution signing, upload, build readiness, listing, review |
| `APP_STORE_CONNECT_ISSUER_ID` | Its issuer |
| `APP_STORE_CONNECT_API_KEY_P8` | Its private key (`.p8` body) |
| `IOS_DEV_SIGNING_P12_BASE64` | The CI Apple Development identity (base64 `.p12`) that signs the archive; App Store signing happens at export with Apple's cloud-managed certificate |
| `IOS_DEV_SIGNING_P12_PASSWORD` | Its password |
| `ANDROID_KEYSTORE_BASE64` | base64 of `fudai-release.jks`, the upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Upload key alias (`fudai`) |
| `ANDROID_KEY_PASSWORD` | Upload key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON key of the `play-publisher` service account (shared by the owner's Android apps): Play listing, uploads, track read-back and the read-only Play checks |

The callers pass each reusable workflow exactly the secrets it declares, by name
(never `secrets: inherit`). `GRADLE_ENCRYPTION_KEY` belongs to Quality checks, not
to the store pipeline. `REVENUECAT_SECRET_API_KEY` is reserved for catalog
automation that does not exist yet and is not set.

The CI Apple Development identity is certificate "Apple Development: Created via
API", ID `NVNGDQGCKK`, **expiring 2027-10-14**. The same identity signs the archives
of Verceltics, Abysm, iitjee, Quit All and Scowld, because Apple allows one
API-created development certificate per API key. Renewing it:
[the shared CI signing identity](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/provenance.md#facts-that-only-concern-the-six-apps);
giving another app repository a copy without the private key leaving GitHub:
[sharing one development identity](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/secrets.md#sharing-one-development-identity-across-your-apps).
When it is replaced, update both `IOS_DEV_SIGNING_*` secrets here and in every
repository listed above.

The Android upload certificate is pinned as `android.upload_cert_sha256` in
[`store-release.toml`](store-release.toml) (`35d739f4…f704e0`, the certificate every
Fud AI release since 1.0.0 is signed with). A run fails before anything is signed or
published when `ANDROID_KEYSTORE_BASE64` holds any other key. After a Play upload-key
reset, change the pin in the same change as the new keystore secret.

## Release procedure

1. Keep `APPSTORE.md`, `PLAYSTORE.md` and `RELEASE_NOTES.md` in sync. The tag's
   `## ios-vX.Y` / `## android-vX.Y` section of `RELEASE_NOTES.md` becomes the
   GitHub Release body; a tag without one gets GitHub's generated notes, and an
   empty or duplicated section fails the run. Store What's New comes from the What's
   New blocks in `APPSTORE.md` and `PLAYSTORE.md`, and is read (and checked, before
   anything is built) only when its switch is on; the listing text likewise.
2. Bump the version: iOS `MARKETING_VERSION`; Android `versionName` and a
   `versionCode` higher than any Play has seen and than every earlier `android-v*`
   GitHub Release's.
3. iOS: the listing, What's New, screenshot, image and review switches need App Store
   version X.Y to exist. With `IOS_CREATE_VERSION` on, the tag run creates it.
4. Run **iOS Release → Run workflow** and **Android Release → Run workflow**. Both
   only validate. Apple refuses versions it has already approved, so while the
   project still has the released version, set the iOS run's `marketing_version` to
   the next one (e.g. `7.2`); the build is validated as that version without changing
   the project. Read each `store-plan` job's summary: it lists every write the tag
   run would make with the current switches, and anything that would stop it.
5. Check that the commit you tag passed **Quality checks** and, for iOS, **iOS
   tests** (it runs on `main` only when iOS files change, and daily; start it with
   **Run workflow** when its last run is older or red). The tag run repeats them and
   builds nothing if one fails.
6. Push `ios-vX.Y` / `android-vX.Y`, one tag at a time.
7. If a later step fails, fix the cause and use **Re-run failed jobs**
   ([re-runs](https://github.com/apoorvdarshan/store-release/blob/v0.3.0/docs/rerun.md)).

**Xcode Cloud** no longer builds Fud AI: its tag-triggered "Release" workflow was
removed in App Store Connect. Xcode Cloud triggers live in App Store Connect, not in
this repository, so a switch here cannot stop one; never re-create an `ios-v*` Xcode
Cloud workflow, or both pipelines would upload a build for the same tag.

## Export compliance (iOS)

[`ios/calorietracker/Info.plist`](../ios/calorietracker/Info.plist) declares
`ITSAppUsesNonExemptEncryption` = `false`, so no build waits for a manual answer in
App Store Connect. `NO` is right while the app only uses exempt encryption: HTTPS
(Apple's networking and the RevenueCat SDK), Keychain, iOS Data Protection, SHA-256
for download and backup integrity, and Apple's system crypto. If the app ever adds
its own encryption, set it to `YES`, add `ITSEncryptionExportComplianceCode` with
the code App Store Connect issues, and change `ios.export_compliance` in
[`store-release.toml`](store-release.toml) in the same change: Store checks and
every iOS release run fail while the project declares anything other than that pin,
and the archived app is checked again right after archiving.

## Catalog

- [`catalog/products.json`](catalog/products.json): App Store and Play product ids
  (4 subscriptions, 3 credit packs, 4 tips).
- [`catalog/revenuecat.json`](catalog/revenuecat.json): RevenueCat entitlements
  (`plus`, `pro`) and offerings.

Product ids stay aligned with
[`docs/HOSTED_AI_REVENUECAT.md`](../docs/HOSTED_AI_REVENUECAT.md). store-release
checks the shared rules (every id starts with `com.apoorvdarshan.calorietracker.`,
ids are unique, Play ids and base plans are valid); Fud AI's own rules (the `plus`
and `pro` entitlements and offerings, their packages and credit packs, Pro including
Plus, only subscriptions unlocking an entitlement) are
[`scripts/ci/check_catalog.py`](../scripts/ci/check_catalog.py), which store-release
runs as a `catalog.checks` hook wherever the catalog is checked.

With `IOS_SUBMIT_REVIEW` on, a release submits the version together with the
catalog's subscriptions that are `READY_TO_SUBMIT` (or `DEVELOPER_REJECTED`, which
attaching the version clears) as `subscriptionVersion` items of the version's review
submission, so a first-time subscription ships with a version. Credit packs and tips
that are `READY_TO_SUBMIT` go through `inAppPurchaseSubmissions`, which sends a
product to App Review at once, so they are submitted only after the final build and
selection recheck. Only products listed in `products.json` are ever submitted.

## Store files

```
store/screenshots/     # listing images, as live on the stores (the source of truth)
  ios/                 # 10 App Store iPhone screenshots, 1290×2796, NN-name.png
  ipad/                # 7 App Store 13" iPad screenshots, 2064×2752
  iphone-duo/          # 7 App Store iPhone Duo screenshots, 1398×2034
  watch/               # 1 Apple Watch screenshot, 416×496 (APP_WATCH_SERIES_10)
  play/                # 8 Play phone screenshots, 1290×2796 (padded to 9:16 when staged)
store/creative/ios/    # App Store product page images (Asset Library)
  header/              # one header PNG, exactly 3840×1646
  search-results/      # one search results PNG or JPEG, 3:2, 1920 to 3840 wide
store/graphics/play/
  feature-graphic.png  # Play feature graphic, 1024×500, uploaded by hand
```

Each screenshot upload replaces that store's set with these files, so to change a
listing, replace the files here first and a switch can never upload an older set.
The app supports iPad, and App Store Connect will not submit a version without 13"
iPad screenshots. iPhone Duo screenshots are optional until April 2027. Website and
README screenshots live in [`web/assets/screenshots/`](../web/assets/screenshots/);
promo, social and launch graphics do not belong in the repository.

## Header and search results images (iOS)

The 9.0 product-page header (`01-product-page-header.png`) and search-results image
(`02-search-results.png`) were staged in App Store Connect's Asset Library.
Store-release matches the staged assets by file name and byte size, so
`ios.creative.sizes` in [`store-release.toml`](store-release.toml) pins both.
Update the pin in the same change when replacing either file.

To change an image, commit it under a new file name and update the pin in the same
change. The next release with `IOS_UPLOAD_CREATIVE_ASSETS` on uploads it, sends it to
App Review in a submission of its own, and places it on the version once Apple has
approved it, which is usually by the following release.

## Verifying Android downloads

Releases after `android-v7.2` are built and signed by store-release's
`android-publish.yml`; pin this repository as well, because every app that uses
store-release shares that signer:

```bash
cosign verify-blob fudai-vX.Y.apk --bundle fudai-vX.Y.apk.sigstore.json \
  --certificate-identity-regexp '^https://github\.com/apoorvdarshan/store-release/\.github/workflows/android-publish\.yml@[0-9a-f]{40}$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-github-workflow-repository apoorvdarshan/fud-ai
gh attestation verify fudai-vX.Y.apk --repo apoorvdarshan/fud-ai \
  --signer-workflow apoorvdarshan/store-release/.github/workflows/android-publish.yml
```

`android-v7.0` through `android-v7.2` were signed by this repository's own
`android-release.yml`:

```bash
cosign verify-blob fudai-vX.Y.apk --bundle fudai-vX.Y.apk.sigstore.json \
  --certificate-identity 'https://github.com/apoorvdarshan/fud-ai/.github/workflows/android-release.yml@refs/tags/android-vX.Y' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify fudai-vX.Y.apk --repo apoorvdarshan/fud-ai \
  --signer-workflow apoorvdarshan/fud-ai/.github/workflows/android-release.yml
```

Earlier Android releases have no Sigstore bundle.

## Checking locally

With Python 3.11 or later (CI uses 3.13), from the repository root:

```bash
git clone --branch v0.3.0 https://github.com/apoorvdarshan/store-release ../store-release
python3 -I -u ../store-release/run.py --app-root . config check --files
python3 -I -u ../store-release/run.py --app-root . catalog check
python3 -I -u ../store-release/run.py --app-root . listing prepare --platform all --out /tmp/listing
python3 -I -u ../store-release/run.py --app-root . whats-new prepare --platform all --tag dry-run --out /tmp/whatsnew
IOS_SUBMIT_REVIEW=maybe python3 -I -u ../store-release/run.py print-switches --platform all   # shows INVALID
```

These read only this checkout and call no store API.
