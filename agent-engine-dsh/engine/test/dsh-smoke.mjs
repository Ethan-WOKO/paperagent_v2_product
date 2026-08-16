// Real-model smoke: DSH ReactLoopAgent + deepseek-v4-pro on the T1 task,
// gateway = StubGateway (frozen Sort.java fixture). Proves the loop, tool
// dispatch, event bridging, budget, and terminal flow without the #151 gateway.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');
const envFile = readFileSync('C:/java_file/private_helper_Agent/paperagent_v2_product/.env', 'utf8');
const apiKey = envFile.match(/^\s*DEEPSEEK_API_KEY\s*=\s*(.*)\s*$/m)?.[1] ?? '';
if (!apiKey) {
  console.error('DEEPSEEK_API_KEY missing from .env');
  process.exit(1);
}

const dir = mkdtempSync(join(tmpdir(), 'dsh-smoke-'));
const proc = spawn(process.execPath, ['src/index.ts'], {
  env: {
    ...process.env,
    ENGINE_RUNNER: 'dsh',
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
  const deadline = Date.now() + 360000;
  while (Date.now() < deadline) {
    const res = await fetch(base + '/v1/tasks/' + fixture.taskId, { headers: { Authorization: 'Bearer ' + token } });
    view = await res.json();
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
} finally {
  proc.kill();
  rmSync(dir, { recursive: true, force: true });
}
