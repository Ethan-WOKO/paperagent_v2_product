// Real-model smoke: DSH ReactLoopAgent + deepseek-v4-pro on the T1 task,
// gateway = controlled HTTP mock implementing the contract endpoints (no
// in-process StubGateway). Proves the formal path minus the #151 Java gateway.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { startMockGateway } from './mock-gateway.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const envFile = readFileSync('C:/java_file/private_helper_Agent/paperagent_v2_product/.env', 'utf8');
const apiKey = envFile.match(/^\s*DEEPSEEK_API_KEY\s*=\s*(.*)\s*$/m)?.[1] ?? '';
if (!apiKey) {
  console.error('DEEPSEEK_API_KEY missing from .env');
  process.exit(1);
}

const dir = mkdtempSync(join(tmpdir(), 'dsh-smoke-'));
const submissionLog = join(dir, 'gw-submissions.jsonl');
const { server: gwServer } = await startMockGateway({ port: 18290, submissionLog });
const proc = spawn(process.execPath, ['src/index.ts'], {
  env: {
    ...process.env,
    ENGINE_RUNNER: 'dsh',
    ENGINE_GATEWAY_BASE_URL: 'http://127.0.0.1:18290',
    ENGINE_SERVICE_TOKEN: 't',
    ENGINE_PORT: '18201',
    ENGINE_DATA_DIR: dir,
    DEEPSEEK_API_KEY: apiKey,
  },
  stdio: ['ignore', 'ignore', 'pipe'],
});
proc.stderr.on('data', (d) => {
  const text = d.toString().trim();
  if (text) console.error('[engine] ' + text.slice(0, 400));
});

const base = 'http://127.0.0.1:18201';
const token = 't';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const fixture = JSON.parse(readFileSync(join(here, '..', '..', '..', 'agent-engine-contract', 'conformance', 'fixtures', 'positive', 'task-submission.json'), 'utf8'));
fixture.taskId = 'task.' + 'a'.repeat(64);

try {
  // wait up
  for (let i = 0; i < 100; i++) {
    try {
      await fetch(base + '/v1/tasks', { headers: { Authorization: 'Bearer ' + token } });
      break;
    } catch {
      await sleep(300);
    }
  }
  const submit = await fetch(base + '/v1/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify(fixture),
  });
  console.log('submit status:', submit.status);

  let view = null;
  const answered = new Set();
  const deadline = Date.now() + 360000;
  while (Date.now() < deadline) {
    const res = await fetch(base + '/v1/tasks/' + fixture.taskId, { headers: { Authorization: 'Bearer ' + token } });
    view = await res.json();
    if (view.state === 'waiting_user' && view.pendingQuestionId && !answered.has(view.pendingQuestionId)) {
      answered.add(view.pendingQuestionId);
      const answer = '请继续执行，无需补充信息。';
      const sha = createHash('sha256').update(answer).digest('hex');
      const a = await fetch(base + '/v1/tasks/' + fixture.taskId + '/answer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify({
          contractVersion: '1.0',
          clientRequestId: 'answer.' + 'f'.repeat(20),
          questionId: view.pendingQuestionId,
          answer,
          answerDigest: sha,
        }),
      });
      console.log('answer status:', a.status, 'questionId:', view.pendingQuestionId);
      continue;
    }
    if (['succeeded', 'failed', 'cancelled'].includes(view.state)) break;
    await sleep(2000);
  }
  console.log('final view:', JSON.stringify(view));

  const events = await fetch(base + '/v1/tasks/' + fixture.taskId + '/events', { headers: { Authorization: 'Bearer ' + token, 'Last-Event-ID': '0' } });
  const text = await events.text();
  const types = [...text.matchAll(/"type":"(\w+)"/g)].map((m) => m[1]);
  console.log('event types:', types.join(','));
  const delivery = [...text.matchAll(/"conclusion":"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
  console.log('delivery conclusion:', delivery[0] ?? '(none)');

  const subs = existsSync(submissionLog)
    ? readFileSync(submissionLog, 'utf8').trim().split('\n').filter(Boolean)
    : [];
  console.log('gateway submissions:', subs.length, subs.map((l) => JSON.parse(l).argv.join(' ')).join(' | '));
} finally {
  proc.kill();
  gwServer.close();
  rmSync(dir, { recursive: true, force: true });
}
