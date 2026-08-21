package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.api.agent.reactplan.gateway.EngineModelRouteCandidate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ReactPlanCheckpointModelRoutes {
    private static final int MAX_FALLBACKS = 7;

    private ReactPlanCheckpointModelRoutes() { }

    static List<EngineModelRouteCandidate> fallbacks(JsonNode model) {
        JsonNode frozen = model.path("fallbacks");
        if (frozen.isMissingNode() || frozen.isNull()) return List.of();
        if (!frozen.isArray() || frozen.size() > MAX_FALLBACKS) {
            throw invalid();
        }
        List<EngineModelRouteCandidate> result = new ArrayList<>();
        Set<EngineModelRouteCandidate> distinct = new LinkedHashSet<>();
        for (JsonNode candidate : frozen) {
            if (!candidate.isObject()
                    || !candidate.path("provider").isTextual()
                    || !candidate.path("model").isTextual()) {
                throw invalid();
            }
            try {
                distinct.add(new EngineModelRouteCandidate(
                        candidate.path("provider").asText(),
                        candidate.path("model").asText()));
            } catch (IllegalArgumentException invalid) {
                throw invalid();
            }
        }
        result.addAll(distinct);
        return List.copyOf(result);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "CHECKPOINT_MODEL_FALLBACKS_INVALID");
    }
}
