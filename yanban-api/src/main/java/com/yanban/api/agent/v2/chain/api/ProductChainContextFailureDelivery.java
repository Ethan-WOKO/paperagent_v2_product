package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.context.ProductChainContextIdentity;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes the fixed terminal Delivery authority when Answer Context cannot build. */
@Component
public final class ProductChainContextFailureDelivery {
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFinalizationRepositoryAdapter finalization;
    private final PlatformTransactionManager transactions;

    public ProductChainContextFailureDelivery(
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFinalizationRepositoryAdapter finalization,
            PlatformTransactionManager transactions) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public FailedDelivery fail(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ContextBuildFailureRecord failure,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(now, "now");
        require(task.taskId().equals(context.taskId())
                        && task.taskId().equals(failure.taskId())
                        && task.sessionId() == instruction.sessionId()
                        && task.taskId().equals(instruction.originTaskId())
                        && context.role() == io.paperagent.v2.chain.ChainRole.ANSWER
                        && context.contextRevisionId().equals(
                                failure.contextRevisionId())
                        && context.instructionId().equals(
                                instruction.instructionId())
                        && context.instructionId().equals(
                                failure.instructionId())
                        && context.workState() == failure.workState()
                        && context.callReason().equals(failure.callReason())
                        && context.runtimePolicyVersion().equals(
                                failure.runtimePolicyVersion()),
                "CHAIN_CONTEXT_FAILURE_DELIVERY_SOURCE_INVALID");
        ChainRuntimePolicy.requireVersion(context.runtimePolicyVersion());
        Source source = source(task, instruction, context);
        Instant committedAt = now.truncatedTo(ChronoUnit.MICROS);
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(ignored -> append(task, context, failure,
                        source, committedAt)));
    }

    private FailedDelivery append(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ContextBuildFailureRecord failure,
            Source source,
            Instant now) {
        String identity = sha256(task.taskId() + "\0"
                + failure.contextBuildFailureId());
        String deliveryId = "delivery.context-failure." + identity;
        String deliveryEventId = "delivery.context-failure.event." + identity;
        var delivery = new ChainPersistenceRecords.DeliveryRecord(
                deliveryId, task.taskId(), deliveryEventId,
                source.sourceCommandId(), source.routeDecisionId(),
                source.taskOutcomeId(), source.gapId(), null,
                null, null, now);
        var deliveryFact = authoritative(
                delivery.eventId(), task.taskId(), "DELIVERY",
                        sha256(source.type() + "\0" + source.ref()
                                + "\0" + failure.contextBuildFailureId()),
                        delivery, now);
        var appendedDelivery = finalization.appendDelivery(deliveryFact);
        var storedDelivery = appendedDelivery.fact();
        require(sameRecordIgnoringAudit(storedDelivery, delivery),
                "CHAIN_CONTEXT_FAILURE_DELIVERY_REPLAY_INVALID");
        requireEvent(deliveryFact.event(), appendedDelivery.event(),
                storedDelivery.createdAt());

        var failed = new ChainPersistenceRecords.DeliveryEventRecord(
                deliveryId, 1L, task.taskId(),
                "delivery.context-failure.failed." + identity,
                ChainDeliveryStatus.DELIVERY_FAILED, 1,
                failure.errorCode(), context.runtimePolicyVersion(), now);
        var failedFact = authoritative(
                failed.eventId(), task.taskId(),
                        "DELIVERY_DELIVERY_FAILED",
                        sha256(deliveryId + "\0DELIVERY_FAILED\0"
                                + failure.contextBuildFailureId()),
                        failed, now);
        var appendedFailed = finalization.appendDeliveryEvent(failedFact);
        var storedFailed = appendedFailed.fact();
        require(sameRecordIgnoringAudit(storedFailed, failed),
                "CHAIN_CONTEXT_FAILURE_DELIVERY_FAILED_REPLAY_INVALID");
        requireEvent(failedFact.event(), appendedFailed.event(),
                storedFailed.committedAt());
        return new FailedDelivery(storedDelivery, storedFailed);
    }

    private Source source(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        if (context.workState() == ChainWorkState.DIRECT_ANSWERING
                && "DIRECT_ROUTE".equals(context.callReason())) {
            var routes = workflow.findRouteDecisions(task.taskId()).stream()
                    .filter(value -> value.route()
                            == ChainExecutionMode.DIRECT)
                    .filter(value -> value.instructionId().equals(
                            instruction.instructionId()))
                    .filter(value -> ProductChainAnswerDeliveryProgression
                            .directContextId(task.taskId(),
                                    value.routeDecisionId())
                            .equals(context.contextRevisionId()))
                    .toList();
            return mapRoute(exactlyOne(
                    routes, "CHAIN_CONTEXT_FAILURE_ROUTE_INVALID"),
                    instruction.commandId());
        }
        if ("PENDING_ITEM".equals(context.callReason())) {
            var gaps = workflow.findPendingItems(task.taskId()).stream()
                    .filter(value -> value.pendingType()
                            == ChainPendingItemType.PERMISSION
                            ? context.workState()
                            == ChainWorkState.WAITING_PERMISSION
                            : context.workState() == ChainWorkState.WAITING_USER)
                    .filter(value -> ProductChainContextIdentity
                            .pendingItemAnswer(task.taskId(), value.gapId(),
                                    context.workState().name())
                            .equals(context.contextRevisionId()))
                    .toList();
            return Source.gap(exactlyOne(gaps,
                    "CHAIN_CONTEXT_FAILURE_GAP_INVALID").gapId(),
                    instruction.commandId());
        }
        if ("TASK_OUTCOME".equals(context.callReason())) {
            var outcome = finalization.findTaskOutcome(task.taskId())
                    .filter(value -> ProductChainContextIdentity
                            .taskOutcomeAnswer(task.taskId(), value.outcomeId())
                            .equals(context.contextRevisionId()))
                    .orElseThrow(() -> failure(
                            "CHAIN_CONTEXT_FAILURE_OUTCOME_INVALID"));
            return Source.outcome(
                    outcome.outcomeId(), instruction.commandId());
        }
        throw failure("CHAIN_CONTEXT_FAILURE_ANSWER_SOURCE_INVALID");
    }

    private static <T> T exactlyOne(List<T> values, String code) {
        if (values.size() != 1) throw failure(code);
        return values.get(0);
    }

    private static <T extends ChainPersistenceRecords.TaskAuthorityFact>
            ChainPersistenceRecords.AuthoritativeFact<T> authoritative(
                    String eventId, String taskId, String eventType,
                    String digest, T fact, Instant now) {
        return new ChainPersistenceRecords.AuthoritativeFact<>(
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, taskId, eventType, null, digest, now), fact);
    }

    public record FailedDelivery(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainPersistenceRecords.DeliveryEventRecord failed) {
    }

    private record Source(
            String type, String ref, String sourceCommandId,
            String routeDecisionId,
            String taskOutcomeId, String gapId) {
        static Source route(String ref, String sourceCommandId) {
            return new Source("ROUTE", ref, sourceCommandId,
                    ref, null, null);
        }

        static Source outcome(String ref, String sourceCommandId) {
            return new Source("TASK_OUTCOME", ref, sourceCommandId,
                    null, ref, null);
        }

        static Source gap(String ref, String sourceCommandId) {
            return new Source("GAP", ref, sourceCommandId,
                    null, null, ref);
        }
    }

    private static Source mapRoute(
            ChainPersistenceRecords.RouteDecisionRecord value,
            String sourceCommandId) {
        return Source.route(value.routeDecisionId(), sourceCommandId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean sameRecordIgnoringAudit(
            Record left, Record right) {
        if (!left.getClass().equals(right.getClass())) return false;
        try {
            for (var component : left.getClass().getRecordComponents()) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    continue;
                }
                if (!Objects.equals(component.getAccessor().invoke(left),
                        component.getAccessor().invoke(right))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireEvent(
            ChainPersistenceRecords.AuthorityEventRequest requested,
            ChainPersistenceRecords.AuthorityEventRecord stored,
            Instant factTime) {
        require(stored.eventId().equals(requested.eventId())
                        && stored.taskId().equals(requested.taskId())
                        && stored.eventType().equals(requested.eventType())
                        && Objects.equals(stored.transitionId(),
                                requested.transitionId())
                        && stored.sourceIdentitySha256().equals(
                                requested.sourceIdentitySha256())
                        && stored.committedAt().equals(factTime),
                "CHAIN_CONTEXT_FAILURE_DELIVERY_EVENT_REPLAY_INVALID");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw failure(code);
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
