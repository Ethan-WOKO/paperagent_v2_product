package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class V2AdaptiveExecutionStore {
    private final V2AdaptiveTurnRepository turns;
    private final ObjectMapper json;

    public V2AdaptiveExecutionStore(
            V2AdaptiveTurnRepository turns, ObjectMapper json) {
        this.turns = turns;
        this.json = json;
    }

    @Transactional
    public void open(
            Long intakeId, Long userId, Long sessionId, String requestId,
            String planId, String projectVersion,
            List<V2AdaptiveTurnResponse.Step> steps) {
        var existing = turns.findByUserIdAndSessionIdAndClientRequestId(
                userId, sessionId, requestId);
        if (existing.isPresent()) {
            if (!planId.equals(existing.orElseThrow().planId())) {
                throw new IllegalStateException(
                        "adaptive request authority mismatch");
            }
            return;
        }
        turns.saveAndFlush(V2AdaptiveTurnEntity.running(
                intakeId, userId, sessionId, requestId, planId,
                projectVersion, write(steps), Instant.now()));
    }

    @Transactional
    public void finish(
            Long userId, Long sessionId, String requestId,
            String status, List<V2AdaptiveTurnResponse.Step> steps,
            String finalText, String artifactId, List<String> paths,
            String errorCode, int reflections, int replans, int repairs) {
        var value = turns.findByUserIdAndSessionIdAndClientRequestId(
                        userId, sessionId, requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "adaptive execution was not opened"));
        value.finish(status, write(steps), finalText, artifactId,
                write(paths), errorCode, reflections, replans, repairs,
                Instant.now());
        turns.saveAndFlush(value);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("adaptive projection encoding failed");
        }
    }
}
