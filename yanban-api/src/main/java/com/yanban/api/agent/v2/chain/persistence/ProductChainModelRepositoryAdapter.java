package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainContentWriter;
import io.paperagent.v2.chain.ChainModelInvocationWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainProposalStateWriter;
import io.paperagent.v2.chain.ChainProposalWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelInvocationRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProviderAttemptRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductChainModelRepositoryAdapter
        implements ChainModelRepository, ChainModelInvocationWriter,
        ChainContentWriter, ChainProposalWriter, ChainProposalStateWriter {
    private final ProductChainTransactions transactions;

    public ProductChainModelRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public AppendResult<ModelInvocationRecord> appendInvocation(
            ModelInvocationRecord invocation) {
        return transactions.append("agent_v2_chain_model_invocations",
                ModelInvocationRecord.class, invocation,
                Map.of("invocation_id", invocation.invocationId()));
    }

    @Override
    public AppendResult<ProviderAttemptRecord> appendProviderAttempt(
            ProviderAttemptRecord attempt) {
        return transactions.append("agent_v2_chain_provider_attempts",
                ProviderAttemptRecord.class, attempt,
                ordered("invocation_id", attempt.invocationId(),
                        "attempt_no", attempt.attemptNo()));
    }

    @Override
    public AppendResult<ContentRecord> appendContent(ContentRecord content) {
        if (!ProductChainRecordCodec.sha256(content.body())
                .equals(content.bodySha256())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONTENT_BODY_DIGEST_MISMATCH");
        }
        return transactions.append("agent_v2_chain_contents",
                ContentRecord.class, content,
                Map.of("content_id", content.contentId()));
    }

    @Override
    public AppendResult<ModelProposalRecord> appendProposal(
            ModelProposalRecord proposal) {
        return transactions.append("agent_v2_chain_model_proposals",
                ModelProposalRecord.class, proposal,
                Map.of("proposal_id", proposal.proposalId()));
    }

    @Override
    public AuthoritativeAppendResult<ProposalStateEventRecord>
            appendProposalState(
                    AuthoritativeFact<ProposalStateEventRecord> event) {
        ProposalStateEventRecord fact = event.fact();
        return transactions.appendAuthoritative(
                "agent_v2_chain_proposal_state_events",
                ProposalStateEventRecord.class, event,
                ordered("proposal_id", fact.proposalId(),
                        "state_sequence", fact.stateSequence()));
    }

    @Override
    public Optional<ModelInvocationRecord> findInvocation(String invocationId) {
        return transactions.find("agent_v2_chain_model_invocations",
                ModelInvocationRecord.class,
                Map.of("invocation_id", invocationId));
    }

    @Override
    public List<ModelInvocationRecord> findInvocations(
            String taskId, long invocationOrdinalCut) {
        return transactions.jdbc().queryForList("""
                        SELECT *
                          FROM agent_v2_chain_model_invocations
                         WHERE task_id = :taskId
                           AND invocation_ordinal <= :invocationOrdinalCut
                         ORDER BY invocation_ordinal
                        """, new MapSqlParameterSource()
                        .addValue("taskId", taskId)
                        .addValue("invocationOrdinalCut",
                                invocationOrdinalCut)).stream()
                .map(row -> transactions.codec().decode(
                        ModelInvocationRecord.class, row))
                .toList();
    }

    /** Exact product-side lineage lookup; intentionally not a core port. */
    public List<ModelInvocationRecord> findInvocationsByContextRevisionId(
            String taskId, String contextRevisionId) {
        return transactions.findAll("agent_v2_chain_model_invocations",
                ModelInvocationRecord.class,
                ordered("task_id", taskId, "context_revision_id",
                        contextRevisionId),
                "invocation_ordinal");
    }

    /** Exact task-local invocation watermark without a synthetic high cut. */
    @Override
    public long highestInvocationOrdinal(String taskId) {
        Long value = transactions.jdbc().queryForObject("""
                        SELECT COALESCE(MAX(invocation_ordinal), 0)
                          FROM agent_v2_chain_model_invocations
                         WHERE task_id = :taskId
                        """, new MapSqlParameterSource("taskId", taskId),
                Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public int highestProviderAttemptNo(String invocationId) {
        Integer value = transactions.jdbc().queryForObject("""
                        SELECT COALESCE(MAX(attempt_no), 0)
                          FROM agent_v2_chain_provider_attempts
                         WHERE invocation_id = :invocationId
                        """, new MapSqlParameterSource(
                                "invocationId", invocationId),
                Integer.class);
        return value == null ? 0 : value;
    }

    @Override
    public List<ProviderAttemptRecord> findProviderAttempts(
            String invocationId) {
        return transactions.findAll("agent_v2_chain_provider_attempts",
                ProviderAttemptRecord.class,
                Map.of("invocation_id", invocationId), "attempt_no");
    }

    @Override
    public List<ContentRecord> findContents(String invocationId) {
        return transactions.findAll("agent_v2_chain_contents",
                ContentRecord.class, Map.of("invocation_id", invocationId),
                "created_at, content_id").stream()
                .map(this::verifyContentDigest)
                .toList();
    }

    @Override
    public Optional<ContentRecord> findContent(String contentId) {
        return transactions.find("agent_v2_chain_contents",
                        ContentRecord.class, Map.of("content_id", contentId))
                .map(this::verifyContentDigest);
    }

    @Override
    public Optional<ModelProposalRecord> findProposal(String proposalId) {
        return transactions.find("agent_v2_chain_model_proposals",
                ModelProposalRecord.class, Map.of("proposal_id", proposalId));
    }

    @Override
    public Optional<ModelProposalRecord> findProposalByInvocation(
            String invocationId) {
        return transactions.find("agent_v2_chain_model_proposals",
                ModelProposalRecord.class,
                Map.of("invocation_id", invocationId));
    }

    @Override
    public List<ProposalStateEventRecord> findProposalStateEvents(
            String proposalId) {
        return transactions.findAll("agent_v2_chain_proposal_state_events",
                ProposalStateEventRecord.class,
                Map.of("proposal_id", proposalId), "state_sequence");
    }

    /** Exact product-side event lookup used by frozen selector successors. */
    public Optional<ProposalStateEventRecord> findProposalStateEvent(
            String eventId) {
        return transactions.find("agent_v2_chain_proposal_state_events",
                ProposalStateEventRecord.class, Map.of("event_id", eventId));
    }

    private ContentRecord verifyContentDigest(ContentRecord content) {
        if (!ProductChainRecordCodec.sha256(content.body())
                .equals(content.bodySha256())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONTENT_BODY_DIGEST_MISMATCH");
        }
        return content;
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
