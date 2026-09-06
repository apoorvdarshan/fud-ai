#!/usr/bin/env python3
"""Small-memory chroma extraction for deliberately green-backed generated edits.

Never use on original workout artwork or green foregrounds. Writes an unapproved
candidate and provenance only; no semantic model and no production replacement.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image


def extract(rgba):
    rgb = np.asarray(rgba.convert('RGB'), dtype=np.float32)
    red, green, blue = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    strongest = np.maximum(red, blue)
    keyed = (green > 180) & (green - strongest > 100)
    border = np.concatenate((keyed[0], keyed[-1], keyed[:, 0], keyed[:, -1]))
    if border.mean() < .90:
        raise ValueError('Requires a deliberately flat green border; not a generic background remover')
    key_color = np.median(rgb[keyed], axis=0)
    key_difference = max(float(key_color[1] - max(key_color[0], key_color[2])), 1)
    alpha = 1 - np.clip((green - strongest) / key_difference, 0, 1)
    # Generated green is near-uniform rather than exact RGB(0,255,0).
    # Remove strongly saturated key pixels outright; never amplify their noise.
    background = (green > 150) & (green - strongest > 100) & (strongest < 85)
    alpha[background | (alpha < .08)] = 0
    # Unmix the known pure-green background only, preserving red/blue and whites.
    rgb = (rgb - (1 - alpha[:, :, None]) * key_color) / np.maximum(alpha[:, :, None], .001)
    out = np.dstack((np.clip(rgb, 0, 255).astype(np.uint8), np.rint(alpha * 255).astype(np.uint8)))
    out[alpha == 0, :3] = 0
    if np.count_nonzero(alpha > .9) < alpha.size * .01:
        raise ValueError('Refusing nearly blank candidate')
    return Image.fromarray(out, 'RGBA')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generated', type=Path, required=True)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    destination = args.output.resolve()
    for forbidden in [root / 'shared', root / 'ios', root / 'android']:
        if destination.is_relative_to(forbidden):
            parser.error('Candidates must remain outside production assets')
    if destination.exists() or destination == args.generated.resolve():
        parser.error('Output must be a new candidate path')
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    source_hash = sha(args.original)
    with Image.open(args.original) as im:
        size = im.size
    with Image.open(args.generated) as im:
        if abs(im.width / im.height - size[0] / size[1]) > .01:
            parser.error('Aspect ratio differs; requires deliberate framing review')
        output = extract(im).resize(size, Image.Resampling.LANCZOS)
    destination.parent.mkdir(parents=True, exist_ok=True)
    output.save(destination)
    if sha(args.original) != source_hash:
        raise RuntimeError('Original changed while extracting')
    record = {'source_sha256': source_hash, 'candidate_sha256': sha(destination),
              'generated_sha256': sha(args.generated), 'generated_path': str(args.generated.resolve()),
              'method': 'built-in-imagegen-plus-known-green-chroma-v2', 'size': list(size),
              'alpha_extrema': list(output.getchannel('A').getextrema()),
              'acceptance': 'pending_visual_and_human_review'}
    destination.with_suffix('.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record))


if __name__ == '__main__':
    main()
