# Batch 01 repair and human review

Scope: all 50 exercises (400 frames), starting with 20 critical and 30 major.
The user has authorized candidate repairs before human review. Production images
must not be replaced until the user approves the exact reviewed sets.

## Checkpoint — 2026-09-06

- 207/400 cached candidate frames match recorded original and candidate hashes.
- 25 complete candidate sets, four partial sets, 21 missing sets.
- This is integrity coverage, **not 25 repaired/accepted exercises**.
- All 400 originals match the repair pass's original-hash snapshot.
- Visual review of Glute Bridge, Bottoms Up and Cable Wrist Curl found unresolved
  anatomy/mechanics and background errors. Cleanup alone is insufficient.
- The first Rear Delt Row imagegen candidate contained a painted checkerboard
  and was rejected, not exposed as an approved candidate.
- The initial four concurrent cleanup processes stopped. Old `running` state
  files are stale; they are not evidence of live jobs.

## Resource limit

`scripts/stage_workout_review_batch.mjs` now runs its existing cache shards
sequentially, one segmentation process with one inference thread. It checks
memory headroom before each shard and stops a child exceeding 2 GiB resident
memory. SIGINT/SIGTERM are forwarded to the current child. Do not launch the
underlying Python script in parallel or use the old four-process approach.

## Animated human review

Run `node scripts/serve_workout_review.mjs batch-01` and open the printed loopback
URL. Original and candidate frames can be compared in motion, with gender,
background and frame controls. Missing candidates and critical warnings remain
explicit. Export human decisions with their exact candidate hashes.

No export or review button promotes images into the app. A separate, explicit
human-approved promotion is required. No new phone build has been installed.
# Local Luna Max batch dispatch — 2026-09-06

50 separate local projectless tasks launched, one exercise per task, Luna Max. Receipts: repair-pass-01/luna-launches/001.json through 050.json. Reports: repair-pass-01/luna-workers/NNN/result.json. Workers write files only and must not message the coordinator. The coordinator reviews outputs and returns failed checks to the same task. No production promotion or Batch 02 without human approval.

Active heartbeat: review-workout-batch-01-workers, every five minutes in the coordinator task. It checks reports, performs parent review, and notifies when all 50 are ready for human animated review. Launch count is not completion count. No local ML segmentation allowed in these workers.
