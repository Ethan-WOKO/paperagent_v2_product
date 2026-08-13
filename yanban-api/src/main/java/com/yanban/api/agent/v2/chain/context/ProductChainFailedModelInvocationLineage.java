package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resolves only the deterministic same-Context lineage after model failure. */
final class ProductChainFailedModelInvocationLineage {
    private final ProductChainModelRepositoryAdapter models;

    ProductChainFailedModelInvocationLineage(
            ProductChainModelRepositoryAdapter models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    Decision decide(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord current,
            String rootInvocationId) {
        Failure failure = exhaustedFailure(taskId, current);
        if (failure == null) {
            return Decision.notFailed();
        }
        String retryId = retryIdentity(
                context.contextRevisionId(), current.invocationId(),
                failure.lastAttemptNo());
        var existingRetry = models.findInvocation(retryId).orElse(null);
        if (existingRetry != null) {
            return Decision.retry(retryId, existingRetry);
        }
        List<ChainPersistenceRecords.ModelInvocationRecord> prefix =
                invocationPrefix(taskId, context, current, rootInvocationId);
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                current.runtimePolicyVersion());
        return prefix.size()
                >= runtimePolicy.modelInvocationsPerContextTotal()
                ? Decision.atLimit()
                : Decision.retry(retryId, null);
    }

    private Failure exhaustedFailure(
            String taskId,
            ChainPersistenceRecords.ModelInvocationRecord invocation) {
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                invocation.runtimePolicyVersion());
        if (models.findProposalByInvocation(invocation.invocationId())
                .isPresent()) {
            return null;
        }
        int attemptHead = models.highestProviderAttemptNo(
                invocation.invocationId());
        List<ChainPersistenceRecords.ProviderAttemptRecord> attempts =
                models.findProviderAttempts(invocation.invocationId());
        if (attemptHead != attempts.size()
                || attemptHead > runtimePolicy.providerAttemptsTotal()) {
            throw blocked("provider attempt prefix is inconsistent");
        }
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (!validFailureAttempt(taskId, invocation, attempt, index + 1)) {
                throw blocked("provider attempt prefix is inconsistent");
            }
        }
        return attemptHead == runtimePolicy.providerAttemptsTotal()
                ? new Failure(attemptHead) : null;
    }

    private List<ChainPersistenceRecords.ModelInvocationRecord>
            invocationPrefix(
                    String taskId,
                    ChainPersistenceRecords.ContextRevisionRecord context,
                    ChainPersistenceRecords.ModelInvocationRecord current,
                    String rootInvocationId) {
        List<ChainPersistenceRecords.ModelInvocationRecord> values = models
                .findInvocationsByContextRevisionId(
                        taskId, context.contextRevisionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ModelInvocationRecord
                                ::invocationOrdinal))
                .toList();
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                current.runtimePolicyVersion());
        if (values.isEmpty()
                || values.size() > runtimePolicy
                .modelInvocationsPerContextTotal()
                || !values.get(values.size() - 1).invocationId()
                .equals(current.invocationId())) {
            throw blocked("Context invocation prefix is incomplete");
        }
        String expectedId = rootInvocationId;
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            verifyFrozenIdentity(taskId, context, current, value, expectedId);
            if (index + 1 < values.size()) {
                Failure predecessor = exhaustedFailure(taskId, value);
                if (predecessor == null) {
                    throw blocked(
                            "Context invocation retry lacks failed predecessor");
                }
                expectedId = retryIdentity(
                        context.contextRevisionId(), value.invocationId(),
                        predecessor.lastAttemptNo());
            }
        }
        return values;
    }

    private static boolean validFailureAttempt(
            String taskId,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ProviderAttemptRecord attempt,
            int expectedAttemptNo) {
        boolean validStatus = attempt.schemaValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.NOT_RUN
                && attempt.proposalValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.NOT_RUN
                || attempt.schemaValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.FAILED
                && attempt.proposalValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.NOT_RUN
                || attempt.schemaValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.PASSED
                && attempt.proposalValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.FAILED;
        return attempt.taskId().equals(taskId)
                && attempt.invocationId().equals(invocation.invocationId())
                && attempt.attemptNo() == expectedAttemptNo
                && attempt.errorCode() != null
                && !attempt.errorCode().isBlank()
                && validStatus;
    }

    private static void verifyFrozenIdentity(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord current,
            ChainPersistenceRecords.ModelInvocationRecord value,
            String expectedInvocationId) {
        if (!value.taskId().equals(taskId)
                || !value.invocationId().equals(expectedInvocationId)
                || !value.contextRevisionId().equals(
                context.contextRevisionId())
                || !value.completionToken().equals(current.completionToken())
                || value.role() != current.role()
                || value.workState() != current.workState()
                || !value.callReason().equals(current.callReason())
                || !value.provider().equals(current.provider())
                || !value.model().equals(current.model())
                || !value.runtimePolicyVersion().equals(
                current.runtimePolicyVersion())) {
            throw blocked("Context invocation retry identity changed");
        }
    }

    private static String retryIdentity(
            String contextRevisionId,
            String previousInvocationId,
            int lastAttemptNo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("invocation\0model-failure-retry\0"
                            + contextRevisionId + "\0"
                            + previousInvocationId + "\0"
                            + lastAttemptNo).getBytes(StandardCharsets.UTF_8));
            return "invocation." + java.util.HexFormat.of()
                    .formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                io.paperagent.v2.chain.ChainContextModule
                        .MODEL_INVOCATIONS_AND_PROPOSALS,
                reason);
    }

    record Decision(
            boolean failed,
            boolean capped,
            String retryInvocationId,
            ChainPersistenceRecords.ModelInvocationRecord existingRetry) {
        Decision {
            if (!failed && (capped || retryInvocationId != null
                    || existingRetry != null)
                    || capped && retryInvocationId != null
                    || existingRetry != null && !existingRetry.invocationId()
                    .equals(retryInvocationId)) {
                throw new IllegalArgumentException(
                        "model failure retry decision is inconsistent");
            }
        }

        static Decision notFailed() {
            return new Decision(false, false, null, null);
        }

        static Decision atLimit() {
            return new Decision(true, true, null, null);
        }

        static Decision retry(
                String retryInvocationId,
                ChainPersistenceRecords.ModelInvocationRecord existing) {
            return new Decision(true, false,
                    Objects.requireNonNull(
                            retryInvocationId, "retryInvocationId"),
                    existing);
        }
    }

    private record Failure(int lastAttemptNo) {
        private Failure {
            if (lastAttemptNo < 1) {
                throw new IllegalArgumentException(
                        "lastAttemptNo must be positive");
            }
        }
    }
}
