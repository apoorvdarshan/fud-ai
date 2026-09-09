# Quality checks and accepted diagnostics

The project runs `npm run check` in `web/` and Android
`:app:testDebugUnitTest :app:lintRelease` in the Quality checks workflow.
The same checks are required before releases; see [CONTRIBUTING.md](CONTRIBUTING.md).
Failures must be investigated rather than bypassed with an automatic lint baseline.

## Review on 2026-09-09

Local validation passed 211 Android unit tests and 41 web tests, including four
fast-check properties with 1,000 generated inputs each. Release lint initially
found 22 errors; these were fixed by using Compose-aware resource access and
completing plural categories. Literal percent signs in challenge explanation
strings are explicitly non-format strings.

The remaining warning classes are accepted for this revision with these limits:

- Launcher shape/duplicate icon warnings describe intentionally shared branding
  and launcher artwork across densities and resources. Keep accessible labels and
  platform adaptive-icon rendering checks when changing launcher artwork.
- Unused resource/attribute warnings are cleanup opportunities in shared and
  translated UI resources. Removing them is not required for this release; do not
  remove a resource based only on a text search where runtime lookup may exist.
- KTX suggestions, redundant labels, boxing, modifier-parameter conventions,
  obsolete SDK checks and vector-path optimizations are maintainability or
  performance suggestions. They are not compilation failures. Address them when
  editing the affected UI.
- Plural candidates and unused/optional plural quantities reflect localization
  conventions. Required plural-category errors are fixed. Retain native-language
  review when editing translations; lint does not establish translation quality.
- Default-locale formatting warnings occur in displayed numeric values and picker
  labels. These are user-visible formatting, not a network serialization policy.
  Machine-readable exports and requests must continue to use their serializers.
- The remaining screen-width API warning concerns sheet layout. Check window
  resizing when changing that layout. The framework ExifInterface warning is an
  AndroidX migration recommendation for existing image orientation processing;
  preserve orientation behavior in any migration.
- Dependency/target-version notices are upgrade suggestions, not vulnerability
  verdicts. Security advisories are reviewed separately. The web toolchain's
  sharp advisory GHSA-rgj7-g3m4-5g8c was fixed with 0.35.4; npm audit then reported
  zero known vulnerabilities.
- `InsecureBaseConfiguration` reflects supported user-selected HTTP Ollama/custom
  endpoints. Built-in cloud endpoints use HTTPS and platform certificate checks;
  no trust-all override is introduced. HTTP provides no transport confidentiality,
  so local HTTP support must not become the default for cloud providers. This is
  an accepted compatibility limitation, not a claim that HTTP is secure.
- iOS currently builds in Swift 5 mode. The observed concurrency/availability
  diagnostics remain migration work for Swift 6; raising the language mode must
  include affected actor-isolation tests, not just warning suppression.

Warnings remain visible in reports. This acceptance is limited to the reviewed
classes, not future warnings or confirmed exploitable security findings. The
initial 551 Android warnings were below one per 100 lines of the approximately
107,000 lines of first-party Swift/Kotlin/TypeScript/JavaScript reviewed here;
that density is context, not a replacement for the decisions above.

## Security review evidence

A Gitleaks 8.30.1 scan of all 1,646 Git commits available at the start of this
review found two generic-key candidates. Both were non-secret preference-key
identifiers (heart-rate storage and an energy-burn toggle). No credential was
found by that scan; secret scanning and push protection remain enabled on GitHub.
The historical CodeQL records contained five fixed high-severity alerts and one
false positive. This evidence does not replace ongoing review or confidential
vulnerability reporting.
