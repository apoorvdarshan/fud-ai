#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BATCH="cursor-shard-100-done12"
CAND="$ROOT/artifacts/workout-visual-qa/$BATCH/candidates/images"
MANIFEST="$ROOT/artifacts/workout-visual-qa/review-batches/$BATCH/$BATCH.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "Missing $MANIFEST — git fetch && git checkout cursor/cloud-agent-1788728909021-bphzd" >&2
  exit 1
fi
if [[ ! -d "$CAND" ]] || [[ $(find "$CAND" -maxdepth 1 -name '*.png' | wc -l) -lt 96 ]]; then
  echo "Missing candidate PNGs — git pull origin cursor/cloud-agent-1788728909021-bphzd" >&2
  exit 1
fi
lsof -ti :8766 2>/dev/null | xargs kill -9 2>/dev/null || true
export WORKOUT_REVIEW_PORT=8766
exec node "$ROOT/scripts/serve_cursor_batch_review.mjs" "$BATCH"
