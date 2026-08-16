import { CODE_PATTERN, SHA256_PATTERN, TASK_ID_PATTERN, CALL_ID_PATTERN, PROJECT_ID_PATTERN } from './problem.ts';

export interface TaskAuthority {
  runMode: 'PERSISTENT_PLAN_EXECUTE';
  sessionRef: string;
  project: { projectId: string; projectVersion: string };
  instruction: string;
  permissions: { readProject: boolean; writeWorkspace: boolean; executeSandbox: boolean };
  model: { provider: string; model: string };
}

export interface TaskGateway {
  taskGrant: string;
  expiresAt: string;
}

export interface TaskSubmission {
  contractVersion: '1.0';
  taskId: string;
  requestDigest: string;
  authority: TaskAuthority;
  gateway: TaskGateway;
}

function fail(field: string): never {
  throw new Error('invalid field: ' + field);
}

export function parseTaskSubmission(body: unknown): TaskSubmission {
  if (typeof body !== 'object' || body === null) fail('root');
  const b = body as Record<string, unknown>;
  const topKeys = Object.keys(b);
  for (const k of topKeys) {
    if (!['contractVersion', 'taskId', 'requestDigest', 'authority', 'gateway'].includes(k)) fail(k);
  }
  if (b.contractVersion !== '1.0') fail('contractVersion');
  const taskId = typeof b.taskId === 'string' && TASK_ID_PATTERN.test(b.taskId) ? b.taskId : fail('taskId');
  const requestDigest = typeof b.requestDigest === 'string' && SHA256_PATTERN.test(b.requestDigest) ? b.requestDigest : fail('requestDigest');

  const authority = b.authority;
  if (typeof authority !== 'object' || authority === null) fail('authority');
  const a = authority as Record<string, unknown>;
  for (const k of Object.keys(a)) {
    if (!['runMode', 'sessionRef', 'project', 'instruction', 'permissions', 'model'].includes(k)) fail('authority.' + k);
  }
  if (a.runMode !== 'PERSISTENT_PLAN_EXECUTE') fail('authority.runMode');
  const sessionRef = typeof a.sessionRef === 'string' && a.sessionRef.length >= 1 && a.sessionRef.length <= 128 ? a.sessionRef : fail('authority.sessionRef');
  const project = a.project;
  if (typeof project !== 'object' || project === null) fail('authority.project');
  const p = project as Record<string, unknown>;
  if (typeof p.projectId !== 'string' || !PROJECT_ID_PATTERN.test(p.projectId)) fail('authority.project.projectId');
  if (typeof p.projectVersion !== 'string' || !SHA256_PATTERN.test(p.projectVersion)) fail('authority.project.projectVersion');
  const instruction = typeof a.instruction === 'string' && a.instruction.length >= 1 && a.instruction.length <= 16000 ? a.instruction : fail('authority.instruction');
  const permissions = a.permissions;
  if (typeof permissions !== 'object' || permissions === null) fail('authority.permissions');
  const perm = permissions as Record<string, unknown>;
  if (perm.readProject !== true) fail('authority.permissions.readProject');
  if (perm.writeWorkspace !== false) fail('authority.permissions.writeWorkspace');
  if (perm.executeSandbox !== true) fail('authority.permissions.executeSandbox');
  const model = a.model;
  if (typeof model !== 'object' || model === null) fail('authority.model');
  const m = model as Record<string, unknown>;
  const provider = typeof m.provider === 'string' && m.provider.length >= 1 && m.provider.length <= 64 ? m.provider : fail('authority.model.provider');
  const modelName = typeof m.model === 'string' && m.model.length >= 1 && m.model.length <= 128 ? m.model : fail('authority.model.model');

  const gateway = b.gateway;
  if (typeof gateway !== 'object' || gateway === null) fail('gateway');
  const g = gateway as Record<string, unknown>;
  const taskGrant = typeof g.taskGrant === 'string' && g.taskGrant.length >= 32 && g.taskGrant.length <= 4096 ? g.taskGrant : fail('gateway.taskGrant');
  const expiresAt = typeof g.expiresAt === 'string' && !Number.isNaN(Date.parse(g.expiresAt)) ? g.expiresAt : fail('gateway.expiresAt');

  return {
    contractVersion: '1.0',
    taskId,
    requestDigest,
    authority: {
      runMode: 'PERSISTENT_PLAN_EXECUTE',
      sessionRef,
      project: { projectId: p.projectId, projectVersion: p.projectVersion },
      instruction,
      permissions: { readProject: true, writeWorkspace: false, executeSandbox: true },
      model: { provider, model: modelName },
    },
    gateway: { taskGrant, expiresAt },
  };
}

export function parseCancelBody(body: unknown): { contractVersion: '1.0'; clientRequestId: string } {
  if (typeof body !== 'object' || body === null) fail('root');
  const b = body as Record<string, unknown>;
  if (b.contractVersion !== '1.0') fail('contractVersion');
  if (typeof b.clientRequestId !== 'string' || !/^cancel\.[A-Za-z0-9_-]{16,120}$/.test(b.clientRequestId)) fail('clientRequestId');
  return { contractVersion: '1.0', clientRequestId: b.clientRequestId };
}

export function parseAnswerBody(body: unknown): { contractVersion: '1.0'; clientRequestId: string; questionId: string; answer: string } {
  if (typeof body !== 'object' || body === null) fail('root');
  const b = body as Record<string, unknown>;
  if (b.contractVersion !== '1.0') fail('contractVersion');
  if (typeof b.clientRequestId !== 'string' || !/^answer\.[A-Za-z0-9_-]{16,120}$/.test(b.clientRequestId)) fail('clientRequestId');
  const questionId = typeof b.questionId === 'string' && b.questionId.length >= 1 && b.questionId.length <= 128 ? b.questionId : fail('questionId');
  const answer = typeof b.answer === 'string' && b.answer.length >= 1 && b.answer.length <= 16000 ? b.answer : fail('answer');
  return { contractVersion: '1.0', clientRequestId: b.clientRequestId, questionId, answer };
}

export function validProblemCode(code: string): boolean {
  return CODE_PATTERN.test(code);
}
