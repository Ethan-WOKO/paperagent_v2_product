package com.yanban.api.agent.v2.chain.model;

import io.paperagent.v2.chain.ChainContentWriter;
import io.paperagent.v2.chain.ChainModelInvocationWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProviderAttemptRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationStatus;
import io.paperagent.v2.chain.ChainProposalWriter;
import io.paperagent.v2.chain.model.ChainModelMaterializationPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Local-database atomic boundary for a successfully validated provider turn.
 *
 * <p>The raw provider response is intentionally absent from this API. The
 * runtime passes only the validated attempt, the optional single authoritative
 * body, and the immutable ref-only proposal. Existing granular writers join
 * this transaction; failed provider attempts continue to use their granular
 * writer directly.</p>
 */
public final class ProductChainModelMaterializationAdapter
        implements ChainModelMaterializationPort {
    private final ChainModelInvocationWriter invocations;
    private final ChainContentWriter contents;
    private final ChainProposalWriter proposals;
    private final TransactionTemplate transaction;

    public ProductChainModelMaterializationAdapter(
            ChainModelInvocationWriter invocations,
            ChainContentWriter contents,
            ChainProposalWriter proposals,
            PlatformTransactionManager transactions) {
        this.invocations = Objects.requireNonNull(
                invocations, "invocations");
        this.contents = Objects.requireNonNull(contents, "contents");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public SuccessfulMaterialization persistSuccessfulAttempt(
            ProviderAttemptRecord attempt,
            ContentRecord bodyContent,
            ModelProposalRecord proposal) {
        validate(attempt, bodyContent, proposal);
        SuccessfulMaterialization stored = transaction.execute(status -> {
            AppendResult<ProviderAttemptRecord> storedAttempt =
                    invocations.appendProviderAttempt(attempt);
            AppendResult<ContentRecord> storedContent = bodyContent == null
                    ? null : contents.appendContent(bodyContent);
            AppendResult<ModelProposalRecord> storedProposal =
                    proposals.appendProposal(proposal);
            boolean replayed = storedAttempt.replayed()
                    && (storedContent == null || storedContent.replayed())
                    && storedProposal.replayed();
            return new SuccessfulMaterialization(
                    storedAttempt.value(),
                    storedContent == null ? null : storedContent.value(),
                    storedProposal.value(), replayed);
        });
        return Objects.requireNonNull(stored, "materialization transaction");
    }

    private static void validate(
            ProviderAttemptRecord attempt,
            ContentRecord bodyContent,
            ModelProposalRecord proposal) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(proposal, "proposal");
        if (attempt.schemaValidationStatus() != ValidationStatus.PASSED
                || attempt.proposalValidationStatus()
                != ValidationStatus.PASSED
                || attempt.errorCode() != null) {
            throw new IllegalArgumentException(
                    "successful materialization requires one fully validated attempt");
        }
        if (!attempt.invocationId().equals(proposal.invocationId())
                || !attempt.taskId().equals(proposal.taskId())) {
            throw new IllegalArgumentException(
                    "attempt and proposal must belong to the same invocation and task");
        }
        verifyCanonicalJson(
                proposal.payload().json(), proposal.payload().sha256(),
                "proposal payload");
        verifyCanonicalJson(
                proposal.sourceRefs().json(), proposal.sourceRefs().sha256(),
                "proposal source refs");
        if (bodyContent == null) {
            if (proposal.bodyAuthorityType() != null
                    || proposal.bodyAuthorityRef() != null) {
                throw new IllegalArgumentException(
                        "proposal body authority requires the same materialization body");
            }
            return;
        }
        if (!attempt.invocationId().equals(bodyContent.invocationId())
                || !attempt.taskId().equals(bodyContent.taskId())) {
            throw new IllegalArgumentException(
                    "body content must belong to the materialized invocation and task");
        }
        if (!bodyContent.contentKind().name().equals(
                    proposal.bodyAuthorityType())
                || !bodyContent.contentId().equals(
                    proposal.bodyAuthorityRef())) {
            throw new IllegalArgumentException(
                    "proposal must reference exactly the materialized body authority");
        }
    }

    private static void verifyCanonicalJson(
            String json, String expectedDigest, String field) {
        if (!sha256(json).equals(expectedDigest)) {
            throw new IllegalArgumentException(field + " digest mismatch");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte element : digest) {
                output.append(String.format("%02x", element & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }
}
