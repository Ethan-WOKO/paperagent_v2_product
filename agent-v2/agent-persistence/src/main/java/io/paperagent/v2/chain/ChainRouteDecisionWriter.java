package io.paperagent.v2.chain;

public interface ChainRouteDecisionWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.RouteDecisionRecord>
            appendRouteDecision(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.RouteDecisionRecord> decision);
}
