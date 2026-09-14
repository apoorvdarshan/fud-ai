#!/usr/bin/env python3
"""Copy marketing PNGs into store/metadata/screenshots/ for Play + ASC upload paths."""
from __future__ import annotations

import argparse
import io
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "web" / "assets" / "screenshots"
PLAY_PHONE_DIR = ROOT / "store" / "metadata" / "screenshots" / "play" / "phone"
IOS_67_DIR = ROOT / "store" / "metadata" / "screenshots" / "ios" / "6.7"

# App Store Connect 6.7" iPhone (portrait)
ASC_IPHONE_67_ACCEPTED = ((1290, 2796), (1284, 2778))
ASC_IPHONE_67_TARGET = ASC_IPHONE_67_ACCEPTED[0]

# Google Play phone screenshots (portrait ~9:16)
PLAY_MIN_SIDE = 320
PLAY_MAX_SIDE = 3840
PLAY_PORTRAIT_ASPECT = 9 / 16  # width / height
PLAY_ASPECT_TOLERANCE = 0.08
# Play Console phone screenshot cap (App Store allows 10).
PLAY_PHONE_MAX = 8


def fail(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def uploads_enabled() -> bool:
    return os.environ.get("STORE_UPLOAD_SCREENSHOTS", "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def try_import_pillow():
    try:
        from PIL import Image  # type: ignore[import-untyped]

        return Image
    except ImportError:
        return None


def read_png_size(path: Path) -> tuple[int, int]:
    Image = try_import_pillow()
    if Image is not None:
        with Image.open(path) as img:
            return img.size
    # PNG IHDR without Pillow
    with path.open("rb") as f:
        header = f.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"not a PNG: {path}")
    w = int.from_bytes(header[16:20], "big")
    h = int.from_bytes(header[20:24], "big")
    return w, h


def fit_and_pad_rgba(
    Image, src, target_w: int, target_h: int, fill=(0, 0, 0, 255)
):
    src_w, src_h = src.size
    scale = min(target_w / src_w, target_h / src_h)
    new_w = max(1, int(round(src_w * scale)))
    new_h = max(1, int(round(src_h * scale)))
    resized = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (target_w, target_h), fill)
    ox = (target_w - new_w) // 2
    oy = (target_h - new_h) // 2
    canvas.paste(resized, (ox, oy), resized if resized.mode == "RGBA" else None)
    return canvas


def prepare_asc_png(Image, path: Path) -> bytes:
    target_w, target_h = ASC_IPHONE_67_TARGET
    with Image.open(path) as img:
        img = img.convert("RGBA")
        w, h = img.size
        if (w, h) in ASC_IPHONE_67_ACCEPTED:
            out = io.BytesIO()
            img.save(out, format="PNG")
            return out.getvalue()
        out_img = fit_and_pad_rgba(Image, img, target_w, target_h)
    out = io.BytesIO()
    out_img.save(out, format="PNG")
    return out.getvalue()


def play_portrait_ok(width: int, height: int) -> bool:
    if width <= 0 or height <= 0 or height <= width:
        return False
    if min(width, height) < PLAY_MIN_SIDE or max(width, height) > PLAY_MAX_SIDE:
        return False
    aspect = width / height
    return abs(aspect - PLAY_PORTRAIT_ASPECT) <= PLAY_ASPECT_TOLERANCE


def validate_play_png(path: Path, *, width: int, height: int) -> None:
    if not play_portrait_ok(width, height):
        fail(
            f"{path.name}: Play phone screenshot must be portrait ~9:16 with each side "
            f"between {PLAY_MIN_SIDE} and {PLAY_MAX_SIDE}px (got {width}x{height})"
        )


def clear_destination_pngs(*dest_dirs: Path) -> None:
    for dest_dir in dest_dirs:
        dest_dir.mkdir(parents=True, exist_ok=True)
        for stale in dest_dir.glob("*.png"):
            stale.unlink()


def validate_asc_png(path: Path, *, width: int, height: int) -> None:
    if (width, height) not in ASC_IPHONE_67_ACCEPTED:
        accepted = ", ".join(f"{w}x{h}" for w, h in ASC_IPHONE_67_ACCEPTED)
        fail(
            f"{path.name}: ASC 6.7\" screenshot must be {accepted} "
            f"(got {width}x{height}). Install Pillow (requirements-store.txt) "
            "so staging can resize automatically, or pre-export at the required size."
        )


def stage(*, dry_run: bool) -> list[Path]:
    if not SOURCE.is_dir():
        fail(f"screenshot source missing: {SOURCE.relative_to(ROOT)}")

    pngs = sorted(SOURCE.glob("*.png"))
    if not pngs:
        fail(f"no PNG files under {SOURCE.relative_to(ROOT)}")

    will_upload = uploads_enabled()
    Image = try_import_pillow()
    play_pngs = pngs[:PLAY_PHONE_MAX]
    if will_upload and Image is None:
        for path in play_pngs:
            w, h = read_png_size(path)
            validate_play_png(path, width=w, height=h)
        for path in pngs:
            w, h = read_png_size(path)
            validate_asc_png(path, width=w, height=h)

    if not dry_run:
        clear_destination_pngs(PLAY_PHONE_DIR, IOS_67_DIR)

    if len(pngs) > PLAY_PHONE_MAX:
        skipped = ", ".join(p.name for p in pngs[PLAY_PHONE_MAX:])
        print(
            f"note: Play phone cap is {PLAY_PHONE_MAX}; "
            f"staging ASC-only for: {skipped}"
        )

    copied: list[Path] = []
    for path in pngs:
        w, h = read_png_size(path)
        for_play = path in play_pngs

        if dry_run:
            dests = [IOS_67_DIR.relative_to(ROOT)]
            if for_play:
                dests.insert(0, PLAY_PHONE_DIR.relative_to(ROOT))
            print(
                f"dry-run: would stage {path.name} ({w}x{h}) → "
                + " and ".join(str(d) for d in dests)
            )
            continue

        IOS_67_DIR.mkdir(parents=True, exist_ok=True)

        if for_play:
            PLAY_PHONE_DIR.mkdir(parents=True, exist_ok=True)
            play_dest = PLAY_PHONE_DIR / path.name
            if will_upload:
                if Image is not None and not play_portrait_ok(w, h):
                    with Image.open(path) as img:
                        img = img.convert("RGBA")
                        target_h = min(PLAY_MAX_SIDE, max(h, PLAY_MIN_SIDE * 16 // 9))
                        target_w = int(round(target_h * PLAY_PORTRAIT_ASPECT))
                        target_w = min(PLAY_MAX_SIDE, max(PLAY_MIN_SIDE, target_w))
                        play_img = fit_and_pad_rgba(Image, img, target_w, target_h)
                    out = io.BytesIO()
                    play_img.save(out, format="PNG")
                    play_bytes = out.getvalue()
                else:
                    validate_play_png(path, width=w, height=h)
                    play_bytes = path.read_bytes()
            else:
                play_bytes = path.read_bytes()

            play_dest.write_bytes(play_bytes)
            copied.append(play_dest)

        ios_dest = IOS_67_DIR / path.name
        if Image is not None:
            ios_bytes = prepare_asc_png(Image, path)
        elif will_upload:
            validate_asc_png(path, width=w, height=h)
            ios_bytes = path.read_bytes()
        else:
            ios_bytes = path.read_bytes()
        ios_dest.write_bytes(ios_bytes)
        copied.append(ios_dest)

    if not dry_run:
        print(
            f"staged {len(play_pngs)} Play phone + {len(pngs)} ASC 6.7\" screenshot(s)"
        )
    return copied


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print planned copies only (no filesystem changes)",
    )
    args = parser.parse_args()
    stage(dry_run=args.dry_run)


if __name__ == "__main__":
    main()
