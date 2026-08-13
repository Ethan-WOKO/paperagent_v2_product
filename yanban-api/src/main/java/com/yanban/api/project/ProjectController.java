package com.yanban.api.project;

import java.util.List;
import com.yanban.api.agent.ProjectSessionService;
import com.yanban.api.agent.AgentSessionResponse;
import com.yanban.api.agent.CreateSessionRequest;
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
    private final ProjectSessionService projectSessions;
    private final ProjectUploadService projectUploadService;
    private final ProjectRevisionWorkflowService revisionWorkflow;
    private final CandidateSandboxValidationService candidateValidations;

    /** Compatibility constructor for focused existing controller tests. */
    public ProjectController(ProjectService projectService) {
        this(projectService, null, null, null, java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectSessionService projectSessions) {
        this(projectService, projectSessions, null, null,
                java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectSessionService projectSessions,
                             ProjectUploadService projectUploadService) {
        this(projectService, projectSessions, projectUploadService,
                null, java.util.Optional.empty());
    }

    public ProjectController(ProjectService projectService, ProjectSessionService projectSessions,
                             ProjectUploadService projectUploadService,
                             ProjectRevisionWorkflowService revisionWorkflow) {
        this(projectService, projectSessions, projectUploadService,
                revisionWorkflow, java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectController(ProjectService projectService,
                             ProjectSessionService projectSessions,
                             ProjectUploadService projectUploadService,
                             ProjectRevisionWorkflowService revisionWorkflow,
                             java.util.Optional<CandidateSandboxValidationService> candidateValidations) {
        this.projectService = projectService;
        this.projectSessions = projectSessions;
        this.projectUploadService = projectUploadService;
        this.revisionWorkflow = revisionWorkflow;
        this.candidateValidations = candidateValidations.orElse(null);
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
        if (projectSessions == null) {
            projectService.delete(userId, projectId);
            return;
        }
        projectSessions.deleteProject(userId, projectId);
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
        if (projectSessions == null) throw new IllegalStateException("Project sessions are not configured");
        return projectSessions.listSessions(userId, projectId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AgentSessionResponse createProjectSession(@AuthenticationPrincipal(expression = "id") Long userId,
                                                     @PathVariable Long projectId,
                                                     @Valid @org.springframework.web.bind.annotation.RequestBody CreateSessionRequest request) {
        if (projectSessions == null) throw new IllegalStateException("Project sessions are not configured");
        return projectSessions.createSession(userId, projectId, request);
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
