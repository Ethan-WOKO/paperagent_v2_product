package com.yanban.api.agent.v2.context.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextRevisionDraft;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.api.agent.v2.loop.StepModelCallGuard;
import com.yanban.api.agent.v2.loop.StepModelCallGuardException;
import com.yanban.api.agent.v2.loop.V2StepModelCallMaterial;
import io.paperagent.v2.providers.ModelRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Persists the safe Step decision cut immediately before provider.complete. */
@Component
public class V2StepModelCallContextGuard implements StepModelCallGuard {
    private final AgentTurnProductContextResolver contexts;
    private final V2ContextRevisionService revisions;
    private final V2ContextRevisionOrchestrator orchestrator;
    private final V2SectionCompactor compactor;
    private final V2ContextStageKeyFactory keys;
    private final ObjectMapper json;
    private final Utf8ByteTokenCounter tokens = new Utf8ByteTokenCounter();

    public V2StepModelCallContextGuard(
            AgentTurnProductContextResolver contexts,
            V2ContextRevisionService revisions,
            V2ContextRevisionOrchestrator orchestrator,
            V2SectionCompactor compactor,
            V2ContextStageKeyFactory keys,
            ObjectMapper json) {
        this.contexts = contexts;
        this.revisions = revisions;
        this.orchestrator = orchestrator;
        this.compactor = compactor;
        this.keys = keys;
        this.json = json;
    }

    @Override
    public ModelRequest requireReady(Call call) {
        try {
            return requireReadyInternal(call);
        } catch (StepModelCallGuardException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new StepModelCallGuardException(
                    "STEP_CONTEXT_INFRASTRUCTURE_FAILED");
        }
    }

    private ModelRequest requireReadyInternal(Call call) {
        var identity = contexts.resolve(call.userId(), call.turnId()).identity();
        Long sessionId = identity.sessionId();
        V2StepModelCallMaterial material = call.material();
        String callDigest = material.safeModelRequestDigest();
        V2StepModelCallMaterial.Step step = material.step();
        List<String> tuple = List.of(
                "step-model-v1", "owner", String.valueOf(call.userId()),
                "session", String.valueOf(sessionId),
                "turn", String.valueOf(call.turnId()),
                "taskFrame", step.taskFrameId(),
                "plan", step.planId(),
                "planRevision", step.planRevisionId(),
                "step", step.stepId(),
                "activationEvent", call.activationEventId(),
                "activationSequence", String.valueOf(call.activationSequence()),
                "activationCheckpoint", String.valueOf(
                        call.activatedCheckpointVersion()),
                "checkpointVersion", String.valueOf(step.checkpointVersion()),
                "checkpointSequence", String.valueOf(
                        step.checkpointLastEventSequence()),
                "historyDigest", material.safeHistoryCanonicalDigest(),
                "decisionOrdinal", String.valueOf(material.decisionOrdinal()));
        String logicalKey = keys.logicalKey(
                V2ContextStage.STEP_DECISION, tuple, "decision");
        Optional<V2ContextRevisionSnapshot> replay = revisions.find(
                call.userId(), sessionId, call.turnId(), logicalKey);
        if (replay.isPresent()) {
            return replayRequest(replay.orElseThrow(), call, material);
        }

        V2ContextRevisionSnapshot parent = revisions.findLatest(
                        call.userId(), sessionId, call.turnId())
                .orElseThrow(() -> failure("PARENT_MISSING"));
        V2ContextRevisionDraft baseline = parent.revision();
        Map<ContextSectionType, V2ContextSectionDraft> inherited =
                baseline(baseline);
        V2ContextSectionDraft stepSection = replacement(
                inherited.get(ContextSectionType.STEP_STATE),
                stepRefs(call, material, callDigest), material.step());
        boolean stepOverflow = stepSection.status()
                != V2ContextSectionStatus.READY;
        V2ContextSectionDraft toolSection = replacement(
                inherited.get(ContextSectionType.TOOL_RESULTS),
                toolRefs(material, callDigest), material.history());
        V2ContextSectionDraft toolSectionBeforeCompaction = toolSection;
        boolean compacting = toolSection.status()
                == V2ContextSectionStatus.COMPACTION_REQUIRED
                && !stepOverflow;
        V2SectionCompactionResult compaction = compacting
                ? compactor.compact(toolSection) : null;
        V2StepModelCallMaterial finalMaterial = material;
        if (compaction != null && compaction.success()) {
            finalMaterial = material.withHistory(historyItems(
                    compaction.section().projectionJson()));
            callDigest = finalMaterial.safeModelRequestDigest();
            toolSection = rewrapToolSection(
                    compaction.section(), finalMaterial, callDigest,
                    compaction.keptRefsJson(), compaction.removedRefsJson());
            compaction = rewrapCompaction(compaction, toolSection);
            stepSection = replacement(
                    inherited.get(ContextSectionType.STEP_STATE),
                    stepRefs(call, finalMaterial, callDigest),
                    finalMaterial.step());
        }
        List<V2ContextSectionDraft> sections = new ArrayList<>();
        for (ContextSectionType type : ContextSectionType.values()) {
            sections.add(switch (type) {
                case STEP_STATE -> stepSection;
                case TOOL_RESULTS -> compacting
                        ? toolSectionBeforeCompaction : toolSection;
                default -> inherited.get(type);
            });
        }
        boolean ready = !stepOverflow
                && (!compacting || compaction.success());
        int number = baseline.revisionNumber() + 1;
        List<V2ContextPhaseRevision> phases = new ArrayList<>();
        phases.add(new V2ContextPhaseRevision(
                V2ContextRevisionStatus.ASSEMBLING, number++));
        if (compacting) {
            phases.add(new V2ContextPhaseRevision(
                    V2ContextRevisionStatus.COMPACTION_REQUIRED, number++));
            phases.add(new V2ContextPhaseRevision(
                    V2ContextRevisionStatus.COMPACTING, number++));
        }
        phases.add(new V2ContextPhaseRevision(ready
                ? V2ContextRevisionStatus.READY
                : V2ContextRevisionStatus.FAILED, number));
        V2ContextBoundaryResult result = orchestrator.prepare(
                new V2ContextBoundaryRequest(
                        call.userId(), sessionId, call.turnId(), parent.id(),
                        parent.contextDigest(), V2ContextStage.STEP_DECISION,
                        tuple, "decision", 1,
                        baseline.modelProvider(), baseline.model(),
                        baseline.contextWindowTokens(), baseline.maxOutputTokens(),
                        baseline.tokenCounterVersion(), baseline.profileVersion(),
                        baseline.outputReserveTokens(), sections,
                        compacting ? ContextSectionType.TOOL_RESULTS : null,
                        phases), compaction);
        if (!(result instanceof V2ContextBoundaryPrepared prepared)
                || prepared.readyRevision().revision().status()
                        != V2ContextRevisionStatus.READY) {
            throw failure(result instanceof V2ContextBoundaryFailure value
                    ? value.code() : "NOT_READY");
        }
        return finalMaterial.request();
    }

    private Map<ContextSectionType, V2ContextSectionDraft> baseline(
            V2ContextRevisionDraft value) {
        if (value.status() != V2ContextRevisionStatus.READY
                || value.sections().size() != ContextSectionType.values().length
                || !Utf8ByteTokenCounter.VERSION.equals(value.tokenCounterVersion())) {
            throw failure("PARENT_NOT_READY_NINE_LAYER");
        }
        Map<ContextSectionType, V2ContextSectionDraft> result =
                new EnumMap<>(ContextSectionType.class);
        for (V2ContextSectionDraft section : value.sections()) {
            if (section.status() != V2ContextSectionStatus.READY
                    || result.put(section.type(), section) != null) {
                throw failure("PARENT_NOT_READY_NINE_LAYER");
            }
        }
        if (result.size() != ContextSectionType.values().length) {
            throw failure("PARENT_NOT_READY_NINE_LAYER");
        }
        return result;
    }

    private ObjectNode stepRefs(
            Call call, V2StepModelCallMaterial material, String callDigest) {
        V2StepModelCallMaterial.Step step = material.step();
        ObjectNode refs = json.createObjectNode();
        refs.put("taskFrameId", step.taskFrameId());
        refs.put("planId", step.planId());
        refs.put("planRevisionId", step.planRevisionId());
        refs.put("stepId", step.stepId());
        refs.put("activationEventId", call.activationEventId());
        refs.put("activationSequence", call.activationSequence());
        refs.put("activatedCheckpointVersion",
                call.activatedCheckpointVersion());
        refs.put("checkpointVersion", step.checkpointVersion());
        refs.put("checkpointLastEventSequence",
                step.checkpointLastEventSequence());
        refs.put("safeHistoryCanonicalDigest",
                material.safeHistoryCanonicalDigest());
        refs.put("decisionOrdinal", material.decisionOrdinal());
        refs.put("callDigest", callDigest);
        return refs;
    }

    private ObjectNode toolRefs(
            V2StepModelCallMaterial material, String callDigest) {
        ObjectNode refs = json.createObjectNode();
        refs.put("safeHistoryCanonicalDigest",
                material.safeHistoryCanonicalDigest());
        refs.put("historyCount", material.history().size());
        refs.put("decisionOrdinal", material.decisionOrdinal());
        refs.put("callDigest", callDigest);
        var selected = refs.putArray("selected");
        material.history().forEach(item -> {
            ObjectNode ref = selected.addObject();
            ref.put("toolCallId", item.toolCallId());
            if (item.receiptId() == null) ref.putNull("receiptId");
            else ref.put("receiptId", item.receiptId());
        });
        return refs;
    }

    private V2ContextSectionDraft rewrapToolSection(
            V2ContextSectionDraft compacted,
            V2StepModelCallMaterial material,
            String callDigest,
            String keptRefs,
            String removedRefs) {
        ObjectNode refs = toolRefs(material, callDigest);
        refs.set("kept", read(keptRefs));
        refs.set("removed", read(removedRefs));
        return new V2ContextSectionDraft(
                compacted.type(), compacted.fixedPercentage(),
                compacted.tokenLimit(), compacted.tokensBefore(),
                compacted.tokensAfter(), compacted.status(),
                write(refs), compacted.projectionJson(),
                compacted.compactionReason());
    }

    private V2SectionCompactionResult rewrapCompaction(
            V2SectionCompactionResult value,
            V2ContextSectionDraft section) {
        return new V2SectionCompactionResult(
                value.success(), value.targetTokens(), value.tokensBefore(),
                value.tokensAfter(), value.oldProjectionDigest(),
                value.newProjectionDigest(), value.keptRefsJson(),
                value.removedRefsJson(), section, value.code());
    }

    private List<V2StepModelCallMaterial.HistoryItem> historyItems(
            String projectionJson) {
        try {
            return json.readerForListOf(
                            V2StepModelCallMaterial.HistoryItem.class)
                    .<List<V2StepModelCallMaterial.HistoryItem>>readValue(
                            projectionJson);
        } catch (Exception failure) {
            throw new StepModelCallGuardException(
                    "STEP_CONTEXT_COMPACTED_HISTORY_INVALID");
        }
    }

    private V2ContextSectionDraft replacement(
            V2ContextSectionDraft baseline, Object refs, Object projection) {
        String encoded = write(projection);
        long count = tokens.count(encoded);
        boolean overflow = count > baseline.tokenLimit();
        return new V2ContextSectionDraft(
                baseline.type(), baseline.fixedPercentage(),
                baseline.tokenLimit(), count, count,
                overflow ? V2ContextSectionStatus.COMPACTION_REQUIRED
                        : V2ContextSectionStatus.READY,
                write(refs), encoded,
                overflow ? "STEP_MODEL_SECTION_LIMIT_EXCEEDED" : null);
    }

    private ModelRequest replayRequest(
            V2ContextRevisionSnapshot replay,
            Call call,
            V2StepModelCallMaterial material) {
        V2ContextRevisionDraft value = replay.revision();
        if (value.status() != V2ContextRevisionStatus.READY
                || value.stage() != V2ContextStage.STEP_DECISION
                || value.sections().size() != ContextSectionType.values().length) {
            throw failure("REPLAY_CONFLICT");
        }
        Map<ContextSectionType, V2ContextSectionDraft> unique =
                new EnumMap<>(ContextSectionType.class);
        for (V2ContextSectionDraft section : value.sections()) {
            if (section.status() != V2ContextSectionStatus.READY
                    || unique.put(section.type(), section) != null) {
                throw failure("REPLAY_CONFLICT");
            }
        }
        if (unique.size() != ContextSectionType.values().length) {
            throw failure("REPLAY_CONFLICT");
        }
        V2ContextRevisionSnapshot parent = revisions.findById(
                        call.userId(), value.sessionId(), call.turnId(),
                        value.parentSnapshotId())
                .orElseThrow(() -> failure("REPLAY_PARENT_MISSING"));
        if (!value.parentDigest().equals(parent.contextDigest())) {
            throw failure("REPLAY_CONFLICT");
        }
        Map<ContextSectionType, V2ContextSectionDraft> parentSections =
                replaySections(parent.revision());
        for (ContextSectionType type : ContextSectionType.values()) {
            if (type != ContextSectionType.STEP_STATE
                    && type != ContextSectionType.TOOL_RESULTS
                    && !unique.get(type).equals(parentSections.get(type))) {
                throw failure("REPLAY_INHERITANCE_CONFLICT");
            }
        }
        V2ContextSectionDraft step = unique.get(ContextSectionType.STEP_STATE);
        V2ContextSectionDraft tool = unique.get(ContextSectionType.TOOL_RESULTS);
        V2StepModelCallMaterial rebuilt = material.withHistory(
                historyItems(tool.projectionJson()));
        String callDigest = rebuilt.safeModelRequestDigest();
        JsonNode stepRefs = read(step.sourceRefsJson());
        JsonNode toolRefs = read(tool.sourceRefsJson());
        if (!callDigest.equals(stepRefs.path("callDigest").asText())
                || !callDigest.equals(toolRefs.path("callDigest").asText())
                || !material.safeHistoryCanonicalDigest().equals(
                        stepRefs.path("safeHistoryCanonicalDigest").asText())
                || !material.safeHistoryCanonicalDigest().equals(
                        toolRefs.path("safeHistoryCanonicalDigest").asText())
                || material.decisionOrdinal()
                        != stepRefs.path("decisionOrdinal").asLong(-1)
                || material.decisionOrdinal()
                        != toolRefs.path("decisionOrdinal").asLong(-1)
                || !call.activationEventId().equals(
                        stepRefs.path("activationEventId").asText())
                || call.activationSequence()
                        != stepRefs.path("activationSequence").asLong(-1)
                || call.activatedCheckpointVersion()
                        != stepRefs.path("activatedCheckpointVersion").asLong(-1)) {
            throw failure("REPLAY_CONFLICT");
        }
        return rebuilt.request();
    }

    private Map<ContextSectionType, V2ContextSectionDraft> replaySections(
            V2ContextRevisionDraft value) {
        Map<ContextSectionType, V2ContextSectionDraft> result =
                new EnumMap<>(ContextSectionType.class);
        if (value.sections().size() != ContextSectionType.values().length) {
            throw failure("REPLAY_CONFLICT");
        }
        for (V2ContextSectionDraft section : value.sections()) {
            if (result.put(section.type(), section) != null) {
                throw failure("REPLAY_CONFLICT");
            }
        }
        if (result.size() != ContextSectionType.values().length) {
            throw failure("REPLAY_CONFLICT");
        }
        return result;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw failure("REPLAY_CONFLICT");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new StepModelCallGuardException(
                    "STEP_CONTEXT_ENCODING_FAILED");
        }
    }

    private static StepModelCallGuardException failure(String code) {
        return new StepModelCallGuardException("STEP_CONTEXT_" + code);
    }

}
