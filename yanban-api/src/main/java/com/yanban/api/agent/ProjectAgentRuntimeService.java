package com.yanban.api.agent;

import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Authenticated API adapter that is the only HTTP path permitted to bind a Project to runtime. */
@Service
public class ProjectAgentRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(ProjectAgentRuntimeService.class);
    private static final int CANONICAL_CHUNK_CODE_POINTS = 64;

    private final ProjectService projects;
    private final AgentService agentService;
    private final PlanAgentService planAgentService;
    private final AgentContextSnapshotService contextSnapshots;

    public ProjectAgentRuntimeService(ProjectService projects, AgentService agentService, PlanAgentService planAgentService) {
        this(projects, agentService, planAgentService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectAgentRuntimeService(ProjectService projects, AgentService agentService,
                                      PlanAgentService planAgentService,
                                      AgentContextSnapshotService contextSnapshots) {
        this.projects = projects;
        this.agentService = agentService;
        this.planAgentService = planAgentService;
        this.contextSnapshots = contextSnapshots;
    }

    public AgentSessionResponse createSession(Long userId, Long projectId, CreateSessionRequest request) {
        projects.manifest(userId, projectId);
        return agentService.createProjectSession(userId, projectId, request, "Project #" + projectId);
    }

    public List<AgentSessionResponse> listSessions(Long userId, Long projectId) {
        projects.manifest(userId, projectId);
        return agentService.listProjectSessions(userId, projectId);
    }

    public AgentPlanResponse createPlan(Long userId, Long projectId, Long sessionId, CreateAgentPlanRequest request) {
        projects.manifest(userId, projectId);
        agentService.getOwnedProjectSession(userId, projectId, sessionId);
        log.info("Project Plan request accepted userId={} projectId={} sessionId={} autoExecute={}",
                userId, projectId, sessionId, request == null ? null : request.autoExecute());
        try {
            return planAgentService.createProjectPlan(userId, projectId, sessionId, request);
        } catch (RuntimeException ex) {
            log.warn("Project Plan request failed userId={} projectId={} sessionId={} errorType={} error={}",
                    userId, projectId, sessionId, ex.getClass().getSimpleName(), abbreviate(ex.getMessage()));
            throw ex;
        }
    }

    public SendMessageResponse send(Long userId, Long projectId, Long sessionId, SendMessageRequest request) {
        var manifest = projects.manifest(userId, projectId);
        agentService.getOwnedProjectSession(userId, projectId, sessionId);
        SendMessageResponse response = agentService.sendProjectMessage(userId, sessionId, request,
                new ProjectRuntimeContext(userId, projectId, manifest.version()));
        return projectResponse(response, manifest);
    }

    /**
     * Streams process observations immediately, but releases assistant content only after the Project completion
     * gate has selected its canonical response. Runtime attempt tokens are deliberately suppressed: a bounded
     * repair may execute more than once and must never concatenate unverified attempts in the client bubble.
     */
    public SendMessageResponse sendStreaming(Long userId,
                                             Long projectId,
                                             Long sessionId,
                                             SendMessageRequest request,
                                             Consumer<String> canonicalTokenConsumer,
                                             Consumer<String> processConsumer) {
        var manifest = projects.manifest(userId, projectId);
        agentService.getOwnedProjectSession(userId, projectId, sessionId);
        SendMessageResponse response = agentService.sendProjectMessageStreaming(
                userId,
                sessionId,
                request,
                new ProjectRuntimeContext(userId, projectId, manifest.version()),
                ignoredAttemptToken -> { },
                processConsumer
        );
        SendMessageResponse projected = projectResponse(response, manifest);
        if (projected.success() && canonicalTokenConsumer != null
                && StringUtils.hasText(projected.assistantContent())) {
            emitCanonicalChunks(projected.assistantContent(), canonicalTokenConsumer);
        }
        return projected;
    }

    private void emitCanonicalChunks(String content, Consumer<String> consumer) {
        int start = 0;
        while (start < content.length()) {
            int remainingCodePoints = content.codePointCount(start, content.length());
            int chunkCodePoints = Math.min(CANONICAL_CHUNK_CODE_POINTS, remainingCodePoints);
            int end = content.offsetByCodePoints(start, chunkCodePoints);
            consumer.accept(content.substring(start, end));
            start = end;
        }
    }

    private SendMessageResponse projectResponse(SendMessageResponse response, ProjectManifestResponse manifest) {
        List<ProjectEvidenceResponse> source = response.projectEvidence() == null
                ? List.of() : response.projectEvidence();
        List<ProjectEvidenceResponse> current = source.stream()
                .filter(item -> item.trusted())
                .filter(item -> isSafeRelativePath(item.relativePath()))
                .map(item -> new ProjectEvidenceResponse(item.id(), item.relativePath(), item.hash(), item.version(),
                        item.chunk(), true, manifest.files().stream().anyMatch(file -> file.path().equals(item.relativePath())
                                && file.sha256().equals(item.hash()))))
                .toList();
        return new SendMessageResponse(response.success(), response.assistantContent(), response.steps(), response.errorMessage(),
                response.navigationUrl(), response.messages(), response.debug(), current,
                response.completionStatus(), response.stopReason(), response.outcome(), response.candidateArtifact())
                .withFinalSynthesisInput(response.finalSynthesisInput());
    }

    private boolean isSafeRelativePath(String value) {
        if (!StringUtils.hasText(value) || value.startsWith("/") || value.startsWith("\\")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private String abbreviate(String value) {
        if (!StringUtils.hasText(value)) {
            return "unspecified";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 497) + "...";
    }

    public List<ProjectEvidenceResponse> listPlanEvidence(Long userId, Long projectId, Long planId) {
        return planAgentService.listProjectEvidence(userId, projectId, planId);
    }

    public List<AgentContextSnapshotResponse> listContextSnapshots(Long userId, Long projectId,
                                                                   Long sessionId, Integer limit) {
        projects.manifest(userId, projectId);
        agentService.getOwnedProjectSession(userId, projectId, sessionId);
        if (contextSnapshots == null) {
            throw new IllegalStateException("Context snapshots are not configured");
        }
        return contextSnapshots.listSessionSnapshots(userId, sessionId, limit);
    }

}
