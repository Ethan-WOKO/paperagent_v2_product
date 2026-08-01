package com.yanban.api.agent.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** Runs optional output analysis outside receipt projection and persists it in a short transaction. */
@Service
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
public class SandboxOutputAnalysisProjectionService {
    private final SandboxOutboxRepository outbox;
    private final AgentSessionRepository sessions;
    private final AgentPlanEventRepository events;
    private final com.yanban.core.agent.AgentPlanStepRepository steps;
    private final SandboxOutputAnalysisService analysis;
    private final ObjectMapper json;
    private final TransactionTemplate requiresNew;

    public SandboxOutputAnalysisProjectionService(SandboxOutboxRepository outbox,
                                                   AgentSessionRepository sessions,
                                                   AgentPlanEventRepository events,
                                                   com.yanban.core.agent.AgentPlanStepRepository steps,
                                                   SandboxOutputAnalysisService analysis,
                                                   ObjectMapper json,
                                                   PlatformTransactionManager transactions) {
        this.outbox = outbox;
        this.sessions = sessions;
        this.events = events;
        this.steps = steps;
        this.analysis = analysis;
        this.json = json;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void analyzeAfterCommit(String executionId) {
        try {
            AnalysisInput input = requiresNew.execute(status -> {
                SandboxOutboxExecution execution = outbox.findByExecutionId(executionId).orElse(null);
                if (execution == null || !StringUtils.hasText(execution.receiptJson())) return null;
                String key = "sandbox-output-analysis:" + executionId;
                if (events.findByPlanIdAndIdempotencyKey(execution.planId(), key).isPresent()) return null;
                try {
                    return new AnalysisInput(execution, sessions.findById(execution.sessionId()).orElse(null),
                            json.readValue(execution.receiptJson(), SandboxReceipt.class), key);
                } catch (Exception exception) { return null; }
            });
            if (input == null) return;
            SandboxOutboxExecution execution = input.execution();
            String summary = analysis.analyze(execution.userId(), input.session(), input.receipt(),
                    "sandbox-output-analysis-" + executionId);
            if (!StringUtils.hasText(summary)) return;
            requiresNew.executeWithoutResult(status -> {
                if (events.findByPlanIdAndIdempotencyKey(execution.planId(), input.key()).isPresent()) return;
                try {
                    events.saveAndFlush(new AgentPlanEvent(execution.planId(), execution.stepId(),
                            "sandbox_output_analysis", write(Map.of("executionId", executionId,
                            "summary", summary, "disclaimer", SandboxOutputAnalysisService.DISCLAIMER)), input.key()));
                } catch (DataIntegrityViolationException ignored) {
                    // Another best-effort callback already persisted the same analysis event.
                }
                steps.findById(execution.stepId()).filter(step -> "FAILED".equals(step.getStatus())).ifPresent(step -> {
                    step.appendReadOnlyTerminalResult(SandboxOutputAnalysisService.DISCLAIMER + "\n" + summary);
                    steps.saveAndFlush(step);
                });
            });
        } catch (Exception ignored) {
            // Receipt projection is already committed; optional analysis may be absent.
        }
    }

    private record AnalysisInput(SandboxOutboxExecution execution,
                                 com.yanban.core.agent.AgentSession session,
                                 SandboxReceipt receipt, String key) { }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
