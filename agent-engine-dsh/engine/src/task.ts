import type { ServerResponse } from 'node:http';
import type { TaskMeta } from './store.ts';

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

export class TaskRuntime {
  readonly meta: TaskMeta;
  private subscribers = new Set<SseSubscriber>();
  private aborted = false;
  private answeredQuestions = new Map<string, string>();
  private readonly store: { writeMeta(m: TaskMeta): void; appendEvent(taskId: string, event: Record<string, unknown>, sequence: number): void };
  private readonly runner: Runner;
  grant: { taskGrant: string; expiresAt: string };
  authority: Record<string, unknown>;

  constructor(
    meta: TaskMeta,
    store: { writeMeta(m: TaskMeta): void; appendEvent(taskId: string, event: Record<string, unknown>, sequence: number): void },
    authority: Record<string, unknown>,
    grant: { taskGrant: string; expiresAt: string },
    runner: Runner,
  ) {
    this.meta = meta;
    this.store = store;
    this.runner = runner;
    this.authority = authority;
    this.grant = grant;
  }

  get state(): TaskMeta['state'] {
    return this.meta.state;
  }

  isTerminal(): boolean {
    return TERMINAL_STATES.has(this.meta.state);
  }

  view(): Record<string, unknown> {
    return {
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
      if (this.meta.state !== 'queued' && state !== this.meta.state) {
        // status transitions are monotonic; keep terminal guard
      }
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
      }
      this.meta.deliverySequence = this.meta.lastSequence;
    }
  }

  start(): void {
    if (this.isTerminal()) return;
    if (this.meta.state === 'queued') {
      this.emit('status', { state: 'running', error: null });
    }
    void this.runner.run(this, () => this.aborted);
  }

  requestCancel(): void {
    this.aborted = true;
  }

  cancelFinalize(): void {
    if (!this.isTerminal()) {
      this.emit('status', { state: 'cancelled', error: null });
    }
  }

  hasAnswer(questionId: string): string | null {
    return this.answeredQuestions.get(questionId) ?? null;
  }

  recordAnswer(questionId: string, answer: string): void {
    this.answeredQuestions.set(questionId, answer);
  }

  pendingQuestion(): string | null {
    return this.meta.pendingQuestionId;
  }

  subscribe(res: ServerResponse): () => void {
    const subscriber: SseSubscriber = {
      res,
      lastAcked: 0,
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
    return () => {
      clearInterval(subscriber.heartbeat);
      this.subscribers.delete(subscriber);
    };
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
        subscriber.res.end();
      }
      this.subscribers.clear();
    }
  }

  /** Replay persisted events with sequence > lastEventId, then live. */
  replay(res: ServerResponse, lastEventId: number, events: { sequence: number; event: Record<string, unknown> }[]): void {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    let lastSent = lastEventId;
    for (const stored of events) {
      if (stored.sequence > lastEventId) {
        res.write(`id: ${stored.sequence}\ndata: ${JSON.stringify(stored.event)}\n\n`);
        lastSent = stored.sequence;
      }
    }
    if (this.isTerminal()) {
      res.end();
      return;
    }
    const subscriber: SseSubscriber = {
      res,
      lastAcked: lastSent,
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
  }
}

export interface Runner {
  run(task: TaskRuntime, isCancelled: () => boolean): Promise<void>;
}
