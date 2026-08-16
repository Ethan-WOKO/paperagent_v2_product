import { mkdir, open, readFile, readdir, rename, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import type { PersistedTask, TaskEvent } from "./types.js";

export class TaskStore {
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
        reconcile(task, events);
        tasks.push(task);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }
    }
    return tasks;
  }
}

function reconcile(task: PersistedTask, events: TaskEvent[]): void {
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
