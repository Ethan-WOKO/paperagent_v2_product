package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import com.yanban.api.security.JwtUser;
import com.yanban.api.agent.ProjectSessionService;
import com.yanban.api.agent.AgentSessionResponse;
import com.yanban.core.agent.AgentSessionScope;

class ProjectControllerIntegrationTest {

    @org.junit.jupiter.api.Test
    void candidateReviewRoutesRemainSeparateWhenSandboxValidationIsDisabled() {
        ProjectService service = org.mockito.Mockito.mock(ProjectService.class);
        ProjectController controller = new ProjectController(service);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.validateCandidate(7L, 9L, 11L,
                "validation-key", "a".repeat(64),
                new CreateCandidateValidationRequest(CandidateValidationProfile.MAVEN_TEST, java.util.List.of(0), true)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private MockMvc mockMvc;
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectStorageProperties properties = new ProjectStorageProperties();

    @TempDir
    Path tempDir;

    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.txt"), "safe");
        Files.writeString(projectRoot.resolve("large.txt"), "x".repeat(33));
        properties.setLocalServerRoot(serverRoot.toString());
        properties.setAllowLocalAbsoluteProjectFolders(true);
        Project project = new Project(7L, "Study", "study", projectRoot.toRealPath().toString(), "[\"**\"]", "[]");
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(projects.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtUser(7L, "project-test-user"), "n/a"));
        ProjectService projectService = new ProjectService(projects,
                List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
                .setControllerAdvice(new ProjectExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalReadReturnsRelativePathOnly() throws Exception {
        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "notes.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("notes.txt"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C:\\"))));
    }

    @Test
    void invalidAbsoluteOrTraversalPathReturnsStableBadRequestWithoutRoot() throws Exception {
        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "C:\\private\\secret.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_PATH"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "../secret.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_PATH"));
    }

    @Test
    void unauthorizedAndOversizedFileRemainNotFound() throws Exception {
        properties.setMaxFileBytes(32);

        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "large.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/99/files/read").param("path", "notes.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void traversalLimitReturnsStructuredPayloadTooLarge() throws Exception {
        properties.setMaxFiles(1);

        mockMvc.perform(get("/api/v1/projects/42/manifest"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PROJECT_LIMIT_EXCEEDED"));
    }

    @Test
    void projectSessionHistoryRoutesUseAuthenticatedUserAndPathProjectId() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectSessionService facade = mock(ProjectSessionService.class);
        AgentSessionResponse session = new AgentSessionResponse(8L, 7L, AgentSessionScope.PROJECT, 42L,
                "Study conversation", "deepseek", "deepseek-v4-flash", 20, true, null, null);
        when(facade.listSessions(7L, 42L)).thenReturn(List.of(session));
        when(facade.createSession(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any())).thenReturn(session);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, facade))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(get("/api/v1/projects/42/agent/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scope").value("PROJECT"))
                .andExpect(jsonPath("$[0].projectId").value(42));

        mockMvc.perform(post("/api/v1/projects/42/agent/sessions")
                        .contentType("application/json")
                        .content("{\"title\":\"Study conversation\",\"ragDisabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("PROJECT"));

        org.mockito.Mockito.verify(facade).listSessions(7L, 42L);
        org.mockito.Mockito.verify(facade).createSession(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());
    }

    void createUsesAuthenticatedOwnerAndNeverAcceptsCallerSelectedOwnership() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectProvisioningService provisioning = mock(ProjectProvisioningService.class);
        when(provisioning.provision(org.mockito.ArgumentMatchers.any())).thenReturn(
                new Project(7L, "Created", "study", projectRoot.toRealPath().toString(), "[\"**\"]", "[]"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(post("/api/v1/projects").contentType("application/json").content("""
                {"userId":999,"accessMode":"READ_WRITE","name":"Created","projectFolder":"C:\\\\科研项目\\\\FDA-MIMO","includeRules":["**"],"ignoreRules":[]}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Created"))
                .andExpect(jsonPath("$.accessMode").value("READ_ONLY"));
        org.mockito.ArgumentCaptor<ProjectProvisioningRequest> request = org.mockito.ArgumentCaptor.forClass(ProjectProvisioningRequest.class);
        org.mockito.Mockito.verify(provisioning).provision(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().userId()).isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(request.getValue().projectFolder()).isEqualTo("C:\\科研项目\\FDA-MIMO");
    }

    @Test
    void legacyAbsoluteFolderJsonCreationIsNoLongerExposed() throws Exception {
        mockMvc.perform(post("/api/v1/projects").contentType("application/json").content("""
                {"name":"Created","projectFolder":"C:\\\\Research\\\\Study","includeRules":["**"],"ignoreRules":[]}
                """))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void browserFolderUploadBindsAuthenticatedOwnerAndMultipartRelativeFiles() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectUploadService uploadService = mock(ProjectUploadService.class);
        when(uploadService.upload(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("Uploaded"),
                org.mockito.ArgumentMatchers.eq(List.of("**")), org.mockito.ArgumentMatchers.eq(List.of("target/**")),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(
                Project.managedUpload(7L, "Uploaded", projectRoot.toRealPath().toString(), "[\"**\"]", "[\"target/**\"]"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, null, uploadService))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        MockMultipartFile file = new MockMultipartFile("files", "study/notes.txt", "text/plain", "safe".getBytes());
        mockMvc.perform(multipart("/api/v1/projects")
                        .file(file)
                        .param("name", "Uploaded")
                        .param("includeRules", "**")
                        .param("ignoreRules", "target/**"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Uploaded"))
                .andExpect(jsonPath("$.accessMode").value("READ_ONLY"));

        org.mockito.ArgumentCaptor<List<org.springframework.web.multipart.MultipartFile>> files =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(uploadService).upload(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("Uploaded"), org.mockito.ArgumentMatchers.eq(List.of("**")),
                org.mockito.ArgumentMatchers.eq(List.of("target/**")), files.capture());
        assertThat(files.getValue()).hasSize(1);
        assertThat(files.getValue().get(0).getOriginalFilename()).isEqualTo("study/notes.txt");
    }

    @Test
    void browserFolderUploadPreservesUnicodeParenthesizedMultipartFilename() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectUploadService uploadService = mock(ProjectUploadService.class);
        when(uploadService.upload(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(
                Project.managedUpload(7L, "Uploaded", projectRoot.toRealPath().toString(), "[\"**\"]", "[]"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, null, uploadService))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        MockMultipartFile file = new MockMultipartFile("files",
                "P-FDA-MIMO_v3(agent_test)/中文目录/波形(最终版).m", "text/plain", "safe".getBytes());
        mockMvc.perform(multipart("/api/v1/projects").file(file).param("name", "Uploaded").param("includeRules", "**"))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<List<org.springframework.web.multipart.MultipartFile>> files =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(uploadService).upload(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("Uploaded"), org.mockito.ArgumentMatchers.eq(List.of("**")),
                org.mockito.ArgumentMatchers.eq(List.of()), files.capture());
        assertThat(files.getValue().get(0).getOriginalFilename())
                .isEqualTo("P-FDA-MIMO_v3(agent_test)/中文目录/波形(最终版).m");
    }

    @Test
    void deleteRemovesOnlyTheAuthenticatedUsersProjectBinding() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/42"))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(projects).findByIdAndUserId(42L, 7L);
        org.mockito.Mockito.verify(projects).delete(org.mockito.ArgumentMatchers.any(Project.class));
    }

    @Test
    void deleteDelegatesToProjectSessionCleanupWhenConfigured() {
        ProjectService projectService = org.mockito.Mockito.mock(ProjectService.class);
        ProjectSessionService projectSessions = org.mockito.Mockito.mock(ProjectSessionService.class);
        ProjectController controller = new ProjectController(projectService, projectSessions);

        controller.delete(7L, 42L);

        org.mockito.Mockito.verify(projectSessions).deleteProject(7L, 42L);
        org.mockito.Mockito.verifyNoInteractions(projectService);
    }

    @Test
    void deleteReturnsTheSameNotFoundForMissingOrNonOwnedProject() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/99"))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(projects).findByIdAndUserId(99L, 7L);
        org.mockito.Mockito.verify(projects, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.any(Project.class));
    }

    @Test
    void revisionRoutesUseAuthenticatedActorRouteIdentityAndExplicitHeaders() throws Exception {
        ProjectService service = new ProjectService(projects,
                List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectRevisionWorkflowService workflow = mock(ProjectRevisionWorkflowService.class);
        ProjectRevisionOperationResponse response = new ProjectRevisionOperationResponse(91L,
                ProjectRevisionOperation.Type.APPLICATION, ProjectRevisionOperation.Outcome.SUCCEEDED,
                11L, "a".repeat(64), 12L, "b".repeat(64), 55L, "c".repeat(64),
                List.of(0, 2), List.of(1), java.time.Instant.parse("2026-07-16T00:00:00Z"));
        when(workflow.applyCandidate(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(55L),
                org.mockito.ArgumentMatchers.eq("application-key-1"),
                org.mockito.ArgumentMatchers.eq("a".repeat(64)), org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, null, null, workflow))
                .setControllerAdvice(new ProjectExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(post("/api/v1/projects/42/candidates/55/applications")
                        .header("Idempotency-Key", "application-key-1")
                        .header("If-Match", "a".repeat(64))
                        .contentType("application/json")
                        .content("{\"userId\":999,\"projectId\":999,\"acceptedChangeIndexes\":[0,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(91))
                .andExpect(jsonPath("$.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resultVersion").value("b".repeat(64)));

        org.mockito.ArgumentCaptor<ApplyCandidateRequest> request =
                org.mockito.ArgumentCaptor.forClass(ApplyCandidateRequest.class);
        org.mockito.Mockito.verify(workflow).applyCandidate(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(55L),
                org.mockito.ArgumentMatchers.eq("application-key-1"),
                org.mockito.ArgumentMatchers.eq("a".repeat(64)), request.capture());
        assertThat(request.getValue().acceptedChangeIndexes()).containsExactly(0, 2);
    }
}
