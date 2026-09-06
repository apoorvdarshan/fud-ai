#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
const html=fs.readFileSync(new URL('./workout_review.html',import.meta.url),'utf8');
const source=html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/refresh\(\);clock\(\);\s*$/,'');
function harness(){
 const jobs=[],timers=new Map(),listeners={};let tid=0;
 class Element{constructor(){this.children=[];this.value='';}append(...a){this.children.push(...a)}replaceChildren(...a){this.children=a}removeAttribute(k){delete this[k]}click(){}}
 const ids=['previous','next','play','step','speed','refresh','export','approve','repair','keep','clear','notes','summary','error','position','name','status','findings','views','exercise','empty','load-state','decision-state','frame-label'];
 const controls=Object.fromEntries(ids.map(k=>[k,new Element()]));
 const document={hidden:false,getElementById:k=>controls[k],createElement:()=>new Element(),addEventListener:(k,v)=>listeners[k]=v};
 class Image extends Element{constructor(){super();this.naturalWidth=1024}decode(){return new Promise((resolve,reject)=>jobs.push({image:this,resolve,reject}))}}
 const context=vm.createContext({document,Image,matchMedia:()=>({matches:false}),localStorage:{getItem:()=>null,setItem(){}},setInterval:fn=>{timers.set(++tid,fn);return tid},clearInterval:id=>timers.delete(id),setTimeout(){},URL:{createObjectURL:()=>'',revokeObjectURL(){}},Blob});
 vm.runInContext(source,context);
 const run=s=>vm.runInContext(s,context);
 run("data={batch:'batch-01',count:2,rows:['First','Second'].map(id=>({id,index:1,status:'Review',findings:[],available:8,humanApproved:false,sources:Array.from({length:8},(_,i)=>'/original/'+id+'/'+i),candidates:Array.from({length:8},(_,i)=>'/candidate/'+id+'/'+i),sourceHashes:Array.from({length:8},(_,i)=>'s'+i),hashes:Array.from({length:8},(_,i)=>'c'+i)}))};selectedId='First';");
 const settle=async()=>{await new Promise(resolve=>setImmediate(resolve))};
 return {run,jobs,controls,document,listeners,timers,settle};
}
test('one exercise has four simultaneous gender/background panels and no selectors',()=>{
 const h=harness();h.run('renderExercise()');
 assert.equal(h.run('panels.length'),4);assert.equal(h.jobs.length,4);
 assert.deepEqual(h.jobs.map(j=>j.image.src),['/original/First/0','/candidate/First/0','/original/First/4','/candidate/First/4']);
 assert.equal((html.match(/<select\b/g)||[]).length,0);
 assert.match(html,/header\{background:#181c24/);assert.doesNotMatch(html,/position:sticky/);
});
test('all panels wait for the matched four-image frame set',async()=>{
 const h=harness();h.run('renderExercise()');
 for(const j of h.jobs.slice(0,3))j.resolve();await h.settle();
 assert.equal(h.run('panels[0].before.src'),undefined);
 h.jobs[3].resolve();await h.settle();
 assert.equal(h.run('panels[0].before.src'),'/original/First/0');
 assert.equal(h.run('panels[1].before.src'),'/original/First/0');
 assert.equal(h.run('panels[2].before.src'),'/original/First/4');
 assert.equal(h.run('seen.size'),2);
});
test('navigation discards a late frame from the previous exercise',async()=>{
 const h=harness();h.run('renderExercise();move(1)');
 for(const j of h.jobs.slice(0,4))j.resolve();await h.settle();
 assert.equal(h.run('selectedId'),'Second');assert.equal(h.run('panels[0].before.src'),undefined);
 assert.equal(h.jobs.length,8);for(const j of h.jobs.slice(4))j.resolve();await h.settle();
 assert.equal(h.run('panels[0].before.src'),'/original/Second/0');
});
test('approval requires all eight frames and a failure revokes local approval',async()=>{
 const h=harness();h.run('renderExercise();decide("approve_candidate")');
 assert.equal(h.run('decisions.First'),undefined);
 for(let f=0;f<4;f++){if(f)h.run('frame='+f+';draw()');const js=h.jobs.slice(-4);js.forEach(j=>j.resolve());await h.settle();}
 h.run('decide("approve_candidate")');assert.equal(h.run('decisions.First.decision'),'approve_candidate');
 h.run('draw()');h.jobs.at(-1).reject(Error('changed'));for(const j of h.jobs.slice(-4,-1))j.resolve();await h.settle();
 assert.equal(h.run('decisions.First.decision'),'pending');assert.equal(h.run('seen.size'),6);assert.equal(h.controls.approve.disabled,true);
});
test('hidden tab releases all displayed images and pauses animation',async()=>{
 const h=harness();h.run('renderExercise();clock()');h.jobs.forEach(j=>j.resolve());await h.settle();
 assert.equal(h.timers.size,1);h.document.hidden=true;h.listeners.visibilitychange();
 assert.equal(h.timers.size,0);assert.equal(h.run('panels.every(p=>!p.before.src&&!p.after.src)'),true);
 const count=h.jobs.length;h.run('draw()');assert.equal(h.jobs.length,count);
});
test('export preserves chat approval and invalidates stale local approvals',()=>{
 const h=harness();h.run("data.rows[0].humanApproved=true;decisions.Second={decision:'approve_candidate',signature:'old',notes:'keep note'}");
 const out=JSON.parse(h.run('JSON.stringify(decisionPayload(data,decisions))'));
 assert.equal(out.decisions.First.decision,'approve_candidate');
 assert.equal(out.decisions.Second.decision,'pending');assert.equal(out.decisions.Second.notes,'keep note');
 assert.equal(out.approvalDoesNotPromote,true);
});
