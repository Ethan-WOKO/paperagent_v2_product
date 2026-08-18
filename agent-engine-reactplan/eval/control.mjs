import { randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { MUTATION_CASE } from './cases.mjs';
import { ProductClient, collect, readSse, resultRecord, runTaskCase, waitForTerminal, writeReport } from './run.mjs';
import { redact, scoreTask, summarize } from './scoring.mjs';

const origin = (process.env.PAPERAGENT_EVAL_ORIGIN ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const projectId = Number(process.env.PAPERAGENT_EVAL_PROJECT_ID ?? 0);
const action = process.argv[2] ?? 'mutation-and-rollback';
const pendingFile = path.resolve('.eval-results/restart-pending.json');

async function client() {
  const username = process.env.PAPERAGENT_EVAL_USERNAME;
  const password = process.env.PAPERAGENT_EVAL_PASSWORD;
  if (!username || !password || !projectId) throw new Error('Set eval username, password, and PAPERAGENT_EVAL_PROJECT_ID');
  const value = new ProductClient(origin);
  await value.login(username, password);
  return value;
}

async function mutationAndRollback(api) {
  const beforeManifest = await api.request(`/api/v1/projects/${projectId}/manifest`);
  const beforeRevisions = await api.request(`/api/v1/projects/${projectId}/revisions`);
  const beforeRevision = beforeRevisions.find(({ current }) => current);
  const session = await api.createSession(projectId, 'EVAL-193 modify-publish');
  const accepted = await api.startTask(session.id, MUTATION_CASE.instruction);
  accepted.sessionId = session.id;
  const view = await waitForTerminal(api, accepted, 360_000);
  const afterManifest = await api.request(`/api/v1/projects/${projectId}/manifest`);
  const collected = await collect(api, { ...accepted, task: view }, { beforeVersion: beforeManifest.version, afterVersion: afterManifest.version });
  const mutationScore = scoreTask(MUTATION_CASE.expect, collected.observed);
  const mutation = resultRecord(MUTATION_CASE.id, collected, mutationScore, collected.trace.summary.totalDurationMillis);

  let rollback;
  if (beforeRevision && afterManifest.version !== beforeManifest.version) {
    const operation = await api.request(`/api/v1/projects/${projectId}/revisions/${beforeRevision.id}/rollback`, {
      method: 'POST', body: {}, headers: { 'Idempotency-Key': `eval-193-rollback-${randomUUID()}`, 'If-Match': afterManifest.version },
    });
    const restored = await api.request(`/api/v1/projects/${projectId}/manifest`);
    const passed = operation.outcome === 'SUCCEEDED' && restored.version === beforeManifest.version;
    rollback = { id: 'rollback', score: { passed, checks: [
      { name: 'rollback-outcome', passed: operation.outcome === 'SUCCEEDED', actual: operation.outcome },
      { name: 'content-version-restored', passed: restored.version === beforeManifest.version, actual: `${afterManifest.version} -> ${restored.version}` },
    ] }, beforeRevisionId: beforeRevision.id, resultRevisionId: operation.resultRevisionId };
  } else {
    rollback = { id: 'rollback', score: { passed: false, checks: [] }, runnerError: 'Mutation did not create a new ProjectVersion' };
  }
  return [mutation, rollback];
}

async function runningCancel(api) {
  const session = await api.createSession(projectId, 'EVAL-193 running cancel');
  const accepted = await api.startTask(session.id, '逐个读取 src 下所有源码并详细比较其作用；开始后持续执行，直到有充分依据再回答。');
  let sawRunning = accepted.task.state === 'running';
  for (let attempt = 0; attempt < 20 && !sawRunning; attempt += 1) {
    await delay(250);
    sawRunning = (await api.task(accepted.turnId, accepted.taskId)).state === 'running';
  }
  await api.cancel(accepted.turnId, accepted.taskId);
  const view = await waitForTerminal(api, accepted, 60_000);
  return { id: 'running-cancel', score: { passed: sawRunning && view.state === 'cancelled', checks: [
    { name: 'observed-running', passed: sawRunning, actual: sawRunning },
    { name: 'terminal-cancelled', passed: view.state === 'cancelled', actual: view.state },
  ] }, taskId: accepted.taskId };
}

async function queuedCancel(api) {
  const accepted = [];
  for (let index = 0; index < 4; index += 1) {
    const session = await api.createSession(projectId, `EVAL-193 queue ${index + 1}`);
    accepted.push(await api.startTask(session.id, `读取 src 目录并逐个概括文件，这是并发评测任务 ${index + 1}。`));
  }
  let queued = null;
  for (let attempt = 0; attempt < 20 && !queued; attempt += 1) {
    for (const task of accepted) {
      const view = await api.task(task.turnId, task.taskId);
      if (view.state === 'queued') { queued = task; break; }
    }
    if (!queued) await delay(200);
  }
  if (queued) await api.cancel(queued.turnId, queued.taskId);
  const queuedView = queued ? await waitForTerminal(api, queued, 60_000) : null;
  await Promise.allSettled(accepted.filter((task) => task !== queued).map((task) => api.cancel(task.turnId, task.taskId)));
  const passed = Boolean(queued) && queuedView?.state === 'cancelled';
  return { id: 'queued-cancel-release', score: { passed, checks: [
    { name: 'observed-queued', passed: Boolean(queued), actual: Boolean(queued) },
    { name: 'queued-terminal-cancelled', passed: queuedView?.state === 'cancelled', actual: queuedView?.state ?? null },
  ] }, taskId: queued?.taskId ?? null };
}

async function sseResume(api) {
  const session = await api.createSession(projectId, 'EVAL-193 SSE resume');
  const accepted = await api.startTask(session.id, '读取 src/main/java/LRUCache.java 并简洁概括。');
  const first = await readSse(await api.events(accepted.turnId, accepted.taskId, 0), { stopAfter: 2 });
  const last = first.at(-1)?.id ?? 0;
  await waitForTerminal(api, accepted);
  const resumed = await readSse(await api.events(accepted.turnId, accepted.taskId, last));
  const ids = resumed.map(({ id }) => id).filter(Number.isFinite);
  const passed = first.length === 2 && ids.length > 0 && ids.every((id) => id > last) && new Set(ids).size === ids.length;
  return { id: 'sse-refresh-resume', score: { passed, checks: [
    { name: 'initial-events', passed: first.length === 2, actual: first.length },
    { name: 'resume-after-last-event-id', passed: ids.every((id) => id > last), actual: { last, firstResumed: ids[0] ?? null } },
    { name: 'no-duplicate-sequences', passed: new Set(ids).size === ids.length, actual: ids.length },
  ] }, taskId: accepted.taskId };
}

async function concurrency(api) {
  const definitions = [1, 2, 3].map((number) => ({
    id: `concurrent-${number}`, instruction: `读取 src/main/java/LRUCache.java，并用一句话概括。这是并发评测 ${number}。`,
    expect: { state: 'succeeded', tools: ['project.read'] },
  }));
  const started = Date.now();
  const results = await Promise.all(definitions.map((definition) => runTaskCase(api, projectId, definition)));
  const wallMillis = Date.now() - started;
  const summedMillis = results.reduce((sum, item) => sum + item.durationMillis, 0);
  const allSucceeded = results.every(({ score }) => score.passed);
  const overlapped = wallMillis < summedMillis;
  const passed = allSucceeded && overlapped;
  return { id: 'multi-conversation-concurrency', score: { passed, checks: [
    { name: 'three-terminal-successes', passed: allSucceeded, actual: results.map(({ state }) => state) },
    { name: 'wall-time-overlap', passed: overlapped, actual: { wallMillis, summedMillis } },
  ] }, tasks: results.map(({ taskId }) => taskId) };
}

async function prepareRestart(api) {
  const point = process.env.PAPERAGENT_EVAL_RESTART_POINT ?? 'sandbox-running';
  const definitions = {
    'model-running': {
      instruction: '检查 src 目录中的主要源码，给出有依据的简洁概括。',
      reached: (events) => events.some((event) => event.type === 'status' && event.state === 'running')
        && !events.some((event) => event.type === 'tool'),
    },
    'sandbox-running': {
      instruction: '在沙箱中编译 src/main/java/Sort.java，依据正式回执诊断失败原因。',
      reached: (events) => events.some((event) => event.type === 'tool'
        && event.name === 'sandbox.execute' && event.state === 'running'),
    },
    'tool-result-persisted': {
      instruction: '读取 src/main/java/LRUCache.java，然后在沙箱中编译或运行它，只依据实际工具结果回答。',
      reached: (events) => events.some((event) => event.type === 'tool'
        && event.name === 'project.read' && event.state === 'succeeded')
        && !events.some((event) => event.type === 'delivery'),
    },
  };
  const definition = definitions[point];
  if (!definition) throw new Error(`Unknown restart point: ${point}`);
  const session = await api.createSession(projectId, `EVAL-193 restart ${point} ${Date.now()}`);
  const accepted = await api.startTask(session.id, definition.instruction);
  let events = [];
  for (let attempt = 0; attempt < 300; attempt += 1) {
    const page = await api.request(`/api/v1/react-agent/sessions/${session.id}/tasks?includeEvents=true&limit=1`);
    events = page.items?.[0]?.events ?? [];
    if (definition.reached(events)) break;
    if (page.items?.[0] && ['succeeded', 'failed', 'cancelled'].includes(page.items[0].task.state)) {
      throw new Error(`Task became ${page.items[0].task.state} before restart point ${point}`);
    }
    await delay(100);
  }
  if (!definition.reached(events)) throw new Error(`Restart point ${point} was not reached`);
  await writeFile(pendingFile, `${JSON.stringify({ projectId, point, sessionId: session.id, turnId: accepted.turnId, taskId: accepted.taskId, sequenceAtStop: events.at(-1)?.sequence ?? 0 }, null, 2)}\n`);
  console.log(`[eval-control] restart point=${point} sequence=${events.at(-1)?.sequence ?? 0} task=${accepted.taskId}`);
}

async function resumeRestart(api) {
  const pending = JSON.parse(await readFile(pendingFile, 'utf8'));
  const view = await waitForTerminal(api, pending, 360_000);
  const trace = await api.trace(pending.turnId, pending.taskId);
  const page = await api.request(`/api/v1/react-agent/sessions/${pending.sessionId}/tasks?includeEvents=true&limit=1`);
  const events = page.items?.[0]?.events ?? [];
  const sequences = events.map(({ sequence }) => sequence);
  const requestedCalls = events.filter((event) => event.type === 'tool' && event.state === 'requested')
    .map(({ callId }) => callId);
  const ordered = sequences.every((sequence, index) => index === 0 || sequence > sequences[index - 1]);
  const noDuplicateRequests = new Set(requestedCalls).size === requestedCalls.length;
  const passed = view.state === 'succeeded' && ordered && noDuplicateRequests;
  return { id: 'engine-restart-recovery', score: { passed, checks: [
    { name: 'terminal-succeeded', passed: view.state === 'succeeded', actual: view.state },
    { name: 'event-sequence-monotonic', passed: ordered, actual: sequences },
    { name: 'no-duplicate-tool-submission', passed: noDuplicateRequests, actual: requestedCalls },
  ] }, point: pending.point, sequenceAtStop: pending.sequenceAtStop, taskId: pending.taskId, metrics: trace.summary };
}

async function main() {
  const api = await client();
  if (action === 'prepare-restart') return prepareRestart(api);
  const results = action === 'resume-restart' ? [await resumeRestart(api)]
    : action === 'mutation-and-rollback' ? await mutationAndRollback(api)
      : action === 'running-cancel' ? [await runningCancel(api)]
        : action === 'queued-cancel' ? [await queuedCancel(api)]
          : action === 'sse-resume' ? [await sseResume(api)]
            : action === 'concurrency' ? [await concurrency(api)]
              : (() => { throw new Error(`Unknown control action: ${action}`); })();
  const report = { contractVersion: '1.0', issue: 193, action, projectId, createdAt: new Date().toISOString(), results, summary: summarize(results) };
  const file = await writeReport(report);
  console.log(`[eval-control] ${results.map(({ id, score }) => `${id}=${score.passed ? 'PASS' : 'FAIL'}`).join(' ')}`);
  console.log(`[eval-control] report=${file}`);
}

function delay(millis) { return new Promise((resolve) => setTimeout(resolve, millis)); }

main().catch((error) => {
  console.error(`[eval-control] fatal: ${redact(error.message)}`);
  process.exitCode = 1;
});
