#!/usr/bin/env node
// Loopback-only review. Writes human decisions, never promotes app images.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { collectReviewTotals, matchesFrames, readJson, validateDecision } from './workout_review_state.mjs';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const batchName = process.argv[2] || 'all';
if (batchName !== 'all' && !/^batch-\d{2}$/.test(batchName)) throw new Error('Expected all or batch-NN');
const batchesRoot = path.join(root, 'artifacts/workout-visual-qa/review-batches');
function reviewBatches() {
  return fs.readdirSync(batchesRoot).filter(name => /^batch-\d{2}$/.test(name) && (batchName === 'all' || name === batchName)).sort().flatMap(name => {
    const base = path.join(batchesRoot, name), stage = path.join(base, 'repair-pass-01');
    const batch = readJson(path.join(base, name + '.json'), null);
    return batch && fs.existsSync(stage) ? [{ name, stage, batch }] : [];
  });
}
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const images = new Map();
function manifest() {
  const nextImages = new Map();
  const allRows = [], seen = new Set();
  let batchTotal = 0;
  for (const { name, stage, batch } of reviewBatches()) {
  batchTotal += batch.exercises.length;
  const ledgerPath = path.join(stage, 'parent-review-passed.json');
  const passed = fs.existsSync(ledgerPath) ? JSON.parse(fs.readFileSync(ledgerPath)).checks : [];
  const approvals = fs.readdirSync(stage).filter(n => /^human-approval.*\.json$/.test(n)).flatMap(n => JSON.parse(fs.readFileSync(path.join(stage, n))).decisions || []);
  const webDecisions = readJson(path.join(stage, 'human-decisions.json'), {}).decisions || {};
  const rows = batch.exercises.map((e, index) => {
    const accepted = passed.find(r => r.index === e.index && r.exerciseId === e.exerciseId && ['parent_pass_human_pending', 'user_requested_review'].includes(r.status));
    if (!accepted) return null;
    const collected = path.join(stage, 'parent-collected', String(e.index).padStart(3, '0'));
    const reportPath = path.join(collected, 'worker-result.json');
    if (!fs.existsSync(reportPath) || sha(reportPath) !== accepted.reportSha256) return null;
    const report = JSON.parse(fs.readFileSync(reportPath));
    const sources = [], sourceHashes = [], candidates = [], hashes = [], methods = [];
    for (const p of e.sourceFramePaths) {
      const name = path.basename(p), original = path.join(root, 'shared/workout-vectors', name);
      const sourceHash = sha(original);
      const sourceUrl = '/image/original/' + name + '?v=' + sourceHash;
      nextImages.set(sourceUrl.split('?')[0], { file: original, hash: sourceHash }); sources.push(sourceUrl); sourceHashes.push(sourceHash);
      let chosen = null, method = null;
      const record = report.frames.find(f => f.filename === name);
      const checked = accepted.frames.find(f => f.filename === name);
      const candidate = path.join(collected, name);
      if (record && checked && fs.existsSync(candidate) && record.sourceSha256 === sourceHash && checked.sourceSha256 === sourceHash && record.candidateSha256 === checked.candidateSha256 && sha(candidate) === checked.candidateSha256) {
        chosen = candidate; method = record.method;
      }
      if (chosen) {
        const digest = sha(chosen), url = '/image/candidate/' + name + '?v=' + digest;
        nextImages.set(url.split('?')[0], { file: chosen, hash: digest }); candidates.push(url); hashes.push(digest); methods.push(method);
      } else { candidates.push(null); hashes.push(null); methods.push(null); }
    }
    const available = candidates.filter(Boolean).length;
    if (available !== 8) return null;
    const chatApproved = approvals.some(a => a.exerciseId === e.exerciseId && a.decision === 'approve_candidate' && a.frames?.length === 8 && a.frames.every(f => {
      const i = e.sourceFramePaths.findIndex(p => path.basename(p) === f.filename);
      return i >= 0 && f.sourceSha256 === sourceHashes[i] && f.candidateSha256 === hashes[i];
    }));
    const saved = webDecisions[e.exerciseId];
    const renewedReview = accepted.reviewRequestedAt && (!saved?.reviewedAt || saved.reviewedAt < accepted.reviewRequestedAt);
    const humanDecision = renewedReview ? 'pending' : matchesFrames(saved, { sourceHashes, hashes }) ? saved.decision : chatApproved ? 'approve_candidate' : 'pending';
    const humanApproved = ['approve_candidate', 'keep_original'].includes(humanDecision);
    return { id: e.exerciseId, batch: name, index: e.index, severity: e.severity, findings: accepted.reviewFindings || e.findings, suggestedRoute: e.suggestedRoute, sources, sourceHashes, candidates, hashes, methods, available,
      humanApproved, humanDecision, savedNotes: saved?.notes || '', status: humanApproved ? 'Approved by you · Saved' : humanDecision === 'needs_more_work' ? 'In progress · Returned for correction' : accepted.status === 'user_requested_review' ? (accepted.reviewLabel || 'Unfinished repair · Your review requested') : 'Parent-reviewed · Awaiting your approval',
      warning: 'Review the full animation in both genders and backgrounds. Your decision does not replace app images automatically.' };
  }).filter(Boolean).sort((a, b) => Number(a.humanApproved) - Number(b.humanApproved) || a.index - b.index);
  for (const row of rows) {
    if (seen.has(row.id)) throw new Error('Duplicate exercise across review batches');
    seen.add(row.id); allRows.push(row);
  }
  }
  images.clear();for (const [key, value] of nextImages) images.set(key, value);
  const undecided = allRows.filter(row => row.humanDecision === 'pending');
  return { batch: batchName, count: undecided.length, batchTotal, totals: collectReviewTotals(root), rows: undecided };
}
const server = http.createServer(async (req, res) => {
  try {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'POST' && url.pathname === '/decision') {
    const expectedOrigin = `http://127.0.0.1:${server.address().port}`;
    if (req.headers.host !== `127.0.0.1:${server.address().port}` || req.headers.origin !== expectedOrigin || req.headers['content-type']?.split(';')[0] !== 'application/json') { res.writeHead(403); res.end('Same-origin JSON required'); return; }
    let body = '';
    for await (const chunk of req) { body += chunk; if (Buffer.byteLength(body) > 24000) { res.writeHead(413); res.end('Decision too large'); return; } }
    try {
      const input = JSON.parse(body), current = manifest(), row = current.rows.find(r => r.id === input.exerciseId);
      const decision = validateDecision(input, row);
      // Derive the destination from the validated server row, never the request.
      const stage = path.join(batchesRoot, row.batch, 'repair-pass-01');
      const file = path.join(stage, 'human-decisions.json');
      const state = readJson(file, { batch: row.batch, decisions: {} });
      state.decisions[decision.exerciseId] = decision;
      state.updatedAt = decision.reviewedAt;
      const temp = file + '.' + crypto.randomUUID() + '.tmp';
      fs.writeFileSync(temp, JSON.stringify(state, null, 2), { mode: 0o600 }); fs.renameSync(temp, file);
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ saved: decision, totals: collectReviewTotals(root) }));
    } catch (error) { res.writeHead(409, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error: error.message })); }
    return;
  }
  if (req.method !== 'GET') { res.writeHead(405); res.end(); return; }
  if (url.pathname === '/decisions.json') {
    const decisions = {};
    for (const { stage } of reviewBatches()) {
    for (const file of fs.readdirSync(stage).filter(n => /^human-approval.*\.json$/.test(n))) {
      for (const d of readJson(path.join(stage, file), {}).decisions || []) decisions[d.exerciseId] = d;
    }
    Object.assign(decisions, readJson(path.join(stage, 'human-decisions.json'), {}).decisions || {});
    }
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ batch: batchName, exportedAt: new Date().toISOString(), approvalDoesNotPromote: true, decisions })); return;
  }
  if (url.pathname === '/manifest.json') {
    const body = JSON.stringify(manifest());
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });res.end(body);return;
  }
  let key;
  try { key = decodeURI(url.pathname); } catch { res.writeHead(400); res.end(); return; }
  const entry = images.get(key);
  const file = url.pathname === '/' ? path.join(root, 'scripts/workout_review.html') : entry?.file;
  if (!file || !fs.existsSync(file)) { res.writeHead(404);res.end('Not found');return; }
  // Serve the exact bytes we verify, not a later file version from a worker.
  const bytes = fs.readFileSync(file);
  if (entry && (url.searchParams.get('v') !== entry.hash || crypto.createHash('sha256').update(bytes).digest('hex') !== entry.hash)) { res.writeHead(409);res.end('Image changed; refresh candidates');return; }
  res.writeHead(200, { 'Content-Type': url.pathname === '/' ? 'text/html; charset=utf-8' : 'image/png', 'Cache-Control': url.pathname === '/' ? 'no-store' : 'private, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff' });
  res.end(bytes);
  } catch {
    if (!res.headersSent) res.writeHead(503, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    res.end('Review assets are unavailable or changing; retry refresh.');
  }
});
manifest();
server.listen(Number(process.env.WORKOUT_REVIEW_PORT || 8765), '127.0.0.1', () => console.log(`Review ${batchName}: http://127.0.0.1:${server.address().port}`));
