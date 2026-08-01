package com.yanban.api.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.worker.WorkerBudget;
import com.yanban.core.agent.worker.WorkerMaterialAssignment;
import com.yanban.core.agent.worker.WorkerMaterialType;
import com.yanban.core.agent.worker.WorkerServerAuthority;
import com.yanban.core.agent.worker.WorkerTaskAttestation;
import com.yanban.core.agent.worker.WorkerTaskAttestor;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchRuntimeScope;
import com.yanban.core.research.ResearchToolContracts;
import com.yanban.core.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControlledWorkerExecutionScopeTest {

    private static final ProjectVersionRef VERSION = new ProjectVersionRef("a".repeat(64));
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recordsOnlyAnAllowedInvocationOnTheExactAssignedPath() {
        WorkerTaskAttestation task = task(ProjectRelativePath.of("paper/main.tex"));
        ObjectNode arguments = mapper.createObjectNode();
        arguments.putArray("relativePaths").add("paper/main.tex");
        ObjectNode output = mapper.createObjectNode().put("status", "COMPLETE");

        assertThat(ControlledWorkerExecutionScope.isActive()).isFalse();
        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task)) {
            assertThat(ControlledWorkerExecutionScope.isActive()).isTrue();
            ControlledWorkerExecutionScope.validateInvocation(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, arguments);
            ControlledWorkerExecutionScope.recordResult(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, arguments,
                    ToolResult.success("call-1", ResearchToolContracts.PROJECT_LATEX_OUTLINE, output));
            ControlledWorkerExecutionScope.Snapshot snapshot = scope.snapshot();

            assertThat(snapshot.rejection()).isNull();
            assertThat(snapshot.executions()).singleElement().satisfies(execution -> {
                assertThat(execution.toolName()).isEqualTo(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
                assertThat(execution.requestedPaths()).containsExactly(ProjectRelativePath.of("paper/main.tex"));
                assertThat(execution.success()).isTrue();
            });
        }
        assertThat(ControlledWorkerExecutionScope.isActive()).isFalse();
    }

    @Test
    void rejectsOutOfScopePathWrongToolAndRecursiveWorker() {
        WorkerTaskAttestation task = task(ProjectRelativePath.of("paper/main.tex"));
        ObjectNode outside = mapper.createObjectNode();
        outside.putArray("relativePaths").add("src/Main.java");

        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task)) {
            assertThatThrownBy(() -> ControlledWorkerExecutionScope.validateInvocation(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, outside))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside its assignment");
            assertThat(scope.snapshot().rejection()).contains("outside its assignment");
            assertThatThrownBy(() -> ControlledWorkerExecutionScope.open(task))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("recursive");
        }

        ObjectNode assigned = mapper.createObjectNode();
        assigned.putArray("relativePaths").add("paper/main.tex");
        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task)) {
            assertThatThrownBy(() -> ControlledWorkerExecutionScope.validateInvocation(
                    ResearchToolContracts.PROJECT_CODE_SYMBOLS, assigned))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("allowlist");
            assertThat(scope.snapshot().executions()).isEmpty();
        }
    }

    @Test
    void rejectsDuplicateRelativePathsBeforeTheToolExecutorRuns() {
        WorkerTaskAttestation task = task(ProjectRelativePath.of("paper/main.tex"));
        ObjectNode duplicated = mapper.createObjectNode();
        duplicated.putArray("relativePaths").add("paper/main.tex").add("paper/main.tex");

        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task)) {
            assertThatThrownBy(() -> ControlledWorkerExecutionScope.validateInvocation(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, duplicated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicates");
            assertThat(scope.snapshot().executions()).isEmpty();
            assertThat(scope.snapshot().rejection()).contains("duplicates");
        }
    }

    @Test
    void rejectsASecondSuccessfulInspectionOfTheSamePathBeforeBudgetCanExpand() {
        WorkerTaskAttestation task = task(ProjectRelativePath.of("paper/main.tex"));
        ObjectNode arguments = mapper.createObjectNode();
        arguments.putArray("relativePaths").add("paper/main.tex");

        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task)) {
            ControlledWorkerExecutionScope.validateInvocation(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, arguments);
            ControlledWorkerExecutionScope.recordResult(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, arguments,
                    ToolResult.success("call-1", ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                            mapper.createObjectNode().put("status", "COMPLETE")));

            assertThatThrownBy(() -> ControlledWorkerExecutionScope.validateInvocation(
                    ResearchToolContracts.PROJECT_LATEX_OUTLINE, arguments))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already inspected");
            assertThat(scope.snapshot().executions()).hasSize(1);
        }
    }

    private WorkerTaskAttestation task(ProjectRelativePath path) {
        WorkerBudget budget = new WorkerBudget(1, 2, 2, 16, 10_000, 10_000);
        AgentRunIdentity identity = new AgentRunIdentity("TEST", "run", 7L, 9L, 21L);
        ResearchRuntimeScope scope = new ResearchRuntimeScope(21L, 7L,
                Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), VERSION);
        WorkerServerAuthority authority = WorkerServerAuthority.serverResolved(identity, scope,
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), budget);
        WorkerTaskPacket packet = new WorkerTaskPacket("worker-paper", identity.runId(), VERSION,
                List.of(new WorkerMaterialAssignment(path, WorkerMaterialType.PAPER)), "Analyze assigned paper.",
                List.of("Inspect assigned material"), List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                List.of(ControlledWorkerDispatchPlanner.FINDING_KEY), budget, List.of());
        return WorkerTaskAttestor.attestServerResolved(authority, packet);
    }
}
