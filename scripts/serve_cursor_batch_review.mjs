#!/usr/bin/env node
/** Loopback review for cursor repair batches (pilot-10, cloud-100, …). */
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { matchesFrames, readJson, validateDecision } from './workout_review_state.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const batchName = process.argv[2] || 'cursor-cloud-100';
const batchJson = path.join(root, 'artifacts/workout-visual-qa/review-batches', batchName, `${batchName}.json`);
const batchRoot = path.join(root, 'artifacts/workout-visual-qa', batchName);
const originals = path.join(root, 'shared/workout-vectors');
const candidates = path.join(batchRoot, 'candidates/images');
const decisionsFile = path.join(batchRoot, 'human-decisions.json');
const defaultPorts = { 'cursor-pilot-10': 8766, 'cursor-cloud-100': 8767, 'cursor-cloud-100-done30': 8766 };
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const images = new Map();

function batchTotals(rows) {
  const approved = rows.filter(r => ['approve_candidate', 'keep_original'].includes(r.humanDecision)).length;
  const inProgress = rows.filter(r => r.humanDecision === 'needs_more_work').length;
  const pending = rows.filter(r => r.humanDecision === 'pending').length;
  return { total: rows.length, approved, inProgress, notStarted: pending, readyForReview: pending + inProgress };
}

function manifest() {
  const batch = readJson(batchJson, null);
  if (!batch?.exercises?.length) throw new Error(`${batchName} batch manifest missing`);
  const webDecisions = readJson(decisionsFile, {}).decisions || {};
  const nextImages = new Map();
  const allRows = [];
  for (const e of batch.exercises) {
    const sources = [], sourceHashes = [], candidateUrls = [], hashes = [], methods = [];
    let missing = false;
    for (const p of e.sourceFramePaths) {
      const name = path.basename(p);
      const original = path.join(originals, name);
      const candidate = path.join(candidates, name);
      if (!fs.existsSync(original)) throw new Error(`Missing original ${name}`);
      if (!fs.existsSync(candidate)) { missing = true; break; }
      const sourceHash = sha(original);
      const candidateHash = sha(candidate);
      const sourceUrl = '/image/original/' + name + '?v=' + sourceHash;
      const candidateUrl = '/image/candidate/' + name + '?v=' + candidateHash;
      nextImages.set(sourceUrl.split('?')[0], { file: original, hash: sourceHash });
      nextImages.set(candidateUrl.split('?')[0], { file: candidate, hash: candidateHash });
      sources.push(sourceUrl);
      sourceHashes.push(sourceHash);
      candidateUrls.push(candidateUrl);
      hashes.push(candidateHash);
      methods.push('script_background_cleanup');
    }
    if (missing) continue;
    const saved = webDecisions[e.exerciseId];
    const humanDecision = matchesFrames(saved, { sourceHashes, hashes }) ? saved.decision : 'pending';
    const humanApproved = ['approve_candidate', 'keep_original'].includes(humanDecision);
    allRows.push({
      id: e.exerciseId,
      batch: batchName,
      index: e.index,
      severity: e.severity,
      findings: e.findings || [],
      suggestedRoute: e.suggestedRoute || 'script_fix',
      sources,
      sourceHashes,
      candidates: candidateUrls,
      hashes,
      methods,
      available: 8,
      humanApproved,
      humanDecision,
      savedNotes: saved?.notes || '',
      status: humanApproved ? 'Approved by you · Saved' : humanDecision === 'needs_more_work' ? 'Needs Cursor image edit / more work' : 'Script cleanup · Awaiting your approval',
      warning: 'Approve saves decision only — app images stay unchanged.',
    });
  }
  allRows.sort((a, b) => Number(a.humanApproved) - Number(b.humanApproved) || a.index - b.index);
  images.clear();
  for (const [key, value] of nextImages) images.set(key, value);
  const undecided = allRows.filter(r => r.humanDecision === 'pending');
  return { batch: batchName, count: undecided.length, batchTotal: batch.exercises.length, readyCount: allRows.length, totals: batchTotals(allRows), rows: undecided };
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'POST' && url.pathname === '/decision') {
      const expectedOrigin = `http://127.0.0.1:${server.address().port}`;
      if (req.headers.host !== `127.0.0.1:${server.address().port}` || req.headers.origin !== expectedOrigin || req.headers['content-type']?.split(';')[0] !== 'application/json') {
        res.writeHead(403); res.end('Same-origin JSON required'); return;
      }
      let body = '';
      for await (const chunk of req) { body += chunk; if (Buffer.byteLength(body) > 24000) { res.writeHead(413); res.end('Decision too large'); return; } }
      const input = JSON.parse(body);
      const row = manifest().rows.find(r => r.id === input.exerciseId);
      const decision = validateDecision(input, row);
      const state = readJson(decisionsFile, { batch: batchName, decisions: {} });
      state.decisions[decision.exerciseId] = decision;
      state.updatedAt = decision.reviewedAt;
      fs.mkdirSync(batchRoot, { recursive: true });
      const temp = decisionsFile + '.' + crypto.randomUUID() + '.tmp';
      fs.writeFileSync(temp, JSON.stringify(state, null, 2), { mode: 0o600 });
      fs.renameSync(temp, decisionsFile);
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ saved: decision, totals: manifest().totals }));
      return;
    }
    if (req.method !== 'GET') { res.writeHead(405); res.end(); return; }
    if (url.pathname === '/decisions.json') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ batch: batchName, exportedAt: new Date().toISOString(), approvalDoesNotPromote: true, decisions: readJson(decisionsFile, {}).decisions || {} }));
      return;
    }
    if (url.pathname === '/manifest.json') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(manifest()));
      return;
    }
    let key;
    try { key = decodeURI(url.pathname); } catch { res.writeHead(400); res.end(); return; }
    const entry = images.get(key);
    const file = url.pathname === '/' ? path.join(root, 'scripts/workout_review.html') : entry?.file;
    if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end('Not found'); return; }
    const bytes = fs.readFileSync(file);
    if (entry && (url.searchParams.get('v') !== entry.hash || sha(file) !== entry.hash)) { res.writeHead(409); res.end('Image changed; refresh'); return; }
    res.writeHead(200, {
      'Content-Type': url.pathname === '/' ? 'text/html; charset=utf-8' : 'image/png',
      'Cache-Control': url.pathname === '/' ? 'no-store' : 'private, max-age=31536000, immutable',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(bytes);
  } catch (error) {
    if (!res.headersSent) res.writeHead(503, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    res.end(String(error.message || error));
  }
});

try { manifest(); } catch (e) { console.warn('Manifest preload:', e.message); }
const port = Number(process.env.WORKOUT_REVIEW_PORT || defaultPorts[batchName] || 8767);
server.listen(port, '127.0.0.1', () => console.log(`${batchName} review: http://127.0.0.1:${port}`));
