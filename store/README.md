# Store release automation

Fud AI's App Store and Google Play releases run on
[apoorvdarshan/store-release](https://github.com/apoorvdarshan/store-release), a
shared set of reusable GitHub Actions workflows and Python tooling that started as
this repository's own pipeline and keeps its behaviour. This folder holds what is
specific to Fud AI: its store data, its config, and the facts below.

**Nothing reaches a store unless its switch is on.** An `ios-v*` tag runs
[`ios-release.yml`](../.github/workflows/ios-release.yml): GitHub Actions builds,
signs and uploads the iOS binary (Xcode Cloud no longer builds Fud AI). An
`android-v*` tag runs [`android-release.yml`](../.github/workflows/android-release.yml):
GitHub Actions builds, verifies and signs the Android app, and uploads it to the
Play tracks you switch on. A manual **Run workflow** on either only validates the
build and prints a read-only store plan of what a tag run would change; it never
uploads, submits or creates a GitHub Release.

How it works, in store-release's docs (for the version pinned here,
[v0.1.0](https://github.com/apoorvdarshan/store-release/tree/v0.1.0/docs)):

| Topic | Doc |
|-------|-----|
| The jobs, their order, check names and caller checks | [workflows](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/workflows.md) |
| What each switch does | [switches](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/switches.md) |
| Which job uses which secret, the shared CI signing identity and how to copy it | [secrets](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/secrets.md) |
| `store-release.toml` keys | [config](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/config.md) |
| **Re-run failed jobs** | [rerun](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/rerun.md) |
| The store plan on validation runs | [plan](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/plan.md) |
| Verifying Android release files | [verification](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/verification.md) |
| New store-release versions, yanked versions | [release process](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/release-process.md), [yanked](https://github.com/apoorvdarshan/store-release/blob/main/docs/yanked.md) |

## What lives here

| File | Role |
|------|------|
| [`store-release.toml`](store-release.toml) | Fud AI's identities and paths: bundle id, App Store Connect app id, team, Xcode project, the pinned upload certificate, screenshot folders, catalog, sparse checkouts, release titles and asset names |
| [`../.github/workflows/ios-release.yml`](../.github/workflows/ios-release.yml), [`android-release.yml`](../.github/workflows/android-release.yml), [`store-checks.yml`](../.github/workflows/store-checks.yml) | Thin callers of store-release, pinned by commit SHA with a `# vX.Y.Z` comment. They are rendered from the config (`run.py callers render`); **Store checks** fails when they drift, so don't edit them by hand |
| [`catalog/`](catalog/) | The IAP / subscription / tip / credit catalog |
| [`../scripts/ci/check_catalog.py`](../scripts/ci/check_catalog.py) | Fud AI's own catalog rules (the `catalog.checks` hook) |
| [`screenshots/`](screenshots/), [`creative/ios/`](creative/ios/), [`graphics/`](graphics/) | Listing images ([Metadata layout](#metadata-layout)) |
| [`../APPSTORE.md`](../APPSTORE.md), [`../PLAYSTORE.md`](../PLAYSTORE.md) | Listing copy and What's New |
| [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md) | GitHub Release notes, one `## <tag>` section per tag |

**Store checks** (`store-checks.yml`) runs on pull requests and `main` pushes that
touch these files: the switches, the config and every file it names, the catalog,
the listing and What's New text, screenshot staging with Pillow, the project's
export compliance and versions, the gradle version, the callers and their pins.
It never calls a store API.

New store-release versions arrive as one Dependabot pull request (group
`store-release`, after a 7-day cooldown) that moves every pin at once. Merge it
once Store checks pass; when its changelog's `Impact:` is `validation` or
`release-path`, first run **iOS Release** and **Android Release** on the
Dependabot branch with **Run workflow** and check both store plans.

## Tags and versions

- iOS: `ios-vX.Y`, equal to `MARKETING_VERSION` in
  [`ios/calorietracker.xcodeproj`](../ios/calorietracker.xcodeproj) (the same in
  every configuration). Build numbers come from App Store Connect.
- Android: `android-vX.Y`, equal to `versionName` in
  [`android/app/build.gradle.kts`](../android/app/build.gradle.kts). Bump
  `versionCode` before tagging: Play rejects a versionCode it has seen, and the
  run stops before building when Play or an earlier `android-v*` GitHub Release
  has it.
- Push `ios-v*` and `android-v*` tags one at a time: GitHub starts no workflow
  for any of them when more than three tags are pushed at once.

## Switches

Repository **Variables** (Settings → Secrets and variables → Actions → Variables).
Missing means off; any value other than `true/false/yes/no/on/off/1/0` fails the
run, even when the platform's master switch is off. What each one does:
[switches](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/switches.md);
the canonical list of names is store-release's
[`gates.env.example`](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/gates.env.example).

Fud AI's values on 2026-10-21 (the Variables page is the source of truth):

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
| `STORE_SYNC_REVENUECAT` | not set (off; on fails the run, as the sync is not implemented) |

So today an `ios-v*` tag creates the App Store version when it is missing, then
builds, uploads and waits for the build, and creates the GitHub Release (Latest
when it is the highest iOS version); it changes no listing text, screenshots or
images and submits nothing. An `android-v*` tag builds, verifies and signs the
app, updates the Play listing text, uploads to no track, and creates a GitHub
pre-release.

## Secrets

Repository secrets (Settings → Secrets and variables → Actions → Secrets). The
callers map each one by name to the store-release job that uses it, never with
`secrets: inherit`.

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | Admin-role App Store Connect API key: version creation, build numbers, cloud-managed distribution signing, upload, build readiness, listing, review, the store plan |
| `APP_STORE_CONNECT_ISSUER_ID` | Its issuer |
| `APP_STORE_CONNECT_API_KEY_P8` | Its private key (`.p8` body) |
| `IOS_DEV_SIGNING_P12_BASE64` | The CI Apple Development identity (base64 `.p12`; certificate "Apple Development: Created via API", ID `NVNGDQGCKK`, **expires 2027-10-14**) that signs the archive. App Store signing happens at export with Apple's cloud-managed distribution certificate |
| `IOS_DEV_SIGNING_P12_PASSWORD` | Password for that `.p12` |
| `ANDROID_KEYSTORE_BASE64` | base64 of `fudai-release.jks`, the upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Upload key alias (`fudai`) |
| `ANDROID_KEY_PASSWORD` | Upload key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play publisher service-account JSON key: Play listing, uploads, track read-back, the store plan |
| `GRADLE_ENCRYPTION_KEY` | Not a store secret: the Gradle configuration-cache key of [Quality checks](../.github/workflows/quality.yml) |

The same CI identity signs the archives of Verceltics, Abysm, iitjee, Quit All and
Scowld (Apple allows one API-created development certificate per API key). When it
expires or is revoked, replace both `IOS_DEV_SIGNING_*` secrets in Fud AI and in
every repository listed here; to give another repository the identity without
rotating it, follow
[secrets](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/secrets.md#the-shared-ci-signing-identity).

The Android upload certificate is pinned in
[`store-release.toml`](store-release.toml) (`android.upload_cert_sha256`,
`35d739f4…f704e0`, the certificate every Fud AI release since 1.0.0 is signed
with). A release run fails when `ANDROID_KEYSTORE_BASE64` holds any other key,
before anything is signed or published. After a Play upload-key reset, change
that value in the same change as the new keystore secret.

## Export compliance (iOS)

The app declares its export compliance answer in its own Info.plist
([`ios/calorietracker/Info.plist`](../ios/calorietracker/Info.plist):
`ITSAppUsesNonExemptEncryption` = `false`), so every build's Info.plist carries
it. Without it, App Store Connect asks about each build by hand, and review
submission fails with `usesNonExemptEncryption` missing (HTTP 409).

`NO` is right while the app only uses exempt encryption: HTTPS (Apple's
networking and the RevenueCat SDK), Keychain, iOS Data Protection, SHA-256 for
download and backup integrity, and Apple's system crypto. If the app ever adds
its own encryption, set it to `YES`, add `ITSEncryptionExportComplianceCode` with
the code App Store Connect issues for the app's encryption documentation, and
change `ios.export_compliance` in [`store-release.toml`](store-release.toml): it
pins the answer (`NO`), so Store checks and every iOS release run fail when the
project declares another one. Release and validation runs also check the
archived app's Info.plist right after the archive.

## Canonical catalog

- [`catalog/products.json`](catalog/products.json): App Store and Play product IDs
  (Plus / Pro subscriptions, credit packs, tips)
- [`catalog/revenuecat.json`](catalog/revenuecat.json): entitlements and offerings

store-release checks the generic rules (each group is a list of unique
`com.apoorvdarshan.calorietracker.*` ids; a `play` block exactly when `android`
is in `platforms`; Play id formats; `revenuecat_product_id` = `<sub>:<base>`).
[`scripts/ci/check_catalog.py`](../scripts/ci/check_catalog.py) adds Fud AI's: the
`plus` / `pro` entitlements and offerings, their packages, Pro including Plus,
and that only subscriptions unlock an entitlement. Both run in iOS tag runs, every
Android release job, and Store checks. The Hosted AI Worker's tests in
[Quality checks](../.github/workflows/quality.yml) (`web`) check its credit
packs against the same catalog.

Source of truth for product IDs stays aligned with
[`docs/HOSTED_AI_REVENUECAT.md`](../docs/HOSTED_AI_REVENUECAT.md).

`IOS_SUBMIT_REVIEW` submits the App Store version together with every catalog
subscription that is `READY_TO_SUBMIT` (or `DEVELOPER_REJECTED`, after its
version was removed from an earlier submission), attached through
`subscriptionVersion` review items, so a first-time subscription ships with the
version. Credit and tip IAPs go through `POST /inAppPurchaseSubmissions` only
after the final build and selection recheck, because that sends them to App
Review at once. Only products listed in `products.json` are ever submitted.

## Metadata layout

```
store/screenshots/     # listing images, as live on the stores (source of truth)
  ios/                 # 10 App Store iPhone screenshots, 1290×2796, NN-name.png
  ipad/                # 7 App Store iPad screenshots, 2064×2752
  iphone-duo/          # 7 App Store iPhone Duo screenshots, outer screen 1398×2034
                       # (the same set also takes inner-screen 2007×2853 frames)
  play/                # 8 Play phone screenshots, 1290×2796 (padded to 9:16 when staged)
  watch/               # Apple Watch screenshot, 416×496 (APP_WATCH_SERIES_10)
store/creative/ios/    # App Store product page creative assets (Asset Library)
  header/              # one product page header PNG, exactly 3840×1646
  search-results/      # one search results PNG or JPEG, 3:2, 1920 to 3840 wide
store/graphics/
  play/feature-graphic.png   # Play feature graphic, 1024×500 (uploaded by hand)
```

The folders and their App Store display types are set in
[`store-release.toml`](store-release.toml) (`[[ios.screenshots.sets]]`,
`[android.screenshots]`). Release runs stage the screenshots into a temporary
folder of the runner, never into the repository: App Store frames are saved as
RGB without an alpha channel (App Store Connect refuses alpha even when every
pixel is opaque), and Play frames are padded onto black at 9:16 (1573×2796), at
most 8. Each upload replaces that store's whole set, and an App Store set is
replaced only when its files differ. Every iOS set needs at least one numbered
PNG; the app supports iPad, and App Store Connect will not submit a version
without 13" iPad screenshots. iPhone Duo screenshots are optional until April
2027. To change a listing, replace the files in `store/screenshots/` first, so
turning a switch on never uploads an older set. The files here are only read,
never rewritten.

The Play feature graphic has no automated upload; upload it in Play Console by hand.

## Header and search results images (iOS)

With `IOS_UPLOAD_CREATIVE_ASSETS` on, the images in `store/creative/ios/` go to
the product page through the app's Asset Library: a new image is uploaded once
(matched by file name and size), reviewed in a submission of its own, and placed
on the version's en-US page once Apple approves it, while the version can be
edited. To change an image, commit it under a new file name.

Fud AI's header (`fud-ai-header-orb-horizon.png`, 1,763,332 bytes) and search
results image (`fud-ai-search-voice.png`, 2,670,930 bytes) were approved and placed
on the live 7.1.2 page on 2026-10-20, so the pipeline finds them in the Asset
Library and never uploads them again. Their names and sizes are pinned in
[`store-release.toml`](store-release.toml) (`[ios.creative.sizes]`): Store checks
and every iOS release run fail when the files change, until the pin is updated
with the new image.

Website and README screenshots stay in [`web/assets/screenshots/`](../web/assets/screenshots/).
Promo, social and launch graphics do not belong in the repository.

## Release procedure

1. Keep `APPSTORE.md`, `PLAYSTORE.md` and `RELEASE_NOTES.md` in sync. The tag's
   `## ios-vX.Y` / `## android-vX.Y` section becomes the GitHub Release body; a
   tag without one gets GitHub's generated notes, and an empty or duplicated
   section fails the run.
2. Bump the version: iOS `MARKETING_VERSION` (the tag must equal it); Android
   `versionName` and a `versionCode` higher than any Play has seen.
3. **iOS: the X.Y App Store version must exist** (Prepare for Submission) whenever
   `IOS_UPLOAD_LISTING`, `IOS_UPLOAD_WHATS_NEW`, `IOS_UPLOAD_SCREENSHOTS`,
   `IOS_UPLOAD_CREATIVE_ASSETS` or `IOS_SUBMIT_REVIEW` is on. With
   `IOS_CREATE_VERSION` on, the tag run creates it when it is missing; otherwise
   create it in App Store Connect first.
4. Flip only the variables you want. iOS operations also need
   `IOS_RELEASE_AUTOMATION`; Android ones need `ANDROID_RELEASE_AUTOMATION`.
5. Run **iOS Release → Run workflow** (with **marketing_version** set to the next
   unapproved version, e.g. `7.2`, before the project is bumped; Apple refuses
   versions it has already approved) and **Android Release → Run workflow**. Both
   validate the signed build, and their `store-plan / store-plan` job lists, read
   only, every App Store Connect, Play and GitHub Release change a tag run would
   make with the current switches. Read it before tagging.
6. Check that the commit you tag passed **Quality checks** and, for iOS, **iOS
   tests** (that workflow runs on `main` only when iOS files change and daily;
   start it with **Run workflow** if its last run is older or red). The tag run
   repeats them and builds nothing if one fails.
7. Tag as usual (`ios-vX.Y` / `android-vX.Y`). Try a build on
   `ANDROID_INTERNAL_TESTING` first; leave `ANDROID_PRODUCTION_ROLLOUT`,
   `IOS_SUBMIT_REVIEW` and `IOS_PUBLIC_RELEASE` off until you intend that release.
8. If a later step fails, fix the cause and use **Re-run failed jobs**
   ([rerun](https://github.com/apoorvdarshan/store-release/blob/v0.1.0/docs/rerun.md)).

**Xcode Cloud:** its tag-triggered "Release" workflow was removed in App Store
Connect. Xcode Cloud triggers live in App Store Connect, not in this repository,
so a GitHub switch cannot stop them; never re-create an `ios-v*` Xcode Cloud
workflow, or both pipelines will upload a build for the same tag.

## Verifying Android release files

Each `android-v*` GitHub Release carries `fudai-vX.Y.aab` and `fudai-vX.Y.apk`,
each with a Sigstore bundle (`.sigstore.json`) and a build provenance attestation.

Releases built by store-release (after `android-v7.2`) are signed by
store-release's `android-publish.yml`, an identity every app that uses it shares,
so always pin this repository as well:

```bash
cosign verify-blob fudai-vX.Y.apk --bundle fudai-vX.Y.apk.sigstore.json \
  --certificate-identity-regexp '^https://github\.com/apoorvdarshan/store-release/\.github/workflows/android-publish\.yml@[0-9a-f]{40}$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-github-workflow-repository apoorvdarshan/fud-ai
gh attestation verify fudai-vX.Y.apk --repo apoorvdarshan/fud-ai \
  --signer-workflow apoorvdarshan/store-release/.github/workflows/android-publish.yml
```

`android-v7.2` and earlier were signed by this repository's own
`android-release.yml`:

```bash
cosign verify-blob fudai-v7.2.apk --bundle fudai-v7.2.apk.sigstore.json \
  --certificate-identity-regexp '^https://github.com/apoorvdarshan/fud-ai/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify fudai-v7.2.apk --repo apoorvdarshan/fud-ai
```

## Local checks

With a checkout of store-release next to this repository (no store calls; the
last two need its `requirements.txt` and `requirements-checks.txt`):

```bash
sr() { python3 -I -u ../store-release/run.py --app-root . "$@"; }
sr config check --files
sr catalog check
sr listing prepare --platform all --out /tmp/listing
sr whats-new prepare --platform all --tag ios-v7.2 --out /tmp/whatsnew
sr screenshots stage --platform all --dry-run --out /tmp/screenshots
sr release-notes --check-headings
IOS_SUBMIT_REVIEW=maybe sr print-switches --platform all   # shows INVALID
STORE_SYNC_REVENUECAT=false sr revenuecat-gate
GH_TOKEN="$(gh auth token)" sr callers render --platform ios \
  --sha <the pinned sha> --version <its version> --app-repository apoorvdarshan/fud-ai
```
