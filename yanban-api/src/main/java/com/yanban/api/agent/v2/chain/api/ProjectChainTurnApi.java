package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import java.util.List;

public interface ProjectChainTurnApi {
    V2NaturalLanguageTurnResponse start(
            long userId, long sessionId,
            V2NaturalLanguageTurnRequest request);

    V2TurnCommandResponse reply(
            long userId, long sessionId, String targetClientRequestId,
            String gapId, V2TurnCommandRequest request);

    V2TurnCommandResponse cancel(
            long userId, long sessionId, String targetClientRequestId,
            V2TurnCancelRequest request);

    V2ProjectTurnResponse get(
            long userId, long sessionId, String rootClientRequestId);

    List<V2ProjectTurnListItem> list(
            long userId, long sessionId, int limit);
}
