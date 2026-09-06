import test from 'node:test';
import assert from 'node:assert/strict';
import { reviewTotals, matchesFrames, validateDecision } from './workout_review_state.mjs';

test('875 totals are mutually exclusive, and ready is a subset of progress', () => {
  const ids = new Set(Array.from({length:875},(_,i)=>String(i)));
  const totals = reviewTotals(ids,new Set(['0','1','2','3']),new Set(['0']),new Set(['1','2']));
  assert.deepEqual(totals,{total:875,approved:1,inProgress:3,notStarted:871,readyForReview:2});
  assert.equal(totals.approved+totals.inProgress+totals.notStarted,875);
});
test('rejection returns approved exercise to progress, not unstarted', () => {
  const totals=reviewTotals(new Set(['x']),new Set(['x']),new Set(),new Set());
  assert.deepEqual(totals,{total:1,approved:0,inProgress:1,notStarted:0,readyForReview:0});
});
test('decisions require exact current hashes and known decision', () => {
  const row={id:'Exercise',sourceHashes:['s'],hashes:['c']};
  const input={exerciseId:'Exercise',decision:'needs_more_work',notes:'white gap',sourceHashes:['s'],candidateHashes:['c']};
  assert.equal(validateDecision(input,row).decision,'needs_more_work');
  assert.throws(()=>validateDecision({...input,candidateHashes:['old']},row),/changed/);
  assert.throws(()=>validateDecision({...input,decision:'delete'},row));
  assert.throws(()=>validateDecision(input,null));
});
test('a repaired revision no longer matches the rejected candidate',()=>{
  assert.equal(matchesFrames({sourceHashes:['s'],candidateHashes:['old']},{sourceHashes:['s'],hashes:['new']}),false);
});
