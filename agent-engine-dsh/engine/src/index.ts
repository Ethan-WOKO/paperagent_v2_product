import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { TaskStore } from './store.ts';
import type { TaskMeta } from './store.ts';
import { StubGateway } from './gateway.ts';
import type { GatewayClient } from './gateway.ts';
import { HttpGatewayClient } from './gateway-http.ts';
import { StubRunner, stubResumeAfterAnswer } from './runner.ts';
import { DshRunner } from './dsh/runner.ts';
import { bootDsh } from './dsh/runtime.ts';
import type { DshRuntime } from './dsh/runtime.ts';
import { TaskRuntime } from './task.ts';
import type { Runner } from './task.ts';
import { EngineServer, listen } from './server.ts';
import { sha256Hex } from './canonical.ts';

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.ENGINE_PORT ?? 8092);
const serviceToken = process.env.ENGINE_SERVICE_TOKEN ?? '';
const dataDir = process.env.ENGINE_DATA_DIR ?? join(here, '..', 'data');
const runnerKind = process.env.ENGINE_RUNNER ?? 'stub';

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

function gatewayFor(task: TaskRuntime): GatewayClient {
  const baseUrl = process.env.ENGINE_GATEWAY_BASE_URL;
  if (baseUrl) {
    return new HttpGatewayClient(baseUrl, task.meta.taskId, task.grant.taskGrant);
  }
  return new StubGateway(gatewayFiles, new Map([['src/main/java/Sort.java', fixtureContent]]), submissionLog);
}

const SYSTEM_PROMPT =
  'You are the coding agent of a research-assistant product. Work on the task in the user message. ' +
  'You may list and read frozen project Workspace files and run bounded sandbox commands. ' +
  'Never modify files: P1 has no write tool. Declare every non-standard Java/Python dependency on the ' +
  'first sandbox run with --dependency=... arguments. If a run fails, analyze the receipt and adjust the ' +
  'command instead of giving up. Produce a final user-visible conclusion with evidence.';

async function buildRunnerFactory(): Promise<(meta: TaskMeta, authority: Record<string, unknown>) => Runner> {
  if (runnerKind === 'dsh') {
    const dsh: DshRuntime = await bootDsh(join(here, '..', 'cordis.yml'));
    console.log('DSH tree booted (runner=dsh)');
    const dshRunner = new DshRunner(dsh, gatewayFor, SYSTEM_PROMPT);
    return () => dshRunner as unknown as Runner;
  }
  const stubGateway = new StubGateway(gatewayFiles, new Map([['src/main/java/Sort.java', fixtureContent]]), submissionLog);
  return () =>
    new StubRunner({
      question: process.env.STUB_QUESTION === '1',
      fail: process.env.STUB_FAIL === '1',
      stepDelayMs: Number(process.env.STUB_STEP_DELAY_MS ?? 100),
      message: process.env.STUB_MESSAGE ?? 'stub engine working',
      conclusion: process.env.STUB_CONCLUSION ?? 'stub conclusion: task finished',
      useGateway: process.env.STUB_USE_GATEWAY === '1',
      gateway: stubGateway,
    }) as unknown as Runner;
}

const runnerFactory = await buildRunnerFactory();

const engine = new EngineServer({
  serviceToken,
  store,
  runnerFactory,
  onAnswer: (task: TaskRuntime) => {
    stubResumeAfterAnswer(task, {
      conclusion: process.env.STUB_CONCLUSION ?? 'stub conclusion after answer',
      answerDelayMs: Number(process.env.STUB_ANSWER_DELAY_MS ?? 10),
    });
  },
});

await listen(engine, port);
console.log(`agent-engine-dsh listening on http://127.0.0.1:${port} (runner=${runnerKind}, token=${serviceToken ? 'set' : 'UNSET'})`);
