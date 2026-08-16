import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { problem } from './problem.ts';
import type { Problem } from './problem.ts';
import { parseAnswerBody, parseCancelBody, parseTaskSubmission } from './validate.ts';
import { requestDigestOf } from './canonical.ts';
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
  private readonly submittedAuthorities = new Map<string, Record<string, unknown>>();
  private readonly cancelRequestIds = new Map<string, Set<string>>();
  private readonly options: EngineOptions;

  constructor(options: EngineOptions) {
    this.options = options;
    for (const taskId of options.store.listTaskIds()) {
      const meta = options.store.get(taskId);
      if (!meta) continue;
      const authority = this.restoreAuthority(taskId);
      const runtime = new TaskRuntime(meta, options.store, authority, { taskGrant: '', expiresAt: '' }, options.runnerFactory(meta, authority));
      this.runtimes.set(taskId, runtime);
      this.submittedAuthorities.set(taskId, authority);
    }
  }

  /** Authority is not persisted in plaintext-secret-free form; P1 keeps the
   * raw authority beside the task dir (no secrets inside authority). */
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

    const authorized = this.authorized(req);
    if (!authorized) {
      sendProblem(res, 401, problem('UNAUTHORIZED', 'authorization', 'missing or invalid service credential', false));
      return;
    }

    void this.route(req, res, path);
  }

  private authorized(req: IncomingMessage): boolean {
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
    } catch {
      sendProblem(res, 400, problem('INVALID_SUBMISSION', 'request', 'task submission violates the frozen contract', false));
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
      if (!existing.isTerminal() && existing.meta.state === 'queued') {
        existing.start();
      }
      res.writeHead(202, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ contractVersion: '1.0', replayed: true, task: existing.view() }));
      return;
    }

    const createdAt = new Date().toISOString();
    const meta = this.options.store.create(submission.taskId, submission.requestDigest, createdAt);
    const { writeFileSync } = await import('node:fs');
    writeFileSync(join(this.options.store.taskDir(submission.taskId), 'authority.json'), JSON.stringify(submission.authority), 'utf8');
    const runtime = new TaskRuntime(meta, this.options.store, submission.authority as unknown as Record<string, unknown>, submission.gateway, this.options.runnerFactory(meta, submission.authority as unknown as Record<string, unknown>));
    this.runtimes.set(submission.taskId, runtime);
    this.submittedAuthorities.set(submission.taskId, submission.authority as unknown as Record<string, unknown>);
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
    let ids = this.cancelRequestIds.get(taskId);
    if (!ids) {
      ids = new Set();
      this.cancelRequestIds.set(taskId, ids);
    }
    ids.add(parsed.clientRequestId);
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
    } catch {
      sendProblem(res, 400, problem('INVALID_ANSWER', 'request', 'answer body violates the frozen contract', false));
      return;
    }
    if (runtime.state !== 'waiting_user') {
      sendProblem(res, 409, problem('NO_PENDING_QUESTION', 'request', 'task has no pending question', false, taskId));
      return;
    }
    if (runtime.pendingQuestion() !== parsed.questionId) {
      sendProblem(res, 409, problem('QUESTION_MISMATCH', 'request', 'questionId does not match the pending question', false, taskId));
      return;
    }
    const previous = runtime.hasAnswer(parsed.questionId);
    if (previous !== null) {
      if (previous === parsed.answer) {
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(runtime.view()));
        return;
      }
      sendProblem(res, 409, problem('ANSWER_CONFLICT', 'request', 'question already answered with different content', false, taskId));
      return;
    }
    runtime.recordAnswer(parsed.questionId, parsed.answer);
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
