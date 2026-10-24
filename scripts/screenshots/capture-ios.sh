#!/bin/bash
# README and website screenshots from the Debug app on a headless iOS simulator, with the
# demo data and screens in ios/calorietracker/Services/ScreenshotMode.swift.
#
#   scripts/screenshots/capture-ios.sh [output-dir]
#
# Uses (and creates once) a dedicated "Fud AI Screenshots" iPhone 17 Pro simulator, never
# opens Simulator.app, and shuts the simulator down when done. Output: one PNG per scene.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/scripts/screenshots/out}"
DEVICE_NAME="Fud AI Screenshots"
DEVICE_TYPE="com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
BUNDLE="com.apoorvdarshan.calorietracker.debug"
DERIVED="${DERIVED:-$HOME/Library/Developer/Xcode/DerivedData/calorietracker-screenshots}"
# scene:file
SCENES=(
  home:home
  voice:live-voice
  food:food
  nutrition:nutrition
  progress:progress
  coach:coach
  workouts:workouts
  aiProviders:ai-providers
  voiceSettings:voice-settings
)

mkdir -p "$OUT"
UDID=$(xcrun simctl list devices available | grep -F "$DEVICE_NAME (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/' || true)
if [ -z "$UDID" ]; then
  # The newest runtime that offers an iPhone 17 Pro; a device-specific runtime (iOS 27.1
  # ships only the iPhone Duo) can be newer and would reject this device type.
  RUNTIME=$(xcrun simctl list --json runtimes available | python3 -I -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if ".iOS-" in r["identifier"] and
            any(d["identifier"] == sys.argv[1] for d in r.get("supportedDeviceTypes", []))]
runtimes.sort(key=lambda r: tuple(int(p) for p in r["version"].split(".")))
print(runtimes[-1]["identifier"] if runtimes else "")' "$DEVICE_TYPE")
  [ -n "$RUNTIME" ] || { echo "No installed iOS runtime supports $DEVICE_TYPE." >&2; exit 1; }
  UDID=$(xcrun simctl create "$DEVICE_NAME" "$DEVICE_TYPE" "$RUNTIME")
fi
echo "Simulator $UDID"

# Always leave the simulator as we found it, including after a failed build or capture.
cleanup() {
  status=$?
  xcrun simctl terminate "$UDID" "$BUNDLE" >/dev/null 2>&1 || true
  xcrun simctl status_bar "$UDID" clear >/dev/null 2>&1 || true
  xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
  exit $status
}
trap cleanup EXIT INT TERM

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" appearance dark
xcrun simctl status_bar "$UDID" override --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100

xcodebuild -project "$ROOT/ios/calorietracker.xcodeproj" -scheme calorietracker -configuration Debug \
  -destination "id=$UDID" -derivedDataPath "$DERIVED" ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build -quiet
xcrun simctl install "$UDID" "$DERIVED/Build/Products/Debug-iphonesimulator/calorietracker.app"

DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)
mkdir -p "$DATA/Documents/ScreenshotDemo"
cp "$ROOT/scripts/screenshots/demo-photos/"*.jpg "$DATA/Documents/ScreenshotDemo/"

launch() {
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  xcrun simctl launch "$UDID" "$BUNDLE" --ui-testing --ui-testing-reset --ui-testing-onboarded --screenshot-demo \
    -screenshotScene "$1" -AppleLanguages "(en)" -AppleLocale en_US \
    -didPromptReviewAfterFirstLog YES -WorkoutVectorsBaseURL none >/dev/null
}

# The first launch after an install is slow; warm up so the first capture isn't blank.
launch home
sleep 12

for pair in "${SCENES[@]}"; do
  scene="${pair%%:*}"; file="${pair##*:}"
  captured=0
  for attempt in 1 2 3; do
    launch "$scene"
    sleep "${SCREENSHOT_WAIT:-8}"
    xcrun simctl io "$UDID" screenshot --type=png "$OUT/$file.png" >/dev/null 2>&1
    # A frame caught mid-launch is nearly all black and compresses to almost nothing.
    if [ "$(stat -f %z "$OUT/$file.png")" -gt 150000 ]; then captured=1; break; fi
    echo "retrying $file (blank frame)"
  done
  if [ "$captured" -ne 1 ]; then
    echo "error: $file stayed blank after 3 attempts" >&2
    exit 1
  fi
  echo "captured $file"
done
