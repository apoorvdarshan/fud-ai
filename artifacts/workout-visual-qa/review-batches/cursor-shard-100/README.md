# Cursor shard 100

Next 100 unapproved exercises after done30 / next-10. Same cheap path as the 30: **one local process per 10 exercises**, script background cleanup only. No Cloud Agents. No GenerateImage.

## Repair (10 shards)

```sh
python3 scripts/build_cursor_shard_100.py
for s in $(seq 1 10); do
  python3 scripts/run_cloud_worker_shard.py --batch cursor-shard-100 --shard "$s"
done
python3 scripts/collect_cursor_cloud_candidates.py --batch cursor-shard-100 --all
```

## Review (localhost)

```sh
scripts/start_shard100_review.sh
```

Open **http://127.0.0.1:8766**. Approve saves the decision only — app images stay unchanged.
