#!/usr/bin/env node
// No browser, server, real images, network, or model sessions: mocked DOM tests.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const html = fs.readFileSync(new URL('./workout_review.html', import.meta.url), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/refresh\(\);clock\(\);\s*$/, '');

function harness() {
  const jobs = [], intervals = new Map(), listeners = new Map();
  let exported, downloaded, intervalId = 0;
  class Element {
    constructor(tag = 'div') { this.tag = tag; this.children = []; this.value = ''; this.hidden = false; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    replaceWith(other) { this.replacedBy = other; }
    removeAttribute(name) { delete this[name]; }
    setAttribute(name, value) { this[name] = value; }
    querySelector(selector) { return this.children.find(c => c.value === selector.match(/value="([^"]+)"/)[1]); }
    click() { downloaded = this.download; }
  }
  const controls = Object.fromEntries(['gender', 'background', 'frame', 'frameNumber', 'play', 'speed', 'step', 'refresh', 'export', 'error', 'summary', 'grid'].map(id => [id, new Element()]));
  Object.assign(controls.gender, { value: 'male' });
  Object.assign(controls.background, { value: 'dark' });
  Object.assign(controls.speed, { value: '650' });
  const document = { hidden: false, getElementById: id => controls[id], createElement: tag => new Element(tag), addEventListener: (name, fn) => listeners.set(name, fn), querySelector: () => new Element() };
  class Image extends Element {
    constructor() { super('img'); this.naturalWidth = 1024; }
    decode() { return new Promise((resolve, reject) => jobs.push({ image: this, resolve, reject })); }
  }
  const context = vm.createContext({ document, Image, matchMedia: () => ({ matches: false }), localStorage: { getItem: () => null, setItem() {} },
    IntersectionObserver: class { observe() {} disconnect() {} }, Blob,
    URL: { createObjectURL(blob) { exported = blob; return 'blob:test'; }, revokeObjectURL() {} },
    setTimeout() {}, setInterval(fn) { intervals.set(++intervalId, fn); return intervalId; }, clearInterval(id) { intervals.delete(id); },
  });
  vm.runInContext(source, context);
  const run = code => vm.runInContext(code, context);
  run(`data={batch:'batch-02',count:1,rows:[{id:'Example',index:1,severity:'major',status:'candidate',warning:'Review',findings:[],methods:['cleanup'],available:8,
    sources:Array.from({length:8},(_,i)=>'/original/'+i),candidates:Array.from({length:8},(_,i)=>'/candidate/'+i),sourceHashes:Array.from({length:8},(_,i)=>'s'+i),hashes:Array.from({length:8},(_,i)=>'c'+i)}]};render();`);
  return { run, controls, document, jobs, intervals, listeners, view: run('views[0]'), exported: () => exported, downloaded: () => downloaded };
}

test('offscreen and hidden-tab cards do not decode; hidden tab releases images and stops timer', async () => {
  const h = harness();
  await h.run('drawView(views[0])');
  assert.equal(h.jobs.length, 0);
  h.view.visible = true; h.document.hidden = true;
  await h.run('drawView(views[0])');
  assert.equal(h.jobs.length, 0);
  h.document.hidden = false; h.run('clock()');
  assert.equal(h.intervals.size, 1);
  h.view.before.src = '/held'; h.view.after.src = '/held-candidate';
  h.document.hidden = true; h.listeners.get('visibilitychange')();
  assert.equal(h.intervals.size, 0);
  assert.equal(h.view.before.src, undefined);
  assert.equal(h.view.after.src, undefined);
});

test('matching pair waits for both decodes and preserves gender/frame alignment', async () => {
  const h = harness(); h.view.visible = true; h.controls.gender.value = 'female';
  const before = h.view.before, after = h.view.after;
  const loading = h.run('frame=2;drawView(views[0])');
  assert.equal(h.jobs.length, 2);
  assert.equal(h.jobs[0].image.src, '/original/6');
  assert.equal(h.jobs[1].image.src, '/candidate/6');
  h.jobs[0].resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(h.view.before, before); assert.equal(h.view.after, after);
  h.jobs[1].resolve(); await loading;
  assert.equal(h.view.before, h.jobs[0].image); assert.equal(h.view.after, h.jobs[1].image);
  assert.match(h.view.before.alt, /female frame 2$/); assert.match(h.view.after.alt, /female frame 2$/);
  assert.equal(h.view.seen.has(6), true);
});

test('offscreen invalidation prevents pending old pair from painting', async () => {
  const h = harness(); h.view.visible = true;
  const before = h.view.before, loading = h.run('drawView(views[0])');
  h.view.visible = false; h.run('release(views[0])');
  for (const job of h.jobs) job.resolve();
  await loading;
  assert.equal(h.view.before, before); assert.equal(h.view.seen.size, 0);
  assert.equal(h.view.before.hidden, true);
});

test('export resets stale signatures, keeps current decisions, excludes other exercises and uses current batch', async () => {
  const h = harness();
  h.run(`decisions={Example:{decision:'approve_candidate',signature:'stale',notes:'preserve notes'},Unrelated:{decision:'approve_candidate'}};`);
  h.controls.export.onclick();
  let result = JSON.parse(await h.exported().text());
  assert.equal(result.batch, 'batch-02'); assert.equal(h.downloaded(), 'workout-batch-02-human-decisions.json');
  assert.deepEqual(Object.keys(result.decisions), ['Example']);
  assert.equal(result.decisions.Example.decision, 'pending'); assert.equal(result.decisions.Example.stalePreviousDecision, true);
  assert.equal(result.decisions.Example.notes, 'preserve notes');
  assert.equal(result.decisions.Example.sourceHashes.length, 8); assert.equal(result.decisions.Example.candidateHashes.length, 8);
  h.run(`decisions.Example={decision:'keep_original',signature:signature(data.rows[0])};`);
  h.controls.export.onclick(); result = JSON.parse(await h.exported().text());
  assert.equal(result.decisions.Example.decision, 'keep_original');
});

test('failed image decode invalidates approval and exported decision', async () => {
  const h = harness(); h.view.visible = true;
  h.run(`views[0].seen=new Set([0,1,2,3,4,5,6,7]);approval(views[0]);views[0].choice.value='approve_candidate';views[0].update();`);
  assert.equal(h.view.choice.querySelector('option[value="approve_candidate"]').disabled, false);
  const loading = h.run('drawView(views[0])');
  h.jobs[0].resolve(); h.jobs[1].reject(new Error('invalid PNG')); await loading;
  assert.equal(h.view.choice.value, 'pending'); assert.equal(h.view.failed.has(0), true);
  assert.equal(h.view.choice.querySelector('option[value="approve_candidate"]').disabled, true);
  h.controls.export.onclick();
  assert.equal(JSON.parse(await h.exported().text()).decisions.Example.decision, 'pending');
});

test('animation does not advance while visible matched pairs are decoding', () => {
  const h = harness(); h.view.visible = true; h.view.busy = true; h.run('clock()');
  for (const tick of h.intervals.values()) tick();
  assert.equal(h.run('frame'), 0); assert.equal(h.jobs.length, 0);
});
