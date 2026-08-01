package com.yanban.api.agent;

/** Deterministic runtime-loop stop signal, independent from model-generated text. */
public enum AgentRuntimeStopSignal {
    NONE,
    TOOL_CALL_BUDGET_EXHAUSTED,
    MAX_STEPS_BUDGET_EXHAUSTED,
    MODEL_OUTPUT_TRUNCATED
}
