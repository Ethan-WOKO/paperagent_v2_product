package io.paperagent.v2.chain.transition;

import io.paperagent.v2.chain.ChainPersistenceRecords.*;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainApplicabilityWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime.*;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ChainTransitionTestStore
        implements ChainWorkflowRepository, ChainTransitionWriter,
        ChainApplicabilityWriter, ChainApplicabilityAuthorityPort,
        StageAuthorityVerifier {
    final Map<String, TransitionRecord> transitions = new LinkedHashMap<>();
    final Map<String, List<TransitionStageRecord>> stages =
            new LinkedHashMap<>();
    private long eventSequence;
    final Map<String, FinalizationCheckOutcome> formalAuthorities =
            new LinkedHashMap<>();
    final List<AcceptedResultRecord> acceptedResults = new ArrayList<>();
    final List<ResultApplicabilityRecord> applicabilityDecisions =
            new ArrayList<>();
    ChainApplicabilityAuthorityPort.SourceAuthority applicabilityAuthority;
    boolean normalizeAuditTimesToMicros;

    void normalizeAuditTimesToMicros() {
        normalizeAuditTimesToMicros = true;
    }

    void register(
            String type, String ref, FinalizationCheckOutcome outcome) {
        formalAuthorities.put(type + "\0" + ref, outcome);
    }

    @Override
    public AuthorityVerification verify(StageAuthorityQuery query) {
        TransitionStageRecord stage = query.stage();
        if (stage.successorAuthorityType() == null
                && stage.predecessorAuthorityType() == null) {
            return AuthorityVerification.verifiedEmpty();
        }
        for (String[] authority : List.of(
                new String[]{stage.predecessorAuthorityType(),
                        stage.predecessorAuthorityRef()},
                new String[]{stage.successorAuthorityType(),
                        stage.successorAuthorityRef()})) {
            if (authority[0] == null) continue;
            if ("TRANSITION".equals(authority[0])
                    && transitions.containsKey(authority[1])) continue;
            if (!formalAuthorities.containsKey(
                    authority[0] + "\0" + authority[1])) {
                return new AuthorityVerification(false, null, false);
            }
        }
        String type = stage.successorAuthorityType() != null
                ? stage.successorAuthorityType()
                : stage.predecessorAuthorityType();
        String ref = stage.successorAuthorityRef() != null
                ? stage.successorAuthorityRef()
                : stage.predecessorAuthorityRef();
        String key = type + "\0" + ref;
        FinalizationCheckOutcome outcome = formalAuthorities.get(key);
        return outcome == null
                ? AuthorityVerification.verified()
                : AuthorityVerification.finalization(outcome);
    }

    @Override public Optional<TransitionRecord> findTransition(String id) { return Optional.ofNullable(transitions.get(id)); }
    @Override public List<TransitionStageRecord> findTransitionStages(String id) { return List.copyOf(stages.getOrDefault(id, List.of())); }
    @Override public List<TransitionRecord> findIncompleteTransitions(String taskId) { return transitions.values().stream().filter(value -> value.taskId().equals(taskId) && !value.transitionType().isCompleteSequence(findTransitionStages(value.transitionId()).stream().map(TransitionStageRecord::stageCode).toList())).toList(); }
    @Override public List<RouteDecisionRecord> findRouteDecisions(String taskId) { return List.of(); }
    @Override public List<PlanBindingRecord> findPlanBindings(String taskId) { return List.of(); }
    @Override public List<CandidateStepResultRecord> findCandidateStepResults(String taskId) { return List.of(); }
    @Override public List<ReviewDecisionRecord> findReviewDecisions(String taskId) { return List.of(); }
    @Override public List<AcceptedResultRecord> findAcceptedResults(String taskId) { return acceptedResults.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<ResultApplicabilityRecord> findApplicabilityDecisions(String taskId) { return applicabilityDecisions.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<PendingItemRecord> findPendingItems(String taskId) { return List.of(); }
    @Override public List<PendingItemRecord> findOpenPendingItems(String taskId) { return List.of(); }
    @Override public List<PendingItemEventRecord> findPendingItemEvents(String gapId) { return List.of(); }
    @Override public List<PermissionDecisionRecord> findPermissionDecisions(String taskId) { return List.of(); }
    @Override public List<ActionBindingRecord> findActionBindings(String taskId) { return List.of(); }
    @Override public List<ActionBindingRecord> findInFlightActions(String taskId) { return List.of(); }
    @Override public List<WorkspaceCandidateRecord> findWorkspaceCandidates(String taskId) { return List.of(); }

    @Override
    public AuthoritativeAppendResult<TransitionRecord> appendTransition(
            AuthoritativeFact<TransitionRecord> value) {
        TransitionRecord requested = normalized(value.fact());
        TransitionRecord existing = transitions.get(
                requested.transitionId());
        if (existing != null) {
            if (!existing.equals(requested)) {
                throw new IllegalStateException("conflicting transition replay");
            }
            return new AuthoritativeAppendResult<>(
                    event(value.event()), existing, true);
        }
        transitions.put(requested.transitionId(), requested);
        return new AuthoritativeAppendResult<>(
                event(value.event()), requested, false);
    }

    @Override
    public AuthoritativeAppendResult<TransitionStageRecord>
            appendTransitionStage(
                    AuthoritativeFact<TransitionStageRecord> value) {
        TransitionStageRecord requested = normalized(value.fact());
        List<TransitionStageRecord> values = stages.computeIfAbsent(
                requested.transitionId(), ignored -> new ArrayList<>());
        Optional<TransitionStageRecord> existing = values.stream().filter(
                stage -> stage.stageCode() == requested.stageCode())
                .findFirst();
        if (existing.isPresent()) {
            if (!existing.get().equals(requested)) {
                throw new IllegalStateException("conflicting stage replay");
            }
            return new AuthoritativeAppendResult<>(
                    event(value.event()), existing.get(), true);
        }
        values.add(requested);
        return new AuthoritativeAppendResult<>(
                event(value.event()), requested, false);
    }

    private TransitionRecord normalized(TransitionRecord value) {
        if (!normalizeAuditTimesToMicros) return value;
        return new TransitionRecord(
                value.transitionId(), value.taskId(), value.eventId(),
                value.transitionType(), value.sourceDecisionId(),
                value.targetIdentityDigest(),
                value.createdAt().truncatedTo(ChronoUnit.MICROS));
    }

    private TransitionStageRecord normalized(TransitionStageRecord value) {
        if (!normalizeAuditTimesToMicros) return value;
        return new TransitionStageRecord(
                value.transitionId(), value.stageCode(), value.taskId(),
                value.eventId(), value.stageOrdinal(),
                value.predecessorAuthorityType(),
                value.predecessorAuthorityRef(),
                value.successorAuthorityType(),
                value.successorAuthorityRef(),
                value.committedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    public ChainApplicabilityAuthorityPort.SourceAuthority verify(
            ChainApplicabilityAuthorityPort.SourceQuery query) {
        if (applicabilityAuthority == null) {
            throw new IllegalStateException(
                    "applicability source authority not configured");
        }
        return applicabilityAuthority;
    }

    @Override
    public AuthoritativeAppendResult<ResultApplicabilityRecord>
            appendApplicability(
                    AuthoritativeFact<ResultApplicabilityRecord> value) {
        Optional<ResultApplicabilityRecord> existing = applicabilityDecisions
                .stream().filter(item -> item.applicabilityId().equals(
                        value.fact().applicabilityId())).findFirst();
        if (existing.isPresent()) {
            return new AuthoritativeAppendResult<>(
                    event(value.event()), existing.get(), true);
        }
        applicabilityDecisions.add(value.fact());
        return new AuthoritativeAppendResult<>(
                event(value.event()), value.fact(), false);
    }

    private AuthorityEventRecord event(AuthorityEventRequest request) {
        return new AuthorityEventRecord(
                request.eventId(), request.taskId(), ++eventSequence,
                request.eventType(), request.transitionId(),
                request.sourceIdentitySha256(), request.committedAt());
    }
}
