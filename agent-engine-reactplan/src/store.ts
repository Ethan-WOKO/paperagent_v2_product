import { mkdir, open, readFile, readdir, rename, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import type { PersistedTask, TaskEvent } from "./types.js";
import { EngineProblem, problem } from "./util.js";

export interface RecoveryGrant { taskGrant: string; expiresAt: string }
export interface TaskLease { owner: string; token: string; fence: number }
export interface ClaimedTask {
  checkpointRevision: number;
  checkpoint: PersistedTask;
  lease: TaskLease;
  taskGrant: string;
  expiresAt: string;
  cancellationRequested: boolean;
}

export interface TaskPersistence {
  initialize(): Promise<void>;
  create(task: PersistedTask): Promise<void>;
  save(task: PersistedTask): Promise<void>;
  appendEvent(event: TaskEvent): Promise<void>;
  events(taskId: string): Promise<TaskEvent[]>;
  loadAll(): Promise<PersistedTask[]>;
  load?(taskId: string): Promise<PersistedTask>;
  authorizeRecovery?(task: PersistedTask): Promise<RecoveryGrant>;
  claimNext?(owner: string): Promise<ClaimedTask | null>;
  claimTask?(taskId: string, owner: string): Promise<ClaimedTask | null>;
  renewLease?(taskId: string): Promise<{ cancellationRequested: boolean }>;
  releaseLease?(taskId: string): Promise<void>;
  requestCancellation?(taskId: string): Promise<void>;
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
  private readonly leases = new Map<string, TaskLease>();

  constructor(private readonly origin: string, private readonly serviceToken: string) {
    if (serviceToken.length < 32) throw new Error("ENGINE_SERVICE_TOKEN must contain at least 32 characters");
  }

  async initialize(): Promise<void> { /* Durable tasks are claimed or loaded lazily. */ }

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
      requestDigest: task.view.requestDigest, expectedRevision, checkpoint: task,
      lease: this.leases.get(task.view.taskId)
    });
    this.revisions.set(task.view.taskId, response.checkpointRevision);
  }

  async appendEvent(event: TaskEvent): Promise<void> {
    await this.call("/events", "POST", { contractVersion: "1.0", event, lease: this.leases.get(event.taskId) });
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

  async load(taskId: string): Promise<PersistedTask> {
    const response = await this.call<{ checkpointRevision: number; checkpoint: PersistedTask }>(
      `/tasks/${encodeURIComponent(taskId)}/checkpoint`, "GET");
    this.revisions.set(taskId, response.checkpointRevision);
    return response.checkpoint;
  }

  authorizeRecovery(task: PersistedTask): Promise<RecoveryGrant> {
    return this.call(`/tasks/${encodeURIComponent(task.view.taskId)}/authorize-recovery`, "POST", {
      contractVersion: "1.0", requestDigest: task.view.requestDigest
    });
  }

  async claimNext(owner: string): Promise<ClaimedTask | null> {
    const response = await this.call<{ task: ClaimedTask | null }>("/claims/next", "POST", {
      contractVersion: "1.0", owner
    });
    if (response.task === null) return null;
    this.revisions.set(response.task.checkpoint.view.taskId, response.task.checkpointRevision);
    this.leases.set(response.task.checkpoint.view.taskId, response.task.lease);
    return response.task;
  }

  async claimTask(taskId: string, owner: string): Promise<ClaimedTask | null> {
    const response = await this.call<{ task: ClaimedTask | null }>(
      `/tasks/${encodeURIComponent(taskId)}/claim`, "POST", {
        contractVersion: "1.0", owner
      });
    if (response.task === null) return null;
    this.revisions.set(taskId, response.task.checkpointRevision);
    this.leases.set(taskId, response.task.lease);
    return response.task;
  }

  renewLease(taskId: string): Promise<{ cancellationRequested: boolean }> {
    const lease = this.requireLease(taskId);
    return this.call(`/tasks/${encodeURIComponent(taskId)}/lease/renew`, "POST", {
      contractVersion: "1.0", lease
    });
  }

  async releaseLease(taskId: string): Promise<void> {
    const lease = this.leases.get(taskId);
    if (!lease) return;
    try {
      await this.call(`/tasks/${encodeURIComponent(taskId)}/lease/release`, "POST", {
        contractVersion: "1.0", lease
      });
    } finally { this.leases.delete(taskId); }
  }

  requestCancellation(taskId: string): Promise<void> {
    return this.call(`/tasks/${encodeURIComponent(taskId)}/cancel-request`, "POST", {
      contractVersion: "1.0"
    });
  }

  private requireLease(taskId: string): TaskLease {
    const lease = this.leases.get(taskId);
    if (!lease) throw new Error(`Missing task lease for ${taskId}`);
    return lease;
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
      const queueFull = response.status === 429;
      throw new EngineProblem(response.status, problem(
        queueFull ? "AGENT_USER_QUEUE_FULL" : "TASK_PERSISTENCE_REJECTED",
        response.status >= 500 ? "internal" : "request",
        queueFull
          ? "该用户的 Agent 并发和排队数量已达到当前部署上限，请等待其中一个任务结束后重试。"
          : message,
        response.status >= 500
      ));
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
