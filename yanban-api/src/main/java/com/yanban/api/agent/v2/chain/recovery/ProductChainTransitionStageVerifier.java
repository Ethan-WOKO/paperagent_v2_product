package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;

@FunctionalInterface
interface ProductChainTransitionStageVerifier {
    ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage);
}
