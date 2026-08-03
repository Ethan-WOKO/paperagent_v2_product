package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.api.agent.v2.intake.V2TurnContextAuthorityService;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class V2AdaptiveTurnQueryService {
    private final V2AdaptiveTurnRepository turns;
    private final ObjectMapper json;
    private final V2TurnContextAuthorityService turnAuthority;
    private final V2ContextRevisionService contextRevisions;

    @Autowired
    public V2AdaptiveTurnQueryService(
            V2AdaptiveTurnRepository turns, ObjectMapper json,
            V2TurnContextAuthorityService turnAuthority,
            V2ContextRevisionService contextRevisions) {
        this.turns = turns;
        this.json = json;
        this.turnAuthority = turnAuthority;
        this.contextRevisions = contextRevisions;
    }

    V2AdaptiveTurnQueryService(
            V2AdaptiveTurnRepository turns, ObjectMapper json) {
        this(turns, json, null, null);
    }

    public V2AdaptiveTurnResponse get(
            Long userId, Long sessionId, String clientRequestId) {
        return find(userId, sessionId, clientRequestId)
                .map(V2AdaptiveTurnSnapshot::response)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "V2 adaptive execution was not found"));
    }

    public Optional<V2AdaptiveTurnSnapshot> find(
            Long userId, Long sessionId, String clientRequestId) {
        return turns.findByUserIdAndSessionIdAndClientRequestId(
                        userId, sessionId, clientRequestId)
                .map(this::decode)
                .map(snapshot -> withCurrentContext(
                        userId, sessionId, clientRequestId, snapshot));
    }

    private V2AdaptiveTurnSnapshot withCurrentContext(
            Long userId, Long sessionId, String clientRequestId,
            V2AdaptiveTurnSnapshot snapshot) {
        V2AdaptiveTurnResponse response = snapshot.response();
        if (turnAuthority == null || contextRevisions == null
                || !"RUNNING".equals(response.status())
                || response.steps().stream().noneMatch(
                        step -> "RUNNING".equals(step.status()))) {
            return snapshot;
        }
        Optional<V2AdaptiveTurnResponse.Context> context = turnAuthority
                .find(userId, sessionId, clientRequestId)
                .flatMap(authority -> contextRevisions.findLatest(
                        userId, sessionId, authority.turnId()))
                .flatMap(this::projectContext);
        return context.map(value -> new V2AdaptiveTurnSnapshot(
                        response.withContext(value), snapshot.createdAt(),
                        snapshot.updatedAt()))
                .orElse(snapshot);
    }

    private Optional<V2AdaptiveTurnResponse.Context> projectContext(
            V2ContextRevisionSnapshot snapshot) {
        var revision = snapshot.revision();
        if (revision.stage() == V2ContextStage.PLANNER
                || revision.stage() == V2ContextStage.FINAL_SYNTHESIS) {
            return Optional.empty();
        }
        Optional<V2ContextSectionDraft> stepState = revision.sections().stream()
                .filter(section -> section.type() == ContextSectionType.STEP_STATE)
                .findFirst();
        String stepId = stepState.flatMap(this::stepId).orElse(null);
        if (stepId == null) {
            return Optional.empty();
        }
        List<String> compacted = revision.sections().stream()
                .filter(V2AdaptiveTurnQueryService::isCompactionSection)
                .map(section -> section.type().name())
                .toList();
        return Optional.of(new V2AdaptiveTurnResponse.Context(
                revision.status().name(), stepId, compacted));
    }

    private Optional<String> stepId(V2ContextSectionDraft section) {
        try {
            String value = json.readTree(section.sourceRefsJson())
                    .path("stepId").asText(null);
            return value == null || value.isBlank()
                    ? Optional.empty() : Optional.of(value);
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static boolean isCompactionSection(V2ContextSectionDraft section) {
        return section.status() == V2ContextSectionStatus.COMPACTION_REQUIRED
                || section.status() == V2ContextSectionStatus.FAILED
                || section.tokensAfter() < section.tokensBefore()
                || section.compactionReason() != null;
    }

    private V2AdaptiveTurnSnapshot decode(V2AdaptiveTurnEntity value) {
        try {
            List<V2AdaptiveTurnResponse.Step> steps = json.readValue(
                    value.stepsJson(), new TypeReference<>() {});
            List<String> paths = json.readValue(
                    value.outputPathsJson(), new TypeReference<>() {});
            return new V2AdaptiveTurnSnapshot(
                    new V2AdaptiveTurnResponse(
                            value.status(), value.route(), value.planId(),
                            value.projectVersion(), steps,
                            value.finalText(), value.candidateArtifactId(),
                            paths, value.errorCode()),
                    value.createdAt(), value.updatedAt());
        } catch (Exception corrupt) {
            throw new IllegalStateException(
                    "V2 adaptive read model is invalid");
        }
    }
}
