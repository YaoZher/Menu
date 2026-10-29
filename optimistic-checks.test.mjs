import test from 'node:test';
import assert from 'node:assert/strict';
import { OptimisticCheckQueue } from './src/main/resources/static/assets/optimistic-checks.js';

const ingredients = (a = false, b = false) => [{ id: 'a', checked: a }, { id: 'b', checked: b }];

test('different checkboxes update immediately while one write is in flight', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  queue.set('a', true);
  const first = queue.next();
  queue.set('b', true);
  assert.equal(queue.value('a'), true);
  assert.equal(queue.value('b'), true);
  assert.equal(queue.next(), null);
  queue.resolve(first, ingredients(true, false));
  assert.equal(queue.value('b'), true, 'older full-entry response must not overwrite the newer choice');
  const second = queue.next();
  assert.equal(second.id, 'b');
  queue.resolve(second, ingredients(true, true));
  assert.equal(queue.pending, false);
  assert.equal(queue.value('a'), true);
  assert.equal(queue.value('b'), true);
});

test('rapid reversal stays visible and is saved after the original request', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  queue.set('a', true);
  const first = queue.next();
  queue.set('a', false);
  assert.equal(queue.value('a'), false);
  queue.resolve(first, ingredients(true, false));
  assert.equal(queue.value('a'), false);
  const reversal = queue.next();
  assert.equal(reversal.checked, false);
  queue.resolve(reversal, ingredients());
  assert.equal(queue.value('a'), false);
  assert.equal(queue.pending, false);
});

test('rapid check-uncheck-check coalesces to the final saved value', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  queue.set('a', true);
  const first = queue.next();
  queue.set('a', false);
  queue.set('a', true);
  queue.resolve(first, ingredients(true, false));
  assert.equal(queue.value('a'), true);
  assert.equal(queue.next(), null);
});

test('a failed latest write rolls back only that item, preserving other queued and saved choices', () => {
  const queue = new OptimisticCheckQueue(ingredients(true, false));
  queue.set('a', false);
  const first = queue.next();
  queue.set('b', true);
  queue.reject(first);
  assert.equal(queue.value('a'), true, 'restore the last confirmed value');
  assert.equal(queue.value('b'), true, 'keep the unrelated unsaved choice visible');
  const second = queue.next();
  queue.resolve(second, ingredients(true, true));
  assert.equal(queue.value('a'), true);
  assert.equal(queue.value('b'), true);
  assert.equal(queue.pending, false);
});

test('failure of an older intent cannot roll back a newer intent for the same checkbox', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  queue.set('a', true);
  const first = queue.next();
  queue.set('a', false);
  queue.set('a', true);
  queue.reject(first);
  assert.equal(queue.value('a'), true);
  const latest = queue.next();
  assert.equal(latest.checked, true);
  queue.resolve(latest, ingredients(true, false));
  assert.equal(queue.pending, false);
});

test('recipe reconciliation removes stale queued ingredients without changing other intentions', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  queue.set('a', true);
  const first = queue.next();
  queue.set('b', true);
  const outcome = queue.resolve(first, [{ id: 'a', checked: true }, { id: 'new', checked: false }]);
  assert.equal(outcome.removed, 1);
  assert.equal(queue.next(), null);
  assert.equal(queue.value('a'), true);
  assert.equal(queue.value('new'), false);
  assert.equal(queue.set('b', true), false);
});

test('stale duplicate completions cannot replace confirmed state', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  queue.set('a', true);
  const first = queue.next();
  queue.resolve(first, ingredients(true, false));
  queue.set('a', false);
  const second = queue.next();
  assert.equal(queue.resolve(first, ingredients(true, true)).accepted, false);
  assert.equal(queue.reject(first), false);
  assert.equal(queue.value('a'), false);
  queue.resolve(second, ingredients());
  assert.equal(queue.value('a'), false);
  assert.equal(queue.value('b'), false);
});

test('cancelling a queue rejects late completions and drops unsaved choices', () => {
  const queue = new OptimisticCheckQueue(ingredients(true, false));
  queue.set('a', false);
  const operation = queue.next();
  queue.set('b', true);
  queue.cancel();
  assert.equal(queue.pending, false);
  assert.equal(queue.value('a'), true);
  assert.equal(queue.value('b'), false);
  assert.equal(queue.resolve(operation, ingredients(false, true)).accepted, false);
  assert.equal(queue.value('a'), true);
  assert.equal(queue.value('b'), false);
});

test('unchanged choices do not create writes or replace an active operation', () => {
  const queue = new OptimisticCheckQueue(ingredients());
  assert.equal(queue.set('a', false), false);
  assert.equal(queue.next(), null);
  queue.set('a', true);
  const operation = queue.next();
  assert.equal(queue.set('a', true), false);
  assert.equal(queue.next(), null);
  queue.resolve(operation, ingredients(true, false));
  assert.equal(queue.pending, false);
});
