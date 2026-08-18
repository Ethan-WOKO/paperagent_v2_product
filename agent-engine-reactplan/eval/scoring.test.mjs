import assert from 'node:assert/strict';
import test from 'node:test';
import { redact, scoreTask, summarize } from './scoring.mjs';

test('redacts credentials recursively without deleting ordinary facts', () => {
  assert.deepEqual(redact({ password: '123', totalTokens: 456, nested: { authorization: 'Bearer abc.def', result: 'ok' } }), {
    password: '[REDACTED]', totalTokens: 456, nested: { authorization: '[REDACTED]', result: 'ok' },
  });
  assert.equal(redact('Authorization: Bearer abc.def'), 'Authorization: Bearer [REDACTED]');
});

test('scores state, tools, evidence, sandbox, and answer facts deterministically', () => {
  const scored = scoreTask(
    { state: 'succeeded', tools: ['project.read'], includes: ['Sort.java'], sandbox: true, sandboxSuccess: true, evidence: true },
    { state: 'succeeded', toolNames: ['project.read'], conclusion: '读取 Sort.java', metrics: { sandboxCalls: 1 }, sandboxSuccess: true, evidenceCount: 2 },
  );
  assert.equal(scored.passed, true);
  assert.equal(scored.checks.length, 6);
});

test('summary counts a task once while retaining total model token usage', () => {
  assert.deepEqual(summarize([
    { score: { passed: true }, metrics: { totalTokens: 120, totalDurationMillis: 1000 } },
    { score: { passed: false }, metrics: { totalTokens: 80, totalDurationMillis: 500 } },
  ]), { total: 2, passed: 1, failed: 1, successRate: 0.5, totalTokens: 200, durationMillis: 1500 });
});
