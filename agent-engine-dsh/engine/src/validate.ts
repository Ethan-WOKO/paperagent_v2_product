import { validateSubmission, validateAnswer } from './schemas.ts';
import { answerDigestOf } from './canonical.ts';

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

export interface TaskAnswerRequest {
  contractVersion: '1.0';
  clientRequestId: string;
  questionId: string;
  answer: string;
  answerDigest: string;
}

/** Shared-schema validation wrapper: any violation becomes SchemaViolation with
 * an instance path. Callers convert to 400 INVALID_SUBMISSION / INVALID_ANSWER. */
export function parseTaskSubmission(body: unknown): TaskSubmission {
  validateSubmission(body);
  return body as TaskSubmission;
}

export function parseAnswerBody(body: unknown): TaskAnswerRequest {
  validateAnswer(body);
  const request = body as TaskAnswerRequest;
  if (answerDigestOf(request.answer) !== request.answerDigest) {
    throw new Error('answerDigest does not match the exact UTF-8 answer bytes');
  }
  return request;
}

/** The cancel body is only defined inline in openapi.yaml (no shared schema
 * file); keep the same shape check here, mirroring the frozen contract. */
export function parseCancelBody(body: unknown): { contractVersion: '1.0'; clientRequestId: string } {
  if (typeof body !== 'object' || body === null) throw new Error('invalid cancel body');
  const b = body as Record<string, unknown>;
  if (b.contractVersion !== '1.0') throw new Error('invalid contractVersion');
  if (typeof b.clientRequestId !== 'string' || !/^cancel\.[A-Za-z0-9_-]{16,120}$/.test(b.clientRequestId)) {
    throw new Error('invalid clientRequestId');
  }
  return { contractVersion: '1.0', clientRequestId: b.clientRequestId };
}
