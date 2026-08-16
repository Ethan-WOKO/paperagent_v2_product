import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { problem } from './problem.ts';
import type { Problem } from './problem.ts';
import { validateProblem } from './schemas.ts';
import { parseAnswerBody, parseCancelBody, parseTaskSubmission } from './validate.ts';
import { requestDigestOf, answerDigestOf } from './canonical.ts';
import { TaskRuntime } from './task.ts';
import type { Runner } from './task.ts';
import { TaskStore } from './store.ts';
import type { TaskMeta } from './store.ts';
import { stubResumeAfterAnswer } from './runner.ts';

interface EngineOptions {
  serviceToken: string;
  store: TaskStore;
  runnerFactory: (meta: TaskMeta, authority: Record<string, unknown>) => Runner;
  onAnswer: (task: TaskRuntime) => void;
}

function sendProblem(res: ServerResponse, status: number, p: Problem): void {
  validateProblem(p);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(p));
}

function readBody(req: IncomingMessage, limitBytes = 1_048_576): Promise<unknown> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > limitBytes) {
        reject(new Error('BODY_TOO_LARGE'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      try {
        resolve(raw.length === 0 ? {} : JSON.parse(raw));
      } catch {
        reject(new Error('BODY_NOT_JSON'));
      }
    });
    req.on('error', reject);
  });
}

export class EngineServer {
  private readonly runtimes = new Map<string, TaskRuntime>();
  private readonly options: EngineOptions;

  constructor(options: EngineOptions) {
    this.options = options;
    for (const taskId of options.store.listTaskIds()) {
      const meta = options.store.get(taskId);
      if (!meta) continue;
      const authority = this.restoreAuthority(taskId);
      const runtime = new TaskRuntime(meta, options.store, authority, { taskGrant: '', expiresAt: '1970-01-01T00:00:00Z' }, options.runnerFactory(meta, authority));
      this.runtimes.set(taskId, runtime);
      // Non-terminal recovery: queued tasks start, running tasks resume,
      // waiting_user tasks stay parked until /answer.
      if (!runtime.isTerminal()) {
        if (runtime.state === 'queued') runtime.start();
        else runtime.resume();
      }
    }
  }

  private restoreAuthority(taskId: string): Record<string, unknown> {
    try {
      const path = join(this.options.store.taskDir(taskId), 'authority.json');
      return JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>;
    } catch {
      return { runMode: 'PERSISTENT_PLAN_EXECUTE', sessionRef: 'restored', project: { projectId: '0', projectVersion: '0'.repeat(64) }, instruction: 'restored', permissions: { readProject: true, writeWorkspace: false, executeSandbox: true }, model: { provider: 'unknown', model: 'unknown' } };
    }
  }

  handle(req: IncomingMessage, res: ServerResponse): void {
    const url = new URL(req.url ?? '/', 'http://engine.local');
    const path = url.pathname;

    if (!this.authorized(req)) {
      sendProblem(res, 401, problem('UNAUTHORIZED', 'authorization', 'missing or invalid service credential', false));
      return;
    }

    void this.route(req, res, path);
  }

  /** Fail-closed: without a configured service token every control-plane call
   * is rejected. There is no open mode. */
  private authorized(req: IncomingMessage): boolean {
    if (this.options.serviceToken.length === 0) return false;
    const header = req.headers.authorization;
    if (!header || !header.startsWith('Bearer ')) return false;
    return header.slice('Bearer '.length) === this.options.serviceToken;
  }

  private async route(req: IncomingMessage, res: ServerResponse, path: string): Promise<void> {
    try {
      if (path === '/v1/tasks' && req.method === 'POST') {
        await this.submit(req, res);
        return;
      }
      const taskMatch = path.match(/^\/v1\/tasks\/(task\.[a-f0-9]{64})(?:\/(events|cancel|answer))?$/);
      if (taskMatch) {
        const taskId = taskMatch[1];
        const action = taskMatch[2];
        if (action === undefined && req.method === 'GET') return this.getTask(res, taskId);
        if (action === 'events' && req.method === 'GET') return this.streamEvents(req, res, taskId);
        if (action === 'cancel' && req.method === 'POST') return this.cancel(req, res, taskId);
        if (action === 'answer' && req.method === 'POST') return this.answer(req, res, taskId);
      }
      sendProblem(res, 404, problem('NOT_FOUND', 'request', 'unknown endpoint', false));
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      sendProblem(res, 500, problem('INTERNAL', 'internal', message.slice(0, 1000), false));
    }
  }

  private async submit(req: IncomingMessage, res: ServerResponse): Promise<void> {
    let body: unknown;
    try {
      body = await readBody(req);
    } catch {
      sendProblem(res, 400, problem('INVALID_SUBMISSION', 'request', 'body is not valid JSON or too large', false));
      return;
    }
    let submission;
    try {
      submission = parseTaskSubmission(body);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      sendProblem(res, 400, problem('INVALID_SUBMISSION', 'request', 'task submission violates the frozen contract: ' + message.slice(0, 600), false));
      return;
    }
    const computed = requestDigestOf(submission.authority);
    if (computed !== submission.requestDigest) {
      sendProblem(res, 400, problem('REQUEST_DIGEST_INVALID', 'request', 'requestDigest does not match the authority canonical digest', false));
      return;
    }

    const existing = this.runtimes.get(submission.taskId);
    if (existing) {
      if (existing.meta.requestDigest !== submission.requestDigest) {
        sendProblem(res, 409, problem('TASK_DIGEST_CONFLICT', 'request', 'same taskId already exists with another request digest', false, submission.taskId));
        return;
      }
      existing.grant = submission.gateway;
      // Exact replay refreshes the short-lived task grant and re-arms a
      // non-terminal task; events and side effects are never replayed.
      if (!existing.isTerminal()) {
        if (existing.state === 'queued') existing.start();
        else if (existing.state === 'running') existing.resume();
      }
      res.writeHead(202, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ contractVersion: '1.0', replayed: true, task: existing.view() }));
      return;
    }

    const createdAt = new Date().toISOString();
    const meta = this.options.store.create(submission.taskId, submission.requestDigest, createdAt);
    writeFileSync(join(this.options.store.taskDir(submission.taskId), 'authority.json'), JSON.stringify(submission.authority), 'utf8');
    const runtime = new TaskRuntime(meta, this.options.store, submission.authority as unknown as Record<string, unknown>, submission.gateway, this.options.runnerFactory(meta, submission.authority as unknown as Record<string, unknown>));
    this.runtimes.set(submission.taskId, runtime);
    runtime.emit('status', { state: 'queued', error: null });
    runtime.start();
    res.writeHead(202, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ contractVersion: '1.0', replayed: false, task: runtime.view() }));
  }

  private getTask(res: ServerResponse, taskId: string): void {
    const runtime = this.runtimes.get(taskId);
    if (!runtime) {
      sendProblem(res, 404, problem('TASK_NOT_FOUND', 'request', 'unknown taskId', false, taskId));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(runtime.view()));
  }

  private streamEvents(req: IncomingMessage, res: ServerResponse, taskId: string): void {
    const runtime = this.runtimes.get(taskId);
    if (!runtime) {
      sendProblem(res, 404, problem('TASK_NOT_FOUND', 'request', 'unknown taskId', false, taskId));
      return;
    }
    let lastEventId = 0;
    const raw = req.headers['last-event-id'];
    if (raw !== undefined) {
      const parsed = Number(String(raw));
      if (!Number.isInteger(parsed) || parsed < 0) {
        sendProblem(res, 400, problem('INVALID_LAST_EVENT_ID', 'request', 'Last-Event-ID must be a non-negative integer', false));
        return;
      }
      lastEventId = parsed;
    }
    runtime.replay(res, lastEventId, this.options.store.readEvents(taskId));
  }

  private async cancel(req: IncomingMessage, res: ServerResponse, taskId: string): Promise<void> {
    const runtime = this.runtimes.get(taskId);
    if (!runtime) {
      sendProblem(res, 404, problem('TASK_NOT_FOUND', 'request', 'unknown taskId', false, taskId));
      return;
    }
    let body: unknown;
    try {
      body = await readBody(req);
    } catch {
      sendProblem(res, 400, problem('INVALID_CANCEL', 'request', 'body is not valid JSON', false));
      return;
    }
    let parsed;
    try {
      parsed = parseCancelBody(body);
    } catch {
      sendProblem(res, 400, problem('INVALID_CANCEL', 'request', 'cancel body violates the frozen contract', false));
      return;
    }
    runtime.recordCancelRequest(parsed.clientRequestId);
    if (!runtime.isTerminal()) {
      runtime.requestCancel();
      runtime.cancelFinalize();
    }
    res.writeHead(202, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(runtime.view()));
  }

  private async answer(req: IncomingMessage, res: ServerResponse, taskId: string): Promise<void> {
    const runtime = this.runtimes.get(taskId);
    if (!runtime) {
      sendProblem(res, 404, problem('TASK_NOT_FOUND', 'request', 'unknown taskId', false, taskId));
      return;
    }
    let body: unknown;
    try {
      body = await readBody(req);
    } catch {
      sendProblem(res, 400, problem('INVALID_ANSWER', 'request', 'body is not valid JSON', false));
      return;
    }
    let parsed;
    try {
      parsed = parseAnswerBody(body);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      sendProblem(res, 400, problem('INVALID_ANSWER', 'request', 'answer body violates the frozen contract: ' + message.slice(0, 600), false));
      return;
    }

    if (runtime.pendingQuestion() !== parsed.questionId || runtime.state !== 'waiting_user') {
      sendProblem(res, 409, problem('QUESTION_NOT_PENDING', 'request', 'questionId is not the currently pending question', false, taskId));
      return;
    }

    // Idempotency by clientRequestId first: the same request id must always
    // name the same question and digest.
    const byRequestId = runtime.answerByRequestId(parsed.clientRequestId);
    if (byRequestId !== null) {
      if (byRequestId.questionId === parsed.questionId && byRequestId.answerDigest === parsed.answerDigest) {
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(runtime.view()));
        return;
      }
      sendProblem(res, 409, problem('ANSWER_REQUEST_CONFLICT', 'request', 'clientRequestId already used with a different questionId or answerDigest', false, taskId));
      return;
    }

    const byQuestion = runtime.answerFor(parsed.questionId);
    if (byQuestion !== null) {
      if (byQuestion.answerDigest === parsed.answerDigest) {
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(runtime.view()));
        return;
      }
      sendProblem(res, 409, problem('QUESTION_ANSWER_CONFLICT', 'request', 'question already answered with a different answerDigest', false, taskId));
      return;
    }

    runtime.recordAnswer(parsed.questionId, parsed.answerDigest, parsed.clientRequestId);
    this.options.onAnswer(runtime);
    res.writeHead(202, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(runtime.view()));
  }
}

export function createEngineServer(options: EngineOptions): EngineServer {
  return new EngineServer(options);
}

export function listen(server: EngineServer, port: number): Promise<void> {
  const http = createServer((req, res) => server.handle(req, res));
  return new Promise((resolve) => {
    http.listen(port, () => resolve());
  });
}
