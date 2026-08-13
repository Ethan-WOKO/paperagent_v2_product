package io.paperagent.v2.chain.validation;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainValidationBundleRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-09T04:00:00Z");
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);
    private static final String H3 = "3".repeat(64);
    private static final String H4 = "4".repeat(64);
    private static final String H5 = "5".repeat(64);

    @Test
    void closesMultipleStepsOrderIndependentlyAndReplaysExactly() {
        Fixture fixture = fixture();
        InMemoryBundleRepository repository = new InMemoryBundleRepository();
        var runtime = new ChainValidationBundleRuntime(repository);

        var first = assertInstanceOf(ChainValidationBundleRuntime.Committed.class,
                runtime.commit(fixture.command(fixture.sources())));
        var replay = assertInstanceOf(ChainValidationBundleRuntime.Committed.class,
                runtime.commit(fixture.command(List.of(
                        fixture.sources().get(1), fixture.sources().get(0)))));

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.bundle(), replay.bundle());
        assertEquals(List.of("step-1", "step-2"), first.sets().stream()
                .map(ChainPersistenceRecords.ValidationBundleSetRecord::stepId)
                .toList());
    }

    @Test
    void rejectsMissingExtraOrDriftedFormalSources() {
        Fixture fixture = fixture();
        var runtime = new ChainValidationBundleRuntime(
                new InMemoryBundleRepository());
        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(fixture.sources().get(0)))));

        var source = fixture.sources().get(0);
        var wrongInstruction = copyResult(source.stepResult(),
                "instruction-other", source.stepResult().validationId());
        var drifted = new ChainValidationBundleRuntime.FormalSource(
                source.validation(), source.validationEvent(), source.candidateItems(),
                source.actionReceiptItems(), wrongInstruction,
                source.workspaceCandidate(), source.receiptRefs());
        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(drifted, fixture.sources().get(1)))));

        var missingReceipt = new ChainValidationBundleRuntime.FormalSource(
                source.validation(), source.validationEvent(), source.candidateItems(),
                source.actionReceiptItems(), source.stepResult(),
                source.workspaceCandidate(), List.of("receipt-action-1"));
        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(missingReceipt,
                        fixture.sources().get(1)))));

        var failedSet = copySet(source.validation(),
                source.validation().requestDigest(),
                io.paperagent.v2.chain.ChainValidationConclusion.FAILED);
        var failed = new ChainValidationBundleRuntime.FormalSource(
                failedSet, source.validationEvent(), source.candidateItems(), source.actionReceiptItems(),
                source.stepResult(), source.workspaceCandidate(),
                source.receiptRefs());
        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(failed, fixture.sources().get(1)))));

        var badDigestSet = copySet(source.validation(), H5,
                io.paperagent.v2.chain.ChainValidationConclusion.PASSED);
        var badDigest = new ChainValidationBundleRuntime.FormalSource(
                badDigestSet, source.validationEvent(), source.candidateItems(),
                source.actionReceiptItems(), source.stepResult(),
                source.workspaceCandidate(), source.receiptRefs());
        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(badDigest,
                        fixture.sources().get(1)))));

        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(source, source,
                        fixture.sources().get(1)))));

        var wrongEvent = new ChainPersistenceRecords.AuthorityEventRecord(
                source.validationEvent().eventId(),
                source.validationEvent().taskId(),
                source.validationEvent().eventSequence(), "VALIDATION",
                null, H5, NOW);
        var eventDrifted = new ChainValidationBundleRuntime.FormalSource(
                source.validation(), wrongEvent, source.candidateItems(),
                source.actionReceiptItems(), source.stepResult(),
                source.workspaceCandidate(), source.receiptRefs());
        assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                fixture.command(List.of(eventDrifted,
                        fixture.sources().get(1)))));
        for (var wrong : List.of(
                new ChainPersistenceRecords.AuthorityEventRecord(
                        "validation-event-other",
                        source.validationEvent().taskId(), 1,
                        "VALIDATION", null,
                        source.validationEvent().sourceIdentitySha256(), NOW),
                new ChainPersistenceRecords.AuthorityEventRecord(
                        source.validationEvent().eventId(),
                        source.validationEvent().taskId(), 1,
                        "VALIDATION_BUNDLE", null,
                        source.validationEvent().sourceIdentitySha256(), NOW))) {
            var wrongAuthority = new ChainValidationBundleRuntime.FormalSource(
                    source.validation(), wrong, source.candidateItems(),
                    source.actionReceiptItems(), source.stepResult(),
                    source.workspaceCandidate(), source.receiptRefs());
            assertThrows(IllegalArgumentException.class, () -> runtime.commit(
                    fixture.command(List.of(wrongAuthority,
                            fixture.sources().get(1)))));
        }
    }

    @Test
    void rejectsCandidateThatDoesNotBindExactWorkspaceAuthority() {
        Fixture fixture = fixture();
        var source = fixture.sources().get(0);
        var wrongWorkspace = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                source.workspaceCandidate().workspaceCandidateId(), "task-1",
                "workspace-event", "candidate-action", "workspace-wrong",
                H4, 101L, H3, H5, H1, NOW);
        var drifted = new ChainValidationBundleRuntime.FormalSource(
                source.validation(), source.validationEvent(), source.candidateItems(),
                source.actionReceiptItems(), source.stepResult(),
                wrongWorkspace, source.receiptRefs());

        assertThrows(IllegalArgumentException.class, () ->
                new ChainValidationBundleRuntime(new InMemoryBundleRepository())
                        .commit(fixture.command(List.of(
                                drifted, fixture.sources().get(1)))));
    }

    @Test
    void explicitNoRequirementsReturnsNotRequiredWithoutWriting() {
        InMemoryBundleRepository repository = new InMemoryBundleRepository();
        var command = new ChainValidationBundleRuntime.CommitCommand(
                new ChainValidationBundleRuntime.Scope(
                        "task-1", "frame-1", "plan-1", "revision-1", 1,
                        "instruction-1", "step-1", "bundle-empty", NOW),
                TaskRequirements.explicit(
                List.of(), PublishRequirement.NOT_REQUIRED),
                List.of(step("step-1", List.of(), List.of("done"))),
                List.of());
        assertInstanceOf(ChainValidationBundleRuntime.NotRequired.class,
                new ChainValidationBundleRuntime(repository).commit(command));
        assertEquals(0, repository.appendCount);
        assertThrows(IllegalArgumentException.class, () ->
                new ChainValidationBundleRuntime(repository).commit(
                        new ChainValidationBundleRuntime.CommitCommand(scope(),
                                TaskRequirements.legacyUnspecified(),
                                command.steps(), List.of())));
    }

    @Test
    void candidateOnlyStepDoesNotLookLikeOverlappingTypedItems() {
        ValidationRequirement candidate = new ValidationRequirement(
                "candidate-required", ValidationSubject.CANDIDATE,
                "candidate verified");
        Fixture fixture = new Fixture(
                TaskRequirements.explicit(
                        List.of(candidate), PublishRequirement.NOT_REQUIRED),
                List.of(step("step-1", List.of(candidate.requirementId()),
                        List.of(candidate.completionCondition()))),
                List.of(source("step-1", "activation-1",
                        List.of(candidate), true)));

        var committed = assertInstanceOf(
                ChainValidationBundleRuntime.Committed.class,
                new ChainValidationBundleRuntime(
                        new InMemoryBundleRepository())
                        .commit(fixture.command(fixture.sources())));

        assertEquals(List.of("step-1"), committed.sets().stream()
                .map(ChainPersistenceRecords.ValidationBundleSetRecord::stepId)
                .toList());
    }

    private static Fixture fixture() {
        ValidationRequirement candidate = new ValidationRequirement(
                "candidate-required", ValidationSubject.CANDIDATE,
                "candidate verified");
        ValidationRequirement action1 = new ValidationRequirement(
                "action-required-1", ValidationSubject.ACTION_RECEIPT,
                "first action verified");
        ValidationRequirement action2 = new ValidationRequirement(
                "action-required-2", ValidationSubject.ACTION_RECEIPT,
                "second action verified");
        List<PlanStep> steps = List.of(
                step("step-1", List.of(candidate.requirementId(),
                                action1.requirementId()),
                        List.of(candidate.completionCondition(),
                                action1.completionCondition())),
                step("step-2", List.of(action2.requirementId()),
                        List.of(action2.completionCondition())));
        TaskRequirements requirements = TaskRequirements.explicit(
                List.of(candidate, action1, action2),
                PublishRequirement.NOT_REQUIRED);
        var source1 = source("step-1", "activation-1",
                List.of(candidate, action1), true);
        var source2 = source("step-2", "activation-2",
                List.of(action2), false);
        return new Fixture(requirements, steps, List.of(source1, source2));
    }

    private static ChainValidationBundleRuntime.FormalSource source(
            String stepId, String activationId,
            List<ValidationRequirement> requirements, boolean candidate) {
        CapturingValidationRepository repository =
                new CapturingValidationRepository();
        var validationRuntime = new ChainValidationRuntime(authority(), repository);
        List<ProposalFields.ValidationSource> sources = requirements.stream()
                .map(requirement -> new ProposalFields.ValidationSource(
                        requirement.requirementId(), receipt(requirement)))
                .toList();
        var committed = validationRuntime.commit(
                new ChainValidationRuntime.CommitCommand(
                        new ChainValidationRuntime.Scope(
                                "task-1", "frame-1", "plan-1", "revision-1",
                                1, stepId, activationId,
                                "validation-key-" + stepId, NOW),
                        requirements, sources));
        List<String> refs = new ArrayList<>(sources.stream()
                .map(ProposalFields.ValidationSource::receiptRef).toList());
        refs.sort(String::compareTo);
        String json = canonicalArray(refs);
        var stepResult = new ChainPersistenceRecords.CandidateStepResultRecord(
                "result-" + stepId, "task-1", "result-event-" + stepId,
                "proposal-1", "content-1", "instruction-1", "frame-1",
                "plan-1", "revision-1", 1, stepId, activationId,
                candidate ? 101L : null, candidate ? H3 : null,
                candidate ? H5 : null,
                new ChainPersistenceRecords.CanonicalJson(
                        1, ChainValidationIdentity.sha256(json), json),
                committed.validation().validationId(),
                committed.validation().requestDigest(),
                committed.validation().receiptSetDigest(),
                new ChainPersistenceRecords.CanonicalJson(
                        1, ChainValidationIdentity.sha256("[]"), "[]"),
                H1, NOW);
        ChainPersistenceRecords.WorkspaceCandidateRecord workspace = candidate
                ? new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "candidate-1", "task-1", "workspace-event",
                "candidate-action", "workspace-1", H4, 101L, H3, H5,
                H1, NOW) : null;
        return new ChainValidationBundleRuntime.FormalSource(
                committed.validation(), repository.event,
                committed.candidateItems(),
                committed.actionReceiptItems(), stepResult, workspace, refs);
    }

    private static ChainValidationAuthorityPort authority() {
        return new ChainValidationAuthorityPort() {
            @Override
            public VerifiedCandidate verifyCandidate(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement, String receiptRef) {
                return new VerifiedCandidate("candidate-action",
                        "validation-action", receiptRef, H2, H1,
                        "candidate-1", "workspace-1", 101L, H3, H4);
            }

            @Override
            public VerifiedActionReceipt verifyActionReceipt(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement, String receiptRef) {
                return new VerifiedActionReceipt("action-" + requirement
                        .requirementId(), receiptRef, H2, H1);
            }
        };
    }

    private static String receipt(ValidationRequirement requirement) {
        return requirement.subject() == ValidationSubject.CANDIDATE
                ? "receipt-candidate" : "receipt-" + requirement.requirementId();
    }

    private static PlanStep step(
            String id, List<String> requirementIds,
            List<String> completionConditions) {
        return new PlanStep(new PlanStepId(id), "execute", "done", Set.of(),
                completionConditions, new BoundedExecutionHints(
                1, Duration.ofMinutes(1)), List.of(), false, null,
                requirementIds);
    }

    private static ChainValidationBundleRuntime.Scope scope() {
        return scope("step-2");
    }

    private static ChainValidationBundleRuntime.Scope scope(
            String finalStepId) {
        return new ChainValidationBundleRuntime.Scope(
                "task-1", "frame-1", "plan-1", "revision-1", 1,
                "instruction-1", finalStepId, "bundle-key", NOW);
    }

    private static String canonicalArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]").orElse("[]");
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord copyResult(
            ChainPersistenceRecords.CandidateStepResultRecord value,
            String instructionId, String validationId) {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                value.candidateResultId(), value.taskId(), value.eventId(),
                value.proposalId(), value.contentId(), instructionId,
                value.taskFrameId(), value.planId(), value.planRevisionId(),
                value.planRevisionNumber(), value.stepId(),
                value.activationEventId(), value.artifactId(),
                value.candidateFingerprint(), value.diffDigest(),
                value.receiptRefs(), validationId,
                value.validationRequestDigest(),
                value.validationReceiptDigest(), value.evidenceRefs(),
                value.versionFenceSha256(), value.createdAt());
    }

    private static ChainPersistenceRecords.ValidationSetRecord copySet(
            ChainPersistenceRecords.ValidationSetRecord value,
            String requestDigest,
            io.paperagent.v2.chain.ChainValidationConclusion conclusion) {
        return new ChainPersistenceRecords.ValidationSetRecord(
                value.validationId(), value.taskId(), value.eventId(),
                value.taskFrameId(), value.planId(), value.planRevisionId(),
                value.planRevisionNumber(), value.stepId(),
                value.activationEventId(), requestDigest,
                value.receiptSetDigest(), value.conclusionDigest(), conclusion,
                value.idempotencyKey(), value.createdAt());
    }

    private record Fixture(
            TaskRequirements requirements, List<PlanStep> steps,
            List<ChainValidationBundleRuntime.FormalSource> sources) {
        ChainValidationBundleRuntime.CommitCommand command(
                List<ChainValidationBundleRuntime.FormalSource> values) {
            return new ChainValidationBundleRuntime.CommitCommand(
                    scope(steps.get(steps.size() - 1).id().value()),
                    requirements, steps, values);
        }
    }

    private static final class CapturingValidationRepository
            implements ChainValidationRepository {
        private ChainPersistenceRecords.AuthorityEventRecord event;

        @Override
        public ValidationAppendResult appendValidation(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationSetRecord> value,
                List<ChainPersistenceRecords.CandidateValidationItemRecord> candidates,
                List<ChainPersistenceRecords.ActionReceiptValidationItemRecord> actions) {
            event = new ChainPersistenceRecords.AuthorityEventRecord(
                    value.event().eventId(), value.event().taskId(), 1,
                    value.event().eventType(), null,
                    value.event().sourceIdentitySha256(), NOW);
            return new ValidationAppendResult(event, value.fact(), candidates,
                    actions, false);
        }

        public Optional<ChainPersistenceRecords.ValidationSetRecord>
                findValidation(String id) { return Optional.empty(); }
        public List<ChainPersistenceRecords.CandidateValidationItemRecord>
                findCandidateItems(String id) { return List.of(); }
        public List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                findActionReceiptItems(String id) { return List.of(); }
    }

    private static final class InMemoryBundleRepository
            implements ChainValidationBundleRepository {
        private BundleAppendResult stored;
        private int appendCount;

        @Override
        public BundleAppendResult appendBundle(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationBundleRecord> value,
                List<ChainPersistenceRecords.ValidationBundleSetRecord> sets) {
            appendCount++;
            if (stored != null) {
                if (!stored.bundle().equals(value.fact())
                        || !stored.sets().equals(sets)) {
                    throw new IllegalStateException("conflicting replay");
                }
                return new BundleAppendResult(stored.event(), stored.bundle(),
                        stored.sets(), true);
            }
            var event = new ChainPersistenceRecords.AuthorityEventRecord(
                    value.event().eventId(), value.event().taskId(), 1,
                    value.event().eventType(), null,
                    value.event().sourceIdentitySha256(), NOW);
            stored = new BundleAppendResult(event, value.fact(), sets, false);
            return stored;
        }

        public Optional<ChainPersistenceRecords.ValidationBundleRecord>
                findBundle(String id) {
            return stored == null ? Optional.empty()
                    : Optional.of(stored.bundle());
        }
        public List<ChainPersistenceRecords.ValidationBundleSetRecord>
                findBundleSets(String id) {
            return stored == null ? List.of() : stored.sets();
        }
    }
}
