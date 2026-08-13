package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.progression.ProductChainContextBuildFailureAuthority.Source;
import com.yanban.api.agent.v2.chain.api.ProductChainContextFailureDelivery;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import io.paperagent.v2.chain.step.ChainAbnormalSuccessorPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Maps one exact ContextBuildFailure to its role-owned formal successor. */
@Component
public final class ProductChainContextBuildFailureSuccessor {
    private final SourcePort sources;
    private final OutcomePort outcomes;
    private final DeliveryPort deliveries;
    private final ChainAbnormalSuccessorPolicy policy;

    @Autowired
    public ProductChainContextBuildFailureSuccessor(
            ProductChainContextBuildFailureAuthority authority,
            ProductChainCompletedOutcomeAdapter outcomes,
            ProductChainContextFailureDelivery deliveries) {
        this.sources = Objects.requireNonNull(authority, "authority")::read;
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes")::commit;
        this.deliveries = Objects.requireNonNull(deliveries,
                "deliveries")::fail;
        this.policy = null;
    }

    ProductChainContextBuildFailureSuccessor(
            SourcePort sources,
            OutcomePort outcomes,
            DeliveryPort deliveries,
            ChainAbnormalSuccessorPolicy policy) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Result advance(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String contextBuildFailureId,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        now = Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS);
        Source source = Objects.requireNonNull(sources.read(
                task.taskId(), required(contextBuildFailureId,
                        "contextBuildFailureId")), "Context failure source");
        verifyCommandIdentity(task, instruction, source);
        if (source.successorContextPresent()) {
            return new SuccessorAlreadyPresent(source.failure()
                    .contextBuildFailureId());
        }
        ChainAbnormalSuccessorPolicy selectedPolicy = policy != null
                ? policy : new ChainAbnormalSuccessorPolicy(
                ChainRuntimePolicy.requireVersion(
                        source.failure().runtimePolicyVersion()));
        return switch (selectedPolicy.contextBuildFailed(
                source.failure().role())) {
            case FAIL_TASK_THEN_ANSWER -> failTask(
                    task, instruction, source, now);
            case BLOCK_STEP_THEN_REFLECTOR -> stepBlock(source);
            case DELIVERY_FAILED -> new DeliveryFailed(deliveries.fail(
                    task, instruction, source.context(), source.failure(), now));
            default -> throw failure(
                    "CHAIN_CONTEXT_BUILD_FAILURE_SUCCESSOR_INVALID");
        };
    }

    private TaskFailed failTask(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            Source source,
            Instant now) {
        var context = source.context();
        var failure = source.failure();
        var empty = canonicalArray();
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        task.taskId(), "context-failure-outcome." + sha256(
                        task.taskId() + "\0"
                                + failure.contextBuildFailureId()),
                        instruction.commandId(), instruction.instructionId(),
                        context.taskFrameId(), context.planId(),
                        context.planRevisionId(), empty, empty,
                        context.candidateArtifactId(), Objects.toString(
                        context.candidateFingerprint(), ChainIdentity.NONE),
                        Objects.toString(context.validationId(),
                                ChainIdentity.NONE),
                        null, null, null, null,
                        empty, canonicalArray("CONTEXT_INPUT_BLOCKED"),
                        empty, now);
        ChainTaskOutcomeRuntime.Failed command =
                new ChainTaskOutcomeRuntime.Failed(
                        draft, failure.contextBuildFailureId(),
                        "CONTEXT", failure.errorCode());
        ChainTaskOutcomeRuntime.CommitResult committed = outcomes.commit(
                command, new FailureVerifier(task, instruction, source));
        return new TaskFailed(committed.outcome());
    }

    private static StepBlockRequired stepBlock(Source source) {
        var context = source.context();
        if (context.taskFrameId() == null || context.planId() == null
                || context.planRevisionId() == null
                || context.planRevisionNumber() == null
                || context.stepId() == null
                || context.activationEventId() == null) {
            throw failure("CHAIN_CONTEXT_EXECUTOR_STEP_IDENTITY_MISSING");
        }
        return new StepBlockRequired(
                source.failure().contextBuildFailureId(),
                context.taskFrameId(), context.planId(),
                context.planRevisionId(), context.planRevisionNumber(),
                context.stepId(), context.activationEventId());
    }

    private static void verifyCommandIdentity(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            Source source) {
        if (!task.taskId().equals(source.failure().taskId())
                || task.sessionId() != instruction.sessionId()
                || !task.taskId().equals(instruction.originTaskId())
                || !instruction.instructionId().equals(
                        source.failure().instructionId())) {
            throw failure("CHAIN_CONTEXT_BUILD_FAILURE_COMMAND_INVALID");
        }
    }

    private final class FailureVerifier
            implements ChainTaskOutcomeRuntime.FormalSourceVerifier {
        private final ChainPersistenceRecords.TaskRecord task;
        private final ChainPersistenceRecords.InstructionRecord instruction;
        private final Source expected;

        private FailureVerifier(
                ChainPersistenceRecords.TaskRecord task,
                ChainPersistenceRecords.InstructionRecord instruction,
                Source expected) {
            this.task = task;
            this.instruction = instruction;
            this.expected = expected;
        }

        @Override
        public void verifyCompleted(ChainTaskOutcomeRuntime.Completed ignored) {
            throw failure("CHAIN_CONTEXT_FAILURE_OUTCOME_KIND_INVALID");
        }

        @Override
        public void verifyFailed(ChainTaskOutcomeRuntime.Failed value) {
            Source exact = sources.read(task.taskId(),
                    expected.failure().contextBuildFailureId());
            verifyCommandIdentity(task, instruction, exact);
            if (!exact.equals(expected)
                    || !value.draft().taskId().equals(task.taskId())
                    || !value.formalFailureSourceId().equals(
                            expected.failure().contextBuildFailureId())
                    || !"CONTEXT".equals(value.failureCategory())
                    || !"CONTEXT_INPUT_BLOCKED".equals(
                            value.failureCode())) {
                throw failure("CHAIN_CONTEXT_FAILURE_OUTCOME_SOURCE_INVALID");
            }
        }

        @Override
        public void verifyCancelled(ChainTaskOutcomeRuntime.Cancelled ignored) {
            throw failure("CHAIN_CONTEXT_FAILURE_OUTCOME_KIND_INVALID");
        }

        @Override
        public void verifySuperseded(
                ChainTaskOutcomeRuntime.Superseded ignored) {
            throw failure("CHAIN_CONTEXT_FAILURE_OUTCOME_KIND_INVALID");
        }
    }

    public sealed interface Result permits TaskFailed, StepBlockRequired,
            DeliveryFailed, SuccessorAlreadyPresent {
    }

    public record TaskFailed(
            ChainPersistenceRecords.TaskOutcomeRecord outcome)
            implements Result {
        public TaskFailed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record StepBlockRequired(
            String contextBuildFailureId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            long planRevisionNumber,
            String stepId,
            String activationEventId) implements Result {
        public StepBlockRequired {
            required(contextBuildFailureId, "contextBuildFailureId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            if (planRevisionNumber < 1) {
                throw new IllegalArgumentException(
                        "planRevisionNumber must be positive");
            }
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
        }

        public ChainRecoveryRuntime.NextDirective reflectorDirective() {
            return new ChainRecoveryRuntime.NextDirective(
                    ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                    "CONTEXT_BUILD_FAILURE", contextBuildFailureId);
        }
    }

    public record DeliveryFailed(
            ProductChainContextFailureDelivery.FailedDelivery delivery)
            implements Result {
        public DeliveryFailed {
            Objects.requireNonNull(delivery, "delivery");
        }
    }

    public record SuccessorAlreadyPresent(String contextBuildFailureId)
            implements Result {
        public SuccessorAlreadyPresent {
            required(contextBuildFailureId, "contextBuildFailureId");
        }
    }

    @FunctionalInterface
    interface SourcePort {
        ProductChainContextBuildFailureAuthority.Source read(
                String taskId, String contextBuildFailureId);
    }

    @FunctionalInterface
    interface OutcomePort {
        ChainTaskOutcomeRuntime.CommitResult commit(
                ChainTaskOutcomeRuntime.OutcomeCommand command,
                ChainTaskOutcomeRuntime.FormalSourceVerifier verifier);
    }

    @FunctionalInterface
    interface DeliveryPort {
        ProductChainContextFailureDelivery.FailedDelivery fail(
                ChainPersistenceRecords.TaskRecord task,
                ChainPersistenceRecords.InstructionRecord instruction,
                ChainPersistenceRecords.ContextRevisionRecord context,
                ChainPersistenceRecords.ContextBuildFailureRecord failure,
                Instant now);
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(
            String... values) {
        String json = values.length == 0 ? "[]"
                : "[\"" + String.join("\",\"", values) + "\"]";
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
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

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
