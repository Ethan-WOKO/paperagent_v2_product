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
import { FakeAdapter } from './dsh/fake-adapter.ts';
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

// Fail-closed runner selection: only the two explicit values exist. A missing
// or unknown value refuses to start; there is no silent stub fallback.
const runnerKind = process.env.ENGINE_RUNNER;
if (runnerKind !== 'dsh' && runnerKind !== 'stub') {
  throw new Error('ENGINE_RUNNER must be exactly "dsh" or "stub" (fail-closed: no default)');
}

const store = new TaskStore(dataDir);

const SYSTEM_PROMPT =
  'You are the coding agent of a research-assistant product. Work on the task in the user message. ' +
  'You may list and read frozen project Workspace files, run bounded sandbox commands, and ask the user ' +
  'one precise question when genuinely blocked. Never modify files: P1 has no write tool. Declare every ' +
  'non-standard Java/Python dependency on the first sandbox run with --dependency=... arguments. If a run ' +
  'fails, analyze the receipt and adjust the command instead of giving up. Produce a final user-visible ' +
  'conclusion with evidence.';

/** dsh runner: every gateway call reads the CURRENT task grant (replay may
 * refresh it mid-flight). The frozen ProjectVersion from the task authority is
 * bound to every file-manifest response. */
function gatewayFor(task: TaskRuntime): GatewayClient {
  const baseUrl = process.env.ENGINE_GATEWAY_BASE_URL;
  if (!baseUrl) {
    throw new Error('ENGINE_GATEWAY_BASE_URL_MISSING');
  }
  return new HttpGatewayClient(
    baseUrl,
    task.meta.taskId,
    () => task.grant.taskGrant,
    () => {
      const project = task.authority.project as { projectVersion?: string } | undefined;
      return typeof project?.projectVersion === 'string' ? project.projectVersion : null;
    },
  );
}

function stubFixture(): { gatewayFiles: { path: string; sizeBytes: number; sha256: string; mediaType: string }[]; content: string } {
  const fixturePath = join(here, '..', '..', 'spike', 'fixture', 'src', 'main', 'java', 'Sort.java');
  let content = '';
  try {
    content = readFileSync(fixturePath, 'utf8');
  } catch {
    content = 'public class Sort { public static void main(String[] args) {} }\n';
  }
  return {
    gatewayFiles: [{ path: 'src/main/java/Sort.java', sizeBytes: Buffer.byteLength(content, 'utf8'), sha256: sha256Hex(content), mediaType: 'text/x-java' }],
    content,
  };
}

async function buildRunnerFactory(): Promise<(meta: TaskMeta, authority: Record<string, unknown>) => Runner> {
  if (runnerKind === 'dsh') {
    if (!process.env.ENGINE_GATEWAY_BASE_URL) {
      throw new Error('ENGINE_RUNNER=dsh requires ENGINE_GATEWAY_BASE_URL');
    }
    const dsh: DshRuntime = await bootDsh(join(here, '..', 'cordis.yml'));
    console.log('DSH tree booted (runner=dsh)');
    let providerRoute = 'deepseek-official';
    if (process.env.ENGINE_FAKE_LLM === '1') {
      dsh.ctx.llm.registerAdapter(['fake-llm'], new FakeAdapter(join(dataDir, 'fake-llm-calls.jsonl')) as never);
      providerRoute = 'fake-llm';
      console.log('fake-llm adapter registered for formal-path tests');
    }
    const dshRunner = new DshRunner(dsh, gatewayFor, SYSTEM_PROMPT, providerRoute);
    return () => dshRunner as unknown as Runner;
  }
  // stub branch (conformance/tests only): fixture and stub gateway are loaded
  // lazily and never touch the formal dsh path.
  const fixture = stubFixture();
  const submissionLog = join(dataDir, 'gateway-submissions.jsonl');
  const stubGateway = new StubGateway(fixture.gatewayFiles, new Map([['src/main/java/Sort.java', fixture.content]]), submissionLog);
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
  onAnswer: (task: TaskRuntime, answer: { questionId: string; answer: string }) => {
    if (runnerKind === 'dsh') {
      // Formal path: deliver into the ask_user gate; never a stub finalizer.
      if (!task.deliverAnswer(answer.questionId, answer.answer)) {
        console.error('answer delivered with no pending ask_user gate: ' + answer.questionId);
      }
      return;
    }
    stubResumeAfterAnswer(task, {
      conclusion: process.env.STUB_CONCLUSION ?? 'stub conclusion after answer',
      answerDelayMs: Number(process.env.STUB_ANSWER_DELAY_MS ?? 10),
    });
  },
});

await listen(engine, port);
console.log(`agent-engine-dsh listening on http://127.0.0.1:${port} (runner=${runnerKind}, token=${serviceToken ? 'set' : 'UNSET'})`);
