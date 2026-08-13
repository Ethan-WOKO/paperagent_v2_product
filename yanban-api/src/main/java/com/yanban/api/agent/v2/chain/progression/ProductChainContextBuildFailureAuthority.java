package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Exact read side for one formal ContextBuildFailure authority. */
@Component
final class ProductChainContextBuildFailureAuthority {
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainFoundationRepositoryAdapter foundations;

    ProductChainContextBuildFailureAuthority(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainFoundationRepositoryAdapter foundations) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
    }

    Source read(String taskId, String failureId) {
        var failure = contexts.findContextBuildFailureById(failureId)
                .filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> failure(
                        "CHAIN_CONTEXT_BUILD_FAILURE_MISSING"));
        var context = contexts.findContextRevision(
                        failure.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_CONTEXT_BUILD_FAILURE_CONTEXT_MISSING"));
        verifyBinding(taskId, context, failure);
        long cut = foundations.highestAuthorityEventSequence(taskId);
        var events = foundations.findAuthorityEvents(taskId, cut).stream()
                .filter(value -> value.eventId().equals(failure.eventId()))
                .toList();
        String frozenIdentity = context.contextRevisionId() + "\0"
                + context.taskId() + "\0" + context.role().name() + "\0"
                + context.workState().name() + "\0" + context.callReason()
                + "\0" + context.instructionId() + "\0"
                + failure.failedModule().wireName() + "\0"
                + failure.errorCode() + "\0"
                + context.projectorSetVersion() + "\0"
                + context.paginationVersion() + "\0"
                + context.runtimePolicyVersion();
        var event = events.size() == 1 ? events.get(0) : null;
        if (event == null
                || !"CONTEXT_BUILD_FAILURE".equals(event.eventType())
                || event.transitionId() != null
                || event.eventSequence() > cut
                || !event.committedAt().equals(failure.createdAt())
                || !event.sourceIdentitySha256().equals(sha256(
                        frozenIdentity + "\0"
                                + failure.contextBuildFailureId()))) {
            throw failure("CHAIN_CONTEXT_BUILD_FAILURE_EVENT_INVALID");
        }
        boolean successor = contexts.findContextRevisions(taskId).stream()
                .anyMatch(value -> failure.contextRevisionId().equals(
                        value.parentContextRevisionId()));
        return new Source(context, failure,
                event.eventSequence(), successor);
    }

    private static void verifyBinding(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ContextBuildFailureRecord failure) {
        if (!taskId.equals(context.taskId())
                || context.status() != ChainContextRevisionStatus.BUILDING
                || !context.contextRevisionId().equals(
                        failure.contextRevisionId())
                || context.role() != failure.role()
                || context.workState() != failure.workState()
                || !context.callReason().equals(failure.callReason())
                || !context.instructionId().equals(failure.instructionId())
                || !context.projectorSetVersion().equals(
                        failure.projectorSetVersion())
                || !context.paginationVersion().equals(
                        failure.paginationVersion())
                || !context.runtimePolicyVersion().equals(
                        failure.runtimePolicyVersion())
                || !"CONTEXT_INPUT_BLOCKED".equals(failure.errorCode())) {
            throw failure("CHAIN_CONTEXT_BUILD_FAILURE_SOURCE_INVALID");
        }
        ChainRuntimePolicy.requireVersion(failure.runtimePolicyVersion());
    }

    record Source(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ContextBuildFailureRecord failure,
            long authoritySequence,
            boolean successorContextPresent) {
        Source {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(failure, "failure");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
