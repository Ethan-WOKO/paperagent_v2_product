package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlanStepVerifierTest {

    @Mock
    ChatModelProvider modelProvider;

    private PlanStepVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new PlanStepVerifier(modelProvider, new ObjectMapper());
    }

    @Test
    void verifyReturnsPassedAndSendsCompleteVerificationContext() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {
                          "passed": true,
                          "reason": "The candidate includes the required artifact.",
                          "evidence": "artifact path and summary are present",
                          "missingItems": []
                        }
                        """),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("candidate artifact path: report.md"));

        assertThat(result.passed()).isTrue();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.reason()).contains("required artifact");
        assertThat(result.evidence()).contains("artifact path");

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.provider()).isEqualTo(UserSettingsService.DEFAULT_PROVIDER);
        assertThat(request.model()).isEqualTo(UserSettingsService.DEFAULT_DEEPSEEK_MODEL);
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxTokens()).isEqualTo(256);
        assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
        assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).content())
                .contains("a documented zero-match result satisfies the")
                .contains("Never require a positive finding")
                .contains("require paths and line numbers only for actual matches");
        assertThat(request.messages().get(1).content())
                .contains("Overall goal")
                .contains("Complete the research task")
                .contains("success criteria")
                .contains("The step has produced a usable result")
                .contains("dependency result")
                .contains("candidate artifact path")
                .contains("Server-observed tool facts")
                .contains("observation=hits:0");
    }

    @Test
    void verifyReturnsFailureWithMissingItems() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {
                          "passed": false,
                          "reason": "The candidate is too vague",
                          "missingItems": ["specific source", "reusable conclusion"]
                        }
                        """),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("looks fine"));

        assertThat(result.passed()).isFalse();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.reason())
                .contains("too vague")
                .contains("specific source")
                .contains("reusable conclusion");
    }

    @Test
    void verifierReceivesCompletedResultsAcrossTheDependencyClosure() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("{\"passed\":true,\"reason\":\"The transitive evidence is present.\"}"),
                "stop",
                null
        ));
        PlanStepVerifier.VerificationRequest base = newRequest("bounded final synthesis");
        AgentPlanStep research = base.allSteps().get(0);
        AgentPlanStep crossCheck = new AgentPlanStep(
                19L,
                "step_2",
                2,
                "Cross-check",
                "Compare the research results",
                "VERIFICATION",
                "[\"step_1\"]",
                "[]",
                "A bounded comparison exists."
        );
        crossCheck.markDegraded("cross-material partial result", "semantic consistency unresolved");
        AgentPlanStep synthesis = new AgentPlanStep(
                19L,
                "step_3",
                3,
                "Synthesis",
                "Produce the final report",
                "ANALYSIS",
                "[\"step_2\"]",
                "[]",
                "The final report preserves evidence and limitations."
        );

        PlanStepVerifier.VerificationResult result = verifier.verify(new PlanStepVerifier.VerificationRequest(
                base.plan(), base.session(), synthesis, List.of(research, crossCheck, synthesis),
                "bounded final synthesis", "", "test-api-key", null, "trace-transitive"));

        assertThat(result.passed()).isTrue();
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(request.capture());
        assertThat(request.getValue().messages().get(1).content())
                .contains("dependency result")
                .contains("cross-material partial result")
                .contains("Dependency limitation: semantic consistency unresolved");
    }

    @Test
    void verifyRequiresOneDecisionForEveryServerNumberedCriterion() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {
                          "criteria": [
                            {"id":"c1","satisfied":true,"reason":"Sections are listed"},
                            {"id":"c2","satisfied":false,"reason":"Line references are missing"}
                          ],
                          "reason":"The result is only partially complete"
                        }
                        """),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationRequest request = newRequest("sections without line references");
        ReflectionTestUtils.setField(request.step(), "successCriteria",
                "List the section structure; Provide relative paths and line references");
        PlanStepVerifier.VerificationResult result = verifier.verify(request);

        assertThat(result.passed()).isFalse();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.criteria()).extracting(PlanStepVerifier.CriterionDecision::id)
                .containsExactly("c1", "c2");
        assertThat(result.criteria()).extracting(PlanStepVerifier.CriterionDecision::satisfied)
                .containsExactly(true, false);
    }

    @Test
    void verifyIsInconclusiveWhenStructuredDecisionOmitsACriterion() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"criteria":[{"id":"c1","satisfied":true,"reason":"Sections are listed"}]}
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {"criteria":[{"id":"c1","satisfied":true,"reason":"Sections are listed"}]}
                        """), "stop", null));

        PlanStepVerifier.VerificationRequest request = newRequest("sections only");
        ReflectionTestUtils.setField(request.step(), "successCriteria",
                "List the section structure; Provide relative paths and line references");
        PlanStepVerifier.VerificationResult result = verifier.verify(request);

        assertThat(result.conclusive()).isFalse();
        assertThat(result.reason()).contains("criterion c2");
        verify(modelProvider, org.mockito.Mockito.times(2)).chat(any());
    }

    @Test
    void verifyIsInconclusiveWhenModelReturnsMalformedJson() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("not json"),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("candidate result"));

        assertThat(result.passed()).isTrue();
        assertThat(result.conclusive()).isFalse();
        assertThat(result.reason()).contains("invalid JSON");
        verify(modelProvider, org.mockito.Mockito.times(2)).chat(any());
    }

    @Test
    void verifyRepairsOneTruncatedResponseWithoutReexecutingTheStep() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"passed\":true,\"reason\":\"truncated"),
                        "length", new ChatResponse.Usage(100, 256, 356)))
                .thenReturn(new ChatResponse(ChatMessage.assistant(
                        "{\"passed\":true,\"reason\":\"Required observations are present\",\"missing\":[]}"),
                        "stop", new ChatResponse.Usage(80, 30, 110)));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("candidate result"));

        assertThat(result.passed()).isTrue();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.reason()).contains("Required observations");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, org.mockito.Mockito.times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).extracting(ChatRequest::maxTokens).containsExactly(256, 160);
    }

    @Test
    void deterministicFinalSynthesisCheckRejectsMissingRequestedSectionsBeforeLlmApproval() {
        PlanStepVerifier.VerificationRequest request = newRequest(
                "## 一致点\n算法流程一致。\n## 证据位置\npaper.tex:10。\n## 待确认事项\n参数仍待确认。");
        ReflectionTestUtils.setField(request.plan(), "goal",
                "形成包含一致点、差异点、证据位置和待确认事项的综合结论");
        ReflectionTestUtils.setField(request.step(), "title", "综合结论");

        PlanStepVerifier.VerificationResult result = verifier.verify(request);

        assertThat(result.passed()).isFalse();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.reason()).contains("差异点");
        verify(modelProvider, never()).chat(any());
    }

    private PlanStepVerifier.VerificationRequest newRequest(String candidateResult) {
        AgentPlan plan = new AgentPlan(
                11L,
                7L,
                "Complete the research task",
                "Research task plan",
                false,
                null,
                "{}"
        );
        ReflectionTestUtils.setField(plan, "id", 19L);

        AgentSession session = new AgentSession(
                7L,
                "Verifier test session",
                UserSettingsService.DEFAULT_PROVIDER,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                8,
                false
        );
        ReflectionTestUtils.setField(session, "id", 11L);

        AgentPlanStep dependency = new AgentPlanStep(
                19L,
                "step_1",
                1,
                "Dependency",
                "Produce dependency result",
                "ANALYSIS",
                "[]",
                "[]",
                "Dependency result exists."
        );
        dependency.markCompleted("dependency result");

        AgentPlanStep step = new AgentPlanStep(
                19L,
                "step_2",
                2,
                "Current",
                "Produce the final artifact",
                "VERIFICATION",
                "[\"step_1\"]",
                "[]",
                "The step has produced a usable result."
        );

        return new PlanStepVerifier.VerificationRequest(
                plan,
                session,
                step,
                List.of(dependency, step),
                candidateResult,
                "step=1 tool=project_search success=true observation=hits:0",
                "test-api-key",
                null,
                "trace-test"
        );
    }
}
