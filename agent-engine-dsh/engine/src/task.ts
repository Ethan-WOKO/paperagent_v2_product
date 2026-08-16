import type { ServerResponse } from 'node:http';
import { validateEvent, validateView } from './schemas.ts';
import type { AnswerRecord, RunnerPhase, TaskMeta } from './store.ts';

export type TaskEventType = 'status' | 'message' | 'question' | 'tool' | 'delivery';

export interface TaskEvent {
  contractVersion: '1.0';
  taskId: string;
  sequence: number;
  occurredAt: string;
  type: TaskEventType;
  [field: string]: unknown;
}

export interface SseSubscriber {
  res: ServerResponse;
  lastAcked: number;
  heartbeat: NodeJS.Timeout;
}

const TERMINAL_STATES = new Set(['succeeded', 'failed', 'cancelled']);

export interface TaskStorePort {
  writeMeta(m: TaskMeta): void;
  appendEvent(taskId: string, event: Record<string, unknown>, sequence: number): void;
  appendAnswer(taskId: string, record: AnswerRecord): void;
  readAnswers(taskId: string): AnswerRecord[];
  appendCancel(taskId: string, clientRequestId: string): void;
  readCancels(taskId: string): string[];
  appendToolLedger(taskId: string, entry: Record<string, unknown>): void;
  readToolLedger(taskId: string): Record<string, unknown>[];
}

export class TaskRuntime {
  readonly meta: TaskMeta;
  private subscribers = new Set<SseSubscriber>();
  private aborted = false;
  private answeredQuestions = new Map<string, AnswerRecord>();
  private cancelRequests = new Set<string>();
  private readonly store: TaskStorePort;
  private readonly runner: Runner;
  grant: { taskGrant: string; expiresAt: string };
  authority: Record<string, unknown>;

  constructor(
    meta: TaskMeta,
    store: TaskStorePort,
    authority: Record<string, unknown>,
    grant: { taskGrant: string; expiresAt: string },
    runner: Runner,
  ) {
    this.meta = meta;
    this.store = store;
    this.runner = runner;
    this.authority = authority;
    this.grant = grant;
    for (const record of store.readAnswers(meta.taskId)) {
      this.answeredQuestions.set(record.questionId, record);
    }
    for (const clientRequestId of store.readCancels(meta.taskId)) {
      this.cancelRequests.add(clientRequestId);
    }
  }

  get state(): TaskMeta['state'] {
    return this.meta.state;
  }

  isTerminal(): boolean {
    return TERMINAL_STATES.has(this.meta.state);
  }

  view(): Record<string, unknown> {
    const view = {
      contractVersion: '1.0',
      taskId: this.meta.taskId,
      requestDigest: this.meta.requestDigest,
      state: this.meta.state,
      lastSequence: this.meta.lastSequence,
      pendingQuestionId: this.meta.pendingQuestionId,
      deliverySequence: this.meta.deliverySequence,
      terminalSequence: this.meta.terminalSequence,
      error: this.meta.error,
      createdAt: this.meta.createdAt,
      updatedAt: this.meta.updatedAt,
    };
    validateView(view);
    return view;
  }

  setRunnerPhase(phase: RunnerPhase): void {
    this.meta.runnerPhase = phase;
    this.store.writeMeta(this.meta);
  }

  /** Persist mutable meta (e.g. modelCallsUsed) without a phase change. */
  touch(): void {
    this.store.writeMeta(this.meta);
  }

  emit(type: TaskEventType, fields: Record<string, unknown>): number {
    if (this.isTerminal()) {
      // One terminal state only: late runner emissions after cancel/failure are ignored.
      return this.meta.lastSequence;
    }
    if (type === 'status' && fields.state === 'succeeded' && this.meta.deliverySequence === null) {
      throw new Error('succeeded requires one delivery event first');
    }
    const sequence = this.meta.lastSequence + 1;
    const event: TaskEvent = {
      contractVersion: '1.0',
      taskId: this.meta.taskId,
      sequence,
      occurredAt: new Date().toISOString(),
      type,
      ...fields,
    };
    validateEvent(event);
    this.store.appendEvent(this.meta.taskId, event as Record<string, unknown>, sequence);
    this.meta.lastSequence = sequence;
    this.applyState(type, fields);
    this.store.writeMeta(this.meta);
    this.publish(event);
    return sequence;
  }

  private applyState(type: TaskEventType, fields: Record<string, unknown>): void {
    if (type === 'status') {
      const state = fields.state as TaskMeta['state'];
      this.meta.state = state;
      if (TERMINAL_STATES.has(state)) {
        this.meta.terminalSequence = this.meta.lastSequence;
        if (state === 'failed') {
          this.meta.error = fields.error ?? null;
        } else {
          this.meta.error = null;
        }
      }
    } else if (type === 'question') {
      this.meta.state = 'waiting_user';
      this.meta.pendingQuestionId = fields.questionId as string;
    } else if (type === 'delivery') {
      if (this.meta.state === 'waiting_user') {
        this.meta.state = 'running';
        this.meta.pendingQuestionId = null;
        this.meta.pendingQuestionText = null;
      }
      this.meta.deliverySequence = this.meta.lastSequence;
    }
  }

  start(): void {
    if (this.isTerminal()) return;
    if (this.meta.state === 'queued') {
      this.emit('status', { state: 'running', error: null });
    }
    this.armRunner();
  }

  /** Non-terminal recovery: resume the runner without re-emitting 'running'.
   * waiting_user tasks re-arm too: after restart the in-process ask_user gate is
   * gone and only the runner can re-establish it and consume a delivered answer. */
  resume(): void {
    if (this.isTerminal()) return;
    this.armRunner();
  }

  /** At most one runner invocation is active per task; a replay submit while
   * running only refreshes the grant and never spawns a second driver. */
  private runnerActive = false;

  private armRunner(): void {
    if (this.runnerActive) return;
    this.runnerActive = true;
    void this.runner.run(this, () => this.aborted).finally(() => {
      this.runnerActive = false;
    });
  }

  requestCancel(): void {
    this.aborted = true;
  }

  hasCancelRequest(clientRequestId: string): boolean {
    return this.cancelRequests.has(clientRequestId);
  }

  recordCancelRequest(clientRequestId: string): void {
    if (this.cancelRequests.has(clientRequestId)) return;
    this.cancelRequests.add(clientRequestId);
    this.store.appendCancel(this.meta.taskId, clientRequestId);
  }

  cancelFinalize(): void {
    if (!this.isTerminal()) {
      this.emit('status', { state: 'cancelled', error: null });
    }
  }

  answerFor(questionId: string): AnswerRecord | null {
    return this.answeredQuestions.get(questionId) ?? null;
  }

  answerByRequestId(clientRequestId: string): AnswerRecord | null {
    for (const record of this.answeredQuestions.values()) {
      if (record.clientRequestId === clientRequestId) return record;
    }
    return null;
  }

  recordAnswer(questionId: string, answer: string, answerDigest: string, clientRequestId: string): void {
    const record: AnswerRecord = { questionId, answer, answerDigest, clientRequestId, acceptedAt: new Date().toISOString() };
    this.answeredQuestions.set(questionId, record);
    this.store.appendAnswer(this.meta.taskId, record);
    // Note: pendingQuestionId stays set while the state is waiting_user — the
    // frozen task-view schema requires a non-null string there. It is consumed
    // when the state leaves waiting_user (delivery or terminal status), and
    // answer idempotency never depends on its clearing.
  }

  /** Persisted answer BODY for a questionId (survives restart), or null. */
  answerBodyFor(questionId: string): string | null {
    return this.answeredQuestions.get(questionId)?.answer ?? null;
  }

  pendingQuestion(): string | null {
    return this.meta.pendingQuestionId;
  }

  private answerGates = new Map<string, (answer: string) => void>();

  /** Ask the user: emits the question event, persists the question text and the
   * 'questioned' phase, and returns the question id plus a promise resolving
   * with the delivered answer. Used by the DSH ask_user tool. */
  askUser(text: string): { questionId: string; answerPromise: Promise<string> } {
    const questionId = 'q' + this.meta.lastSequence + '.' + Math.random().toString(36).slice(2, 10);
    const bounded = text.slice(0, 4000);
    this.emit('question', { questionId, text: bounded });
    this.meta.pendingQuestionText = bounded;
    this.setRunnerPhase('questioned');
    const answerPromise = new Promise<string>((resolve) => {
      this.answerGates.set(questionId, resolve);
    });
    return { questionId, answerPromise };
  }

  /** Re-arm the answer gate for a persisted pending question after restart. The
   * caller must re-check answerBodyFor after arming: an answer may have been
   * recorded between the first check and this call. */
  awaitAnswer(questionId: string): { answerPromise: Promise<string> } {
    let resolveAnswer: (answer: string) => void = () => {};
    const answerPromise = new Promise<string>((resolve) => {
      resolveAnswer = resolve;
    });
    this.answerGates.set(questionId, resolveAnswer);
    return { answerPromise };
  }

  /** Resolve a pending ask_user gate. Returns false when no gate is waiting. */
  deliverAnswer(questionId: string, answer: string): boolean {
    const resolve = this.answerGates.get(questionId);
    if (!resolve) return false;
    this.answerGates.delete(questionId);
    resolve(answer);
    return true;
  }

  appendToolLedger(entry: Record<string, unknown>): void {
    this.store.appendToolLedger(this.meta.taskId, entry);
  }

  readToolLedger(): Record<string, unknown>[] {
    return this.store.readToolLedger(this.meta.taskId);
  }

  /** Atomic replay→live switch: subscribe first (publish checks lastAcked),
   * then walk persisted history, so no event can be missed or duplicated. */
  replay(res: ServerResponse, lastEventId: number, events: { sequence: number; event: Record<string, unknown> }[]): void {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    const subscriber: SseSubscriber = {
      res,
      lastAcked: lastEventId,
      heartbeat: setInterval(() => {
        try {
          res.write(': hb\n\n');
        } catch {
          /* connection gone */
        }
      }, 15000),
    };
    this.subscribers.add(subscriber);
    res.on('close', () => {
      clearInterval(subscriber.heartbeat);
      this.subscribers.delete(subscriber);
    });
    for (const stored of events) {
      if (stored.sequence > subscriber.lastAcked) {
        res.write(`id: ${stored.sequence}\ndata: ${JSON.stringify(stored.event)}\n\n`);
        subscriber.lastAcked = stored.sequence;
      }
    }
    if (this.isTerminal()) {
      clearInterval(subscriber.heartbeat);
      this.subscribers.delete(subscriber);
      res.end();
    }
  }

  publish(event: TaskEvent): void {
    const data = `id: ${event.sequence}\ndata: ${JSON.stringify(event)}\n\n`;
    for (const subscriber of this.subscribers) {
      if (event.sequence > subscriber.lastAcked) {
        subscriber.res.write(data);
        subscriber.lastAcked = event.sequence;
      }
    }
    if (TERMINAL_STATES.has(this.meta.state)) {
      for (const subscriber of this.subscribers) {
        clearInterval(subscriber.heartbeat);
        subscriber.res.end();
      }
      this.subscribers.clear();
    }
  }
}

export interface Runner {
  run(task: TaskRuntime, isCancelled: () => boolean): Promise<void>;
}
