package com.yanban.api.agent.v2.workspace;

import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.ProjectVersionSource;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<WorkspaceKey, WorkspacePort> activeTurns =
            new ConcurrentHashMap<>();

    AuthenticatedAgentTurnWorkspacePortFactory(
            AuthenticatedAgentTurnProjectVersionSourceFactory sources,
            @Value("${yanban.agent.v2.workspace.root:data/agent-v2-workspaces}")
                    String configuredRoot
    ) {
        this.sources = sources;
        this.workspaceRoot = resolveRoot(configuredRoot);
    }

    public WorkspacePort create(Long authenticatedUserId, Long turnId) {
        WorkspaceKey key = new WorkspaceKey(authenticatedUserId, turnId);
        return activeTurns.computeIfAbsent(key, ignored -> {
            ProjectVersionSource source = sources.create(
                    authenticatedUserId, turnId);
            return create(source);
        });
    }

    WorkspacePort create(VerifiedAgentTurnProductContext context) {
        WorkspaceKey key = workspaceKey(context);
        return activeTurns.computeIfAbsent(
                key,
                ignored -> create(sources.create(context)));
    }

    private static WorkspaceKey workspaceKey(
            VerifiedAgentTurnProductContext context
    ) {
        if (context == null
                || !"AGENT_TURN".equals(context.identity().source())) {
            throw new IllegalArgumentException(
                    "authenticated Workspace identity is invalid");
        }
        try {
            return new WorkspaceKey(
                    context.identity().userId(),
                    Long.valueOf(context.identity().sourceId()));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "authenticated Workspace identity is invalid");
        }
    }

    private WorkspacePort create(ProjectVersionSource source) {
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

    private record WorkspaceKey(Long userId, Long turnId) {
        private WorkspaceKey {
            if (userId == null || userId <= 0
                    || turnId == null || turnId <= 0) {
                throw new IllegalArgumentException(
                        "authenticated Workspace identity is invalid");
            }
        }
    }
}
