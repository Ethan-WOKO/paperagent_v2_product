package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.context.ProductChainExecutorActionContextProjection;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.step.ChainExecutorRepairService;
import io.paperagent.v2.chain.step.ChainActionProgressIdentity;
import io.paperagent.v2.chain.step.ChainProgressAuthorityPort;
import io.paperagent.v2.chain.step.ChainProgressPolicy;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies the versioned action-repair policy and owns its formal Step block. */
@Component
public final class ProductChainActionFailureProgression {
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final EffectOutcomeRepository outcomes;
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainCandidateMaterializationFailureRepositoryAdapter
            candidateFailures;
    private final PlatformTransactionManager transactions;

    public ProductChainActionFailureProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectOutcomeRepository outcomes,
            NamedParameterJdbcTemplate jdbc,
            ProductChainModelRepositoryAdapter models,
            ProductChainCandidateMaterializationFailureRepositoryAdapter
                    candidateFailures,
            PlatformTransactionManager transactions) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.models = Objects.requireNonNull(models, "models");
        this.candidateFailures = Objects.requireNonNull(
                candidateFailures, "candidateFailures");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public Decision decide(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.PlanBindingRecord plan,
            ChainStepAuthorityPort.StepEvent activation,
            ChainModelProtocolOutcome.ProposalReady ready,
            ProductChainExecutorActionContextProjection.Failure failure,
            String forcedReason,
            Instant observedAt) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(failure, "failure");
        Instant now = Objects.requireNonNull(observedAt, "observedAt")
                .truncatedTo(ChronoUnit.MICROS);
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(ignored -> decideLocked(
                        task, plan, activation, ready, failure,
                        forcedReason, now)));
    }

    private Decision decideLocked(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.PlanBindingRecord plan,
            ChainStepAuthorityPort.StepEvent activation,
            ChainModelProtocolOutcome.ProposalReady ready,
            ProductChainExecutorActionContextProjection.Failure failure,
            String forcedReason,
            Instant now) {
        String taskId = task.taskId();
        ChainRuntimePolicy runtimePolicy = runtimePolicy(taskId, ready);
        lockTask(taskId);
        assertCurrentPlan(taskId, plan);
        String stepId = activation.command().stepId();
        String activationId = activation.command().activationEventId();
        Decision replay = replayCommittedBlock(
                taskId, plan, activation, ready, failure);
        if (replay != null) {
            return replay;
        }
        ProgressEvidence progress = progress(taskId, stepId, activationId);
        String proposedSignature = ready.proposal().payload().sha256();
        ChainExecutorRepairService.RepairDecision policyDecision;
        if (failure.isReceiptFailure()) {
            policyDecision = new ChainExecutorRepairService(
                    runtimePolicy, workflow,
                    (ignoredTask, ignoredStep, ignoredActivation) ->
                            progress.snapshot())
                    .decide(new ChainExecutorRepairService.RepairRequest(
                            taskId, stepId, activationId,
                            proposedSignature, false,
                            failure.actionId(), failure.errorRef(),
                            proposedSignature));
        } else {
            var assessment = new ChainProgressPolicy(runtimePolicy)
                    .assess(progress.snapshot().markers());
            policyDecision = assessment.decision()
                    == ChainProgressPolicy.ProgressDecision.BLOCK_FOR_REFLECTOR
                    ? new ChainExecutorRepairService.RepairDecision(
                    ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR,
                    failure.actionId(), "NO_PROGRESS_THRESHOLD_REACHED")
                    : new ChainExecutorRepairService.RepairDecision(
                    ChainExecutorRepairService.RepairNext.CALL_EXECUTOR_REPAIR,
                    failure.actionId(), "CANDIDATE_MATERIALIZATION_FAILED");
        }
        String reason = forcedReason != null
                ? forcedReason
                : policyDecision.next()
                == ChainExecutorRepairService.RepairNext.BLOCK_FOR_REFLECTOR
                ? policyDecision.reasonCode() : null;
        if (reason == null) {
            return Decision.continueRepair();
        }
        if (!("NO_PROGRESS_THRESHOLD_REACHED".equals(reason)
                || "REPEATED_ACTION_SIGNATURE".equals(reason)
                || "REPAIR_DID_NOT_CHANGE_ACTION".equals(reason))) {
            throw failure("CHAIN_ACTION_FAILURE_BLOCK_REASON_INVALID");
        }
        ChainPersistenceRecords.ActionBindingRecord failedAction = workflow
                .findActionBindings(taskId).stream()
                .filter(value -> value.actionId().equals(failure.actionId()))
                .reduce((left, right) -> {
                    throw failure("CHAIN_ACTION_FAILURE_ACTION_AMBIGUOUS");
                }).orElseThrow(() -> failure(
                        "CHAIN_ACTION_FAILURE_ACTION_MISSING"));
        String authorityType;
        String authorityRef;
        String receiptId = null;
        String receiptPayloadSha256 = null;
        String receiptStatus = null;
        String failureCategory;
        String failureCode;
        if (failure.isReceiptFailure()) {
            PersistedEffectResult result = persistedResult(
                    failedAction.actionId());
            var receipt = result.receipt();
            receiptId = receipt.id().value();
            receiptPayloadSha256 = receiptPayloadSha256(
                    failedAction.actionId(), receiptId);
            if (!receiptId.equals(failure.errorRef())
                    || receipt.status() == ReceiptStatus.SUCCESS
                    || receipt.status() != failure.receiptStatus()
                    || !receipt.resultCode().orElseThrow().equals(
                    failure.failureCode())) {
                throw failure("CHAIN_ACTION_FAILURE_RECEIPT_CUT_INVALID");
            }
            authorityType = "RECEIPT";
            authorityRef = receiptId;
            receiptStatus = receipt.status().name();
            failureCategory = "EXECUTION";
            failureCode = receipt.resultCode().orElseThrow();
        } else {
            var candidateFailure = exactCandidateFailure(
                    taskId, failedAction.actionId(), failure.errorRef());
            if (!candidateFailure.errorCode().equals(failure.failureCode())
                    || !candidateFailure.workspaceId().equals(
                    failedAction.workspaceId())
                    || !candidateFailure.baseCandidateKey().equals(
                    failedAction.baseCandidateKey())
                    || !candidateFailure.versionFenceSha256().equals(
                    failedAction.versionFenceSha256())) {
                throw failure("CHAIN_ACTION_FAILURE_CANDIDATE_CUT_INVALID");
            }
            authorityType = "CANDIDATE_MATERIALIZATION_FAILURE";
            authorityRef = candidateFailure.candidateFailureId();
            failureCategory = "CANDIDATE";
            failureCode = candidateFailure.errorCode();
        }
        if (!sameCut(plan, activation, failedAction)) {
            throw failure("CHAIN_ACTION_FAILURE_SOURCE_CUT_INVALID");
        }
        int observed = observations(taskId, stepId, activationId,
                reason, proposedSignature, progress, runtimePolicy);
        String repairContextRevisionId = repairContextRevisionId(
                taskId, ready.proposal().proposalId());
        String blockIdentity = sha256(String.join("\0",
                failedAction.actionId(), authorityType, authorityRef,
                Objects.toString(receiptPayloadSha256, "NONE"),
                ready.proposal().proposalId(), proposedSignature,
                repairContextRevisionId,
                Long.toString(progress.snapshot().authorityEventCut()),
                progress.digest(), Integer.toString(observed), reason,
                runtimePolicy.policyVersion()));
        String blockId = "action-receipt-step-block." + blockIdentity;
        var block = new ChainPersistenceRecords.ActionReceiptStepBlockRecord(
                blockId, taskId,
                "action-receipt-step-block-event." + blockIdentity,
                failedAction.actionId(), authorityType, authorityRef,
                receiptId, receiptPayloadSha256,
                failedAction.instructionId(), failedAction.taskFrameId(),
                failedAction.planId(), failedAction.planRevisionId(),
                plan.planRevisionNumber(), failedAction.stepId(),
                failedAction.activationEventId(), ready.proposal().proposalId(),
                repairContextRevisionId,
                proposedSignature, progress.snapshot().authorityEventCut(),
                progress.digest(), observed, receiptStatus,
                failureCategory, failureCode, reason,
                runtimePolicy.policyVersion(),
                failedAction.versionFenceSha256(), blockIdentity, now);
        return commitBlockAndBindProposal(block, now);
    }

    private Decision replayCommittedBlock(
            String taskId,
            ChainPersistenceRecords.PlanBindingRecord plan,
            ChainStepAuthorityPort.StepEvent activation,
            ChainModelProtocolOutcome.ProposalReady ready,
            ProductChainExecutorActionContextProjection.Failure formalFailure) {
        var matches = workflow.findActionReceiptStepBlocks(taskId).stream()
                .filter(value -> value.actionId().equals(
                        formalFailure.actionId()))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw failure("CHAIN_ACTION_FAILURE_BLOCK_REPLAY_INVALID");
        }
        var block = matches.get(0);
        var actions = workflow.findActionBindings(taskId).stream()
                .filter(value -> value.actionId().equals(
                        formalFailure.actionId()))
                .toList();
        if (actions.size() != 1) {
            throw failure("CHAIN_ACTION_FAILURE_BLOCK_REPLAY_INVALID");
        }
        var action = actions.get(0);
        boolean sourceMatches;
        if (formalFailure.isReceiptFailure()) {
            var receipt = persistedResult(action.actionId()).receipt();
            sourceMatches = "RECEIPT".equals(block.failureAuthorityType())
                    && block.failureAuthorityRef().equals(
                    formalFailure.errorRef())
                    && Objects.equals(block.receiptId(),
                    receipt.id().value())
                    && Objects.equals(block.receiptPayloadSha256(),
                    receiptPayloadSha256(
                            action.actionId(), receipt.id().value()))
                    && Objects.equals(block.receiptStatus(),
                    formalFailure.receiptStatus().name())
                    && "EXECUTION".equals(block.failureCategory());
        } else {
            var candidate = exactCandidateFailure(taskId, action.actionId(),
                    formalFailure.errorRef());
            sourceMatches = "CANDIDATE_MATERIALIZATION_FAILURE".equals(
                    block.failureAuthorityType())
                    && block.failureAuthorityRef().equals(
                    candidate.candidateFailureId())
                    && block.receiptId() == null
                    && block.receiptPayloadSha256() == null
                    && block.receiptStatus() == null
                    && "CANDIDATE".equals(block.failureCategory())
                    && candidate.errorCode().equals(block.failureCode())
                    && candidate.workspaceId().equals(action.workspaceId())
                    && candidate.baseCandidateKey().equals(
                    action.baseCandidateKey())
                    && candidate.versionFenceSha256().equals(
                    action.versionFenceSha256());
        }
        String repairContext = repairContextRevisionId(
                taskId, ready.proposal().proposalId());
        ChainRuntimePolicy runtimePolicy = runtimePolicy(taskId, ready);
        boolean lineageMatches = block.failureAuthorityRef().equals(
                formalFailure.errorRef())
                && block.failureCode().equals(formalFailure.failureCode())
                && block.actionId().equals(action.actionId())
                && block.instructionId().equals(plan.instructionId())
                && block.taskFrameId().equals(plan.taskFrameId())
                && block.planId().equals(plan.planId())
                && block.planRevisionId().equals(plan.planRevisionId())
                && block.planRevisionNumber() == plan.planRevisionNumber()
                && block.stepId().equals(activation.command().stepId())
                && block.activationEventId().equals(
                activation.command().activationEventId())
                && sameCut(plan, activation, action)
                && block.repairProposalId().equals(
                ready.proposal().proposalId())
                && block.repairContextRevisionId().equals(repairContext)
                && block.repairProposalSignatureSha256().equals(
                ready.proposal().payload().sha256())
                && block.runtimePolicyVersion().equals(
                runtimePolicy.policyVersion())
                && block.versionFenceSha256().equals(
                action.versionFenceSha256())
                && block.blockIdentityDigestSha256().equals(
                sha256(String.join("\0",
                        block.actionId(), block.failureAuthorityType(),
                        block.failureAuthorityRef(),
                        Objects.toString(
                                block.receiptPayloadSha256(), "NONE"),
                        block.repairProposalId(),
                        block.repairProposalSignatureSha256(),
                        block.repairContextRevisionId(),
                        Long.toString(block.progressAuthorityEventCut()),
                        block.progressSnapshotDigestSha256(),
                        Integer.toString(
                                block.thresholdObservedOccurrences()),
                        block.blockReasonCode(),
                        block.runtimePolicyVersion())));
        if (!sourceMatches || !lineageMatches) {
            throw failure("CHAIN_ACTION_FAILURE_BLOCK_REPLAY_INVALID");
        }
        return Decision.blocked(block.stepBlockId());
    }

    private void lockTask(String taskId) {
        List<String> rows = jdbc.queryForList("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, Map.of("taskId", taskId), String.class);
        if (rows.size() != 1) {
            throw failure("CHAIN_ACTION_FAILURE_TASK_MISSING");
        }
    }

    private void assertCurrentPlan(
            String taskId,
            ChainPersistenceRecords.PlanBindingRecord expected) {
        List<ChainPersistenceRecords.PlanBindingRecord> plans =
                workflow.findPlanBindings(taskId);
        if (plans.isEmpty()
                || !plans.get(plans.size() - 1).planBindingId().equals(
                expected.planBindingId())) {
            throw failure("CHAIN_ACTION_FAILURE_PLAN_STALE");
        }
    }

    private Decision commitBlockAndBindProposal(
            ChainPersistenceRecords.ActionReceiptStepBlockRecord block,
            Instant now) {
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        admission.admit(new ChainProposalAdmissionService.AdmissionRequest(
                block.repairProposalId(), block.taskId(),
                "action-repair-accepted."
                        + sha256(block.repairProposalId()),
                true, null, block.repairProposalSignatureSha256(), now));
        var existing = workflow.findActionReceiptStepBlocks(block.taskId())
                .stream().filter(value -> value.actionId().equals(
                        block.actionId())).toList();
        ChainPersistenceRecords.ActionReceiptStepBlockRecord official;
        if (!existing.isEmpty()) {
            if (existing.size() != 1 || !sameBlock(existing.get(0), block)) {
                throw failure("CHAIN_ACTION_FAILURE_BLOCK_REPLAY_INVALID");
            }
            official = existing.get(0);
        } else {
            var appended = workflow.appendActionReceiptStepBlock(
                    new ChainPersistenceRecords.AuthoritativeFact<>(
                            new ChainPersistenceRecords.AuthorityEventRequest(
                                    block.eventId(), block.taskId(),
                                    "ACTION_RECEIPT_STEP_BLOCK", null,
                                    block.blockIdentityDigestSha256(), now),
                            block));
            if (!sameBlock(appended.fact(), block)) {
                throw failure("CHAIN_ACTION_FAILURE_BLOCK_APPEND_INVALID");
            }
            official = appended.fact();
        }
        var state = admission.replaceByOfficialResult(
                        new ChainProposalAdmissionService.OfficialReplacement(
                                official.repairProposalId(), official.taskId(),
                                "action-receipt-step-block-bound."
                                        + official.blockIdentityDigestSha256(),
                                ChainPersistenceRecords
                                        .ProposalOfficialAuthorityType
                                        .ACTION_RECEIPT_STEP_BLOCK,
                                official.stepBlockId(), null,
                                official.blockIdentityDigestSha256(), now))
                .state();
        if (!"ACTION_RECEIPT_STEP_BLOCK".equals(
                state.officialAuthorityType())
                || !official.stepBlockId().equals(
                state.officialAuthorityRef())) {
            throw failure("CHAIN_ACTION_FAILURE_PROPOSAL_BINDING_INVALID");
        }
        return Decision.blocked(official.stepBlockId());
    }

    private static boolean sameBlock(
            ChainPersistenceRecords.ActionReceiptStepBlockRecord left,
            ChainPersistenceRecords.ActionReceiptStepBlockRecord right) {
        return left.stepBlockId().equals(right.stepBlockId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.actionId().equals(right.actionId())
                && left.failureAuthorityType().equals(
                right.failureAuthorityType())
                && left.failureAuthorityRef().equals(
                right.failureAuthorityRef())
                && Objects.equals(left.receiptId(), right.receiptId())
                && Objects.equals(left.receiptPayloadSha256(),
                right.receiptPayloadSha256())
                && left.instructionId().equals(right.instructionId())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.planRevisionNumber() == right.planRevisionNumber()
                && left.stepId().equals(right.stepId())
                && left.activationEventId().equals(right.activationEventId())
                && left.repairProposalId().equals(right.repairProposalId())
                && left.repairContextRevisionId().equals(
                right.repairContextRevisionId())
                && left.repairProposalSignatureSha256().equals(
                right.repairProposalSignatureSha256())
                && left.progressAuthorityEventCut()
                == right.progressAuthorityEventCut()
                && left.progressSnapshotDigestSha256().equals(
                right.progressSnapshotDigestSha256())
                && left.thresholdObservedOccurrences()
                == right.thresholdObservedOccurrences()
                && Objects.equals(left.receiptStatus(), right.receiptStatus())
                && left.failureCategory().equals(right.failureCategory())
                && left.failureCode().equals(right.failureCode())
                && left.blockReasonCode().equals(right.blockReasonCode())
                && left.runtimePolicyVersion().equals(
                right.runtimePolicyVersion())
                && left.versionFenceSha256().equals(
                right.versionFenceSha256())
                && left.blockIdentityDigestSha256().equals(
                right.blockIdentityDigestSha256());
    }

    private ProgressEvidence progress(
            String taskId, String stepId, String activationId) {
        long cut = foundations.highestAuthorityEventSequence(taskId);
        Map<String, Long> sequences = new LinkedHashMap<>();
        for (var event : foundations.findAuthorityEvents(taskId, cut)) {
            if (sequences.put(event.eventId(), event.eventSequence()) != null) {
                throw failure("CHAIN_ACTION_FAILURE_EVENT_PREFIX_INVALID");
            }
        }
        List<ChainProgressPolicy.ProgressMarker> markers = new ArrayList<>();
        for (var action : workflow.findActionBindings(taskId).stream()
                .filter(value -> value.stepId().equals(stepId)
                        && value.activationEventId().equals(activationId))
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ActionBindingRecord::attemptNo))
                .toList()) {
            Long sequence = sequences.get(action.eventId());
            if (sequence == null || sequence > cut) {
                throw failure("CHAIN_ACTION_FAILURE_EVENT_CUT_INVALID");
            }
            PersistedEffectResult result = optionalResult(action.actionId());
            var candidateFailure = candidateFailures
                    .findCandidateMaterializationFailure(
                            taskId, action.actionId()).orElse(null);
            if (candidateFailure != null) {
                markers.add(new ChainProgressPolicy.ProgressMarker(
                        sequence, ChainActionProgressIdentity.candidateFailure(
                        action.actionSignatureSha256(),
                        candidateFailure.errorCode())));
                continue;
            }
            if (result == null) continue;
            var receipt = result.receipt();
            markers.add(new ChainProgressPolicy.ProgressMarker(
                    sequence, ChainActionProgressIdentity.receipt(
                    action.actionSignatureSha256(), receipt,
                    candidateEvidence(taskId, action.actionId()))));
        }
        String serialized = markers.stream()
                .map(value -> value.authorityEventSequence() + ":"
                        + value.progressIdentitySha256())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new ProgressEvidence(
                new ChainProgressAuthorityPort.ProgressSnapshot(cut, markers),
                sha256(serialized));
    }

    private List<String> candidateEvidence(
            String taskId, String actionId) {
        var candidates = workflow.findWorkspaceCandidates(taskId).stream()
                .filter(value -> value.actionId().equals(actionId)).toList();
        if (candidates.size() > 1) {
            throw failure("CHAIN_ACTION_PROGRESS_CANDIDATE_AMBIGUOUS");
        }
        return candidates.isEmpty() ? List.of() : List.of(
                candidates.get(0).candidateFingerprint(),
                candidates.get(0).diffDigest());
    }

    private int observations(
            String taskId, String stepId, String activationId,
            String reason, String proposedSignature,
            ProgressEvidence progress,
            ChainRuntimePolicy runtimePolicy) {
        if ("NO_PROGRESS_THRESHOLD_REACHED".equals(reason)) {
            return new ChainProgressPolicy(runtimePolicy)
                    .assess(progress.snapshot().markers())
                    .unchangedOccurrences();
        }
        if ("REPEATED_ACTION_SIGNATURE".equals(reason)) {
            return Math.toIntExact(workflow.findActionBindings(taskId).stream()
                    .filter(value -> value.stepId().equals(stepId)
                            && value.activationEventId().equals(activationId)
                            && value.actionSignatureSha256().equals(
                            proposedSignature)).count());
        }
        return 1;
    }

    private ChainRuntimePolicy runtimePolicy(
            String taskId, ChainModelProtocolOutcome.ProposalReady ready) {
        var invocation = models.findInvocation(
                        ready.proposal().invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_ACTION_FAILURE_INVOCATION_MISSING"));
        if (!invocation.taskId().equals(taskId)) {
            throw failure("CHAIN_ACTION_FAILURE_INVOCATION_INVALID");
        }
        return ChainRuntimePolicy.requireVersion(
                invocation.runtimePolicyVersion());
    }

    private PersistedEffectResult persistedResult(String actionId) {
        PersistedEffectResult result = optionalResult(actionId);
        if (result == null) {
            throw failure("CHAIN_ACTION_FAILURE_RECEIPT_MISSING");
        }
        return result;
    }

    private ChainPersistenceRecords.CandidateMaterializationFailureRecord
            exactCandidateFailure(
                    String taskId, String actionId, String failureRef) {
        var value = candidateFailures.findCandidateMaterializationFailure(
                        taskId, actionId)
                .orElseThrow(() -> failure(
                        "CHAIN_ACTION_FAILURE_CANDIDATE_MISSING"));
        if (!value.candidateFailureId().equals(failureRef)) {
            throw failure("CHAIN_ACTION_FAILURE_CANDIDATE_REF_INVALID");
        }
        return value;
    }

    private String receiptPayloadSha256(
            String actionId, String receiptId) {
        List<String> values = jdbc.queryForList("""
                SELECT receipt.payload_sha256
                  FROM agent_v2_receipts receipt
                  JOIN agent_v2_effect_results result
                    ON result.receipt_id = receipt.receipt_id
                   AND result.tool_call_id = receipt.tool_call_id
                 WHERE receipt.tool_call_id = :actionId
                   AND receipt.receipt_id = :receiptId
                """, Map.of("actionId", actionId, "receiptId", receiptId),
                String.class);
        if (values.size() != 1
                || !values.get(0).matches("[0-9a-f]{64}")) {
            throw failure("CHAIN_ACTION_FAILURE_RECEIPT_DIGEST_INVALID");
        }
        return values.get(0);
    }

    private String repairContextRevisionId(
            String taskId, String proposalId) {
        List<String> values = jdbc.queryForList("""
                SELECT invocation.context_revision_id
                  FROM agent_v2_chain_model_proposals proposal
                  JOIN agent_v2_chain_model_invocations invocation
                    ON invocation.task_id = proposal.task_id
                   AND invocation.invocation_id = proposal.invocation_id
                 WHERE proposal.task_id = :taskId
                   AND proposal.proposal_id = :proposalId
                """, Map.of("taskId", taskId, "proposalId", proposalId),
                String.class);
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw failure("CHAIN_ACTION_FAILURE_REPAIR_CONTEXT_INVALID");
        }
        return values.get(0);
    }

    private PersistedEffectResult optionalResult(String actionId) {
        PersistenceResult<PersistedEffectResult> found = outcomes.findResult(
                new ToolCallId(actionId));
        if (found.successful()) return found.value().orElseThrow();
        if (found.failure().orElseThrow().code()
                == PersistenceErrorCode.NOT_FOUND) return null;
        throw failure("CHAIN_ACTION_FAILURE_RECEIPT_READ_FAILED");
    }

    private static boolean sameCut(
            ChainPersistenceRecords.PlanBindingRecord plan,
            ChainStepAuthorityPort.StepEvent activation,
            ChainPersistenceRecords.ActionBindingRecord action) {
        return action.instructionId().equals(plan.instructionId())
                && action.taskFrameId().equals(plan.taskFrameId())
                && action.planId().equals(plan.planId())
                && action.planRevisionId().equals(plan.planRevisionId())
                && action.stepId().equals(activation.command().stepId())
                && action.activationEventId().equals(
                activation.command().activationEventId());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private record ProgressEvidence(
            ChainProgressAuthorityPort.ProgressSnapshot snapshot,
            String digest) {
    }

    public record Decision(boolean blocked, String stepBlockId) {
        static Decision continueRepair() { return new Decision(false, null); }
        static Decision blocked(String id) { return new Decision(true, id); }
    }
}
