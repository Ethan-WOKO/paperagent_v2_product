// Engine-side conformance harness for agent-engine-contract 1.0 scenarios.
// Spawns the engine with the stub runner and exercises the control plane:
// submit replay/conflict, SSE resume, cancel idempotency, answer flow,
// event redaction, restart-after-receipt, grant expiry.
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const engineEntry = join(here, '..', 'src', 'index.ts');
const TOKEN = 'conformance-token';

// The frozen positive fixture values (agent-engine-contract fixtures/positive/task-submission.json).
const GRANT = 'grant.test-only-0123456789abcdef0123456789abcdef';
function submission(taskId, overrides = {}) {
  return {
    contractVersion: '1.0',
    taskId,
    requestDigest: '',
    authority: {
      runMode: 'PERSISTENT_PLAN_EXECUTE',
      sessionRef: 'session.1',
      project: { projectId: '95', projectVersion: 'a'.repeat(64) },
      instruction: 'Check whether src/main/java/Sort.java compiles successfully. Do not modify files.',
      permissions: { readProject: true, writeWorkspace: false, executeSandbox: true },
      model: { provider: 'deepseek', model: 'deepseek-v4-pro' },
      ...overrides,
    },
    gateway: { taskGrant: GRANT, expiresAt: '2030-01-01T00:00:00Z' },
  };
}

function canonicalJson(value) {
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value).sort((a, b) => (a[0] < b[0] ? -1 : 1));
    return '{' + entries.map(([k, v]) => JSON.stringify(k) + ':' + canonicalJson(v)).join(',') + '}';
  }
  return JSON.stringify(value);
}
import { createHash } from 'node:crypto';
const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

let failures = 0;
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!ok) failures++;
}

async function postJson(base, path, body, token = TOKEN) {
  const res = await fetch(base + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function getJson(base, path, token = TOKEN) {
  const res = await fetch(base + path, { headers: { Authorization: 'Bearer ' + token } });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

function startEngine(env) {
  const proc = spawn(process.execPath, [engineEntry], {
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  proc.stdout.on('data', () => {});
  proc.stderr.on('data', (d) => console.error('[engine] ' + d.toString().trim()));
  return proc;
}

async function waitUp(base, proc, attempts = 60) {
  for (let i = 0; i < attempts; i++) {
    if (proc.exitCode !== null) throw new Error('engine exited early');
    try {
      await getJson(base, '/v1/tasks');
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 200));
    }
  }
  throw new Error('engine did not become ready');
}

async function readSse(base, path, lastEventId, stopAfter) {
  const res = await fetch(base + path, {
    headers: { Authorization: 'Bearer ' + TOKEN, 'Last-Event-ID': String(lastEventId) },
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const events = [];
  let buffer = '';
  let closed = false;
  while (!closed) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      const dataLines = frame.split('\n').filter((l) => l.startsWith('data: '));
      if (dataLines.length > 0) {
        events.push(JSON.parse(dataLines.map((l) => l.slice(6)).join('\n')));
        if (stopAfter !== undefined && events.length >= stopAfter) {
          await reader.cancel();
          closed = true;
          break;
        }
      }
    }
  }
  return events;
}

async function main() {
  const baseDir = mkdtempSync(join(tmpdir(), 'agent-engine-dsh-conformance-'));

  // ---- Instance A: gateway-backed runner, no question ----
  const dirA = join(baseDir, 'a');
  const procA = startEngine({
    ENGINE_PORT: '18092',
    ENGINE_SERVICE_TOKEN: TOKEN,
    ENGINE_DATA_DIR: dirA,
    STUB_STEP_DELAY_MS: '120',
    STUB_USE_GATEWAY: '1',
  });
  const baseA = 'http://127.0.0.1:18092';
  await waitUp(baseA, procA);

  // S1 submit-exact-replay
  const t1 = submission('task.' + '1'.repeat(64));
  t1.requestDigest = sha256(canonicalJson(t1.authority));
  let r1 = await postJson(baseA, '/v1/tasks', t1);
  check('S1a submit accepted', r1.status === 202 && r1.body?.replayed === false, `status=${r1.status}`);
  let r2 = await postJson(baseA, '/v1/tasks', t1);
  check('S1b exact replay replayed=true', r2.status === 202 && r2.body?.replayed === true, `status=${r2.status}`);

  // S2 submit-digest-conflict
  const t2 = submission(t1.taskId, { instruction: 'Different instruction text for conflict.' });
  t2.requestDigest = sha256(canonicalJson(t2.authority));
  const r3 = await postJson(baseA, '/v1/tasks', t2);
  check('S2 digest conflict 409', r3.status === 409 && r3.body?.code === 'TASK_DIGEST_CONFLICT', `status=${r3.status} code=${r3.body?.code}`);

  // wait terminal
  let viewA = null;
  for (let i = 0; i < 60; i++) {
    viewA = (await getJson(baseA, '/v1/tasks/' + t1.taskId)).body;
    if (['succeeded', 'failed', 'cancelled'].includes(viewA.state)) break;
    await new Promise((r) => setTimeout(r, 200));
  }
  check('S3a terminal reached', viewA && viewA.state === 'succeeded', `state=${viewA?.state}`);
  const eventsA = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 0);
  const seqs = eventsA.map((e) => e.sequence);
  check('S3b contiguous sequences from 1', seqs.length >= 5 && seqs.every((s, i) => s === i + 1), `sequences=${seqs.join(',')}`);
  check('S3c terminal is last', eventsA[eventsA.length - 1]?.type === 'status' && ['succeeded', 'failed', 'cancelled'].includes(eventsA[eventsA.length - 1]?.state));
  const deliveryIdx = eventsA.findIndex((e) => e.type === 'delivery');
  const terminalIdx = eventsA.length - 1;
  check('S3d delivery before terminal', deliveryIdx > -1 && deliveryIdx < terminalIdx);
  check('S3e gateway tool events present', eventsA.some((e) => e.type === 'tool' && e.name === 'sandbox.execute'));

  // S4 sse-resume
  const partial = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 0, 2);
  check('S4a partial read 2 events', partial.length === 2);
  const resumed = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 2);
  check('S4b resume returns only sequence > 2', resumed.length === eventsA.length - 2 && resumed[0].sequence === 3, `got=${resumed.map((e) => e.sequence).join(',')}`);

  // S7 event-redaction
  const rawEvents = readFileSync(join(dirA, t1.taskId, 'events.jsonl'), 'utf8');
  check('S7 no grant in events', !rawEvents.includes(GRANT));
  check('S7b no file body in events', !rawEvents.includes('public class Sort'));

  // S8 restart-after-receipt
  const submissionLogPath = join(dirA, 'gateway-submissions.jsonl');
  const linesBefore = readFileSync(submissionLogPath, 'utf8').split('\n').filter(Boolean).length;
  procA.kill();
  await new Promise((r) => setTimeout(r, 500));
  const procA2 = startEngine({ ENGINE_PORT: '18092', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirA, STUB_STEP_DELAY_MS: '120', STUB_USE_GATEWAY: '1' });
  await waitUp(baseA, procA2);
  const viewAfterRestart = (await getJson(baseA, '/v1/tasks/' + t1.taskId)).body;
  check('S8a terminal preserved after restart', viewAfterRestart?.state === 'succeeded', `state=${viewAfterRestart?.state}`);
  const replayedEvents = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 0);
  check('S8b events replayed after restart', replayedEvents.length === eventsA.length, `count=${replayedEvents.length}`);
  const linesAfter = readFileSync(submissionLogPath, 'utf8').split('\n').filter(Boolean).length;
  check('S8c no duplicate gateway dispatch', linesAfter === linesBefore, `before=${linesBefore} after=${linesAfter}`);
  procA2.kill();

  // ---- Instance B: cancel ----
  const dirB = join(baseDir, 'b');
  const procB = startEngine({ ENGINE_PORT: '18093', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirB, STUB_STEP_DELAY_MS: '3000' });
  const baseB = 'http://127.0.0.1:18093';
  await waitUp(baseB, procB);
  const t5 = submission('task.' + '5'.repeat(64));
  t5.requestDigest = sha256(canonicalJson(t5.authority));
  await postJson(baseB, '/v1/tasks', t5);
  const cancelBody = { contractVersion: '1.0', clientRequestId: 'cancel.' + 'x'.repeat(20) };
  const c1 = await postJson(baseB, `/v1/tasks/${t5.taskId}/cancel`, cancelBody);
  const c2 = await postJson(baseB, `/v1/tasks/${t5.taskId}/cancel`, cancelBody);
  check('S5a cancel accepted', c1.status === 202 && c1.body?.state === 'cancelled', `state=${c1.body?.state}`);
  check('S5b cancel idempotent same terminal', c2.status === 202 && c2.body?.state === 'cancelled' && c2.body?.terminalSequence === c1.body?.terminalSequence);
  const eventsB = await readSse(baseB, '/v1/tasks/' + t5.taskId + '/events', 0);
  check('S5c exactly one cancelled terminal', eventsB.filter((e) => e.type === 'status' && e.state === 'cancelled').length === 1);
  const c3 = await postJson(baseB, `/v1/tasks/${t5.taskId}/cancel`, cancelBody);
  check('S5d cancel after terminal does not rewrite', c3.body?.terminalSequence === c1.body?.terminalSequence);
  procB.kill();

  // ---- Instance C: answer flow ----
  const dirC = join(baseDir, 'c');
  const procC = startEngine({ ENGINE_PORT: '18094', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirC, STUB_STEP_DELAY_MS: '100', STUB_QUESTION: '1' });
  const baseC = 'http://127.0.0.1:18094';
  await waitUp(baseC, procC);
  const t6 = submission('task.' + '6'.repeat(64));
  t6.requestDigest = sha256(canonicalJson(t6.authority));
  await postJson(baseC, '/v1/tasks', t6);
  let viewC = null;
  for (let i = 0; i < 60; i++) {
    viewC = (await getJson(baseC, '/v1/tasks/' + t6.taskId)).body;
    if (viewC.state === 'waiting_user') break;
    await new Promise((r) => setTimeout(r, 200));
  }
  check('S6a waiting_user with pendingQuestionId', viewC?.state === 'waiting_user' && viewC.pendingQuestionId === 'q1', `state=${viewC?.state}`);
  const wrong = await postJson(baseC, `/v1/tasks/${t6.taskId}/answer`, { contractVersion: '1.0', clientRequestId: 'answer.' + 'a'.repeat(20), questionId: 'other', answer: 'no' });
  check('S6b wrong questionId 409', wrong.status === 409 && wrong.body?.code === 'QUESTION_MISMATCH', `status=${wrong.status}`);
  const a1 = await postJson(baseC, `/v1/tasks/${t6.taskId}/answer`, { contractVersion: '1.0', clientRequestId: 'answer.' + 'a'.repeat(20), questionId: 'q1', answer: 'yes' });
  check('S6c answer accepted', a1.status === 202);
  let viewC2 = null;
  for (let i = 0; i < 60; i++) {
    viewC2 = (await getJson(baseC, '/v1/tasks/' + t6.taskId)).body;
    if (viewC2.state === 'succeeded') break;
    await new Promise((r) => setTimeout(r, 200));
  }
  check('S6d succeeds after answer', viewC2?.state === 'succeeded', `state=${viewC2?.state}`);
  const a2 = await postJson(baseC, `/v1/tasks/${t6.taskId}/answer`, { contractVersion: '1.0', clientRequestId: 'answer.' + 'a'.repeat(20), questionId: 'q1', answer: 'different' });
  // After the first answer the question closes; a second different answer must 409 (ANSWER_CONFLICT or NO_PENDING_QUESTION).
  check('S6e conflicting second answer 409', a2.status === 409, `status=${a2.status} code=${a2.body?.code}`);
  procC.kill();

  // ---- Instance D: expired grant ----
  const dirD = join(baseDir, 'd');
  const procD = startEngine({ ENGINE_PORT: '18095', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirD, STUB_STEP_DELAY_MS: '100', STUB_USE_GATEWAY: '1' });
  const baseD = 'http://127.0.0.1:18095';
  await waitUp(baseD, procD);
  const t7 = submission('task.' + '7'.repeat(64));
  t7.gateway = { taskGrant: GRANT, expiresAt: '2020-01-01T00:00:00Z' };
  t7.requestDigest = sha256(canonicalJson(t7.authority));
  await postJson(baseD, '/v1/tasks', t7);
  let viewD = null;
  for (let i = 0; i < 60; i++) {
    viewD = (await getJson(baseD, '/v1/tasks/' + t7.taskId)).body;
    if (viewD.state === 'failed') break;
    await new Promise((r) => setTimeout(r, 200));
  }
  check('S9 expired grant fails with authorization category', viewD?.state === 'failed' && viewD?.error?.category === 'authorization', `state=${viewD?.state} category=${viewD?.error?.category}`);
  check('S9b no gateway dispatch with expired grant', !existsSync(join(dirD, 'gateway-submissions.jsonl')));
  procD.kill();

  // ---- Instance E: auth ----
  const dirE = join(baseDir, 'e');
  const procE = startEngine({ ENGINE_PORT: '18096', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirE });
  const baseE = 'http://127.0.0.1:18096';
  await waitUp(baseE, procE);
  const noAuth = await getJson(baseE, '/v1/tasks', 'wrong-token');
  check('S10 unauthenticated request rejected 401', noAuth.status === 401, `status=${noAuth.status}`);
  procE.kill();

  rmSync(baseDir, { recursive: true, force: true });
  console.log(failures === 0 ? '\nALL CONFORMANCE CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('conformance harness crashed:', e);
  process.exit(1);
});
