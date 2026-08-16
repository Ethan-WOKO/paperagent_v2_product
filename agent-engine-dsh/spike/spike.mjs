// Spike: minimal ReAct loop — "check whether Sort.java compiles" with DeepSeek v4-pro + native tool calls.
// Zero dependencies. Uses the product's real sandbox broker for sandbox_execute.
import { createHash } from 'node:crypto';
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, relative, sep } from 'node:path';

const ROOT = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const FIXTURE = join(ROOT, 'fixture');

function envValue(path, key) {
  const raw = readFileSync(path, 'utf8');
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && m[1] === key) return m[2];
  }
  return undefined;
}
const REPO = 'C:/java_file/private_helper_Agent/paperagent_v2_product';
const DEEPSEEK_KEY = envValue(join(REPO, '.env'), 'DEEPSEEK_API_KEY');
const BROKER_TOKEN = envValue(join(REPO, '.env.sandbox.local'), 'YANBAN_SANDBOX_BROKER_TOKEN');
const BROKER = 'http://127.0.0.1:8091';
const MODEL = 'deepseek-v4-pro';

const listFiles = () => {
  const out = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const stat = statSync(p);
      if (stat.isDirectory()) walk(p);
      else out.push(relative(FIXTURE, p).split(sep).join('/'));
    }
  };
  walk(FIXTURE);
  return out;
};

const readFile = (args) => {
  const path = String(args.path || '');
  const target = resolve(FIXTURE, path);
  if (!target.startsWith(resolve(FIXTURE))) return 'REJECTED: path escapes the project root';
  if (!existsSync(target)) return 'ERROR: file not found: ' + path;
  return readFileSync(target, 'utf8');
};

function canonicalJackson(value, sortKeys) {
  if (Array.isArray(value)) return '[' + value.map((v) => canonicalJackson(v, true)).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    const entries = sortKeys
      ? Object.entries(value).sort((a, b) => (a[0] < b[0] ? -1 : 1))
      : Object.entries(value);
    return '{' + entries.map(([k, v]) => JSON.stringify(k) + ':' + canonicalJackson(v, true)).join(',') + '}';
  }
  return JSON.stringify(value);
}
const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

const sandboxExecute = async (args) => {
  const paths = (args.paths || []).map(String);
  const argv = (args.argv || []).map(String);
  const files = {};
  for (const path of paths) {
    const target = resolve(FIXTURE, path);
    if (!target.startsWith(resolve(FIXTURE))) return 'REJECTED: path escapes the project root: ' + path;
    if (!existsSync(target)) return 'ERROR: file not found: ' + path;
    files[path] = readFileSync(target, 'utf8');
  }
  const dispatch = {
    idempotencyKey: 'spike-' + Date.now(),
    requestDigest: '',
    userId: 2, projectId: 95, sessionId: 1, planId: 1, stepId: 1,
    fence: 1, projectVersion: 'a'.repeat(64), policyDigest: '0'.repeat(64),
    files, argv, cpus: 2, memoryBytes: 536870912,
    timeoutMillis: 300000, maxOutputBytes: 20971520, networkEnabled: false,
  };
  dispatch.requestDigest = sha256(canonicalJackson(dispatch, false));

  const res = await fetch(BROKER + '/internal/v1/executions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + BROKER_TOKEN },
    body: JSON.stringify(dispatch),
  });
  const bodyText = await res.text();
  if (!res.ok) return 'BROKER_REJECTED: ' + bodyText.slice(0, 600);
  const created = JSON.parse(bodyText);
  const executionId = created.executionId;

  const deadline = Date.now() + 360000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 3000));
    const st = await fetch(BROKER + '/internal/v1/executions/' + executionId, {
      headers: { Authorization: 'Bearer ' + BROKER_TOKEN },
    });
    const view = JSON.parse(await st.text());
    if (['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(view.status)) {
      const r = view.receipt || {};
      return JSON.stringify({
        status: view.status, exitCode: r.exitCode ?? null,
        stdout: (r.stdout || '').slice(-4000), stderr: (r.stderr || '').slice(-4000),
        errorCode: view.errorCode ?? null,
      });
    }
  }
  return 'TIMED_OUT_WAITING_FOR_BROKER';
};

const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'project_list_files',
      description: 'List relative paths of all files in the project (read only).',
      parameters: { type: 'object', properties: {}, required: [] },
    },
  },
  {
    type: 'function',
    function: {
      name: 'project_read_file',
      description: 'Read the complete content of one project file (read only; never modifies).',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: 'project-relative path, e.g. src/main/java/Sort.java' } },
        required: ['path'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'sandbox_execute',
      description:
        'Compile/run project files in the isolated sandbox. argv accepts only fixed shapes: ' +
        'yanban-runner java <source>, yanban-runner java <source> --dependency=group:artifact:version ..., ' +
        'yanban-runner python <source>, javac <.java files>, mvn -o test, and bounded git checks. ' +
        'When compiling/running Java or Python files that use third-party libraries, declare every ' +
        'non-standard dependency in the first run with --dependency=group:artifact:version; otherwise ' +
        'the run fails because the dependency is missing. paths lists the project files involved.',
      parameters: {
        type: 'object',
        properties: {
          paths: { type: 'array', items: { type: 'string' }, description: 'project-relative paths involved' },
          argv: { type: 'array', items: { type: 'string' }, description: 'complete argv executed in the sandbox' },
        },
        required: ['paths', 'argv'],
      },
    },
  },
];

async function callModel(messages) {
  const res = await fetch('https://api.deepseek.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + DEEPSEEK_KEY },
    body: JSON.stringify({
      model: MODEL,
      messages,
      tools: TOOLS,
      tool_choice: 'auto',
      max_tokens: 4096,
    }),
  });
  const body = await res.text();
  if (!res.ok) throw new Error('model call failed HTTP ' + res.status + ': ' + body.slice(0, 400));
  const data = JSON.parse(body);
  const msg = data.choices[0].message;
  return { content: msg.content || '', toolCalls: msg.tool_calls || [], usage: data.usage };
}

const SYSTEM =
  'You are the coding agent of a research-assistant product. Goal: check whether ' +
  'src/main/java/Sort.java in the project compiles successfully.\n' +
  'Constraint: never modify any file; inspection only.\n' +
  'Method: list project files and read Sort.java to inspect imports; then call sandbox_execute to compile. ' +
  'If the file uses third-party libraries, declare them on the FIRST run with --dependency=group:artifact:version; ' +
  'do not run bare first. Tool results come back to you. If a run fails, analyze the reason and retry with an ' +
  'adjusted command instead of giving up. After you have solid evidence, output the final conclusion ' +
  '(compiles / does not compile, with the reason).';

async function main() {
  const messages = [
    { role: 'system', content: SYSTEM },
    { role: 'user', content: 'Check whether src/main/java/Sort.java compiles successfully.' },
  ];
  for (let round = 1; round <= 14; round++) {
    const { content, toolCalls, usage } = await callModel(messages);
    console.log(`\n[round ${round}] text: ${content || '(none)'}  tool_calls=${toolCalls.length}  tokens=${usage?.total_tokens ?? '?'}`);
    if (toolCalls.length === 0) {
      console.log('\n=== FINAL ANSWER ===\n' + content);
      return;
    }
    messages.push({ role: 'assistant', content: content || null, tool_calls: toolCalls });
    for (const call of toolCalls) {
      const fn = call.function;
      let result;
      try {
        const args = JSON.parse(fn.arguments || '{}');
        console.log(`[round ${round}] ${fn.name}(${fn.arguments})`);
        if (fn.name === 'project_list_files') result = JSON.stringify(listFiles());
        else if (fn.name === 'project_read_file') result = readFile(args);
        else if (fn.name === 'sandbox_execute') result = await sandboxExecute(args);
        else result = 'ERROR: unknown tool ' + fn.name;
      } catch (e) {
        result = 'TOOL_ERROR: ' + e.message;
      }
      messages.push({ role: 'tool', tool_call_id: call.id, content: String(result).slice(0, 12000) });
    }
  }
  console.log('\n=== ROUND LIMIT REACHED ===');
}

main().catch((e) => { console.error('SPIKE FAILED:', e); process.exit(1); });
