package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Continues an exact, already-created mechanical authority. */
@Component
public final class ProductChainMechanicalProgression
        implements ProductChainTaskProgressionAdapter.MechanicalProgression {
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainFinalizationRepositoryAdapter finalization;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainAnswerDeliveryProgression answer;
    private final ProductChainModelFailureProgression modelFailures;
    private final ProductChainContextBuildFailureSuccessor contextFailures;
    private final ProjectChainPlannerProgression planner;
    private final Clock clock;

    @Autowired
    public ProductChainMechanicalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainModelFailureProgression modelFailures,
            ProductChainContextBuildFailureSuccessor contextFailures,
            ProjectChainPlannerProgression planner) {
        this(foundations, finalization, workflow, answer, modelFailures,
                contextFailures, planner, Clock.systemUTC());
    }

    ProductChainMechanicalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainAnswerDeliveryProgression answer,
            Clock clock) {
        this(foundations, finalization, null, answer, null, null, null,
                clock);
    }

    ProductChainMechanicalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainAnswerDeliveryProgression answer,
            Clock clock) {
        this(foundations, finalization, workflow, answer, null, null, null,
                clock);
    }

    ProductChainMechanicalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainModelFailureProgression modelFailures,
            Clock clock) {
        this(foundations, finalization, workflow, answer, modelFailures,
                null, null, clock);
    }

    ProductChainMechanicalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainModelFailureProgression modelFailures,
            ProductChainContextBuildFailureSuccessor contextFailures,
            ProjectChainPlannerProgression planner,
            Clock clock) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.workflow = workflow;
        this.answer = Objects.requireNonNull(answer, "answer");
        this.modelFailures = modelFailures;
        this.contextFailures = contextFailures;
        this.planner = planner;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProductChainTaskProgressionAdapter.ActionReceipt advance(
            ProductChainTaskProgressionAdapter.MechanicalCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.selection() instanceof ProductChainNextRoleSelector
                .MechanicalDirectPlannerDelivery selected) {
            if (planner == null) {
                throw failure("CHAIN_DIRECT_PLANNER_DELIVERY_OWNER_MISSING");
            }
            var task = foundations.findTask(command.taskId()).orElseThrow(
                    () -> failure("CHAIN_DIRECT_PLANNER_TASK_MISSING"));
            planner.deliverAcceptedDirect(task, currentInstruction(task),
                    selected.routeDecisionId(), clock.instant());
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction
                            .mechanical("DIRECT_PLANNER_ROUTE",
                                    selected.routeDecisionId()));
        }
        if (command.selection() instanceof ProductChainNextRoleSelector
                .MechanicalPermission permission) {
            return advancePermission(command, permission);
        }
        if (command.selection() instanceof ProductChainNextRoleSelector
                .MechanicalModelFailure failure) {
            if (modelFailures == null) {
                throw failure("CHAIN_MODEL_FAILURE_OWNER_MISSING");
            }
            var task = foundations.findTask(command.taskId()).orElseThrow(
                    () -> failure("CHAIN_MODEL_FAILURE_TASK_MISSING"));
            var instruction = currentInstruction(task);
            modelFailures.advance(task, instruction, failure,
                    clock.instant());
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction
                            .mechanical("MODEL_CALL_FAILED",
                                    failure.invocationId()));
        }
        if (command.selection() instanceof ProductChainNextRoleSelector
                .MechanicalContextFailure failure) {
            if (contextFailures == null) {
                throw failure("CHAIN_CONTEXT_FAILURE_OWNER_MISSING");
            }
            var task = foundations.findTask(command.taskId()).orElseThrow(
                    () -> failure("CHAIN_CONTEXT_FAILURE_TASK_MISSING"));
            contextFailures.advance(task, currentInstruction(task),
                    failure.contextBuildFailureId(), clock.instant());
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction
                            .mechanical("CONTEXT_BUILD_FAILURE",
                                    failure.contextBuildFailureId()));
        }
        if (!(command.selection() instanceof ProductChainNextRoleSelector
                .MechanicalDelivery selected)) {
            throw failure("CHAIN_MECHANICAL_DELIVERY_SELECTION_REQUIRED");
        }
        ChainPersistenceRecords.DeliveryRecord delivery = exactDelivery(
                command.taskId(), selected.deliveryId());
        long authoritySequence = authoritySequence(
                command.taskId(), delivery.eventId());
        if (authoritySequence != selected.authoritySequence()
                || authoritySequence > command.snapshot().roleProjection()
                        .authorityCut()) {
            throw failure("CHAIN_MECHANICAL_DELIVERY_SELECTION_STALE");
        }
        var attempted = answer.retryDelivery(
                command.taskId(), delivery.deliveryId(), clock.instant());
        if (!attempted.delivery().equals(delivery)) {
            throw failure("CHAIN_MECHANICAL_DELIVERY_IDENTITY_CHANGED");
        }
        return new ProductChainTaskProgressionAdapter.ActionReceipt(
                ProductChainTaskProgressionAdapter.SelectedAction.mechanical(
                        "DELIVERY", delivery.deliveryId()));
    }

    private ProductChainTaskProgressionAdapter.ActionReceipt advancePermission(
            ProductChainTaskProgressionAdapter.MechanicalCommand command,
            ProductChainNextRoleSelector.MechanicalPermission selected) {
        if (workflow == null) {
            throw failure("CHAIN_PERMISSION_DECISION_OWNER_MISSING");
        }
        List<ChainPersistenceRecords.PermissionDecisionRecord> matches =
                workflow.findPermissionDecisions(command.taskId()).stream()
                        .filter(value -> value.permissionDecisionId().equals(
                                selected.permissionDecisionId()))
                        .toList();
        if (matches.size() != 1) {
            throw failure("CHAIN_PERMISSION_DECISION_IDENTITY_INVALID");
        }
        var decision = matches.get(0);
        long sequence = authoritySequence(command.taskId(), decision.eventId(),
                "PERMISSION_DECISION");
        if (sequence != selected.authoritySequence()
                || sequence > command.snapshot().roleProjection()
                        .authorityCut()) {
            throw failure("CHAIN_PERMISSION_DECISION_SELECTION_STALE");
        }
        ChainPendingItemRuntime runtime = new ChainPendingItemRuntime(
                workflow, foundations, workflow,
                ignored -> { throw failure("CHAIN_PERMISSION_OPEN_SOURCE_UNUSED"); },
                ignored -> { throw failure("CHAIN_PERMISSION_MODEL_SOURCE_FORBIDDEN"); },
                new ChainPendingItemRuntime.NormalSuccessorPort() {
                    @Override
                    public ChainPendingItemRuntime.OfficialSuccessor commit(
                            ChainPendingItemRuntime.NormalSuccessorRequest request) {
                        throw failure("CHAIN_PERMISSION_SUCCESSOR_UNUSED");
                    }

                    @Override
                    public java.util.Optional<ChainPendingItemRuntime
                            .OfficialSuccessor> findCommitted(
                            String taskId, String transitionId) {
                        return java.util.Optional.empty();
                    }
                },
                new ChainPendingItemRuntime.PermissionDecisionSource() {
                    @Override
                    public java.util.Optional<ChainPersistenceRecords
                            .PermissionDecisionRecord> find(
                            String taskId, String gapId, String decisionId) {
                        return taskId.equals(command.taskId())
                                && gapId.equals(decision.gapId())
                                && decisionId.equals(
                                decision.permissionDecisionId())
                                ? java.util.Optional.of(decision)
                                : java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<ChainPersistenceRecords
                            .PermissionDecisionRecord> findLatest(
                            String taskId, String gapId) {
                        return taskId.equals(command.taskId())
                                && gapId.equals(decision.gapId())
                                ? java.util.Optional.of(decision)
                                : java.util.Optional.empty();
                    }
                },
                (taskId, proposalId, authorityType, authorityRef) -> {
                    throw failure("CHAIN_PERMISSION_PROPOSAL_BIND_FORBIDDEN");
                });
        runtime.applyPermissionDecision(
                new ChainPendingItemRuntime.PermissionRequest(
                        command.taskId(), decision.gapId(),
                        "pending.permission." + sha256(
                                decision.permissionDecisionId()),
                        decision.permissionDecisionId(), clock.instant()));
        return new ProductChainTaskProgressionAdapter.ActionReceipt(
                ProductChainTaskProgressionAdapter.SelectedAction.mechanical(
                        "PERMISSION_DECISION",
                        decision.permissionDecisionId()));
    }

    private ChainPersistenceRecords.DeliveryRecord exactDelivery(
            String taskId, String deliveryId) {
        List<ChainPersistenceRecords.DeliveryRecord> matches = finalization
                .findDeliveries(taskId).stream()
                .filter(value -> value.deliveryId().equals(deliveryId))
                .toList();
        if (matches.size() != 1
                || !matches.get(0).taskId().equals(taskId)) {
            throw failure("CHAIN_MECHANICAL_DELIVERY_IDENTITY_INVALID");
        }
        return matches.get(0);
    }

    private ChainPersistenceRecords.InstructionRecord currentInstruction(
            ChainPersistenceRecords.TaskRecord task) {
        var bindings = foundations.findTaskInstructions(
                        task.taskId(), task.nextEventSequence()).stream()
                .sorted(java.util.Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord
                                ::taskInstructionSequence)).toList();
        if (bindings.isEmpty()) {
            throw failure("CHAIN_MODEL_FAILURE_INSTRUCTION_MISSING");
        }
        var binding = bindings.get(bindings.size() - 1);
        return foundations.findInstruction(binding.instructionId())
                .filter(value -> value.sessionId() == task.sessionId())
                .orElseThrow(() -> failure(
                        "CHAIN_MODEL_FAILURE_INSTRUCTION_INVALID"));
    }

    private long authoritySequence(String taskId, String eventId) {
        return authoritySequence(taskId, eventId, "DELIVERY");
    }

    private long authoritySequence(
            String taskId, String eventId, String eventType) {
        long cut = foundations.highestAuthorityEventSequence(taskId);
        List<ChainPersistenceRecords.AuthorityEventRecord> matches =
                foundations.findAuthorityEvents(taskId, cut).stream()
                        .filter(value -> value.eventId().equals(eventId))
                        .filter(value -> eventType.equals(value.eventType()))
                        .toList();
        if (matches.size() != 1) {
            throw failure("CHAIN_MECHANICAL_AUTHORITY_INVALID");
        }
        return matches.get(0).eventSequence();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
