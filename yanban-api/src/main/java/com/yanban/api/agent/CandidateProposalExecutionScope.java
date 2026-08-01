package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Set;

/** Request-bound inputs that a model cannot supply or override. */
final class CandidateProposalExecutionScope implements AutoCloseable {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    record Context(Long userId, Long projectId, Long sessionId, List<ChatMessage> transcript,
                   int currentTurnStart, EvidenceLedger inheritedEvidence, Set<String> allowedTools) {
        Context {
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
            inheritedEvidence = inheritedEvidence == null ? EvidenceLedger.empty() : inheritedEvidence;
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        }
    }

    private CandidateProposalExecutionScope(Context context) {
        if (CURRENT.get() != null) throw new IllegalStateException("candidate proposal scope is already bound");
        CURRENT.set(context);
    }

    static CandidateProposalExecutionScope open(AgentRuntimeRequest request, List<ChatMessage> transcript) {
        if (request == null || request.projectContext() == null
                || !request.userId().equals(request.projectContext().userId())) {
            throw new IllegalArgumentException("candidate proposal requires a trusted Project runtime");
        }
        return new CandidateProposalExecutionScope(new Context(request.userId(),
                request.projectContext().projectId(), request.sessionId(), transcript,
                request.history().size(), request.inheritedTrustedEvidence(),
                Set.copyOf(request.toolPolicy().allowedTools())));
    }

    static Context current() { return CURRENT.get(); }

    @Override
    public void close() { CURRENT.remove(); }
}
