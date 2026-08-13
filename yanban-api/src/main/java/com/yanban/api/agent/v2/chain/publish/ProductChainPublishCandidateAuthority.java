package com.yanban.api.agent.v2.chain.publish;

import com.yanban.api.agent.v2.chain.recovery.ProductChainReadinessAuthority;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort.PublishCommand;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Resolves the one typed Candidate proof authorized by an exact V82 bundle. */
@Component
public final class ProductChainPublishCandidateAuthority {
    private final ChainFoundationRepository foundations;
    private final ChainFinalizationRepository finalization;
    private final ChainValidationBundleRepository bundles;
    private final ChainValidationRepository validations;
    private final ChainWorkflowRepository workflow;
    private final ProductChainReadinessAuthority readinessAuthority;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainPublishCandidateAuthority(
            ChainFoundationRepository foundations,
            ChainFinalizationRepository finalization,
            ChainValidationBundleRepository bundles,
            ChainValidationRepository validations,
            ChainWorkflowRepository workflow,
            @Lazy ProductChainReadinessAuthority readinessAuthority,
            NamedParameterJdbcTemplate jdbc) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.validations = Objects.requireNonNull(validations, "validations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.readinessAuthority = Objects.requireNonNull(
                readinessAuthority, "readinessAuthority");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Proof requireExact(PublishCommand command) {
        Objects.requireNonNull(command, "command");
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                finalization.findReadinessById(command.readinessId())
                        .orElseThrow(() -> invalid("publish readiness is missing"));
        require(readiness.taskId().equals(command.taskId())
                        && readiness.publishRequirement()
                        == ChainPublishRequirement.REQUIRED
                        && Objects.equals(readiness.artifactId(),
                        command.artifactId())
                        && readiness.candidateKey().equals(
                        command.candidateKey())
                        && readiness.validationId().equals(
                        command.validationId())
                        && readiness.projectVersion().equals(
                        command.baseProjectVersion())
                        && Objects.equals(readiness.validationRequestDigest(),
                        command.validationRequestDigest())
                        && Objects.equals(readiness.validationReceiptDigest(),
                        command.validationReceiptDigest()),
                "publish command does not bind exact readiness");
        List<ChainPersistenceRecords.FinalizationCheckRecord> checks =
                finalization.findFinalizationChecks(readiness.readinessId())
                        .stream()
                        .filter(value -> value.finalizationCheckId().equals(
                                command.finalizationCheckId()))
                        .toList();
        require(checks.size() == 1, "publish finalization check is not exact");
        ChainPersistenceRecords.FinalizationCheckRecord check = checks.get(0);
        require(check.resultStatus() == ChainFinalization.Outcome.PASSED
                        && check.taskId().equals(readiness.taskId())
                        && check.readinessId().equals(readiness.readinessId())
                        && check.taskFrameId().equals(readiness.taskFrameId())
                        && check.finalPlanRevisionId().equals(
                        readiness.finalPlanRevisionId())
                        && check.candidateKey().equals(readiness.candidateKey())
                        && check.workspaceId().equals(readiness.workspaceId())
                        && check.validationId().equals(readiness.validationId())
                        && Objects.equals(check.validationRequestDigest(),
                        readiness.validationRequestDigest())
                        && Objects.equals(check.validationReceiptDigest(),
                        readiness.validationReceiptDigest())
                        && check.projectVersion().equals(
                        readiness.projectVersion()),
                "publish finalization check does not bind readiness");

        readinessAuthority.requireExact(readiness);
        ChainPersistenceRecords.ValidationBundleRecord bundle = bundles
                .findBundle(command.validationId())
                .orElseThrow(() -> invalid("publish Validation bundle is missing"));
        require(bundle.taskId().equals(readiness.taskId())
                        && bundle.taskFrameId().equals(readiness.taskFrameId())
                        && bundle.planId().equals(readiness.finalPlanId())
                        && bundle.planRevisionId().equals(
                        readiness.finalPlanRevisionId())
                        && bundle.planRevisionNumber()
                        == readiness.finalPlanRevisionNumber()
                        && bundle.finalStepId().equals(readiness.finalStepId())
                        && bundle.requestDigest().equals(
                        command.validationRequestDigest())
                        && bundle.receiptSetDigest().equals(
                        command.validationReceiptDigest())
                        && bundle.conclusion()
                        == ChainValidationConclusion.PASSED,
                "publish Validation bundle does not bind readiness");

        List<SelectedItem> items =
                new ArrayList<>();
        for (ChainPersistenceRecords.ValidationBundleSetRecord member
                : bundles.findBundleSets(bundle.validationBundleId())) {
            require(member.validationBundleId().equals(
                            bundle.validationBundleId())
                            && member.taskId().equals(bundle.taskId()),
                    "publish Validation membership is invalid");
            validations.findCandidateItems(member.validationId()).forEach(
                    item -> {
                        require(item.validationId().equals(
                                        member.validationId()),
                                "publish Candidate item changed Validation set");
                        items.add(new SelectedItem(member, item));
                    });
        }
        require(items.size() == 1,
                "publish Candidate Validation item is missing or ambiguous");
        SelectedItem selected = items.get(0);
        ChainPersistenceRecords.ValidationBundleSetRecord member =
                selected.member();
        ChainPersistenceRecords.CandidateValidationItemRecord item =
                selected.item();
        require(item.taskId().equals(readiness.taskId())
                        && item.conclusion() == ChainValidationConclusion.PASSED
                        && item.workspaceCandidateId().equals(
                        readiness.candidateKey())
                        && item.workspaceId().equals(readiness.workspaceId())
                        && item.artifactId() == command.artifactId()
                        && item.baseProjectVersion().equals(
                        command.baseProjectVersion()),
                "publish Candidate Validation item does not bind readiness");

        ChainPersistenceRecords.WorkspaceCandidateRecord candidate = exact(
                workflow.findWorkspaceCandidates(command.taskId()).stream()
                        .filter(value -> value.workspaceCandidateId().equals(
                                item.workspaceCandidateId())).toList(),
                "publish Workspace Candidate is missing or ambiguous");
        require(candidate.actionId().equals(item.candidateActionId())
                        && candidate.workspaceId().equals(item.workspaceId())
                        && candidate.artifactId() == item.artifactId()
                        && candidate.candidateFingerprint().equals(
                        item.candidateFingerprint())
                        && candidate.baseProjectVersion().equals(
                        item.baseProjectVersion()),
                "publish Workspace Candidate identity changed");

        List<ChainPersistenceRecords.ActionBindingRecord> actions = workflow
                .findActionBindings(command.taskId());
        ChainPersistenceRecords.ActionBindingRecord candidateAction = exact(
                actions.stream().filter(value -> value.actionId().equals(
                        item.candidateActionId())).toList(),
                "publish Candidate Action is missing or ambiguous");
        ChainPersistenceRecords.ActionBindingRecord validationAction = exact(
                actions.stream().filter(value -> value.actionId().equals(
                        item.validationActionId())).toList(),
                "publish Validation Action is missing or ambiguous");
        require(candidateAction.taskFrameId().equals(bundle.taskFrameId())
                        && candidateAction.planId().equals(bundle.planId())
                        && candidateAction.planRevisionId().equals(
                        bundle.planRevisionId())
                        && candidateAction.workspaceId().equals(
                        candidate.workspaceId())
                        && validationAction.taskFrameId().equals(
                        bundle.taskFrameId())
                        && validationAction.planId().equals(bundle.planId())
                        && validationAction.planRevisionId().equals(
                        bundle.planRevisionId())
                        && validationAction.stepId().equals(member.stepId())
                        && validationAction.activationEventId().equals(
                        member.activationEventId())
                        && validationAction.workspaceId().equals(
                        candidate.workspaceId())
                        && validationAction.baseCandidateKey().equals(
                        candidate.candidateFingerprint())
                        && validationAction.actionSignatureSha256().equals(
                        item.actionSignatureSha256()),
                "publish Candidate Actions do not bind exact Plan revision");

        List<ReceiptRow> receipts = jdbc.query("""
                SELECT receipt.tool_call_id, receipt.payload_sha256,
                       receipt.payload_json
                  FROM agent_v2_receipts receipt
                  JOIN agent_v2_effect_results result
                    ON result.receipt_id = receipt.receipt_id
                   AND result.tool_call_id = receipt.tool_call_id
                 WHERE receipt.receipt_id = :receiptId
                   AND receipt.tool_call_claim_owner_kind = 'EFFECT_INTENT'
                   AND receipt.receipt_owner_kind = 'EFFECT_OUTCOME'
                """, new MapSqlParameterSource("receiptId", item.receiptId()),
                (row, ignored) -> new ReceiptRow(
                        row.getString("tool_call_id"),
                        row.getString("payload_sha256"),
                        row.getString("payload_json")));
        require(receipts.size() == 1,
                "publish original Receipt is missing or ambiguous");
        ReceiptRow receipt = receipts.get(0);
        require(receipt.actionId().equals(item.validationActionId())
                        && receipt.payloadSha256().equals(
                        item.receiptPayloadSha256())
                        && receipt.payloadSha256().equals(
                        sha256(receipt.payloadJson())),
                "publish original Receipt identity changed");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId())
                .orElseThrow(() -> invalid("publish task is missing"));
        require(task.projectId() != null
                        && task.initialProjectVersion().equals(
                        command.baseProjectVersion()),
                "publish task Project identity changed");
        return new Proof(task.userId(), task.projectId(), item.receiptId(),
                candidate.workspaceCandidateId(), candidate.workspaceId(),
                candidate.artifactId(), candidate.candidateFingerprint(),
                candidate.baseProjectVersion(), candidateAction.actionId(),
                validationAction.actionId(), item.validationId(),
                item.requirementId());
    }

    private static <T> T exact(List<T> values, String message) {
        require(values.size() == 1, message);
        return values.get(0);
    }

    private static BindingMismatchException invalid(String message) {
        return new BindingMismatchException(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Proof(
            long userId, long projectId, String receiptId,
            String workspaceCandidateId, String workspaceId, long artifactId,
            String candidateFingerprint, String baseProjectVersion,
            String candidateActionId, String validationActionId,
            String validationSetId, String requirementId) {
    }

    private record ReceiptRow(
            String actionId, String payloadSha256, String payloadJson) {
    }

    private record SelectedItem(
            ChainPersistenceRecords.ValidationBundleSetRecord member,
            ChainPersistenceRecords.CandidateValidationItemRecord item) {
    }

    static final class BindingMismatchException extends IllegalStateException {
        private BindingMismatchException(String message) {
            super(message);
        }
    }
}
