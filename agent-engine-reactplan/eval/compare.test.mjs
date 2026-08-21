import test from 'node:test';
import assert from 'node:assert/strict';
import { scoreComparableAnswer } from './compare.mjs';

test('comparison scoring accepts the same content under either successful state vocabulary', () => {
  const expect = { includes: ['Sort.java'], includesAny: ['冒泡', '快速'], excludes: ['编造'] };
  assert.equal(scoreComparableAnswer(expect, 'succeeded', 'Sort.java 实现冒泡排序').passed, true);
  assert.equal(scoreComparableAnswer(expect, 'COMPLETED', 'Sort.java 实现快速排序').passed, true);
});

test('comparison scoring rejects failed states and missing required content', () => {
  const expect = { includes: ['src/main/java/Sort.java'] };
  assert.equal(scoreComparableAnswer(expect, 'failed', 'src/main/java/Sort.java').passed, false);
  assert.equal(scoreComparableAnswer(expect, 'COMPLETED', 'Sort.java').passed, false);
});
