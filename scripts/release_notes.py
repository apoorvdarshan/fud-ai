#!/usr/bin/env python3
"""Print reviewed release notes for an exact tag; fail rather than publish empty notes."""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('tag')
args = parser.parse_args()
text = (Path(__file__).resolve().parents[1] / 'RELEASE_NOTES.md').read_text()
sections = re.split(r'^## ', text, flags=re.MULTILINE)[1:]
for section in sections:
    title, _, body = section.partition('\n')
    if title.strip() == args.tag and args.tag != 'Unreleased':
        body = body.strip()
        if not body:
            parser.error('The release note section is empty')
        print(body)
        break
else:
    parser.error(f'Add a reviewed ## {args.tag} section to RELEASE_NOTES.md before tagging')
