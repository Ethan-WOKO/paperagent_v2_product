package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
        V2AdaptiveTurnEntity value = turns
                .findByUserIdAndSessionIdAndClientRequestId(
                        userId, sessionId, clientRequestId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "V2 执行记录不存在"));
        try {
            List<V2AdaptiveTurnResponse.Step> steps = json.readValue(
                    value.stepsJson(), new TypeReference<>() {});
            List<String> paths = json.readValue(
                    value.outputPathsJson(), new TypeReference<>() {});
            return new V2AdaptiveTurnResponse(
                    value.status(), value.route(), value.planId(),
                    value.projectVersion(), steps, value.finalText(),
                    value.candidateArtifactId(), paths, value.errorCode());
        } catch (Exception corrupt) {
            throw new IllegalStateException("V2 adaptive read model is invalid");
        }
    }
}
