package com.yanban.api.agent.v2.workspace;

/**
 * Sanitized product configuration failure for the authenticated V2 Workspace
 * boundary.
 */
public final class AuthenticatedAgentTurnWorkspaceConfigurationException
        extends RuntimeException {

    public enum Code {
        INVALID_ROOT
    }

    private final Code code;

    AuthenticatedAgentTurnWorkspaceConfigurationException(Code code) {
        super("authenticated Agent Workspace configuration failed: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
