import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export const decisionValues = ['approve_candidate', 'needs_more_work', 'keep_original', 'pending', 'defer'];
export const hashFile = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
export function readJson(file, fallback) { try { return JSON.parse(fs.readFileSync(file)); } catch { return fallback; } }
export function matchesFrames(decision, row) {
  return JSON.stringify(decision?.sourceHashes) === JSON.stringify(row.sourceHashes) && JSON.stringify(decision?.candidateHashes) === JSON.stringify(row.hashes);
}
export function validateDecision(input, row) {
  if (!row || input.exerciseId !== row.id || !decisionValues.includes(input.decision)) throw new Error('Unknown exercise or decision');
  if (!matchesFrames(input, row)) throw new Error('Candidate changed. Refresh before reviewing.');
  if (typeof input.notes !== 'string' || input.notes.length > 10000) throw new Error('Notes must be text, at most 10000 characters');
  return { exerciseId: row.id, decision: input.decision, notes: input.notes, sourceHashes: row.sourceHashes, candidateHashes: row.hashes, reviewedAt: new Date().toISOString() };
}
export function reviewTotals(ids, started, approved, ready) {
  const total = ids.size;
  const approvedCount = [...ids].filter(id => approved.has(id)).length;
  const inProgress = [...ids].filter(id => !approved.has(id) && (started.has(id) || ready.has(id))).length;
  return { total, approved: approvedCount, inProgress, notStarted: total - approvedCount - inProgress, readyForReview: [...ids].filter(id => ready.has(id) && !approved.has(id)).length };
}
export function collectReviewTotals(root) {
  const ids = new Set(readJson(path.join(root, 'shared/workout-vectors/exercise-visual-manifest.json'), { exercises: [] }).exercises.map(e => e.exerciseId));
  const started = new Set(), approved = new Set(), ready = new Set();
  const batches = path.join(root, 'artifacts/workout-visual-qa/review-batches');
  for (const name of fs.readdirSync(batches).filter(n => /^batch-\d{2}$/.test(n))) {
    const stage = path.join(batches, name, 'repair-pass-01');
    if (!fs.existsSync(stage)) continue;
    const launches = path.join(stage, 'luna-launches');
    if (fs.existsSync(launches)) for (const file of fs.readdirSync(launches).filter(n => n.endsWith('.json'))) {
      const launch = readJson(path.join(launches, file), null);
      if (launch?.response?.isError === false && launch.response.content?.some(c => c.type === 'text' && readTextJson(c.text)?.threadId)) started.add(launch.exerciseId);
    }
    const decisions = readJson(path.join(stage, 'human-decisions.json'), {}).decisions || {};
    const exercises = readJson(path.join(batches, name, `${name}.json`), {}).exercises || [];
    const chat = fs.readdirSync(stage).filter(n => /^human-approval.*\.json$/.test(n)).flatMap(n => readJson(path.join(stage, n), {}).decisions || []);
    for (const check of readJson(path.join(stage, 'parent-review-passed.json'), {}).checks || []) {
      if (!['parent_pass_human_pending', 'user_requested_review'].includes(check.status) || check.frames?.length !== 8) continue;
      // Saved web decisions use the manifest order, not the worker's report order.
      const exercise = exercises.find(e => e.index === check.index && e.exerciseId === check.exerciseId);
      const frames = exercise?.sourceFramePaths?.map(file => check.frames.find(f => f.filename === path.basename(file)));
      if (frames?.length !== 8 || frames.some(f => !f) || new Set(frames.map(f => f.filename)).size !== 8) continue;
      const row = { sourceHashes: frames.map(f => f.sourceSha256), hashes: frames.map(f => f.candidateSha256) };
      // A stale source invalidates approval and readiness; generated files stay untouched.
      const collected = path.join(stage, 'parent-collected', String(check.index).padStart(3, '0'));
      if (!frames.every(f => path.basename(f.filename) === f.filename && fs.existsSync(path.join(root, 'shared/workout-vectors', f.filename)) && hashFile(path.join(root, 'shared/workout-vectors', f.filename)) === f.sourceSha256 && fs.existsSync(path.join(collected, f.filename)) && hashFile(path.join(collected, f.filename)) === f.candidateSha256)) continue;
      const web = decisions[check.exerciseId];
      const renewedReview = check.reviewRequestedAt && (!web?.reviewedAt || web.reviewedAt < check.reviewRequestedAt);
      const decision = renewedReview ? 'pending' : matchesFrames(web, row) ? web.decision : chat.some(a => a.exerciseId === check.exerciseId && a.decision === 'approve_candidate' && JSON.stringify(a.frames) === JSON.stringify(frames)) ? 'approve_candidate' : 'pending';
      if (['approve_candidate', 'keep_original'].includes(decision)) approved.add(check.exerciseId);
      else if (decision !== 'needs_more_work') ready.add(check.exerciseId);
    }
  }
  return reviewTotals(ids, started, approved, ready);
}
function readTextJson(text) { try { return JSON.parse(text); } catch { return null; } }
