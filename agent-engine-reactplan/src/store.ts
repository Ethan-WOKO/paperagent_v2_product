import { mkdir, open, readFile, readdir, rename, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import type { PersistedTask, TaskEvent } from "./types.js";

export interface RecoveryGrant { taskGrant: string; expiresAt: string }

export interface TaskPersistence {
  initialize(): Promise<void>;
  create(task: PersistedTask): Promise<void>;
  save(task: PersistedTask): Promise<void>;
  appendEvent(event: TaskEvent): Promise<void>;
  events(taskId: string): Promise<TaskEvent[]>;
  loadAll(): Promise<PersistedTask[]>;
  authorizeRecovery?(task: PersistedTask): Promise<RecoveryGrant>;
}

export class TaskStore implements TaskPersistence {
  constructor(private readonly root: string) {}

  async initialize(): Promise<void> { await mkdir(this.root, { recursive: true }); }

  private directory(taskId: string): string { return resolve(this.root, taskId); }

  async create(task: PersistedTask): Promise<void> {
    await mkdir(this.directory(task.view.taskId), { recursive: false });
    await this.save(task);
  }

  async save(task: PersistedTask): Promise<void> {
    const directory = this.directory(task.view.taskId);
    const target = resolve(directory, "task.json");
    const temporary = resolve(directory, `task.${process.pid}.${randomUUID()}.tmp`);
    await writeFile(temporary, JSON.stringify(task), { encoding: "utf8", flag: "wx" });
    await rename(temporary, target);
  }

  async appendEvent(event: TaskEvent): Promise<void> {
    const handle = await open(resolve(this.directory(event.taskId), "events.jsonl"), "a");
    try { await handle.writeFile(`${JSON.stringify(event)}\n`, "utf8"); await handle.sync(); }
    finally { await handle.close(); }
  }

  async events(taskId: string): Promise<TaskEvent[]> {
    try {
      const text = await readFile(resolve(this.directory(taskId), "events.jsonl"), "utf8");
      return text.split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line) as TaskEvent);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
      throw error;
    }
  }

  async loadAll(): Promise<PersistedTask[]> {
    await this.initialize();
    const entries = await readdir(this.root, { withFileTypes: true });
    const tasks: PersistedTask[] = [];
    for (const entry of entries) {
      if (!entry.isDirectory() || !entry.name.startsWith("task.")) continue;
      try {
        const task = JSON.parse(await readFile(resolve(this.root, entry.name, "task.json"), "utf8")) as PersistedTask;
        const events = await this.events(entry.name);
        reconcileTask(task, events);
        tasks.push(task);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }
    }
    return tasks;
  }
}

export class HttpTaskStore implements TaskPersistence {
  private readonly revisions = new Map<string, number>();

  constructor(private readonly origin: string, private readonly serviceToken: string) {
    if (serviceToken.length < 32) throw new Error("ENGINE_SERVICE_TOKEN must contain at least 32 characters");
  }

  async initialize(): Promise<void> { await this.loadAll(); }

  async create(task: PersistedTask): Promise<void> {
    const response = await this.call<{ checkpointRevision: number }>("/checkpoints", "POST", {
      contractVersion: "1.0", taskId: task.view.taskId,
      requestDigest: task.view.requestDigest, expectedRevision: null, checkpoint: task
    });
    this.revisions.set(task.view.taskId, response.checkpointRevision);
  }

  async save(task: PersistedTask): Promise<void> {
    const expectedRevision = this.revisions.get(task.view.taskId);
    if (expectedRevision === undefined) throw new Error(`Missing checkpoint revision for ${task.view.taskId}`);
    const response = await this.call<{ checkpointRevision: number }>("/checkpoints", "POST", {
      contractVersion: "1.0", taskId: task.view.taskId,
      requestDigest: task.view.requestDigest, expectedRevision, checkpoint: task
    });
    this.revisions.set(task.view.taskId, response.checkpointRevision);
  }

  async appendEvent(event: TaskEvent): Promise<void> {
    await this.call("/events", "POST", { contractVersion: "1.0", event });
  }

  async events(taskId: string): Promise<TaskEvent[]> {
    const response = await this.call<{ events: TaskEvent[] }>(`/tasks/${encodeURIComponent(taskId)}/events`, "GET");
    return response.events;
  }

  async loadAll(): Promise<PersistedTask[]> {
    const response = await this.call<{ tasks: Array<{ checkpointRevision: number; checkpoint: PersistedTask }> }>("/checkpoints", "GET");
    this.revisions.clear();
    for (const item of response.tasks) this.revisions.set(item.checkpoint.view.taskId, item.checkpointRevision);
    return response.tasks.map((item) => item.checkpoint);
  }

  authorizeRecovery(task: PersistedTask): Promise<RecoveryGrant> {
    return this.call(`/tasks/${encodeURIComponent(task.view.taskId)}/authorize-recovery`, "POST", {
      contractVersion: "1.0", requestDigest: task.view.requestDigest
    });
  }

  private async call<T>(path: string, method: "GET" | "POST", body?: unknown): Promise<T> {
    const url = `${this.origin.replace(/\/$/, "")}/internal/v1/agent-engine/task-state${path}`;
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      let response: Response;
      try {
        response = await fetch(url, {
          method,
          headers: {
            authorization: `Bearer ${this.serviceToken}`,
            ...(body === undefined ? {} : { "content-type": "application/json" })
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) })
        });
      } catch (failure) {
        if (attempt === 3) throw failure;
        await new Promise((resolvePromise) => setTimeout(resolvePromise, attempt * 100));
        continue;
      }
      if (response.ok) return await response.json() as T;
      if (response.status >= 500 && attempt < 3) {
        await new Promise((resolvePromise) => setTimeout(resolvePromise, attempt * 100));
        continue;
      }
      let message = `Task persistence gateway returned HTTP ${response.status}`;
      try { message = (await response.json() as { message?: string }).message ?? message; } catch { /* bounded fallback */ }
      throw new Error(message);
    }
    throw new Error("Task persistence gateway retry budget exhausted");
  }
}

export function reconcileTask(task: PersistedTask, events: TaskEvent[]): void {
  for (const event of events) {
    task.view.lastSequence = Math.max(task.view.lastSequence, event.sequence);
    task.view.updatedAt = event.occurredAt;
    if (event.type === "status") {
      task.view.state = event.state;
      task.view.error = event.error;
      if (["succeeded", "failed", "cancelled"].includes(event.state)) task.view.terminalSequence = event.sequence;
    } else if (event.type === "delivery") task.view.deliverySequence = event.sequence;
    else if (event.type === "question") task.view.pendingQuestionId = event.questionId;
  }
}
