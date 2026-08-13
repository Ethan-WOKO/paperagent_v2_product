package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Projects the frozen TaskFrame from its exact product Plan binding. */
@Component
public final class ProductTaskContractContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.TASK_CONTRACT;
    private static final String PROJECTION_VERSION =
            "product-task-contract-v2";
    private static final String PAGINATION_VERSION = "none-v1";

    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ChainFoundationRepository foundations;

    public ProductTaskContractContextProjector(
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ChainFoundationRepository foundations) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        ContextRevisionRecord building = request.buildingRevision();
        if (building.taskFrameId() == null) {
            if (ProductDirectAnswerContextAuthority.isDirectAnswer(building)) {
                ProductDirectAnswerContextAuthority.require(building, workflow);
                return emptyDirectAnswer(building);
            }
            return emptyInitialPlanner(building);
        }

        PlanBindingRecord binding = exactBinding(building);
        PersistedPlanBootstrap bootstrap = bootstraps
                .find(new PlanId(binding.planId()))
                .orElseThrow(() -> blocked("TaskFrame bootstrap is missing"));
        TaskFrame frame = bootstrap.taskFrame();
        verifyIdentity(building, binding, bootstrap, frame);

        ProductChainContractProjectionCodec.Projection encoded =
                ProductChainContractProjectionCodec.taskFrame(frame);
        long bindingSequence = bindingSequence(building, binding);
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : request.requiredFields(MODULE)) {
            fields.put(field, fieldValue(
                    field, frame, binding, encoded.value()));
        }
        Map<String, ChainContextValue> sourceVersion = Map.of(
                "taskFrameId", ref(frame.id().value()),
                "taskFramePayloadHash", ChainContextValue.text(
                        encoded.sha256()));
        Map<String, ChainContextValue> readBoundary = Map.of(
                "taskIdentity", identityValue(
                        building, binding, bindingSequence));
        Map<String, ChainContextValue> parameters = Map.of(
                "planBindingRef", ref(binding.planBindingId()),
                "taskFrameRef", ref(frame.id().value()),
                "contractDigest", ChainContextValue.text(encoded.sha256()));
        return ProductChainContextProjectionSupport.present(
                MODULE, sourceVersion, readBoundary, PROJECTION_VERSION,
                PAGINATION_VERSION, parameters, fields,
                request.requiredFields(MODULE).toArray(String[]::new));
    }

    private ProductChainContextAuthorityProjection emptyDirectAnswer(
            ContextRevisionRecord building) {
        String digest = ProductChainContractProjectionCodec.sha256(
                "taskFrame=DIRECT_EMPTY\0" + building.taskId() + "\0"
                        + building.instructionId());
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("taskFrameId", ChainContextValue.text("NONE"),
                        "taskFramePayloadHash", ChainContextValue.text(digest)),
                Map.of("taskIdentity", ChainContextValue.object(Map.of(
                        "taskId", ChainContextValue.text(building.taskId()),
                        "instructionId", ref(building.instructionId()),
                        "executionMode", ChainContextValue.text("DIRECT")))),
                PROJECTION_VERSION, PAGINATION_VERSION,
                Map.of("absenceDigest", ChainContextValue.text(digest)),
                "taskFrame=NONE@instructionVersion");
    }

    private ProductChainContextAuthorityProjection emptyInitialPlanner(
            ContextRevisionRecord building) {
        if (building.role() != ChainRole.PLANNER || building.planId() != null
                || building.stepId() != null
                || building.activationEventId() != null) {
            throw blocked("only the initial Planner may observe no TaskFrame");
        }
        long authorityCut = foundations.highestAuthorityEventSequence(
                building.taskId());
        if (!workflow.findPlanBindings(building.taskId()).isEmpty()) {
            throw blocked("a formal TaskFrame binding already exists");
        }
        String absenceDigest = ProductChainContractProjectionCodec.sha256(
                "taskFrame=NONE\0" + building.taskId() + "\0"
                        + building.instructionId() + "\0" + authorityCut);
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("taskFrameId", ChainContextValue.text("NONE"),
                        "taskFramePayloadHash",
                        ChainContextValue.text(absenceDigest)),
                Map.of("taskIdentity", ChainContextValue.object(Map.of(
                        "taskId", ChainContextValue.text(building.taskId()),
                        "instructionId", ref(building.instructionId()),
                        "chainAuthorityEventCut",
                        ChainContextValue.number(authorityCut),
                        "formalTaskFrameBindingCount",
                        ChainContextValue.number(0)))),
                PROJECTION_VERSION, PAGINATION_VERSION,
                Map.of("absenceDigest", ChainContextValue.text(absenceDigest)),
                "taskFrame=NONE@instructionVersion");
    }

    private PlanBindingRecord exactBinding(ContextRevisionRecord building) {
        List<PlanBindingRecord> matches = workflow
                .findPlanBindings(building.taskId()).stream()
                .filter(value -> value.taskFrameId().equals(
                        building.taskFrameId()))
                .filter(value -> building.planId() == null
                        || value.planId().equals(building.planId()))
                .filter(value -> building.planRevisionId() == null
                        || value.planRevisionId().equals(
                        building.planRevisionId()))
                .filter(value -> building.planRevisionNumber() == null
                        || value.planRevisionNumber()
                        == building.planRevisionNumber())
                .toList();
        if (matches.size() != 1) {
            throw blocked("TaskFrame requires one exact Plan binding");
        }
        return matches.get(0);
    }

    private void verifyIdentity(
            ContextRevisionRecord building,
            PlanBindingRecord binding,
            PersistedPlanBootstrap bootstrap,
            TaskFrame frame) {
        boolean exact = frame.id().value().equals(building.taskFrameId())
                && frame.id().value().equals(binding.taskFrameId())
                && bootstrap.plan().id().value().equals(binding.planId())
                && bootstrap.plan().taskFrameId().equals(frame.id());
        if (!exact) {
            throw blocked("TaskFrame/Plan binding identity is inconsistent");
        }
        ProjectVersionRef project = frame.sourceProjectVersion().orElse(null);
        boolean projectExact = project == null
                ? building.projectId() == null && building.projectVersion() == null
                : building.projectId() != null
                && Long.toString(building.projectId()).equals(project.projectId())
                && Objects.equals(building.projectVersion(), project.versionId());
        if (!projectExact) {
            throw blocked("TaskFrame ProjectVersion does not match the frozen cut");
        }
    }

    private long bindingSequence(
            ContextRevisionRecord building, PlanBindingRecord binding) {
        long cut = foundations.highestAuthorityEventSequence(building.taskId());
        return foundations.findAuthorityEvents(building.taskId(), cut).stream()
                .filter(value -> value.eventId().equals(binding.eventId()))
                .mapToLong(value -> value.eventSequence())
                .reduce((left, right) -> {
                    throw blocked("Plan binding event identity is ambiguous");
                }).orElseThrow(() -> blocked(
                        "Plan binding event is outside the authority cut"));
    }

    private static ChainContextValue fieldValue(
            String field, TaskFrame frame, PlanBindingRecord binding,
            ChainContextValue complete) {
        return switch (field) {
            case "taskFrame.deliveryRequirements" -> ChainContextValue.object(
                    Map.of("taskFrameRef", ref(frame.id().value()),
                            "requirements",
                            ProductChainContractProjectionCodec
                                    .taskRequirements(frame.requirements())));
            case "taskFrame.validationRequirements" -> ChainContextValue.object(
                    Map.of("taskFrameRef", ref(frame.id().value()),
                            "requirements",
                            ProductChainContractProjectionCodec
                                    .taskRequirements(frame.requirements())));
            case "taskFrame.completeOrExplicitEmpty", "taskFrame.complete",
                    "taskFrame.hardBoundary",
                    "taskFrame.persistentCompleteOrDirectEmpty",
                    "foundation.taskFrameAndHardBoundary" ->
                    ChainContextValue.object(Map.of(
                    "taskFrameRef", ref(frame.id().value()),
                    "contract", complete));
            case "taskFrame.officialRouteOrOutcome" ->
                    ChainContextValue.object(Map.of(
                            "planBindingRef",
                            ref(binding.planBindingId()),
                            "taskFrameRef", ref(frame.id().value()),
                            "contract", complete));
            default -> throw blocked(
                    "unsupported required TaskFrame field: " + field);
        };
    }

    private static ChainContextValue identityValue(
            ContextRevisionRecord building, PlanBindingRecord binding,
            long bindingSequence) {
        return ChainContextValue.object(Map.of(
                "taskId", ChainContextValue.text(building.taskId()),
                "instructionId", ref(building.instructionId()),
                "taskFrameId", ref(binding.taskFrameId()),
                "planBindingId", ref(binding.planBindingId()),
                "planBindingEventId", ref(binding.eventId()),
                "planBindingEventSequence",
                ChainContextValue.number(bindingSequence)));
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
