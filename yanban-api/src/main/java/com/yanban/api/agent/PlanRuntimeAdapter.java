package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Adapter boundary between the Coordinator and the existing persisted Plan scheduler. */
@Component
public class PlanRuntimeAdapter implements RuntimeAdapter {

    private static final Pattern INTERNAL_EVIDENCE_REF_LINE = Pattern.compile(
            "(?m)^[ \\t]*\\[projectEvidenceRefs=[^\\r\\n\\]]*\\][ \\t]*(?:\\R|\\z)");
    private static final Pattern INTERNAL_EVIDENCE_REF = Pattern.compile(
            "\\[projectEvidenceRefs=[^\\r\\n\\]]*\\]");
    private static final Pattern NESTED_GOVERNANCE_HEADER = Pattern.compile(
            "(?s)^Governed completion status:[^\\r\\n]*(?:\\R(?:Cross-material consistency:|Scope:)[^\\r\\n]*)*\\R{2}");

    private final PlanAgentService planAgentService;

    public PlanRuntimeAdapter(@Lazy PlanAgentService planAgentService) {
        this.planAgentService = planAgentService;
    }

    @Override
    public boolean supports(AgentStrategy strategy) {
        return strategy == AgentStrategy.PLAN_EXECUTE;
    }

    @Override
    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        if (request.planId() == null) {
            Long createdPlanId = null;
            try {
                boolean unifiedProjectPlan = isServerAutoProjectPlan(request);
                AgentRuntimeRequest persistedRequest = unifiedProjectPlan
                        ? request.withPlanConversationSummaryPersistence(false) : request;
                AgentPlanResponse created = planAgentService.createPlanWithinAdapter(persistedRequest);
                createdPlanId = created.id();
                if (unifiedProjectPlan) {
                    AgentRuntimeRequest persistedParent = persistedRequest.withPlanId(createdPlanId);
                    if (persistedRequest.controlledWorkerDispatch() != null) {
                        persistedParent = persistedParent.withControlledWorkerDispatch(
                                persistedRequest.controlledWorkerDispatch().bindToParentPlan(createdPlanId));
                    }
                    return execute(persistedParent, createdPlanId, false);
                }
                String content = "Plan " + created.id() + " created.";
                return new AgentRuntimeResult(true, content, transcriptWithAssistant(request, content), 0,
                        null, List.of(), List.of(), null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "PLAN_CREATED", false, null)
                        .withPlanId(created.id());
            } catch (RuntimeException ex) {
                String error = ex instanceof ResponseStatusException statusException
                        && StringUtils.hasText(statusException.getReason())
                        ? statusException.getReason()
                        : (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
                if (createdPlanId != null && error.startsWith("SANDBOX_CONFIRMATION_REQUIRED")) {
                    try {
                        AgentPlanResponse waiting = planAgentService.getPlan(request.userId(), createdPlanId);
                        if (isSandboxConfirmationWaiting(waiting)) {
                            String content = "Plan execution is waiting for your confirmation or another required action.";
                            return new AgentRuntimeResult(false, content, transcriptWithAssistant(request, content),
                                    waiting.steps().size(), null, List.of(), List.of(), null, null, null)
                                    .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.WAITING_FOR_USER,
                                            "WAITING", false, null)
                                    .withPlanId(createdPlanId)
                                    .withPlanPersistenceLevel(waiting.persistenceLevel());
                        }
                    } catch (RuntimeException ignored) {
                        // Preserve the original governed failure if the persisted waiting state cannot be verified.
                    }
                }
                return new AgentRuntimeResult(false, null, List.of(), 0, error, List.of(), List.of(),
                        null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED, "FAILED", false, null)
                        .withPlanId(createdPlanId);
            }
        }
        return execute(request, request.planId(), request.shouldPersistPlanConversationSummary(true));
    }

    private boolean isSandboxConfirmationWaiting(AgentPlanResponse plan) {
        return plan != null && "REVIEWING".equals(plan.status()) && plan.steps() != null
                && plan.steps().stream().anyMatch(step -> step != null && "PENDING".equals(step.status())
                && step.allowedTools() != null && step.allowedTools().contains("sandbox_execute"));
    }

    private AgentRuntimeResult execute(AgentRuntimeRequest request, Long planId,
                                       boolean persistConversationSummary) {
        PlanAgentService.PlanExecutionResult execution = request.controlledWorkerDispatch() == null
                ? planAgentService.executePlanResultWithinAdapter(
                        request.userId(), planId, request.traceId(), persistConversationSummary)
                : planAgentService.executePlanResultWithinAdapter(
                        request, planId, request.traceId(), persistConversationSummary);
        AgentPlanResponse plan = execution.plan();
        PlanTerminal terminal = classify(plan, execution.stopSignal());
        String content = buildExecutionContent(plan, terminal);
        List<AgentPlanStepResponse> planSteps = plan.steps() == null ? List.of() : plan.steps();
        return new AgentRuntimeResult(
                terminal.success(), content, transcriptWithAssistant(request, content), planSteps.size(),
                terminal.success() ? null : plan.errorMessage(), List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, terminal.stopReason(), terminal.outcome(),
                        terminal.degraded(), terminal.degraded() ? AgentStrategy.PLAN_EXECUTE : null)
                .withRuntimeStopSignal(terminal.stopSignal())
                .withPlanId(plan.id())
                .withPlanPersistenceLevel(plan.persistenceLevel())
                .withFinalSynthesisInput(plan.finalSynthesisInput())
                .withTrustedEvidenceLedger(execution.evidenceLedger())
                .withDomainRuntimeFacts(execution.domainRuntimeFacts());
    }

    private List<ChatMessage> transcriptWithAssistant(AgentRuntimeRequest request, String content) {
        List<ChatMessage> messages = new ArrayList<>(request.history());
        messages.add(ChatMessage.assistant(content));
        return List.copyOf(messages);
    }

    static boolean isServerAutoProjectPlan(AgentRuntimeRequest request) {
        if (request == null || request.strategy() != AgentStrategy.PLAN_EXECUTE
                || request.projectContext() == null || request.orchestrationRequirements() == null) {
            return false;
        }
        AgentOrchestrationRequirements audit = request.orchestrationRequirements();
        return (audit.selectionOrigin() == AgentStrategySelectionOrigin.SERVER_AUTO
                && (audit.reasonCodes().contains(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN)
                || audit.reasonCodes().contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN)
                || audit.reasonCodes().contains(AgentStrategyReasonCode.SANDBOX_EXECUTION_REQUIRES_PLAN)))
                || (audit.selectionOrigin() == AgentStrategySelectionOrigin.LLM_ROUTER
                && (audit.reasonCodes().contains(AgentStrategyReasonCode.LLM_ROUTER_PLAN)
                || audit.reasonCodes().contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN)
                || audit.reasonCodes().contains(AgentStrategyReasonCode.SANDBOX_EXECUTION_REQUIRES_PLAN)))
                || (audit.selectionOrigin() == AgentStrategySelectionOrigin.ROUTER_FALLBACK
                && audit.reasonCodes().contains(AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_PLAN)
                && (audit.reasonCodes().contains(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN)
                || audit.reasonCodes().contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN)
                || audit.reasonCodes().contains(AgentStrategyReasonCode.SANDBOX_EXECUTION_REQUIRES_PLAN)));
    }

    private String buildExecutionContent(AgentPlanResponse plan, PlanTerminal terminal) {
        String finalAnswer = withoutInternalPresentationMetadata(
                SandboxTrustedResultBoundary.trustedPlanFinalAnswer(plan));
        if (StringUtils.hasText(finalAnswer)) {
            // Lifecycle/outcome remain typed response fields and Plan events. The canonical chat answer is the
            // single model-produced synthesis so confirmed language/style preferences are not prefixed by a
            // second, hard-coded English assistant answer.
            return finalAnswer;
        }
        StringBuilder content = new StringBuilder("Plan ").append(plan.id())
                .append(" execution lifecycle status: ").append(plan.status()).append(".");
        if (!"SUCCESS".equals(terminal.outcome())) {
            content.append("\nPlan execution outcome: ").append(terminal.outcome()).append(".");
        }
        if (StringUtils.hasText(plan.errorMessage())) {
            content.append("\n\n").append(plan.errorMessage());
        } else {
            content.append("\n\nNo final synthesis was produced. Inspect the Plan steps for details.");
        }
        return content.toString();
    }

    static String withoutInternalPresentationMetadata(String detail) {
        if (!StringUtils.hasText(detail)) return "";
        String withoutMarkerLines = INTERNAL_EVIDENCE_REF_LINE.matcher(detail).replaceAll("");
        String withoutMarkers = INTERNAL_EVIDENCE_REF.matcher(withoutMarkerLines).replaceAll("");
        return NESTED_GOVERNANCE_HEADER.matcher(withoutMarkers).replaceFirst("").stripTrailing();
    }

    static PlanTerminal classify(AgentPlanResponse plan, AgentRuntimeStopSignal stopSignal) {
        if (stopSignal != AgentRuntimeStopSignal.NONE) {
            AgentStopReason reason = switch (stopSignal) {
                case TOOL_CALL_BUDGET_EXHAUSTED -> AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED;
                case MAX_STEPS_BUDGET_EXHAUSTED -> AgentStopReason.MAX_STEPS_BUDGET_EXHAUSTED;
                case MODEL_OUTPUT_TRUNCATED -> AgentStopReason.MODEL_OUTPUT_TRUNCATED;
                case NONE -> throw new IllegalStateException("NONE is not a terminal stop signal");
            };
            String outcome = stopSignal == AgentRuntimeStopSignal.MODEL_OUTPUT_TRUNCATED
                    ? "PARTIAL" : "BUDGET_STOP";
            return new PlanTerminal(false, true, reason, outcome, stopSignal);
        }
        if ("CANCELLED".equals(plan.executionOutcome()) || "CANCELLED".equals(plan.taskOutcome())) {
            return new PlanTerminal(false, false, AgentStopReason.CANCELLED,
                    "CANCELLED", AgentRuntimeStopSignal.NONE);
        }
        if ("TIMED_OUT".equals(plan.executionOutcome()) || "TIMED_OUT".equals(plan.taskOutcome())) {
            return new PlanTerminal(false, false, AgentStopReason.TIMED_OUT,
                    "TIMED_OUT", AgentRuntimeStopSignal.NONE);
        }
        boolean governedExecution = plan.steps() != null && plan.steps().stream()
                .anyMatch(step -> step != null && "SANDBOX_EXECUTE".equals(step.type()));
        if (governedExecution
                && ("FAILED".equals(plan.executionOutcome()) || "UNAVAILABLE".equals(plan.executionOutcome()))) {
            return new PlanTerminal(false, false, AgentStopReason.RUNTIME_FAILED,
                    "FAILED", AgentRuntimeStopSignal.NONE);
        }
        if ("PARTIAL".equals(plan.taskOutcome())) {
            return new PlanTerminal(false, true, AgentStopReason.PLAN_PARTIAL,
                    "PARTIAL", AgentRuntimeStopSignal.NONE);
        }
        if ("COMPLETED".equals(plan.status())) {
            return new PlanTerminal(true, false, AgentStopReason.COMPLETED,
                    "SUCCESS", AgentRuntimeStopSignal.NONE);
        }
        return switch (plan.status()) {
            case "PAUSED" -> new PlanTerminal(false, false, AgentStopReason.PAUSED,
                    "PAUSED", AgentRuntimeStopSignal.NONE);
            case "REVIEWING", "RUNNING" -> new PlanTerminal(false, false, AgentStopReason.WAITING_FOR_USER,
                    "WAITING", AgentRuntimeStopSignal.NONE);
            default -> new PlanTerminal(false, false, AgentStopReason.RUNTIME_FAILED,
                    "FAILED", AgentRuntimeStopSignal.NONE);
        };
    }

    static PlanTerminal classify(AgentPlanResponse plan) {
        return classify(plan, AgentRuntimeStopSignal.NONE);
    }

    record PlanTerminal(boolean success, boolean degraded, AgentStopReason stopReason,
                        String outcome, AgentRuntimeStopSignal stopSignal) {
    }
}
