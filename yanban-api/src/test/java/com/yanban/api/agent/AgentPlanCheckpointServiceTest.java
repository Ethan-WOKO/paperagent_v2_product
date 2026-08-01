package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import(AgentPlanRunLeaseService.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentPlanCheckpointServiceTest {

    private static final long USER_ID = 71L;
    private static final long SESSION_ID = 81L;
    private static final long PROJECT_ID = 91L;
    private static final String VERSION = "a".repeat(64);
    private static final String FILE_HASH = "b".repeat(64);
    private static final String TOOL = "project_latex_outline";

    private final ObjectMapper json = new ObjectMapper();
    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanEventRepository events;
    private final AgentPlanRunLeaseService leases;
    private final JdbcTemplate jdbc;
    private ProjectService projects;
    private AgentPlanCheckpointService checkpoints;
    private ProjectManifestResponse manifest;

    @Autowired
    AgentPlanCheckpointServiceTest(AgentPlanRepository plans, AgentPlanStepRepository steps,
                                   AgentPlanEventRepository events, AgentPlanRunLeaseService leases,
                                   JdbcTemplate jdbc) {
        this.plans = plans;
        this.steps = steps;
        this.events = events;
        this.leases = leases;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        manifest = new ProjectManifestResponse(PROJECT_ID, VERSION, List.of(
                new ProjectFileEntry("paper/main.tex", 120L, Instant.EPOCH, FILE_HASH)));
        when(projects.manifest(USER_ID, PROJECT_ID)).thenAnswer(invocation -> manifest);
        checkpoints = new AgentPlanCheckpointService(json, plans, steps, events, leases, projects);
    }

    @Test
    void restartValidatesCheckpointAndDoesNotResetCompletedStep() {
        AgentPlan plan = durablePlan();
        AgentPlanStep step = planStep(plan);
        AgentPlanExecutionLease first = claim(plan, "instance-a");
        checkpoints.initializeOrValidate(first, policy(), ceiling());
        step.markRunning();
        leases.saveOwnedStep(first, step);
        step.markCompleted("audited result");
        leases.saveOwnedStep(first, step);
        checkpoints.saveBoundary(first, policy(), ceiling());
        leases.release(first, "PROCESS_STOPPED");

        AgentPlanExecutionLease restarted = claim(plan, "instance-b");
        AgentPlanCheckpointService.Validation validation = checkpoints.initializeOrValidate(
                restarted, policy(), ceiling());

        assertThat(validation.recovery()).isTrue();
        AgentPlanStep stored = steps.findByPlanIdOrderBySortOrderAsc(plan.getId()).get(0);
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(stored.getResult()).isEqualTo("audited result");
    }

    @Test
    void missingAndTamperedCheckpointFailClosed() throws Exception {
        AgentPlan missing = durablePlan();
        planStep(missing);
        AgentPlanExecutionLease first = claim(missing, "instance-a");
        leases.release(first, "CRASHED_BEFORE_CHECKPOINT");
        AgentPlanExecutionLease recovered = claim(missing, "instance-b");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(recovered, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");

        AgentPlan tampered = durablePlan();
        planStep(tampered);
        AgentPlanExecutionLease initial = claim(tampered, "instance-a");
        checkpoints.initializeOrValidate(initial, policy(), ceiling());
        leases.release(initial, "CRASHED");
        jdbc.update("UPDATE agent_plans SET checkpoint_json = checkpoint_json || ' ' WHERE id = ?", tampered.getId());
        AgentPlanExecutionLease tamperedRecovery = claim(tampered, "instance-b");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(tamperedRecovery, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("tampered");
    }

    @Test
    void staleProjectVersionAndRevokedToolFailClosed() {
        AgentPlan stale = durablePlan();
        planStep(stale);
        AgentPlanExecutionLease first = claim(stale, "instance-a");
        checkpoints.initializeOrValidate(first, policy(), ceiling());
        leases.release(first, "CRASHED");
        manifest = new ProjectManifestResponse(PROJECT_ID, "c".repeat(64), manifest.files());

        AgentPlanExecutionLease recovered = claim(stale, "instance-b");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(recovered, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STALE");

        manifest = new ProjectManifestResponse(PROJECT_ID, VERSION, manifest.files());
        AgentPlan revoked = durablePlan();
        planStep(revoked);
        AgentPlanExecutionLease authorized = claim(revoked, "instance-a");
        checkpoints.initializeOrValidate(authorized, policy(), ceiling());
        leases.release(authorized, "CRASHED");
        AgentPlanExecutionLease revokedRecovery = claim(revoked, "instance-b");
        ResolvedToolPolicy denyAll = new ResolvedToolPolicy(List.of(), 0, 0, "revoked");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(
                revokedRecovery, denyAll, new AgentPlanCheckpointService.BudgetCeiling(240, 2, 1, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revoked");
    }

    @Test
    void durableSandboxAuthoritySurvivesFirstCheckpointButCurrentRevocationStopsRecovery() {
        AgentPlan plan = durablePlan();
        steps.save(new AgentPlanStep(plan.getId(), "sandbox", 1, "sandbox", "governed check",
                "SANDBOX_EXECUTE", "[]", "[\"sandbox_execute\"]", "verified receipt"));
        ResolvedToolPolicy sandbox = new ResolvedToolPolicy(List.of(TOOL, "sandbox_execute"), 2, 1, "current");
        AgentPlanExecutionLease first = claim(plan, "sandbox-owner");
        AgentPlanCheckpointService.Validation initialized = checkpoints.initializeOrValidate(first, sandbox, ceiling());
        assertThat(initialized.checkpointVersion()).isPositive();
        leases.release(first, "WAITING_FOR_SANDBOX");

        AgentPlanExecutionLease recovery = claim(plan, "sandbox-recovery");
        ResolvedToolPolicy revoked = new ResolvedToolPolicy(List.of(TOOL), 2, 1, "sandbox_revoked");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(recovery, revoked, ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revoked");
    }

    @Test
    void duplicateToolBudgetCannotExpandOnRecovery() {
        AgentPlan plan = durablePlan();
        planStep(plan);
        ResolvedToolPolicy original = new ResolvedToolPolicy(List.of(TOOL), 2, 0, "original");
        AgentPlanExecutionLease first = claim(plan, "instance-a");
        checkpoints.initializeOrValidate(first, original, ceiling());
        leases.release(first, "CRASHED");

        AgentPlanExecutionLease recovered = claim(plan, "instance-b");

        assertThatThrownBy(() -> checkpoints.initializeOrValidate(recovered, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("budget");
    }

    @Test
    void recoveryKeepsOriginalTotalToolBudgetAfterRepairAddsAStep() {
        AgentPlan plan = durablePlan();
        planStep(plan);
        AgentPlanExecutionLease first = claim(plan, "instance-a");
        checkpoints.initializeOrValidate(first, policy(), ceiling());
        leases.saveOwnedStep(first, new AgentPlanStep(plan.getId(), "repair", 2, "repair", "repair analysis",
                "ANALYSIS", "[]", "[\"" + TOOL + "\"]", "evidence"));
        checkpoints.saveBoundary(first, policy(), ceiling());
        leases.release(first, "CRASHED");

        AgentPlanExecutionLease recovered = claim(plan, "instance-b");
        AgentPlanCheckpointService.Validation validation = checkpoints.initializeOrValidate(
                recovered, policy(), new AgentPlanCheckpointService.BudgetCeiling(240, 2, 1, 4));

        assertThat(validation.budgetCeiling().maxToolCalls()).isEqualTo(2);
    }

    @Test
    void identityBudgetAndReceiptTamperingFailClosed() throws Exception {
        AgentPlan identity = durablePlan();
        planStep(identity);
        AgentPlanExecutionLease first = claim(identity, "instance-a");
        checkpoints.initializeOrValidate(first, policy(), ceiling());
        leases.release(first, "CRASHED");
        rewriteCheckpoint(identity.getId(), root -> root.put("projectId", PROJECT_ID + 1));
        AgentPlanExecutionLease identityRecovery = claim(identity, "instance-b");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(identityRecovery, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity");

        AgentPlan budget = durablePlan();
        planStep(budget);
        AgentPlanExecutionLease budgetFirst = claim(budget, "instance-a");
        checkpoints.initializeOrValidate(budgetFirst, policy(), ceiling());
        leases.release(budgetFirst, "CRASHED");
        rewriteCheckpoint(budget.getId(), root -> root.put("maxToolCalls", 999));
        AgentPlanExecutionLease budgetRecovery = claim(budget, "instance-b");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(budgetRecovery, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("budget");

        AgentPlan receipt = durablePlan();
        AgentPlanStep receiptStep = planStep(receipt);
        AgentPlanExecutionLease receiptFirst = claim(receipt, "instance-a");
        checkpoints.initializeOrValidate(receiptFirst, policy(), ceiling());
        events.saveAndFlush(new AgentPlanEvent(receipt.getId(), receiptStep.getId(),
                "step_project_evidence", "{\"evidence\":[]}", "evt:evidence"));
        checkpoints.saveBoundary(receiptFirst, policy(), ceiling());
        leases.release(receiptFirst, "CRASHED");
        jdbc.update("UPDATE agent_plan_events SET payload_json = ? WHERE plan_id = ? AND idempotency_key = ?",
                "{\"evidence\":[\"forged\"]}", receipt.getId(), "evt:evidence");
        AgentPlanExecutionLease receiptRecovery = claim(receipt, "instance-b");
        assertThatThrownBy(() -> checkpoints.initializeOrValidate(receiptRecovery, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("receipt");
    }

    @Test
    void persistedPlanMetadataTamperingFailsClosed() {
        AgentPlan plan = durablePlan();
        planStep(plan);
        AgentPlanExecutionLease first = claim(plan, "instance-a");
        checkpoints.initializeOrValidate(first, policy(), ceiling());
        leases.release(first, "CRASHED");
        jdbc.update("UPDATE agent_plans SET goal = ? WHERE id = ?", "forged goal", plan.getId());

        AgentPlanExecutionLease recovered = claim(plan, "instance-b");

        assertThatThrownBy(() -> checkpoints.initializeOrValidate(recovered, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
    }

    @Test
    void checkpointRejectsAbsoluteManifestPaths() {
        AgentPlan plan = durablePlan();
        planStep(plan);
        manifest = new ProjectManifestResponse(PROJECT_ID, VERSION, List.of(
                new ProjectFileEntry("C:/private/secret.txt", 10L, Instant.EPOCH, FILE_HASH)));
        AgentPlanExecutionLease lease = claim(plan, "instance-a");

        assertThatThrownBy(() -> checkpoints.initializeOrValidate(lease, policy(), ceiling()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("absolute");
    }

    @Test
    void crossUserCannotClaimDurablePlan() {
        AgentPlan plan = durablePlan();
        planStep(plan);
        assertThat(leases.claim(plan.getId(), USER_ID + 1, "other-user", Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void remainingTotalToolBudgetUsesPersistedBudgetConsumingObservationReceipts() {
        AgentPlan plan = durablePlan();
        AgentPlanStep step = planStep(plan);
        AgentPlanExecutionLease lease = claim(plan, "instance-a");
        checkpoints.initializeOrValidate(lease, policy(), ceiling());

        events.saveAndFlush(new AgentPlanEvent(plan.getId(), step.getId(), "step_tool_observation",
                "{\"budgetConsumed\":true}", "evt:tool:1"));
        events.saveAndFlush(new AgentPlanEvent(plan.getId(), step.getId(), "step_tool_observation",
                "{\"budgetConsumed\":false}", "evt:tool:reused"));
        assertThat(checkpoints.remainingToolCalls(lease, ceiling())).isEqualTo(1);

        events.saveAndFlush(new AgentPlanEvent(plan.getId(), step.getId(), "step_tool_observation",
                "{\"budgetConsumed\":true}", "evt:tool:2"));
        assertThat(checkpoints.remainingToolCalls(lease, ceiling())).isZero();
    }

    @Test
    void stableAuthorityPolicySurvivesRecoveryAfterMultipleBudgetConsumingCalls() {
        AgentPlan plan = durablePlan();
        AgentPlanStep step = planStep(plan);
        AgentPlanExecutionLease first = claim(plan, "instance-a");
        checkpoints.initializeOrValidate(first, policy(), ceiling());

        events.saveAndFlush(new AgentPlanEvent(plan.getId(), step.getId(), "step_tool_observation",
                "{\"budgetConsumed\":true}", "evt:tool:1"));
        checkpoints.saveBoundary(first, policy(), ceiling());
        leases.release(first, "WAITING_FOR_RECEIPT");

        AgentPlanExecutionLease recovered = claim(plan, "instance-b");
        AgentPlanCheckpointService.Validation validation = checkpoints.initializeOrValidate(
                recovered, policy(), ceiling());
        assertThat(validation.recovery()).isTrue();
        assertThat(checkpoints.remainingToolCalls(recovered, validation.budgetCeiling())).isEqualTo(1);

        events.saveAndFlush(new AgentPlanEvent(plan.getId(), step.getId(), "step_tool_observation",
                "{\"budgetConsumed\":true}", "evt:tool:2"));
        checkpoints.saveBoundary(recovered, policy(), validation.budgetCeiling());
        assertThat(checkpoints.remainingToolCalls(recovered, validation.budgetCeiling())).isZero();
    }

    private AgentPlan durablePlan() {
        String envelope = ProjectPlanEnvelope.wrap(json, "{}", new ProjectRuntimeContext(USER_ID, PROJECT_ID));
        AgentPlan plan = new AgentPlan(SESSION_ID, USER_ID, "goal", "summary", true, null, envelope);
        plan.enableDurableExecution();
        return plans.saveAndFlush(plan);
    }

    private AgentPlanStep planStep(AgentPlan plan) {
        return steps.saveAndFlush(new AgentPlanStep(plan.getId(), "read", 1, "read", "read paper",
                "ANALYSIS", "[]", "[\"" + TOOL + "\"]", "evidence"));
    }

    private AgentPlanExecutionLease claim(AgentPlan plan, String owner) {
        return leases.claim(plan.getId(), USER_ID, owner, Duration.ofSeconds(5)).orElseThrow();
    }

    private ResolvedToolPolicy policy() {
        return new ResolvedToolPolicy(List.of(TOOL), 2, 1, "test");
    }

    private AgentPlanCheckpointService.BudgetCeiling ceiling() {
        return new AgentPlanCheckpointService.BudgetCeiling(240, 2, 1, 2);
    }

    private void rewriteCheckpoint(Long planId,
                                   java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        String current = jdbc.queryForObject("SELECT checkpoint_json FROM agent_plans WHERE id = ?",
                String.class, planId);
        ObjectNode root = (ObjectNode) json.readTree(current);
        mutation.accept(root);
        String changed = json.writeValueAsString(root);
        jdbc.update("UPDATE agent_plans SET checkpoint_json = ?, checkpoint_hash = ? WHERE id = ?",
                changed, AgentPlanCheckpointService.sha256(changed), planId);
    }
}
