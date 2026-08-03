package com.yanban.api.agent.v2.context;

import com.yanban.core.agent.AgentContextSnapshot;
import com.yanban.core.agent.AgentContextSnapshotRepository;
import com.yanban.core.agent.AgentContextSnapshotSection;
import com.yanban.core.agent.AgentContextSnapshotSectionRepository;
import com.yanban.core.agent.AgentTurn;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class V2ContextRevisionTransactions {
    private final AgentContextSnapshotRepository headers;
    private final AgentContextSnapshotSectionRepository sections;
    private final EntityManager entityManager;
    private final V2ContextRevisionCodec codec;

    public V2ContextRevisionTransactions(
            AgentContextSnapshotRepository headers,
            AgentContextSnapshotSectionRepository sections,
            EntityManager entityManager,
            V2ContextRevisionCodec codec) {
        this.headers = headers;
        this.sections = sections;
        this.entityManager = entityManager;
        this.codec = codec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public V2ContextRevisionSnapshot insert(
            V2ContextRevisionDraft draft,
            V2ContextRevisionCodec.EncodedRevision encoded) {
        AgentTurn turn = entityManager.find(AgentTurn.class, draft.turnId());
        if (turn == null || !draft.userId().equals(turn.getUserId())
                || !draft.sessionId().equals(turn.getSessionId())) {
            throw new V2ContextRevisionConflictException(
                    "context turn authority is invalid");
        }
        if (headers.findByUserIdAndSessionIdAndTurnIdAndStableStageKey(
                draft.userId(), draft.sessionId(), draft.turnId(),
                draft.stableStageKey()).isPresent()) {
            throw new V2ContextRevisionRaceException();
        }
        if (headers.findByTurnIdAndRevisionNumber(
                draft.turnId(), draft.revisionNumber()).isPresent()) {
            throw new V2ContextRevisionRaceException();
        }
        verifyParent(draft);
        AgentContextSnapshot header = new AgentContextSnapshot(
                draft.turnId(), draft.sessionId(), draft.userId(),
                draft.revisionNumber(), draft.parentSnapshotId(),
                draft.stage().name(), draft.stableStageKey(),
                draft.status().name(), draft.modelProvider(), draft.model(),
                draft.contextWindowTokens(), draft.maxOutputTokens(),
                draft.tokenCounterVersion(), draft.profileVersion(),
                draft.totalTokens(), draft.outputReserveTokens(),
                draft.parentDigest(), encoded.digest());
        entityManager.persist(header);
        entityManager.flush();
        for (V2ContextRevisionCodec.EncodedSection section
                : encoded.sections()) {
            V2ContextSectionDraft value = section.draft();
            entityManager.persist(new AgentContextSnapshotSection(
                    header.getId(), section.ordinal(), value.type().name(),
                    value.fixedPercentage(), value.tokenLimit(),
                    value.tokensBefore(), value.tokensAfter(),
                    value.status().name(), section.sourceRefsJson(),
                    section.projectionJson(), section.projectionDigest(),
                    value.compactionReason()));
        }
        entityManager.flush();
        return codec.decode(header,
                sections.findBySnapshotIdOrderBySectionOrdinalAsc(
                        header.getId()),
                V2ContextRevisionOutcome.APPLIED);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public Optional<V2ContextRevisionSnapshot> readByStableKey(
            Long userId, Long sessionId, Long turnId, String stableStageKey) {
        return headers.findByUserIdAndSessionIdAndTurnIdAndStableStageKey(
                        userId, sessionId, turnId, stableStageKey)
                .map(header -> codec.decode(
                        header,
                        sections.findBySnapshotIdOrderBySectionOrdinalAsc(
                                header.getId()),
                        V2ContextRevisionOutcome.REPLAYED));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public Optional<V2ContextRevisionSnapshot> readByRevision(
            Long userId, Long sessionId, Long turnId, int revisionNumber) {
        return headers.findByTurnIdAndRevisionNumber(turnId, revisionNumber)
                .filter(header -> userId.equals(header.getUserId())
                        && sessionId.equals(header.getSessionId()))
                .map(header -> codec.decode(
                        header,
                        sections.findBySnapshotIdOrderBySectionOrdinalAsc(
                                header.getId()),
                        V2ContextRevisionOutcome.REPLAYED));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public Optional<V2ContextRevisionSnapshot> readLatest(
            Long userId, Long sessionId, Long turnId) {
        return headers.findByTurnIdAndSessionIdAndUserId(
                        turnId, sessionId, userId)
                .map(header -> codec.decode(
                        header,
                        sections.findBySnapshotIdOrderBySectionOrdinalAsc(
                                header.getId()),
                        V2ContextRevisionOutcome.REPLAYED));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public Optional<V2ContextRevisionSnapshot> readById(
            Long userId, Long sessionId, Long turnId, Long snapshotId) {
        return headers.findByIdAndUserIdAndSessionIdAndTurnId(
                        snapshotId, userId, sessionId, turnId)
                .map(header -> codec.decode(
                        header,
                        sections.findBySnapshotIdOrderBySectionOrdinalAsc(
                                header.getId()),
                        V2ContextRevisionOutcome.REPLAYED));
    }

    private void verifyParent(V2ContextRevisionDraft draft) {
        if (draft.revisionNumber() == 1) return;
        AgentContextSnapshot parent = headers
                .findByIdAndUserIdAndSessionIdAndTurnId(
                        draft.parentSnapshotId(), draft.userId(),
                        draft.sessionId(), draft.turnId())
                .orElseThrow(() -> new V2ContextRevisionConflictException(
                        "context direct parent is invalid"));
        if (parent.getRevisionNumber() != draft.revisionNumber() - 1
                || !draft.parentDigest().equals(parent.getContextDigest())) {
            throw new V2ContextRevisionConflictException(
                    "context direct parent is invalid");
        }
        AgentContextSnapshot direct = headers.findByTurnIdAndRevisionNumber(
                        draft.turnId(), draft.revisionNumber() - 1)
                .orElseThrow(() -> new V2ContextRevisionConflictException(
                        "context direct parent is missing"));
        if (!direct.getId().equals(parent.getId())) {
            throw new V2ContextRevisionConflictException(
                    "context direct parent is invalid");
        }
    }

    static final class V2ContextRevisionRaceException
            extends RuntimeException { }
}
