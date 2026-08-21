package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
public final class AgentEngineTaskGrantService {
    private static final String PREFIX = "grant.";
    private final ObjectMapper json;
    private final EngineGatewayProperties properties;
    private final Clock clock;
    private final AgentTurnProductContextResolver contexts;

    @Autowired
    public AgentEngineTaskGrantService(
            ObjectMapper json,
            EngineGatewayProperties properties,
            AgentTurnProductContextResolver contexts) {
        this(json, properties, contexts, Clock.systemUTC());
    }

    AgentEngineTaskGrantService(
            ObjectMapper json,
            EngineGatewayProperties properties,
            AgentTurnProductContextResolver contexts,
            Clock clock) {
        this.json = json;
        this.properties = properties;
        this.contexts = contexts;
        this.clock = clock;
    }

    public EngineTaskGrant issue(
            String taskId, String requestDigest, long authenticatedUserId, long turnId,
            String modelProvider, String modelName) {
        return issue(taskId, requestDigest, authenticatedUserId, turnId,
                modelProvider, modelName, List.of());
    }

    public EngineTaskGrant issue(
            String taskId, String requestDigest, long authenticatedUserId, long turnId,
            String modelProvider, String modelName,
            List<EngineModelRouteCandidate> modelFallbacks) {
        VerifiedAgentTurnProductContext context = contexts.resolve(authenticatedUserId, turnId);
        if (!"AGENT_TURN".equals(context.identity().source())
                || !String.valueOf(turnId).equals(context.identity().sourceId())
                || !Long.valueOf(authenticatedUserId).equals(context.identity().userId())
                || context.identity().sessionId() == null || context.identity().sessionId() <= 0
                || context.identity().projectId() == null || context.identity().projectId() <= 0
                || context.projectVersionId().isEmpty()) {
            throw new IllegalArgumentException("engine task Project authority is invalid");
        }
        Instant expiresAt = clock.instant().plus(properties.getTaskGrantTtl());
        EngineTaskAuthority authority = new EngineTaskAuthority(
                taskId, requestDigest, authenticatedUserId, turnId,
                context.identity().sessionId(), context.identity().projectId(),
                context.projectVersionId().orElseThrow(), true, true, true,
                modelProvider, modelName, modelFallbacks, expiresAt);
        byte[] payload = write(authority);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(payload));
        return new EngineTaskGrant(PREFIX + body + "." + signature, expiresAt);
    }

    EngineTaskAuthority verify(String authorization, String taskId, boolean sandbox) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw EngineGatewayException.unauthorized("TASK_GRANT_REQUIRED");
        }
        String token = authorization.substring("Bearer ".length());
        if (!token.startsWith(PREFIX) || token.length() > 4096) {
            throw EngineGatewayException.unauthorized("TASK_GRANT_INVALID");
        }
        String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2) {
            throw EngineGatewayException.unauthorized("TASK_GRANT_INVALID");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] supplied = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), supplied)) {
                throw EngineGatewayException.unauthorized("TASK_GRANT_INVALID");
            }
            EngineTaskAuthority authority = json.readValue(payload, EngineTaskAuthority.class);
            if (!authority.taskId().equals(taskId)) {
                throw EngineGatewayException.forbidden("TASK_GRANT_WRONG_TASK");
            }
            if (!authority.expiresAt().isAfter(clock.instant())) {
                throw EngineGatewayException.unauthorized("TASK_GRANT_EXPIRED");
            }
            if (!authority.readProject() || sandbox && !authority.executeSandbox()) {
                throw EngineGatewayException.forbidden("TASK_GRANT_PERMISSION_DENIED");
            }
            return authority;
        } catch (EngineGatewayException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw EngineGatewayException.unauthorized("TASK_GRANT_INVALID");
        }
    }

    EngineTaskAuthority verifyWorkspaceWrite(String authorization, String taskId) {
        EngineTaskAuthority authority = verify(authorization, taskId, false);
        if (!authority.writeWorkspace()) {
            throw EngineGatewayException.forbidden("TASK_GRANT_PERMISSION_DENIED");
        }
        return authority;
    }

    private byte[] write(EngineTaskAuthority authority) {
        try {
            return json.writeValueAsBytes(authority);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("task grant serialization failed", impossible);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getTaskGrantSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception impossible) {
            throw new IllegalStateException("task grant signing failed", impossible);
        }
    }
}
