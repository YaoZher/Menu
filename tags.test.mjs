import test from 'node:test';
import assert from 'node:assert/strict';
import { flattenTags, matchesTagFilters, normalizeTagSelection } from './src/main/resources/static/assets/tags.js';

const groups = [
  { id: 1, name: '菜品类型', tags: [{ id: 11, name: '主食' }, { id: 12, name: '前菜' }] },
  { id: 2, name: '营养构成', tags: [{ id: 21, name: '碳水' }, { id: 22, name: '蛋白质' }] },
  { id: 3, name: '其他', tags: [{ id: 31, name: '未分类' }] }
];
const [main, starter, carbs, protein, fallback] = flattenTags(groups);

test('catalog tags retain category identity without changing the catalog', () => {
  assert.deepEqual(main, { id: 11, name: '主食', categoryId: 1, categoryName: '菜品类型' });
  assert.deepEqual(groups[0].tags[0], { id: 11, name: '主食' });
});

test('empty tag filters include dishes with or without tags', () => {
  assert.equal(matchesTagFilters([main], []), true);
  assert.equal(matchesTagFilters(undefined, []), true);
  assert.equal(matchesTagFilters([], []), true);
});

test('filter choices in one category are alternatives', () => {
  assert.equal(matchesTagFilters([main], [main, starter]), true);
  assert.equal(matchesTagFilters([starter, protein], [main, starter]), true);
  assert.equal(matchesTagFilters([protein], [main, starter]), false);
});

test('filters across categories must all match while each category allows alternatives', () => {
  const selected = [main, starter, protein];
  assert.equal(matchesTagFilters([main, protein], selected), true);
  assert.equal(matchesTagFilters([starter, protein, carbs], selected), true);
  assert.equal(matchesTagFilters([main, carbs], selected), false);
  assert.equal(matchesTagFilters([protein], selected), false);
  assert.equal(matchesTagFilters(undefined, selected), false);
});

test('matching uses IDs, tolerates numeric strings and separates equal tag names', () => {
  assert.equal(matchesTagFilters([{ ...main, id: '11' }], [main]), true);
  assert.equal(matchesTagFilters([{ ...main, id: 99 }], [main]), false);
  assert.equal(matchesTagFilters([main], [main, { ...starter, categoryId: '1' }]), true);
});

test('editor keeps one fallback only when no specific tags remain', () => {
  assert.deepEqual(normalizeTagSelection([], fallback), [fallback]);
  assert.deepEqual(normalizeTagSelection([fallback], fallback), [fallback]);
  assert.deepEqual(normalizeTagSelection([fallback, main], fallback), [main]);
  assert.deepEqual(normalizeTagSelection([main, protein, fallback], fallback), [main, protein]);
  assert.deepEqual(normalizeTagSelection([], undefined), []);
});

test('editor deduplicates tag IDs before enforcing its 12-tag limit', () => {
  assert.deepEqual(normalizeTagSelection([main, { ...main, id: '11' }], fallback), [{ ...main, id: '11' }]);
  const twelve = Array.from({ length: 12 }, (_, i) => ({ id: i + 100, name: `标签${i}` }));
  assert.equal(normalizeTagSelection([...twelve, twelve[0], fallback], fallback).length, 12);
  assert.throws(() => normalizeTagSelection([...twelve, main], fallback), /12/);
  assert.equal(twelve.length, 12);
});
