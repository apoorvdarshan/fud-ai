#!/bin/sh
# Xcode run-script build phase ("Copy Workout Frames") for the calorietracker target.
#
# Copies the *runtime* part of shared/workout-vectors into the app bundle:
#
#   calorietracker.app/workout-vectors/exercise-visual-manifest.json
#   calorietracker.app/workout-vectors/<exercise>_<gender>_v2_<frame>.png   (7,000 files)
#
# Nothing else from that directory (README.md, sample-pack.txt, the legacy SVG pilot,
# editor droppings) ever reaches the IPA, mirroring the Android asset task. The copy is
# incremental — unchanged frames are skipped, stale or foreign files in the destination
# are removed — and the result is verified against the manifest's frame sequences so a
# frame the manifest names can never be silently absent from the build.
#
# Xcode provides SRCROOT, TARGET_BUILD_DIR and UNLOCALIZED_RESOURCES_FOLDER_PATH. The
# phase declares $(SRCROOT)/../shared/workout-vectors as its input directory and the
# bundle folder below as its output so it runs under ENABLE_USER_SCRIPT_SANDBOXING.
set -eu

SOURCE="${WORKOUT_VECTORS_SOURCE:-${SRCROOT}/../shared/workout-vectors}"
DESTINATION="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}/workout-vectors"
MANIFEST_NAME="exercise-visual-manifest.json"
# 875 illustrated exercises x 2 genders x 4 frames (scripts/sync_workout_visual_assets.py).
EXPECTED_FRAME_COUNT="${WORKOUT_VECTORS_EXPECTED_FRAMES:-7000}"

fail() {
    echo "error: workout frames: $*" >&2
    exit 1
}

is_runtime_asset() {
    case "$1" in
        "$MANIFEST_NAME") return 0 ;;
        *_v2_*.png) return 0 ;;
        *) return 1 ;;
    esac
}

[ -d "$SOURCE" ] || fail "corpus directory not found: $SOURCE"
[ -f "$SOURCE/$MANIFEST_NAME" ] || fail "manifest not found: $SOURCE/$MANIFEST_NAME"
mkdir -p "$DESTINATION"

# 1. Drop anything in the destination that is not a runtime asset or no longer exists
#    in the corpus (renamed frames, files from an older layout, tooling leftovers).
removed=0
for target in "$DESTINATION"/* "$DESTINATION"/.[!.]*; do
    [ -e "$target" ] || [ -L "$target" ] || continue
    name=${target##*/}
    if [ -d "$target" ] || ! is_runtime_asset "$name" || [ ! -f "$SOURCE/$name" ]; then
        rm -rf "$target"
        removed=$((removed + 1))
    fi
done

# 2. Copy the manifest and every frame whose bundled copy is missing or has a different
#    modification time (cp -p preserves it, so an up-to-date copy compares equal).
copy_if_changed() {
    source_file=$1
    target_file="$DESTINATION/${source_file##*/}"
    [ -f "$source_file" ] || fail "not a regular file: $source_file"
    if [ -L "$source_file" ]; then
        fail "canonical frames must not be symlinks: $source_file"
    fi
    if [ ! -f "$target_file" ] || [ "$source_file" -nt "$target_file" ] || [ "$target_file" -nt "$source_file" ]; then
        cp -p "$source_file" "$target_file" || fail "copy failed: $source_file"
        copied=$((copied + 1))
    fi
}

copied=0
copy_if_changed "$SOURCE/$MANIFEST_NAME"
for source_file in "$SOURCE"/*_v2_*.png; do
    [ -e "$source_file" ] || continue
    copy_if_changed "$source_file"
done

# 3. Verify the bundled frame set equals the manifest's sequences, name for name.
manifest_frames=$(grep -o '"[^"]*_v2_[0-9][0-9]*"' "$SOURCE/$MANIFEST_NAME" | tr -d '"' | sort -u)
bundled_frames=$(
    cd "$DESTINATION" || exit 1
    for frame in *_v2_*.png; do
        [ -e "$frame" ] || continue
        printf '%s\n' "${frame%.png}"
    done | sort -u
)
if [ "$manifest_frames" != "$bundled_frames" ]; then
    manifest_list=$(mktemp)
    bundled_list=$(mktemp)
    printf '%s\n' "$manifest_frames" > "$manifest_list"
    printf '%s\n' "$bundled_frames" > "$bundled_list"
    echo "error: workout frames: bundled frames differ from $MANIFEST_NAME" >&2
    echo "  in manifest only:" >&2
    comm -23 "$manifest_list" "$bundled_list" | head -8 | sed 's/^/    /' >&2
    echo "  bundled only:" >&2
    comm -13 "$manifest_list" "$bundled_list" | head -8 | sed 's/^/    /' >&2
    rm -f "$manifest_list" "$bundled_list"
    echo "  run scripts/sync_workout_visual_assets.py --check" >&2
    exit 1
fi

frame_count=$(printf '%s\n' "$bundled_frames" | grep -c .)
[ "$frame_count" -eq "$EXPECTED_FRAME_COUNT" ] \
    || fail "bundled $frame_count frames, expected $EXPECTED_FRAME_COUNT; run scripts/sync_workout_visual_assets.py --check"

echo "workout frames: bundled manifest + $frame_count frame(s) into $DESTINATION (copied $copied, removed $removed)"
