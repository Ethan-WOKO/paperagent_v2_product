// Formal-path tests with the real DshRunner + HttpGatewayClient, no stub:
//  - T4-recovery: crash after Receipt, restart, exact replay → no duplicate
//    sandbox dispatch, contiguous events, delivery reuses the original receipt.
//  - F1: fresh tasks take the fresh path (instruction, never 'Continue').
//  - F2: sandbox digest covers argv+inputs+timeoutMillis canonically; the mock
//    gateway rejects any non-canonical digest.
//  - F3: crash inside the submit→receipt window (executionRef + fixed deadline
//    persisted BEFORE the receipt) → recovery polls the ORIGINAL execution with
//    the SAME deadline, never re-dispatches.
//  - F4: waiting_user restart → replay re-arms, the accepted answer BODY is
//    injected into the model transcript and the loop resumes.
//  - F5: recovery prompt carries the frozen instruction, ProjectVersion,
//    completed receipts and the no-resubmit rule; the ledger re-adopts the
//    completed receipt so a directly-concluding model still passes the gate.
//  - F6: recovery with an already-expired persisted deadline fails with
//    SANDBOX_STATUS_DEADLINE_EXCEEDED and ZERO status requests.
//  - F7: the deadline is checked before every status request and sleeps never
//    run past it — no status request is issued after the deadline.
//  - F8: duplicate sandbox input paths are rejected BEFORE any dispatch.
//  - F9/F10: TIMED_OUT / SYSTEM_ERROR receipts fail the task with a
//    sandbox_system code and NO delivery.
//  - F11: a FAILED receipt (compile error) is a legitimate answer — the task
//    succeeds with delivery (T2 semantics).
//  - Budget: 20 model calls allowed, the 21st is rejected before dispatch.
//  - ask_user: formal question/answer gate (no stub finalizer).
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startMockGateway } from './mock-gateway.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const engineEntry = join(here, '..', 'src', 'index.ts');
const contractDir = join(here, '..', '..', '..', 'agent-engine-contract');
const TOKEN = 'formal-token';
const fixture = JSON.parse(readFileSync(join(contractDir, 'conformance', 'fixtures', 'positive', 'task-submission.json'), 'utf8'));

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!ok) failures++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function startEngine(env) {
  const proc = spawn(process.execPath, [engineEntry], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  proc.stdout.on('data', () => {});
  proc.stderr.on('data', (d) => {
    const text = d.toString().trim();
    if (text && !text.includes('[fake') && !text.includes('fake-llm adapter')) console.error('[engine] ' + text.slice(0, 300));
  });
  return proc;
}

async function waitUp(base, proc) {
  for (let i = 0; i < 100; i++) {
    if (proc.exitCode !== null) throw new Error('engine exited early');
    try {
      await fetch(base + '/v1/tasks', { headers: { Authorization: 'Bearer ' + TOKEN } });
      return;
    } catch {
      await sleep(300);
    }
  }
  throw new Error('engine not ready');
}

const post = (base, path, body) =>
  fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + TOKEN }, body: JSON.stringify(body) })
    .then(async (r) => ({ status: r.status, body: await r.json().catch(() => null) }));
const get = (base, path) =>
  fetch(base + path, { headers: { Authorization: 'Bearer ' + TOKEN } }).then(async (r) => ({ status: r.status, body: await r.json().catch(() => null) }));

async function readSse(base, path, lastEventId, stopAfter) {
  const res = await fetch(base + path, { headers: { Authorization: 'Bearer ' + TOKEN, 'Last-Event-ID': String(lastEventId) } });
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
      const lines = frame.split('\n').filter((l) => l.startsWith('data: '));
      if (lines.length) {
        events.push(JSON.parse(lines.map((l) => l.slice(6)).join('\n')));
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

async function waitForState(base, taskId, states, attempts = 120) {
  for (let i = 0; i < attempts; i++) {
    const view = (await get(base, '/v1/tasks/' + taskId)).body;
    if (view && states.includes(view.state)) return view;
    await sleep(300);
  }
  return null;
}

async function main() {
  const baseDir = mkdtempSync(join(tmpdir(), 'dsh-formal-'));

  // ---- T4 recovery: crash after Receipt ----
  {
    const gatewayPort = 18310;
    const gwLog = join(baseDir, 'gw-submissions.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 't4');
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_DELAY_MS: '5000',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18311',
      ENGINE_DATA_DIR: dir,
    };
    const base = 'http://127.0.0.1:18311';
    const t = { ...fixture, taskId: 'task.' + 'b'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);

    // wait until a sandbox receipt appears in persisted events, then kill
    // (FAKE_DELAY_MS keeps the finalize step pending so the crash lands
    // between Receipt persistence and delivery)
    let killed = false;
    let lastSeen = 0;
    for (let i = 0; i < 200 && !killed; i++) {
      const next = await readSse(base, '/v1/tasks/' + t.taskId + '/events', lastSeen, 1);
      if (next.length === 1) {
        lastSeen = next[0].sequence;
        if (next[0].type === 'tool' && next[0].receiptRef) {
          proc.kill();
          killed = true;
        }
      }
    }
    check('T4a engine killed after receipt event', killed);
    await sleep(600);

    const proc2 = startEngine(env);
    await waitUp(base, proc2);
    // paused cold start
    const paused = (await get(base, '/v1/tasks/' + t.taskId)).body;
    check('T4b paused after restart', paused?.state === 'running' || paused?.state === 'waiting_user', `state=${paused?.state}`);
    // exact replay with fresh grant
    const replay = await post(base, '/v1/tasks', t);
    check('T4c exact replay re-arms', replay.status === 202 && replay.body?.replayed === true, `status=${replay.status}`);
    const view = await waitForState(base, t.taskId, ['succeeded', 'failed', 'cancelled']);
    check('T4d terminal after replay', view?.state === 'succeeded', `state=${view?.state} code=${view?.error?.code}`);
    const events = await readSse(base, '/v1/tasks/' + t.taskId + '/events', 0);
    check('T4e contiguous sequences', events.map((e) => e.sequence).every((s, i) => s === i + 1), `count=${events.length}`);
    const delivery = events.find((e) => e.type === 'delivery');
    const receiptRefs = delivery?.receiptRefs ?? [];
    const toolReceipts = events.filter((e) => e.type === 'tool' && e.receiptRef).map((e) => e.receiptRef);
    check('T4f delivery carries the original receipt', toolReceipts.length > 0 && receiptRefs.includes(toolReceipts[0]), `receiptRefs=${receiptRefs.join(',')}`);
    const gwLines = readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length;
    check('T4g exactly one gateway submission across restart', gwLines === 1, `submissions=${gwLines}`);
    proc2.kill();
    gw.close();
  }

  // ---- Budget: 20 allowed, 21st rejected before dispatch ----
  {
    const gatewayPort = 18312;
    const gwLog = join(baseDir, 'gw-budget.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 'budget');
    const base = 'http://127.0.0.1:18313';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_MODE: 'budget',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18313',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + 'c'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const view = await waitForState(base, t.taskId, ['failed'], 400);
    check('B1 budget exhaustion fails the task', view?.error?.code === 'MODEL_BUDGET_EXCEEDED', `code=${view?.error?.code}`);
    const callsPath = join(dir, 'fake-llm-calls.jsonl');
    const calls = existsSync(callsPath) ? readFileSync(callsPath, 'utf8').split('\n').filter(Boolean).length : 0;
    check('B2 exactly 20 provider calls, 21st never dispatched', calls === 20, `calls=${calls}`);
    proc.kill();
    gw.close();
  }

  // ---- ask_user: formal question/answer gate ----
  {
    const gatewayPort = 18314;
    const gwLog = join(baseDir, 'gw-ask.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 'ask');
    const base = 'http://127.0.0.1:18315';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_MODE: 'ask',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18315',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + 'd'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const waiting = await waitForState(base, t.taskId, ['waiting_user'], 200);
    check('A1 waiting_user with pending question', waiting?.pendingQuestionId != null, `questionId=${waiting?.pendingQuestionId}`);
    const answer = 'yes, continue';
    const sha = (await import('node:crypto')).createHash('sha256').update(answer).digest('hex');
    const a = await post(base, `/v1/tasks/${t.taskId}/answer`, {
      contractVersion: '1.0',
      clientRequestId: 'answer.' + 'e'.repeat(20),
      questionId: waiting.pendingQuestionId,
      answer,
      answerDigest: sha,
    });
    check('A2 answer accepted', a.status === 202, `status=${a.status}`);
    // The ask flow never runs the sandbox, so the P1 receipt completion gate
    // must fail the task with the dedicated code — proving both the
    // question/answer resume AND the program-enforced receipt requirement.
    const view = await waitForState(base, t.taskId, ['failed', 'succeeded'], 200);
    check('A3 terminal after formal answer', view?.state === 'failed', `state=${view?.state}`);
    check('A3b receipt gate code', view?.error?.code === 'RECEIPT_REQUIRED_NOT_SATISFIED', `code=${view?.error?.code}`);
    const events = await readSse(base, '/v1/tasks/' + t.taskId + '/events', 0);
    check('A4 loop resumed after answer (message event)', events.some((e) => e.type === 'message'), '');
    check('A5 no stub finalizer artifacts', events.every((e) => e.type !== 'delivery' || (e.receiptRefs ?? []).every((r) => !String(r).startsWith('receipt.stub'))), '');
    proc.kill();
    gw.close();
  }

  // ---- F1: fresh tasks must take the fresh path ----
  {
    const gatewayPort = 18316;
    const gwLog = join(baseDir, 'gw-f1.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 'f1');
    const base = 'http://127.0.0.1:18317';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18317',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '1'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const view = await waitForState(base, t.taskId, ['succeeded', 'failed'], 200);
    check('F1a fresh run succeeds through the canonical-digest mock', view?.state === 'succeeded', `state=${view?.state}`);
    const calls = existsSync(join(dir, 'fake-llm-calls.jsonl'))
      ? readFileSync(join(dir, 'fake-llm-calls.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
      : [];
    const firstUserTexts = (calls[0]?.userTexts ?? []).join(' ');
    check('F1b first model call received the task instruction (no fake resume)', firstUserTexts.includes('Check whether') && !firstUserTexts.includes('Continue to completion.'), `first=${firstUserTexts.slice(0, 80)}`);
    const gwLines = readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length;
    check('F1c exactly one gateway submission (digest accepted)', gwLines === 1, `submissions=${gwLines}`);
    proc.kill();
    gw.close();
  }

  // ---- F2: sandbox digest covers argv+inputs+timeoutMillis canonically ----
  {
    const { canonicalJson, sha256Hex } = await import('../src/canonical.ts');
    const { createHash } = await import('node:crypto');
    const inputs = [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }];
    const argv = ['javac', 'src/main/java/Sort.java'];
    const d1 = sha256Hex(canonicalJson({ argv, inputs, timeoutMillis: 120000 }));
    const d2 = sha256Hex(canonicalJson({ argv, inputs, timeoutMillis: 150000 }));
    const oldForm = createHash('sha256').update(JSON.stringify([argv, inputs])).digest('hex');
    check('F2a timeoutMillis participates in the digest', d1 !== d2, '');
    check('F2b digest is canonical JSON, not the legacy array form', d1 !== oldForm, '');

    // Negative control through the gateway double: a submission digest in the
    // legacy form must be rejected with SUBMIT_DIGEST_INVALID.
    const gatewayPort = 18318;
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: join(baseDir, 'gw-f2.jsonl') });
    const raw = await fetch(`http://127.0.0.1:${gatewayPort}/internal/v1/agent-engine/tasks/task.${'a'.repeat(64)}/sandbox-executions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + 'g'.repeat(48) },
      body: JSON.stringify({
        contractVersion: '1.0',
        clientRequestId: 'call.' + '9'.repeat(20),
        requestDigest: oldForm,
        argv,
        inputs,
        timeoutMillis: 120000,
      }),
    });
    const rejected = await raw.json();
    check('F2c mock gateway rejects a non-canonical digest', raw.status === 400 && rejected.code === 'SUBMIT_DIGEST_INVALID', `status=${raw.status} code=${rejected.code}`);
    gw.close();
  }

  // ---- F3: crash inside the submit→receipt window ----
  {
    const gatewayPort = 18320;
    const gwLog = join(baseDir, 'gw-f3.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog, holdPolls: 2 });
    const dir = join(baseDir, 'f3');
    const base = 'http://127.0.0.1:18321';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_DELAY_MS: '5000',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18321',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '8'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    // Kill as soon as the PROVISIONAL ledger entry (executionRef + fixed
    // deadline, receiptRef still null) is persisted — i.e. after the 202, before
    // any receipt was fetched.
    const ledgerPath = join(dir, t.taskId, 'tool-ledger.jsonl');
    let killed = false;
    for (let i = 0; i < 100 && !killed; i++) {
      if (existsSync(ledgerPath) && readFileSync(ledgerPath, 'utf8').includes('"receiptRef":null')) {
        proc.kill();
        killed = true;
      }
      await sleep(100);
    }
    check('F3a killed in the submit→receipt window', killed, '');
    const gwMid = readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length;
    check('F3b one gateway submission before the crash', gwMid === 1, `submissions=${gwMid}`);
    await sleep(600);

    const proc2 = startEngine(env);
    await waitUp(base, proc2);
    const paused = (await get(base, '/v1/tasks/' + t.taskId)).body;
    check('F3c paused after restart', paused?.state === 'running', `state=${paused?.state}`);
    const replay = await post(base, '/v1/tasks', t);
    check('F3d exact replay re-arms', replay.status === 202 && replay.body?.replayed === true, `status=${replay.status}`);
    const view = await waitForState(base, t.taskId, ['succeeded', 'failed', 'cancelled']);
    check('F3e terminal after recovery', view?.state === 'succeeded', `state=${view?.state}`);
    const gwAfter = readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length;
    check('F3f never re-dispatches the same call', gwAfter === 1, `submissions=${gwAfter}`);
    const ledger = readFileSync(ledgerPath, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
    const clientIds = new Set(ledger.map((entry) => entry.clientRequestId));
    check('F3g one stable clientRequestId across the crash', clientIds.size === 1, `ids=${[...clientIds].join(',')}`);
    const provisional = ledger.find((entry) => entry.receiptRef === null);
    const completed = ledger.find((entry) => typeof entry.receiptRef === 'string');
    check('F3h fixed deadline persisted before the receipt and reused', provisional != null && completed != null && Number(provisional.deadlineAt) === Number(completed.deadlineAt), `deadlineAt=${provisional?.deadlineAt}`);
    const events = await readSse(base, '/v1/tasks/' + t.taskId + '/events', 0);
    const delivery = events.find((e) => e.type === 'delivery');
    check('F3i delivery carries the receipt after recovery', (delivery?.receiptRefs ?? []).length > 0, `receiptRefs=${(delivery?.receiptRefs ?? []).join(',')}`);
    check('F3j contiguous sequences after recovery', events.map((e) => e.sequence).every((s, i) => s === i + 1), `count=${events.length}`);
    proc2.kill();
    gw.close();
  }

  // ---- F4: waiting_user restart — answer body reaches the resumed loop ----
  {
    const gatewayPort = 18322;
    const gwLog = join(baseDir, 'gw-f4.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 'f4');
    const base = 'http://127.0.0.1:18323';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_MODE: 'ask',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18323',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '2'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const waiting = await waitForState(base, t.taskId, ['waiting_user'], 200);
    check('F4a waiting_user reached', waiting?.pendingQuestionId != null, `questionId=${waiting?.pendingQuestionId}`);
    proc.kill();
    await sleep(600);

    const proc2 = startEngine(env);
    await waitUp(base, proc2);
    const paused = (await get(base, '/v1/tasks/' + t.taskId)).body;
    check('F4b waiting_user paused after restart (question persisted)', paused?.state === 'waiting_user' && paused?.pendingQuestionId === waiting?.pendingQuestionId, `state=${paused?.state}`);
    const replay = await post(base, '/v1/tasks', t);
    check('F4c replay re-arms the waiting_user task', replay.status === 202 && replay.body?.replayed === true, `status=${replay.status}`);
    const answerBody = 'yes, continue with the javac approach';
    const sha = (await import('node:crypto')).createHash('sha256').update(answerBody).digest('hex');
    const a = await post(base, `/v1/tasks/${t.taskId}/answer`, {
      contractVersion: '1.0',
      clientRequestId: 'answer.' + '4'.repeat(20),
      questionId: waiting.pendingQuestionId,
      answer: answerBody,
      answerDigest: sha,
    });
    check('F4d answer accepted after restart', a.status === 202, `status=${a.status}`);
    const view = await waitForState(base, t.taskId, ['failed', 'succeeded'], 200);
    check('F4e terminal after resumed loop', view?.state === 'failed', `state=${view?.state}`);
    check('F4f receipt gate code', view?.error?.code === 'RECEIPT_REQUIRED_NOT_SATISFIED', `code=${view?.error?.code}`);
    const calls = existsSync(join(dir, 'fake-llm-calls.jsonl'))
      ? readFileSync(join(dir, 'fake-llm-calls.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
      : [];
    check('F4g delivered answer BODY reached the model transcript', calls.some((c) => (c.userTexts ?? []).join(' ').includes(answerBody)), `calls=${calls.length}`);
    const resumedTexts = calls.map((c) => (c.userTexts ?? []).join(' ')).join(' | ');
    check('F4k recovery prompt carries instruction + ProjectVersion + paired question/answer', resumedTexts.includes('Check whether') && resumedTexts.includes('Frozen ProjectVersion') && resumedTexts.includes('Answered question "Should I continue?"'), '');
    const events = await readSse(base, '/v1/tasks/' + t.taskId + '/events', 0);
    check('F4h exactly one question event (never re-asked)', events.filter((e) => e.type === 'question').length === 1, '');
    check('F4i loop resumed after answer (message event)', events.some((e) => e.type === 'message'), '');
    check('F4j contiguous sequences', events.map((e) => e.sequence).every((s, i) => s === i + 1), `count=${events.length}`);
    proc2.kill();
    gw.close();
  }

  // ---- F5: ledger re-adopts receipts; recovery prompt carries frozen facts ----
  {
    const gatewayPort = 18324;
    const gwLog = join(baseDir, 'gw-f5.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 'f5');
    const base = 'http://127.0.0.1:18325';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_DELAY_MS: '5000',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18325',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '3'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    // Kill after the receipt-bearing tool event (completion ledger entry is
    // persisted before that event).
    let killed = false;
    let lastSeen = 0;
    for (let i = 0; i < 200 && !killed; i++) {
      const next = await readSse(base, '/v1/tasks/' + t.taskId + '/events', lastSeen, 1);
      if (next.length === 1) {
        lastSeen = next[0].sequence;
        if (next[0].type === 'tool' && next[0].receiptRef) {
          proc.kill();
          killed = true;
        }
      }
    }
    check('F5a killed after receipt event', killed, '');
    await sleep(600);
    // Restart with a model that concludes DIRECTLY (no tool call) — the
    // completion gate must be satisfied by the ledger re-adopted receipt.
    const env2 = { ...env, FAKE_MODE: 'recover-finalize', FAKE_DELAY_MS: '0' };
    const proc2 = startEngine(env2);
    await waitUp(base, proc2);
    const replay = await post(base, '/v1/tasks', t);
    check('F5b exact replay re-arms', replay.status === 202 && replay.body?.replayed === true, `status=${replay.status}`);
    const view = await waitForState(base, t.taskId, ['succeeded', 'failed', 'cancelled']);
    check('F5c terminal without any new tool call', view?.state === 'succeeded', `state=${view?.state}`);
    const events = await readSse(base, '/v1/tasks/' + t.taskId + '/events', 0);
    const delivery = events.find((e) => e.type === 'delivery');
    check('F5d delivery carries the ledger re-adopted receipt', (delivery?.receiptRefs ?? []).includes('receipt.mock.1'), `receiptRefs=${(delivery?.receiptRefs ?? []).join(',')}`);
    const gwLines = readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length;
    check('F5e no re-dispatch across restart', gwLines === 1, `submissions=${gwLines}`);
    const calls = existsSync(join(dir, 'fake-llm-calls.jsonl'))
      ? readFileSync(join(dir, 'fake-llm-calls.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
      : [];
    const resumedTexts = calls.map((c) => (c.userTexts ?? []).join(' ')).join(' | ');
    check(
      'F5f recovery prompt carries instruction + ProjectVersion + receipt + no-resubmit rule',
      resumedTexts.includes('Check whether') && resumedTexts.includes('Frozen ProjectVersion') && resumedTexts.includes('receipt.mock.1') && resumedTexts.includes('do NOT re-submit'),
      '',
    );
    proc2.kill();
    gw.close();
  }

  // ---- F6: recovery with expired persisted deadline → 0 status requests ----
  {
    const gatewayPort = 18326;
    const gwLog = join(baseDir, 'gw-f6.jsonl');
    const statusLog = join(baseDir, 'gw-f6-status.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog, statusLog, holdPolls: 2 });
    const dir = join(baseDir, 'f6');
    const base = 'http://127.0.0.1:18327';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_DELAY_MS: '5000',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18327',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '4'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const ledgerPath = join(dir, t.taskId, 'tool-ledger.jsonl');
    let killed = false;
    for (let i = 0; i < 100 && !killed; i++) {
      if (existsSync(ledgerPath) && readFileSync(ledgerPath, 'utf8').includes('"receiptRef":null')) {
        proc.kill();
        killed = true;
      }
      await sleep(100);
    }
    check('F6a killed in the submit→receipt window', killed, '');
    await sleep(600);
    // Simulate wall-clock passage: the persisted deadline is already in the past.
    const ledgerLines = readFileSync(ledgerPath, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
    const expiredAt = Date.now() - 5000;
    for (const line of ledgerLines) line.deadlineAt = expiredAt;
    writeFileSync(ledgerPath, ledgerLines.map((l) => JSON.stringify(l)).join('\n') + '\n');
    const proc2 = startEngine(env);
    await waitUp(base, proc2);
    await post(base, '/v1/tasks', t);
    const view = await waitForState(base, t.taskId, ['failed'], 200);
    check('F6b terminal failure after expired deadline', view?.state === 'failed', `state=${view?.state}`);
    check('F6c SANDBOX_STATUS_DEADLINE_EXCEEDED with sandbox_system category', view?.error?.code === 'SANDBOX_STATUS_DEADLINE_EXCEEDED' && view?.error?.category === 'sandbox_system', `code=${view?.error?.code} category=${view?.error?.category}`);
    const statusGets = existsSync(statusLog) ? readFileSync(statusLog, 'utf8').split('\n').filter(Boolean).length : 0;
    check('F6d zero status requests after expired-deadline recovery', statusGets === 0, `statusGets=${statusGets}`);
    const gwLines = readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length;
    check('F6e no re-dispatch across restart', gwLines === 1, `submissions=${gwLines}`);
    proc2.kill();
    gw.close();
  }

  // ---- F7: no status request is ever issued past the deadline ----
  {
    const gatewayPort = 18328;
    const gwLog = join(baseDir, 'gw-f7.jsonl');
    const statusLog = join(baseDir, 'gw-f7-status.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog, statusLog, holdPolls: 1000 });
    const dir = join(baseDir, 'f7');
    const base = 'http://127.0.0.1:18329';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_TIMEOUT_MS: '1000',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18329',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '5'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    // timeoutMillis clamps to 1000 → deadline ≈ 31s; the mock never goes
    // terminal, so the engine must fail at the deadline and never poll past it.
    const view = await waitForState(base, t.taskId, ['failed'], 300);
    check('F7a deadline failure with sandbox_system category', view?.error?.code === 'SANDBOX_STATUS_DEADLINE_EXCEEDED' && view?.error?.category === 'sandbox_system', `code=${view?.error?.code} category=${view?.error?.category}`);
    const ledger = readFileSync(join(dir, t.taskId, 'tool-ledger.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
    const provisional = ledger.find((entry) => entry.receiptRef === null);
    const deadlineAt = Number(provisional?.deadlineAt);
    const statusGets = existsSync(statusLog) ? readFileSync(statusLog, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) : [];
    check('F7b it polled before the deadline (deadline was actually enforced)', statusGets.length > 0, `statusGets=${statusGets.length}`);
    check('F7c no status request past the deadline (sleep never crosses it)', statusGets.every((g) => g.at < deadlineAt), `last=${statusGets[statusGets.length - 1]?.at} deadline=${deadlineAt}`);
    proc.kill();
    gw.close();
  }

  // ---- F8: duplicate sandbox input paths rejected before dispatch ----
  {
    const gatewayPort = 18330;
    const gwLog = join(baseDir, 'gw-f8.jsonl');
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog });
    const dir = join(baseDir, 'f8');
    const base = 'http://127.0.0.1:18331';
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      FAKE_MODE: 'dup-inputs',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: '18331',
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + '6'.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const view = await waitForState(base, t.taskId, ['failed', 'succeeded'], 200);
    check('F8a terminal after rejected duplicate inputs', view?.state === 'failed', `state=${view?.state}`);
    const gwLines = existsSync(gwLog) ? readFileSync(gwLog, 'utf8').split('\n').filter(Boolean).length : 0;
    check('F8b zero gateway dispatches (rejected pre-dispatch)', gwLines === 0, `submissions=${gwLines}`);
    proc.kill();
    gw.close();
  }

  // ---- F9/F10/F11: sandbox terminal receipt semantics ----
  for (const scenario of [
    { suffix: '9', port: 18332, enginePort: 18333, terminalState: 'TIMED_OUT', expectedCode: 'SANDBOX_TIMED_OUT', expectDelivery: false },
    { suffix: 'a', port: 18334, enginePort: 18335, terminalState: 'SYSTEM_ERROR', expectedCode: 'SANDBOX_SYSTEM_ERROR', expectDelivery: false },
    { suffix: 'b', port: 18336, enginePort: 18337, terminalState: 'FAILED', expectedCode: null, expectDelivery: true },
  ]) {
    const gatewayPort = scenario.port;
    const gwLog = join(baseDir, `gw-f${scenario.suffix}.jsonl`);
    const { server: gw } = await startMockGateway({ port: gatewayPort, submissionLog: gwLog, terminalState: scenario.terminalState });
    const dir = join(baseDir, `f${scenario.suffix}`);
    const base = `http://127.0.0.1:${scenario.enginePort}`;
    const env = {
      ENGINE_RUNNER: 'dsh',
      ENGINE_GATEWAY_BASE_URL: `http://127.0.0.1:${gatewayPort}`,
      ENGINE_FAKE_LLM: '1',
      ENGINE_SERVICE_TOKEN: TOKEN,
      ENGINE_PORT: String(scenario.enginePort),
      ENGINE_DATA_DIR: dir,
    };
    const t = { ...fixture, taskId: 'task.' + scenario.suffix.repeat(64) };
    t.requestDigest = (await import('../src/canonical.ts')).requestDigestOf(t.authority);
    const proc = startEngine(env);
    await waitUp(base, proc);
    await post(base, '/v1/tasks', t);
    const view = await waitForState(base, t.taskId, ['succeeded', 'failed'], 200);
    const events = await readSse(base, '/v1/tasks/' + t.taskId + '/events', 0);
    const hasDelivery = events.some((e) => e.type === 'delivery');
    const toolReceipt = events.find((e) => e.type === 'tool' && e.receiptRef);
    if (scenario.expectDelivery) {
      check(`F${scenario.suffix}a FAILED receipt still delivers (compile failure is an answer)`, view?.state === 'succeeded' && hasDelivery, `state=${view?.state} delivery=${hasDelivery}`);
      check(`F${scenario.suffix}b delivery carries the receipt`, (events.find((e) => e.type === 'delivery')?.receiptRefs ?? []).length > 0, '');
    } else {
      check(`F${scenario.suffix}a ${scenario.terminalState} receipt fails the task`, view?.state === 'failed', `state=${view?.state}`);
      check(`F${scenario.suffix}b code and category`, view?.error?.code === scenario.expectedCode && view?.error?.category === 'sandbox_system', `code=${view?.error?.code} category=${view?.error?.category}`);
      check(`F${scenario.suffix}c no delivery`, !hasDelivery, '');
      check(`F${scenario.suffix}d receipt still surfaced in the tool event`, toolReceipt?.receiptRef != null, `receiptRef=${toolReceipt?.receiptRef ?? 'none'}`);
      check(`F${scenario.suffix}e contiguous sequences`, events.map((e) => e.sequence).every((s, i) => s === i + 1), `count=${events.length}`);
    }
    proc.kill();
    gw.close();
  }

  rmSync(baseDir, { recursive: true, force: true });
  console.log(failures === 0 ? '\nALL FORMAL-PATH CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('formal-path harness crashed:', e);
  process.exit(1);
});
