import { mkdir, open, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import type { AcceptedAnswer, PersistedTask, TaskEvent } from "./types.js";

type JournaledAnswer = AcceptedAnswer & { answer: string };

export class TaskStore {
  constructor(private readonly root: string) {}

  async initialize(): Promise<void> { await mkdir(this.root, { recursive: true }); }

  private directory(taskId: string): string { return resolve(this.root, taskId); }

  async create(task: PersistedTask): Promise<void> {
    await this.initialize();
    const target = this.directory(task.view.taskId);
    const temporary = resolve(this.root, `.creating.${task.view.taskId}.${process.pid}.${randomUUID()}`);
    await mkdir(temporary, { recursive: false });
    try {
      await writeFile(resolve(temporary, "task.json"), JSON.stringify(task), { encoding: "utf8", flag: "wx" });
      await rename(temporary, target);
    } catch (error) {
      await rm(temporary, { recursive: true, force: true });
      throw error;
    }
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

  async appendAnswer(taskId: string, answer: JournaledAnswer): Promise<void> {
    const handle = await open(resolve(this.directory(taskId), "answers.jsonl"), "a");
    try { await handle.writeFile(`${JSON.stringify(answer)}\n`, "utf8"); await handle.sync(); }
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

  async answers(taskId: string): Promise<JournaledAnswer[]> {
    try {
      const text = await readFile(resolve(this.directory(taskId), "answers.jsonl"), "utf8");
      return text.split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line) as JournaledAnswer);
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
        const answers = await this.answers(entry.name);
        reconcile(task, events, answers);
        tasks.push(task);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }
    }
    return tasks;
  }
}

function reconcile(task: PersistedTask, events: TaskEvent[], answers: JournaledAnswer[]): void {
  for (const event of events) {
    task.view.lastSequence = Math.max(task.view.lastSequence, event.sequence);
    task.view.updatedAt = event.occurredAt;
    if (event.type === "status") {
      task.view.state = event.state;
      task.view.error = event.error;
      if (["succeeded", "failed", "cancelled"].includes(event.state)) task.view.terminalSequence = event.sequence;
    } else if (event.type === "delivery") task.view.deliverySequence = event.sequence;
    else if (event.type === "question") {
      task.view.pendingQuestionId = event.questionId;
      if (!isTerminal(task.view.state)) task.view.state = "waiting_user";
    }
  }
  for (const answer of answers) reconcileAnswer(task, answer);
}

function reconcileAnswer(task: PersistedTask, answer: JournaledAnswer): void {
  const byRequest = task.acceptedAnswers.find((value) => value.clientRequestId === answer.clientRequestId);
  if (byRequest && (byRequest.questionId !== answer.questionId || byRequest.answerDigest !== answer.answerDigest)) throw new Error("Persisted answer request authority conflicts with its journal");
  const byQuestion = task.acceptedAnswers.find((value) => value.questionId === answer.questionId);
  if (byQuestion) {
    if (byQuestion.answerDigest !== answer.answerDigest) throw new Error("Persisted question authority conflicts with its journal");
    return;
  }
  if ((task.view.state !== "waiting_user" && task.view.state !== "running") || task.view.pendingQuestionId !== answer.questionId) throw new Error("Journaled answer has no matching pending question");
  const pending = task.pendingCalls[task.nextPendingCall];
  if (!pending || pending.name !== "ask_user") throw new Error("Journaled answer has no matching pending tool call");
  task.acceptedAnswers.push(answer);
  task.messages.push({ role: "tool", toolCallId: pending.id, content: JSON.stringify({ answer: answer.answer }) });
  task.nextPendingCall += 1;
  task.view.pendingQuestionId = null;
}

function isTerminal(state: PersistedTask["view"]["state"]): boolean {
  return state === "succeeded" || state === "failed" || state === "cancelled";
}
