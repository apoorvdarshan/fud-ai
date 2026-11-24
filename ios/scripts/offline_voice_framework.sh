#!/bin/sh
# llama.cpp's release supplies a device slice, not a simulator slice. The
# simulator bridge needs no download or dylib.
set -eu

[ "${PLATFORM_NAME:-}" = "iphoneos" ] || exit 0
root="${DERIVED_FILE_DIR:?}/OfflineVoice"
tag=b11960
checksum=feba05e0b7cb5e682ae3354abb455bf9723ae2cc95c69e24d073264e6a34d759
framework="$root/llama.framework"

case "${1:-}" in
    prepare)
        if [ -f "$framework/llama" ] && [ -d "$root/llama.framework.dSYM" ] && [ -f "$root/checksum" ] &&
            [ "$(cat "$root/checksum")" = "$checksum" ]; then
            exit 0
        fi
        mkdir -p "$root"
        work="$(mktemp -d "$root/.prepare.XXXXXXXX")"
        trap 'rm -rf "$work"' EXIT
        curl --proto '=https' --proto-redir '=https' -fsSL --retry 2 --max-time 180 \
            "https://github.com/ggml-org/llama.cpp/releases/download/$tag/llama-$tag-xcframework.zip" \
            -o "$work/runtime.zip"
        if [ "$(shasum -a 256 "$work/runtime.zip" | cut -d ' ' -f 1)" != "$checksum" ]; then
            echo "error: Unexpected llama.cpp archive checksum" >&2
            exit 1
        fi
        ditto -x -k "$work/runtime.zip" "$work"
        slice="$work/build-apple/llama.xcframework/ios-arm64"
        test -f "$slice/llama.framework/llama"
        test -f "$slice/dSYMs/llama.dSYM/Contents/Resources/DWARF/llama"
        rm -rf "$framework" "$root/llama.framework.dSYM"
        mv "$slice/llama.framework" "$framework"
        mv "$slice/dSYMs/llama.dSYM" "$root/llama.framework.dSYM"
        printf '%s\n' "$checksum" > "$root/checksum"
        ;;
    embed)
        dest="${TARGET_BUILD_DIR:?}/${FRAMEWORKS_FOLDER_PATH:?}/llama.framework"
        test -f "$framework/llama"
        mkdir -p "$(dirname "$dest")"
        rm -rf "$dest"
        ditto "$framework" "$dest"
        # Headers and module maps are build inputs, not part of the app bundle.
        rm -rf "$dest/Headers" "$dest/PrivateHeaders" "$dest/Modules"
        if [ "${CODE_SIGNING_ALLOWED:-NO}" = "YES" ] && [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
            codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --timestamp=none "$dest"
        fi
        if [ "${DEBUG_INFORMATION_FORMAT:-}" = "dwarf-with-dsym" ] &&
            [ -n "${DWARF_DSYM_FOLDER_PATH:-}" ] && [ -d "$root/llama.framework.dSYM" ]; then
            ditto "$root/llama.framework.dSYM" "$DWARF_DSYM_FOLDER_PATH/llama.framework.dSYM"
        fi
        ;;
    *) echo "error: expected prepare or embed" >&2; exit 64 ;;
esac
