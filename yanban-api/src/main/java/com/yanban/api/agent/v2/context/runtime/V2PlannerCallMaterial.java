package com.yanban.api.agent.v2.context.runtime;

import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import java.util.List;

public record V2PlannerCallMaterial(
        String requestId,
        String phase,
        String diagnostic,
        String previousOutputDigest,
        List<ModelMessage> messages
) {
    public V2PlannerCallMaterial {
        if (requestId == null || requestId.isBlank()
                || phase == null || phase.isBlank() || messages == null) {
            throw new IllegalArgumentException("planner call material is invalid");
        }
        if ((diagnostic == null) != (previousOutputDigest == null)) {
            throw new IllegalArgumentException("repair material is incomplete");
        }
        if (previousOutputDigest != null
                && !previousOutputDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("previous output digest is invalid");
        }
        messages = List.copyOf(messages);
    }

    public static V2PlannerCallMaterial ordinary(
            ModelRequest request,
            String phase) {
        return new V2PlannerCallMaterial(request.requestId().value(), phase,
                null, null, request.messages());
    }

    public static V2PlannerCallMaterial repair(
            ModelRequest originalRequest,
            String diagnostic,
            String previousOutputDigest) {
        return new V2PlannerCallMaterial(
                originalRequest.requestId().value() + "-repair-material",
                "format-repair", diagnostic, previousOutputDigest,
                originalRequest.messages().stream()
                        .filter(message -> message.role() != MessageRole.TOOL_FACT)
                        .toList());
    }
}
