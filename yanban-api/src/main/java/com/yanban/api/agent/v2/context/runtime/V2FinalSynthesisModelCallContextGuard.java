package com.yanban.api.agent.v2.context.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.adaptive.FinalSynthesisModelCallGuard;
import com.yanban.api.agent.v2.adaptive.FinalSynthesisModelCallGuardException;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextRevisionDraft;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.api.agent.v2.loop.V2StepModelCallMaterial;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Persists the exact safe final request before the real provider call. */
@Component
public class V2FinalSynthesisModelCallContextGuard
        implements FinalSynthesisModelCallGuard {
    private final AgentTurnProductContextResolver contexts;
    private final V2ContextRevisionService revisions;
    private final V2ContextRevisionOrchestrator orchestrator;
    private final V2ContextStageKeyFactory keys;
    private final ObjectMapper json;
    private final Utf8ByteTokenCounter tokens = new Utf8ByteTokenCounter();

    public V2FinalSynthesisModelCallContextGuard(
            AgentTurnProductContextResolver contexts,
            V2ContextRevisionService revisions,
            V2ContextRevisionOrchestrator orchestrator,
            V2ContextStageKeyFactory keys,
            ObjectMapper json) {
        this.contexts = contexts;
        this.revisions = revisions;
        this.orchestrator = orchestrator;
        this.keys = keys;
        this.json = json;
    }

    @Override
    public ModelRequest requireReady(Call call) {
        try {
            return prepare(call);
        } catch (FinalSynthesisModelCallGuardException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FinalSynthesisModelCallGuardException(
                    "FINAL_SYNTHESIS_CONTEXT_INFRASTRUCTURE_FAILED");
        }
    }

    private ModelRequest prepare(Call call) {
        Long sessionId = contexts.resolve(
                call.userId(), call.turnId()).identity().sessionId();
        ModelRequest request = safeRequest(call.request());
        String digest = V2StepModelCallMaterial.requestDigest(request);
        List<String> tuple = List.of(
                "final-synthesis-model-v1",
                "owner", String.valueOf(call.userId()),
                "session", String.valueOf(sessionId),
                "turn", String.valueOf(call.turnId()),
                "taskFrame", request.taskFrameId()
                        .map(value -> value.value()).orElse("none"),
                "plan", request.planId()
                        .map(value -> value.value()).orElse("none"),
                "planRevision", request.planRevisionId()
                        .map(value -> value.value()).orElse("none"),
                "requestDigest", digest);
        String logical = keys.logicalKey(
                V2ContextStage.FINAL_SYNTHESIS, tuple, "answer");
        var replay = revisions.find(
                call.userId(), sessionId, call.turnId(), logical);
        if (replay.isPresent()) {
            verifyReplay(replay.orElseThrow(), digest,
                    call.userId(), sessionId, call.turnId());
            return request;
        }
        V2ContextRevisionSnapshot parent = revisions.findLatest(
                        call.userId(), sessionId, call.turnId())
                .orElseThrow(() -> failure("PARENT_MISSING"));
        Map<ContextSectionType, V2ContextSectionDraft> inherited =
                baseline(parent.revision());
        V2ContextSectionDraft section = section(
                inherited.get(ContextSectionType.STEP_STATE), digest, request);
        List<V2ContextSectionDraft> sections = new ArrayList<>();
        for (ContextSectionType type : ContextSectionType.values()) {
            sections.add(type == ContextSectionType.STEP_STATE
                    ? section : inherited.get(type));
        }
        boolean ready = section.status() == V2ContextSectionStatus.READY;
        int number = parent.revision().revisionNumber() + 1;
        V2ContextBoundaryResult result = orchestrator.prepare(
                new V2ContextBoundaryRequest(
                        call.userId(), sessionId, call.turnId(), parent.id(),
                        parent.contextDigest(), V2ContextStage.FINAL_SYNTHESIS,
                        tuple, "answer", 1,
                        parent.revision().modelProvider(),
                        parent.revision().model(),
                        parent.revision().contextWindowTokens(),
                        parent.revision().maxOutputTokens(),
                        parent.revision().tokenCounterVersion(),
                        parent.revision().profileVersion(),
                        parent.revision().outputReserveTokens(), sections,
                        null, List.of(
                                new V2ContextPhaseRevision(
                                        V2ContextRevisionStatus.ASSEMBLING,
                                        number),
                                new V2ContextPhaseRevision(ready
                                        ? V2ContextRevisionStatus.READY
                                        : V2ContextRevisionStatus.FAILED,
                                        number + 1))));
        if (!(result instanceof V2ContextBoundaryPrepared prepared)
                || prepared.readyRevision().revision().status()
                        != V2ContextRevisionStatus.READY) {
            throw failure(result instanceof V2ContextBoundaryFailure failed
                    ? failed.code() : "NOT_READY");
        }
        return request;
    }

    private Map<ContextSectionType, V2ContextSectionDraft> baseline(
            V2ContextRevisionDraft value) {
        if (value.status() != V2ContextRevisionStatus.READY
                || value.sections().size() != ContextSectionType.values().length) {
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

    private V2ContextSectionDraft section(
            V2ContextSectionDraft source, String digest,
            ModelRequest request) {
        ObjectNode refs = json.createObjectNode();
        refs.put("callDigest", digest);
        ArrayNode messages = json.createArrayNode();
        request.messages().forEach(message -> {
            ObjectNode value = messages.addObject();
            value.put("role", message.role().name());
            value.put("content", message.content());
        });
        ObjectNode projection = json.createObjectNode();
        projection.put("callDigest", digest);
        projection.set("messages", messages);
        String encoded = write(projection);
        long count = tokens.count(encoded);
        boolean overflow = count > source.tokenLimit();
        return new V2ContextSectionDraft(
                source.type(), source.fixedPercentage(), source.tokenLimit(),
                count, count, overflow
                        ? V2ContextSectionStatus.COMPACTION_REQUIRED
                        : V2ContextSectionStatus.READY,
                write(refs), encoded,
                overflow ? "FINAL_SYNTHESIS_CONTEXT_LIMIT_EXCEEDED" : null);
    }

    private ModelRequest safeRequest(ModelRequest source) {
        List<ModelMessage> messages = source.messages().stream()
                .map(message -> new ModelMessage(
                        message.role(), safe(message.content())))
                .toList();
        return new ModelRequest(
                source.requestId(), source.correlationId(), messages,
                source.availableTools(), source.generationOptions(),
                source.taskFrameId(), source.planId(),
                source.planRevisionId(), source.stepId(),
                source.cancellationRequested());
    }

    private String safe(String value) {
        try {
            return V2RuntimeProjectionSafety.required(
                    value, "finalSynthesisMessage", 32_000);
        } catch (IllegalArgumentException unsafe) {
            return "[unsafe final synthesis material omitted]";
        }
    }

    private void verifyReplay(
            V2ContextRevisionSnapshot snapshot, String digest,
            Long userId, Long sessionId, Long turnId) {
        Map<ContextSectionType, V2ContextSectionDraft> sections =
                baseline(snapshot.revision());
        if (snapshot.revision().stage()
                != V2ContextStage.FINAL_SYNTHESIS) {
            throw failure("REPLAY_CONFLICT");
        }
        JsonNode refs = read(sections.get(
                ContextSectionType.STEP_STATE).sourceRefsJson());
        if (!digest.equals(refs.path("callDigest").asText())) {
            throw failure("REPLAY_CONFLICT");
        }
        V2ContextRevisionSnapshot parent = revisions.findById(
                        userId, sessionId, turnId,
                        snapshot.revision().parentSnapshotId())
                .orElseThrow(() -> failure("REPLAY_PARENT_MISSING"));
        if (!snapshot.revision().parentDigest().equals(
                parent.contextDigest())) {
            throw failure("REPLAY_CONFLICT");
        }
        Map<ContextSectionType, V2ContextSectionDraft> inherited =
                uniqueSections(parent.revision());
        for (ContextSectionType type : ContextSectionType.values()) {
            if (type != ContextSectionType.STEP_STATE
                    && !sections.get(type).equals(inherited.get(type))) {
                throw failure("REPLAY_INHERITANCE_CONFLICT");
            }
        }
    }

    private Map<ContextSectionType, V2ContextSectionDraft> uniqueSections(
            V2ContextRevisionDraft value) {
        if (value.sections().size() != ContextSectionType.values().length) {
            throw failure("REPLAY_CONFLICT");
        }
        Map<ContextSectionType, V2ContextSectionDraft> result =
                new EnumMap<>(ContextSectionType.class);
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
        try { return json.readTree(value); }
        catch (Exception invalid) { throw failure("REPLAY_CONFLICT"); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw failure("ENCODING_FAILED"); }
    }

    private static FinalSynthesisModelCallGuardException failure(String code) {
        return new FinalSynthesisModelCallGuardException(
                "FINAL_SYNTHESIS_CONTEXT_" + code);
    }
}
