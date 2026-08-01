package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public record AgentRuntimeResult(
        boolean success,
        String assistantContent,
        List<ChatMessage> messages,
        int steps,
        String errorMessage,
        List<String> toolTrace,
        List<String> fallbacks,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        AgentStrategy selectedStrategy,
        AgentStopReason stopReason,
        String outcome,
        boolean degraded,
        AgentStrategy degradedFrom,
        AgentRuntimeStopSignal runtimeStopSignal,
        Long planId,
        EvidenceLedger evidenceLedger,
        EvidenceLedger trustedEvidenceLedger,
        CompletionVerification completionVerification,
        CandidateChangeSet candidateChangeSet,
        @JsonIgnore CandidateIntent candidateIntent,
        CandidateArtifactResponse candidateArtifact,
        DomainRuntimeFacts domainRuntimeFacts,
        String planPersistenceLevel,
        FinalSynthesisInput finalSynthesisInput
) {
    public AgentRuntimeResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        runtimeStopSignal = runtimeStopSignal == null ? AgentRuntimeStopSignal.NONE : runtimeStopSignal;
        evidenceLedger = evidenceLedger == null ? EvidenceLedger.empty() : evidenceLedger;
        trustedEvidenceLedger = trustedEvidenceLedger == null ? EvidenceLedger.empty() : trustedEvidenceLedger;
        domainRuntimeFacts = domainRuntimeFacts == null ? DomainRuntimeFacts.empty() : domainRuntimeFacts;
        planPersistenceLevel = "L2_DURABLE".equals(planPersistenceLevel)
                ? "L2_DURABLE" : "L1_PERSISTED".equals(planPersistenceLevel) ? "L1_PERSISTED" : null;
    }

    /** Source-compatible bridge for the pre-final-synthesis-input result shape. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              AgentStrategy selectedStrategy, AgentStopReason stopReason, String outcome,
                              boolean degraded, AgentStrategy degradedFrom,
                              AgentRuntimeStopSignal runtimeStopSignal, Long planId,
                              EvidenceLedger evidenceLedger, EvidenceLedger trustedEvidenceLedger,
                              CompletionVerification completionVerification, CandidateChangeSet candidateChangeSet,
                              CandidateIntent candidateIntent, CandidateArtifactResponse candidateArtifact,
                              DomainRuntimeFacts domainRuntimeFacts, String planPersistenceLevel) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded,
                degradedFrom, runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger,
                completionVerification, candidateChangeSet, candidateIntent, candidateArtifact,
                domainRuntimeFacts, planPersistenceLevel, null);
    }

    /** Source-compatible bridge for the pre-L2 runtime result shape. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              AgentStrategy selectedStrategy, AgentStopReason stopReason, String outcome,
                              boolean degraded, AgentStrategy degradedFrom,
                              AgentRuntimeStopSignal runtimeStopSignal, Long planId,
                              EvidenceLedger evidenceLedger, EvidenceLedger trustedEvidenceLedger,
                              CompletionVerification completionVerification, CandidateChangeSet candidateChangeSet,
                              CandidateIntent candidateIntent, CandidateArtifactResponse candidateArtifact,
                              DomainRuntimeFacts domainRuntimeFacts) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded,
                degradedFrom, runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger,
                completionVerification, candidateChangeSet, candidateIntent, candidateArtifact,
                domainRuntimeFacts, null);
    }

    /** Source-compatible bridge for callers using the pre-domain-facts canonical result. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              AgentStrategy selectedStrategy, AgentStopReason stopReason, String outcome,
                              boolean degraded, AgentStrategy degradedFrom,
                              AgentRuntimeStopSignal runtimeStopSignal, Long planId,
                              EvidenceLedger evidenceLedger, EvidenceLedger trustedEvidenceLedger,
                              CompletionVerification completionVerification, CandidateChangeSet candidateChangeSet,
                              CandidateIntent candidateIntent, CandidateArtifactResponse candidateArtifact) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded,
                degradedFrom, runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger,
                completionVerification, candidateChangeSet, candidateIntent, candidateArtifact,
                DomainRuntimeFacts.empty());
    }

    /** Source-compatible result constructor for adapters that do not coordinate themselves. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, null, null, null, false, null, AgentRuntimeStopSignal.NONE, null, EvidenceLedger.empty(), EvidenceLedger.empty(), null, null);
    }

    /** Source-compatible canonical projection used before structured Candidate intents existed. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              AgentStrategy selectedStrategy, AgentStopReason stopReason, String outcome,
                              boolean degraded, AgentStrategy degradedFrom,
                              AgentRuntimeStopSignal runtimeStopSignal, Long planId,
                              EvidenceLedger evidenceLedger, EvidenceLedger trustedEvidenceLedger,
                              CompletionVerification completionVerification, CandidateChangeSet candidateChangeSet) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded,
                degradedFrom, runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger,
                completionVerification, candidateChangeSet, null, null, DomainRuntimeFacts.empty());
    }

    public AgentRuntimeResult withCoordination(AgentStrategy selectedStrategy, AgentStopReason stopReason,
                                               String outcome, boolean degraded, AgentStrategy degradedFrom) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal == null ? AgentRuntimeStopSignal.NONE : runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withRuntimeStopSignal(AgentRuntimeStopSignal signal) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                signal == null ? AgentRuntimeStopSignal.NONE : signal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withPlanId(Long planId) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withEvidenceLedger(EvidenceLedger ledger) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, ledger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult insufficientProjectEvidence(EvidenceLedger ledger) {
        return insufficientProjectEvidence(ledger, 0);
    }

    public AgentRuntimeResult insufficientProjectEvidence(EvidenceLedger ledger, int historySize) {
        return insufficientProjectEvidence(ledger, historySize,
                "Insufficient Project evidence: no authorized file read/search observation was captured; this is not a complete review.");
    }

    public AgentRuntimeResult insufficientProjectEvidence(EvidenceLedger ledger, int historySize, String limitation) {
        String safeLimitation = limitation == null || limitation.isBlank()
                ? "Insufficient Project evidence: the requested review is not verified." : limitation;
        java.util.ArrayList<ChatMessage> safeMessages = new java.util.ArrayList<>();
        int boundary = Math.max(0, Math.min(historySize, messages.size()));
        safeMessages.addAll(messages.subList(0, boundary));
        for (ChatMessage message : messages.subList(boundary, messages.size())) {
            boolean unverifiedVisibleAssistant = message != null && "assistant".equalsIgnoreCase(message.role())
                    && (message.toolCalls() == null || message.toolCalls().isEmpty());
            if (!unverifiedVisibleAssistant) {
                safeMessages.add(message);
            }
        }
        safeMessages.add(ChatMessage.assistant(safeLimitation));
        return new AgentRuntimeResult(false, safeLimitation, safeMessages, steps, safeLimitation, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, AgentStopReason.PLAN_PARTIAL, "INSUFFICIENT_EVIDENCE",
                true, degradedFrom, runtimeStopSignal, planId, ledger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withCompletionVerification(CompletionVerification verification) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, verification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    /**
     * Replaces the current turn's final chat-visible assistant with the canonical projection.
     * Tool-call assistants and the persisted history prefix remain byte-for-byte unchanged.
     */
    public AgentRuntimeResult withCanonicalAssistantContent(String content, int historySize) {
        ArrayList<ChatMessage> canonicalMessages = new ArrayList<>(messages);
        int boundary = Math.max(0, Math.min(historySize, canonicalMessages.size()));
        int finalVisibleAssistant = -1;
        for (int i = canonicalMessages.size() - 1; i >= boundary; i--) {
            ChatMessage message = canonicalMessages.get(i);
            if (message != null && "assistant".equalsIgnoreCase(message.role())
                    && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                finalVisibleAssistant = i;
                break;
            }
        }
        if (finalVisibleAssistant >= 0) {
            canonicalMessages.set(finalVisibleAssistant, ChatMessage.assistant(content));
        } else {
            canonicalMessages.add(ChatMessage.assistant(content));
        }
        return new AgentRuntimeResult(success, content, canonicalMessages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withCandidateChangeSet(CandidateChangeSet candidate) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidate, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withCandidateIntent(CandidateIntent intent) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                candidateChangeSet, intent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withCandidateArtifact(CandidateArtifactResponse artifact) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                null, null, artifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult asVerifiedFailure(String message) {
        return new AgentRuntimeResult(false, assistantContent, messages, steps, message, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    /** A controlled partial has useful chat-visible content and is delivered normally without claiming verification. */
    public AgentRuntimeResult asControlledPartial() {
        return new AgentRuntimeResult(true, assistantContent, messages, steps, null, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withTrustedEvidenceLedger(EvidenceLedger ledger) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, ledger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withDomainRuntimeFacts(DomainRuntimeFacts facts) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                candidateChangeSet, candidateIntent, candidateArtifact, facts, planPersistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withPlanPersistenceLevel(String persistenceLevel) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, persistenceLevel, finalSynthesisInput);
    }

    public AgentRuntimeResult withFinalSynthesisInput(FinalSynthesisInput input) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                candidateChangeSet, candidateIntent, candidateArtifact, domainRuntimeFacts, planPersistenceLevel, input);
    }
}
