package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure semantic values and version vectors for one verified Plan cut. */
final class ProductPlanContractProjectionValues {
    private ProductPlanContractProjectionValues() {
    }

    static Values create(
            List<String> requiredFields,
            PersistedPlanBootstrap bootstrap,
            PlanBindingRecord binding,
            PlanRevision revision,
            PlanStep step,
            ChainStepAuthorityPort.StepEvent activation,
            List<ChainStepAuthorityPort.StepEvent> events,
            ProductChainContractProjectionCodec.Projection encoded,
            long v2EventSequence,
            long eventCut) {
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            fields.put(field, fieldValue(field, bootstrap, revision, step,
                    activation, encoded.value()));
        }
        Map<String, ChainContextValue> source = Map.of(
                "planId", ref(binding.planId()),
                "revisionIdentity", revisionIdentity(revision),
                "checkpoint", checkpoint(bootstrap, events),
                "v2EventSequence", ChainContextValue.number(v2EventSequence),
                "payloadHash", ChainContextValue.text(encoded.sha256()));
        Map<String, ChainContextValue> boundary = Map.of(
                "stableV2PlanCut", stableCut(binding, revision, bootstrap,
                        v2EventSequence, eventCut),
                "chainAuthorityEventCut", ChainContextValue.number(eventCut));
        Map<String, ChainContextValue> parameters = new TreeMap<>();
        parameters.put("planBindingRef", ref(binding.planBindingId()));
        parameters.put("planRevisionRef", ref(revision.id().value()));
        parameters.put("contractDigest", ChainContextValue.text(encoded.sha256()));
        if (step != null) {
            parameters.put("stepRef", ref(step.id().value()));
            parameters.put("activationRef", ref(
                    activation.command().activationEventId()));
        }
        return new Values(source, boundary, parameters, fields);
    }

    private static ChainContextValue fieldValue(
            String field, PersistedPlanBootstrap bootstrap,
            PlanRevision revision, PlanStep step,
            ChainStepAuthorityPort.StepEvent activation,
            ChainContextValue revisionValue) {
        if (field.equals("plan.currentRevisionCompleteOrExplicitEmpty")
                || field.equals("plan.currentRevisionComplete")
                || field.equals("plan.persistentTerminalOrDirectEmpty")) {
            return withRef("planRevisionRef", revision.id().value(),
                    revisionValue);
        }
        if (step == null) {
            throw blocked("required Plan field needs a current Step: " + field);
        }
        return switch (field) {
            case "plan.currentStep" -> withRef("activationRef",
                    activation.command().activationEventId(),
                    ProductChainContractProjectionCodec.planStep(step).value());
            case "plan.dependencies", "plan.directDependencies" ->
                    stepArray(revision, step.dependencies());
            case "plan.affectedSteps" -> stepArray(
                    revision, affected(revision, step.id()));
            case "plan.completionConditions" -> strings(
                    step.completionCriteria());
            case "plan.constraints" -> strings(step.constraints());
            case "plan.scope" -> ChainContextValue.object(Map.of(
                    "stepRef", ref(step.id().value()),
                    "intent", ChainContextValue.text(step.intent()),
                    "expectedOutcome",
                    ChainContextValue.text(step.expectedOutcome()),
                    "mayChangeCandidate",
                    ChainContextValue.bool(step.mayChangeCandidate())));
            case "plan.deliverables" -> strings(
                    bootstrap.taskFrame().deliverables());
            default -> throw blocked("unsupported required Plan field: " + field);
        };
    }

    private static ChainContextValue stepArray(
            PlanRevision revision, Set<PlanStepId> ids) {
        List<ChainContextValue> values = revision.steps().stream()
                .filter(step -> ids.contains(step.id()))
                .map(ProductChainContractProjectionCodec::planStep)
                .map(ProductChainContractProjectionCodec.Projection::value)
                .toList();
        if (values.size() != ids.size()) {
            throw blocked("Plan dependency definitions are incomplete");
        }
        return ChainContextValue.array(values);
    }

    private static Set<PlanStepId> affected(
            PlanRevision revision, PlanStepId source) {
        Set<PlanStepId> result = new HashSet<>(Set.of(source));
        boolean changed;
        do {
            changed = false;
            for (PlanStep candidate : revision.steps()) {
                if (!result.contains(candidate.id())
                        && candidate.dependencies().stream()
                        .anyMatch(result::contains)) {
                    changed |= result.add(candidate.id());
                }
            }
        } while (changed);
        return Set.copyOf(result);
    }

    private static ChainContextValue revisionIdentity(PlanRevision revision) {
        return ChainContextValue.object(Map.of(
                "revisionId", ref(revision.id().value()),
                "revisionNumber", ChainContextValue.number(revision.number()),
                "taskFrameId", ref(revision.taskFrameId().value())));
    }

    private static ChainContextValue checkpoint(
            PersistedPlanBootstrap bootstrap,
            List<ChainStepAuthorityPort.StepEvent> events) {
        var checkpoint = bootstrap.initialCheckpoint();
        List<ChainContextValue> refs = events.stream()
                .map(value -> (ChainContextValue) ref(
                        value.command().eventId())).toList();
        return ChainContextValue.object(Map.of(
                "bootstrapCheckpointVersion",
                ChainContextValue.number(checkpoint.version()),
                "bootstrapLastEventSequence", ChainContextValue.number(
                        checkpoint.checkpoint().lastEventSequence()),
                "observedStepEventRefs", ChainContextValue.array(refs)));
    }

    private static ChainContextValue stableCut(
            PlanBindingRecord binding, PlanRevision revision,
            PersistedPlanBootstrap bootstrap, long v2EventSequence,
            long eventCut) {
        return ChainContextValue.object(Map.of(
                "planBindingRef", ref(binding.planBindingId()),
                "planRevisionRef", ref(revision.id().value()),
                "planRevisionNumber", ChainContextValue.number(revision.number()),
                "bootstrapCheckpointVersion", ChainContextValue.number(
                        bootstrap.initialCheckpoint().version()),
                "v2EventSequence", ChainContextValue.number(v2EventSequence),
                "chainAuthorityEventCut", ChainContextValue.number(eventCut)));
    }

    private static ChainContextValue withRef(
            String name, String authorityRef, ChainContextValue value) {
        return ChainContextValue.object(Map.of(name, ref(authorityRef),
                "contract", value));
    }

    private static ChainContextValue.ArrayValue strings(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ChainContextValue::text).toList());
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                io.paperagent.v2.chain.ChainContextModule
                        .PLAN_AND_STEP_CONTRACT, reason);
    }

    record Values(
            Map<String, ChainContextValue> sourceVersion,
            Map<String, ChainContextValue> readBoundary,
            Map<String, ChainContextValue> parameters,
            Map<String, ChainContextValue> fields) {
    }
}
