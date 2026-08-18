import { randomUUID } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import process from 'node:process';
import { TASK_CASES, MUTATION_CASE } from './cases.mjs';
import { redact, scoreTask, summarize } from './scoring.mjs';

const TERMINAL = new Set(['succeeded', 'failed', 'cancelled', 'waiting_user']);
const BLOCKED_SEGMENTS = new Set(['.git', 'target', 'build', 'node_modules', '.idea']);
const BLOCKED_NAMES = new Set(['.env', '.npmrc', '.pypirc', 'credentials', 'credentials.json']);
const origin = (process.env.PAPERAGENT_EVAL_ORIGIN ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const sourceRoot = process.env.PAPERAGENT_EVAL_PROJECT_SOURCE;
const outputRoot = path.resolve('.eval-results');
const runId = process.env.PAPERAGENT_EVAL_RUN_ID ?? new Date().toISOString().replace(/[:.]/g, '-');

export class ProductClient {
  constructor(baseUrl) { this.baseUrl = baseUrl; this.token = null; }

  async login(username, password) {
    const response = await this.request('/api/v1/auth/login', { method: 'POST', body: { username, password }, anonymous: true });
    this.token = response.accessToken;
    if (!this.token) throw new Error('Login response did not contain an access token');
  }

  async request(route, { method = 'GET', body, headers = {}, anonymous = false, raw = false, signal } = {}) {
    const requestHeaders = { ...headers };
    if (!anonymous && this.token) requestHeaders.Authorization = `Bearer ${this.token}`;
    let payload = body;
    if (body !== undefined && !(body instanceof FormData)) {
      requestHeaders['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const response = await fetch(`${this.baseUrl}${route}`, { method, headers: requestHeaders, body: payload, signal });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`${method} ${route} returned ${response.status}: ${text.slice(0, 500)}`);
    }
    if (raw) return response;
    if (response.status === 204) return null;
    return response.json();
  }

  async uploadProject(name, root) {
    const form = new FormData();
    form.append('name', name);
    form.append('includeRules', '**');
    for (const relativePath of await admittedFiles(root)) {
      const bytes = await readFile(path.join(root, ...relativePath.split('/')));
      form.append('files', new Blob([bytes]), `${name}/${relativePath}`);
    }
    return this.request('/api/v1/projects', { method: 'POST', body: form });
  }

  createSession(projectId, title) {
    return this.request(`/api/v1/projects/${projectId}/agent/sessions`, {
      method: 'POST', body: { title, modelProvider: 'deepseek', model: 'deepseek-v4-flash', maxSteps: 20, ragDisabled: false },
    });
  }

  startTask(sessionId, instruction) {
    return this.request(`/api/v1/react-agent/sessions/${sessionId}/tasks`, {
      method: 'POST', body: { clientRequestId: `request.eval193_${randomUUID()}`, instruction },
    });
  }

  task(turnId, taskId) { return this.request(`/api/v1/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}`); }
  trace(turnId, taskId) { return this.request(`/api/v1/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}/trace`); }
  events(turnId, taskId, after = 0, signal) {
    return this.request(`/api/v1/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}/events`, {
      headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(after) }, raw: true, signal,
    });
  }
  cancel(turnId, taskId, clientRequestId = `cancel.eval193_${randomUUID()}`) {
    return this.request(`/api/v1/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: 'POST', body: { clientRequestId },
    });
  }
}

async function admittedFiles(root, relative = '') {
  const output = [];
  for (const entry of await readdir(path.join(root, relative), { withFileTypes: true })) {
    if (BLOCKED_SEGMENTS.has(entry.name) || BLOCKED_NAMES.has(entry.name) || entry.name.startsWith('.env.')) continue;
    const child = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) output.push(...await admittedFiles(root, child));
    else if (entry.isFile()) output.push(child.replaceAll('\\', '/'));
  }
  return output.sort();
}

export async function waitForTerminal(client, accepted, timeoutMillis = 240_000) {
  const deadline = Date.now() + timeoutMillis;
  let view = accepted.task ?? accepted;
  while (!TERMINAL.has(view.state)) {
    if (Date.now() >= deadline) throw new Error(`Task ${accepted.taskId} did not finish within ${timeoutMillis} ms`);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    view = await client.task(accepted.turnId, accepted.taskId);
  }
  return view;
}

export async function readSse(response, { stopAfter = Number.POSITIVE_INFINITY } = {}) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const events = [];
  let buffer = '';
  while (events.length < stopAfter) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? '';
    for (const block of blocks) {
      const id = block.match(/^id:\s*(\d+)$/m)?.[1];
      const data = block.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n');
      if (data) events.push({ id: id ? Number(id) : null, data: JSON.parse(data) });
      if (events.length >= stopAfter) break;
    }
    if (done) break;
  }
  await reader.cancel().catch(() => {});
  return events;
}

function observedFrom(events, trace, view, versions = {}) {
  const conclusion = [...events].reverse().find((event) => event.type === 'delivery')?.conclusion ?? '';
  const toolEvents = events.filter((event) => event.type === 'tool');
  const evidenceCount = toolEvents.reduce((sum, event) => {
    const count = event.outputSummary?.match(/evidenceCount=(\d+)/)?.[1];
    return sum + (count ? Number(count) : 0);
  }, 0);
  const sandboxSuccess = toolEvents.some((event) => event.name === 'sandbox.execute'
    && event.state === 'succeeded' && /status=SUCCEEDED;\s*exitCode=0\b/.test(event.outputSummary ?? ''));
  const toolNames = new Set(toolEvents.map((event) => event.name));
  for (const event of toolEvents) {
    const registered = `${event.inputSummary ?? ''};${event.outputSummary ?? ''}`.match(/registeredTool=([a-z][a-z0-9_]{0,63})/)?.[1];
    if (registered) toolNames.add(registered);
  }
  return {
    state: view.state,
    conclusion,
    toolNames: [...toolNames],
    evidenceCount,
    sandboxSuccess,
    metrics: trace.summary,
    ...versions,
  };
}

export async function collect(client, accepted, versions = {}) {
  const view = await waitForTerminal(client, accepted);
  const page = await client.request(`/api/v1/react-agent/sessions/${accepted.sessionId ?? 0}/tasks?includeEvents=true&limit=1`).catch(() => null);
  let events = page?.items?.find((item) => item.taskId === accepted.taskId)?.events;
  if (!events) {
    const response = await client.events(accepted.turnId, accepted.taskId, 0);
    events = (await readSse(response)).map(({ data }) => data);
  }
  const trace = await client.trace(accepted.turnId, accepted.taskId);
  return { view, events, trace, observed: observedFrom(events, trace, view, versions) };
}

export async function runTaskCase(client, projectId, definition) {
  const started = Date.now();
  const session = await client.createSession(projectId, `EVAL-193 ${definition.id}`);
  const accepted = await client.startTask(session.id, definition.instruction);
  accepted.sessionId = session.id;
  const collected = await collect(client, accepted);
  const score = scoreTask(definition.expect, collected.observed);
  return resultRecord(definition.id, collected, score, Date.now() - started);
}

export function resultRecord(id, collected, score, durationMillis) {
  return redact({
    id, score, durationMillis, state: collected.view.state,
    taskId: collected.view.taskId, lastSequence: collected.view.lastSequence,
    metrics: collected.trace.summary,
    tools: collected.observed.toolNames,
    evidenceCount: collected.observed.evidenceCount,
    answerHash: collected.observed.conclusion ? (awaitHash(collected.observed.conclusion)) : null,
    terminalError: collected.view.error ?? null,
  });
}

function awaitHash(text) {
  // Kept synchronous to make result construction and redaction mechanically simple.
  let hash = 2166136261;
  for (const char of text) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  return `fnv1a32:${(hash >>> 0).toString(16).padStart(8, '0')}`;
}

async function setupMemory(client, projectId) {
  return client.request('/api/v1/settings/memory', {
    method: 'POST',
    body: { projectId, scope: 'PROJECT', memoryType: 'FACT', content: '本次评测约定的代号是 EVAL-193-MEMORY。', tags: ['eval-193'], confidence: 1 },
  });
}

export async function writeReport(report) {
  await mkdir(outputRoot, { recursive: true });
  const file = path.join(outputRoot, `${runId}.json`);
  await writeFile(file, `${JSON.stringify(redact(report), null, 2)}\n`, 'utf8');
  return file;
}

async function main() {
  const username = process.env.PAPERAGENT_EVAL_USERNAME;
  const password = process.env.PAPERAGENT_EVAL_PASSWORD;
  if (!username || !password || !sourceRoot) {
    throw new Error('Set PAPERAGENT_EVAL_USERNAME, PAPERAGENT_EVAL_PASSWORD, and PAPERAGENT_EVAL_PROJECT_SOURCE');
  }
  const client = new ProductClient(origin);
  await client.login(username, password);
  const requestedProjectId = Number(process.env.PAPERAGENT_EVAL_PROJECT_ID ?? 0);
  const selectedIds = new Set((process.env.PAPERAGENT_EVAL_CASES ?? '').split(',').map((value) => value.trim()).filter(Boolean));
  const definitions = selectedIds.size ? TASK_CASES.filter(({ id }) => selectedIds.has(id)) : TASK_CASES;
  const projectName = requestedProjectId ? null : `java_test_eval_193_${Date.now()}`;
  const project = requestedProjectId ? { id: requestedProjectId, name: `project-${requestedProjectId}` }
    : await client.uploadProject(projectName, sourceRoot);
  const manifest = await client.request(`/api/v1/projects/${project.id}/manifest`);
  const memory = definitions.some(({ memory }) => memory) ? await setupMemory(client, project.id) : null;
  const results = [];
  try {
    for (const definition of definitions) {
      const effective = definition.memory
        ? { ...definition, instruction: `${definition.instruction} 当前指令指定代号为 EVAL-193-CURRENT。` }
        : definition;
      process.stdout.write(`[eval] ${definition.id} ... `);
      let result;
      try {
        result = await runTaskCase(client, project.id, effective);
      } catch (error) {
        result = { id: definition.id, score: { passed: false, checks: [] }, runnerError: redact(error.message) };
      }
      results.push(result);
      process.stdout.write(`${result.score.passed ? 'PASS' : 'FAIL'}\n`);
    }
  } finally {
    if (memory) await client.request(`/api/v1/settings/memory/${memory.id}`, { method: 'DELETE' }).catch(() => {});
  }
  const report = {
    contractVersion: '1.0', issue: 193, runId, createdAt: new Date().toISOString(),
    environment: { origin, projectId: project.id, projectName: project.name ?? projectName, initialProjectVersion: manifest.version, fileCount: manifest.files.length },
    results, summary: summarize(results), pendingCases: [MUTATION_CASE.id, 'rollback', 'running-cancel', 'queued-cancel-release', 'engine-restart-recovery', 'sse-refresh-resume', 'multi-conversation-concurrency'],
  };
  const file = await writeReport(report);
  console.log(`[eval] report=${file}`);
  console.log(`[eval] passed=${report.summary.passed}/${report.summary.total} tokens=${report.summary.totalTokens}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`[eval] fatal: ${redact(error.message)}`);
    process.exitCode = 1;
  });
}
