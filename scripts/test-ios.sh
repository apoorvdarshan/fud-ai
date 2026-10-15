#!/bin/bash
# Unsigned simulator tests only. This script never archives or publishes an app.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

if [[ "$(uname -m)" != arm64 ]]; then
  echo 'Fud AI simulator tests require Apple Silicon (LiteRT-LM is arm64-only).' >&2
  exit 1
fi

derived_data="${IOS_TEST_DERIVED_DATA:-$HOME/Library/Developer/Xcode/DerivedData/calorietracker-simulator-tests}"
packages="${IOS_TEST_PACKAGES:-$derived_data/SourcePackages}"
results="${IOS_TEST_RESULTS:-$derived_data/TestResults/$(date -u +%Y%m%dT%H%M%SZ)-$$}"
mkdir -p "$results"
if [[ -e "$results/Tests.xcresult" ]]; then
  echo 'Choose an unused IOS_TEST_RESULTS directory; existing results are preserved.' >&2
  exit 1
fi

xcode_version="$(xcodebuild -version)"
echo "$xcode_version"
xcode_major="$(awk '/^Xcode / { split($2, version, "."); print version[1] }' <<< "$xcode_version")"
if [[ ! "$xcode_major" =~ ^[0-9]+$ ]] || (( xcode_major < 27 )); then
  echo 'Use Xcode 27 or newer: the app uses iOS 27 FoundationModels image APIs.' >&2
  exit 1
fi

# Create a task-owned simulator: never erase or reset a developer's existing one.
simulator_id="$(python3 - <<'PY'
import json, subprocess
data = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '--json']))
def version(runtime):
    return tuple(int(part) for part in runtime['version'].split('.'))
runtimes = [r for r in data['runtimes'] if r.get('isAvailable') and
            '.iOS-' in r['identifier'] and version(r) >= (26, 2)]
if not runtimes:
    raise SystemExit('Install an iOS 26.2 or newer Simulator runtime in Xcode.')
runtime = max(runtimes, key=version)
device_types = runtime.get('supportedDeviceTypes', [])
iphones = [d for d in device_types if d.get('productFamily') == 'iPhone']
if not iphones:
    raise SystemExit('Selected runtime has no supported iPhone device type.')
device = next((d for d in iphones if d['name'] == 'iPhone 17 Pro'), iphones[0])
print(subprocess.check_output(['xcrun', 'simctl', 'create', 'Fud AI CI',
      device['identifier'], runtime['identifier']], text=True).strip())
PY
)"

# shellcheck disable=SC2329 # invoked by the EXIT trap below
cleanup() {
  # Only the simulator created by this invocation is removed.
  xcrun simctl shutdown "$simulator_id" >/dev/null 2>&1 || true
  xcrun simctl delete "$simulator_id" >/dev/null 2>&1 || true
}
trap cleanup EXIT

export TEST_RUNNER_FUDAI_TESTING=1
export SIMCTL_CHILD_FUDAI_TESTING=1

status=0
# -collect-test-diagnostics never: otherwise xcodebuild spends 10 minutes after
# the tests finish timing out while collecting simulator diagnostics. Failure
# screenshots and logs are still kept in the result bundle.
xcodebuild test \
  -project ios/calorietracker.xcodeproj \
  -scheme calorietracker \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -destination-timeout 120 \
  -derivedDataPath "$derived_data" \
  -clonedSourcePackagesDirPath "$packages" \
  -onlyUsePackageVersionsFromResolvedFile \
  -resultBundlePath "$results/Tests.xcresult" \
  -enableCodeCoverage YES \
  -parallel-testing-enabled NO \
  -maximum-concurrent-test-simulator-destinations 1 \
  -collect-test-diagnostics never \
  CODE_SIGNING_ALLOWED=NO WORKOUT_VECTORS=sample \
  2>&1 | tee "$results/xcodebuild.log" || status=$?

# xcodebuild can succeed while a bundle runs nothing (for example after a scheme
# or target change), so both bundles must have run at least one test.
if [[ -d "$results/Tests.xcresult" ]]; then
  python3 scripts/ci/check_ios_test_counts.py "$results/Tests.xcresult" \
    calorietrackerTests calorietrackerUITests || status=1
else
  echo 'error: xcodebuild produced no result bundle' >&2
  status=1
fi
echo "Results: $results/Tests.xcresult"
exit "$status"
