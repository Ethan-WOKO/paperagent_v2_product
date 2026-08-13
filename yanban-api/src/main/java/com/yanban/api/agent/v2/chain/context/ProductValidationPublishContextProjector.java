package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationBundleRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.validation.ProductChainValidationAuthority;
import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Projects validation, finalization and publish facts without executing them. */
@Component
public final class ProductValidationPublishContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.VALIDATION_AND_PUBLISH;
    private static final String VERSION = "product-validation-publish-v3";
    private static final String PAGINATION = "none-v1";
    private final ProductValidationPublishAuthority authority;
    private final ProductChainTerminalOutcomeAuthority terminalOutcomes;

    public ProductValidationPublishContextProjector(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainFinalizationRepository finalization,
            ProductChainValidationRepositoryAdapter validations,
            ProductChainValidationBundleRepositoryAdapter bundles,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainStepAuthorityAdapter steps,
            ProductChainValidationAuthority receiptBodies,
            ProductChainTerminalOutcomeAuthority terminalOutcomes,
            ProductChainPublishAuthoritySource publishes) {
        authority = new ProductValidationPublishAuthority(
                foundations, workflow, finalization, validations, bundles,
                bootstraps, steps, receiptBodies, terminalOutcomes, publishes);
        this.terminalOutcomes = Objects.requireNonNull(
                terminalOutcomes, "terminalOutcomes");
    }

    /** Exact public summary of the same terminal Validation used by Answer. */
    public TerminalValidation terminalValidation(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            String candidateFingerprint) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");
        var terminal = terminalOutcomes.requireExact(task, outcome);
        if (terminal.readiness() == null) {
            return new TerminalValidation(null, "UNAVAILABLE", null, null,
                    List.of());
        }
        var readiness = terminal.readiness();
        var building = new ChainPersistenceRecords.ContextRevisionRecord(
                "terminal-validation." + outcome.outcomeId(), task.taskId(),
                null, ChainRole.ANSWER,
                outcome.outcomeType()
                        == io.paperagent.v2.chain.ChainTaskOutcomeStatus.COMPLETED
                        ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL,
                "TASK_OUTCOME", outcome.instructionId(),
                readiness.taskFrameId(), readiness.finalPlanId(),
                readiness.finalPlanRevisionId(),
                readiness.finalPlanRevisionNumber(), terminal.finalStepId(),
                terminal.activationEventId(), task.projectId(),
                // A ContextRevision is bound to the immutable input cut.  A
                // successfully published version is a TaskOutcome result and
                // must not replace that frozen input identity.
                task.initialProjectVersion(), readiness.workspaceId(),
                outcome.finalArtifactId(), outcome.finalArtifactId() == null
                        ? null : candidateFingerprint,
                terminal.validation().validationId(),
                terminal.validation().requestDigest(),
                terminal.validation().receiptDigest(),
                "chain-product-projector-v1", "v1",
                ChainRuntimePolicy.requireVersion(
                        terminal.check().runtimePolicyVersion())
                        .policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, outcome.createdAt(), null);
        ProductValidationPublishFacts facts = authority.load(building);
        if (facts.validation() == null) {
            if (!ChainIdentity.NONE.equals(outcome.validationId())) {
                throw blocked("terminal Validation proof is missing");
            }
            return new TerminalValidation(ChainIdentity.NONE,
                    "NOT_REQUIRED", null, null, List.of());
        }
        List<TerminalReceipt> receipts = new ArrayList<>();
        for (var set : facts.validation().sets()) {
            for (var item : set.candidateItems()) {
                receipts.add(new TerminalReceipt(item.requirementId(),
                        "CANDIDATE", item.receiptId(),
                        item.validationActionId(), item.artifactId(),
                        item.candidateFingerprint(),
                        item.baseProjectVersion()));
            }
            for (var item : set.actionReceiptItems()) {
                receipts.add(new TerminalReceipt(item.requirementId(),
                        "ACTION_RECEIPT", item.receiptId(), item.actionId(),
                        null, null, null));
            }
        }
        receipts.sort(java.util.Comparator.comparing(
                TerminalReceipt::requirementId));
        return new TerminalValidation(facts.validation().authorityRef(),
                facts.validation().conclusion().name(),
                facts.validation().requestDigest(),
                facts.validation().receiptSetDigest(), receipts);
    }

    public record TerminalValidation(
            String validationId, String status, String requestDigest,
            String receiptDigest, List<TerminalReceipt> receipts) {
        public TerminalValidation {
            status = Objects.requireNonNull(status, "status");
            receipts = List.copyOf(receipts);
        }
    }

    public record TerminalReceipt(
            String requirementId, String subject, String receiptId,
            String actionId, Long candidateArtifactId,
            String candidateFingerprint, String projectVersion) {
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            var facts = authority.load(request.buildingRevision());
            if (facts.empty()) return empty(facts);
            var values = ProductValidationPublishProjectionValues.create(
                    request.requiredFields(MODULE), facts);
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw blocked("validation/publish authority query failed");
        }
    }

    private static ProductChainContextAuthorityProjection empty(
            ProductValidationPublishFacts facts) {
        String digest = ProductChainContractProjectionCodec.sha256(
                "validation-publish=NONE\0" + facts.building().taskId());
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("validationIdentityStatusAndDigest",
                                ChainContextValue.text("NONE"),
                        "readiness", ChainContextValue.text("NONE"),
                        "finalizationAttempt", ChainContextValue.number(0),
                        "publishOperationAndVersion",
                                ChainContextValue.text("NONE")),
                Map.of("candidate", candidateBoundary(facts),
                        "workspace", nullable(facts.building().workspaceId()),
                        "validationCut", ChainContextValue.number(0)),
                VERSION, PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest),
                        "taskAuthorityHead", ChainContextValue.number(
                                facts.taskEventCut())),
                "allSequences=0");
    }

    private static ChainContextValue candidateBoundary(
            ProductValidationPublishFacts facts) {
        if (facts.building().candidateArtifactId() == null) {
            return ChainContextValue.text("NONE");
        }
        return ChainContextValue.object(Map.of(
                "artifactId", ChainContextValue.number(
                        facts.building().candidateArtifactId()),
                "fingerprint", ChainContextValue.text(
                        facts.building().candidateFingerprint())));
    }

    private static ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.text(value);
    }

    private static ChainContextException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
