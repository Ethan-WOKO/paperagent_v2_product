import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { sha256Hex } from './canonical.ts';

export type RunnerPhase = 'init' | 'messaged' | 'tool-requested' | 'tool-running' | 'tool-succeeded' | 'delivered' | 'questioned';

export interface TaskMeta {
  taskId: string;
  requestDigest: string;
  state: 'queued' | 'running' | 'waiting_user' | 'succeeded' | 'failed' | 'cancelled';
  lastSequence: number;
  pendingQuestionId: string | null;
  deliverySequence: number | null;
  terminalSequence: number | null;
  error: unknown | null;
  runnerPhase: RunnerPhase;
  createdAt: string;
  updatedAt: string;
}

export interface StoredEvent {
  sequence: number;
  event: Record<string, unknown>;
}

export interface AnswerRecord {
  questionId: string;
  answerDigest: string;
  clientRequestId: string;
  acceptedAt: string;
}

/** JSONL persistence: data/<taskId>/meta.json + events.jsonl + answers.jsonl + cancels.jsonl. */
export class TaskStore {
  private readonly dataDir: string;

  constructor(dataDir: string) {
    this.dataDir = dataDir;
    mkdirSync(dataDir, { recursive: true });
  }

  taskDir(taskId: string): string {
    return join(this.dataDir, taskId);
  }

  metaPath(taskId: string): string {
    return join(this.taskDir(taskId), 'meta.json');
  }

  eventsPath(taskId: string): string {
    return join(this.taskDir(taskId), 'events.jsonl');
  }

  answersPath(taskId: string): string {
    return join(this.taskDir(taskId), 'answers.jsonl');
  }

  cancelsPath(taskId: string): string {
    return join(this.taskDir(taskId), 'cancels.jsonl');
  }

  create(taskId: string, requestDigest: string, createdAt: string): TaskMeta {
    mkdirSync(this.taskDir(taskId), { recursive: true });
    const meta: TaskMeta = {
      taskId,
      requestDigest,
      state: 'queued',
      lastSequence: 0,
      pendingQuestionId: null,
      deliverySequence: null,
      terminalSequence: null,
      error: null,
      runnerPhase: 'init',
      createdAt,
      updatedAt: createdAt,
    };
    this.writeMeta(meta);
    return meta;
  }

  get(taskId: string): TaskMeta | null {
    if (!existsSync(this.metaPath(taskId))) return null;
    return JSON.parse(readFileSync(this.metaPath(taskId), 'utf8')) as TaskMeta;
  }

  /** Atomic-ish meta write: temp file + rename. */
  writeMeta(meta: TaskMeta): void {
    meta.updatedAt = new Date().toISOString();
    const tmp = this.metaPath(meta.taskId) + '.tmp.' + sha256Hex(meta.updatedAt).slice(0, 8);
    writeFileSync(tmp, JSON.stringify(meta), 'utf8');
    renameSync(tmp, this.metaPath(meta.taskId));
  }

  appendEvent(taskId: string, event: Record<string, unknown>, sequence: number): void {
    appendFileSync(this.eventsPath(taskId), JSON.stringify({ sequence, event }) + '\n', 'utf8');
  }

  readEvents(taskId: string): StoredEvent[] {
    const path = this.eventsPath(taskId);
    if (!existsSync(path)) return [];
    return readFileSync(path, 'utf8')
      .split('\n')
      .filter((line) => line.trim().length > 0)
      .map((line) => JSON.parse(line) as StoredEvent);
  }

  appendAnswer(taskId: string, record: AnswerRecord): void {
    appendFileSync(this.answersPath(taskId), JSON.stringify(record) + '\n', 'utf8');
  }

  readAnswers(taskId: string): AnswerRecord[] {
    const path = this.answersPath(taskId);
    if (!existsSync(path)) return [];
    return readFileSync(path, 'utf8')
      .split('\n')
      .filter((line) => line.trim().length > 0)
      .map((line) => JSON.parse(line) as AnswerRecord);
  }

  appendCancel(taskId: string, clientRequestId: string): void {
    appendFileSync(this.cancelsPath(taskId), JSON.stringify({ clientRequestId, acceptedAt: new Date().toISOString() }) + '\n', 'utf8');
  }

  readCancels(taskId: string): string[] {
    const path = this.cancelsPath(taskId);
    if (!existsSync(path)) return [];
    return readFileSync(path, 'utf8')
      .split('\n')
      .filter((line) => line.trim().length > 0)
      .map((line) => (JSON.parse(line) as { clientRequestId: string }).clientRequestId);
  }

  listTaskIds(): string[] {
    if (!existsSync(this.dataDir)) return [];
    return readdirSync(this.dataDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && existsSync(join(this.dataDir, entry.name, 'meta.json')))
      .map((entry) => entry.name);
  }
}
