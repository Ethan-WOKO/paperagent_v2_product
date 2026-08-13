package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads one exact Plan revision and its optional active Step authority. */
@Component
public final class ProductPlanStepContractContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.PLAN_AND_STEP_CONTRACT;
    private static final String VERSION = "product-plan-step-contract-v1";
    private static final String PAGINATION = "none-v1";

    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductChainStepAuthorityAdapter steps;
    private final ProductPlanAuthorityCutReader authorityCuts;

    public ProductPlanStepContractContextProjector(
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainStepAuthorityAdapter steps,
            ChainFoundationRepository foundations) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.authorityCuts = new ProductPlanAuthorityCutReader(
                workflow, Objects.requireNonNull(foundations, "foundations"));
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        ContextRevisionRecord building = request.buildingRevision();
        if (building.planId() == null) {
            if (ProductDirectAnswerContextAuthority.isDirectAnswer(building)) {
                ProductDirectAnswerContextAuthority.require(building, workflow);
                return emptyDirectAnswer(building);
            }
            return emptyInitialPlanner(building);
        }

        PlanBindingRecord binding = exactBinding(building);
        PersistedPlanBootstrap bootstrap = bootstraps
                .find(new PlanId(building.planId()))
                .orElseThrow(() -> blocked("Plan bootstrap is missing"));
        verifyBootstrap(building, binding, bootstrap);
        PlanRevision revision = exactRevision(building);
        List<ChainStepAuthorityPort.StepEvent> allEvents =
                steps.findStepEvents(
                        building.taskId(), building.planRevisionId());
        ChainStepAuthorityPort.StepEvent activation = exactActivation(
                building, revision, allEvents);
        List<ChainStepAuthorityPort.StepEvent> visibleEvents = activation == null
                ? List.copyOf(allEvents)
                : allEvents.stream().filter(value -> value.authoritySequence()
                        <= activation.authoritySequence()).toList();
        PlanStep currentStep = activation == null ? null
                : revision.steps().stream().filter(value -> value.id().value()
                        .equals(building.stepId())).findFirst().orElseThrow();
        long eventCut = authorityCuts.chainAuthorityCut(
                building.taskId(), binding, visibleEvents);
        long v2EventSequence = Math.max(
                bootstrap.initialCheckpoint().checkpoint().lastEventSequence(),
                visibleEvents.stream().mapToLong(
                        ChainStepAuthorityPort.StepEvent::authoritySequence)
                        .max().orElse(0));
        var encoded = ProductChainContractProjectionCodec.planRevision(revision);
        var values = ProductPlanContractProjectionValues.create(
                request.requiredFields(MODULE), bootstrap, binding, revision,
                currentStep, activation, visibleEvents, encoded,
                v2EventSequence, eventCut);
        return ProductChainContextProjectionSupport.present(
                MODULE, values.sourceVersion(), values.readBoundary(), VERSION,
                PAGINATION, values.parameters(), values.fields(),
                request.requiredFields(MODULE).toArray(String[]::new));
    }

    private ProductChainContextAuthorityProjection emptyDirectAnswer(
            ContextRevisionRecord building) {
        String digest = ProductChainContractProjectionCodec.sha256(
                "plan=DIRECT_EMPTY\0" + building.taskId() + "\0"
                        + building.instructionId());
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("planId", ChainContextValue.text("NONE"),
                        "revisionIdentity", ChainContextValue.text("NONE"),
                        "checkpoint", ChainContextValue.number(0),
                        "v2EventSequence", ChainContextValue.number(0),
                        "payloadHash", ChainContextValue.text(digest)),
                Map.of("stableV2PlanCut", ChainContextValue.object(Map.of(
                                "taskId", ChainContextValue.text(
                                        building.taskId()),
                                "formalPlanBindingCount",
                                ChainContextValue.number(0),
                                "chainAuthorityEventCut",
                                ChainContextValue.number(0))),
                        "chainAuthorityEventCut", ChainContextValue.number(0)),
                VERSION, PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest),
                        "instructionRef", ref(building.instructionId())),
                "plan=NONE,revision=0,v2EventSequence=0");
    }

    private ProductChainContextAuthorityProjection emptyInitialPlanner(
            ContextRevisionRecord building) {
        if (building.role() != ChainRole.PLANNER
                || building.planRevisionId() != null
                || building.planRevisionNumber() != null
                || building.stepId() != null
                || building.activationEventId() != null) {
            throw blocked("only the initial Planner may observe no Plan");
        }
        long cut = authorityCuts.emptyAuthorityCut(building.taskId());
        if (!workflow.findPlanBindings(building.taskId()).isEmpty()) {
            throw blocked("a formal Plan binding already exists");
        }
        String digest = ProductChainContractProjectionCodec.sha256(
                "plan=NONE\0" + building.taskId() + "\0"
                        + building.instructionId() + "\0" + cut);
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("planId", ChainContextValue.text("NONE"),
                        "revisionIdentity", ChainContextValue.text("NONE"),
                        "checkpoint", ChainContextValue.number(0),
                        "v2EventSequence", ChainContextValue.number(0),
                        "payloadHash", ChainContextValue.text(digest)),
                Map.of("stableV2PlanCut", ChainContextValue.object(Map.of(
                                "taskId", ChainContextValue.text(
                                        building.taskId()),
                                "formalPlanBindingCount",
                                ChainContextValue.number(0),
                                "chainAuthorityEventCut",
                                ChainContextValue.number(cut))),
                        "chainAuthorityEventCut",
                        ChainContextValue.number(cut)),
                VERSION, PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest),
                        "instructionRef", ref(building.instructionId())),
                "plan=NONE,revision=0,v2EventSequence=0");
    }

    private PlanBindingRecord exactBinding(ContextRevisionRecord building) {
        List<PlanBindingRecord> matches = workflow
                .findPlanBindings(building.taskId()).stream()
                .filter(value -> value.planId().equals(building.planId()))
                .filter(value -> value.planRevisionId().equals(
                        building.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == building.planRevisionNumber())
                .filter(value -> Objects.equals(value.taskFrameId(),
                        building.taskFrameId())).toList();
        if (matches.size() != 1) {
            throw blocked("Plan requires one exact binding");
        }
        return matches.get(0);
    }

    private static void verifyBootstrap(
            ContextRevisionRecord building, PlanBindingRecord binding,
            PersistedPlanBootstrap bootstrap) {
        boolean exact = bootstrap.plan().id().value().equals(building.planId())
                && bootstrap.plan().taskFrameId().value().equals(
                building.taskFrameId())
                && bootstrap.taskFrame().id().value().equals(
                binding.taskFrameId());
        if (!exact) throw blocked("Plan bootstrap identity is inconsistent");
    }

    private PlanRevision exactRevision(ContextRevisionRecord building) {
        PlanRevision revision = steps.findPlanRevision(
                        building.taskId(), building.planRevisionId())
                .orElseThrow(() -> blocked("Plan revision is missing"));
        if (!revision.id().value().equals(building.planRevisionId())
                || revision.number() != building.planRevisionNumber()
                || !revision.taskFrameId().value().equals(
                building.taskFrameId())) {
            throw blocked("recovered Plan revision mismatches the frozen cut");
        }
        return revision;
    }

    private static ChainStepAuthorityPort.StepEvent exactActivation(
            ContextRevisionRecord building, PlanRevision revision,
            List<ChainStepAuthorityPort.StepEvent> events) {
        if (building.stepId() == null) {
            if (building.role() == ChainRole.EXECUTOR
                    || building.role() == ChainRole.REFLECTOR) {
                throw blocked("the active role requires one current Step");
            }
            return null;
        }
        long definitions = revision.steps().stream().filter(value ->
                value.id().value().equals(building.stepId())).count();
        List<ChainStepAuthorityPort.StepEvent> matches = events.stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .filter(value -> value.command().stepId().equals(
                        building.stepId()))
                .filter(value -> value.command().activationEventId().equals(
                        building.activationEventId()))
                .filter(value -> value.command().planRevisionId().equals(
                        building.planRevisionId())).toList();
        if (definitions != 1 || matches.size() != 1) {
            throw blocked("current Step/activation is not exactly bound");
        }
        return matches.get(0);
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
