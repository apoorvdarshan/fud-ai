#!/usr/bin/env python3
"""Generate Android adaptive launcher icon foreground assets and XML."""

from __future__ import annotations

import os
import re
from pathlib import Path

from PIL import Image

SCRIPT_DIR = Path(__file__).resolve().parent
RES_DIR = SCRIPT_DIR.parent / "app" / "src" / "main" / "res"
ANYDPI_DIR = RES_DIR / "mipmap-anydpi-v26"

DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# Keep artwork inside the 66dp adaptive-icon safe zone (108dp canvas).
SAFE_ZONE_RATIO = 0.60
BACKGROUND_THRESHOLD = 28


def is_background_pixel(r: int, g: int, b: int, a: int) -> bool:
    return a < 16 or (r <= BACKGROUND_THRESHOLD and g <= BACKGROUND_THRESHOLD and b <= BACKGROUND_THRESHOLD)


def extract_foreground(legacy: Image.Image) -> Image.Image:
    rgba = legacy.convert("RGBA")
    width, height = rgba.size
    foreground = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    src = rgba.load()
    dst = foreground.load()
    for y in range(height):
        for x in range(width):
            pixel = src[x, y]
            if not is_background_pixel(*pixel):
                dst[x, y] = pixel
    return foreground


def fit_foreground(legacy: Image.Image, target_size: int) -> Image.Image:
    extracted = extract_foreground(legacy)
    bbox = extracted.getbbox()
    if bbox is None:
        return Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))

    cropped = extracted.crop(bbox)
    max_logo_size = max(1, int(target_size * SAFE_ZONE_RATIO))
    crop_w, crop_h = cropped.size
    scale = min(max_logo_size / crop_w, max_logo_size / crop_h)
    scaled_w = max(1, int(round(crop_w * scale)))
    scaled_h = max(1, int(round(crop_h * scale)))
    scaled = cropped.resize((scaled_w, scaled_h), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (target_size, target_size), (0, 0, 0, 0))
    offset_x = (target_size - scaled_w) // 2
    offset_y = (target_size - scaled_h) // 2
    canvas.paste(scaled, (offset_x, offset_y), scaled)
    return canvas


def discover_variants() -> list[str]:
    sample_dir = RES_DIR / "mipmap-xxxhdpi"
    variants: list[str] = []
    for path in sorted(sample_dir.glob("ic_launcher*.png")):
        name = path.stem
        if name.endswith("_round"):
            continue
        variants.append(name)
    return variants


def foreground_name(variant: str) -> str:
    if variant == "ic_launcher":
        return "ic_launcher_foreground"
    return f"{variant}_foreground"


def write_adaptive_icon_xml(icon_name: str, foreground: str) -> None:
    content = f"""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/{foreground}" />
</adaptive-icon>
"""
    (ANYDPI_DIR / f"{icon_name}.xml").write_text(content, encoding="utf-8")


def main() -> None:
    variants = discover_variants()
    if not variants:
        raise SystemExit("No launcher icon variants found.")

    ANYDPI_DIR.mkdir(parents=True, exist_ok=True)

    for variant in variants:
        foreground = foreground_name(variant)
        for density, (_, adaptive_px) in DENSITIES.items():
            legacy_path = RES_DIR / f"mipmap-{density}" / f"{variant}.png"
            if not legacy_path.exists():
                raise FileNotFoundError(f"Missing legacy icon: {legacy_path}")

            legacy = Image.open(legacy_path)
            fitted = fit_foreground(legacy, adaptive_px)
            output_path = RES_DIR / f"mipmap-{density}" / f"{foreground}.png"
            fitted.save(output_path, format="PNG", optimize=True)

        write_adaptive_icon_xml(variant, foreground)
        round_name = f"{variant}_round"
        round_legacy = RES_DIR / "mipmap-xxxhdpi" / f"{round_name}.png"
        if round_legacy.exists():
            write_adaptive_icon_xml(round_name, foreground)

    print(f"Generated adaptive icons for {len(variants)} variants.")


if __name__ == "__main__":
    main()
