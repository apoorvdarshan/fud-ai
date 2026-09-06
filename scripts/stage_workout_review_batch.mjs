#!/usr/bin/env node
// Non-destructive batch staging. Existing cleanup code owns pixel processing.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const batchName = process.argv[2] || 'batch-01';
if (!/^batch-\d{2}$/.test(batchName)) throw new Error('Expected batch-NN');
const base = path.join(root, 'artifacts/workout-visual-qa/review-batches', batchName);
const batch = JSON.parse(fs.readFileSync(path.join(base, batchName + '.json')));
const stage = path.join(base, 'repair-pass-01');
const cache = path.join(root, 'artifacts/workout-visual-qa/background-full');
const overridesPath = path.join(root, 'artifacts/workout-visual-qa/background-reviewed-overrides.json');
const python = process.env.WORKOUT_REPAIR_PYTHON || '/tmp/fudai-workout-matting.FCLmtL/venv/bin/python';
const memoryLimitKiB = 2 * 1024 * 1024;
let activeChild = null;
let stopped = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  stopped = true;
  if (activeChild) activeChild.kill('SIGTERM');
});
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
fs.mkdirSync(stage, { recursive: true });
const groups = Array.from({ length: 4 }, () => []);
batch.exercises.forEach((e, i) => groups[i % 4].push(e));
const snapshot = Object.fromEntries(batch.exercises.flatMap(e => e.sourceFramePaths.map(p => {
  const name = path.basename(p); return [name, sha(path.join(root, 'shared/workout-vectors', name))];
})));
const snapshotPath = path.join(stage, 'original-hashes.json');
if (fs.existsSync(snapshotPath) && JSON.stringify(JSON.parse(fs.readFileSync(snapshotPath))) !== JSON.stringify(snapshot)) {
  throw new Error('Original artwork changed since staging began; use a new repair pass');
}
fs.writeFileSync(snapshotPath, JSON.stringify(snapshot, null, 2) + '\n');
// Shards preserve existing cache locations, but run strictly one at a time.
// Never load four segmentation models concurrently on the user's Mac.
const results = [];
for (const [index, entries] of groups.entries()) {
  if (stopped) break;
  const pressure = execFileSync('/usr/bin/memory_pressure', ['-Q'], { encoding: 'utf8' });
  const free = Number(pressure.match(/free percentage:\s*(\d+)/)?.[1]);
  if (!Number.isFinite(free) || free < 35) throw new Error('Not starting cleanup: memory headroom is below 35% or unavailable');
  const output = path.join(stage, 'shard-' + index);
  for (const dir of ['images', 'records', 'masks']) fs.mkdirSync(path.join(output, dir), { recursive: true });
  let reused = 0;
  for (const e of entries) for (const p of e.sourceFramePaths) {
    const name = path.basename(p), recordName = name.replace(/\.png$/, '.json');
    const image = path.join(cache, 'images', name), record = path.join(cache, 'records', recordName);
    if (fs.existsSync(path.join(output, 'images', name)) || !fs.existsSync(image) || !fs.existsSync(record)) continue;
    const r = JSON.parse(fs.readFileSync(record));
    // Non-empty reviewed overrides are revalidated by the cleanup program itself.
    if (r.source_sha256 !== snapshot[name] || r.candidate_sha256 !== sha(image) || r.recipe !== 'pale-neural-guard-v2') continue;
    fs.copyFileSync(image, path.join(output, 'images', name));
    fs.copyFileSync(record, path.join(output, 'records', recordName));
    const maskName = snapshot[name] + '.png';
    if (fs.existsSync(path.join(cache, 'masks', maskName))) fs.copyFileSync(path.join(cache, 'masks', maskName), path.join(output, 'masks', maskName));
    reused++;
  }
  const log = fs.openSync(path.join(output, 'run.log'), 'a');
  const args = [path.join(root, 'scripts/repair_workout_visual_backgrounds.py'), '--output', output, '--overrides', overridesPath, '--threads', '1', ...entries.flatMap(e => ['--exercise', e.exerciseId])];
  console.log(`Shard ${index}: ${entries.length} exercises; ${reused} cached frames supplied for revalidation`);
  const code = await new Promise((resolve, reject) => {
    const child = spawn(python, args, { cwd: root, stdio: ['ignore', log, log] });
    activeChild = child;
    const monitor = setInterval(() => {
      try {
        const rss = Number(execFileSync('/bin/ps', ['-p', String(child.pid), '-o', 'rss='], { encoding: 'utf8' }).trim());
        if (rss > memoryLimitKiB) {
          console.error(`Stopping cleanup PID ${child.pid}: RSS exceeds 2 GiB`);
          stopped = true; child.kill('SIGTERM');
        }
      } catch { /* Child exit is handled below. */ }
    }, 1000);
    child.on('error', error => { clearInterval(monitor); activeChild = null; reject(error); });
    child.on('exit', code => { clearInterval(monitor); activeChild = null; resolve(code ?? 1); });
  });
  fs.closeSync(log);
  results.push({ shard: index, code, exercises: entries.length });
  if (code !== 0) break;
}
for (const [name, digest] of Object.entries(snapshot)) if (sha(path.join(root, 'shared/workout-vectors', name)) !== digest) throw new Error('Original changed: ' + name);
const result = { completedAt: new Date().toISOString(), results, originalsUnchanged: true, accepted: 0, status: results.length === groups.length && results.every(x => x.code === 0) ? 'cleanup_candidates_only' : 'partial_failure', note: 'Background cleanup is not anatomy, alignment, visual verification, or human approval.' };
fs.writeFileSync(path.join(stage, 'staging-result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
if (stopped || results.length !== groups.length || results.some(x => x.code !== 0)) process.exitCode = 1;
