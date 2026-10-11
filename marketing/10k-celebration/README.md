# Fud AI — 10K download celebration

A 16-second, 1080 × 1350 (4:5), 30 fps motion graphic for social sharing.
The MP4 uses H.264 video, AAC stereo audio and fast-start playback.

## Deliverables

- `exports/fud-ai-10k-celebration.mp4` — finished video with original synthesized music.
- `exports/poster.png` — matching milestone cover.

## Render

Requires Node.js, FFmpeg on PATH, and macOS Arial Bold / Avenir Next fonts.
From this directory:

```sh
npm ci
npm run render
```

Use `node render.mjs --stills` for preview images only. The renderer uses the
repository's transparent Fud AI logo and real `home.png` / `snap.png` app
screenshots. The soundtrack is synthesized locally without external samples.

## Milestone attribution

The 10K+ label reflects the saved October 11, 2026 reporting snapshot:

- 7,200 first-time App Store downloads, through October 9.
- 2,579 first-time Google Play user installs, through October 7.
- 1,050 stable Android APK GitHub release downloads, checked October 11.

Combined: 10,829 download events across App Store, Google Play and GitHub.
These are not deduplicated people or active users. Store redownloads are not
included in the App Store figure. The platform attribution appears in the video.
This is a fixed milestone asset, not a live analytics display.

Creating this asset does not publish it to any social account.
