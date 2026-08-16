package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
final class ReactPlanTurnIntakeService {
    private final ObjectMapper json;
    private final AgentSessionRepository sessions;
    private final ReactPlanTurnIntakeTransactions transactions;
    private final ReactPlanRuntimeService runtime;
    private final ReactPlanRuntimeProperties properties;

    ReactPlanTurnIntakeService(
            ObjectMapper json,
            AgentSessionRepository sessions,
            ReactPlanTurnIntakeTransactions transactions,
            ReactPlanRuntimeService runtime,
            ReactPlanRuntimeProperties properties) {
        this.json = json;
        this.sessions = sessions;
        this.transactions = transactions;
        this.runtime = runtime;
        this.properties = properties;
    }

    JsonNode start(long userId, long sessionId, ReactPlanSessionTaskRequest request) {
        requireProjectSession(userId, sessionId);
        ReactPlanTaskRequest task = normalized(request.taskRequest());
        String digest = requestDigest(request.clientRequestId(), task);
        ResolvedIntake resolved = transactions.find(
                        userId, sessionId, request.clientRequestId())
                .map(existing -> new ResolvedIntake(
                        requireExact(existing, digest), true))
                .orElseGet(() -> createOrReadWinner(
                        userId, sessionId, request.clientRequestId(), digest,
                        task.instruction()));
        ReactPlanTurnIntakeEntity intake = resolved.entity();
        JsonNode accepted = runtime.submit(userId, intake.turnId(), task);
        ObjectNode response = json.createObjectNode();
        response.put("contractVersion", "1.0");
        response.put("replayed", resolved.replayed()
                && accepted.path("replayed").asBoolean(false));
        response.put("turnId", intake.turnId());
        response.put("taskId", intake.taskId());
        response.set("task", accepted.path("task"));
        return response;
    }

    private ResolvedIntake createOrReadWinner(
            long userId, long sessionId, String clientRequestId,
            String digest, String instruction) {
        try {
            return new ResolvedIntake(transactions.create(
                    userId, sessionId, clientRequestId, digest, instruction), false);
        } catch (DataIntegrityViolationException race) {
            return transactions.find(userId, sessionId, clientRequestId)
                    .map(existing -> new ResolvedIntake(
                            requireExact(existing, digest), true))
                    .orElseThrow(() -> race);
        }
    }

    private ReactPlanTurnIntakeEntity requireExact(
            ReactPlanTurnIntakeEntity existing, String digest) {
        if (!existing.requestDigest().equals(digest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "clientRequestId is already bound to different task content");
        }
        return existing;
    }

    private void requireProjectSession(long userId, long sessionId) {
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent session not found"));
        if (session.getScope() != AgentSessionScope.PROJECT
                || session.getProjectId() == null || session.getProjectId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ReAct P1 requires a Project-scoped session");
        }
    }

    private ReactPlanTaskRequest normalized(ReactPlanTaskRequest request) {
        String provider = request.provider() == null
                ? properties.getDefaultProvider() : request.provider();
        String model = request.model() == null
                ? properties.getDefaultModel() : request.model();
        return new ReactPlanTaskRequest(request.instruction(), provider, model);
    }

    private String requestDigest(String clientRequestId, ReactPlanTaskRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("clientRequestId", clientRequestId);
        value.put("instruction", request.instruction());
        value.put("provider", request.provider());
        value.put("model", request.model());
        return ReactPlanCanonicalJson.digest(json, value);
    }

    private record ResolvedIntake(
            ReactPlanTurnIntakeEntity entity, boolean replayed) { }
}
