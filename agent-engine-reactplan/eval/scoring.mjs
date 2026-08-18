import { createHash } from 'node:crypto';

const SECRET_KEYS = /^(?:password|authorization|api[-_]?key|secret|access[-_]?token|refresh[-_]?token)$/i;

export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key,
      SECRET_KEYS.test(key) ? '[REDACTED]' : redact(item),
    ]));
  }
  if (typeof value !== 'string') return value;
  return value
    .replace(/Bearer\s+[A-Za-z0-9._~+\/-]+/gi, 'Bearer [REDACTED]')
    .replace(/\b(sk-[A-Za-z0-9_-]{8,})\b/g, '[REDACTED]');
}

export function textHash(text) {
  return createHash('sha256').update(text ?? '', 'utf8').digest('hex');
}

export function scoreTask(expect, observed) {
  const answer = observed.conclusion ?? '';
  const toolNames = new Set(observed.toolNames ?? []);
  const checks = [];
  const check = (name, passed, actual) => checks.push({ name, passed: Boolean(passed), actual });

  if (expect.state) check('terminal-state', observed.state === expect.state, observed.state);
  for (const tool of expect.tools ?? []) check(`tool:${tool}`, toolNames.has(tool), [...toolNames]);
  if (expect.anyTools) check('tool:any', expect.anyTools.some((tool) => toolNames.has(tool)), [...toolNames]);
  for (const fragment of expect.includes ?? []) check(`answer-includes:${fragment}`, answer.includes(fragment), textHash(answer));
  if (expect.includesAny) check('answer-includes-any', expect.includesAny.some((fragment) => answer.includes(fragment)), textHash(answer));
  for (const fragment of expect.excludes ?? []) check(`answer-excludes:${fragment}`, !answer.includes(fragment), textHash(answer));
  if (expect.sandbox) check('sandbox-observed', (observed.metrics?.sandboxCalls ?? 0) > 0, observed.metrics?.sandboxCalls ?? 0);
  if (expect.sandboxSuccess) check('sandbox-success-receipt', observed.sandboxSuccess === true, observed.sandboxSuccess ?? false);
  if (expect.evidence) check('formal-evidence', (observed.evidenceCount ?? 0) > 0, observed.evidenceCount ?? 0);
  if (expect.revisionChanged) check('revision-changed', observed.beforeVersion !== observed.afterVersion, `${observed.beforeVersion} -> ${observed.afterVersion}`);

  return { passed: checks.length > 0 && checks.every(({ passed }) => passed), checks };
}

export function summarize(results) {
  const passed = results.filter((item) => item.score?.passed).length;
  const totalTokens = results.reduce((sum, item) => sum + (item.metrics?.totalTokens ?? 0), 0);
  const durationMillis = results.reduce((sum, item) => sum + (item.metrics?.totalDurationMillis ?? item.durationMillis ?? 0), 0);
  return { total: results.length, passed, failed: results.length - passed, successRate: results.length ? passed / results.length : 0, totalTokens, durationMillis };
}
