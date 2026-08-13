package io.paperagent.v2.chain;

public enum ChainContextModule {
    USER_INSTRUCTION_CHAIN(1, "INSTRUCTION_CHAIN"),
    CONVERSATION_CONTEXT(2, "CONVERSATION"),
    PROJECT_AND_INPUT_MATERIALS(3, "PROJECT_INPUTS"),
    TASK_CONTRACT(4, "TASK_CONTRACT"),
    PLAN_AND_STEP_CONTRACT(5, "PLAN_CONTRACT"),
    TASK_AND_STEP_RUNTIME_STATE(6, "EXECUTION_STATE"),
    CURRENT_STEP_ACTION_TOOLS_AND_ERRORS(7, "ACTION_AND_ERRORS"),
    WORKSPACE_AND_CANDIDATE(8, "WORKSPACE_CANDIDATE"),
    VALIDATION_AND_PUBLISH(9, "VALIDATION_PUBLISH"),
    REVIEW_DECISIONS_AND_PENDING_ITEMS(10, "REVIEW_PENDING"),
    MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE(11, "MEMORY_EVIDENCE"),
    RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS(12, "RUNTIME_CAPABILITY_PERMISSION"),
    MODEL_INVOCATIONS_AND_PROPOSALS(13, "MODEL_HISTORY");

    private final int ordinalCode;
    private final String wireName;

    ChainContextModule(int ordinalCode, String wireName) {
        this.ordinalCode = ordinalCode;
        this.wireName = wireName;
    }

    public int ordinalCode() {
        return ordinalCode;
    }

    public String wireName() {
        return wireName;
    }
}
