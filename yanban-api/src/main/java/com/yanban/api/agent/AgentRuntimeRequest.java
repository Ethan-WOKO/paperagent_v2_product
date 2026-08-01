package com.yanban.api.agent;

import com.yanban.api.agent.worker.ControlledWorkerDispatch;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

public record AgentRuntimeRequest(
        AgentStrategy strategy,
        Long sessionId,
        List<ChatMessage> history,
        Long userId,
        String userMessage,
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        int maxSteps,
        boolean ragDisabled,
        String skillId,
        String apiKey,
        String apiUrl,
        String skillPrompt,
        AgentRuntimeMode runtimeMode,
        AgentToolCallingMode toolCallingMode,
        ResolvedToolPolicy toolPolicy,
        Integer maxToolCalls,
        Integer maxDuplicateToolCalls,
        String traceId,
        Consumer<String> tokenConsumer,
        Consumer<String> processConsumer,
        Long planId,
        ProjectRuntimeContext projectContext,
        EvidenceLedger inheritedTrustedEvidence,
        AgentOrchestrationRequirements orchestrationRequirements,
        Boolean persistPlanConversationSummary,
        ControlledWorkerDispatch controlledWorkerDispatch,
        RepairContext repairContext
) {
    public AgentRuntimeRequest {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        runtimeMode = AgentRuntimeMode.LANGCHAIN4J;
        toolCallingMode = AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING;
        history = history == null ? List.of() : List.copyOf(history);
        if (toolPolicy == null) {
            throw new IllegalArgumentException("toolPolicy must be resolved before runtime execution");
        }
        inheritedTrustedEvidence = inheritedTrustedEvidence == null ? EvidenceLedger.empty() : inheritedTrustedEvidence;
        orchestrationRequirements = orchestrationRequirements == null
                ? AgentOrchestrationRequirements.empty() : orchestrationRequirements;
    }

    /** Source-compatible bridge for the request shape before structured tool repair context existed. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, ResolvedToolPolicy toolPolicy, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer, Long planId, ProjectRuntimeContext projectContext,
            EvidenceLedger inheritedTrustedEvidence, AgentOrchestrationRequirements orchestrationRequirements,
            Boolean persistPlanConversationSummary, ControlledWorkerDispatch controlledWorkerDispatch) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode, toolPolicy,
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, planId, projectContext,
                inheritedTrustedEvidence, orchestrationRequirements, persistPlanConversationSummary,
                controlledWorkerDispatch, null);
    }

    /** Source-compatible bridge for the request shape before controlled Worker dispatch existed. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, ResolvedToolPolicy toolPolicy, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer, Long planId, ProjectRuntimeContext projectContext,
            EvidenceLedger inheritedTrustedEvidence, AgentOrchestrationRequirements orchestrationRequirements,
            Boolean persistPlanConversationSummary) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode, toolPolicy,
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, planId, projectContext,
                inheritedTrustedEvidence, orchestrationRequirements, persistPlanConversationSummary, null);
    }

    /** Source-compatible bridge for callers using the pre-presentation-policy canonical shape. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, ResolvedToolPolicy toolPolicy, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer, Long planId, ProjectRuntimeContext projectContext,
            EvidenceLedger inheritedTrustedEvidence, AgentOrchestrationRequirements orchestrationRequirements) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode, toolPolicy,
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, planId, projectContext,
                inheritedTrustedEvidence, orchestrationRequirements, null);
    }

    /** Source-compatible bridge for callers using the pre-AUTO canonical shape. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, ResolvedToolPolicy toolPolicy, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer, Long planId, ProjectRuntimeContext projectContext,
            EvidenceLedger inheritedTrustedEvidence) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode, toolPolicy,
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, planId, projectContext,
                inheritedTrustedEvidence, AgentOrchestrationRequirements.empty());
    }

    /** Compatibility bridge for existing callers. A null legacy list is fail-closed, never unrestricted. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, List<String> allowedToolNames, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode,
                new ResolvedToolPolicy(allowedToolNames == null ? List.of() : allowedToolNames,
                        maxToolCalls == null ? 0 : maxToolCalls,
                        maxDuplicateToolCalls == null ? 0 : maxDuplicateToolCalls,
                "legacy_runtime_request"),
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, null, null,
                EvidenceLedger.empty(), AgentOrchestrationRequirements.empty());
    }

    /** Source-compatible bridge for callers that already supplied a resolved policy. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, ResolvedToolPolicy toolPolicy, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode, toolPolicy,
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, null, null,
                EvidenceLedger.empty(), AgentOrchestrationRequirements.empty());
    }

    public List<String> allowedToolNames() {
        return toolPolicy.allowedTools();
    }

    /** Coordinator resolves AUTO or validates an explicit server-side strategy before execution. */
    public AgentRuntimeRequest withStrategy(AgentStrategy selectedStrategy) {
        if (selectedStrategy == null) {
            throw new IllegalArgumentException("selectedStrategy must not be null");
        }
        return new AgentRuntimeRequest(selectedStrategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** DIRECT is a model-only answer path; it must not inherit tool authority from an AUTO request. */
    public AgentRuntimeRequest withoutToolAuthority(String reason) {
        ResolvedToolPolicy denyAll = ResolvedToolPolicy.denyAll(0, 0,
                reason == null || reason.isBlank() ? "direct_strategy" : reason);
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, denyAll, 0, 0, traceId, tokenConsumer, processConsumer,
                planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** Attach server-owned plan identity after a Plan API request has been authorized. */
    public AgentRuntimeRequest withPlanId(Long planId) {
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** Only an authenticated Project API adapter may attach this context. */
    public AgentRuntimeRequest withProjectContext(ProjectRuntimeContext context) {
        if (context == null || !context.userId().equals(userId)) {
            throw new IllegalArgumentException("project context must match the runtime user");
        }
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, context, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** Attach only server-persisted observations inherited from completed Plan dependencies. */
    public AgentRuntimeRequest withInheritedTrustedEvidence(EvidenceLedger evidence) {
        if (projectContext == null && evidence != null && !evidence.evidence().isEmpty()) {
            throw new IllegalArgumentException("inherited Project evidence requires a trusted project context");
        }
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext, evidence == null ? EvidenceLedger.empty() : evidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /**
     * Adds a server-owned instruction for a bounded repair turn. The user task,
     * authenticated Project identity, resolved tool policy and endpoint remain unchanged.
     */
    public AgentRuntimeRequest withAdditionalSystemInstruction(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return this;
        }
        java.util.ArrayList<ChatMessage> augmentedHistory = new java.util.ArrayList<>(history);
        augmentedHistory.add(ChatMessage.system(instruction));
        return new AgentRuntimeRequest(strategy, sessionId, augmentedHistory, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** Bounded reflection may only reduce runtime and tool budgets. */
    public AgentRuntimeRequest withReducedBudget(int reducedMaxSteps, int reducedMaxToolCalls) {
        if (reducedMaxSteps <= 0 || reducedMaxSteps > maxSteps || reducedMaxToolCalls < 0
                || reducedMaxToolCalls > toolPolicy.maxToolCalls()) {
            throw new IllegalArgumentException("reflection may only use remaining runtime budget");
        }
        ResolvedToolPolicy reducedPolicy = new ResolvedToolPolicy(toolPolicy.allowedTools(), reducedMaxToolCalls,
                Math.min(toolPolicy.maxDuplicateToolCalls(), reducedMaxToolCalls), toolPolicy.reason());
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, reducedMaxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, reducedPolicy, reducedMaxToolCalls, reducedPolicy.maxDuplicateToolCalls(), traceId,
                tokenConsumer, processConsumer, planId, projectContext, inheritedTrustedEvidence,
                orchestrationRequirements, persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** Coordinator-only attachment; this metadata cannot alter any authority-bearing field. */
    public AgentRuntimeRequest withOrchestrationRequirements(AgentOrchestrationRequirements requirements) {
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext, inheritedTrustedEvidence,
                requirements == null ? AgentOrchestrationRequirements.empty() : requirements,
                persistPlanConversationSummary, controlledWorkerDispatch, repairContext);
    }

    /** Server-owned presentation policy for nested Plan execution. It does not change runtime authority. */
    public AgentRuntimeRequest withPlanConversationSummaryPersistence(boolean persist) {
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements, persist,
                controlledWorkerDispatch, repairContext);
    }

    /** Coordinator-only attachment created from the current server manifest and resolved parent policy. */
    public AgentRuntimeRequest withControlledWorkerDispatch(ControlledWorkerDispatch dispatch) {
        if (dispatch == null) return this;
        dispatch.validateAgainst(this);
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer,
                processConsumer, planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, dispatch, repairContext);
    }

    /** Carries one server-created, non-authority repair hint into the existing tool loop. */
    public AgentRuntimeRequest withRepairContext(RepairContext context) {
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer,
                processConsumer, planId, projectContext, inheritedTrustedEvidence, orchestrationRequirements,
                persistPlanConversationSummary, controlledWorkerDispatch, context);
    }

    public boolean shouldPersistPlanConversationSummary(boolean defaultValue) {
        return persistPlanConversationSummary == null ? defaultValue : persistPlanConversationSummary;
    }
}
