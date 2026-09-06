#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BATCH="cursor-shard-100"
CAND="$ROOT/artifacts/workout-visual-qa/$BATCH/candidates/images"
MANIFEST="$ROOT/artifacts/workout-visual-qa/review-batches/$BATCH/$BATCH.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "Missing $MANIFEST" >&2
  exit 1
fi
if [[ ! -d "$CAND" ]] || [[ $(find "$CAND" -maxdepth 1 -name '*.png' | wc -l) -lt 800 ]]; then
  echo "Missing candidate PNGs — run collect_cursor_cloud_candidates.py --batch $BATCH --all" >&2
  exit 1
fi
lsof -ti :8766 2>/dev/null | xargs kill -9 2>/dev/null || true
export WORKOUT_REVIEW_PORT=8766
exec node "$ROOT/scripts/serve_cursor_batch_review.mjs" "$BATCH"
