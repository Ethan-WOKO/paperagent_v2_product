package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FinalSynthesisInputProjectorTest {
    private static final String HASH = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void successfulReceiptKeepsExecutionSuccessAndScopesStdoutAsCapturedOutput() {
        FinalSynthesisInput input = project("COMPLETED", "SUCCEEDED", 0, false,
                "program says algorithm is universally correct", "", "project:v1", Map.of("Main.java", HASH));

        assertThat(input.executionOutcome()).isEqualTo("SUCCESS");
        assertThat(input.taskOutcome()).isEqualTo("SUCCESS");
        assertThat(input.answerStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(input.evidence()).filteredOn(item -> item.category() == EvidenceCategory.EXECUTION_FACT)
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(EvidenceStatus.VERIFIED);
                    assertThat(item.executionFact().provider()).isEqualTo("e2b");
                    assertThat(item.executionFact().exitCode()).isZero();
                    assertThat(item.executionFact().stdout()).contains("universally correct");
                    assertThat(item.executionFact().stderr()).isEmpty();
                });
        assertThat(input.verificationScope().limitations())
                .anyMatch(value -> value.contains("do not make claims printed by the program true"));
    }

    @ParameterizedTest
    @MethodSource("terminalReceipts")
    void terminalReceiptFactsCannotBeUpgradedByInference(String status, Integer exitCode,
                                                          boolean timedOut, String expected) {
        FinalSynthesisInput input = project("FAILED", status, exitCode, timedOut,
                "the model claims success", "failure", "project:v1", Map.of("Main.java", HASH));

        assertThat(input.executionOutcome()).isEqualTo(expected);
        assertThat(input.taskOutcome()).isIn("FAILED", "TIMED_OUT", "CANCELLED");
        assertThat(input.answerStatus()).isEqualTo(EvidenceStatus.UNVERIFIED);
        assertThat(input.evidence()).anyMatch(item -> item.category() == EvidenceCategory.INFERENCE
                && item.status() == EvidenceStatus.INFERRED);
    }

    @Test
    void failedReceiptProjectsSafeProviderDiagnosticsIntoExecutionFact() {
        String payload = """
                {"executionId":"exec-diagnostic","provider":"e2b","status":"FAILED","exitCode":null,
                 "timedOut":false,"command":["java","Sort.java"],"failurePhase":"CREATE",
                 "failureType":"ProviderCommandException","providerErrorType":"TimeoutError",
                 "providerCommandExitCode":70}
                """;
        AgentPlanEvent receipt = new AgentPlanEvent(20L, 21L, "sandbox_execution_failed", payload);

        FinalSynthesisInput input = FinalSynthesisInputProjector.fromPlan(JSON, "FAILED", List.of(),
                null, List.of(receipt), null, Map.of());

        assertThat(input.evidence()).filteredOn(item -> item.category() == EvidenceCategory.EXECUTION_FACT)
                .singleElement().satisfies(item -> {
                    assertThat(item.executionFact().failurePhase()).isEqualTo("CREATE");
                    assertThat(item.executionFact().failureType()).isEqualTo("ProviderCommandException");
                    assertThat(item.executionFact().providerErrorType()).isEqualTo("TimeoutError");
                    assertThat(item.executionFact().providerCommandExitCode()).isEqualTo(70);
                });
    }

    static Stream<Arguments> terminalReceipts() {
        return Stream.of(
                Arguments.of("FAILED", 1, false, "FAILED"),
                Arguments.of("SUCCEEDED", 7, false, "FAILED"),
                Arguments.of("TIMED_OUT", null, true, "TIMED_OUT"),
                Arguments.of("CANCELLED", null, false, "CANCELLED"),
                Arguments.of("UNAVAILABLE", null, false, "UNAVAILABLE"));
    }

    @Test
    void successfulExecutionWithLaterVerificationFailureIsPartialRatherThanFailedExecution() {
        FinalSynthesisInput input = project("FAILED", "SUCCEEDED", 0, false,
                "ok", "", "project:v1", Map.of("Main.java", HASH));

        assertThat(input.executionOutcome()).isEqualTo("SUCCESS");
        assertThat(input.taskOutcome()).isEqualTo("PARTIAL");
        assertThat(input.answerStatus()).isEqualTo(EvidenceStatus.SUPPORTED);

        AgentRuntimeResult runtime = new AgentRuntimeResult(false, "bounded answer",
                List.of(ChatMessage.assistant("bounded answer")), 2, "semantic verification incomplete",
                List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED,
                        "FAILED", false, null)
                .withFinalSynthesisInput(input)
                .withCompletionVerification(new CompletionVerification(
                        CompletionStatus.FAILED, List.of("semantic verification incomplete"), List.of(), false, 0));
        FinalSynthesisInput reconciled = FinalSynthesisInputProjector.fromRuntime(runtime);
        assertThat(reconciled.executionOutcome()).isEqualTo("SUCCESS");
        assertThat(reconciled.taskOutcome()).isEqualTo("PARTIAL");
        assertThat(reconciled.answerStatus()).isEqualTo(EvidenceStatus.UNVERIFIED);
    }

    @Test
    void projectBindingBecomesStaleWhenCurrentVersionChanges() {
        FinalSynthesisInput input = project("COMPLETED", "SUCCEEDED", 0, false,
                "ok", "", "project:v2", Map.of("Main.java", HASH));

        assertThat(input.answerStatus()).isEqualTo(EvidenceStatus.STALE);
        assertThat(input.evidence()).filteredOn(item -> item.category() == EvidenceCategory.VERIFIED_PROJECT_EVIDENCE)
                .singleElement().extracting(SynthesisEvidence::status).isEqualTo(EvidenceStatus.STALE);
    }

    @Test
    void directKnowledgeAnswerCanBeVerifiedWithoutProjectEvidence() {
        AgentRuntimeResult result = new AgentRuntimeResult(true, "Paris", List.of(ChatMessage.assistant("Paris")),
                1, null, List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.DIRECT, AgentStopReason.COMPLETED, "VERIFIED", false, null)
                .withCompletionVerification(new CompletionVerification(
                        CompletionStatus.VERIFIED, List.of("direct knowledge route"), List.of(), false, 0));

        FinalSynthesisInput input = FinalSynthesisInputProjector.fromRuntime(result);

        assertThat(input.executionOutcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(input.taskOutcome()).isEqualTo("SUCCESS");
        assertThat(input.answerStatus()).isEqualTo(EvidenceStatus.VERIFIED);
        assertThat(input.evidence()).isEmpty();
    }

    @Test
    void externalSourcesDistinguishOpenedContentFromSearchSummary() {
        EvidenceLedger ledger = new EvidenceLedger(List.of(
                new EvidenceRef("opened", EvidenceSourceType.WEB, "OPENED", null, null, "https://a", null,
                        "search snippet wording must not downgrade explicit access"),
                new EvidenceRef("summary", EvidenceSourceType.WEB, "SEARCH_SUMMARY", null, null, "https://b", null,
                        "FULL_SOURCE wording must not upgrade a search summary"),
                new EvidenceRef("unknown", EvidenceSourceType.WEB, "WEB", null, null, "https://c", null,
                        "OPENED FULL_SOURCE fetched wording is not an access contract")));
        AgentRuntimeResult result = new AgentRuntimeResult(true, "answer", List.of(ChatMessage.assistant("answer")),
                1, null, List.of(), List.of(), null, null, null).withEvidenceLedger(ledger);

        List<SynthesisEvidence> evidence = FinalSynthesisInputProjector.fromRuntime(result).evidence();
        assertThat(evidence)
                .extracting(SynthesisEvidence::externalAccess)
                .containsExactly(ExternalSourceAccess.OPENED, ExternalSourceAccess.SEARCH_SUMMARY,
                        ExternalSourceAccess.UNKNOWN);
        assertThat(evidence).extracting(SynthesisEvidence::status)
                .containsExactly(EvidenceStatus.SUPPORTED, EvidenceStatus.UNVERIFIED,
                        EvidenceStatus.UNVERIFIED);
    }

    @Test
    void finalAnswerInferenceBasisExcludesUnverifiedExternalEvidence() {
        String payload = """
                {"executionId":"exec-web","provider":"e2b","status":"SUCCEEDED","exitCode":0,
                 "timedOut":false,"command":["java","Main.java"],"stdout":"","stderr":"","evidence":[
                   {"id":"opened","sourceType":"WEB","source":"OPENED","citation":"https://a",
                    "selectionReason":"explicit opened source","versionStatus":"LEGACY_UNVERSIONED"},
                   {"id":"summary","sourceType":"WEB","source":"SEARCH_SUMMARY","citation":"https://b",
                    "selectionReason":"search summary","versionStatus":"LEGACY_UNVERSIONED"},
                   {"id":"unknown","sourceType":"WEB","source":"WEB","citation":"https://c",
                    "selectionReason":"OPENED keyword is only free text","versionStatus":"LEGACY_UNVERSIONED"}
                 ]}
                """;
        AgentPlanEvent receipt = new AgentPlanEvent(20L, 21L, "step_project_evidence", payload);

        FinalSynthesisInput input = FinalSynthesisInputProjector.fromPlan(JSON, "COMPLETED", List.of(),
                "Final answer", List.of(receipt), null, Map.of());

        SynthesisEvidence inference = input.evidence().stream()
                .filter(item -> item.category() == EvidenceCategory.INFERENCE)
                .findFirst().orElseThrow();
        assertThat(inference.basisRefs()).contains("execution:exec-web", "opened")
                .doesNotContain("summary", "unknown");
    }

    @Test
    void legacyPlanAndMessagePayloadsRemainReadableWithoutNewFields() throws Exception {
        AgentPlanResponse plan = JSON.readValue("""
                {"id":20,"sessionId":2,"status":"COMPLETED","steps":[],
                 "executionOutcome":"SUCCESS","finalAnswer":"legacy"}
                """, AgentPlanResponse.class);
        SendMessageResponse message = JSON.readValue("""
                {"success":true,"assistantContent":"legacy","steps":0,"messages":[],
                 "projectEvidence":[],"completionStatus":"VERIFIED","outcome":"VERIFIED"}
                """, SendMessageResponse.class);

        assertThat(plan.executionOutcome()).isEqualTo("SUCCESS");
        assertThat(plan.finalSynthesisInput()).isNull();
        assertThat(message.success()).isTrue();
        assertThat(message.finalSynthesisInput()).isNull();
    }

    private FinalSynthesisInput project(String lifecycle, String receiptStatus, Integer exitCode, boolean timedOut,
                                        String stdout, String stderr, String currentVersion,
                                        Map<String, String> currentHashes) {
        String payload = """
                {"executionId":"exec-20","provider":"e2b","status":"%s","exitCode":%s,"timedOut":%s,
                 "command":["java","Main.java"],
                 "evidence":[{"id":"project-file","sourceType":"PROJECT","source":"PROJECT",
                 "file":"Main.java","chunk":"lines 1-1","citation":"Main.java:1","version":"%s",
                 "selectionReason":"server-bound execution snapshot","projectVersion":"project:v1",
                 "fileHash":"%s","startLine":1,"endLine":1,"parserVersion":"sandbox-receipt-v1",
                 "versionStatus":"VERIFIED"}]}
                """.formatted(receiptStatus, exitCode == null ? "null" : exitCode, timedOut, HASH, HASH);
        AgentPlanEvent receipt = new AgentPlanEvent(20L, 21L,
                "SUCCEEDED".equals(receiptStatus) ? "step_project_evidence" : "sandbox_execution_failed", payload);
        String disclaimer = "AI interpretation based on program output; not independently verified.";
        String analysisSummary = "The output appears plausible but was not independently verified.";
        AgentPlanEvent analysis = new AgentPlanEvent(20L, 21L, "sandbox_output_analysis",
                "{\"executionId\":\"exec-20\",\"disclaimer\":\"" + disclaimer
                        + "\",\"summary\":\"" + analysisSummary + "\"}");
        AgentPlanStepResponse step = new AgentPlanStepResponse(21L, "run", 1, "run", "run", "SANDBOX_EXECUTE",
                List.of(), List.of("sandbox_execute"), "receipt exists",
                "COMPLETED".equals(lifecycle) ? "COMPLETED" : "FAILED", 1,
                "Sandbox receipt digest; provider=e2b; status=" + receiptStatus + "; exitCode=" + exitCode
                        + "; stdoutSha256=" + sha256(stdout) + "; stderrSha256=" + sha256(stderr)
                        + "; outputTrust=UNTRUSTED_DISPLAY_ONLY\nstdout:\n" + stdout + "\nstderr:\n" + stderr
                        + "\n\n" + disclaimer + "\n" + analysisSummary,
                "COMPLETED".equals(lifecycle) ? null : "final validation incomplete", null, null);
        return FinalSynthesisInputProjector.fromPlan(JSON, lifecycle, List.of(step),
                "The model synthesized the result.", List.of(receipt, analysis), currentVersion, currentHashes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
