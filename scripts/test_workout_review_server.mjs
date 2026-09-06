import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';

const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
test('combined review queue routes decisions to the correct batch and hides decided revisions', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workout-review-test-'));
  const write = (file, value) => {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value));
  };
  const scripts = path.join(root, 'scripts');
  fs.mkdirSync(scripts);
  for (const name of ['serve_workout_review.mjs', 'workout_review_state.mjs', 'workout_review.html']) {
    fs.copyFileSync(new URL(name, import.meta.url), path.join(scripts, name));
  }
  const ids = ['First', 'Second'];
  const batches = path.join(root, 'artifacts/workout-visual-qa/review-batches');
  for (const [i, id] of ids.entries()) {
    const batch = `batch-0${i + 1}`, stage = path.join(batches, batch, 'repair-pass-01');
    const collected = path.join(stage, 'parent-collected/001');
    const frames = Array.from({ length: 8 }, (_, n) => {
      const filename = `${id}_${n}.png`, original = path.join(root, 'shared/workout-vectors', filename);
      const candidate = path.join(collected, filename);
      write(original, `original-${id}-${n}`); write(candidate, `candidate-${id}-${n}`);
      return { filename, sourcePath: original, sourceSha256: hash(fs.readFileSync(original)), candidatePath: candidate, candidateSha256: hash(fs.readFileSync(candidate)), method: 'test-fixture' };
    });
    const report = JSON.stringify({ frames });
    write(path.join(collected, 'worker-result.json'), report);
    write(path.join(stage, 'parent-review-passed.json'), { checks: [{ index: 1, exerciseId: id, status: 'parent_pass_human_pending', reportSha256: hash(report), frames }] });
    write(path.join(batches, batch, `${batch}.json`), { exercises: [{ index: 1, exerciseId: id, sourceFramePaths: frames.map(f => f.sourcePath) }] });
  }
  write(path.join(root, 'shared/workout-vectors/exercise-visual-manifest.json'), { exercises: ids.map(exerciseId => ({ exerciseId })) });
  const child = spawn(process.execPath, [path.join(scripts, 'serve_workout_review.mjs')], { env: { ...process.env, WORKOUT_REVIEW_PORT: '0' }, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    const origin = await new Promise((resolve, reject) => {
      let output = '';
      const timer = setTimeout(() => reject(new Error('Review server startup timeout')), 10000);
      child.once('error', reject);
      child.once('exit', code => { clearTimeout(timer); reject(new Error(`Server exited ${code}`)); });
      child.stdout.on('data', chunk => {
        output += chunk;
        const match = output.match(/http:\/\/127\.0\.0\.1:\d+/);
        if (match) { clearTimeout(timer); resolve(match[0]); }
      });
    });
    const initial = await (await fetch(origin + '/manifest.json')).json();
    assert.equal(initial.batch, 'all');
    assert.deepEqual(initial.rows.map(r => r.id), ids);
    assert.deepEqual(initial.rows.map(r => r.batch), ['batch-01', 'batch-02']);
    const row = initial.rows[1];
    assert.equal((await fetch(origin + row.candidates[0])).status, 200);
    const payload = { exerciseId: row.id, batch: 'batch-01', decision: 'approve_candidate', notes: '', sourceHashes: row.sourceHashes, candidateHashes: row.hashes };
    assert.equal((await fetch(origin + '/decision', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })).status, 403);
    const result = await fetch(origin + '/decision', { method: 'POST', headers: { Origin: origin, 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    assert.equal(result.status, 200);
    assert.equal(fs.existsSync(path.join(batches, 'batch-01/repair-pass-01/human-decisions.json')), false);
    const saved = JSON.parse(fs.readFileSync(path.join(batches, 'batch-02/repair-pass-01/human-decisions.json')));
    assert.equal(saved.batch, 'batch-02'); assert.equal(saved.decisions.Second.decision, 'approve_candidate');
    const next = await (await fetch(origin + '/manifest.json')).json();
    assert.deepEqual(next.rows.map(r => r.id), ['First']);
    assert.equal(next.totals.approved, 1); assert.equal(next.totals.readyForReview, 1);
    const exported = await (await fetch(origin + '/decisions.json')).json();
    assert.equal(exported.decisions.Second.decision, 'approve_candidate');
  } finally {
    if (child.exitCode === null) {
      const exited = new Promise(resolve => child.once('exit', resolve));
      child.kill('SIGTERM'); await exited;
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
});
