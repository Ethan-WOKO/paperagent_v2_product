package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.api.agent.worker.ControlledWorkerExecutionScope;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LangChain4jToolCallingStrategy {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolCallingStrategy.class);
    private static final int PROJECT_TOOL_EXTENSION_BATCH = 4;
    private static final int PROJECT_TOOL_HARD_LIMIT = 24;
    private static final Set<String> ASYNC_NON_TERMINAL_STATES = Set.of("RUNNING", "WAITING");
    private static final Set<String> ASYNC_TERMINAL_STATES = Set.of(
            "COMPLETED", "DONE", "SUCCEEDED", "FAILED", "ERROR", "CANCELLED", "STOPPED");
    private static final Pattern DANGLING_CODE_CONDITION = Pattern.compile(
            "(?i)^\\s*(?:if|elif|while|for)\\b.{0,240}\\b(?:is|not|and|or|in)\\s*$");
    private static final String TOOL_ROUTING_SYSTEM_PROMPT = """
            You may decide whether to answer directly or call tools.
            Prefer a tool from the current tool specifications over guessing when evidence is needed.
            Never invent a tool name or request a tool that is absent from the current tool specifications.
            If no tool is needed, answer directly and concisely.
            """;
    private static final String PROJECT_READ_SYSTEM_PROMPT = """
            This request is bound to an authenticated read-only Project. Use only the Project tools
            exposed for this request and only Project-relative paths. The server supplies Project identity;
            never send or guess projectId in tool arguments. project_manifest provides safe
            inventory metadata, but it is not file-content evidence. Prefer a specialized Project research
            tool when its purpose matches the request; its typed evidence is current file-content evidence.
            Otherwise, before making Project conclusions, capture at least one relevant current observation
            with project_read_file or project_search. Follow each tool's JSON schema exactly, especially
            array-valued relativePaths fields.
            project_propose_candidate is the only proposal entry point. Call it only when the user explicitly asks
            for changes and after current Project tools have returned exact portable Evidence provenance. Supply full
            replacement text and never place patch JSON in ordinary assistant text. Each Evidence selector must copy
            the complete path, hash, startLine, endLine, and parserVersion from one completed observation exactly.
            A smaller range inside a larger project_read_file result is not an exact match: first call project_read_file
            again with the precise startLine/endLine you intend to cite, then use that identical range in the proposal.
            A successful proposal remains
            NOT_APPLIED and never changes Project files.
            To inspect all applicable files, call project_manifest, select its concrete relative paths, and
            pass those paths to the specialized tool. Never use ".", "*", "**", or "/" as a file path.
            For project_cross_material_search on a large or unfamiliar Project, first use project_manifest,
            pass selected concrete files through relativePaths, and normally keep maxMatches between 10 and 20.
            After an input/result byte-budget error, narrow relativePaths instead of repeating a whole-Project search.
            For a directory overview, use the manifest to locate candidates, then inspect relevant files
            and clearly distinguish path-based inference from file-content findings. Never claim a complete
            review beyond the observations retrieved in this turn.

            Treat typed research-tool items as the source of truth. Before stating any numeric count, derive
            it from the matching typed items and verify that it equals the entries you enumerate. If the count
            and enumeration cannot be reconciled, omit the numeric total and report the observed entries only.

            Return one coherent final answer rather than separate answers or concatenated drafts. When using
            Markdown, keep every heading to a short standalone phrase on its own line, put a space after the
            heading marker (for example, "## Purpose"), and start the explanatory prose on a new line below it.
            Never format a complete sentence or paragraph as a heading. Use a hyphen followed by a space ("- ")
            for unordered list items.
            """;
    private static final String TOOL_BUDGET_FINAL_ANSWER_PROMPT = """
            The tool-call budget has been reached. Do not call any more tools.
            Use only the conversation and tool results already available to produce the best final answer.
            If evidence is incomplete, say that briefly and explain what can be concluded from the retrieved results.
            """;
    private static final String RESERVED_FINAL_ANSWER_PROMPT = """
            This is the reserved final-synthesis round. Do not call any more tools.
            Use only the conversation and tool results already available to produce one complete final answer.
            Preserve every requested conclusion section and state any evidence limitation explicitly.
            """;
    private static final String SUCCESSFUL_CANDIDATE_FINAL_ANSWER_PROMPT = """
            A governed Candidate proposal was created successfully. Do not call any more tools and do not create
            another Candidate. Summarize the latest successful Candidate tool result, including its target files,
            validation state, and NOT_APPLIED application status. Never claim that Project files were changed.
            """;
    private static final String NO_PROGRESS_FINAL_ANSWER_PROMPT = """
            Every tool request in the latest assistant step duplicated an earlier invocation, so no new
            tool execution was performed. Do not call any more tools. Use the tool results already present
            in the conversation to produce the best final answer. If the evidence is incomplete, state the
            limitation briefly instead of repeating a tool call.
            """;
    private static final String MISSING_TARGET_FINAL_ANSWER_PROMPT = """
            An explicitly requested Project file was reported NOT_FOUND by project_read_file.
            Do not call more tools and do not search for, read, or use alternative files as substitutes.
            Report the exact missing path and state that findings for that material and any comparison that
            requires it cannot be determined. Do not infer implementation details, consistency, differences,
            line locations, or missing modules from filenames, class names, the manifest, or unrelated files.
            Preserve only observations already obtained for other explicitly requested materials.
            """;
    private static final String COMPACT_REWRITE_AFTER_TRUNCATION_PROMPT = """
            The previous answer was cut off by the model output limit. Do not call tools.
            Rewrite the answer once as a compact but complete final response using only the evidence already present.
            Preserve every required conclusion section, concrete evidence location, difference, and limitation.
            Remove repetition and background detail before omitting any requested section. End with a complete sentence.
            """;
    private final LangChain4jChatModelAdapter chatModel;
    private final LangChain4jToolProvider toolProvider;
    private final ObjectMapper objectMapper;

    public LangChain4jToolCallingStrategy(LangChain4jChatModelAdapter chatModel,
                                          LangChain4jToolProvider toolProvider,
                                          ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
    }

    public boolean supports(AgentRuntimeRequest request) {
        return request != null && request.toolCallingMode() == AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING;
    }

    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>(toLangChainMessages(request.history()));
        messages.add(SystemMessage.from(TOOL_ROUTING_SYSTEM_PROMPT));
        if (request.projectContext() != null) {
            messages.add(SystemMessage.from(PROJECT_READ_SYSTEM_PROMPT));
        }
        if (StringUtils.hasText(request.skillPrompt())) {
            messages.add(SystemMessage.from(request.skillPrompt()));
        }
        messages.add(UserMessage.from(request.userMessage()));

        List<String> toolTrace = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        Map<String, InvocationState> invocationStates = new LinkedHashMap<>();
        RepairContext pendingRepair = request.repairContext();
        if (pendingRepair != null) {
            ToolExecutionOutcome previousFailure = new ToolExecutionOutcome(false,
                    pendingRepair.toModelToolResult(objectMapper), pendingRepair.errorMessage(),
                    pendingRepair.errorCode(), pendingRepair.retryable(), false, false);
            invocationStates.put(pendingRepair.signature(objectMapper),
                    InvocationState.after(null, "previous-attempt", previousFailure, 0));
        }
        Set<String> verifiedObservations = new LinkedHashSet<>();
        int projectEvidenceEpoch = 0;
        Set<String> allowedTools = new LinkedHashSet<>(request.toolPolicy().allowedTools());
        Set<String> explicitProjectFiles = ProjectMaterialScope.explicitRelativePaths(request.userMessage());
        ToolProviderResult toolProviderResult = toolProvider.provideTools(request);
        List<ToolSpecification> toolSpecifications = new ArrayList<>(toolProviderResult.tools().keySet());
        TokenUsage totalUsage = null;
        int toolCalls = 0;
        int effectiveToolLimit = request.toolPolicy().maxToolCalls();
        int successfulCallsSinceExtension = 0;
        boolean adaptiveProjectBudget = request.projectContext() != null
                && !ControlledWorkerExecutionScope.isActive()
                && request.toolPolicy().maxToolCalls() >= 12;
        log.info("LangChain4j tool run start sessionId={} userId={} model={} allowedTools={} maxSteps={} maxToolCalls={} maxDuplicateToolCalls={}",
                request.sessionId(),
                request.userId(),
                request.model(),
                List.copyOf(allowedTools),
                request.maxSteps(),
                request.toolPolicy().maxToolCalls(),
                request.toolPolicy().maxDuplicateToolCalls());
        emitProcess(request, "正在分析问题，并判断是否需要调用工具。");

        for (int step = 0; step < request.maxSteps(); step++) {
            if (request.projectContext() != null && toolCalls > 0 && step == request.maxSteps() - 1) {
                String reason = "Reserved final synthesis round after " + toolCalls + " tool call(s)";
                fallbacks.add(reason);
                log.info("LangChain4j using reserved synthesis round sessionId={} maxSteps={} toolCalls={}",
                        request.sessionId(), request.maxSteps(), toolCalls);
                return finalAnswerWithoutMoreTools(
                        messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
            }
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .parameters(ChatRequestParameters.builder()
                            .modelName(request.model())
                            .temperature(request.temperature())
                            .maxOutputTokens(request.maxTokens())
                            .toolSpecifications(toolSpecifications)
                            .build())
                    .build();
            dev.langchain4j.model.chat.response.ChatResponse response = callModel(chatRequest, request);
            totalUsage = TokenUsage.sum(totalUsage, response == null ? null : response.tokenUsage());
            AiMessage aiMessage = response == null ? null : response.aiMessage();
            if (aiMessage == null) {
                fallbacks.add("langchain4j_empty_response");
                return failure(messages, toolTrace, fallbacks, step + 1, totalUsage, "LangChain4j returned an empty response");
            }
            messages.add(aiMessage);
            log.info("LangChain4j step={} assistantPreview={} toolRequests={}",
                    step + 1,
                    abbreviate(aiMessage.text()),
                    summarizeToolRequests(aiMessage.toolExecutionRequests()));

            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (requests == null || requests.isEmpty()) {
                String content = safeAssistantContent(aiMessage.text());
                if (indicatesTruncation(response.finishReason(), content)) {
                    return compactRewriteAfterTruncation(messages, toolTrace, fallbacks, step + 1,
                            totalUsage, request, content, "ordinary final response");
                }
                log.info("LangChain4j completed without tool call step={} assistantPreview={}",
                        step + 1,
                        abbreviate(content));
                emitProcess(request, toolCalls > 0 ? "工具结果已整理完成，正在生成最终回答。" : "已判断无需调用工具，正在生成最终回答。");
                if (!isStreaming(request)) {
                    emitToken(request, content);
                }
                return success(messages, toolTrace, fallbacks, step + 1, totalUsage, content);
            }

            emitProcess(request, "已决定调用 " + requests.size() + " 个工具：" + summarizeToolNames(requests));
            int newToolCallsThisStep = 0;
            int reusedToolCallsThisStep = 0;
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest toolRequest = requests.get(i);
                String signature = toolSignature(toolRequest.name(), toolRequest.arguments());
                InvocationState previous = invocationStates.get(signature);
                String repeatError = repeatError(toolRequest, previous,
                        request.toolPolicy().maxDuplicateToolCalls(), projectEvidenceEpoch);
                if (repeatError != null) {
                    String reason = repeatError + ": " + signature;
                    String reusedContent = reusedToolResult(previous, reason);
                    fallbacks.add("Tool result reused without re-execution: " + reason);
                    toolTrace.add(buildReusedToolTraceLine(step + 1, toolRequest, previous, reason));
                    messages.add(ToolExecutionResultMessage.from(
                            toolRequest.id(),
                            toolRequest.name(),
                            reusedContent
                    ));
                    reusedToolCallsThisStep++;
                    log.info("LangChain4j tool result reused step={} tool={} args={} priorSuccess={} originalToolCallId={} reason={}",
                            step + 1,
                            toolRequest.name(),
                            abbreviate(sanitizedArguments(toolRequest.arguments())),
                            previous.outcome().success(),
                            previous.sourceToolCallId(),
                            repeatError);
                    continue;
                }
                if (toolCalls >= effectiveToolLimit && adaptiveProjectBudget
                        && successfulCallsSinceExtension > 0 && effectiveToolLimit < PROJECT_TOOL_HARD_LIMIT) {
                    int previousLimit = effectiveToolLimit;
                    effectiveToolLimit = Math.min(PROJECT_TOOL_HARD_LIMIT,
                            effectiveToolLimit + PROJECT_TOOL_EXTENSION_BATCH);
                    successfulCallsSinceExtension = 0;
                    fallbacks.add("Project tool budget extended after verified progress: "
                            + previousLimit + "->" + effectiveToolLimit);
                    log.info("Project tool budget extended sessionId={} previousLimit={} effectiveLimit={} hardLimit={}",
                            request.sessionId(), previousLimit, effectiveToolLimit, PROJECT_TOOL_HARD_LIMIT);
                }
                if (toolCalls >= effectiveToolLimit) {
                    String error = adaptiveProjectBudget
                            ? "Tool-call budget exceeded: effectiveMaxToolCalls=" + effectiveToolLimit
                                    + ", hardMaxToolCalls=" + PROJECT_TOOL_HARD_LIMIT
                            : "Tool-call budget exceeded: maxToolCalls=" + effectiveToolLimit;
                    fallbacks.add(error);
                    addSkippedToolResults(messages, toolTrace, requests, i, step + 1, error);
                    return finalAnswerWithoutMoreTools(messages, toolTrace, fallbacks, step + 1, totalUsage, request, error)
                            .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
                }
                toolCalls++;
                newToolCallsThisStep++;
                boolean repairAttempt = pendingRepair != null;
                if (repairAttempt && (!pendingRepair.retryable() || pendingRepair.remainingAttempts() <= 0)) {
                    String reason = "Repair attempt unavailable for non-retryable or exhausted failure";
                    toolCalls--;
                    newToolCallsThisStep--;
                    addSkippedToolResults(messages, toolTrace, requests, i, step + 1, reason);
                    return finalAnswerWithoutMoreTools(
                            messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
                }
                if (repairAttempt) pendingRepair = pendingRepair.withRemainingAttempts(0);

                emitProcess(request, "正在调用工具：" + toolRequest.name());
                ToolExecutionOutcome toolResult;
                if (request.projectContext() == null) {
                    toolResult = executeTool(toolProviderResult, toolRequest, request.userId(), allowedTools);
                } else {
                    try (CandidateProposalExecutionScope ignored = CandidateProposalExecutionScope.open(
                            request, toCoreMessages(messages))) {
                        toolResult = executeTool(toolProviderResult, toolRequest, request.userId(), allowedTools);
                    }
                }
                if (toolResult.scopeRefinementRequired()) {
                    toolCalls--;
                    fallbacks.add("Tool scope refinement required; execution budget was not consumed: "
                            + toolRequest.name());
                }
                if (toolResult.success() && isProjectEvidenceObservation(toolRequest.name())) {
                    projectEvidenceEpoch++;
                }
                invocationStates.put(signature, InvocationState.after(
                        previous, toolRequest.id(), toolResult, projectEvidenceEpoch));
                if (toolResult.success() && !toolResult.scopeRefinementRequired()
                        && registerVerifiedNewObservation(toolResult.content(), verifiedObservations)) {
                    successfulCallsSinceExtension++;
                }
                log.info("LangChain4j tool step={} tool={} args={} success={} error={}",
                        step + 1,
                        toolRequest.name(),
                        abbreviate(sanitizedArguments(toolRequest.arguments())),
                        toolResult.success(),
                        toolResult.success() ? null : abbreviate(defaultString(toolResult.errorMessage(), "tool_failed")));
                RepairContext failureContext = null;
                if (!toolResult.success()) {
                    boolean canRepair = toolResult.retryable()
                            && !repairAttempt
                            && step + 1 < request.maxSteps()
                            && toolCalls < effectiveToolLimit;
                    failureContext = RepairContext.create(objectMapper, toolRequest.name(), toolRequest.arguments(),
                            toolResult.errorCode(), toolResult.errorMessage(), canRepair, canRepair ? 1 : 0);
                    fallbacks.add(failureContext.toFallback(objectMapper));
                }
                toolTrace.add(buildToolTraceLine(step + 1, toolRequest, toolResult, failureContext));
                emitProcess(request, toolResult.success()
                        ? "工具调用完成：" + toolRequest.name()
                        : "工具调用失败：" + toolRequest.name() + "，" + defaultString(toolResult.errorMessage(), "tool_failed"));
                messages.add(ToolExecutionResultMessage.from(
                        toolRequest.id(),
                        toolRequest.name(),
                        failureContext == null ? toolResult.content() : failureContext.toModelToolResult(objectMapper)
                ));
                String missingTarget = missingExplicitProjectFile(
                        explicitProjectFiles, toolRequest, toolResult);
                if (missingTarget != null) {
                    String reason = ProjectMaterialScope.MISSING_TARGET_PREFIX + " " + missingTarget;
                    fallbacks.add(reason);
                    addSkippedToolResults(messages, toolTrace, requests, i + 1, step + 1, reason);
                    log.info("LangChain4j stopping after explicit Project target was not found sessionId={} path={}",
                            request.sessionId(), missingTarget);
                    return finalAnswerWithoutMoreTools(
                            messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
                }
                if (request.projectContext() != null && toolResult.success()
                        && ProjectCandidateProposalToolExecutor.TOOL_NAME.equals(toolRequest.name())) {
                    String reason = "Successful governed Candidate proposal";
                    addSkippedToolResults(messages, toolTrace, requests, i + 1, step + 1, reason);
                    log.info("LangChain4j stopping after first successful Candidate proposal sessionId={} toolCalls={}",
                            request.sessionId(), toolCalls);
                    return finalAnswerWithoutMoreTools(
                            messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
                }
                if (failureContext != null) {
                    pendingRepair = failureContext;
                    String reason = failureContext.retryable()
                            ? "Awaiting one bounded parameter or method repair"
                            : "Tool failure is non-retryable or its repair budget is exhausted";
                    addSkippedToolResults(messages, toolTrace, requests, i + 1, step + 1, reason);
                    if (!failureContext.retryable()) {
                        return finalAnswerWithoutMoreTools(
                                messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
                    }
                    break;
                }
                if (repairAttempt) pendingRepair = null;
            }
            if (newToolCallsThisStep == 0 && reusedToolCallsThisStep > 0) {
                String reason = "No new tool progress: all " + reusedToolCallsThisStep
                        + " requested invocation(s) reused earlier results";
                fallbacks.add(reason);
                log.info("LangChain4j terminating repeated-tool loop step={} reusedToolCalls={} toolCalls={}",
                        step + 1, reusedToolCallsThisStep, toolCalls);
                return finalAnswerAfterNoProgress(
                        messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
            }
        }

        String reason = "Tool reasoning rounds reached maxSteps=" + request.maxSteps();
        fallbacks.add(reason);
        return finalAnswerWithoutMoreTools(messages, toolTrace, fallbacks, request.maxSteps(), totalUsage, request, reason)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED);
    }

    private AgentRuntimeResult finalAnswerWithoutMoreTools(List<dev.langchain4j.data.message.ChatMessage> messages,
                                                           List<String> toolTrace,
                                                           List<String> fallbacks,
                                                           int steps,
                                                           TokenUsage usage,
                                                           AgentRuntimeRequest request,
                                                           String reason) {
        boolean reservedSynthesis = reason != null && reason.startsWith("Reserved final synthesis round");
        boolean missingTarget = reason != null && reason.startsWith(ProjectMaterialScope.MISSING_TARGET_PREFIX);
        boolean successfulCandidate = reason != null && reason.startsWith("Successful governed Candidate proposal");
        emitProcess(request, successfulCandidate
                ? "Candidate proposal created. Generating its final NOT_APPLIED summary."
                : missingTarget
                ? "The explicitly requested Project file was not found. Generating a bounded result without substitutes."
                : reservedSynthesis
                ? "Using the reserved final round to synthesize the answer from existing results."
                : "Tool-call budget reached. Generating the final answer from existing results.");
        messages.add(SystemMessage.from(successfulCandidate
                ? SUCCESSFUL_CANDIDATE_FINAL_ANSWER_PROMPT
                : missingTarget
                ? MISSING_TARGET_FINAL_ANSWER_PROMPT
                : reservedSynthesis ? RESERVED_FINAL_ANSWER_PROMPT : TOOL_BUDGET_FINAL_ANSWER_PROMPT));
        ChatRequest finalRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(request.model())
                        .temperature(request.temperature())
                        .maxOutputTokens(request.maxTokens())
                        .build())
                .build();
        long successfulCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=true")).count();
        long failedCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=false")).count();
        log.info("LangChain4j final synthesis start sessionId={} steps={} successfulTools={} failedTools={} reason={}",
                request.sessionId(), steps, successfulCalls, failedCalls, reason);
        dev.langchain4j.model.chat.response.ChatResponse response;
        try {
            response = callModel(finalRequest, request);
        } catch (RuntimeException ex) {
            return synthesisTransportPartial(messages, toolTrace, fallbacks, steps, usage, request, reason, ex);
        }
        TokenUsage totalUsage = TokenUsage.sum(usage, response == null ? null : response.tokenUsage());
        AiMessage aiMessage = response == null ? null : response.aiMessage();
        String content = aiMessage == null ? null : safeAssistantContent(aiMessage.text());
        if (!StringUtils.hasText(content)) {
            log.warn("LangChain4j final synthesis empty sessionId={} steps={} reason={}",
                    request.sessionId(), steps, reason);
            return failure(messages, toolTrace, fallbacks, steps, totalUsage,
                    "LangChain4j returned an empty final response after " + reason);
        }
        messages.add(aiMessage);
        if (indicatesTruncation(response.finishReason(), content)) {
            return compactRewriteAfterTruncation(messages, toolTrace, fallbacks, steps,
                    totalUsage, request, content, "controlled-stop final synthesis");
        }
        if (!isStreaming(request)) {
            emitToken(request, content);
        }
        log.info("LangChain4j final synthesis completed sessionId={} steps={} contentLength={} reason={}",
                request.sessionId(), steps, content.length(), reason);
        return success(messages, toolTrace, fallbacks, steps, totalUsage, content);
    }

    private String missingExplicitProjectFile(Set<String> explicitProjectFiles,
                                              ToolExecutionRequest request,
                                              ToolExecutionOutcome outcome) {
        if (explicitProjectFiles == null || explicitProjectFiles.isEmpty()
                || request == null || outcome == null || outcome.success()
                || !"project_read_file".equals(request.name())) {
            return null;
        }
        String error = defaultString(outcome.errorCode()) + " "
                + defaultString(outcome.errorMessage()) + " " + defaultString(outcome.content());
        String normalizedError = error.toLowerCase(Locale.ROOT);
        boolean notFound = normalizedError.contains("not_found")
                || normalizedError.contains("not found")
                || normalizedError.contains("404");
        if (!notFound) return null;
        try {
            String path = objectMapper.readTree(defaultString(request.arguments(), "{}"))
                    .path("relativePath").asText("");
            return ProjectMaterialScope.contains(explicitProjectFiles, path) ? path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentRuntimeResult synthesisTransportPartial(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            List<String> toolTrace,
            List<String> fallbacks,
            int steps,
            TokenUsage usage,
            AgentRuntimeRequest request,
            String reason,
            RuntimeException exception) {
        String diagnostic = "Final synthesis unavailable after controlled stop: "
                + abbreviate(defaultString(exception.getMessage(), exception.getClass().getSimpleName()));
        fallbacks.add(diagnostic);
        long successfulCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=true")).count();
        long failedCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=false")).count();
        String content = "The tool phase stopped in a controlled manner after collecting available Project evidence. "
                + "The final model synthesis timed out, so this step is only partially complete. "
                + "Successful tool observations: " + successfulCalls + ", failed or skipped observations: " + failedCalls
                + ". Limitation: " + reason;
        messages.add(AiMessage.from(content));
        if (!isStreaming(request)) {
            emitToken(request, content);
        }
        log.warn("LangChain4j final synthesis transport failed after controlled stop sessionId={} steps={} successfulTools={} failedTools={} reason={}",
                request.sessionId(), steps, successfulCalls, failedCalls, reason, exception);
        return success(messages, toolTrace, fallbacks, steps, usage, content);
    }

    private AgentRuntimeResult finalAnswerAfterNoProgress(List<dev.langchain4j.data.message.ChatMessage> messages,
                                                          List<String> toolTrace,
                                                          List<String> fallbacks,
                                                          int steps,
                                                          TokenUsage usage,
                                                          AgentRuntimeRequest request,
                                                          String reason) {
        emitProcess(request, "No new tool progress. Generating the final answer from existing results.");
        messages.add(SystemMessage.from(NO_PROGRESS_FINAL_ANSWER_PROMPT));
        ChatRequest finalRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(request.model())
                        .temperature(request.temperature())
                        .maxOutputTokens(request.maxTokens())
                        .build())
                .build();
        dev.langchain4j.model.chat.response.ChatResponse response = callModel(finalRequest, request);
        TokenUsage totalUsage = TokenUsage.sum(usage, response == null ? null : response.tokenUsage());
        AiMessage aiMessage = response == null ? null : response.aiMessage();
        String content = aiMessage == null ? null : safeAssistantContent(aiMessage.text());
        if (!StringUtils.hasText(content)) {
            return failure(messages, toolTrace, fallbacks, steps, totalUsage,
                    "LangChain4j returned an empty final response after " + reason);
        }
        messages.add(aiMessage);
        if (indicatesTruncation(response.finishReason(), content)) {
            return compactRewriteAfterTruncation(messages, toolTrace, fallbacks, steps,
                    totalUsage, request, content, "no-progress final synthesis");
        }
        if (!isStreaming(request)) {
            emitToken(request, content);
        }
        return success(messages, toolTrace, fallbacks, steps, totalUsage, content);
    }

    private AgentRuntimeResult compactRewriteAfterTruncation(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            List<String> toolTrace,
            List<String> fallbacks,
            int steps,
            TokenUsage usage,
            AgentRuntimeRequest request,
            String firstContent,
            String context) {
        fallbacks.add("Model output truncated during " + context + "; attempting one compact rewrite");
        messages.add(SystemMessage.from(COMPACT_REWRITE_AFTER_TRUNCATION_PROMPT));
        ChatRequest rewriteRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(request.model())
                        .temperature(0.0)
                        .maxOutputTokens(request.maxTokens())
                        .build())
                .build();
        dev.langchain4j.model.chat.response.ChatResponse rewritten;
        try {
            rewritten = callModel(rewriteRequest, request);
        } catch (RuntimeException ex) {
            fallbacks.add("Compact rewrite failed: "
                    + abbreviate(defaultString(ex.getMessage(), ex.getClass().getSimpleName())));
            return truncatedResult(messages, toolTrace, fallbacks, steps, usage, request, firstContent);
        }
        TokenUsage totalUsage = TokenUsage.sum(usage, rewritten == null ? null : rewritten.tokenUsage());
        AiMessage rewrittenMessage = rewritten == null ? null : rewritten.aiMessage();
        String rewrittenContent = rewrittenMessage == null ? null : safeAssistantContent(rewrittenMessage.text());
        if (StringUtils.hasText(rewrittenContent)) messages.add(rewrittenMessage);
        if (!StringUtils.hasText(rewrittenContent)
                || indicatesTruncation(rewritten == null ? null : rewritten.finishReason(), rewrittenContent)) {
            fallbacks.add("Compact rewrite remained truncated or empty");
            return truncatedResult(messages, toolTrace, fallbacks, steps + 1, totalUsage, request,
                    StringUtils.hasText(rewrittenContent) ? rewrittenContent : firstContent);
        }
        if (!isStreaming(request)) emitToken(request, rewrittenContent);
        log.info("LangChain4j compact rewrite completed sessionId={} originalLength={} rewrittenLength={}",
                request.sessionId(), firstContent == null ? 0 : firstContent.length(), rewrittenContent.length());
        return success(messages, toolTrace, fallbacks, steps + 1, totalUsage, rewrittenContent);
    }

    private AgentRuntimeResult truncatedResult(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            List<String> toolTrace,
            List<String> fallbacks,
            int steps,
            TokenUsage usage,
            AgentRuntimeRequest request,
            String content) {
        String preserved = StringUtils.hasText(content)
                ? content : "The model output was truncated before a complete final synthesis could be produced.";
        if (!isStreaming(request)) emitToken(request, preserved);
        return success(messages, toolTrace, fallbacks, steps, usage, preserved)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.MODEL_OUTPUT_TRUNCATED);
    }

    private boolean indicatesTruncation(Object finishReason, String content) {
        if (finishReason != null) {
            String normalized = String.valueOf(finishReason).trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("LENGTH") || normalized.contains("MAX_TOKEN")) return true;
        }
        return contentLooksStructurallyTruncated(content);
    }

    private boolean contentLooksStructurallyTruncated(String content) {
        if (!StringUtils.hasText(content)) return false;
        String trimmed = content.stripTrailing();
        if (occurrences(trimmed, "```") % 2 != 0 || occurrences(trimmed, "$$") % 2 != 0) {
            return true;
        }
        int lastBreak = Math.max(trimmed.lastIndexOf('\n'), trimmed.lastIndexOf('\r'));
        String lastLine = trimmed.substring(lastBreak + 1);
        return DANGLING_CODE_CONDITION.matcher(lastLine).matches();
    }

    private int occurrences(String content, String marker) {
        int count = 0;
        for (int index = 0; (index = content.indexOf(marker, index)) >= 0; index += marker.length()) {
            count++;
        }
        return count;
    }

    String safeAssistantContent(String content) {
        if (!StringUtils.hasText(content)) return content;
        String normalized = content.toLowerCase(Locale.ROOT);
        boolean internalToolProtocol = normalized.contains("dsml")
                && (normalized.contains("tool_calls") || normalized.contains("<｜｜dsml｜｜invoke")
                || normalized.contains("<||dsml||invoke"));
        if (!internalToolProtocol) return content;
        log.warn("Suppressed model-internal DSML tool protocol from assistant content");
        return "The requested Project operation did not complete. No Candidate was created and no Project files were changed. "
                + "Please retry after reviewing the failed tool call in the process details.";
    }

    private void addSkippedToolResults(List<dev.langchain4j.data.message.ChatMessage> messages,
                                       List<String> toolTrace,
                                       List<ToolExecutionRequest> requests,
                                       int fromIndex,
                                       int step,
                                       String reason) {
        for (int i = fromIndex; i < requests.size(); i++) {
            ToolExecutionRequest request = requests.get(i);
            messages.add(ToolExecutionResultMessage.from(
                    request.id(),
                    request.name(),
                    budgetSkippedToolResult(reason)
            ));
            toolTrace.add("step=" + step
                    + " tool=" + request.name()
                    + " executed=false budgetConsumed=false success=false reused=false skipped=true"
                    + " args=" + sanitizedArguments(request.arguments())
                    + " success=false error=" + reason);
        }
    }

    private String budgetSkippedToolResult(String reason) {
        var result = objectMapper.createObjectNode();
        result.put("success", false);
        result.put("executed", false);
        result.put("skipped", true);
        result.put("controlledStop", true);
        result.put("errorCode", AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED.name());
        result.put("stopReason", AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED.name());
        result.put("outcome", CompletionStatus.PARTIAL.name());
        result.put("retryable", false);
        result.put("message", reason);
        return result.toString();
    }

    private dev.langchain4j.model.chat.response.ChatResponse callModel(ChatRequest chatRequest, AgentRuntimeRequest request) {
        if (!isStreaming(request)) {
            return chatModel.chat(chatRequest, request);
        }
        return streamModel(chatRequest, request);
    }

    private boolean isStreaming(AgentRuntimeRequest request) {
        return request != null && request.tokenConsumer() != null;
    }

    private dev.langchain4j.model.chat.response.ChatResponse streamModel(ChatRequest chatRequest, AgentRuntimeRequest request) {
        StreamAccumulator accumulator = new StreamAccumulator(request);
        chatModel.stream(chatRequest, request)
                .toIterable()
                .forEach(accumulator::accept);
        AiMessage aiMessage = accumulator.toAiMessage();
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(aiMessage)
                .modelName(chatRequest == null ? null : chatRequest.modelName())
                .build();
    }

    private ToolExecutionOutcome executeTool(ToolProviderResult providerResult, ToolExecutionRequest toolRequest,
                                             Long userId, Set<String> allowedTools) {
        try {
            if (!allowedTools.contains(toolRequest.name())) {
                return outcome(false,
                        toolProvider.failureContent("tool_permission_denied: " + toolRequest.name()),
                        "tool_permission_denied", "PERMISSION_DENIED", false);
            }
            dev.langchain4j.service.tool.ToolExecutor executor = providerResult.toolExecutorByName(toolRequest.name());
            if (executor == null) {
                return outcome(false, toolProvider.failureContent("tool_not_found: " + toolRequest.name()),
                        "tool_not_found", "NOT_FOUND", false);
            }
            String content = executor.execute(toolRequest, userId);
            return classifyToolContent(content);
        } catch (Exception ex) {
            String error = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
            return outcome(false, toolProvider.failureContent(error), error, "INTERNAL_ERROR", false);
        }
    }

    private ToolExecutionOutcome classifyToolContent(String content) {
        if (!StringUtils.hasText(content)) {
            return outcome(true, content, null);
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node.isObject() && node.has("success") && !node.path("success").asBoolean(true)) {
                String error = node.path("errorMessage").asText(node.path("error").asText("tool_failed"));
                String errorCode = node.path("errorCode").asText("INTERNAL_ERROR");
                boolean retryable = node.path("retryable").asBoolean(false)
                        || "VALIDATION_ERROR".equals(errorCode);
                return outcome(false, content, error, errorCode, retryable);
            }
        } catch (Exception ignored) {
            // Text and domain-specific JSON without the ToolResult success field remain successful payloads.
        }
        return outcome(true, content, null, null, false);
    }

    private ToolExecutionOutcome outcome(boolean success, String content, String errorMessage) {
        return outcome(success, content, errorMessage, null, false);
    }

    private ToolExecutionOutcome outcome(boolean success, String content, String errorMessage, String errorCode) {
        return outcome(success, content, errorMessage, errorCode, false);
    }

    private ToolExecutionOutcome outcome(boolean success, String content, String errorMessage,
                                         String errorCode, boolean retryable) {
        AsyncObservation async = asyncObservation(content);
        return new ToolExecutionOutcome(success, content, errorMessage, errorCode, retryable,
                async.observed(), async.terminal());
    }

    private AsyncObservation asyncObservation(String content) {
        if (!StringUtils.hasText(content)) {
            return new AsyncObservation(false, false);
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            String state = node.path("status").asText(node.path("state").asText(""));
            if (!StringUtils.hasText(state)) {
                return new AsyncObservation(false, false);
            }
            String normalized = state.trim().toUpperCase(Locale.ROOT);
            if (ASYNC_TERMINAL_STATES.contains(normalized)) {
                return new AsyncObservation(true, true);
            }
            return new AsyncObservation(ASYNC_NON_TERMINAL_STATES.contains(normalized), false);
        } catch (Exception ignored) {
            return new AsyncObservation(false, false);
        }
    }

    private String buildToolTraceLine(int step, ToolExecutionRequest toolRequest, ToolExecutionOutcome toolResult,
                                      RepairContext repairContext) {
        return "step=" + step
                + " tool=" + toolRequest.name()
                + " executed=true"
                + " budgetConsumed=" + !toolResult.scopeRefinementRequired()
                + " success=" + toolResult.success()
                + " reused=false skipped=false"
                + " args=" + sanitizedArguments(toolRequest.arguments())
                + (toolResult.success() ? summarizeToolObservation(toolResult.content())
                : " errorCode=" + defaultString(toolResult.errorCode(), "INTERNAL_ERROR")
                + " retryable=" + (repairContext != null && repairContext.retryable())
                + " remainingAttempts=" + (repairContext == null ? 0 : repairContext.remainingAttempts())
                + " error=" + (repairContext == null
                ? defaultString(toolResult.errorMessage(), "tool_failed") : repairContext.errorMessage()));
    }

    private String summarizeToolObservation(String content) {
        if (!StringUtils.hasText(content)) return "";
        try {
            JsonNode result = objectMapper.readTree(content);
            if (result.path("hits").isArray()) return " observation=hits:" + result.path("hits").size();
            if (result.path("items").isArray()) return " observation=items:" + result.path("items").size()
                    + (result.has("status") ? ",status:" + result.path("status").asText() : "");
            if (result.has("status")) return " observation=status:" + result.path("status").asText();
        } catch (Exception ignored) {
            // Tool content remains available in the transcript; trace summaries are best effort only.
        }
        return "";
    }

    private boolean registerVerifiedNewObservation(String content, Set<String> observed) {
        if (!StringUtils.hasText(content)) return false;
        try {
            JsonNode result = objectMapper.readTree(content);
            boolean added = registerArrayEntries(result.path("items"), "item:", observed);
            return registerArrayEntries(result.path("evidenceRefs"), "evidence:", observed) || added;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean registerArrayEntries(JsonNode values, String prefix, Set<String> observed) {
        if (!values.isArray() || values.isEmpty()) return false;
        boolean added = false;
        for (JsonNode value : values) {
            added |= observed.add(prefix + value.toString());
        }
        return added;
    }

    private String reusedToolResult(InvocationState previous, String reason) {
        var reused = objectMapper.createObjectNode();
        reused.put("success", previous.outcome().success());
        reused.put("reused", true);
        reused.put("originalToolCallId", defaultString(previous.sourceToolCallId(), "unknown"));
        reused.put("reason", reason);
        reused.put("message", "Identical request was not re-executed; use the original tool result already present.");
        if (!previous.outcome().success()) {
            reused.put("errorMessage", defaultString(previous.outcome().errorMessage(), "tool_failed"));
        }
        return reused.toString();
    }

    private String buildReusedToolTraceLine(int step,
                                            ToolExecutionRequest toolRequest,
                                            InvocationState previous,
                                            String reason) {
        return "step=" + step
                + " tool=" + toolRequest.name()
                + " executed=false budgetConsumed=false"
                + " success=" + previous.outcome().success()
                + " reused=true skipped=false"
                + " args=" + sanitizedArguments(toolRequest.arguments())
                + " originalToolCallId=" + defaultString(previous.sourceToolCallId(), "unknown")
                + " reason=" + reason
                + (previous.outcome().success() ? ""
                : " error=" + defaultString(previous.outcome().errorMessage(), "tool_failed"));
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangChainMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<dev.langchain4j.data.message.ChatMessage> converted = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            String role = message.role() == null ? "" : message.role().trim().toLowerCase(Locale.ROOT);
            switch (role) {
                case "system" -> converted.add(SystemMessage.from(defaultString(message.content())));
                case "assistant" -> {
                    if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                        converted.add(AiMessage.from(defaultString(message.content())));
                    } else {
                        List<ToolExecutionRequest> requests = message.toolCalls().stream()
                                .filter(call -> call.function() != null)
                                .map(call -> ToolExecutionRequest.builder()
                                        .id(call.id())
                                        .name(call.function().name())
                                        .arguments(defaultString(call.function().arguments(), "{}"))
                                        .build())
                                .toList();
                        converted.add(AiMessage.from(defaultString(message.content()), requests));
                    }
                }
                case "tool" -> converted.add(ToolExecutionResultMessage.from(
                        defaultString(message.toolCallId(), "tool-call"),
                        "tool",
                        defaultString(message.content())
                ));
                default -> converted.add(UserMessage.from(defaultString(message.content())));
            }
        }
        return converted;
    }

    private AgentRuntimeResult success(List<dev.langchain4j.data.message.ChatMessage> messages,
                                       List<String> toolTrace,
                                       List<String> fallbacks,
                                       int steps,
                                       TokenUsage usage,
                                       String assistantContent) {
        return new AgentRuntimeResult(
                true,
                assistantContent,
                toCoreMessages(messages),
                steps,
                null,
                toolTrace,
                fallbacks,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount()
        );
    }

    private AgentRuntimeResult failure(List<dev.langchain4j.data.message.ChatMessage> messages,
                                       List<String> toolTrace,
                                       List<String> fallbacks,
                                       int steps,
                                       TokenUsage usage,
                                       String errorMessage) {
        return new AgentRuntimeResult(
                false,
                null,
                toCoreMessages(messages),
                steps,
                errorMessage,
                toolTrace,
                fallbacks,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount()
        );
    }

    private List<ChatMessage> toCoreMessages(List<dev.langchain4j.data.message.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>();
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage) {
                converted.add(ChatMessage.user(userMessage.singleText()));
            } else if (message instanceof SystemMessage systemMessage) {
                converted.add(ChatMessage.system(systemMessage.text()));
            } else if (message instanceof ToolExecutionResultMessage toolMessage) {
                converted.add(ChatMessage.tool(toolMessage.id(), toolMessage.text()));
            } else if (message instanceof AiMessage aiMessage) {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                converted.add(new ChatMessage(
                        "assistant",
                        aiMessage.text(),
                        requests == null || requests.isEmpty() ? null : requests.stream()
                                .map(request -> new com.yanban.core.model.ToolCall(
                                        request.id(),
                                        "function",
                                        new com.yanban.core.model.ToolCall.FunctionCall(request.name(), request.arguments())
                                ))
                                .toList(),
                        null
                ));
            }
        }
        return converted;
    }

    private void emitToken(AgentRuntimeRequest request, String assistantContent) {
        if (request.tokenConsumer() != null && StringUtils.hasText(assistantContent)) {
            request.tokenConsumer().accept(assistantContent);
        }
    }

    private void emitProcess(AgentRuntimeRequest request, String content) {
        if (request.processConsumer() != null && StringUtils.hasText(content)) {
            request.processConsumer().accept(content);
        }
    }

    private String normalizeArguments(String value) {
        String raw = defaultString(value, "{}");
        try {
            return objectMapper.writeValueAsString(canonicalize(objectMapper.readTree(raw)));
        } catch (Exception ignored) {
            // Invalid JSON has no semantic equivalence. Preserve its exact bytes as a stable fallback.
            return "invalid-json:" + raw;
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            var canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        var canonical = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.stream().sorted().forEach(name -> canonical.set(name, canonicalize(node.get(name))));
        return canonical;
    }

    private String repeatError(ToolExecutionRequest request, InvocationState previous,
                               int maxDuplicates, int projectEvidenceEpoch) {
        if (previous == null) {
            return null;
        }
        ToolDescriptor descriptor = toolProvider.descriptor(request.name()).orElse(null);
        if (descriptor == null) {
            return "Tool metadata missing";
        }
        return switch (descriptor.repeatPolicy()) {
            case DENY_SAME_INPUT -> !previous.success()
                    && ProjectCandidateProposalToolExecutor.TOOL_NAME.equals(request.name())
                    && projectEvidenceEpoch > previous.projectEvidenceEpoch()
                    ? null : "Duplicate tool call blocked";
            case POLL_UNTIL_TERMINAL -> {
                if (descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC || !previous.asyncStateObserved()) {
                    yield "Repeated asynchronous poll has no observable non-terminal state";
                }
                yield previous.asyncTerminal() ? "Repeated asynchronous poll after terminal state" : null;
            }
            case ALLOW_LIMITED -> previous.success()
                    ? "Duplicate successful tool call blocked"
                    : "Duplicate failed tool call blocked";
        };
    }

    private boolean isProjectEvidenceObservation(String toolName) {
        return "project_read_file".equals(toolName) || "project_search".equals(toolName);
    }

    private boolean hasIdempotencyKey(String arguments) {
        try {
            JsonNode idempotencyKey = objectMapper.readTree(defaultString(arguments, "{}"))
                    .path("clientRequestId");
            return idempotencyKey.isTextual() && StringUtils.hasText(idempotencyKey.asText());
        } catch (Exception ex) {
            return false;
        }
    }

    private String toolSignature(String toolName, String arguments) {
        RepairContext context = RepairContext.create(objectMapper, toolName, arguments,
                "INTERNAL_ERROR", "signature", false, 0);
        return context.signature(objectMapper);
    }

    private String sanitizedArguments(String arguments) {
        try {
            return objectMapper.writeValueAsString(
                    canonicalize(RepairContext.sanitizeArguments(objectMapper, arguments)));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private List<String> summarizeToolRequests(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.stream()
                .map(request -> request.name() + "(" + abbreviate(sanitizedArguments(request.arguments())) + ")")
                .toList();
    }

    private String summarizeToolNames(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return "无";
        }
        return String.join("、", requests.stream()
                .map(ToolExecutionRequest::name)
                .filter(StringUtils::hasText)
                .toList());
    }

    private String abbreviate(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private String defaultString(String value) {
        return defaultString(value, "");
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static final class StreamAccumulator {

        private final AgentRuntimeRequest request;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        private StreamMode mode = StreamMode.UNDECIDED;

        private StreamAccumulator(AgentRuntimeRequest request) {
            this.request = request;
        }

        private void accept(ChatChunk chunk) {
            if (chunk == null) {
                return;
            }
            if (!chunk.toolCallDeltas().isEmpty()) {
                mode = StreamMode.TOOL_CALL;
                for (ChatChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
                    toolCalls.computeIfAbsent(delta.index(), ignored -> new ToolCallBuilder())
                            .append(delta);
                }
                return;
            }
            if (StringUtils.hasText(chunk.content()) && mode != StreamMode.TOOL_CALL) {
                mode = StreamMode.TEXT;
                content.append(chunk.content());
                if (request.tokenConsumer() != null) {
                    request.tokenConsumer().accept(chunk.content());
                }
            }
        }

        private AiMessage toAiMessage() {
            List<ToolExecutionRequest> requests = toolCalls.values().stream()
                    .map(ToolCallBuilder::build)
                    .filter(toolRequest -> StringUtils.hasText(toolRequest.name()))
                    .toList();
            if (!requests.isEmpty()) {
                return AiMessage.from(content.toString(), requests);
            }
            return AiMessage.from(content.toString());
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private void append(ChatChunk.ToolCallDelta delta) {
            if (delta == null) {
                return;
            }
            if (StringUtils.hasText(delta.id())) {
                id = delta.id();
            }
            if (StringUtils.hasText(delta.functionName())) {
                name = delta.functionName();
            }
            if (delta.argumentsDelta() != null) {
                arguments.append(delta.argumentsDelta());
            }
        }

        private ToolExecutionRequest build() {
            return ToolExecutionRequest.builder()
                    .id(StringUtils.hasText(id) ? id : "tool-call-" + Math.abs(arguments.toString().hashCode()))
                    .name(name)
                    .arguments(arguments.isEmpty() ? "{}" : arguments.toString())
                    .build();
        }
    }

    private enum StreamMode {
        UNDECIDED,
        TEXT,
        TOOL_CALL
    }

    private record InvocationState(int count, String sourceToolCallId, ToolExecutionOutcome outcome,
                                   int projectEvidenceEpoch) {
        private static InvocationState after(InvocationState previous,
                                             String sourceToolCallId,
                                             ToolExecutionOutcome outcome,
                                             int projectEvidenceEpoch) {
            return new InvocationState(previous == null ? 1 : previous.count() + 1,
                    sourceToolCallId, outcome, projectEvidenceEpoch);
        }

        private boolean success() {
            return outcome.success();
        }

        private boolean asyncStateObserved() {
            return outcome.asyncStateObserved();
        }

        private boolean asyncTerminal() {
            return outcome.asyncTerminal();
        }
    }

    private record ToolExecutionOutcome(boolean success, String content, String errorMessage, String errorCode,
                                        boolean retryable,
                                        boolean asyncStateObserved, boolean asyncTerminal) {
        private boolean scopeRefinementRequired() {
            return !success && "VALIDATION_ERROR".equals(errorCode)
                    && errorMessage != null && errorMessage.contains("requires relativePaths");
        }
    }

    private record AsyncObservation(boolean observed, boolean terminal) {
    }
}
