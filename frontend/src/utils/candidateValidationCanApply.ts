import type { CandidateValidationResponse } from '@/api/project';

export interface CandidateValidationBinding {
  projectVersion: string;
  candidateFingerprint: string;
  acceptedChangeIndexes: number[];
}

export function candidateValidationCanApply(
  validation: CandidateValidationResponse,
  binding: CandidateValidationBinding,
) {
  const trustedProvider = validation.provider === 'docker-sbx' || validation.provider === 'e2b';
  const validExecutionProof = validation.profile === 'DOCUMENT_INTEGRITY'
    ? validation.exitCode === null
      && validation.provider === null
      && validation.receiptDigest === null
      && validation.errorCode === null
      && validation.stdout === ''
      && validation.stderr === ''
    : validation.exitCode === 0 && trustedProvider && validation.receiptDigest !== null;
  return validation.status === 'SUCCEEDED'
    && !validation.timedOut
    && validExecutionProof
    && validation.decisionStatus === 'PENDING'
    && validation.projectVersion === binding.projectVersion
    && validation.candidateFingerprint === binding.candidateFingerprint
    && validation.acceptedChangeIndexes.length === binding.acceptedChangeIndexes.length
    && validation.acceptedChangeIndexes.every(
      (value, index) => value === binding.acceptedChangeIndexes[index],
    );
}
