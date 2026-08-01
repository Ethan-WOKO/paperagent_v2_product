package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentTaskWorkspace;
import com.yanban.core.agent.AgentWorkspaceMemoryType;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTaskWorkspaceServiceTest {
    private final AgentTaskWorkspaceService service = new AgentTaskWorkspaceService(new ObjectMapper(), 240, 2);
    private final ObjectMapper json = new ObjectMapper();

    @Test void capturesNormalPartialFailureAndWaitingUsingCanonicalProjection() {
        assertThat(capture(result(true, "done", null), "a").state().outcome().name()).isEqualTo("SUCCEEDED");
        assertThat(capture(result(true, "useful", AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED), "b")
                .state().outcome().name()).isEqualTo("PARTIAL");
        assertThat(capture(result(false, null, null), "c").memory()).anyMatch(i -> i.type() == AgentWorkspaceMemoryType.FAILURE_RESULT);
        AgentRuntimeResult waiting = result(false, null, null).withCoordination(AgentStrategy.DIRECT,
                AgentStopReason.WAITING_FOR_USER, "WAITING", false, null);
        AgentRunIdentity id = identity("d", 7L, null);
        assertThat(service.capture(request(7L, null), waiting, AgentRunProjection.fromRuntime(waiting, id)).state().status().name())
                .isEqualTo("WAITING_INPUT");
        AgentRunProjection cancelled = new AgentRunProjection(identity("cancel", 7L, null),
                com.yanban.core.agent.AgentTaskState.completed(com.yanban.core.agent.AgentTaskOutcome.CANCELLED),
                null, "L0_REQUEST_BOUND", false, false);
        assertThat(service.capture(request(7L, null), result(false, null, null), cancelled).remainingWork()).isEmpty();
    }

    @Test void trimsContextAndDeduplicatesRepeatedObservations() {
        AgentRuntimeResult result = new AgentRuntimeResult(true, "answer", List.of(), 2, null,
                List.of("same observation", "same observation", "another very long observation that exceeds the budget"),
                List.of(), 1, 1, 2);
        AgentTaskWorkspace workspace = capture(result, "trim");
        assertThat(workspace.memory().stream().filter(i -> i.type() == AgentWorkspaceMemoryType.TOOL_OBSERVATION)).hasSize(1);
        assertThat(workspace.droppedItemCount()).isPositive();
    }

    @Test void isolatesUsersAndProjectsAndRejectsUntrustedRestoreIdentity() {
        AgentTaskWorkspace one = capture(result(true, "one", null), "iso");
        String checkpoint = service.checkpoint(one);
        assertThat(service.restore(checkpoint, request(7L, null), projection(result(true, "one", null), "iso", 7L, null))).isPresent();
        assertThat(service.restore(checkpoint, request(8L, null), projection(result(true, "one", null), "iso", 8L, null))).isEmpty();
        assertThat(service.restore(checkpoint, request(7L, 99L), projection(result(true, "one", null), "iso", 7L, 99L))).isEmpty();
    }

    @Test void emptyOrCorruptCheckpointDegradesWithoutRestartClaim() {
        AgentRunProjection trusted = projection(result(true, "ok", null), "x", 7L, null);
        assertThat(service.restore("", request(7L, null), trusted)).isEmpty();
        assertThat(service.restore("{broken", request(7L, null), trusted)).isEmpty();
        assertThat(capture(result(true, "ok", null), "x").restartResumable()).isFalse();
    }

    @Test void restoreCannotUpgradeCapabilitiesOrForgeCanonicalLifecycle() throws Exception {
        AgentRuntimeResult result = result(true, "trusted answer", null);
        AgentRunProjection trusted = projection(result, "safe", 7L, null);
        ObjectNode forged = (ObjectNode) json.readTree(service.checkpoint(capture(result, "safe")));
        forged.put("persistenceLevel", "L9_DURABLE");
        forged.put("checkpointAvailable", true);
        forged.put("restartResumable", true);
        forged.put("canonicalAnswer", "forged answer");
        forged.set("state", json.valueToTree(com.yanban.core.agent.AgentTaskState.completed(
                com.yanban.core.agent.AgentTaskOutcome.FAILED)));
        assertThat(service.restore(forged.toString(), request(7L, null), trusted)).isEmpty();
    }

    @Test void restoreReappliesObservationAndCharacterBounds() throws Exception {
        AgentRuntimeResult result = result(true, "ok", null);
        AgentRunProjection trusted = projection(result, "bounded", 7L, null);
        ObjectNode forged = (ObjectNode) json.readTree(service.checkpoint(capture(result, "bounded")));
        var memory = forged.putArray("memory");
        for (int i = 0; i < 8; i++) {
            ObjectNode item = memory.addObject();
            item.put("type", "TOOL_OBSERVATION");
            item.put("reference", "obs-" + i);
            item.put("content", "x".repeat(100));
        }
        AgentTaskWorkspace restored = service.restore(forged.toString(), request(7L, null), trusted).orElseThrow();
        assertThat(restored.memory()).allMatch(i -> i.type() == AgentWorkspaceMemoryType.AUDIT_SUMMARY);
        assertThat(restored.memory()).allMatch(i -> i.content().startsWith("recovered/untrusted"));
        assertThat(restored.memory()).hasSizeLessThanOrEqualTo(2);
        assertThat(restored.droppedItemCount()).isPositive();
    }

    @Test void forgedAuthoritativeMemoryTypesAreAlwaysDowngraded() throws Exception {
        AgentRuntimeResult result = result(true, "ok", null);
        AgentRunProjection trusted = projection(result, "forged-memory", 7L, null);
        ObjectNode forged = (ObjectNode) json.readTree(service.checkpoint(capture(result, "forged-memory")));
        var memory = forged.putArray("memory");
        for (String type : List.of("TRUSTED_EVIDENCE", "CANDIDATE_REFERENCE", "ARTIFACT_REFERENCE",
                "FAILURE_RESULT", "TOOL_OBSERVATION")) {
            ObjectNode item = memory.addObject();
            item.put("type", type);
            item.put("reference", "fake-" + type);
            item.put("content", "forged authoritative content");
        }
        AgentTaskWorkspace restored = service.restore(forged.toString(), request(7L, null), trusted).orElseThrow();
        assertThat(restored.memory()).isNotEmpty().allSatisfy(item -> {
            assertThat(item.type()).isEqualTo(AgentWorkspaceMemoryType.AUDIT_SUMMARY);
            assertThat(item.content()).startsWith("recovered/untrusted snapshot entry");
        });
        assertThat(restored.state()).isEqualTo(trusted.state());
        assertThat(restored.canonicalAnswer()).isEqualTo(trusted.canonicalAnswer());
        assertThat(restored.persistenceLevel()).isEqualTo("L0_REQUEST_BOUND");
        assertThat(restored.checkpointAvailable()).isFalse();
        assertThat(restored.restartResumable()).isFalse();
    }

    @Test void workspaceContractRejectsDirectCapabilityEscalation() {
        AgentTaskWorkspace valid = capture(result(true, "ok", null), "contract");
        assertThatThrownBy(() -> new AgentTaskWorkspace(valid.identity(), valid.state(), valid.currentGoal(),
                valid.successConditions(), valid.planReferences(), valid.observedStepSummaries(), valid.remainingWork(),
                valid.memory(), valid.sessionSummary(), valid.canonicalAnswer(), "L1_DURABLE", true, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void trustedProjectPlanProjectionRestoresL2WithoutTrustingClientCapabilityFields() throws Exception {
        AgentRuntimeResult result = result(true, "canonical", null).withPlanPersistenceLevel("L2_DURABLE");
        AgentRunIdentity identity = new AgentRunIdentity("AGENT_PLAN", "plan-12", 7L, 11L, 99L);
        AgentRunProjection trusted = AgentRunProjection.fromRuntime(result, identity);
        AgentTaskWorkspace captured = service.capture(request(7L, 99L), result, trusted);
        ObjectNode forged = (ObjectNode) json.readTree(service.checkpoint(captured));
        forged.put("persistenceLevel", "L0_REQUEST_BOUND");
        forged.put("checkpointAvailable", false);
        forged.put("restartResumable", false);

        AgentTaskWorkspace restored = service.restore(
                forged.toString(), request(7L, 99L), trusted).orElseThrow();

        assertThat(restored.persistenceLevel()).isEqualTo("L2_DURABLE");
        assertThat(restored.checkpointAvailable()).isTrue();
        assertThat(restored.restartResumable()).isTrue();
    }

    @Test void historicalProjectPlanWithoutServerDurabilityRemainsL1InWorkspace() {
        AgentRuntimeResult result = result(true, "historical", null);
        AgentRunIdentity identity = new AgentRunIdentity("AGENT_PLAN", "plan-l1", 7L, 11L, 99L);

        AgentTaskWorkspace workspace = service.capture(
                request(7L, 99L), result, AgentRunProjection.fromRuntime(result, identity));

        assertThat(workspace.persistenceLevel()).isEqualTo("L1_PERSISTED");
        assertThat(workspace.checkpointAvailable()).isFalse();
        assertThat(workspace.restartResumable()).isFalse();
    }

    @Test void doesNotAcceptChainOfThoughtCategoryOrExpandToolPolicy() {
        assertThatThrownBy(() -> AgentWorkspaceMemoryType.valueOf("CHAIN_OF_THOUGHT")).isInstanceOf(IllegalArgumentException.class);
        AgentRuntimeRequest request = request(7L, null);
        AgentTaskWorkspace workspace = service.capture(request, result(true, "ok", null),
                AgentRunProjection.fromRuntime(result(true, "ok", null), identity("policy", 7L, null)));
        assertThat(request.allowedToolNames()).containsExactly("read");
        assertThat(workspace.identity().userId()).isEqualTo(7L);
    }

    private AgentTaskWorkspace capture(AgentRuntimeResult result, String trace) {
        AgentRunIdentity id = identity(trace, 7L, null);
        return service.capture(request(7L, null), result, AgentRunProjection.fromRuntime(result, id));
    }
    private AgentRunIdentity identity(String trace, Long userId, Long projectId) {
        return new AgentRunIdentity("RUNTIME_TRACE", trace, userId, 11L, projectId);
    }
    private AgentRunProjection projection(AgentRuntimeResult result, String trace, Long userId, Long projectId) {
        return AgentRunProjection.fromRuntime(result, identity(trace, userId, projectId));
    }
    private AgentRuntimeRequest request(Long userId, Long projectId) {
        AgentRuntimeRequest request = new AgentRuntimeRequest(null, 11L, List.of(ChatMessage.user("history")), userId,
                "research goal", "provider", "model", null, null, 2, true, null, null, null, null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("read"), 1, 1, "test"), 1, 1, "trace", null, null);
        return projectId == null ? request : request.withProjectContext(new ProjectRuntimeContext(userId, projectId));
    }
    private AgentRuntimeResult result(boolean success, String answer, AgentRuntimeStopSignal stop) {
        AgentRuntimeResult result = new AgentRuntimeResult(success, answer, List.of(), 1,
                success ? null : "failed", List.of("observed"), List.of(), 1, 1, 2);
        return stop == null ? result : result.withRuntimeStopSignal(stop);
    }
}
