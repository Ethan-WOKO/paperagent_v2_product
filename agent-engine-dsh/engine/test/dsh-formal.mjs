// Formal-path tests with the real DshRunner + HttpGatewayClient, no stub:
//  - T4-recovery: crash after Receipt, restart, exact replay → no duplicate
//    sandbox dispatch, contiguous events, delivery reuses the original receipt.
//  - Budget: 20 model calls allowed, the 21st is rejected before dispatch.
//  - ask_user: formal question/answer gate (no stub finalizer).
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
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
    check('T4d terminal after replay', view?.state === 'succeeded', `state=${view?.state}`);
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

  rmSync(baseDir, { recursive: true, force: true });
  console.log(failures === 0 ? '\nALL FORMAL-PATH CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('formal-path harness crashed:', e);
  process.exit(1);
});
