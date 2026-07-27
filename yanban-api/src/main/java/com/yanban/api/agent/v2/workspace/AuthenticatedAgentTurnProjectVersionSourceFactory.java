package com.yanban.api.agent.v2.workspace;

import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.project.ProjectService;
import io.paperagent.v2.workspace.ProjectVersionSource;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedAgentTurnProjectVersionSourceFactory {

    private static final String PROJECT_SOURCE_PATH = "agentTurn.projectVersion";

    private final AgentTurnProductContextResolver contexts;
    private final ProjectService projects;

    public AuthenticatedAgentTurnProjectVersionSourceFactory(
            AgentTurnProductContextResolver contexts,
            ProjectService projects
    ) {
        this.contexts = contexts;
        this.projects = projects;
    }

    public ProjectVersionSource create(Long authenticatedUserId, Long turnId) {
        VerifiedAgentTurnProductContext context = contexts.resolve(authenticatedUserId, turnId);
        return create(context);
    }

    ProjectVersionSource create(VerifiedAgentTurnProductContext context) {
        if (context == null) {
            throw projectSourceRequired();
        }
        Long projectId = context.identity().projectId();
        String versionId = context.projectVersionId().orElseThrow(
                AuthenticatedAgentTurnProjectVersionSourceFactory::projectSourceRequired);
        if (projectId == null || projectId <= 0) {
            throw projectSourceRequired();
        }
        return new ProductProjectVersionSource(
                context.identity().userId(),
                projectId,
                versionId,
                projects);
    }

    private static AuthenticatedProjectVersionSourceBindingException projectSourceRequired() {
        return new AuthenticatedProjectVersionSourceBindingException(
                AuthenticatedProjectVersionSourceBindingCode.PROJECT_SOURCE_REQUIRED,
                PROJECT_SOURCE_PATH);
    }
}
