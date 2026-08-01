package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.LocalServerProjectRootProvider;
import com.yanban.api.project.Project;
import com.yanban.api.project.ProjectRepository;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import com.yanban.core.agent.AgentRunStateMappings;
import com.yanban.core.agent.AgentTaskOutcome;
import com.yanban.core.agent.AgentTurn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class AgentServiceResearchEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path tempDir;

    @Test
    void directCurrentResearchEnvelopeSurvivesRealManifestValidationAndStaleIsRemoved() throws Exception {
        ProjectService projects = projectService(); var manifest = projects.manifest(7L, 42L);
        String hash = manifest.files().get(0).sha256();
        AgentRuntimeResult current = runtime(List.of(request("c1"), ChatMessage.tool("c1",
                envelope(manifest.version(), hash, "SERVER_ATTESTED_METADATA"))));
        EvidenceLedger extracted = AgentService.projectEvidenceFromRuntime(json, current, new ProjectRuntimeContext(7L,42L), 0);
        EvidenceLedger validated = new ProjectEvidenceValidator(projects).current(7L, new ProjectRuntimeContext(7L,42L), extracted);
        assertThat(validated.evidence()).singleElement().satisfies(ref -> { assertThat(ref.file()).isEqualTo("paper.tex"); assertThat(ref.version()).isEqualTo(hash); assertThat(ref.id()).startsWith("trusted-tool:42:"); });
        AgentRuntimeResult stale = runtime(List.of(request("c1"), ChatMessage.tool("c1",
                envelope(manifest.version(), "a".repeat(64), "SERVER_ATTESTED_METADATA"))));
        assertThat(new ProjectEvidenceValidator(projects).current(7L, new ProjectRuntimeContext(7L,42L),
                AgentService.projectEvidenceFromRuntime(json, stale, new ProjectRuntimeContext(7L,42L), 0)).evidence()).isEmpty();
    }

    @Test
    void reactHistoryAndLookalikesCannotCreateCurrentResearchEvidence() {
        ChatMessage historicalRequest = request("old"); ChatMessage fakeCurrent = ChatMessage.tool("old", envelope("c".repeat(64), "b".repeat(64), "SERVER_ATTESTED_METADATA"));
        AgentRuntimeResult replay = runtime(List.of(historicalRequest, ChatMessage.assistant("history"), fakeCurrent));
        assertThat(AgentService.projectEvidenceFromRuntime(json, replay, new ProjectRuntimeContext(7L,42L), 1).evidence()).isEmpty();
        AgentRuntimeResult chat = runtime(List.of(ChatMessage.tool("x", envelope("c".repeat(64), "b".repeat(64), "SERVER_ATTESTED_METADATA"))));
        AgentRuntimeResult badTrust = runtime(List.of(request("c1"), ChatMessage.tool("c1", envelope("c".repeat(64), "b".repeat(64), "UNTRUSTED_PROJECT_CONTENT"))));
        assertThat(AgentService.projectEvidenceFromRuntime(json, chat, new ProjectRuntimeContext(7L,42L), 0).evidence()).isEmpty();
        assertThat(AgentService.projectEvidenceFromRuntime(json, badTrust, new ProjectRuntimeContext(7L,42L), 0).evidence()).isEmpty();
    }

    @Test
    void finalProjectionRequiresEvidenceOnlyForProjectContentClaims() {
        ProjectRuntimeContext context = new ProjectRuntimeContext(7L, 42L);

        AgentRuntimeResult inventory = AgentService.enforceProjectEvidenceRequirement(
                context, "你现在有哪些工具？", runtime(List.of()), 0);
        AgentRuntimeResult contentClaim = AgentService.enforceProjectEvidenceRequirement(
                context, "分析项目中有哪些工具函数", runtime(List.of()), 0);

        assertThat(inventory.success()).isTrue();
        assertThat(contentClaim.success()).isFalse();
        assertThat(contentClaim.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");

        AgentTurn turn = new AgentTurn(3L, 7L, 10L);
        ReflectionTestUtils.setField(turn, "id", 99L);
        AgentRunProjection projection = AgentService.finalRunProjection(contentClaim, turn, context);
        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.PARTIAL);
        assertThat(projection.identity().runId()).isEqualTo("AGENT_TURN:99");
        turn.complete(11L);
        assertThat(AgentRunStateMappings.fromTurn(turn.getStatus(), true)).isEqualTo(projection.state());
    }

    @Test
    void finalProjectionTrustsOnlyTheCoordinatorRouterDirectKnowledgeDecision() {
        ProjectRuntimeContext context = new ProjectRuntimeContext(7L, 42L);
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_DIRECT), List.of(),
                AgentStrategySelectionOrigin.LLM_ROUTER);
        AgentStrategySelection selection = new AgentStrategySelection(
                AgentStrategy.AUTO, AgentStrategy.DIRECT, false, false, null,
                List.of(AgentStrategy.DIRECT, AgentStrategy.SINGLE_STEP_REACT, AgentStrategy.PLAN_EXECUTE),
                audit, "llm_router_direct");
        CompletionVerification verified = new CompletionVerification(
                CompletionStatus.VERIFIED, List.of(), List.of(), false, 0);
        AgentRuntimeResult result = runtime(List.of())
                .withCoordination(AgentStrategy.DIRECT, AgentStopReason.COMPLETED, "VERIFIED", false, null)
                .withCompletionVerification(verified);

        AgentRuntimeResult projected = AgentService.enforceProjectEvidenceRequirement(
                context, "1+1等于多少？不要读取项目文件。", selection, result, 0);
        AgentRuntimeResult untrusted = AgentService.enforceProjectEvidenceRequirement(
                context, "1+1等于多少？不要读取项目文件。", null, result, 0);

        assertThat(projected.success()).isTrue();
        assertThat(untrusted.success()).isFalse();
        assertThat(untrusted.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void finalProjectionAcceptsTheCoordinatorRouterFallbackDirectDecision() {
        ProjectRuntimeContext context = new ProjectRuntimeContext(7L, 42L);
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_INVALID_RESPONSE,
                        AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_DIRECT),
                List.of(), AgentStrategySelectionOrigin.ROUTER_FALLBACK);
        AgentStrategySelection selection = new AgentStrategySelection(
                AgentStrategy.AUTO, AgentStrategy.DIRECT, false, false, null,
                List.of(AgentStrategy.DIRECT, AgentStrategy.SINGLE_STEP_REACT, AgentStrategy.PLAN_EXECUTE),
                audit, "llm_router_invalid_response_fallback_direct");
        AgentRuntimeResult result = runtime(List.of())
                .withCoordination(AgentStrategy.DIRECT, AgentStopReason.COMPLETED, "VERIFIED", false, null)
                .withCompletionVerification(new CompletionVerification(
                        CompletionStatus.VERIFIED, List.of(), List.of(), false, 0));

        AgentRuntimeResult projected = AgentService.enforceProjectEvidenceRequirement(
                context, "1+1等于多少？", selection, result, 0);

        assertThat(projected.success()).isTrue();
    }

    @Test
    void finalProjectionPreservesActionablePlanWaitingWithoutProjectEvidence() {
        ProjectRuntimeContext context = new ProjectRuntimeContext(7L, 42L);
        AgentRuntimeResult waiting = runtime(List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.WAITING_FOR_USER,
                        "PARTIAL", false, null);

        AgentRuntimeResult projected = AgentService.enforceProjectEvidenceRequirement(
                context, "Run src/Main.java in the governed sandbox.", null, waiting, 0);

        assertThat(projected.success()).isTrue();
        assertThat(projected.stopReason()).isEqualTo(AgentStopReason.WAITING_FOR_USER);
        assertThat(projected.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void synchronousTurnCompletesPartialWhileCanonicalPlanRemainsWaiting() {
        ProjectRuntimeContext context = new ProjectRuntimeContext(7L, 42L);
        AgentTurn turn = new AgentTurn(3L, 7L, 10L);
        ReflectionTestUtils.setField(turn, "id", 101L);
        AgentRuntimeResult waiting = runtime(List.of())
                .withCanonicalAssistantContent(
                        "Plan execution is waiting for your confirmation or another required action.", 0)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.WAITING_FOR_USER,
                        "PARTIAL", false, null)
                .withPlanId(55L);

        AgentRunProjection projection = AgentService.finalRunProjection(waiting, turn, context);

        assertThat(projection.identity().source()).isEqualTo("AGENT_TURN");
        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.PARTIAL);
        assertThat(projection.canonicalAnswer()).isEqualTo(waiting.assistantContent());
        assertThat(projection.persistenceLevel()).isEqualTo("L0_REQUEST_BOUND");
    }

    private AgentRuntimeResult runtime(List<ChatMessage> messages) { return new AgentRuntimeResult(true,"ok",messages,1,null,List.of(),List.of(),null,null,null); }
    private ChatMessage request(String id) { return new ChatMessage("assistant", null, List.of(new ToolCall(id,"function",new ToolCall.FunctionCall("project_latex_outline","{}"))),null); }
    private String envelope(String projectVersion, String hash, String trust) { return "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\""+projectVersion+"\",\"relativePath\":\"paper.tex\",\"fileHash\":\""+hash+"\",\"range\":{\"startLine\":1,\"endLine\":1},\"parserVersion\":\"latex-outline@1\",\"trustLabel\":\""+trust+"\"}]}"; }
    private ProjectService projectService() throws Exception { Path root=Files.createDirectories(tempDir.resolve("p"));Files.writeString(root.resolve("paper.tex"),"paper\n");ProjectStorageProperties p=new ProjectStorageProperties();p.setLocalServerRoot(root.toString());ProjectRepository r=org.mockito.Mockito.mock(ProjectRepository.class);when(r.findByIdAndUserId(42L,7L)).thenReturn(Optional.of(new Project(7L,"p",".",root.toRealPath().toString(),"[\"**\"]","[]")));return new ProjectService(r,List.of(new LocalServerProjectRootProvider(p)),p,json); }
}
