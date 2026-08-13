package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

public interface ChainModelRepository {
    Optional<ChainPersistenceRecords.ModelInvocationRecord> findInvocation(String invocationId);

    /** Exact task-local invocation watermark; never infer this from time. */
    long highestInvocationOrdinal(String taskId);

    List<ChainPersistenceRecords.ModelInvocationRecord> findInvocations(
            String taskId, long invocationOrdinalCut);

    /** Exact invocation-local provider-attempt watermark. */
    int highestProviderAttemptNo(String invocationId);

    List<ChainPersistenceRecords.ProviderAttemptRecord> findProviderAttempts(
            String invocationId);

    List<ChainPersistenceRecords.ContentRecord> findContents(
            String invocationId);

    Optional<ChainPersistenceRecords.ContentRecord> findContent(
            String contentId);

    Optional<ChainPersistenceRecords.ModelProposalRecord> findProposal(String proposalId);

    Optional<ChainPersistenceRecords.ModelProposalRecord> findProposalByInvocation(
            String invocationId);

    List<ChainPersistenceRecords.ProposalStateEventRecord> findProposalStateEvents(String proposalId);
}
