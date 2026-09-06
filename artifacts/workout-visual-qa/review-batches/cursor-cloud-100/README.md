# Cursor Cloud 100

100 exercises (batch-06 indices 11–50 + batch-07 + batch-08 indices 1–15). Script background cleanup first; Cursor GenerateImage for surgical fixes when needed.

## Parallel repair (10 shards)

```sh
cd /workspace
python3 scripts/normalize_batch_paths.py artifacts/workout-visual-qa/review-batches/cursor-cloud-100/cursor-cloud-100.json
for s in $(seq 1 10); do python3 scripts/run_cloud_worker_shard.py --shard $s & done
wait
python3 scripts/collect_cursor_cloud_candidates.py
```

## Review (localhost)

```sh
node scripts/serve_cursor_batch_review.mjs cursor-cloud-100
```

Open **http://127.0.0.1:8767** — animated original vs candidate, Approve / Needs more work.

Decisions save to `artifacts/workout-visual-qa/cursor-cloud-100/human-decisions.json`.

99+ approved exercises in `human-approved-candidates.json` are frozen and excluded from this batch.
