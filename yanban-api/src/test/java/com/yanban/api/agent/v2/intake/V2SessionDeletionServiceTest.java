package com.yanban.api.agent.v2.intake;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnDeletionService;
import io.paperagent.v2.chain.ChainSessionDeletionPort;
import org.junit.jupiter.api.Test;

class V2SessionDeletionServiceTest {

    @Test
    void deletesChainThenAdaptiveRowsBeforeIntakesAndFlushesTheBoundary() {
        var chain = mock(ChainSessionDeletionPort.class);
        var adaptiveTurns = mock(V2AdaptiveTurnDeletionService.class);
        var intakes = mock(V2TurnIntakeJpaRepository.class);
        var service = new V2SessionDeletionService(
                chain, adaptiveTurns, intakes);

        service.deleteOwnedSessionData(7L, 45L);

        var order = inOrder(chain, adaptiveTurns, intakes);
        order.verify(chain).deleteOwnedSessionData(7L, 45L);
        order.verify(adaptiveTurns).deleteOwnedSessionData(7L, 45L);
        order.verify(intakes).deleteByUserIdAndSessionId(7L, 45L);
        order.verify(intakes).flush();
    }
}
