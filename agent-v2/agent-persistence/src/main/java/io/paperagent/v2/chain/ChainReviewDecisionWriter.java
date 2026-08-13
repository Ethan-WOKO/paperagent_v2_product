package io.paperagent.v2.chain;

public interface ChainReviewDecisionWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.ReviewDecisionRecord>
            appendReviewDecision(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.ReviewDecisionRecord> decision);
}
