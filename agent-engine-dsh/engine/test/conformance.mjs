// Engine-side conformance harness for agent-engine-contract 1.0.
// Consumes the shared positive fixtures directly from agent-engine-contract/
// and exercises the control plane: submit replay/conflict, SSE resume,
// cancel idempotency, answer idempotency (answerDigest), event redaction,
// restart-after-receipt, non-terminal restart, grant expiry, auth, Unicode
// canonical ordering.
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const engineEntry = join(here, '..', 'src', 'index.ts');
const contractDir = join(here, '..', '..', '..', 'agent-engine-contract');
const TOKEN = 'conformance-token';

const fixture = JSON.parse(readFileSync(join(contractDir, 'conformance', 'fixtures', 'positive', 'task-submission.json'), 'utf8'));
const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

function canonicalJson(value) {
  const byCodePoint = (a, b) => {
    const cp = (s) => [...s].map((ch) => ch.codePointAt(0));
    const l = cp(a);
    const r = cp(b);
    for (let i = 0; i < Math.min(l.length, r.length); i++) if (l[i] !== r[i]) return l[i] - r[i];
    return l.length - r.length;
  };
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value).sort((a, b) => byCodePoint(a[0], b[0]));
    return '{' + entries.map(([k, v]) => JSON.stringify(k) + ':' + canonicalJson(v)).join(',') + '}';
  }
  return JSON.stringify(value);
}

function submission(taskId, authorityOverrides = {}, gatewayOverrides = {}) {
  const body = structuredClone(fixture);
  body.taskId = taskId;
  body.authority = { ...body.authority, ...authorityOverrides };
  body.gateway = { ...body.gateway, ...gatewayOverrides };
  body.requestDigest = sha256(canonicalJson(body.authority));
  return body;
}

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
    env: { ...process.env, ...env, ENGINE_RUNNER: env.ENGINE_RUNNER ?? 'stub' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  proc.stdout.on('data', () => {});
  proc.stderr.on('data', (d) => console.error('[engine] ' + d.toString().trim()));
  return proc;
}

async function waitUp(base, proc, attempts = 80) {
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

async function waitForState(base, taskId, states, attempts = 100) {
  for (let i = 0; i < attempts; i++) {
    const view = (await getJson(base, '/v1/tasks/' + taskId)).body;
    if (states.includes(view?.state)) return view;
    await new Promise((r) => setTimeout(r, 200));
  }
  return null;
}

function answerBody(clientSuffix, questionId, answer) {
  return {
    contractVersion: '1.0',
    clientRequestId: 'answer.' + clientSuffix,
    questionId,
    answer,
    answerDigest: sha256(answer),
  };
}

async function main() {
  const baseDir = mkdtempSync(join(tmpdir(), 'agent-engine-dsh-conformance-'));

  // ---- U0: Unicode canonical ordering (unit level) ----
  const unicodeCanonical = canonicalJson({ b: 1, a: 2, '😀': 3 });
  check('U0 unicode code-point key order', unicodeCanonical === '{"a":2,"b":1,"😀":3}', unicodeCanonical);

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

  // S1 submit-exact-replay (shared fixture digest)
  const t1 = submission('task.' + '1'.repeat(64));
  check('S1f fixture digest matches shared requestDigest', t1.requestDigest === fixture.requestDigest, t1.requestDigest);
  let r1 = await postJson(baseA, '/v1/tasks', t1);
  check('S1a submit accepted', r1.status === 202 && r1.body?.replayed === false, `status=${r1.status}`);
  let r2 = await postJson(baseA, '/v1/tasks', t1);
  check('S1b exact replay replayed=true', r2.status === 202 && r2.body?.replayed === true, `status=${r2.status}`);

  // S2 submit-digest-conflict
  const t2 = submission(t1.taskId, { instruction: 'Different instruction text for conflict.' });
  const r3 = await postJson(baseA, '/v1/tasks', t2);
  check('S2 digest conflict 409', r3.status === 409 && r3.body?.code === 'TASK_DIGEST_CONFLICT', `status=${r3.status} code=${r3.body?.code}`);

  const viewA = await waitForState(baseA, t1.taskId, ['succeeded', 'failed', 'cancelled']);
  check('S3a terminal reached', viewA?.state === 'succeeded', `state=${viewA?.state}`);
  const eventsA = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 0);
  const seqs = eventsA.map((e) => e.sequence);
  check('S3b contiguous sequences from 1', seqs.length >= 5 && seqs.every((s, i) => s === i + 1), `sequences=${seqs.join(',')}`);
  check('S3c terminal is last', eventsA[eventsA.length - 1]?.type === 'status' && ['succeeded', 'failed', 'cancelled'].includes(eventsA[eventsA.length - 1]?.state));
  const deliveryIdx = eventsA.findIndex((e) => e.type === 'delivery');
  check('S3d delivery before terminal', deliveryIdx > -1 && deliveryIdx < eventsA.length - 1);
  check('S3e gateway tool events present', eventsA.some((e) => e.type === 'tool' && e.name === 'sandbox.execute'));

  // S4 sse-resume
  const partial = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 0, 2);
  check('S4a partial read 2 events', partial.length === 2);
  const resumed = await readSse(baseA, '/v1/tasks/' + t1.taskId + '/events', 2);
  check('S4b resume returns only sequence > 2', resumed.length === eventsA.length - 2 && resumed[0].sequence === 3, `got=${resumed.map((e) => e.sequence).join(',')}`);

  // S7 event-redaction
  const rawEvents = readFileSync(join(dirA, t1.taskId, 'events.jsonl'), 'utf8');
  check('S7 no grant in events', !rawEvents.includes(fixture.gateway.taskGrant));
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

  // ---- Instance B: non-terminal restart (no duplicated events) ----
  const dirB = join(baseDir, 'b');
  const procB = startEngine({ ENGINE_PORT: '18093', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirB, STUB_STEP_DELAY_MS: '8000' });
  const baseB = 'http://127.0.0.1:18093';
  await waitUp(baseB, procB);
  const tN = submission('task.' + '9'.repeat(64));
  await postJson(baseB, '/v1/tasks', tN);
  // wait until the message event lands (phase messaged) then kill mid-run
  let killedMidRun = false;
  let lastSeen = 0;
  for (let i = 0; i < 200 && !killedMidRun; i++) {
    const next = await readSse(baseB, '/v1/tasks/' + tN.taskId + '/events', lastSeen, 1);
    if (next.length === 1) {
      lastSeen = next[0].sequence;
      if (next[0].type === 'message') {
        procB.kill();
        killedMidRun = true;
      }
    }
  }
  check('S8d engine killed mid-run after message', killedMidRun);
  await new Promise((r) => setTimeout(r, 500));
  const procB2 = startEngine({ ENGINE_PORT: '18093', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirB, STUB_STEP_DELAY_MS: '100' });
  await waitUp(baseB, procB2);
  // Cold-start recovery is PAUSED: the non-terminal task must not advance
  // until Java resubmits the exact taskId/digest with a fresh grant.
  await new Promise((r) => setTimeout(r, 600));
  const pausedView = (await getJson(baseB, '/v1/tasks/' + tN.taskId)).body;
  const persistedLines = readFileSync(join(dirB, tN.taskId, 'events.jsonl'), 'utf8').split('\n').filter(Boolean).length;
  check('S8e paused after cold start (no grant, no advance)', pausedView?.state === 'running' && pausedView?.lastSequence === persistedLines, `state=${pausedView?.state} seq=${pausedView?.lastSequence}/${persistedLines}`);
  const replay = await postJson(baseB, '/v1/tasks', tN);
  check('S8f exact replay re-arms paused task', replay.status === 202 && replay.body?.replayed === true, `status=${replay.status}`);
  const viewN = await waitForState(baseB, tN.taskId, ['succeeded', 'failed', 'cancelled']);
  check('S8g non-terminal task resumes to terminal after replay', viewN?.state === 'succeeded', `state=${viewN?.state}`);
  const eventsN = await readSse(baseB, '/v1/tasks/' + tN.taskId + '/events', 0);
  check('S8h no duplicated message after restart', eventsN.filter((e) => e.type === 'message').length === 1, `messages=${eventsN.filter((e) => e.type === 'message').length}`);
  check('S8i sequences contiguous after restart', eventsN.map((e) => e.sequence).every((s, i) => s === i + 1));
  procB2.kill();

  // ---- Instance C: cancel ----
  const dirC = join(baseDir, 'c');
  const procC = startEngine({ ENGINE_PORT: '18094', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirC, STUB_STEP_DELAY_MS: '3000' });
  const baseC = 'http://127.0.0.1:18094';
  await waitUp(baseC, procC);
  const t5 = submission('task.' + '5'.repeat(64));
  await postJson(baseC, '/v1/tasks', t5);
  const cancelBody = { contractVersion: '1.0', clientRequestId: 'cancel.' + 'x'.repeat(20) };
  const c1 = await postJson(baseC, `/v1/tasks/${t5.taskId}/cancel`, cancelBody);
  const c2 = await postJson(baseC, `/v1/tasks/${t5.taskId}/cancel`, cancelBody);
  check('S5a cancel accepted', c1.status === 202 && c1.body?.state === 'cancelled', `state=${c1.body?.state}`);
  check('S5b cancel idempotent same terminal', c2.status === 202 && c2.body?.state === 'cancelled' && c2.body?.terminalSequence === c1.body?.terminalSequence);
  const eventsC = await readSse(baseC, '/v1/tasks/' + t5.taskId + '/events', 0);
  check('S5c exactly one cancelled terminal', eventsC.filter((e) => e.type === 'status' && e.state === 'cancelled').length === 1);
  const c3 = await postJson(baseC, `/v1/tasks/${t5.taskId}/cancel`, cancelBody);
  check('S5d cancel after terminal does not rewrite', c3.body?.terminalSequence === c1.body?.terminalSequence);
  procC.kill();

  // ---- Instance D: answer flow with answerDigest ----
  const dirD = join(baseDir, 'd');
  const procD = startEngine({ ENGINE_PORT: '18095', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirD, STUB_STEP_DELAY_MS: '100', STUB_QUESTION: '1', STUB_ANSWER_DELAY_MS: '1000' });
  const baseD = 'http://127.0.0.1:18095';
  await waitUp(baseD, procD);
  const t6 = submission('task.' + '6'.repeat(64));
  await postJson(baseD, '/v1/tasks', t6);
  const viewD = await waitForState(baseD, t6.taskId, ['waiting_user']);
  check('S6a waiting_user with pendingQuestionId', viewD?.pendingQuestionId === 'q1', `state=${viewD?.state}`);
  const wrongQ = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('b'.repeat(20), 'other', 'no'));
  check('S6b wrong questionId 409 QUESTION_NOT_PENDING', wrongQ.status === 409 && wrongQ.body?.code === 'QUESTION_NOT_PENDING', `status=${wrongQ.status} code=${wrongQ.body?.code}`);
  const badDigest = answerBody('c'.repeat(20), 'q1', 'yes');
  badDigest.answerDigest = 'f'.repeat(64);
  const d0 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, badDigest);
  check('S6c invalid answerDigest 400', d0.status === 400 && d0.body?.code === 'INVALID_ANSWER', `status=${d0.status}`);
  const a1 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('c'.repeat(20), 'q1', 'yes'));
  check('S6d answer accepted', a1.status === 202);
  // while runner waits (STUB_ANSWER_DELAY_MS=1000), conflict checks are race-free
  const a2 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('d'.repeat(20), 'q1', 'no'));
  check('S6e different answerDigest 409 QUESTION_ANSWER_CONFLICT', a2.status === 409 && a2.body?.code === 'QUESTION_ANSWER_CONFLICT', `status=${a2.status} code=${a2.body?.code}`);
  const a3 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('c'.repeat(20), 'q1', 'no'));
  check('S6f reused clientRequestId different digest 409 ANSWER_REQUEST_CONFLICT', a3.status === 409 && a3.body?.code === 'ANSWER_REQUEST_CONFLICT', `status=${a3.status} code=${a3.body?.code}`);
  const a4 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('c'.repeat(20), 'q1', 'yes'));
  check('S6g exact answer replay 202', a4.status === 202);
  const viewD2 = await waitForState(baseD, t6.taskId, ['succeeded']);
  check('S6h succeeds after answer', viewD2?.state === 'succeeded', `state=${viewD2?.state}`);
  // Post-terminal idempotency: an exact accepted answer stays 202 after the
  // task ended; different content still conflicts with 409.
  const a5 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('c'.repeat(20), 'q1', 'yes'));
  check('S6i exact replay after terminal 202', a5.status === 202, `status=${a5.status}`);
  const a6 = await postJson(baseD, `/v1/tasks/${t6.taskId}/answer`, answerBody('c'.repeat(20), 'q1', 'no'));
  check('S6j conflicting replay after terminal 409', a6.status === 409, `status=${a6.status} code=${a6.body?.code}`);
  procD.kill();

  // ---- Instance E: expired grant ----
  const dirE = join(baseDir, 'e');
  const procE = startEngine({ ENGINE_PORT: '18096', ENGINE_SERVICE_TOKEN: TOKEN, ENGINE_DATA_DIR: dirE, STUB_STEP_DELAY_MS: '100', STUB_USE_GATEWAY: '1' });
  const baseE = 'http://127.0.0.1:18096';
  await waitUp(baseE, procE);
  const t7 = submission('task.' + '7'.repeat(64), {}, { expiresAt: '2020-01-01T00:00:00Z' });
  await postJson(baseE, '/v1/tasks', t7);
  const viewE = await waitForState(baseE, t7.taskId, ['failed']);
  check('S9 expired grant fails with authorization category', viewE?.error?.category === 'authorization', `state=${viewE?.state} category=${viewE?.error?.category}`);
  check('S9b no gateway dispatch with expired grant', !existsSync(join(dirE, 'gateway-submissions.jsonl')));
  procE.kill();

  // ---- Instance F: auth fail-closed ----
  const dirF = join(baseDir, 'f');
  const procF = startEngine({ ENGINE_PORT: '18097', ENGINE_SERVICE_TOKEN: '', ENGINE_DATA_DIR: dirF });
  const baseF = 'http://127.0.0.1:18097';
  await waitUp(baseF, procF);
  const noToken = await getJson(baseF, '/v1/tasks', TOKEN);
  check('S10 unconfigured token fails closed', noToken.status === 401, `status=${noToken.status}`);
  procF.kill();

  rmSync(baseDir, { recursive: true, force: true });
  console.log(failures === 0 ? '\nALL CONFORMANCE CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('conformance harness crashed:', e);
  process.exit(1);
});
