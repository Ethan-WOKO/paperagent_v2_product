package com.yanban.api.agent.v2.workspace;

import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.ProjectVersionSource;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates a local V2 Workspace boundary whose Project source is bound to one
 * authenticated Agent turn.
 */
@Service
public final class AuthenticatedAgentTurnWorkspacePortFactory {

    private final AuthenticatedAgentTurnProjectVersionSourceFactory sources;
    private final Path workspaceRoot;

    AuthenticatedAgentTurnWorkspacePortFactory(
            AuthenticatedAgentTurnProjectVersionSourceFactory sources,
            @Value("${yanban.agent.v2.workspace.root:data/agent-v2-workspaces}")
                    String configuredRoot
    ) {
        this.sources = sources;
        this.workspaceRoot = resolveRoot(configuredRoot);
    }

    public WorkspacePort create(Long authenticatedUserId, Long turnId) {
        ProjectVersionSource source = sources.create(authenticatedUserId, turnId);
        try {
            return new LocalWorkspaceProvider(workspaceRoot, source);
        } catch (RuntimeException failure) {
            throw invalidRoot();
        }
    }

    private static Path resolveRoot(String configuredRoot) {
        try {
            if (configuredRoot == null || configuredRoot.isBlank()) {
                throw invalidRoot();
            }
            Path configured = Path.of(configuredRoot);
            Path absolute = configured.isAbsolute()
                    ? configured
                    : Path.of("").toAbsolutePath().resolve(configured);
            return absolute.normalize();
        } catch (AuthenticatedAgentTurnWorkspaceConfigurationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalidRoot();
        }
    }

    private static AuthenticatedAgentTurnWorkspaceConfigurationException invalidRoot() {
        return new AuthenticatedAgentTurnWorkspaceConfigurationException(
                AuthenticatedAgentTurnWorkspaceConfigurationException.Code.INVALID_ROOT);
    }
}
