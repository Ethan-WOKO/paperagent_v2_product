import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { TASK_CASES } from './cases.mjs';
import { ProductClient, collect } from './run.mjs';
import { redact } from './scoring.mjs';

const origin = (process.env.PAPERAGENT_EVAL_ORIGIN ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const outputRoot = path.resolve('.eval-results');
const DEFAULT_CASE_IDS = [
  'project-manifest',
  'exact-file-read',
  'source-summary',
  'entry-point-selection',
  'web-search-sources',
];
const PLAN_TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

function selectedCases() {
  const configured = (process.env.PAPERAGENT_EVAL_COMPARE_CASES ?? '')
    .split(',').map((value) => value.trim()).filter(Boolean);
  const ids = configured.length ? configured : DEFAULT_CASE_IDS;
  const definitions = ids.map((id) => TASK_CASES.find((item) => item.id === id));
  const missing = ids.filter((_, index) => !definitions[index]);
  if (missing.length) throw new Error(`Unknown comparison cases: ${missing.join(', ')}`);
  return definitions;
}

function includesAny(answer, values) {
  return !values?.length || values.some((value) => answer.includes(value));
}

export function scoreComparableAnswer(expect, state, answer) {
  const normalized = answer ?? '';
  const terminalSuccess = state === 'succeeded' || state === 'COMPLETED';
  const required = (expect.includes ?? []).every((value) => normalized.includes(value));
  const alternatives = includesAny(normalized, expect.includesAny);
  const excluded = (expect.excludes ?? []).every((value) => !normalized.includes(value));
  return {
    passed: terminalSuccess && required && alternatives && excluded,
    checks: { terminalSuccess, required, alternatives, excluded },
  };
}

function answerHash(text) {
  if (!text) return null;
  let hash = 2166136261;
  for (const char of text) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  return `fnv1a32:${(hash >>> 0).toString(16).padStart(8, '0')}`;
}

async function runReact(client, projectId, definition) {
  const started = Date.now();
  const session = await client.createSession(projectId, `A-B ReAct ${definition.id}`);
  const accepted = await client.startTask(session.id, definition.instruction);
  accepted.sessionId = session.id;
  const collected = await collect(client, accepted);
  const answer = collected.observed.conclusion;
  return {
    chain: 'REACT',
    state: collected.view.state,
    durationMillis: Date.now() - started,
    score: scoreComparableAnswer(definition.expect, collected.view.state, answer),
    answerHash: answerHash(answer),
    modelCalls: collected.trace.summary?.modelCalls ?? null,
    toolCalls: collected.trace.summary?.toolCalls ?? null,
    totalTokens: collected.trace.summary?.totalTokens ?? null,
    tokenCoverage: 'COMPLETE',
    terminalError: collected.view.error ?? null,
  };
}

async function waitForPlan(client, planId, timeoutMillis = 240_000) {
  const deadline = Date.now() + timeoutMillis;
  let plan = await client.request(`/api/v1/agent/plans/${planId}`);
  while (!PLAN_TERMINAL.has(plan.status)) {
    if (Date.now() >= deadline) throw new Error(`Plan ${planId} did not finish within ${timeoutMillis} ms`);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    plan = await client.request(`/api/v1/agent/plans/${planId}`);
  }
  return plan;
}

async function runPlan(client, projectId, definition) {
  const started = Date.now();
  const session = await client.createSession(projectId, `A-B Plan ${definition.id}`);
  let plan = await client.request(`/api/v1/agent/sessions/${session.id}/plans`, {
    method: 'POST', body: { content: definition.instruction, ragDisabled: false, autoExecute: true },
  });
  plan = await waitForPlan(client, plan.id);
  const events = await client.request(`/api/v1/agent/plans/${plan.id}/events`);
  const answer = plan.finalAnswer ?? '';
  return {
    chain: 'PERSISTENT_PLAN_EXECUTE',
    state: plan.status,
    durationMillis: Date.now() - started,
    score: scoreComparableAnswer(definition.expect, plan.status, answer),
    answerHash: answerHash(answer),
    steps: plan.steps?.length ?? 0,
    attempts: (plan.steps ?? []).reduce((sum, step) => sum + (step.attemptCount ?? 0), 0),
    durableEvents: events.length,
    modelCalls: null,
    toolCalls: null,
    totalTokens: null,
    tokenCoverage: 'UNAVAILABLE_LEGACY_CHAIN_DID_NOT_AGGREGATE_ALL_MODEL_PHASES',
    terminalError: plan.errorMessage ?? null,
  };
}

function aggregate(results, chain) {
  const selected = results.map((item) => item[chain]).filter(Boolean);
  const measuredTokens = selected.filter((item) => Number.isFinite(item.totalTokens));
  return {
    cases: selected.length,
    passed: selected.filter((item) => item.score.passed).length,
    averageDurationMillis: selected.length
      ? Math.round(selected.reduce((sum, item) => sum + item.durationMillis, 0) / selected.length) : null,
    totalTokens: measuredTokens.length === selected.length
      ? measuredTokens.reduce((sum, item) => sum + item.totalTokens, 0) : null,
    tokenCoverage: measuredTokens.length === selected.length ? 'COMPLETE' : 'INCOMPLETE',
  };
}

async function main() {
  const username = process.env.PAPERAGENT_EVAL_USERNAME;
  const password = process.env.PAPERAGENT_EVAL_PASSWORD;
  const projectId = Number(process.env.PAPERAGENT_EVAL_PROJECT_ID ?? 0);
  if (!username || !password || !projectId) {
    throw new Error('Set PAPERAGENT_EVAL_USERNAME, PAPERAGENT_EVAL_PASSWORD, and PAPERAGENT_EVAL_PROJECT_ID');
  }
  const client = new ProductClient(origin);
  await client.login(username, password);
  const results = [];
  for (const definition of selectedCases()) {
    process.stdout.write(`[compare] ${definition.id} ... `);
    const record = { id: definition.id };
    for (const [key, runner] of [['react', runReact], ['plan', runPlan]]) {
      try {
        record[key] = await runner(client, projectId, definition);
      } catch (error) {
        record[key] = {
          chain: key === 'react' ? 'REACT' : 'PERSISTENT_PLAN_EXECUTE',
          score: { passed: false, checks: {} },
          runnerError: redact(error.message),
          tokenCoverage: key === 'react' ? 'UNKNOWN' : 'UNAVAILABLE_LEGACY_CHAIN_DID_NOT_AGGREGATE_ALL_MODEL_PHASES',
        };
      }
    }
    results.push(record);
    process.stdout.write(`ReAct=${record.react.score.passed ? 'PASS' : 'FAIL'} Plan=${record.plan.score.passed ? 'PASS' : 'FAIL'}\n`);
  }
  const report = redact({
    contractVersion: '1.0',
    kind: 'PLAN_REACT_COMPARISON',
    createdAt: new Date().toISOString(),
    environment: { origin, projectId },
    methodology: {
      order: 'ReAct then persistent Plan for each fixed read-only task',
      scoring: 'same terminal-state and answer-content checks; tool names are intentionally not compared',
      tokenLimitation: 'The legacy Plan chain did not aggregate planner, verifier, reflection, and synthesis usage into one public metric. Null is reported instead of a fabricated total.',
    },
    results,
    summary: { react: aggregate(results, 'react'), plan: aggregate(results, 'plan') },
  });
  await mkdir(outputRoot, { recursive: true });
  const file = path.join(outputRoot, `plan-react-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
  await writeFile(file, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(`[compare] report=${file}`);
  console.log(`[compare] ReAct=${report.summary.react.passed}/${report.summary.react.cases} Plan=${report.summary.plan.passed}/${report.summary.plan.cases}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`[compare] fatal: ${redact(error.message)}`);
    process.exitCode = 1;
  });
}
