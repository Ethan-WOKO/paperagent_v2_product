package com.yanban.api.agent.v2.chain.model;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import io.paperagent.v2.chain.model.ChainModelCallPort;
import io.paperagent.v2.chain.model.ChainModelCallRequest;
import io.paperagent.v2.chain.model.ChainModelCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Product bridge for the new chain model protocol.
 *
 * <p>This adapter calls the stable {@link ChatModelProvider} directly. It
 * never delegates to the legacy V2 provider adapter because that adapter logs
 * complete messages and responses. Raw prompt/repair/response bodies remain
 * transient here; ordinary logs and returned metadata contain identifiers,
 * endpoint names, token counts and timing only.</p>
 */
public final class ProductChainChatModelAdapter implements ChainModelCallPort {
    private static final Logger log = LoggerFactory.getLogger(
            ProductChainChatModelAdapter.class);
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    private static final double DEFAULT_TEMPERATURE = 0.0d;
    private static final String JSON_OUTPUT_INSTRUCTION =
            "Return one valid json object only. No markdown, prose, or code fences. "
                    + "Use exactly the root fields schemaVersion, kind, and payload; "
                    + "schemaVersion must be \"1\". Never emit runtime IDs, refs, "
                    + "versions, statuses, or authority fields not listed below.";

    private final ChatModelProvider provider;
    private final ProductChainModelEndpointResolver endpoints;
    private final double temperature;
    private final int maxOutputTokens;
    private final LongSupplier nanoTime;

    public ProductChainChatModelAdapter(
            ChatModelProvider provider,
            ProductChainModelEndpointResolver endpoints) {
        this(provider, endpoints, DEFAULT_TEMPERATURE,
                DEFAULT_MAX_OUTPUT_TOKENS, System::nanoTime);
    }

    ProductChainChatModelAdapter(
            ChatModelProvider provider,
            ProductChainModelEndpointResolver endpoints,
            double temperature,
            int maxOutputTokens,
            LongSupplier nanoTime) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        if (!Double.isFinite(temperature) || temperature < 0.0d) {
            throw new IllegalArgumentException(
                    "temperature must be finite and non-negative");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be positive");
        }
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public ChainModelCallResult call(ChainModelCallRequest request) {
        Objects.requireNonNull(request, "request");
        long startedAt = nanoTime.getAsLong();
        ProductChainModelEndpoint endpoint;
        try {
            endpoint = Objects.requireNonNull(
                    endpoints.resolve(request), "resolved endpoint");
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startedAt);
            Map<String, String> metadata = baseMetadata(
                    request, "unresolved", "unresolved");
            log.warn(
                    "chain model endpoint resolution failed invocationId={} attemptNo={} durationMs={} failureType={}",
                    request.invocationId(), request.attemptNo(), durationMs,
                    exception.getClass().getSimpleName());
            return new ChainModelCallResult.Failure(
                    "MODEL_ENDPOINT_UNAVAILABLE", "error", durationMs,
                    metadata);
        }
        if (!endpoint.provider().equals(request.expectedProvider())
                || !endpoint.model().equals(request.expectedModel())) {
            long durationMs = elapsedMillis(startedAt);
            Map<String, String> metadata = baseMetadata(
                    request, endpoint.provider(), endpoint.model());
            log.warn(
                    "chain model endpoint identity mismatch invocationId={} attemptNo={} expectedProvider={} expectedModel={} actualProvider={} actualModel={} durationMs={}",
                    request.invocationId(), request.attemptNo(),
                    request.expectedProvider(), request.expectedModel(),
                    endpoint.provider(), endpoint.model(), durationMs);
            return new ChainModelCallResult.Failure(
                    "MODEL_ENDPOINT_IDENTITY_MISMATCH", "error",
                    durationMs, metadata);
        }

        ChatResponse response;
        try {
            response = provider.chat(chatRequest(request, endpoint));
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startedAt);
            LinkedHashMap<String, String> failureMetadata = new LinkedHashMap<>(
                    baseMetadata(request, endpoint.provider(), endpoint.model()));
            String failureReason = safeFailureReason(exception);
            failureMetadata.put("failureReason", failureReason);
            log.warn(
                    "chain model call failed invocationId={} attemptNo={} provider={} model={} promptChars={} repairChars={} durationMs={} failureType={} failureReason={}",
                    request.invocationId(), request.attemptNo(),
                    endpoint.provider(), endpoint.model(),
                    request.canonicalPrompt() == null ? 0 : request.canonicalPrompt().length(),
                    request.repairFeedback() == null ? 0 : request.repairFeedback().length(),
                    durationMs,
                    exception.getClass().getSimpleName(), failureReason);
            return new ChainModelCallResult.Failure(
                    "MODEL_PROVIDER_UNAVAILABLE", "error", durationMs,
                    Map.copyOf(failureMetadata));
        }

        long durationMs = elapsedMillis(startedAt);
        String finishReason = safeFinishReason(
                response == null ? null : response.finishReason());
        Map<String, String> metadata = responseMetadata(
                request, endpoint, response);
        if (response == null || response.assistantText() == null
                || response.assistantText().isBlank()
                || (response.toolCalls() != null
                && !response.toolCalls().isEmpty())) {
            log.warn(
                    "chain model response invalid invocationId={} attemptNo={} provider={} model={} finishReason={} durationMs={}",
                    request.invocationId(), request.attemptNo(),
                    endpoint.provider(), endpoint.model(), finishReason,
                    durationMs);
            return new ChainModelCallResult.Failure(
                    "MODEL_PROVIDER_RESPONSE_INVALID", finishReason,
                    durationMs, metadata);
        }

        log.info(
                "chain model call completed invocationId={} attemptNo={} provider={} model={} finishReason={} durationMs={} promptTokens={} completionTokens={} totalTokens={}",
                request.invocationId(), request.attemptNo(),
                endpoint.provider(), endpoint.model(), finishReason,
                durationMs, metadata.get("promptTokens"),
                metadata.get("completionTokens"),
                metadata.get("totalTokens"));
        return new ChainModelCallResult.Success(
                response.assistantText(), finishReason, durationMs, metadata);
    }

    private ChatRequest chatRequest(
            ChainModelCallRequest source,
            ProductChainModelEndpoint endpoint) {
        List<ChatMessage> messages = new ArrayList<>();
        String providerVisiblePrompt = switch (source.role()) {
            case PLANNER -> ProductPlannerModelContextView.project(
                    source.canonicalPrompt());
            case EXECUTOR -> ProductExecutorModelContextView.project(
                    source.canonicalPrompt());
            default -> source.canonicalPrompt();
        };
        String systemInstruction = source.role()
                == io.paperagent.v2.chain.ChainRole.PLANNER
                ? plannerSystemInstruction(source)
                : JSON_OUTPUT_INSTRUCTION
                + genericRoleInstruction(source)
                + roleProtocolChecklist(source);
        messages.add(ChatMessage.system(systemInstruction));
        messages.add(ChatMessage.user(providerVisiblePrompt));
        if (source.protocolRepair()) {
            // PROCESS is an internal chain role and is not accepted by the
            // OpenAI-compatible provider message schema. Keep the repair
            // boundary in the chain request while adapting it to a standard
            // provider-visible user message.
            if (source.previousInvalidOutput() != null) {
                messages.add(ChatMessage.assistant(
                        source.previousInvalidOutput()));
            }
            messages.add(ChatMessage.user(repairInstruction(
                    source.repairFeedback())));
            if (source.role() == io.paperagent.v2.chain.ChainRole.REFLECTOR) {
                log.warn("chain reflector protocol repair feedback invocationId={} attemptNo={} feedback={}",
                        source.invocationId(), source.attemptNo(), source.repairFeedback());
            }
        }
        return new ChatRequest(
                endpoint.provider(),
                endpoint.model(),
                List.copyOf(messages),
                temperature,
                maxOutputTokens,
                List.of(),
                endpoint.apiKey(),
                endpoint.apiUrl(),
                ChatRequest.ResponseFormat.jsonObject(),
                ChatRequest.Thinking.disabled(),
                source.invocationId() + ".attempt." + source.attemptNo());
    }

    private static String repairInstruction(String feedback) {
        return "The previous provider response was rejected by the typed protocol. "
                + "Treat the previous assistant JSON as the repair draft. Preserve every field and value "
                + "that the validation feedback does not require changing, and update all dependent "
                + "cross-field copies together. Never silence one validation error by deleting another "
                + "required structure. "
                + "Generate a complete replacement root JSON object from the same frozen Context; "
                + "do not return a patch, explanation, abbreviated fallback, or only the corrected field. "
                + "This is a schema/protocol repair, not new evidence that the task is blocked. Preserve "
                + "the intended semantic proposal kind only when the validation feedback does not reject "
                + "that kind; otherwise choose a kind allowed by the frozen call reason and visible role "
                + "schema. After applying the feedback, recheck every required field and cross-field "
                + "rule in the visible schema, not only the field named by the latest diagnostic. "
                + "Apply this validation feedback: " + feedback;
    }

    private static String genericRoleInstruction(
            ChainModelCallRequest source) {
        return " Act only as the " + source.role().name()
                + " role. The frozen canonical Context in the user message is "
                + "the sole authority for the role schema, semantic rules, "
                + "permissions, tools, constraints, source references, and "
                + "evidence. Return exactly one JSON object that matches the "
                + "visible typed role schema, including every required field "
                + "and no additional field. Use only exact visible authority "
                + "references and facts. When required authority is absent or "
                + "conflicts, use the applicable blocked, input, permission, "
                + "failure, or delivery form allowed by that visible schema; "
                 + "never invent a reference or silently perform another role.";
    }

    private static String plannerSystemInstruction(
            ChainModelCallRequest source) {
        String stage = switch (source.callReason()) {
            case "INITIAL_INTAKE" ->
                    "Current Planner invocation stage is INITIAL_INTAKE. The chain selected this stage "
                            + "from the frozen task state; do not infer a different stage from user prose. "
                            + "Classify the new effective request. Allowed ordinary root kinds are "
                            + "NEED_USER_INPUT, NEED_PERMISSION, DIRECT_ROUTE, and PERSISTENT_PLAN. "
                            + "PLAN_REVISION and USER_INSTRUCTION_DISPOSITION are forbidden because no prior "
                            + "Plan or supplement classification is being handled. ";
            case "PERSISTENT_PLAN" ->
                    "Current Planner invocation stage is PERSISTENT_PLAN. The chain already has a formal "
                            + "persistent RouteDecision and is continuing Plan materialization without a current "
                            + "Plan binding. Allowed ordinary root kinds are NEED_USER_INPUT, NEED_PERMISSION, "
                            + "and PERSISTENT_PLAN. Do not revisit the accepted route with DIRECT_ROUTE; "
                            + "PLAN_REVISION and USER_INSTRUCTION_DISPOSITION are forbidden. ";
            case "PLAN_REVISION" ->
                    "Current Planner invocation stage is PLAN_REVISION. The chain has bound a formal current "
                            + "Plan and an exact revision trigger. Allowed ordinary root kinds are "
                            + "NEED_USER_INPUT, NEED_PERMISSION, and PLAN_REVISION. DIRECT_ROUTE, "
                            + "PERSISTENT_PLAN, and USER_INSTRUCTION_DISPOSITION are forbidden. Every Step "
                            + "shown as COMPLETED must be copied into newRevisionDraft without changing its "
                            + "stepKey, fields, dependencies, or order. Do not make completed work depend on "
                            + "a newly added repair Step. Replace the current ACTIVE failed or superseded Step "
                            + "with a new stepKey, and update later dependencies to use the replacement. An "
                            + "accepted result belonging to an unchanged copied COMPLETED Step remains "
                            + "APPLICABLE; do not mark it NOT_APPLICABLE while stating that it is preserved. ";
            case "USER_INSTRUCTION_DISPOSITION" ->
                    "Current Planner invocation stage is USER_INSTRUCTION_DISPOSITION. The chain is classifying "
                            + "a supplement or correction against an existing task before any continuation or "
                            + "replacement intake. Allowed ordinary root kinds are NEED_USER_INPUT, "
                            + "NEED_PERMISSION, and USER_INSTRUCTION_DISPOSITION. Return "
                            + "USER_INSTRUCTION_DISPOSITION only after the instruction is fully classified. "
                            + "If classification lacks a unique referent or other required information, return "
                            + "the root kind NEED_USER_INPUT; never encode NEED_USER_INPUT or NEED_PERMISSION "
                            + "inside the classification field. DIRECT_ROUTE, PERSISTENT_PLAN, and "
                            + "PLAN_REVISION are forbidden. ";
            case "PENDING_ITEM_VALIDATION" ->
                    "Current Planner invocation stage is PENDING_ITEM_VALIDATION. The chain is validating the "
                            + "user response to the one exact bound PendingItem, not starting a new request. First "
                            + "compare the answer with every visible closing condition. If any condition remains "
                            + "open, return NEED_USER_INPUT with gapValidation outcome STILL_PENDING and a precise "
                            + "remaining question. If all conditions are closed, set gapValidation outcome RESOLVED "
                            + "and continue with the normal Planner root kind required by the frozen "
                            + "review.resumePosition. The current instruction relation and answeredGapId identify "
                            + "the answer; do not reinterpret that answer as an initial request or choose a new "
                            + "stage from its prose. ";
            default -> "Current Planner invocation reason is " + source.callReason()
                    + ". No dedicated stage-selection guidance is registered for this reason. Use the frozen "
                    + "work state, call reason, and visible typed role schema without inventing another stage. ";
        };
        return stage
                + JSON_OUTPUT_INSTRUCTION
                + " Act only as the PLANNER role. The frozen canonical Context in the user message is the "
                + "sole authority for the effective instruction, current stage, formal state, permissions, "
                + "available capabilities, exact references, and output schema. Return exactly one complete "
                + "root object matching one allowed Planner variant, with every required field and no extra field. "
                + "Do not execute work, invent facts or references, or perform another role. "
                + "Decision procedure, in order: (1) obey the frozen invocation stage; (2) validate a bound "
                + "PendingItem when and only when this is PENDING_ITEM_VALIDATION; (3) return NEED_USER_INPUT "
                + "when information required to classify or plan the effective request is missing; (4) return "
                + "NEED_PERMISSION when required authority is not currently granted but can be requested; "
                + "(5) at initial intake, decide DIRECT_ROUTE versus PERSISTENT_PLAN from the requested work; "
                + "(6) construct the stage-appropriate Plan, revision, or instruction disposition; (7) verify "
                + "the selected variant and all cross-field copies before responding. "
                + "Routing boundary definitions: needsProject is true whenever satisfying the request requires "
                + "reading, inspecting, citing, comparing, or changing content in the current Project, even when "
                + "the user explicitly forbids modification. It is false only when the Project is merely attached "
                + "and the effective request can be answered independently of its contents. needsTool is true "
                + "whenever satisfying the request requires observing unavailable content, reading files, "
                + "retrieval, command execution, testing, or another product capability; analysis is not direct "
                + "when its required facts must first be obtained by a tool. needsNetwork is true whenever the "
                + "requested result requires external or current information. If network access is not granted, "
                + "return NEED_PERMISSION before planning; once it is granted, the network-dependent request "
                + "requires PERSISTENT_PLAN. needsPersistentProgress is true when the requested work itself "
                + "requires durable multi-step progress; never infer it merely because PERSISTENT_PLAN was chosen. "
                + "DIRECT_ROUTE is legal only when needsTool, needsNetwork, needsProject, and "
                + "needsPersistentProgress are all false. When returning DIRECT_ROUTE, answer the effective user "
                + "request completely in payload.inlineAnswerBody during this same Provider call. "
                + "inlineAnswerBody is required nonblank user-visible prose, not an authority identifier. "
                + "Never return answerBodyRef or any other persisted body reference; those fields are runtime-owned "
                + "and forbidden in Provider output. PERSISTENT_PLAN is legal when at least one boundary is true. "
                + "The presence of a Project, ProjectVersion, capability, tool category, or permission alone does "
                + "not make a boundary true; the effective request does. "
                + "PLANNING_BLOCKED is exceptional and requires a genuine visible formal conflict or blocker with "
                + "exact knownFactRefs. Difficulty, uncertainty, missing user information, or missing permission "
                + "are not PLANNING_BLOCKED; use NEED_USER_INPUT or NEED_PERMISSION. "
                + "For NEED_PERMISSION, lowerPrivilegeAlternative is always a nonblank truthful alternative or "
                + "an explicit statement that no lower-privilege alternative can satisfy the request. "
                + "For PERSISTENT_PLAN, stableOrder is contiguous from 1. TaskFrame requirements are EXPLICIT. "
                + "Every validation requirement has one stable ID bound exactly once by one Step's "
                + "validationRequirementIds, and its completionCondition is copied byte-for-byte into that same "
                + "Step's completionConditions. A Candidate-changing Plan declares exactly one aggregate "
                + "CANDIDATE validation requirement. Always bind it to a later non-changing validation Step; "
                + "that Step must depend directly or transitively on the last Candidate-changing Step. Never bind "
                + "a CANDIDATE validation requirement to a Step with mayChangeCandidate=true because Workspace "
                + "modification creates a Candidate but does not create the formal validation Receipt. "
                + "The exact completionCondition is also the complete candidateValidationCompletionCondition of "
                + "the Step that owns the validation requirement. Choose the suitable validation action from the "
                + "artifact type and request; do not ask the user to choose an ordinary validation method. "
                + "Keep other Step checks as ordinary completionConditions and use ACTION_RECEIPT only for an "
                + "executed-action receipt. An absent candidateValidationCompletionCondition is JSON null, never "
                + "an empty string. When the user explicitly requests a Project change, publishRequirement is "
                + "REQUIRED; use NOT_REQUIRED only for an explicit preview, proposal, Candidate-only result, or "
                + "instruction not to apply the change. An explicit ordinary Project modification request already "
                + "authorizes isolated Workspace modification and automatic publication after exact successful "
                + "validation; do not request another permission or confirmation for those ordinary operations. "
                + "PLANNED and UNSATISFIED coverage use factRefs []; "
                + "SATISFIED coverage uses exact visible fact refs. Every *Refs field contains only exact visible "
                + "authority identifiers; paths and descriptive text belong in scopes, constraints, deliverables, "
                + "or completionConditions. gapValidation is JSON null outside PENDING_ITEM_VALIDATION. During "
                + "pending validation, every check copies its visible closingCondition and uses the exact visible "
                + "authority ref for the user's answer; outcome is RESOLVED exactly when every check is satisfied.";
    }

    private static String roleProtocolChecklist(
            ChainModelCallRequest source) {
        if (source.role() == io.paperagent.v2.chain.ChainRole.ANSWER
                && ("TASK_OUTCOME".equals(source.callReason())
                || "DIRECT_ROUTE".equals(source.callReason()))) {
            return " Answer output checklist: runtime.answerPayloadTemplate is the exact "
                    + "selected root JSON object mechanically bound to the formal terminal authorities. "
                    + "Copy its root object completely and exactly, preserving schemaVersion, kind, every "
                    + "reference field, array value, and order; replace only payload.inlineAnswerBody with "
                    + "the user-visible answer supported by the same frozen formal facts.";
        }
        if (source.role() == io.paperagent.v2.chain.ChainRole.EXECUTOR) {
            return " Executor protocol checklist: ToolAction priorErrorRef, priorActionRef, "
                    + "changeFromPriorAction, and expectedProgress form one all-or-none group. "
                    + "A possible future failure mentioned in the instruction is not a prior failed action. "
                    + "Unless the frozen Context contains both an exact prior error authority and its exact prior "
                    + "action authority, emit this exact abstract fragment: "
                    + "\"priorErrorRef\":null,\"priorActionRef\":null,\"changeFromPriorAction\":null,"
                    + "\"expectedProgress\":null. Put the current action's expected outputs only in "
                    + "expectedOutputs; expectedProgress is exclusively the progress expected from changing "
                    + "an exact prior failed action, so it is JSON null when there is no such prior action. "
                    + "For a tool-action form, toolId is the exact descriptor.id from one visible "
                    + "completeToolSchemas entry and requiredPermission is that same entry's exact permissionRef; "
                    + "never substitute a public alias or capability name for either field. "
                    + "When repairing, all four are nonblank and priorErrorRef and priorActionRef "
                    + "are exact visible authority identifiers. A tool-action form represents exactly the single "
                    + "tool invocation encoded by completeArguments: purpose, writeScopes, and "
                    + "changeFromPriorAction must not claim a mutation that invocation cannot perform. "
                    + "When progress requires changing workspace content but the available invocation only "
                    + "reads or executes it, return the workspace-change form with the exact canonical change first; "
                    + "a later call can execute or validate the changed workspace; compare every proposed mutation "
                    + "with the exact current Candidate visible in frozen Context. If that Candidate already contains "
                    + "the intended file contents, do not propose another mutation with those same contents; select "
                    + "the next unmet completion condition, or return the completed result only when all completion "
                    + "conditions already have formal support. For a workspace-change form, "
                    + "baseCandidateRef copies the exact visible frozen base Candidate reference, using the visible "
                    + "NONE literal only when that boundary has no base Candidate; it is never blank. "
                    + "For each canonical file change, expectedBaselineSha256 identifies the file content currently "
                    + "being changed: when the same path has a visible Candidate overlay, copy that overlay's exact "
                    + "effectiveSha256 (the resultSha256), not its baseSha256 or the Project manifest sha256; only "
                    + "when no Candidate overlay exists for that path, copy the Project file sha256. For an ADD of "
                    + "a path that does not currently exist, use the exact NONE literal. "
                    + "manifestChanges is an empty JSON array because separate manifest mutation is unsupported; "
                    + "file additions and deletions are represented inside the canonical changes array. "
                    + "For a step-result form, validationSources contains exactly one binding for every ID in "
                    + "the active Step validationRequirementIds and no other ID; each binding uses a visible formal "
                    + "Receipt and that receiptRef also appears in receiptRefs. Use [] only when the active Step has "
                    + "no validation requirement IDs.";
        }
        if (source.role() == io.paperagent.v2.chain.ChainRole.REFLECTOR
                && formalFailureReview(source.callReason())) {
            return " Reflector formal-failure review checklist: this invocation reviews an already "
                    + "formal failure authority, not a CandidateStepResult. The root kind must be "
                    + "the replan-required or task-failed form allowed by the visible schema; never return a Step accept "
                    + "or Step continue kind. Bind review.reviewedObjectRefs to the exact visible formal "
                    + "failure object, and include every exact visible direct failure fact in "
                    + "review.directFactRefs. Use the replan form only when a revised Plan can make "
                    + "progress; use the task-failure form only when the formal facts show no valid continuation.";
        }
        if (source.role() == io.paperagent.v2.chain.ChainRole.REFLECTOR) {
            return " Reflector candidate-review checklist: when returning the step-accept "
                    + "or combined step-accept-and-ready-to-finalize form, conditionJudgements and "
                    + "artifactReceiptCandidateValidationEvidenceRefs are two separate required "
                    + "non-empty lists and both must be present in the same response. "
                    + "conditionJudgements assesses every visible Step completion condition and uses "
                    + "only exact visible fact refs; artifactReceiptCandidateValidationEvidenceRefs "
                    + "lists the exact visible artifact, receipt, candidate, or validation authorities "
                    + "that support the acceptance. candidateResultId, taskFrameRef, planRevisionRef, "
                    + "stepRef must also be exact visible authorities. candidateRef is the exact visible "
                    + "WorkspaceCandidate authority bound to the reviewed result; when that result has no "
                    + "WorkspaceCandidate, candidateRef must instead be the literal string NONE. After any "
                    + "repair, recheck the complete selected kind instead of omitting another required list. "
                    + "For every authority-assessment object present in the selected form, the fields are "
                    + "mutually dependent: use {\"status\":\"BOUND\",\"authorityRef\":\"exact-visible-ref\","
                    + "\"reason\":null} when authority is bound; use, for example, "
                    + "{\"status\":\"MISSING\",\"authorityRef\":null,\"reason\":\"nonblank reason\"} "
                    + "when it is not bound. Never use an empty string in place of JSON null."
                    + " In the combined step-accept-and-ready-to-finalize form, the root review object and "
                    + "acceptance.review are two copies of one common ReviewCommon value: copy every field "
                    + "and array element byte-for-byte, including order. Do not summarize or independently "
                    + "rewrite the nested copy. In that combined form, finalization.validationAssessment and "
                    + "finalization.publishRequirementAssessment assess requirement declarations in the frozen "
                    + "TaskFrame, not a validation receipt, Candidate, or other evidence authority. When the "
                    + "corresponding requirement is declared, use status BOUND, copy acceptance.taskFrameRef "
                    + "exactly as authorityRef, and use JSON-null reason. When it is not declared, use status "
                    + "NOT_REQUIRED, JSON-null authorityRef, and a nonblank reason.";
        }
        return "";
    }

    private static boolean formalFailureReview(String callReason) {
        return List.of(
                "MODEL_CALL_FAILED_REVIEW",
                "CONTEXT_BUILD_FAILURE_REVIEW",
                "ACTION_FAILURE_REVIEW").contains(callReason);
    }

    private Map<String, String> responseMetadata(
            ChainModelCallRequest request,
            ProductChainModelEndpoint endpoint,
            ChatResponse response) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(
                baseMetadata(request, endpoint.provider(), endpoint.model()));
        ChatResponse.Usage usage = response == null ? null : response.usage();
        metadata.put("promptTokens", tokenCount(
                usage == null ? null : usage.promptTokens()));
        metadata.put("completionTokens", tokenCount(
                usage == null ? null : usage.completionTokens()));
        metadata.put("totalTokens", tokenCount(
                usage == null ? null : usage.totalTokens()));
        return Map.copyOf(metadata);
    }

    private static Map<String, String> baseMetadata(
            ChainModelCallRequest request,
            String provider,
            String model) {
        return Map.of(
                "provider", provider,
                "model", model,
                "attemptNo", Integer.toString(request.attemptNo()),
                "protocolRepair", Boolean.toString(request.protocolRepair()));
    }

    private long elapsedMillis(long startedAt) {
        long elapsedNanos = nanoTime.getAsLong() - startedAt;
        return Math.max(0L, elapsedNanos / 1_000_000L);
    }

    private static String safeFinishReason(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    /**
     * Keep provider diagnostics useful without copying response bodies, URLs,
     * authorization values, or other credential-bearing exception text into
     * chain logs or persisted safe metadata.
     */
    private static String safeFailureReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            // Provider error envelopes commonly use invalid_request_error for
            // exhausted account credit. Classify the specific cause before
            // the generic request category so operators do not investigate a
            // valid Context or protocol request as malformed.
            if (normalized.contains("insufficient balance")
                    || normalized.contains("insufficient quota")
                    || normalized.contains("quota exceeded")
                    || normalized.contains("billing hard limit")) {
                return "PROVIDER_QUOTA_OR_BALANCE_EXHAUSTED";
            }
            if (normalized.contains("context_length")
                    || normalized.contains("context length")
                    || normalized.contains("maximum context")
                    || normalized.contains("too long")) {
                return "HTTP_400_CONTEXT_LENGTH";
            }
            if (normalized.contains("invalid_request_error")
                    || normalized.contains("invalid request")) {
                return "HTTP_400_INVALID_REQUEST";
            }
            if (normalized.contains("context") || normalized.contains("prompt")) {
                return "HTTP_400_CONTEXT_OR_PROMPT";
            }
            if (normalized.contains("thinking")) {
                return "HTTP_400_THINKING";
            }
            if (normalized.contains("response_format")) {
                return "HTTP_400_RESPONSE_FORMAT";
            }
            if (normalized.contains("apikey") || normalized.contains("api key")) {
                return "API_KEY_NOT_CONFIGURED";
            }
            if (normalized.contains("apiurl") || normalized.contains("api url")) {
                return "API_URL_NOT_CONFIGURED";
            }
            if (normalized.contains("transport") || normalized.contains("timeout")
                    || normalized.contains("connection")) {
                return "TRANSPORT_FAILURE";
            }
            java.util.regex.Matcher http = java.util.regex.Pattern
                    .compile("HTTP\\s+(\\d{3})")
                    .matcher(message);
            if (http.find()) {
                return "HTTP_" + http.group(1);
            }
        }
        return exception.getClass().getSimpleName();
    }

    private static String tokenCount(Integer value) {
        return value == null || value < 0 ? "unknown" : value.toString();
    }
}
