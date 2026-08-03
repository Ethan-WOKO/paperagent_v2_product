package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextRevisionDraft;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class V2ContextRevisionOrchestrator {
    private final V2ContextRevisionService revisions;
    private final V2ContextStageKeyFactory keys;
    private final V2SectionCompactor compactor;

    public V2ContextRevisionOrchestrator(
            V2ContextRevisionService revisions,
            V2ContextStageKeyFactory keys,
            V2SectionCompactor compactor) {
        this.revisions = revisions;
        this.keys = keys;
        this.compactor = compactor;
    }

    public V2ContextBoundaryResult prepare(V2ContextBoundaryRequest request) {
        return prepare(request, null);
    }

    public V2ContextBoundaryResult prepare(
            V2ContextBoundaryRequest request,
            V2SectionCompactionResult precomputedCompaction) {
        String logicalKey = keys.logicalKey(request.stage(),
                request.canonicalAuthorityTuple(), request.subCall());
        Map<V2ContextRevisionStatus, Integer> numbers = phaseNumbers(request);
        PlannedCompaction plan = planCompaction(request, precomputedCompaction);
        validateExplicitPlan(request, numbers, plan);
        List<V2ContextRevisionSnapshot> snapshots = new ArrayList<>();
        Parent parent = new Parent(request.parentSnapshotId(), request.parentDigest());
        parent = append(request, numbers, logicalKey,
                V2ContextRevisionStatus.ASSEMBLING,
                request.sections(), parent, snapshots);
        if (!plan.overflow()) {
            Parent ready = append(request, numbers, logicalKey,
                    V2ContextRevisionStatus.READY,
                    request.sections(), parent, snapshots);
            return new V2ContextBoundaryPrepared(
                    snapshotById(snapshots, ready.snapshotId()), snapshots);
        }
        if (plan.result() == null) {
            return fail(request, numbers, logicalKey, parent, snapshots,
                    request.compactionTarget(), request.sections(),
                    "SINGLE_TARGET_REQUIRED");
        }
        parent = append(request, numbers, logicalKey,
                V2ContextRevisionStatus.COMPACTION_REQUIRED,
                request.sections(), parent, snapshots);
        V2ContextSectionDraft target = plan.target();
        V2SectionCompactionResult result = plan.result();
        List<V2ContextSectionDraft> compacted = plan.sections();
        parent = append(request, numbers, logicalKey,
                V2ContextRevisionStatus.COMPACTING,
                compacted, parent, snapshots);
        if (!validCompaction(request.sections(), compacted, target.type(), result)) {
            return fail(request, numbers, logicalKey, parent, snapshots,
                    target.type(), compacted, result.code());
        }
        Parent ready = append(request, numbers, logicalKey,
                V2ContextRevisionStatus.READY,
                compacted, parent, snapshots);
        return new V2ContextBoundaryPrepared(
                snapshotById(snapshots, ready.snapshotId()), snapshots);
    }

    private V2ContextBoundaryFailure fail(
            V2ContextBoundaryRequest request,
            Map<V2ContextRevisionStatus, Integer> numbers,
            String logicalKey,
            Parent parent,
            List<V2ContextRevisionSnapshot> snapshots,
            ContextSectionType failedType,
            List<V2ContextSectionDraft> sections,
            String code) {
        List<V2ContextSectionDraft> failed = sections.stream().map(section ->
                section.type() == failedType
                        ? new V2ContextSectionDraft(section.type(), section.fixedPercentage(),
                            section.tokenLimit(), section.tokensBefore(), section.tokensAfter(),
                            V2ContextSectionStatus.FAILED, section.sourceRefsJson(),
                            section.projectionJson(), code)
                        : section).toList();
        Parent failedParent = append(request, numbers, logicalKey,
                V2ContextRevisionStatus.FAILED, failed, parent, snapshots);
        return new V2ContextBoundaryFailure(code, failedType,
                snapshotById(snapshots, failedParent.snapshotId()), snapshots);
    }

    private Parent append(
            V2ContextBoundaryRequest request,
            Map<V2ContextRevisionStatus, Integer> numbers,
            String logicalKey,
            V2ContextRevisionStatus status,
            List<V2ContextSectionDraft> sections,
            Parent parent,
            List<V2ContextRevisionSnapshot> snapshots) {
        Integer number = numbers.get(status);
        if (number == null) {
            throw new IllegalArgumentException("explicit revision missing for " + status);
        }
        String stableKey = status == V2ContextRevisionStatus.READY
                ? logicalKey : keys.childKey(logicalKey, status, request.attempt());
        long total = sections.stream().mapToLong(V2ContextSectionDraft::tokensAfter).sum();
        V2ContextRevisionDraft draft = new V2ContextRevisionDraft(
                request.userId(), request.sessionId(), request.turnId(), number,
                parent.snapshotId(), parent.digest(), request.stage(), stableKey,
                status, request.modelProvider(), request.model(),
                request.contextWindowTokens(), request.maxOutputTokens(),
                request.tokenCounterVersion(), request.profileVersion(), total,
                request.outputReserveTokens(), sections);
        V2ContextRevisionSnapshot snapshot = revisions.append(draft);
        snapshots.add(snapshot);
        return new Parent(snapshot.id(), snapshot.contextDigest());
    }

    private Map<V2ContextRevisionStatus, Integer> phaseNumbers(
            V2ContextBoundaryRequest request) {
        Map<V2ContextRevisionStatus, Integer> values =
                new EnumMap<>(V2ContextRevisionStatus.class);
        int previous = 0;
        for (V2ContextPhaseRevision value : request.phaseRevisions()) {
            if (value.revisionNumber() <= previous
                    || values.put(value.phase(), value.revisionNumber()) != null) {
                throw new IllegalArgumentException(
                        "phase revisions must be explicit and increasing");
            }
            previous = value.revisionNumber();
        }
        return values;
    }

    private void validateExplicitPlan(
            V2ContextBoundaryRequest request,
            Map<V2ContextRevisionStatus, Integer> numbers,
            PlannedCompaction plan) {
        Integer assembling = numbers.get(V2ContextRevisionStatus.ASSEMBLING);
        if (assembling == null) {
            throw new IllegalArgumentException(
                    "explicit revision missing for ASSEMBLING");
        }
        if ((request.parentSnapshotId() == null && assembling != 1)
                || (request.parentSnapshotId() != null && assembling <= 1)) {
            throw new IllegalArgumentException(
                    "explicit parent/revision contract is invalid");
        }
        if (!plan.overflow()) {
            requirePhase(numbers, V2ContextRevisionStatus.READY);
            return;
        }
        if (plan.result() == null) {
            requirePhase(numbers, V2ContextRevisionStatus.FAILED);
            return;
        }
        requirePhase(numbers, V2ContextRevisionStatus.COMPACTION_REQUIRED);
        requirePhase(numbers, V2ContextRevisionStatus.COMPACTING);
        requirePhase(numbers, plan.valid()
                ? V2ContextRevisionStatus.READY
                : V2ContextRevisionStatus.FAILED);
    }

    private void requirePhase(
            Map<V2ContextRevisionStatus, Integer> numbers,
            V2ContextRevisionStatus phase) {
        if (!numbers.containsKey(phase)) {
            throw new IllegalArgumentException(
                    "explicit revision missing for " + phase);
        }
    }

    private PlannedCompaction planCompaction(
            V2ContextBoundaryRequest request,
            V2SectionCompactionResult precomputedCompaction) {
        List<V2ContextSectionDraft> overflowing = request.sections().stream()
                .filter(section -> section.tokensAfter() > section.tokenLimit()
                        || section.status() == V2ContextSectionStatus.COMPACTION_REQUIRED)
                .toList();
        if (overflowing.isEmpty()) {
            return new PlannedCompaction(false, null, null,
                    request.sections(), true);
        }
        if (overflowing.size() != 1 || request.compactionTarget() == null
                || overflowing.get(0).type() != request.compactionTarget()) {
            return new PlannedCompaction(true, null, null,
                    request.sections(), false);
        }
        V2ContextSectionDraft target = overflowing.get(0);
        V2SectionCompactionResult result = precomputedCompaction == null
                ? compactor.compact(target) : precomputedCompaction;
        List<V2ContextSectionDraft> sections = replaceOnly(
                request.sections(), target.type(), result.section());
        return new PlannedCompaction(true, target, result, sections,
                validCompaction(request.sections(), sections, target.type(), result));
    }

    private List<V2ContextSectionDraft> replaceOnly(
            List<V2ContextSectionDraft> source,
            ContextSectionType target,
            V2ContextSectionDraft replacement) {
        return source.stream().map(section -> section.type() == target
                ? replacement : section).toList();
    }

    private boolean validCompaction(
            List<V2ContextSectionDraft> before,
            List<V2ContextSectionDraft> after,
            ContextSectionType target,
            V2SectionCompactionResult result) {
        if (!result.success() || result.tokensAfter() > result.targetTokens()) return false;
        for (int index = 0; index < before.size(); index++) {
            V2ContextSectionDraft oldSection = before.get(index);
            V2ContextSectionDraft newSection = after.get(index);
            if (oldSection.type() != newSection.type()) return false;
            if (oldSection.type() != target
                    && (!oldSection.equals(newSection)
                        || oldSection.sourceRefsJson() != newSection.sourceRefsJson()
                        || oldSection.projectionJson() != newSection.projectionJson())) {
                return false;
            }
        }
        return true;
    }

    private V2ContextRevisionSnapshot snapshotById(
            List<V2ContextRevisionSnapshot> snapshots, Long id) {
        return snapshots.stream().filter(value -> value.id().equals(id))
                .findFirst().orElseThrow();
    }

    private record Parent(Long snapshotId, String digest) { }

    private record PlannedCompaction(
            boolean overflow,
            V2ContextSectionDraft target,
            V2SectionCompactionResult result,
            List<V2ContextSectionDraft> sections,
            boolean valid) { }
}
