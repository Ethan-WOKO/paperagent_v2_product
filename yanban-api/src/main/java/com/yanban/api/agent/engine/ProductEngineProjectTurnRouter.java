package com.yanban.api.agent.engine;

import com.yanban.api.agent.v2.chain.api.ProjectChainTurnApi;
import com.yanban.api.agent.v2.chain.api.ProjectChainTurnCoordinator;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnListItem;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnResponse;
import com.yanban.api.agent.v2.chain.api.V2TurnCancelRequest;
import com.yanban.api.agent.v2.chain.api.V2TurnCommandRequest;
import com.yanban.api.agent.v2.chain.api.V2TurnCommandResponse;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Primary
@Service
final class ProductEngineProjectTurnRouter implements ProjectChainTurnApi {
    private final ProductEngineProperties properties;
    private final ProjectChainTurnCoordinator legacy;
    private final ExternalProductEngineTurnService external;

    ProductEngineProjectTurnRouter(ProductEngineProperties properties,
                                   ProjectChainTurnCoordinator legacy,
                                   ExternalProductEngineTurnService external) {
        this.properties = properties; this.legacy = legacy; this.external = external;
    }

    @Override
    public V2NaturalLanguageTurnResponse start(long userId, long sessionId,
                                                V2NaturalLanguageTurnRequest request) {
        if (external.owns(userId, sessionId, request.clientRequestId())) {
            return properties.selectedMode().external()
                    ? external.start(userId, sessionId, request)
                    : external.persistedStart(userId, sessionId, request);
        }
        return properties.selectedMode().external()
                ? external.start(userId, sessionId, request)
                : legacy.start(userId, sessionId, request);
    }

    @Override
    public V2TurnCommandResponse reply(long userId, long sessionId, String targetClientRequestId,
                                       String gapId, V2TurnCommandRequest request) {
        if (!external.owns(userId, sessionId, targetClientRequestId)) {
            return legacy.reply(userId, sessionId, targetClientRequestId, gapId, request);
        }
        requireExternalMode();
        return external.reply(userId, sessionId, targetClientRequestId, gapId, request);
    }

    @Override
    public V2TurnCommandResponse cancel(long userId, long sessionId, String targetClientRequestId,
                                        V2TurnCancelRequest request) {
        if (!external.owns(userId, sessionId, targetClientRequestId)) {
            return legacy.cancel(userId, sessionId, targetClientRequestId, request);
        }
        requireExternalMode();
        return external.cancel(userId, sessionId, targetClientRequestId, request);
    }

    @Override
    public V2ProjectTurnResponse get(long userId, long sessionId, String rootClientRequestId) {
        if (!external.owns(userId, sessionId, rootClientRequestId)) {
            return legacy.get(userId, sessionId, rootClientRequestId);
        }
        return properties.selectedMode().external()
                ? external.get(userId, sessionId, rootClientRequestId)
                : external.persistedGet(userId, sessionId, rootClientRequestId);
    }

    @Override
    public List<V2ProjectTurnListItem> list(long userId, long sessionId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return Stream.concat(legacy.list(userId, sessionId, bounded).stream(),
                        external.list(userId, sessionId, bounded).stream())
                .sorted(Comparator.comparing(V2ProjectTurnListItem::createdAt).reversed()
                        .thenComparing(V2ProjectTurnListItem::clientRequestId))
                .limit(bounded)
                .toList();
    }

    private void requireExternalMode() {
        if (!properties.selectedMode().external()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ENGINE_MODE_DISABLED");
        }
    }
}
