package com.yanban.api.agent.v2.loop;

/** Sanitized protocol failure at a collaborator boundary. */
public final class PersistentPlanAgentLoopException
        extends RuntimeException {
    private final String stage;

    PersistentPlanAgentLoopException(String stage) {
        super("Persistent Plan Agent Loop failed at " + stage);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
