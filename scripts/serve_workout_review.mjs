#!/usr/bin/env node
// Loopback-only, read-only review. Never promotes candidates or writes decisions.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const batchName = process.argv[2] || 'batch-01';
if (!/^batch-\d{2}$/.test(batchName)) throw new Error('Expected batch-NN');
const base = path.join(root, 'artifacts/workout-visual-qa/review-batches', batchName);
const batch = JSON.parse(fs.readFileSync(path.join(base, batchName + '.json')));
const stage = path.join(base, 'repair-pass-01');
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const images = new Map();
// Match json.dumps(..., sort_keys=True) used for cleanup override digests.
function pythonJson(value) {
  if (Array.isArray(value)) return '[' + value.map(pythonJson).join(', ') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => pythonJson(k) + ': ' + pythonJson(value[k])).join(', ') + '}';
  return JSON.stringify(value).replace(/[\u007f-\uffff]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
}
function manifest() {
  const nextImages = new Map();
  const overrides = JSON.parse(fs.readFileSync(path.join(root, 'artifacts/workout-visual-qa/background-reviewed-overrides.json')));
  const rows = batch.exercises.map((e, index) => {
    const sources = [], sourceHashes = [], candidates = [], hashes = [], methods = [];
    for (const p of e.sourceFramePaths) {
      const name = path.basename(p), original = path.join(root, 'shared/workout-vectors', name);
      const sourceHash = sha(original);
      const sourceUrl = '/image/original/' + name + '?v=' + sourceHash;
      nextImages.set(sourceUrl.split('?')[0], { file: original, hash: sourceHash }); sources.push(sourceUrl); sourceHashes.push(sourceHash);
      const generated = path.join(stage, 'imagegen', name);
      const generatedRecord = generated.replace(/\.png$/, '.json');
      const cleaned = path.join(stage, 'shard-' + index % 4, 'images', name);
      const recordPath = path.join(stage, 'shard-' + index % 4, 'records', name.replace(/\.png$/, '.json'));
      let chosen = null, method = null;
      for (const [candidate, record, kind] of [[generated, generatedRecord, 'imagegen'], [cleaned, recordPath, 'background cleanup']]) {
        if (!fs.existsSync(candidate) || !fs.existsSync(record)) continue;
        try {
          const r = JSON.parse(fs.readFileSync(record));
          const configHash = crypto.createHash('sha256').update(pythonJson(overrides[name] || {})).digest('hex');
          if (kind === 'background cleanup' && (r.recipe !== 'pale-neural-guard-v2' || r.override_sha256 !== configHash)) continue;
          if (r.source_sha256 === sourceHash && r.candidate_sha256 === sha(candidate)) { chosen = candidate; method = kind; break; }
        } catch { /* A worker may be writing; incomplete candidates stay unavailable. */ }
      }
      if (chosen) {
        const digest = sha(chosen), url = '/image/candidate/' + name + '?v=' + digest;
        nextImages.set(url.split('?')[0], { file: chosen, hash: digest }); candidates.push(url); hashes.push(digest); methods.push(method);
      } else { candidates.push(null); hashes.push(null); methods.push(null); }
    }
    const available = candidates.filter(Boolean).length;
    return { id: e.exerciseId, index: e.index, severity: e.severity, findings: e.findings, suggestedRoute: e.suggestedRoute, sources, sourceHashes, candidates, hashes, methods, available,
      status: available === 8 ? 'Candidate set — NOT verified or approved' : `Pending repair: ${available}/8 candidate frames`,
      warning: e.severity === 'critical' ? 'Critical source issue: cleanup alone cannot establish a repair. Inspect anatomy and exercise mechanics.' : 'Check all reported issues; cleanup may leave alignment or interior remnants unresolved.' };
  });
  images.clear();for (const [key, value] of nextImages) images.set(key, value);
  return { batch: batchName, count: rows.length, rows };
}
const server = http.createServer((req, res) => {
  try {
  if (req.method !== 'GET') { res.writeHead(405); res.end(); return; }
  const url = new URL(req.url, 'http://localhost');
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
