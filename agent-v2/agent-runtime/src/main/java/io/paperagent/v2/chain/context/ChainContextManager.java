package io.paperagent.v2.chain.context;

/** The single context entry point shared by Planner, Executor, Reflector and Answer. */
public interface ChainContextManager {
    ChainContextFreezeOutcome freeze(ChainContextFreezeRequest request);

    ChainFrozenContext recover(String taskId, String contextRevisionId);
}
