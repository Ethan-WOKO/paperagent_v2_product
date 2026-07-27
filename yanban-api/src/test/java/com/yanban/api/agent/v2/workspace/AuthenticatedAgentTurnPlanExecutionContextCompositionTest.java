package com.yanban.api.agent.v2.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.bootstrap.AgentV2PlanBootstrapConfiguration;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnExecutionStartRecoveryCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnExecutionStartRecoveryComposer;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnPlanBootstrapComposer;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxWorkspaceRef;
import com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionResolution;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextLeaseAttempt;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextReady;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryResolution;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredExecutionStart;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2authenticatedcontext;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        AuthenticatedAgentTurnPlanBootstrapComposer.class,
        AuthenticatedAgentTurnExecutionStartRecoveryComposer.class,
        AuthenticatedAgentTurnProjectVersionSourceFactory.class,
        AuthenticatedAgentTurnWorkspacePortFactory.class,
        AuthenticatedAgentTurnPlanExecutionContextComposer.class,
        AuthenticatedAgentTurnPlanExecutionContextCompositionTest
                .PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthenticatedAgentTurnPlanExecutionContextCompositionTest {
    private static final Long USER_ID = 7L;
    private static final Long TURN_ID = 42L;
    private static final Long PROJECT_ID = 83L;
    private static final String PROJECT_PATH = "paper.txt";
    private static final String PROJECT_CONTENT = "verified paper";
    private static final Path WORKSPACE_ROOT = temporaryRoot();

    @TestConfiguration
    @ComponentScan(basePackageClasses = ProductPlanBootstrapRepositoryAdapter.class)
    static class PersistenceSlice {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ProjectStorageProperties projectStorageProperties() {
            ProjectStorageProperties properties = new ProjectStorageProperties();
            properties.setMaxFileBytes(1024);
            properties.setMaxTotalBytes(4096);
            properties.setMaxFiles(10);
            return properties;
        }
    }

    @DynamicPropertySource
    static void workspaceRoot(DynamicPropertyRegistry properties) {
        properties.add(
                "yanban.agent.v2.workspace.root",
                () -> WORKSPACE_ROOT.toString());
    }

    @MockBean
    private AgentTurnProductContextResolver contexts;

    @MockBean
    private ProjectService projects;

    @Autowired
    private AuthenticatedAgentTurnPlanBootstrapComposer bootstrap;

    @Autowired
    private AuthenticatedAgentTurnExecutionStartRecoveryComposer executionStart;

    @Autowired
    private AuthenticatedAgentTurnPlanExecutionContextComposer composer;

    @Autowired
    private ProjectStorageProperties limits;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger sourceLoads = new AtomicInteger();
    private String version;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_v2_plan_execution_contexts");
        jdbc.update("DELETE FROM agent_v2_execution_starts");
        jdbc.update("DELETE FROM agent_v2_plan_leases");
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");

        byte[] bytes = PROJECT_CONTENT.getBytes(StandardCharsets.UTF_8);
        ProjectFileEntry entry = new ProjectFileEntry(
                PROJECT_PATH,
                bytes.length,
                Instant.EPOCH,
                sha256(bytes));
        SandboxFileSnapshot snapshot = new SandboxFileSnapshot(
                new ProjectRelativePath(PROJECT_PATH),
                new FileHash(entry.sha256()),
                entry.sizeBytes());
        version = ProjectManifestIdentity.derive(List.of(
                new ProjectManifestIdentity.Entry(
                        snapshot.relativePath(),
                        snapshot.fileHash(),
                        snapshot.sizeBytes()))).value();
        when(contexts.resolve(USER_ID, TURN_ID)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN",
                                String.valueOf(TURN_ID),
                                USER_ID,
                                11L,
                                PROJECT_ID),
                        Optional.of(version)));
        when(projects.manifest(USER_ID, PROJECT_ID)).thenAnswer(invocation -> {
            sourceLoads.incrementAndGet();
            return new ProjectManifestResponse(
                    PROJECT_ID, version, List.of(entry));
        });
        when(projects.materializeSandbox(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(PROJECT_ID),
                anySet())).thenReturn(
                        new ProjectService.SandboxWorkspaceMaterialization(
                                new SandboxWorkspaceSnapshot(
                                        new SandboxWorkspaceRef(
                                                PROJECT_ID,
                                                new com.yanban.core.research
                                                        .ProjectVersionRef(version)),
                                        List.of(snapshot)),
                                Map.of(PROJECT_PATH, PROJECT_CONTENT)));
    }

    @Test
    void firstCompositionIsReadyThenRestartAdoptsPersistedSpecAcrossChangedLimits() {
        assertEquals(
                PersistenceOutcome.APPLIED,
                bootstrap.bootstrap(USER_ID, TURN_ID, bootstrapCommand())
                        .outcome());
        RecoveredExecutionStart started = assertInstanceOf(
                RecoveredExecutionStart.class,
                executionStart.recover(
                        USER_ID,
                        TURN_ID,
                        new AuthenticatedAgentTurnExecutionStartRecoveryCommand(
                                Optional.of(startAttempt()))));
        assertEquals(
                ExecutionStartRecoveryResolution.ATOMIC_START_APPLIED,
                started.resolution());

        PlanExecutionContextReady first = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(USER_ID, TURN_ID, contextCommand()));
        assertThat(first.resolution()).isIn(
                PlanExecutionContextCompositionResolution.CONFIRM_APPLIED,
                PlanExecutionContextCompositionResolution.CONFIRM_REPLAYED);
        assertEquals(1024, first.persistedContext()
                .materializationSpec().limits().maxFileBytes());
        assertEquals(1, rowCount("agent_v2_plan_execution_contexts"));
        assertEquals(1, sourceLoads.get());

        limits.setMaxFileBytes(2048);
        limits.setMaxTotalBytes(8192);
        limits.setMaxFiles(20);

        PlanExecutionContextReady replay = assertInstanceOf(
                PlanExecutionContextReady.class,
                composer.compose(
                        USER_ID,
                        TURN_ID,
                        new AuthenticatedAgentTurnPlanExecutionContextCommand(
                                Optional.empty())));
        assertEquals(
                PlanExecutionContextCompositionResolution.OBSERVED_CONFIRMED,
                replay.resolution());
        assertEquals(
                first.persistedContext(),
                replay.persistedContext());
        assertEquals(
                first.verifiedWorkspace(),
                replay.verifiedWorkspace());
        assertEquals(1024, replay.persistedContext()
                .materializationSpec().limits().maxFileBytes());
        assertEquals(1, rowCount("agent_v2_plan_execution_contexts"));
        assertEquals(2, sourceLoads.get());
    }

    private int rowCount(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private static ProductPersistentPlanBootstrapCommand bootstrapCommand() {
        return new ProductPersistentPlanBootstrapCommand(
                new RoutingDecision(
                        new RoutingRequestId("route-42"),
                        Route.PERSISTENT_PLAN_EXECUTE,
                        RoutingDecisionReason.DECLARED_REQUIREMENT,
                        Set.of(RoutingRequirement.TOOL_USE)),
                new TaskFrameDraft(
                        "Summarize verified sources",
                        List.of("manuscript"),
                        List.of("workspace diff"),
                        List.of("preserve citations")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(
                                Capability.READ_PROJECT,
                                Capability.WRITE_WORKSPACE),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(5),
                                512 * 1024 * 1024L,
                                1024 * 1024L,
                                4),
                        Set.of()),
                new InitialPlanDraft(
                        "initial verified plan",
                        List.of(new PlanStep(
                                new PlanStepId("step-1"),
                                "inspect sources",
                                "verified summary",
                                Set.of(),
                                List.of("citations retained"),
                                new BoundedExecutionHints(
                                        2,
                                        Duration.ofMinutes(2))))),
                Instant.parse("2026-07-27T09:00:00Z"),
                Instant.parse("2026-07-27T09:00:01Z"),
                Instant.parse("2026-07-27T09:00:02Z"));
    }

    private static FreshExecutionStartAttempt startAttempt() {
        return new FreshExecutionStartAttempt(
                "synthetic-owner",
                "synthetic-token",
                Instant.parse("2099-07-27T10:10:00Z"),
                new ExecutionStartEventDraft(
                        new EventId("synthetic-start-event"),
                        Instant.parse("2026-07-27T10:00:03Z"),
                        new EventType("PLAN_STARTED"),
                        Optional.empty(),
                        "synthetic-correlation",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                Instant.parse("2026-07-27T10:00:04Z"));
    }

    private static AuthenticatedAgentTurnPlanExecutionContextCommand
            contextCommand() {
        return new AuthenticatedAgentTurnPlanExecutionContextCommand(
                Optional.of(new PlanExecutionContextLeaseAttempt(
                        "synthetic-owner",
                        "synthetic-token",
                        Instant.parse("2099-07-27T10:10:00Z"))));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("paperagent-v2-issue-29-")
                    .toAbsolutePath()
                    .normalize();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
