package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class V2AdaptiveTurnQueryService {
    private final V2AdaptiveTurnRepository turns;
    private final ObjectMapper json;

    public V2AdaptiveTurnQueryService(
            V2AdaptiveTurnRepository turns, ObjectMapper json) {
        this.turns = turns;
        this.json = json;
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
                .map(this::decode);
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
