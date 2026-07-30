import { describe, expect, it } from 'vitest';
import type { CandidateValidationResponse } from '../src/api/project';
import { candidateValidationCanApply } from '../src/utils/candidateValidationCanApply';

const binding = {
  projectVersion: 'a'.repeat(64),
  candidateFingerprint: 'b'.repeat(64),
  acceptedChangeIndexes: [0, 2],
};

function validation(overrides: Partial<CandidateValidationResponse> = {}): CandidateValidationResponse {
  return {
    validationId: 'validation-1',
    projectId: 7,
    artifactId: 41,
    ...binding,
    profile: 'MAVEN_TEST',
    status: 'SUCCEEDED',
    exitCode: 0,
    timedOut: false,
    provider: 'docker-sbx',
    stdout: 'bounded output',
    stderr: '',
    outputTruncated: true,
    requestDigest: 'c'.repeat(64),
    receiptDigest: 'd'.repeat(64),
    errorCode: null,
    analysisSummary: null,
    analysisDisclaimer: null,
    decisionStatus: 'PENDING',
    applicationOperationId: null,
    appliedRevisionId: null,
    createdAt: '2026-07-20T12:00:00Z',
    updatedAt: '2026-07-20T12:00:01Z',
    ...overrides,
  };
}

describe('Candidate validation application eligibility', () => {
  it('allows a successful bounded receipt even when its output was truncated', () => {
    expect(candidateValidationCanApply(validation(), binding)).toBe(true);
    expect(candidateValidationCanApply(validation({ provider: 'e2b' }), binding)).toBe(true);
  });

  it('still rejects failed and timed-out validations', () => {
    expect(candidateValidationCanApply(validation({ status: 'FAILED', exitCode: 1 }), binding)).toBe(false);
    expect(candidateValidationCanApply(validation({ timedOut: true }), binding)).toBe(false);
    expect(candidateValidationCanApply(validation({ provider: 'untrusted' }), binding)).toBe(false);
  });

  it('allows only receipt-free local proof for document integrity', () => {
    const document = {
      profile: 'DOCUMENT_INTEGRITY' as const,
      exitCode: null,
      provider: null,
      receiptDigest: null,
      errorCode: null,
      stdout: '',
      stderr: '',
    };
    expect(candidateValidationCanApply(validation(document), binding)).toBe(true);
    expect(candidateValidationCanApply(validation({
      ...document,
      provider: 'e2b',
    }), binding)).toBe(false);
    expect(candidateValidationCanApply(validation({
      ...document,
      receiptDigest: 'd'.repeat(64),
    }), binding)).toBe(false);
  });
});
