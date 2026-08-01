package com.yanban.api.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentRuntimeService {

    private final List<RuntimeAdapter> adapters;
    private final CompletionVerifier completionVerifier;
    private final CompletionReflection completionReflection;
    private final CompletionRepairExecutor completionRepairExecutor;

    public AgentRuntimeService(List<RuntimeAdapter> adapters) {
        this(adapters, null, null, null);
    }

    @Autowired
    public AgentRuntimeService(List<RuntimeAdapter> adapters,
                               CompletionVerifier completionVerifier,
                               CompletionReflection completionReflection,
                               CompletionRepairExecutor completionRepairExecutor) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
        this.completionVerifier = completionVerifier;
        this.completionReflection = completionReflection;
        this.completionRepairExecutor = completionRepairExecutor;
    }

    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        if (request == null || request.strategy() == null) {
            throw new IllegalArgumentException("runtime strategy must be selected before adapter execution");
        }
        RuntimeAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(request))
                .findFirst()
                .orElseThrow(() -> new NoRuntimeAdapterException(request.strategy()));
        AgentRuntimeResult raw = adapter.run(request);
        if (completionVerifier == null) {
            return raw;
        }
        AgentRuntimeResult verified = completionVerifier.verify(request, raw);
        CompletionVerification first = verified.completionVerification();
        if (completionReflection != null && completionRepairExecutor != null && completionReflection.mayAttempt(request, first, raw)) {
            AgentRuntimeRequest repairRequest = completionReflection.repairRequest(request, raw, first);
            AgentRuntimeResult repaired = completionRepairExecutor.repair(adapter, repairRequest);
            return completionVerifier.verifyAfterReflection(repairRequest, mergeRepairResult(verified, repaired), first);
        }
        return verified;
    }

    /**
     * A repair is an internal retry of the same chat turn. Keep only its final transcript so
     * the persisted conversation contains one history prefix and one chat-visible answer.
     * The first attempt's already-verified observations remain available as a typed ledger;
     * they are never recovered by rescanning a duplicated history transcript.
     */
    private AgentRuntimeResult mergeRepairResult(AgentRuntimeResult firstVerified, AgentRuntimeResult second) {
        ArrayList<String> trace = new ArrayList<>(firstVerified.toolTrace());
        trace.addAll(second.toolTrace());
        ArrayList<String> fallbacks = new ArrayList<>(firstVerified.fallbacks());
        fallbacks.addAll(second.fallbacks());
        EvidenceLedger retainedTrustedEvidence = mergeExactEvidence(
                firstVerified.trustedEvidenceLedger(), second.trustedEvidenceLedger());
        DomainRuntimeFacts mergedFacts = firstVerified.domainRuntimeFacts().withExecutionAttempt(0)
                .merge(second.domainRuntimeFacts().withExecutionAttempt(1));
        return new AgentRuntimeResult(second.success(), second.assistantContent(), second.messages(),
                firstVerified.steps() + second.steps(), second.errorMessage(), trace, fallbacks,
                sum(firstVerified.promptTokens(), second.promptTokens()),
                sum(firstVerified.completionTokens(), second.completionTokens()),
                sum(firstVerified.totalTokens(), second.totalTokens()))
                .withCoordination(second.selectedStrategy(), second.stopReason(), second.outcome(), second.degraded(), second.degradedFrom())
                .withRuntimeStopSignal(second.runtimeStopSignal()).withPlanId(second.planId())
                .withEvidenceLedger(second.evidenceLedger()).withTrustedEvidenceLedger(retainedTrustedEvidence)
                .withDomainRuntimeFacts(mergedFacts);
    }

    private EvidenceLedger mergeExactEvidence(EvidenceLedger left, EvidenceLedger right) {
        Map<String, EvidenceRef> values = new LinkedHashMap<>();
        if (left != null) left.evidence().forEach(ref -> putExact(values, ref));
        if (right != null) right.evidence().forEach(ref -> putExact(values, ref));
        return new EvidenceLedger(List.copyOf(values.values()));
    }

    private void putExact(Map<String, EvidenceRef> values, EvidenceRef ref) {
        if (ref == null) return;
        EvidenceRef existing = values.putIfAbsent(ref.id(), ref);
        if (existing != null && !existing.equals(ref)) {
            throw new IllegalArgumentException("conflicting evidence id: " + ref.id());
        }
    }

    private Integer sum(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return left + right;
    }
}
