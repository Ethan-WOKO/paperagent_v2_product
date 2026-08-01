package com.yanban.api.agent;

import com.yanban.api.project.ProjectService;
import java.util.List;
import org.springframework.stereotype.Service;

/** Authenticated Project-to-session binding without legacy Agent dispatch. */
@Service
public class ProjectSessionService {
    private final ProjectService projects;
    private final AgentSessionService sessions;

    public ProjectSessionService(
            ProjectService projects,
            AgentSessionService sessions) {
        this.projects = projects;
        this.sessions = sessions;
    }

    public AgentSessionResponse createSession(
            Long userId,
            Long projectId,
            CreateSessionRequest request) {
        projects.manifest(userId, projectId);
        return sessions.createProjectSession(
                userId, projectId, request, "Project #" + projectId);
    }

    public List<AgentSessionResponse> listSessions(Long userId, Long projectId) {
        projects.manifest(userId, projectId);
        return sessions.listProjectSessions(userId, projectId);
    }
}
