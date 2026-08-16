import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { TaskStore } from './store.ts';
import type { TaskMeta } from './store.ts';
import { StubGateway } from './gateway.ts';
import type { GatewayClient } from './gateway.ts';
import { StubRunner, stubResumeAfterAnswer } from './runner.ts';
import { TaskRuntime } from './task.ts';
import type { Runner } from './task.ts';
import { EngineServer, listen } from './server.ts';
import { sha256Hex } from './canonical.ts';

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.ENGINE_PORT ?? 8092);
const serviceToken = process.env.ENGINE_SERVICE_TOKEN ?? '';
const dataDir = process.env.ENGINE_DATA_DIR ?? join(here, '..', 'data');

const store = new TaskStore(dataDir);

// P1 stub gateway: one fixture file (Sort.java) frozen in-process.
const fixturePath = join(here, '..', '..', 'spike', 'fixture', 'src', 'main', 'java', 'Sort.java');
let fixtureContent = '';
try {
  fixtureContent = readFileSync(fixturePath, 'utf8');
} catch {
  fixtureContent = 'public class Sort { public static void main(String[] args) {} }\n';
}
const gatewayFiles = [
  {
    path: 'src/main/java/Sort.java',
    sizeBytes: Buffer.byteLength(fixtureContent, 'utf8'),
    sha256: sha256Hex(fixtureContent),
    mediaType: 'text/x-java',
  },
];
const submissionLog = join(dataDir, 'gateway-submissions.jsonl');
const gateway: GatewayClient = new StubGateway(gatewayFiles, new Map([['src/main/java/Sort.java', fixtureContent]]), submissionLog);

function runnerFactory(_meta: TaskMeta, _authority: Record<string, unknown>): Runner {
  const stepDelayMs = Number(process.env.STUB_STEP_DELAY_MS ?? 100);
  return new StubRunner({
    question: process.env.STUB_QUESTION === '1',
    fail: process.env.STUB_FAIL === '1',
    stepDelayMs,
    message: process.env.STUB_MESSAGE ?? 'stub engine working',
    conclusion: process.env.STUB_CONCLUSION ?? 'stub conclusion: task finished',
    useGateway: process.env.STUB_USE_GATEWAY === '1',
    gateway,
  }) as unknown as Runner;
}

const engine = new EngineServer({
  serviceToken,
  store,
  runnerFactory,
  onAnswer: (task: TaskRuntime) => {
    stubResumeAfterAnswer(task, {
      conclusion: process.env.STUB_CONCLUSION ?? 'stub conclusion after answer',
    });
  },
});

void gateway; // consumed by StubRunner when STUB_USE_GATEWAY=1

await listen(engine, port);
console.log(`agent-engine-dsh listening on http://127.0.0.1:${port} (runner=stub, token=${serviceToken ? 'set' : 'UNSET'})`);
