import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';

const here = dirname(fileURLToPath(import.meta.url));

/** Shared contract schemas live in the sibling agent-engine-contract directory
 * (Issue #150). The engine consumes them directly instead of copying. */
const SCHEMAS_DIR = join(here, '..', '..', '..', 'agent-engine-contract', 'schemas');

const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
// The shared schemas use RFC3339 `date-time`; ajv core has no built-in formats.
ajv.addFormat('date-time', {
  type: 'string',
  validate: (value: string) => typeof value === 'string' && !Number.isNaN(Date.parse(value)),
});

const schemas = new Map<string, object>();
for (const file of readdirSync(SCHEMAS_DIR).filter((f) => f.endsWith('.schema.json')).sort()) {
  const schema = JSON.parse(readFileSync(join(SCHEMAS_DIR, file), 'utf8')) as object;
  schemas.set((schema as { $id?: string }).$id ?? file, schema);
  ajv.addSchema(schema, (schema as { $id?: string }).$id ?? file);
}

const ids = {
  taskSubmission: 'https://paperagent.local/agent-engine/schemas/task-submission.schema.json',
  taskAnswer: 'https://paperagent.local/agent-engine/schemas/task-answer.schema.json',
  taskEvent: 'https://paperagent.local/agent-engine/schemas/task-event.schema.json',
  taskView: 'https://paperagent.local/agent-engine/schemas/task-view.schema.json',
  problem: 'https://paperagent.local/agent-engine/schemas/problem.schema.json',
  gateway: 'https://paperagent.local/agent-engine/schemas/gateway.schema.json',
  receipt: 'https://paperagent.local/agent-engine/schemas/receipt.schema.json',
};

export class SchemaViolation extends Error {
  readonly path: string;

  constructor(path: string, message: string) {
    super(`${path}: ${message}`);
    this.path = path;
  }
}

export function validateSubmission(value: unknown): void {
  assertValid(ids.taskSubmission, value, 'task submission');
}

export function validateAnswer(value: unknown): void {
  assertValid(ids.taskAnswer, value, 'task answer');
}

export function validateEvent(value: unknown): void {
  assertValid(ids.taskEvent, value, 'task event');
}

export function validateView(value: unknown): void {
  assertValid(ids.taskView, value, 'task view');
}

export function validateProblem(value: unknown): void {
  assertValid(ids.problem, value, 'problem');
}

export function validateFileList(value: unknown): void {
  assertValid(ids.gateway + '#/$defs/fileList', value, 'gateway fileList');
}

export function validateFileRead(value: unknown): void {
  assertValid(ids.gateway + '#/$defs/fileRead', value, 'gateway fileRead');
}

export function validateSandboxView(value: unknown): void {
  assertValid(ids.gateway + '#/$defs/sandboxView', value, 'gateway sandboxView');
}

export function validateReceipt(value: unknown): void {
  assertValid(ids.receipt, value, 'receipt');
}

export function schemaPresent(): boolean {
  return [...schemas.keys()].length >= 7;
}

function assertValid(schemaId: string, value: unknown, label: string): void {
  const validate = ajv.getSchema(schemaId);
  if (!validate) throw new SchemaViolation(schemaId, `schema not loaded for ${label}`);
  if (!validate(value)) {
    const first = validate.errors?.[0];
    const path = first?.instancePath || '(root)';
    throw new SchemaViolation(`${label}${path}`, first?.message ?? 'schema violation');
  }
}
