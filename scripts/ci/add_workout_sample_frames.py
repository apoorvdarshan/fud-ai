#!/usr/bin/env python3
"""Add only the workout sample-pack frames to a sparse CI checkout.

CI checks out shared/workout-vectors without its ~7,000 *_v2_*.png frames
(1.2 GB). Debug builds, unit tests and instrumentation tests only use the small
sample pack listed in sample-pack.txt, so this adds exactly those frames.
"""
import base64
import os
import pathlib
import re
import subprocess

SAMPLE_PACK = pathlib.Path("shared/workout-vectors/sample-pack.txt")

sparse = subprocess.run(
    ["git", "config", "--get", "core.sparseCheckout"], capture_output=True, text=True
).stdout.strip()
if sparse != "true":
    print("full checkout: every workout frame is already present; nothing to add")
    raise SystemExit(0)

patterns = []
for line in SAMPLE_PACK.read_text().splitlines():
    exercise = line.split("#", 1)[0].strip()
    if not exercise:
        continue
    if not re.fullmatch(r"[A-Za-z0-9_-]+", exercise):
        raise SystemExit(f"Invalid exercise name in sample pack: {exercise!r}")
    for gender in ("male", "female"):
        for frame in range(4):
            patterns.append(f"/shared/workout-vectors/{exercise}_{gender}_v2_{frame}.png")
if not patterns:
    raise SystemExit("Workout sample pack must not be empty")

git_env = os.environ.copy()
token = git_env.pop("WORKOUT_SAMPLE_CHECKOUT_TOKEN", None)
if token:
    # A blobless checkout can fetch the frames on demand. On GitHub Actions,
    # checkout deliberately removes its credentials before this step runs.
    # Authenticate only this Git command and its lazy-fetch child processes.
    count = int(git_env.get("GIT_CONFIG_COUNT", "0"))
    auth = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    git_env[f"GIT_CONFIG_KEY_{count}"] = "http.https://github.com/.extraheader"
    git_env[f"GIT_CONFIG_VALUE_{count}"] = f"AUTHORIZATION: basic {auth}"
    git_env["GIT_CONFIG_COUNT"] = str(count + 1)

subprocess.run(
    ["git", "sparse-checkout", "add", "--stdin"],
    input="\n".join(patterns) + "\n",
    text=True,
    check=True,
    env=git_env,
)
missing = [p for p in patterns if not pathlib.Path(p.lstrip("/")).is_file()]
if missing:
    raise SystemExit(f"{len(missing)} sample frame(s) missing after checkout, e.g. {missing[0]}")
print(f"added {len(patterns)} workout sample frames")
