package io.paperagent.v2.chain;

public enum ChainProposalKind {
    PLANNER_DIRECT_ROUTE(ChainRole.PLANNER, "DIRECT_ROUTE"),
    PLANNER_PERSISTENT_PLAN(ChainRole.PLANNER, "PERSISTENT_PLAN"),
    PLANNER_PLAN_REVISION(ChainRole.PLANNER, "PLAN_REVISION"),
    PLANNER_NEED_USER_INPUT(ChainRole.PLANNER, "NEED_USER_INPUT"),
    PLANNER_NEED_PERMISSION(ChainRole.PLANNER, "NEED_PERMISSION"),
    PLANNER_USER_INSTRUCTION_DISPOSITION(ChainRole.PLANNER, "USER_INSTRUCTION_DISPOSITION"),
    PLANNER_PLANNING_BLOCKED(ChainRole.PLANNER, "PLANNING_BLOCKED"),

    EXECUTOR_TOOL_ACTION(ChainRole.EXECUTOR, "TOOL_ACTION"),
    EXECUTOR_WORKSPACE_CHANGE(ChainRole.EXECUTOR, "WORKSPACE_CHANGE"),
    EXECUTOR_STEP_RESULT(ChainRole.EXECUTOR, "STEP_RESULT"),
    EXECUTOR_STEP_BLOCKED(ChainRole.EXECUTOR, "STEP_BLOCKED"),

    REFLECTOR_CONTINUE_STEP(ChainRole.REFLECTOR, "CONTINUE_STEP"),
    REFLECTOR_ACCEPT_STEP(ChainRole.REFLECTOR, "ACCEPT_STEP"),
    REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE(ChainRole.REFLECTOR, "ACCEPT_STEP_AND_READY_TO_FINALIZE"),
    REFLECTOR_REPLAN_REQUIRED(ChainRole.REFLECTOR, "REPLAN_REQUIRED"),
    REFLECTOR_NEED_USER_INPUT(ChainRole.REFLECTOR, "NEED_USER_INPUT"),
    REFLECTOR_NEED_PERMISSION(ChainRole.REFLECTOR, "NEED_PERMISSION"),
    REFLECTOR_READY_TO_FINALIZE(ChainRole.REFLECTOR, "READY_TO_FINALIZE"),
    REFLECTOR_TASK_FAILED(ChainRole.REFLECTOR, "TASK_FAILED"),

    ANSWER_DIRECT_ANSWER(ChainRole.ANSWER, "DIRECT_ANSWER"),
    ANSWER_ESCALATE_TO_PERSISTENT(ChainRole.ANSWER, "ESCALATE_TO_PERSISTENT"),
    ANSWER_USER_QUESTION(ChainRole.ANSWER, "USER_QUESTION"),
    ANSWER_STATUS_OR_FAILURE(ChainRole.ANSWER, "STATUS_OR_FAILURE"),
    ANSWER_FINAL_DELIVERY(ChainRole.ANSWER, "FINAL_DELIVERY"),
    ANSWER_DELIVERY_BLOCKED(ChainRole.ANSWER, "DELIVERY_BLOCKED");

    private final ChainRole role;
    private final String wireName;

    ChainProposalKind(ChainRole role, String wireName) {
        this.role = role;
        this.wireName = wireName;
    }

    public ChainRole role() {
        return role;
    }

    public String wireName() {
        return wireName;
    }

    public static ChainProposalKind resolve(ChainRole role, String wireName) {
        for (ChainProposalKind kind : values()) {
            if (kind.role == role && kind.wireName.equals(wireName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown proposal kind " + wireName + " for role " + role);
    }
}
