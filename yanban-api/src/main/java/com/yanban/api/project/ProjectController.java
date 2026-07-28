package com.yanban.api.project;

import java.util.List;
import com.yanban.api.agent.ProjectAgentRuntimeService;
import com.yanban.api.agent.SendMessageRequest;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.agent.AgentPlanResponse;
import com.yanban.api.agent.CreateAgentPlanRequest;
import com.yanban.api.agent.ProjectEvidenceResponse;
import com.yanban.api.agent.AgentSessionResponse;
import com.yanban.api.agent.CreateSessionRequest;
import com.yanban.api.agent.AgentContextSnapshotResponse;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisRequest;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisResponse;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectAgentRuntimeService projectAgentRuntimeService;
    private final ProjectUploadService projectUploadService;
    private final ProjectRevisionWorkflowService revisionWorkflow;
    private final CandidateSandboxValidationService candidateValidations;
    private final V2ProjectAnalysisService v2ProjectAnalysis;

    /** Compatibility constructor for focused existing controller tests. */
    public ProjectController(ProjectService projectService) {
        this(projectService, null, null, null, java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectAgentRuntimeService projectAgentRuntimeService) {
        this(projectService, projectAgentRuntimeService, null, null,
                java.util.Optional.empty(), java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectAgentRuntimeService projectAgentRuntimeService,
                             ProjectUploadService projectUploadService) {
        this(projectService, projectAgentRuntimeService, projectUploadService,
                null, java.util.Optional.empty(), java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectAgentRuntimeService projectAgentRuntimeService,
                             ProjectUploadService projectUploadService,
                             ProjectRevisionWorkflowService revisionWorkflow) {
        this(projectService, projectAgentRuntimeService, projectUploadService,
                revisionWorkflow, java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectAgentRuntimeService projectAgentRuntimeService,
                             ProjectUploadService projectUploadService,
                             ProjectRevisionWorkflowService revisionWorkflow,
                             java.util.Optional<CandidateSandboxValidationService> candidateValidations) {
        this(projectService, projectAgentRuntimeService, projectUploadService,
                revisionWorkflow, candidateValidations,
                java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectController(ProjectService projectService,
                             ProjectAgentRuntimeService projectAgentRuntimeService,
                             ProjectUploadService projectUploadService,
                             ProjectRevisionWorkflowService revisionWorkflow,
                             java.util.Optional<CandidateSandboxValidationService> candidateValidations,
                             java.util.Optional<V2ProjectAnalysisService> v2ProjectAnalysis) {
        this.projectService = projectService;
        this.projectAgentRuntimeService = projectAgentRuntimeService;
        this.projectUploadService = projectUploadService;
        this.revisionWorkflow = revisionWorkflow;
        this.candidateValidations = candidateValidations.orElse(null);
        this.v2ProjectAnalysis = v2ProjectAnalysis.orElse(null);
    }

    @GetMapping
    public List<ProjectSummaryResponse> list(@AuthenticationPrincipal(expression = "id") Long userId) {
        return projectService.list(userId);
    }

    /** Browser folder import: files are copied into server-owned storage and never mutate the source folder. */
    @org.springframework.web.bind.annotation.PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ProjectSummaryResponse upload(@AuthenticationPrincipal(expression = "id") Long userId,
                                         @RequestParam String name,
                                         @RequestParam List<String> includeRules,
                                         @RequestParam(required = false) List<String> ignoreRules,
                                         @org.springframework.web.bind.annotation.RequestPart("files") List<MultipartFile> files) {
        if (projectUploadService == null) {
            throw new IllegalStateException("Project upload is not configured");
        }
        return ProjectSummaryResponse.from(projectUploadService.upload(userId, name, includeRules,
                ignoreRules == null ? List.of() : ignoreRules, files));
    }

    /** Removes only the authenticated user's Project binding; it never deletes files from the bound folder. */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal(expression = "id") Long userId,
                       @PathVariable Long projectId) {
        projectService.delete(userId, projectId);
    }

    @GetMapping("/{projectId}/manifest")
    public ProjectManifestResponse manifest(@AuthenticationPrincipal(expression = "id") Long userId,
                                            @PathVariable Long projectId) {
        return projectService.manifest(userId, projectId);
    }

    @PostMapping("/{projectId}/candidates/{artifactId}/applications")
    public ProjectRevisionOperationResponse applyCandidate(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long artifactId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ApplyCandidateRequest request) {
        requireRevisionWorkflow();
        return revisionWorkflow.applyCandidate(userId, projectId, artifactId, idempotencyKey, ifMatch, request);
    }

    @PostMapping("/{projectId}/candidates/{artifactId}/validations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CandidateValidationResponse validateCandidate(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long artifactId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody CreateCandidateValidationRequest request) {
        return requireCandidateValidations().create(userId, projectId, artifactId, idempotencyKey, ifMatch, request);
    }

    @GetMapping("/{projectId}/candidates/{artifactId}/validations")
    public List<CandidateValidationResponse> candidateValidations(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long artifactId) {
        return requireCandidateValidations().list(userId, projectId, artifactId);
    }

    @PostMapping("/{projectId}/candidate-validations/{validationId}/cancel")
    public CandidateValidationResponse cancelCandidateValidation(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable String validationId) {
        return requireCandidateValidations().cancel(userId, projectId, validationId);
    }

    @PostMapping("/{projectId}/candidate-validations/{validationId}/reject")
    public CandidateValidationResponse rejectCandidateValidation(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable String validationId) {
        return requireCandidateValidations().reject(userId, projectId, validationId);
    }

    @GetMapping("/{projectId}/revisions")
    public List<ProjectRevisionResponse> revisions(@AuthenticationPrincipal(expression = "id") Long userId,
                                                   @PathVariable Long projectId) {
        requireRevisionWorkflow();
        return revisionWorkflow.listRevisions(userId, projectId);
    }

    @PostMapping("/{projectId}/revisions/{revisionId}/rollback")
    public ProjectRevisionOperationResponse rollback(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long revisionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch) {
        requireRevisionWorkflow();
        return revisionWorkflow.rollback(userId, projectId, revisionId, idempotencyKey, ifMatch);
    }

    @GetMapping("/{projectId}/revisions/{revisionId}/export")
    public ResponseEntity<StreamingResponseBody> exportRevision(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long revisionId) {
        requireRevisionWorkflow();
        String filename = revisionWorkflow.exportFilename(userId, projectId, revisionId);
        StreamingResponseBody body = output -> revisionWorkflow.exportRevision(userId, projectId, revisionId, output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    @GetMapping("/{projectId}/agent/sessions")
    public List<AgentSessionResponse> listProjectSessions(@AuthenticationPrincipal(expression = "id") Long userId,
                                                          @PathVariable Long projectId) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.listSessions(userId, projectId);
    }

    @GetMapping("/{projectId}/agent/sessions/{sessionId}/context-snapshots")
    public List<AgentContextSnapshotResponse> listProjectContextSnapshots(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "20") Integer limit) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.listContextSnapshots(userId, projectId, sessionId, limit);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AgentSessionResponse createProjectSession(@AuthenticationPrincipal(expression = "id") Long userId,
                                                     @PathVariable Long projectId,
                                                     @Valid @org.springframework.web.bind.annotation.RequestBody CreateSessionRequest request) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.createSession(userId, projectId, request);
    }

    @GetMapping("/{projectId}/files/read")
    public ProjectFileResponse read(@AuthenticationPrincipal(expression = "id") Long userId,
                                    @PathVariable Long projectId,
                                    @RequestParam String path) {
        return projectService.readFile(userId, projectId, path);
    }

    @GetMapping("/{projectId}/search")
    public List<ProjectSearchHit> search(@AuthenticationPrincipal(expression = "id") Long userId,
                                          @PathVariable Long projectId,
                                          @RequestParam String query,
                                          @RequestParam(required = false) Integer maxResults) {
        return projectService.search(userId, projectId, query, maxResults);
    }

    /** Project binding comes from this authenticated route, never from chat JSON or model content. */
    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions/{sessionId}/messages")
    public SendMessageResponse sendProjectMessage(@AuthenticationPrincipal(expression = "id") Long userId,
                                                  @PathVariable Long projectId,
                                                  @PathVariable Long sessionId,
                                                  @Valid @org.springframework.web.bind.annotation.RequestBody SendMessageRequest request) {
        if (projectAgentRuntimeService == null) {
            throw new IllegalStateException("Project runtime is not configured");
        }
        return projectAgentRuntimeService.send(userId, projectId, sessionId, request);
    }

    @PostMapping("/{projectId}/agent/sessions/{sessionId}/v2/read-analysis-turns")
    public V2ProjectAnalysisResponse startV2ProjectAnalysis(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long sessionId,
            @RequestBody V2ProjectAnalysisRequest request) {
        if (v2ProjectAnalysis == null) {
            throw new IllegalStateException(
                    "V2 Project analysis is not configured");
        }
        return v2ProjectAnalysis.execute(
                userId, projectId, sessionId, request);
    }

    @GetMapping("/{projectId}/agent/sessions/{sessionId}/v2/read-analysis-turns/{clientRequestId}")
    public V2ProjectAnalysisResponse readV2ProjectAnalysis(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable Long projectId,
            @PathVariable Long sessionId,
            @PathVariable String clientRequestId) {
        if (v2ProjectAnalysis == null) {
            throw new IllegalStateException(
                    "V2 Project analysis is not configured");
        }
        return v2ProjectAnalysis.read(
                userId, projectId, sessionId, clientRequestId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions/{sessionId}/plans")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AgentPlanResponse createProjectPlan(@AuthenticationPrincipal(expression = "id") Long userId,
                                               @PathVariable Long projectId, @PathVariable Long sessionId,
                                               @Valid @org.springframework.web.bind.annotation.RequestBody CreateAgentPlanRequest request) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.createPlan(userId, projectId, sessionId, request);
    }

    @GetMapping("/{projectId}/agent/plans/{planId}/evidence")
    public List<ProjectEvidenceResponse> listProjectPlanEvidence(@AuthenticationPrincipal(expression = "id") Long userId,
                                                                  @PathVariable Long projectId,
                                                                  @PathVariable Long planId) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.listPlanEvidence(userId, projectId, planId);
    }

    private void requireRevisionWorkflow() {
        if (revisionWorkflow == null) throw new IllegalStateException("Project revision workflow is not configured");
    }

    private CandidateSandboxValidationService requireCandidateValidations() {
        if (candidateValidations == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Candidate sandbox validation is disabled or unavailable");
        }
        return candidateValidations;
    }
}
