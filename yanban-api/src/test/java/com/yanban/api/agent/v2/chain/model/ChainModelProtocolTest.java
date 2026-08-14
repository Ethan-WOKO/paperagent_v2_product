package com.yanban.api.agent.v2.chain.model;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainContentWriter;
import io.paperagent.v2.chain.ChainModelInvocationWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelInvocationRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProviderAttemptRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationStatus;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalWriter;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.model.ChainModelCallRequest;
import io.paperagent.v2.chain.model.ChainModelCallResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainModelProtocolTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-07T00:00:00Z");
    private static final String PROMPT_CANARY =
            "PROMPT-CANARY-user-private-context";
    private static final String REPAIR_CANARY =
            "REPAIR-CANARY-private-provider-feedback";
    private static final String RESPONSE_CANARY =
            "RESPONSE-CANARY-private-model-body";
    private static final String PREVIOUS_OUTPUT_CANARY =
            "PREVIOUS-OUTPUT-CANARY-private-invalid-body";
    private static final String API_KEY_CANARY =
            "API-KEY-CANARY-private-credential";

    @Test
    void productAdapterCallsStableProviderDirectlyAndLogsOnlySafeMetadata() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModelProvider directProvider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "stable-product-provider";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                captured.set(request);
                return new ChatResponse(
                        ChatMessage.assistant(RESPONSE_CANARY),
                        "stop",
                        new ChatResponse.Usage(17, 23, 40));
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("chain protocol must use chat directly");
            }
        };
        LongSupplier time = new LongSupplier() {
            private int call;

            @Override
            public long getAsLong() {
                return call++ == 0 ? 1_000_000L : 8_000_000L;
            }
        };
        ProductChainChatModelAdapter adapter =
                new ProductChainChatModelAdapter(
                        directProvider,
                        ignored -> new ProductChainModelEndpoint(
                                "provider-safe", "model-safe",
                                API_KEY_CANARY, "https://model.invalid"),
                        0.0d, 2_048, time);
        ChainModelCallRequest request = new ChainModelCallRequest(
                "invocation-safe", "context-safe", "completion-safe",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "protocol-repair", "provider-safe", "model-safe",
                PROMPT_CANARY, 2, true,
                REPAIR_CANARY, PREVIOUS_OUTPUT_CANARY);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        ProductChainChatModelAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ChainModelCallResult result;
        try {
            result = adapter.call(request);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(result).isInstanceOfSatisfying(
                ChainModelCallResult.Success.class, success -> {
                    assertThat(success.rawOutput()).isEqualTo(RESPONSE_CANARY);
                    assertThat(success.durationMs()).isEqualTo(7L);
                    assertThat(success.safeMetadata()).containsExactlyInAnyOrderEntriesOf(
                            java.util.Map.of(
                                    "provider", "provider-safe",
                                    "model", "model-safe",
                                    "attemptNo", "2",
                                    "protocolRepair", "true",
                                    "promptTokens", "17",
                                    "completionTokens", "23",
                                    "totalTokens", "40"));
                });
        assertThat(captured.get()).satisfies(chat -> {
            assertThat(chat.provider()).isEqualTo("provider-safe");
            assertThat(chat.model()).isEqualTo("model-safe");
            assertThat(chat.apiKey()).isEqualTo(API_KEY_CANARY);
            assertThat(chat.responseFormat().type()).isEqualTo("json_object");
            assertThat(chat.messages()).extracting(ChatMessage::content)
                    .hasSize(4);
            assertThat(chat.messages().get(0).content())
                    .contains("Return one valid json object only.",
                            "schemaVersion", "frozen canonical Context",
                            "visible typed role schema")
                    .doesNotContain("TOOL_ACTION", "STEP_RESULT",
                            "WORKSPACE_CHANGE");
            assertThat(chat.messages().get(1).content())
                    .isEqualTo(PROMPT_CANARY);
            assertThat(chat.messages().get(2).content())
                    .isEqualTo(PREVIOUS_OUTPUT_CANARY);
            assertThat(chat.messages().get(3).content())
                     .contains(
                            "Treat the previous assistant JSON as the repair draft",
                            "Preserve every field and value",
                            "update all dependent cross-field copies together",
                            "Never silence one validation error by deleting another required structure",
                             "Generate a complete replacement root JSON object",
                            "do not return a patch, explanation, abbreviated fallback",
                            "not new evidence that the task is blocked",
                            "Preserve the intended semantic proposal kind",
                            "recheck every required field and cross-field rule",
                            "not only the field named by the latest diagnostic",
                            REPAIR_CANARY);
            assertThat(chat.messages()).extracting(ChatMessage::role)
                    .containsExactly("system", "user", "assistant", "user");
        });
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("invocation-safe", "provider-safe",
                            "model-safe", "promptTokens=17",
                            "completionTokens=23", "totalTokens=40")
                    .doesNotContain(PROMPT_CANARY, REPAIR_CANARY,
                            RESPONSE_CANARY, PREVIOUS_OUTPUT_CANARY,
                            API_KEY_CANARY);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(new ProductChainModelEndpoint(
                "provider-safe", "model-safe", API_KEY_CANARY,
                "https://model.invalid").toString())
                .doesNotContain(API_KEY_CANARY, "https://model.invalid");
    }

    @Test
    void endpointIdentityMismatchFailsClosedBeforeProviderCall() {
        ChatModelProvider directProvider = mock(ChatModelProvider.class);
        ProductChainChatModelAdapter adapter =
                new ProductChainChatModelAdapter(
                        directProvider,
                        ignored -> new ProductChainModelEndpoint(
                                "actual-provider", "actual-model",
                                API_KEY_CANARY, "https://model.invalid"),
                        0.0d, 2_048, () -> 1_000_000L);
        ChainModelCallRequest request = new ChainModelCallRequest(
                "invocation-mismatch", "context-safe", "completion-safe",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "advance", "expected-provider", "expected-model",
                PROMPT_CANARY, 1, false, null);

        ChainModelCallResult result = adapter.call(request);

        assertThat(result).isInstanceOfSatisfying(
                ChainModelCallResult.Failure.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(
                                "MODEL_ENDPOINT_IDENTITY_MISMATCH"));
        verify(directProvider, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    void plannerGuidanceIsStageAwareWhileOtherRoleGuidanceRemainsBounded() {
        List<ChatRequest> captured = new ArrayList<>();
        ChatModelProvider directProvider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "provider-safe";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                captured.add(request);
                return new ChatResponse(ChatMessage.assistant("{}"), "stop",
                        new ChatResponse.Usage(1, 1, 2));
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("streaming is not used");
            }
        };
        ProductChainChatModelAdapter adapter = new ProductChainChatModelAdapter(
                directProvider, ignored -> new ProductChainModelEndpoint(
                "provider-safe", "model-safe", "key", null),
                0.0d, 2_048, () -> 1_000_000L);

        adapter.call(new ChainModelCallRequest(
                "invocation-planner", "context", "completion",
                ChainRole.PLANNER, ChainWorkState.PLANNING, "INITIAL_INTAKE",
                "provider-safe", "model-safe", "prompt", 1, false, null));
        adapter.call(new ChainModelCallRequest(
                "invocation-executor", "context", "completion",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING, "advance",
                "provider-safe", "model-safe", "prompt", 1, false, null));
        adapter.call(new ChainModelCallRequest(
                "invocation-reflector", "context", "completion",
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "ACTION_FAILURE_REVIEW",
                "provider-safe", "model-safe", "prompt", 1, false, null));
        adapter.call(new ChainModelCallRequest(
                "invocation-candidate-reflector", "context", "completion",
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "CANDIDATE_RESULT_REVIEW",
                "provider-safe", "model-safe", "prompt", 1, false, null));
        adapter.call(new ChainModelCallRequest(
                "invocation-answer", "context", "completion",
                ChainRole.ANSWER, ChainWorkState.DELIVERING, "TASK_OUTCOME",
                "provider-safe", "model-safe", "prompt", 1, false, null));

        String planner = captured.get(0).messages().get(0).content();
        assertThat(planner)
                .contains("Current Planner invocation stage is INITIAL_INTAKE",
                        "Allowed ordinary root kinds are",
                        "PLAN_REVISION and USER_INSTRUCTION_DISPOSITION are forbidden",
                        "Decision procedure, in order",
                        "return NEED_USER_INPUT when information required to classify or plan",
                        "return NEED_PERMISSION when required authority is not currently granted",
                        "Routing boundary definitions",
                        "needsProject is true whenever satisfying the request requires reading, inspecting",
                        "even when the user explicitly forbids modification",
                        "needsTool is true whenever satisfying the request requires observing unavailable content",
                        "analysis is not direct when its required facts must first be obtained by a tool",
                        "needsNetwork is true whenever the requested result requires external or current information",
                        "If network access is not granted, return NEED_PERMISSION before planning",
                        "needsPersistentProgress is true when the requested work itself requires durable multi-step progress",
                        "DIRECT_ROUTE is legal only when needsTool, needsNetwork, needsProject, and",
                        "answer the effective user request completely in payload.inlineAnswerBody",
                        "inlineAnswerBody is required nonblank user-visible prose",
                        "Never return answerBodyRef or any other persisted body reference",
                        "runtime-owned and forbidden in Provider output",
                        "PERSISTENT_PLAN is legal when at least one boundary is true",
                        "PLANNING_BLOCKED is exceptional",
                        "Act only as the PLANNER role",
                        "frozen canonical Context",
                        "Return exactly one complete root object matching one allowed Planner variant",
                        "stableOrder is contiguous from 1",
                        "bound exactly once by one Step's validationRequirementIds",
                        "exactly one aggregate CANDIDATE validation requirement",
                        "later non-changing validation Step",
                        "Never bind a CANDIDATE validation requirement",
                        "do not ask the user to choose an ordinary validation method",
                        "completionCondition is also the complete candidateValidationCompletionCondition",
                        "use ACTION_RECEIPT only for an executed-action receipt",
                        "publishRequirement is REQUIRED",
                        "do not request another permission or confirmation",
                        "explicit preview, proposal, Candidate-only result",
                        "PLANNED and UNSATISFIED coverage use factRefs []",
                        "Every *Refs field contains only exact visible authority identifiers",
                        "gapValidation is JSON null outside PENDING_ITEM_VALIDATION",
                        "outcome is RESOLVED exactly when every check is satisfied")
                .doesNotContain("at most one mayChangeCandidate",
                        "prefer exactly one Step", "Planner protocol checklist",
                        "Sort.java", "mergeSort");
        assertThat(planner.indexOf(
                "Current Planner invocation stage is INITIAL_INTAKE"))
                .isLessThan(planner.indexOf(
                        "Return one valid json object only."));
        String executor = captured.get(1).messages().get(0).content();
        assertThat(executor)
                .contains("Act only as the EXECUTOR role",
                        "sole authority", "never invent a reference",
                        "ToolAction priorErrorRef, priorActionRef",
                        "changeFromPriorAction, and expectedProgress form one all-or-none group",
                        "possible future failure mentioned in the instruction is not a prior failed action",
                        "\"priorErrorRef\":null,\"priorActionRef\":null",
                        "\"changeFromPriorAction\":null,\"expectedProgress\":null",
                        "current action's expected outputs only in expectedOutputs",
                        "expectedProgress is exclusively the progress expected from changing",
                        "all four are nonblank",
                        "toolId is the exact descriptor.id",
                        "requiredPermission is that same entry's exact permissionRef",
                        "never substitute a public alias or capability name",
                        "tool-action form represents exactly the single tool invocation",
                        "must not claim a mutation that invocation cannot perform",
                         "workspace-change form with the exact canonical change first",
                         "later call can execute or validate the changed workspace",
                         "compare every proposed mutation with the exact current Candidate",
                         "do not propose another mutation with those same contents",
                         "next unmet completion condition",
                         "baseCandidateRef copies the exact visible frozen base Candidate reference",
                        "it is never blank",
                        "expectedBaselineSha256 identifies the file content currently being changed",
                        "copy that overlay's exact effectiveSha256 (the resultSha256)",
                        "not its baseSha256 or the Project manifest sha256",
                        "when no Candidate overlay exists for that path, copy the Project file sha256",
                        "For an ADD of a path that does not currently exist, use the exact NONE literal",
                        "manifestChanges is an empty JSON array",
                        "file additions and deletions are represented inside the canonical changes array",
                        "validationSources contains exactly one binding for every ID",
                        "no other ID",
                        "receiptRef also appears in receiptRefs",
                        "Use [] only when the active Step has no validation requirement IDs",
                        "non-changing Step whose declared validation subject is ACTION_RECEIPT",
                        "FAILURE Receipt with a non-zero process exit code",
                        "requested negative observation, not repair authority",
                        "without retry, mutation, or a user question",
                        "does not apply when the Step permits Candidate change",
                        "TIMEOUT, CANCELLED, unavailable, or unknown execution outcomes")
                .doesNotContain("src/Example.java", "unused import", "Sort.java", "mergeSort");
        String reflector = captured.get(2).messages().get(0).content();
        assertThat(reflector)
                .contains("Act only as the REFLECTOR role",
                        "Reflector formal-failure review checklist",
                        "not a CandidateStepResult",
                        "replan-required or task-failed form allowed by the visible schema",
                        "never return a Step accept or Step continue kind",
                        "review.reviewedObjectRefs",
                        "review.directFactRefs")
                .doesNotContain("Sort.java", "mergeSort", "sorting algorithm");
        assertThat(reflector).doesNotContain(
                "REFLECTOR_REPLAN_REQUIRED", "REFLECTOR_TASK_FAILED",
                "REPLAN_REQUIRED", "TASK_FAILED");
        String candidateReflector = captured.get(3).messages().get(0).content();
        assertThat(candidateReflector)
                .contains("Reflector candidate-review checklist",
                        "step-accept",
                        "combined step-accept-and-ready-to-finalize form",
                        "conditionJudgements and artifactReceiptCandidateValidationEvidenceRefs",
                        "two separate required non-empty lists",
                        "both must be present in the same response",
                        "assesses every visible Step completion condition",
                        "exact visible artifact, receipt, candidate, or validation authorities",
                        "exact visible WorkspaceCandidate authority",
                        "literal string NONE",
                        "recheck the complete selected kind",
                        "authority-assessment object",
                        "\"status\":\"BOUND\"",
                        "\"authorityRef\":\"exact-visible-ref\"",
                        "\"reason\":null",
                        "Never use an empty string in place of JSON null",
                        "root review object and acceptance.review",
                        "copy every field and array element byte-for-byte",
                        "assess requirement declarations in the frozen TaskFrame",
                        "not a validation receipt, Candidate, or other evidence authority",
                        "copy acceptance.taskFrameRef exactly as authorityRef",
                        "NOT_REQUIRED, JSON-null authorityRef, and a nonblank reason")
                .doesNotContain("Sort.java", "mergeSort", "sorting algorithm");
        assertThat(candidateReflector).doesNotContain(
                "REFLECTOR_ACCEPT_STEP",
                "REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE",
                "ACCEPT_STEP", "ACCEPT_STEP_AND_READY_TO_FINALIZE");
        String answer = captured.get(4).messages().get(0).content();
        assertThat(answer)
                .contains("Act only as the ANSWER role",
                        "failure, or delivery form",
                        "never invent a reference",
                        "Answer output checklist",
                        "runtime.answerPayloadTemplate",
                        "replace only payload.inlineAnswerBody")
                .doesNotContain("Sort.java", "mergeSort", "sorting algorithm");
    }

    @Test
    void plannerStageGuidanceCoversEveryProductionInvocationReason() {
        List<ChatRequest> captured = new ArrayList<>();
        ChatModelProvider directProvider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "provider-safe";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                captured.add(request);
                return new ChatResponse(ChatMessage.assistant("{}"), "stop",
                        new ChatResponse.Usage(1, 1, 2));
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("streaming is not used");
            }
        };
        ProductChainChatModelAdapter adapter = new ProductChainChatModelAdapter(
                directProvider, ignored -> new ProductChainModelEndpoint(
                "provider-safe", "model-safe", "key", null),
                0.0d, 2_048, () -> 1_000_000L);

        adapter.call(plannerRequest("initial", ChainWorkState.PLANNING,
                "INITIAL_INTAKE"));
        adapter.call(plannerRequest("persistent", ChainWorkState.PLANNING,
                "PERSISTENT_PLAN"));
        adapter.call(plannerRequest("revision", ChainWorkState.PLANNING,
                "PLAN_REVISION"));
        adapter.call(plannerRequest("disposition",
                ChainWorkState.CLASSIFYING_INSTRUCTION,
                "USER_INSTRUCTION_DISPOSITION"));
        adapter.call(plannerRequest("pending",
                ChainWorkState.VALIDATING_PENDING_ITEM,
                "PENDING_ITEM_VALIDATION"));

        List<String> prompts = captured.stream()
                .map(request -> request.messages().get(0).content()).toList();
        assertThat(prompts.get(0)).contains(
                "Current Planner invocation stage is INITIAL_INTAKE",
                "NEED_USER_INPUT, NEED_PERMISSION, DIRECT_ROUTE, and PERSISTENT_PLAN");
        assertThat(prompts.get(1)).contains(
                "Current Planner invocation stage is PERSISTENT_PLAN",
                "already has a formal persistent RouteDecision",
                "Do not revisit the accepted route with DIRECT_ROUTE");
        assertThat(prompts.get(2)).contains(
                "Current Planner invocation stage is PLAN_REVISION",
                "bound a formal current Plan and an exact revision trigger",
                "NEED_USER_INPUT, NEED_PERMISSION, and PLAN_REVISION");
        assertThat(prompts.get(3)).contains(
                "Current Planner invocation stage is USER_INSTRUCTION_DISPOSITION",
                "classifying a supplement or correction",
                "Return USER_INSTRUCTION_DISPOSITION only after the instruction is fully classified",
                "never encode NEED_USER_INPUT or NEED_PERMISSION inside the classification field");
        assertThat(prompts.get(4)).contains(
                "Current Planner invocation stage is PENDING_ITEM_VALIDATION",
                "one exact bound PendingItem",
                "STILL_PENDING",
                "RESOLVED",
                "frozen review.resumePosition",
                "answeredGapId identify the answer");
        assertThat(prompts).allSatisfy(prompt -> {
            assertThat(prompt).contains(
                    "PLANNING_BLOCKED is exceptional");
            assertThat(prompt.indexOf("Current Planner invocation stage is"))
                    .isLessThan(prompt.indexOf(
                            "Return one valid json object only."));
        });
    }

    private static ChainModelCallRequest plannerRequest(
            String suffix, ChainWorkState state, String callReason) {
        return new ChainModelCallRequest(
                "invocation-" + suffix, "context-" + suffix,
                "completion-" + suffix, ChainRole.PLANNER, state,
                callReason, "provider-safe", "model-safe", "prompt", 1,
                false, null);
    }

    @Test
    void providerFailureExposesOnlySafeDiagnosticCategory() {
        ChatModelProvider directProvider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "deepseek";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new ModelProviderException(
                        "DeepSeek API error: HTTP 401 secret-body");
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("streaming is not used");
            }
        };
        ProductChainChatModelAdapter adapter =
                new ProductChainChatModelAdapter(
                        directProvider,
                        ignored -> new ProductChainModelEndpoint(
                                "deepseek", "deepseek-v4-flash", "key", null),
                        0.0d, 2_048, () -> 1_000_000L);

        ChainModelCallResult result = adapter.call(new ChainModelCallRequest(
                "invocation-failure", "context-safe", "completion-safe",
                ChainRole.PLANNER, ChainWorkState.PLANNING, "initial",
                "deepseek", "deepseek-v4-flash", PROMPT_CANARY, 1, false,
                null));

        assertThat(result).isInstanceOfSatisfying(
                ChainModelCallResult.Failure.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(
                            "MODEL_PROVIDER_UNAVAILABLE");
                    assertThat(failure.safeMetadata()).containsEntry(
                            "failureReason", "HTTP_401");
                    assertThat(failure.safeMetadata().values())
                            .doesNotContain("secret-body");
                });
    }

    @Test
    void exhaustedProviderBalanceTakesPriorityOverGenericInvalidRequest() {
        ChatModelProvider directProvider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "deepseek";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new ModelProviderException(
                        "DeepSeek API error: HTTP 402 "
                                + "{\"type\":\"unknown_error\","
                                + "\"code\":\"invalid_request_error\","
                                + "\"message\":\"Insufficient Balance\"}");
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("streaming is not used");
            }
        };
        ProductChainChatModelAdapter adapter =
                new ProductChainChatModelAdapter(
                        directProvider,
                        ignored -> new ProductChainModelEndpoint(
                                "deepseek", "deepseek-v4-flash", "key", null),
                        0.0d, 2_048, () -> 1_000_000L);

        ChainModelCallResult result = adapter.call(new ChainModelCallRequest(
                "invocation-balance", "context-safe", "completion-safe",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_EXECUTION", "deepseek", "deepseek-v4-flash",
                PROMPT_CANARY, 1, false, null));

        assertThat(result).isInstanceOfSatisfying(
                ChainModelCallResult.Failure.class, failure ->
                        assertThat(failure.safeMetadata()).containsEntry(
                                "failureReason",
                                "PROVIDER_QUOTA_OR_BALANCE_EXHAUSTED"));
    }

    @Test
    void successfulAttemptMaterializesOneBodyAndRefOnlyProposalAtomically() {
        List<String> writes = new ArrayList<>();
        ChainModelInvocationWriter invocations =
                new ChainModelInvocationWriter() {
                    @Override
                    public AppendResult<ModelInvocationRecord>
                            appendInvocation(ModelInvocationRecord invocation) {
                        throw new AssertionError(
                                "invocation is persisted before provider attempts");
                    }

                    @Override
                    public AppendResult<ProviderAttemptRecord>
                            appendProviderAttempt(
                                    ProviderAttemptRecord attempt) {
                        writes.add("attempt");
                        return new AppendResult<>(attempt, false);
                    }
                };
        ChainContentWriter contents = content -> {
            writes.add("content:" + content.contentId());
            return new AppendResult<>(content, false);
        };
        ChainProposalWriter proposals = proposal -> {
            writes.add("proposal:" + proposal.proposalId());
            return new AppendResult<>(proposal, false);
        };
        PlatformTransactionManager transactions = mock(
                PlatformTransactionManager.class);
        TransactionStatus transaction = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        ProductChainModelMaterializationAdapter adapter =
                new ProductChainModelMaterializationAdapter(
                        invocations, contents, proposals, transactions);
        ProviderAttemptRecord attempt = new ProviderAttemptRecord(
                "invocation.1", 1, "task.1", 7L, "stop",
                ValidationStatus.PASSED, ValidationStatus.PASSED,
                null, CREATED_AT);
        String extractedBody = "EXTRACTED-BODY-authoritative-only";
        ContentRecord content = new ContentRecord(
                "content.1", "task.1", "invocation.1",
                ChainContentKind.ANSWER_BODY, extractedBody,
                sha256(extractedBody), "text/plain", CREATED_AT);
        String refOnlyPayload =
                "{\"answerBodyRef\":\"content.1\"}";
        ModelProposalRecord proposal = new ModelProposalRecord(
                "proposal.1", "task.1", "invocation.1", 1,
                ChainRole.ANSWER, ChainProposalKind.ANSWER_DIRECT_ANSWER,
                canonical(refOnlyPayload), canonical("{\"refs\":[]}"),
                ChainContentKind.ANSWER_BODY.name(), "content.1",
                CREATED_AT);

        var result = adapter.persistSuccessfulAttempt(
                attempt, content, proposal);

        assertThat(writes).containsExactly(
                "attempt", "content:content.1", "proposal:proposal.1");
        assertThat(result.bodyContent()).isEqualTo(content);
        assertThat(result.proposal().payload().json())
                .isEqualTo(refOnlyPayload)
                .doesNotContain(extractedBody, RESPONSE_CANARY);
        assertThat(result.replayed()).isFalse();
        verify(transactions).commit(transaction);

        ModelProposalRecord forged = new ModelProposalRecord(
                "proposal.forged", "task.1", "invocation.1", 1,
                ChainRole.ANSWER, ChainProposalKind.ANSWER_DIRECT_ANSWER,
                canonical("{\"answerBodyRef\":\"content.forged\"}"),
                canonical("{\"refs\":[]}"),
                ChainContentKind.ANSWER_BODY.name(), "content.forged",
                CREATED_AT);
        assertThatThrownBy(() -> adapter.persistSuccessfulAttempt(
                attempt, content, forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly the materialized body");
    }

    private static CanonicalJson canonical(String json) {
        return new CanonicalJson(1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte element : digest) {
                output.append(String.format("%02x", element & 0xff));
            }
            return output.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
