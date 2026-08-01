package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanningAgentPlannerTest {

    @Mock
    ChatModelProvider modelProvider;

    private PlanningAgentPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new PlanningAgentPlanner(modelProvider, new ObjectMapper());
    }

    @Test
    void createPlanUsesStructuredLowLatencyRequest() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {
                  "summary": "Study RAG",
                  "steps": [
                    {
                      "id": "research",
                      "title": "Research RAG basics",
                      "description": "Summarize core RAG principles.",
                      "type": "ANALYSIS",
                      "dependencies": [],
                      "allowedTools": [],
                      "successCriteria": "Core principles are summarized."
                    }
                  ]
                }
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Learn RAG in two weeks.",
                "glm",
                "glm-4.5-air",
                "test-key",
                null,
                null,
                null
        );

        assertThat(plan.summary()).isEqualTo("Study RAG");
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).id()).isEqualTo("step_1");
        assertThat(plan.steps().get(0).budget())
                .isEqualTo(new PlanningAgentPlanner.StepBudget(0, 20));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.provider()).isEqualTo("glm");
        assertThat(request.model()).isEqualTo("glm-4.5-air");
        assertThat(request.maxTokens()).isEqualTo(3072);
        assertThat(request.tools()).isNull();
        assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
        assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
        assertThat(request.messages().get(0).content()).contains("Return one JSON object only");
        assertThat(request.messages().get(0).content())
                .contains("use [] when the step must not receive any tool")
                .contains("Tools exposed to this plan:\n")
                .contains("at most 6 for complex tasks")
                .contains("description <= 240")
                .contains("Every step must include a budget");
    }

    @Test
    void plannerPersistsAndClampsPerStepBudgetWithoutExpandingRuntimeAuthority() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Inspect source","steps":[
                  {"id":"read","title":"Read source","description":"Read the requested source", "type":"FILE_READ",
                   "dependencies":[],"allowedTools":["project_read_file"],
                   "budget":{"maxToolCalls":99,"maxRuntimeSteps":99},
                   "successCriteria":"Current source evidence is available"}
                ]}
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Read the current Project source", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));

        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.budget().maxToolCalls()).isEqualTo(12);
            assertThat(step.budget().maxRuntimeSteps()).isEqualTo(20);
        });
        assertThat(plan.rawJson()).contains("maxToolCalls", "maxRuntimeSteps", "step_1");
    }

    @Test
    void candidateStepReservesExactEvidenceReadsAndFinalProposalWithinRuntimeCeiling() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Prepare requested edit","steps":[
                  {"id":"change","title":"Prepare change","description":"Edit Runner.java", "type":"TOOL",
                   "dependencies":[],"allowedTools":["project_propose_candidate"],
                   "budget":{"maxToolCalls":1,"maxRuntimeSteps":4},
                   "successCriteria":"Candidate remains NOT_APPLIED"}
                ]}
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "modify Runner.java to use Math.addExact", "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate"));

        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.allowedTools()).containsExactly("project_propose_candidate");
            assertThat(step.budget().maxToolCalls()).isEqualTo(3);
            assertThat(step.budget().maxRuntimeSteps()).isEqualTo(4);
        });
    }

    @Test
    void recoveryPlannerReturnsOnlyExplicitPendingSupersessionsAndStepBudgets() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Replace stale remaining work","supersededStepIds":["step_3","step_3","done_step"],
                 "steps":[{"id":"replacement","title":"Re-check conflict","description":"Use completed evidence to resolve the conflict",
                 "type":"VERIFICATION","dependencies":[],"allowedTools":[],
                 "budget":{"maxToolCalls":0,"maxRuntimeSteps":3},
                 "successCriteria":"The conflict is resolved or explicitly remains unresolved"}]}
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createRecoveryPlan(
                "Compare materials", "failed and pending context", "deepseek", "model", "key", null,
                null, List.of("project_read_file"));

        assertThat(plan.supersededStepIds()).containsExactly("step_3", "done_step");
        assertThat(plan.steps()).singleElement().satisfies(step ->
                assertThat(step.budget()).isEqualTo(new PlanningAgentPlanner.StepBudget(0, 3)));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(request.capture());
        assertThat(request.getValue().messages().get(0).content())
                .contains("event-triggered Reflection planner")
                .contains("supersededStepIds")
                .contains("immutable facts");
    }

    @Test
    void readOnlyThreeFilePlanCannotInventCandidateAndEndsWithRequestedAnalysis() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Analyze three files","steps":[
                  {"id":"read1","title":"Read Success","description":"Read Success.java","type":"FILE_READ",\
                   "dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Success observed"},
                  {"id":"read2","title":"Read Failure","description":"Read Failure.java","type":"FILE_READ",\
                   "dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Failure observed"},
                  {"id":"read3","title":"Read Infinite","description":"Read Infinite.java","type":"FILE_READ",\
                   "dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Infinite observed"},
                  {"id":"candidate","title":"Propose candidate code","description":"Create Runner.java Candidate",\
                   "type":"FILE_WRITE","dependencies":["read1","read2","read3"],\
                   "allowedTools":["project_propose_candidate"],"successCriteria":"Candidate proposed successfully"}
                ]}
                """), "stop", null));
        String goal = "请按依赖顺序完成三个步骤：先分别读取 Success.java、Failure.java、Infinite.java；"
                + "再比较它们的正常、失败和超时行为；最后生成一张测试矩阵并给出结论。";

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                goal, "deepseek", "deepseek-v4-flash", "key", null, null,
                List.of("project_read_file", "project_propose_candidate"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).extracting(PlanningAgentPlanner.StepSpec::type)
                .containsExactly("FILE_READ", "FILE_READ", "FILE_READ", "ANALYSIS");
        assertThat(plan.steps()).flatExtracting(PlanningAgentPlanner.StepSpec::allowedTools)
                .doesNotContain("project_propose_candidate");
        assertThat(plan.steps().get(3)).satisfies(step -> {
            assertThat(step.title()).isEqualTo("Synthesize requested analysis");
            assertThat(step.description()).contains("comparison, matrix or report", "Do not propose");
            assertThat(step.successCriteria()).contains("original read-only request");
        });
        assertThat(plan.rawJson()).doesNotContain("project_propose_candidate", "Candidate proposed successfully");

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(request.capture());
        assertThat(request.getValue().messages().get(0).content())
                .contains("read-only: use FILE_READ plus ANALYSIS/VERIFICATION")
                .contains("Resolved plan tool allowlist:\nproject_read_file\n");
    }

    @Test
    void explicitRunnerCandidateRequestKeepsAuthorizedCandidateTool() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Propose Runner change","steps":[
                  {"id":"read","title":"Read inputs","description":"Read the three files","type":"FILE_READ",\
                   "dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Inputs observed"},
                  {"id":"candidate","title":"Propose Runner.java candidate","description":"Propose requested modification",\
                   "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],\
                   "successCriteria":"Candidate remains NOT_APPLIED"}
                ]}
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "请基于这些文件提出 Runner.java 修改候选", "deepseek", "deepseek-v4-flash", "key", null,
                null, List.of("project_read_file", "project_propose_candidate"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps().get(1).allowedTools()).containsExactly("project_propose_candidate");
        assertThat(plan.steps().get(1).title()).contains("Runner.java candidate");
        assertThat(plan.rawJson()).contains("project_propose_candidate", "NOT_APPLIED");
    }

    @Test
    void naturalChineseAndEnglishCodeEditRequestsKeepCandidateToolWithoutCandidateTerminology() {
        String plannedCandidate = """
                {"summary":"Modify requested code","steps":[
                  {"id":"read","title":"Read target","description":"Read the target code","type":"FILE_READ",
                   "dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Target observed"},
                  {"id":"change","title":"Prepare reviewable change","description":"Implement the requested edit",
                   "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],
                   "successCriteria":"The change remains NOT_APPLIED"}
                ]}
                """;
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(plannedCandidate), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(plannedCandidate), "stop", null));

        PlanningAgentPlanner.PlanSpec chinese = planner.createPlan(
                "\u8bf7\u4fee\u6539 Runner.java\uff0c\u8ba9\u5b83\u589e\u52a0\u8d85\u65f6\u5904\u7406\uff1b\u4e0d\u8981\u81ea\u52a8\u5e94\u7528", "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate"));
        PlanningAgentPlanner.PlanSpec english = planner.createPlan(
                "fix this Java file", "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate"));

        assertThat(List.of(chinese, english)).allSatisfy(plan -> {
            assertThat(plan.executable()).isTrue();
            assertThat(plan.steps().get(1).allowedTools()).containsExactly("project_propose_candidate");
            assertThat(plan.rawJson()).contains("project_propose_candidate", "NOT_APPLIED");
        });
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.messages().get(0).content())
                        .contains("project_read_file", "project_propose_candidate"));
    }

    @Test
    void negatedOrNonCodeChangeLanguageCannotExposeOrForceCandidateTool() {
        String inventedCandidate = """
                {"summary":"Invented change","steps":[
                  {"id":"read","title":"Read","description":"Read source","type":"FILE_READ",
                   "dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Source observed"},
                  {"id":"candidate","title":"Create Candidate","description":"Create an unrequested change",
                   "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],
                   "successCriteria":"Candidate proposed"}
                ]}
                """;
        when(modelProvider.chat(any())).thenReturn(
                new ChatResponse(ChatMessage.assistant(inventedCandidate), "stop", null));

        List<String> goals = List.of(
                "\u8bf7\u5206\u6790\u5982\u4f55\u4fee\u6539 Runner.java\uff0c\u4f46\u4e0d\u8981\u751f\u6210\u6539\u52a8",
                "\u8bfb\u53d6\u6587\u6863\u5e76\u6bd4\u8f83 Candidate \u548c patch \u7684\u6982\u5ff5\uff0c\u4e0d\u8981\u4fee\u6539\u9879\u76ee",
                "\u4fee\u6539\u8bba\u6587\u7684\u5206\u6790\u65b9\u6cd5\u5e76\u7ed9\u51fa\u5efa\u8bae");

        for (String goal : goals) {
            PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                    goal, "deepseek", "model", "key", null, null,
                    List.of("project_read_file", "project_propose_candidate"));

            assertThat(plan.executable()).isTrue();
            assertThat(plan.steps()).flatExtracting(PlanningAgentPlanner.StepSpec::allowedTools)
                    .doesNotContain("project_propose_candidate");
            assertThat(plan.steps().get(1).type()).isEqualTo("ANALYSIS");
            assertThat(plan.rawJson()).doesNotContain("project_propose_candidate", "Candidate proposed");
        }

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(3)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.messages().get(0).content())
                        .contains("Resolved plan tool allowlist:\nproject_read_file\n")
                        .doesNotContain("Server-confirmed code/file change intent"));
    }

    @Test
    void explicitNaturalEditRepairsAnAnalysisOnlyPlanIntoARequiredCandidateStep() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Describe edit","steps":[{"id":"analysis","title":"Explain",
                        "description":"Explain how to edit Runner.java","type":"ANALYSIS","dependencies":[],
                        "allowedTools":[],"successCriteria":"Suggestion written"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Prepare edit","steps":[{"id":"change","title":"Prepare change",
                        "description":"Create the requested Runner.java change","type":"TOOL","dependencies":[],
                        "allowedTools":["project_propose_candidate"],"successCriteria":"Candidate is NOT_APPLIED"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "modify Runner.java to handle timeout", "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_propose_candidate"));
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.messages().get(0).content())
                        .contains("Server-confirmed code/file change intent")
                        .contains("must end with one change step"));
    }

    @Test
    void explicitProjectCodeRunRepairsPlanThatOmittedSandboxExecution() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Inspect source","steps":[{"id":"read","title":"Read source",
                        "description":"Read src/main/java/xhs_1111.java","type":"FILE_READ","dependencies":[],
                        "allowedTools":["project_read_file"],"successCriteria":"Source observed"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Run source","steps":[
                        {"id":"read","title":"Read source","description":"Read src/main/java/xhs_1111.java",
                        "type":"FILE_READ","dependencies":[],"allowedTools":["project_read_file"],
                        "successCriteria":"Source observed"},
                        {"id":"run","title":"Run source","description":"Run src/main/java/xhs_1111.java",
                        "type":"SANDBOX_EXECUTE","dependencies":["read"],"allowedTools":["sandbox_execute"],
                        "successCriteria":"Report governed sandbox status, exit code, stdout and stderr"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "src/main/java/xhs_1111.java，运行这个程序，结果是什么？",
                "deepseek", "model", "key", null, null,
                List.of("project_read_file", "sandbox_execute"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).extracting(PlanningAgentPlanner.StepSpec::type)
                .containsExactly("FILE_READ", "SANDBOX_EXECUTE");
        assertThat(plan.steps().get(1).allowedTools()).containsExactly("sandbox_execute");
        verify(modelProvider, times(2)).chat(any());
    }

    @Test
    void combinedChineseChangeAndRunUsesStructuredRepairContextToRestoreCompletePlan() {
        String goal = "\u8fd9\u4e2a\u4ee3\u7801\u8fd8\u6709\u6ca1\u6709\u4ec0\u4e48\u9700\u8981\u5b8c\u5584\u7684\uff1f"
                + "\u80fd\u4e0d\u80fd\u6539\u6210\u6839\u636e\u7528\u6237\u8f93\u5165\uff0c\u7136\u540e\u6267\u884c\u8fd9\u4e2a\u7b97\u6cd5\u7684\u4ee3\u7801\uff1f";
        List<ChatMessage> history = List.of(
                ChatMessage.user("Read and run src/main/java/xhs_1111.java."),
                ChatMessage.assistant("The previous run completed."));
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Prepare change","steps":[
                        {"id":"read","title":"Read source","description":"Read src/main/java/xhs_1111.java",
                        "type":"FILE_READ","dependencies":[],"allowedTools":["project_read_file"],
                        "successCriteria":"Trusted source observed"},
                        {"id":"candidate","title":"Prepare Candidate","description":"Change code to accept user input",
                        "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],
                        "budget":{"maxToolCalls":3,"maxRuntimeSteps":6},
                        "successCriteria":"Candidate remains NOT_APPLIED"},
                        {"id":"final","title":"Summarize","description":"Summarize the proposed change",
                        "type":"ANALYSIS","dependencies":["candidate"],"allowedTools":[],
                        "successCriteria":"Explain the governed result"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Change and validate","steps":[
                        {"id":"read","title":"Read source","description":"Read src/main/java/xhs_1111.java",
                        "type":"FILE_READ","deps":[],"tools":["project_read_file"],
                        "budget":{"maxToolCalls":1,"maxRuntimeSteps":4},"success":"Trusted source observed"},
                        {"id":"candidate","title":"Prepare Candidate","description":"Change code to accept user input",
                        "type":"TOOL","deps":["read"],"tools":["project_propose_candidate"],
                        "budget":{"maxToolCalls":3,"maxRuntimeSteps":6},"success":"Candidate remains NOT_APPLIED"},
                        {"id":"run","title":"Run Candidate","description":"Run src/main/java/xhs_1111.java after confirmation",
                        "type":"SANDBOX_EXECUTE","deps":["candidate"],"tools":["sandbox_execute"],
                        "budget":{"maxToolCalls":1,"maxRuntimeSteps":4},"success":"Report the governed receipt"},
                        {"id":"final","title":"Synthesize","description":"Summarize Candidate and sandbox results",
                        "type":"ANALYSIS","deps":["candidate","run"],"tools":[],
                        "budget":{"maxToolCalls":0,"maxRuntimeSteps":4},"success":"Provide the final governed result"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                goal, "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"),
                AgentOrchestrationRequirements.empty(), null, history);

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).extracting(PlanningAgentPlanner.StepSpec::type)
                .containsExactly("FILE_READ", "TOOL", "SANDBOX_EXECUTE", "ANALYSIS");
        assertThat(plan.steps().get(1).allowedTools()).containsExactly("project_propose_candidate");
        assertThat(plan.steps().get(1).budget().maxToolCalls()).isEqualTo(3);
        assertThat(plan.steps().get(2).allowedTools()).containsExactly("sandbox_execute");
        assertThat(plan.rawJson()).contains("NOT_APPLIED");

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.messages()).containsSubsequence(history);
            assertThat(request.messages().stream()
                    .filter(message -> message.content() != null && message.content().contains(goal))
                    .count()).isEqualTo(1);
        });
        assertThat(lastMessage(requests.getAllValues().get(0)).content())
                .isEqualTo("Create an executable plan for this current user request:\n" + goal);
        assertThat(lastMessage(requests.getAllValues().get(1)).content())
                .isEqualTo("Create a fresh, complete replacement plan for this current user request:\n" + goal);
        String repairPrompt = requests.getAllValues().get(1).messages().get(0).content();
        assertThat(repairPrompt)
                .contains("Server-produced repairContext")
                .contains("\"failureCode\":\"INVALID_PLAN\"")
                .contains("\"reason\":\"MISSING_REQUIRED_PLAN_ELEMENTS\"")
                .contains("\"requiredElements\":[\"TRUSTED_PROJECT_EVIDENCE\",\"NOT_APPLIED_CANDIDATE\",\"CONFIRMED_SANDBOX_EXECUTION\",\"EXPLICIT_SANDBOX_TARGET\",\"FINAL_SYNTHESIS\"]")
                .contains("\"missingElements\":[\"CONFIRMED_SANDBOX_EXECUTION\"]")
                .contains("\"missingTools\":[\"sandbox_execute\"]")
                .contains("\"requiredSandboxPaths\":[\"src/main/java/xhs_1111.java\"]")
                .contains("\"id\":\"s1\",\"type\":\"FILE_READ\"")
                .contains("\"id\":\"s2\",\"type\":\"TOOL\"")
                .contains("\"id\":\"s3\",\"type\":\"SANDBOX_EXECUTE\"")
                .contains("\"id\":\"s4\",\"type\":\"ANALYSIS\"")
                .contains("Run src/main/java/xhs_1111.java")
                .contains("Candidate NOT_APPLIED", "requires later explicit user confirmation")
                .doesNotContain("Prepare change");
    }

    @Test
    void combinedChangeAndRunRepairRejectsSandboxStepWithoutExplicitTargetPath() {
        String firstAttempt = """
                {"summary":"Prepare change","steps":[
                {"id":"read","title":"Read source","description":"Read src/main/java/xhs_1111.java",
                "type":"FILE_READ","dependencies":[],"allowedTools":["project_read_file"],
                "successCriteria":"Trusted source observed"},
                {"id":"candidate","title":"Prepare Candidate","description":"Change the observed source",
                "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],
                "successCriteria":"Candidate remains NOT_APPLIED"},
                {"id":"final","title":"Summarize","description":"Summarize the proposed change",
                "type":"ANALYSIS","dependencies":["candidate"],"allowedTools":[],
                "successCriteria":"Explain the governed result"}]}
                """;
        String pathlessRepair = """
                {"summary":"Change and validate","steps":[
                {"id":"read","title":"Read source","description":"Read src/main/java/xhs_1111.java",
                "type":"FILE_READ","deps":[],"tools":["project_read_file"],"success":"Trusted source observed"},
                {"id":"candidate","title":"Prepare Candidate","description":"Change the observed source",
                "type":"TOOL","deps":["read"],"tools":["project_propose_candidate"],"success":"Candidate remains NOT_APPLIED"},
                {"id":"run","title":"Run Candidate","description":"Run the Candidate after confirmation",
                "type":"SANDBOX_EXECUTE","deps":["candidate"],"tools":["sandbox_execute"],"success":"Governed receipt recorded"},
                {"id":"final","title":"Synthesize","description":"Summarize Candidate and sandbox results",
                "type":"ANALYSIS","deps":["candidate","run"],"tools":[],"success":"Provide the governed result"}]}
                """;
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(firstAttempt), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(pathlessRepair), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Modify and run this code.", "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureMessage()).contains("secondReason=MISSING_SANDBOX_TARGET");
        verify(modelProvider, times(2)).chat(any());
    }

    @Test
    void combinedChangeAndRunRepairsSandboxDependencyOnCandidate() {
        String goal = "Modify src/main/java/xhs_1111.java, then run it and summarize the result.";
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(combinedPlanJson(
                        "[\"read\"]", "[\"read\"]", "[\"sandbox\"]", 1, 1)), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(combinedPlanJson(
                        "[\"read\"]", "[\"candidate\"]", "[\"sandbox\"]", 1, 1)), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                goal, "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps().get(2).dependencies()).containsExactly("step_2");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("\"reason\":\"INVALID_DEPENDENCY_CHAIN\"")
                .contains("\"missingElements\":[\"SANDBOX_DEPENDS_ON_CANDIDATE\"]")
                .contains("SANDBOX_EXECUTE step must directly depend on the Candidate step");
    }

    @Test
    void combinedChangeAndRunRejectsCandidateWithoutTrustedReadDependency() {
        String invalid = combinedPlanJson(
                "[]", "[\"candidate\"]", "[\"sandbox\"]", 1, 1);
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(invalid), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(invalid), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Modify src/main/java/xhs_1111.java, then run it and summarize.",
                "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureMessage())
                .contains("firstReason=INVALID_DEPENDENCY_CHAIN")
                .contains("secondReason=INVALID_DEPENDENCY_CHAIN");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("\"missingElements\":[\"CANDIDATE_DEPENDS_ON_TRUSTED_READ\"]");
    }

    @Test
    void combinedChangeAndRunRejectsFinalWithoutSandboxDependency() {
        String invalid = combinedPlanJson(
                "[\"read\"]", "[\"candidate\"]", "[\"candidate\"]", 1, 1);
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(invalid), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(invalid), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Modify src/main/java/xhs_1111.java, then run it and summarize.",
                "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureMessage())
                .contains("firstReason=INVALID_DEPENDENCY_CHAIN")
                .contains("secondReason=INVALID_DEPENDENCY_CHAIN");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("\"missingElements\":[\"FINAL_SYNTHESIS_DEPENDS_ON_SANDBOX\"]");
    }

    @Test
    void combinedChangeAndRunRejectsDuplicateCandidateAndSandboxSteps() {
        String duplicateCandidate = combinedPlanJson(
                "[\"read\"]", "[\"candidate\"]", "[\"sandbox\"]", 2, 1);
        String duplicateSandbox = combinedPlanJson(
                "[\"read\"]", "[\"candidate\"]", "[\"sandbox\"]", 1, 2);
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(duplicateCandidate), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(duplicateCandidate), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(duplicateSandbox), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(duplicateSandbox), "stop", null));

        PlanningAgentPlanner.PlanSpec candidatePlan = planner.createPlan(
                "Modify src/main/java/xhs_1111.java, then run it and summarize.",
                "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));
        PlanningAgentPlanner.PlanSpec sandboxPlan = planner.createPlan(
                "Modify src/main/java/xhs_1111.java, then run it and summarize.",
                "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));

        assertThat(candidatePlan.executable()).isFalse();
        assertThat(candidatePlan.failureMessage()).contains("firstReason=DUPLICATE_REQUIRED_STEP");
        assertThat(sandboxPlan.executable()).isFalse();
        assertThat(sandboxPlan.failureMessage()).contains("firstReason=DUPLICATE_REQUIRED_STEP");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(4)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("\"reason\":\"DUPLICATE_REQUIRED_STEP\"")
                .contains("\"missingElements\":[\"UNIQUE_CANDIDATE_STEP\"]");
        assertThat(requests.getAllValues().get(3).messages().get(0).content())
                .contains("\"reason\":\"DUPLICATE_REQUIRED_STEP\"")
                .contains("\"missingElements\":[\"UNIQUE_SANDBOX_STEP\"]");
    }

    @Test
    void plannerReusesGovernedSessionContextWithoutDuplicatingCurrentMessage() {
        String current = "这个代码改成根据用户输入，然后执行它。";
        List<ChatMessage> context = List.of(
                ChatMessage.user("请读取并运行 src/main/java/xhs_1111.java。"),
                ChatMessage.assistant("已读取并运行 src/main/java/xhs_1111.java。"));
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Change and run","steps":[
                {"id":"read","title":"Read","description":"Read src/main/java/xhs_1111.java",
                "type":"FILE_READ","dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Read"},
                {"id":"candidate","title":"Candidate","description":"Modify src/main/java/xhs_1111.java",
                "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],
                "successCriteria":"Candidate remains NOT_APPLIED"},
                {"id":"run","title":"Run","description":"Run src/main/java/xhs_1111.java after confirmation",
                "type":"SANDBOX_EXECUTE","dependencies":["candidate"],"allowedTools":["sandbox_execute"],
                "successCriteria":"Receipt"},
                {"id":"final","title":"Final","description":"Summarize governed results",
                "type":"ANALYSIS","dependencies":["candidate","run"],"allowedTools":[],"successCriteria":"Answer"}]}
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                current, "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"),
                AgentOrchestrationRequirements.empty(), null, context);

        assertThat(plan.executable()).isTrue();
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(request.capture());
        assertThat(request.getValue().messages()).containsSubsequence(context);
        assertThat(request.getValue().messages().stream()
                .filter(message -> message.content() != null && message.content().contains(current)).count()).isEqualTo(1);
        assertThat(request.getValue().messages().get(request.getValue().messages().size() - 1).content())
                .isEqualTo("Create an executable plan for this current user request:\n" + current);
    }

    @Test
    void combinedChangeAndRunRepairRestoresMissingCandidateWithoutAutoApplyingIt() {
        String goal = "Modify src/main/java/xhs_1111.java to accept user input, then run the code.";
        List<ChatMessage> history = List.of(
                ChatMessage.user("Read src/main/java/xhs_1111.java."),
                ChatMessage.assistant("The source was read."));
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Read and run","steps":[
                        {"id":"read","title":"Read","description":"Read src/main/java/xhs_1111.java",
                        "type":"FILE_READ","dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Read"},
                        {"id":"run","title":"Run","description":"Run src/main/java/xhs_1111.java",
                        "type":"SANDBOX_EXECUTE","dependencies":["read"],"allowedTools":["sandbox_execute"],"successCriteria":"Receipt"},
                        {"id":"final","title":"Final","description":"Summarize results",
                        "type":"ANALYSIS","dependencies":["run"],"allowedTools":[],"successCriteria":"Answer"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Change and run","steps":[
                        {"id":"read","title":"Read","description":"Read src/main/java/xhs_1111.java",
                        "type":"FILE_READ","deps":[],"tools":["project_read_file"],"success":"Read"},
                        {"id":"candidate","title":"Candidate","description":"Prepare the requested input change",
                        "type":"TOOL","deps":["read"],"tools":["project_propose_candidate"],"success":"NOT_APPLIED"},
                        {"id":"run","title":"Run","description":"Run src/main/java/xhs_1111.java after confirmation",
                        "type":"SANDBOX_EXECUTE","deps":["candidate"],"tools":["sandbox_execute"],"success":"Receipt"},
                        {"id":"final","title":"Final","description":"Summarize governed results",
                        "type":"ANALYSIS","deps":["candidate","run"],"tools":[],"success":"Answer"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                goal, "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"),
                AgentOrchestrationRequirements.empty(), null, history);

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).flatExtracting(PlanningAgentPlanner.StepSpec::allowedTools)
                .contains("project_propose_candidate", "sandbox_execute");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.messages()).containsSubsequence(history);
            assertThat(request.messages().stream()
                    .filter(message -> message.content() != null && message.content().contains(goal))
                    .count()).isEqualTo(1);
        });
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("\"missingElements\":[\"NOT_APPLIED_CANDIDATE\"]")
                .contains("\"missingTools\":[\"project_propose_candidate\"]");
    }

    @Test
    void repairCannotAddUnavailableSandboxOrAnIllegalTool() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("not json"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Unauthorized run","steps":[{"id":"run","title":"Run","description":"Run code",
                        "type":"SANDBOX_EXECUTE","deps":[],"tools":["sandbox_execute"],"success":"Receipt"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("still not json"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"Read","steps":[{"id":"read","title":"Read","description":"Read source",
                        "type":"FILE_READ","deps":[],"tools":["project_read_file","search_web"],"success":"Read"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec unavailableSandbox = planner.createPlan(
                "Run this code.", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));
        PlanningAgentPlanner.PlanSpec illegalTool = planner.createPlan(
                "Inspect this Project source.", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));

        assertThat(unavailableSandbox.executable()).isFalse();
        assertThat(unavailableSandbox.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(unavailableSandbox.failureMessage()).contains("secondReason=UNAUTHORIZED_SANDBOX_REQUEST");
        assertThat(illegalTool.executable()).isTrue();
        assertThat(illegalTool.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_read_file"));

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(4)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("Resolved allowlist: project_read_file")
                .doesNotContain("\"requiredTools\":[\"sandbox_execute\"]");
    }

    @Test
    void repeatedMissingSandboxFailsAfterExactlyOneRepairWithoutLooping() {
        String incomplete = """
                {"summary":"Incomplete","steps":[
                {"id":"read","title":"Read","description":"Read src/main/java/xhs_1111.java",
                "type":"FILE_READ","dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"Read"},
                {"id":"candidate","title":"Candidate","description":"Prepare input change",
                "type":"TOOL","dependencies":["read"],"allowedTools":["project_propose_candidate"],"successCriteria":"NOT_APPLIED"},
                {"id":"final","title":"Final","description":"Summarize",
                "type":"ANALYSIS","dependencies":["candidate"],"allowedTools":[],"successCriteria":"Answer"}]}
                """;
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(incomplete), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(incomplete), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Modify src/main/java/xhs_1111.java and run the code.",
                "deepseek", "model", "key", null, null,
                List.of("project_read_file", "project_propose_candidate", "sandbox_execute"));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureMessage())
                .contains("firstReason=MISSING_REQUIRED_PLAN_ELEMENTS")
                .contains("secondReason=MISSING_REQUIRED_PLAN_ELEMENTS");
        verify(modelProvider, times(2)).chat(any());
    }

    @Test
    void createPlanReceivesBoundedCrossMaterialRequirementsInSystemPromptOnly() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Audit materials","steps":[{
                  "id":"audit","title":"Audit paper and code",
                  "description":"Inspect governed paper and code observations.","type":"ANALYSIS",
                  "dependencies":[],"allowedTools":["project_latex_outline","project_code_symbols"],
                  "successCriteria":"Paper and code observations are cited."}]}
                """), "stop", null));
        AgentOrchestrationRequirements requirements = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.VERIFICATION_REQUIRED),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN),
                List.of(
                        new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                                List.of("project_latex_outline", "project_read_file"),
                                List.of("project_latex_outline"), true),
                        new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                                List.of("project_code_symbols", "project_read_file"),
                                List.of("project_code_symbols"), true)));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Audit the implementation claims.", "test", "model", "key", "url", null,
                List.of("project_latex_outline", "project_code_symbols"), requirements);

        assertThat(plan.executable()).isTrue();
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.messages().get(0).content())
                .contains("Server-attested bounded research orchestration requirements")
                .contains("Cover PAPER_LATEX using only one or more of: project_latex_outline")
                .contains("Include a verification step")
                .contains("do not add tools, permissions, identity, network, command, or write authority");
        assertThat(request.messages().get(1).content())
                .isEqualTo("Create an executable plan for this current user request:\nAudit the implementation claims.");
    }

    @Test
    void createPlanReturnsExplicitFailureWhenModelCallFails() {
        when(modelProvider.chat(any())).thenThrow(new RuntimeException("Timeout on blocking read"));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Create a launch checklist.",
                "glm",
                "glm-4.5-air",
                "test-key",
                null,
                null,
                null
        );

        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.MODEL_CALL_FAILED);
        assertThat(plan.failureMessage()).contains("Planner model call failed");
        assertThat(plan.executable()).isFalse();
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    void plannerFailuresAreClassifiedWithoutExecutableFallback() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(""), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(""), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("not json"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("still not json"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"summary\":\"none\",\"steps\":[]}"), "stop", null));

        PlanningAgentPlanner.PlanSpec empty = createPlan();
        PlanningAgentPlanner.PlanSpec invalid = createPlan();
        PlanningAgentPlanner.PlanSpec noSteps = createPlan();

        assertThat(empty.failureCode()).isEqualTo(PlannerFailureCode.EMPTY_RESPONSE);
        assertThat(invalid.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(noSteps.failureCode()).isEqualTo(PlannerFailureCode.NO_STEPS);
        assertThat(empty.steps()).isEmpty();
        assertThat(invalid.steps()).isEmpty();
        assertThat(noSteps.steps()).isEmpty();
    }

    @Test
    void truncatedFirstAttemptIsReplannedOnceWithSameEndpointAndAllowlist() {
        String truncated = """
                {
                  "summary": "Inspect Project",
                  "steps": [{
                    "id": "step_1",
                    "title": "Inspect",
                    "description": "Read the selected Project file
                """;
        String valid = """
                {
                  "summary": "Inspect Project safely",
                  "steps": [{
                    "id": "inspect",
                    "title": "Inspect source",
                    "description": "Read the authorized Project source and summarize relevant findings.",
                    "type": "FILE_READ",
                    "dependencies": [],
                    "allowedTools": ["project_read_file", "write_file"],
                    "successCriteria": "Findings cite the authorized Project source."
                  }]
                }
                """;
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(truncated), "length", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(valid), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Inspect the Project source.", "deepseek", "deepseek-v4-flash",
                "test-key", "https://api.example.test", "read-only Project",
                List.of("project_read_file"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_read_file"));

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(0).maxTokens()).isEqualTo(3072);
        assertThat(requests.getAllValues().get(1).maxTokens()).isEqualTo(2048);
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.provider()).isEqualTo("deepseek");
            assertThat(request.model()).isEqualTo("deepseek-v4-flash");
            assertThat(request.apiKey()).isEqualTo("test-key");
            assertThat(request.apiUrl()).isEqualTo("https://api.example.test");
            assertThat(request.tools()).isNull();
            assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
            assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
            assertThat(request.messages().get(0).content()).contains("project_read_file");
        });
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("compact JSON object")
                .contains("exact resolved allowlist below")
                .doesNotContain(truncated)
                .contains("1-4 short steps")
                .contains("\"deps\"")
                .contains("\"tools\"");
    }

    @Test
    void truncatedRepairAttemptDoesNotGetAcceptedOrRetriedAgain() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"summary\":\"x\",\"steps\":[{\"id\":\"s1\""), "length", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"summary\":\"y\",\"steps\":[{\"id\":\"s1\""), "length", null));

        PlanningAgentPlanner.PlanSpec plan = createPlan();

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(plan.failureMessage()).contains("after one bounded retry", "first=INVALID_PLAN", "second=INVALID_PLAN");
        verify(modelProvider, times(2)).chat(any());
    }

    @Test
    void twoInvalidAttemptsFailDeterministicallyWithoutLeakingRawOutput() {
        String unboundedRaw = "not-json-" + "private-output-".repeat(1000);
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(unboundedRaw), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(unboundedRaw), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = createPlan();

        verify(modelProvider, times(2)).chat(any());
        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(plan.failureMessage())
                .contains("after one bounded retry", "first=INVALID_PLAN", "second=INVALID_PLAN")
                .doesNotContain("Raw output", "private-output-private-output-private-output");
        assertThat(plan.failureMessage().length()).isLessThanOrEqualTo(500);
        assertThat(plan.steps()).isEmpty();
    }

    private PlanningAgentPlanner.PlanSpec createPlan() {
        return planner.createPlan("Create a launch checklist.", "glm", "glm-4.5-air", "test-key", null, null, null);
    }

    @Test
    void explicitCamelCaseDenyAllCannotBeOverriddenByLegacyAlias() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {
                  "summary": "Conflict",
                  "steps": [{
                    "id": "step_1",
                    "title": "No tools",
                    "description": "Answer directly.",
                    "type": "ANALYSIS",
                    "dependencies": [],
                    "allowedTools": [],
                    "allowed_tools": ["search_web"],
                    "successCriteria": "Answered without tools."
                  }]
                }
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Explain the architecture.", "glm", "glm-4.5-air", "test-key", null, null, List.of("search_web"));

        assertThat(plan.steps()).singleElement().satisfies(step -> assertThat(step.allowedTools()).isEmpty());
    }

    @Test
    void recoveryPlanFailsClosedWhenItRequestsAToolOutsideTheResolvedAllowlist() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {
                  "summary": "Recover missing evidence",
                  "steps": [{
                    "id": "repair_1",
                    "title": "Search outside the Project",
                    "description": "Use search_web to replace the missing Project evidence.",
                    "type": "TOOL",
                    "dependencies": [],
                    "allowedTools": ["search_web"],
                    "successCriteria": "A replacement source is found."
                  }]
                }
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createRecoveryPlan(
                "Compare the Project paper and code.",
                "A synthesis step failed.",
                "deepseek",
                "deepseek-v4-flash",
                "test-key",
                null,
                null,
                List.of("project_read_file"));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(plan.failureMessage()).contains("outside the resolved allowlist");
    }

    @Test
    void recoveryPlanRejectsMalformedToolFieldsInsteadOfConvertingThemToDenyAll() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"bad scalar","steps":[{"id":"r1","title":"Search","description":"Search",
                        "type":"TOOL","dependencies":[],"allowedTools":"search_web","successCriteria":"found"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"bad mixed","steps":[{"id":"r1","title":"Read","description":"Read",
                        "type":"TOOL","dependencies":[],"allowedTools":["project_read_file",7],"successCriteria":"read"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"missing tools","steps":[{"id":"r1","title":"Search","description":"Use search_web",
                        "type":"TOOL","dependencies":[],"successCriteria":"found"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec scalar = planner.createRecoveryPlan(
                "Inspect Project", "failed", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));
        PlanningAgentPlanner.PlanSpec mixed = planner.createRecoveryPlan(
                "Inspect Project", "failed", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));
        PlanningAgentPlanner.PlanSpec missing = planner.createRecoveryPlan(
                "Inspect Project", "failed", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));

        assertThat(scalar.executable()).isFalse();
        assertThat(mixed.executable()).isFalse();
        assertThat(missing.executable()).isFalse();
        assertThat(scalar.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(mixed.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(missing.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
    }

    @Test
    void recoveryPlanAcceptsAuthorizedAndExplicitToolFreeSteps() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"authorized","steps":[{"id":"r1","title":"Read","description":"Read Project evidence",
                        "type":"TOOL","dependencies":[],"allowedTools":[" project_read_file "],"successCriteria":"read"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"summary":"synthesize","steps":[{"id":"r1","title":"Synthesize","description":"Reuse dependency evidence",
                        "type":"ANALYSIS","dependencies":[],"allowedTools":[],"successCriteria":"report"}]}
                        """), "stop", null));

        PlanningAgentPlanner.PlanSpec authorized = planner.createRecoveryPlan(
                "Inspect Project", "failed", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));
        PlanningAgentPlanner.PlanSpec toolFree = planner.createRecoveryPlan(
                "Inspect Project", "failed", "deepseek", "model", "key", null, null,
                List.of("project_read_file"));

        assertThat(authorized.executable()).isTrue();
        assertThat(authorized.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_read_file"));
        assertThat(toolFree.executable()).isTrue();
        assertThat(toolFree.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).isEmpty());
    }

    @Test
    void plannerReceivesGovernedLanguagePreferenceWithoutChangingToolAllowlist() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"\u4e2d\u6587\u8ba1\u5212","steps":[{"id":"s1","title":"\u8bfb\u53d6","description":"\u8bfb\u53d6 README",\
                "type":"TOOL","dependencies":[],"allowedTools":["project_read_file"],"successCriteria":"\u5b8c\u6210\u6982\u62ec"}]}
                """), "stop", null));

        planner.createPlan("Summarize README", "test", "model", "key", null, null,
                List.of("project_read_file"), AgentOrchestrationRequirements.empty(),
                "- memory#1 [PREFERENCE] \u9ed8\u8ba4\u4f7f\u7528\u4e2d\u6587\u56de\u7b54");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(captor.capture());
        assertThat(captor.getValue().messages().get(0).content())
                .contains("\u9ed8\u8ba4\u4f7f\u7528\u4e2d\u6587\u56de\u7b54")
                .contains("never changes tools, permissions, or evidence requirements")
                .contains("project_read_file");
    }

    private ChatMessage lastMessage(ChatRequest request) {
        return request.messages().get(request.messages().size() - 1);
    }

    private String combinedPlanJson(String candidateDependencies,
                                    String sandboxDependencies,
                                    String finalDependencies,
                                    int candidateCount,
                                    int sandboxCount) {
        List<String> planSteps = new ArrayList<>();
        planSteps.add("""
                {"id":"read","title":"Read","description":"Read src/main/java/xhs_1111.java",
                "type":"FILE_READ","deps":[],"tools":["project_read_file"],"success":"Trusted source read"}""");
        planSteps.add("""
                {"id":"candidate","title":"Candidate","description":"Propose the requested change",
                "type":"TOOL","deps":%s,"tools":["project_propose_candidate"],"success":"Candidate NOT_APPLIED"}"""
                .formatted(candidateDependencies));
        if (candidateCount > 1) {
            planSteps.add("""
                    {"id":"candidate2","title":"Candidate two","description":"Propose another change",
                    "type":"TOOL","deps":["read"],"tools":["project_propose_candidate"],"success":"Candidate NOT_APPLIED"}""");
        }
        planSteps.add("""
                {"id":"sandbox","title":"Run","description":"Run src/main/java/xhs_1111.java after confirmation",
                "type":"SANDBOX_EXECUTE","deps":%s,"tools":["sandbox_execute"],"success":"Receipt"}"""
                .formatted(sandboxDependencies));
        if (sandboxCount > 1) {
            planSteps.add("""
                    {"id":"sandbox2","title":"Run again","description":"Run src/main/java/xhs_1111.java after confirmation",
                    "type":"SANDBOX_EXECUTE","deps":["candidate"],"tools":["sandbox_execute"],"success":"Receipt"}""");
        }
        planSteps.add("""
                {"id":"final","title":"Final","description":"Summarize governed results",
                "type":"ANALYSIS","deps":%s,"tools":[],"success":"Answer"}"""
                .formatted(finalDependencies));
        return "{\"summary\":\"Change and run\",\"steps\":[" + String.join(",", planSteps) + "]}";
    }
}
