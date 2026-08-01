package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.agent.AgentTaskOutcome;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentTaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunProjectionTest {

    @Test
    void budgetStopWithUsefulAnswerIsPartialAndNotRestartResumable() {
        AgentRuntimeResult result = new AgentRuntimeResult(true, "bounded result", List.of(), 2,
                null, List.of(), List.of(), null, null, null)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED)
                .withCoordination(AgentStrategy.SINGLE_STEP_REACT,
                        AgentStopReason.MAX_STEPS_BUDGET_EXHAUSTED, "BUDGET_STOP", false, null);

        AgentRunProjection projection = project(result);

        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.PARTIAL);
        assertThat(projection.canonicalAnswer()).isEqualTo("bounded result");
        assertThat(projection.persistenceLevel()).isEqualTo("L0_REQUEST_BOUND");
        assertThat(projection.checkpointAvailable()).isFalse();
        assertThat(projection.restartResumable()).isFalse();
    }

    @Test
    void failedIntermediateTextDoesNotBecomeCanonicalAnswer() {
        AgentRuntimeResult result = new AgentRuntimeResult(false, null, List.of(), 1,
                "model failed", List.of("attempt"), List.of(), null, null, null);
        AgentRunProjection projection = project(result);
        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.FAILED);
        assertThat(projection.canonicalAnswer()).isNull();
    }

    @Test
    void failedRuntimeWithAssistantTextCannotPublishCanonicalAnswer() {
        AgentRuntimeResult result = new AgentRuntimeResult(false, "unverified failure summary", List.of(), 1,
                "failed", List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.DIRECT, AgentStopReason.RUNTIME_FAILED,
                        "FAILURE", false, null);
        AgentRunProjection projection = project(result);
        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.FAILED);
        assertThat(projection.canonicalAnswer()).isNull();
    }

    @Test
    void failedPlanAdapterSummaryCannotPublishCanonicalAnswer() {
        AgentRuntimeResult result = new AgentRuntimeResult(false, "Plan 9 finished with status FAILED",
                List.of(), 1, "step failed", List.of(), List.of(), null, null, null)
                .withPlanId(9L)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED,
                        "FAILURE", false, null);
        AgentRunProjection projection = project(result);
        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.FAILED);
        assertThat(projection.canonicalAnswer()).isNull();
    }

    @Test
    void pausedPlanIsActiveAndCannotPublishCanonicalAnswer() {
        AgentRuntimeResult result = new AgentRuntimeResult(false, "Plan paused", List.of(), 1,
                null, List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.PAUSED,
                        "PAUSED", false, null);
        AgentRunProjection projection = project(result);
        assertThat(projection.state().status()).isEqualTo(AgentTaskStatus.PAUSED);
        assertThat(projection.state().outcome()).isNull();
        assertThat(projection.canonicalAnswer()).isNull();
    }

    @Test
    void waitingPlanIsWaitingInputAndCannotPublishCanonicalAnswer() {
        AgentRuntimeResult result = new AgentRuntimeResult(false, "Plan waiting", List.of(), 1,
                null, List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.WAITING_FOR_USER,
                        "WAITING", false, null);
        AgentRunProjection projection = project(result);
        assertThat(projection.state().status()).isEqualTo(AgentTaskStatus.WAITING_INPUT);
        assertThat(projection.state().outcome()).isNull();
        assertThat(projection.canonicalAnswer()).isNull();
    }

    @Test
    void durableWaitingPlanKeepsOneConsistentRecoveryCapability() {
        AgentRuntimeResult result = new AgentRuntimeResult(false, "Plan waiting", List.of(), 1,
                null, List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.WAITING_FOR_USER,
                        "WAITING", false, null)
                .withPlanPersistenceLevel("L2_DURABLE");
        AgentRunProjection projection = AgentRunProjection.fromRuntime(result,
                new AgentRunIdentity("AGENT_PLAN", "plan-waiting", 1L, 1L, 42L));

        assertThat(projection.persistenceLevel()).isEqualTo("L2_DURABLE");
        assertThat(projection.checkpointAvailable()).isTrue();
        assertThat(projection.restartResumable()).isTrue();
    }

    @Test
    void onlyPersistedL2PlanProjectsRecoveryCapabilityAndHistoricalProjectPlanRemainsL1() {
        AgentRuntimeResult result = new AgentRuntimeResult(true, "done", List.of(), 1,
                null, List.of(), List.of(), null, null, null);

        AgentRunProjection durable = AgentRunProjection.fromRuntime(
                result.withPlanPersistenceLevel("L2_DURABLE"),
                new AgentRunIdentity("AGENT_PLAN", "plan-9", 1L, 1L, 42L));
        AgentRunProjection historical = AgentRunProjection.fromRuntime(
                result.withPlanPersistenceLevel("L1_PERSISTED"),
                new AgentRunIdentity("AGENT_PLAN", "plan-8", 1L, 1L, 42L));
        AgentRunProjection ordinary = AgentRunProjection.fromRuntime(result,
                new AgentRunIdentity("RUNTIME_TRACE", "trace-9", 1L, 1L, 42L));

        assertThat(durable.persistenceLevel()).isEqualTo("L2_DURABLE");
        assertThat(durable.checkpointAvailable()).isTrue();
        assertThat(durable.restartResumable()).isTrue();
        assertThat(historical.persistenceLevel()).isEqualTo("L1_PERSISTED");
        assertThat(historical.checkpointAvailable()).isFalse();
        assertThat(historical.restartResumable()).isFalse();
        assertThat(ordinary.persistenceLevel()).isEqualTo("L0_REQUEST_BOUND");
    }

    private AgentRunProjection project(AgentRuntimeResult result) {
        return AgentRunProjection.fromRuntime(result,
                new AgentRunIdentity("RUNTIME_TRACE", "test-trace", 1L, 1L, null));
    }
}
