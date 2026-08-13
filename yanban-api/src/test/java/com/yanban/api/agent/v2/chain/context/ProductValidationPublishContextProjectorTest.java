package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationBundleRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.validation.ProductChainValidationAuthority;
import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.validation.ChainValidationBundleIdentity;
import io.paperagent.v2.chain.validation.ChainValidationIdentity;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductValidationPublishContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-09T08:00:00Z");
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);
    private static final String H3 = "3".repeat(64);

    @Test
    void nonFinalizedFailureMayCarryItsExactCandidateAndValidation() {
        var building = mock(
                ChainPersistenceRecords.ContextRevisionRecord.class);
        when(building.role()).thenReturn(ChainRole.ANSWER);
        when(building.instructionId()).thenReturn("instruction-1");
        when(building.taskFrameId()).thenReturn("frame-1");
        when(building.planId()).thenReturn("plan-1");
        when(building.planRevisionId()).thenReturn("revision-1");
        when(building.candidateArtifactId()).thenReturn(41L);
        when(building.candidateFingerprint()).thenReturn(H1);
        when(building.validationId()).thenReturn("validation-1");
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(outcome.instructionId()).thenReturn("instruction-1");
        when(outcome.taskFrameId()).thenReturn("frame-1");
        when(outcome.finalPlanId()).thenReturn("plan-1");
        when(outcome.finalPlanRevisionId()).thenReturn("revision-1");
        when(outcome.finalArtifactId()).thenReturn(41L);
        when(outcome.candidateKey()).thenReturn(H1);
        when(outcome.validationId()).thenReturn("validation-1");
        when(outcome.finalizationReadinessId()).thenReturn(null);
        when(outcome.finalizationCheckId()).thenReturn(null);
        when(outcome.publishRequirement()).thenReturn(null);
        when(outcome.publishRequirementDigest()).thenReturn(null);
        when(outcome.publishOperationId()).thenReturn(null);
        when(outcome.publishedProjectVersion()).thenReturn(null);
        when(outcome.publishedRevisionId()).thenReturn(null);
        when(outcome.publishReceiptId()).thenReturn(null);
        var candidate = mock(
                ChainPersistenceRecords.CandidateStepResultRecord.class);
        when(candidate.validationId()).thenReturn("validation-1");

        assertDoesNotThrow(() -> ProductValidationPublishIdentity
                .verifyOutcome(building, outcome, null, candidate));
    }

    @Test
    void currentStepReadsOneExactActionReceiptValidationWithoutCandidateBody() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addSet("step-1", "activation-1", requirement);

        var projection = fixture.projector.read(request(building(
                ChainRole.EXECUTOR, "step-1", "activation-1",
                set.validation.validationId(), set.validation.requestDigest(),
                set.validation.receiptSetDigest())));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        assertEquals("product-validation-publish-v3",
                projection.projectionVersion());
        Map<String, ChainContextValue> current = object(
                projection.projectionFields(),
                "validation.currentStepFormalValidation");
        Map<String, ChainContextValue> validation = object(current,
                "validation");
        assertEquals("CURRENT_STEP", text(validation, "scope"));
        var sets = array(validation, "sets");
        var items = array(object(sets.get(0)), "items");
        assertEquals("ACTION_RECEIPT", text(object(items.get(0)), "subject"));
        String projected = projection.projectionFields().toString();
        assertFalse(projected.contains("stdout"));
        assertFalse(projected.contains("stderr"));
        assertFalse(projected.contains("receiptBody"));
    }

    @Test
    void currentStepAcceptsCandidateOnlyValidationWithoutActionReceiptItems() {
        ValidationRequirement requirement = candidate("validate-candidate");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addCandidateSet(
                "step-1", "activation-1", requirement);

        var projection = fixture.projector.read(request(building(
                ChainRole.EXECUTOR, "step-1", "activation-1",
                set.validation.validationId(), set.validation.requestDigest(),
                set.validation.receiptSetDigest())));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        Map<String, ChainContextValue> current = object(
                projection.projectionFields(),
                "validation.currentStepFormalValidation");
        var items = array(object(array(object(current, "validation"),
                "sets").get(0)), "items");
        assertEquals("CANDIDATE", text(object(items.get(0)), "subject"));
    }

    @Test
    void readinessReadsCrossStepBundleAndAllTypedSetsInStableOrder() {
        ValidationRequirement first = action("validate-first");
        ValidationRequirement second = action("validate-second");
        Fixture fixture = Fixture.create(List.of(first, second), List.of(
                step("step-a", Set.of(), List.of(first)),
                step("step-b", Set.of(new PlanStepId("step-a")),
                        List.of(second))));
        SetData a = fixture.addSet("step-a", "activation-a", first);
        SetData b = fixture.addSet("step-b", "activation-b", second);
        BundleData bundle = fixture.addBundle(List.of(a, b), "step-b");
        fixture.readiness.add(readiness(bundle));
        fixture.addEvent("readiness-event");

        var projection = fixture.projector.read(request(building(
                ChainRole.PLANNER, null, null, null, null, null)));

        Map<String, ChainContextValue> validation = object(
                projection.projectionFields(), "validation.latestState");
        assertEquals("PLAN", text(validation, "scope"));
        List<ChainContextValue> sets = array(validation, "sets");
        assertEquals(2, sets.size());
        assertEquals("step-a", text(object(sets.get(0)), "stepId"));
        assertEquals("step-b", text(object(sets.get(1)), "stepId"));
        assertEquals(bundle.bundle.validationBundleId(),
                text(validation, "validationRef"));
    }

    @Test
    void explicitNotRequiredComesFromFrozenRequirementsAndWritesNoBundleView() {
        Fixture fixture = Fixture.create(List.of(), List.of(
                step("step-1", Set.of(), List.of())));
        fixture.readiness.add(notRequiredReadiness());
        fixture.addEvent("readiness-event");

        var projection = fixture.projector.read(request(building(
                ChainRole.REFLECTOR, null, null, null, null, null)));

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        assertEquals("NOT_REQUIRED", text(object(
                projection.projectionFields(),
                "validation.authoritativeValidation"), "status"));
        assertEquals("NOT_REQUIRED", text(object(
                projection.projectionFields(),
                "validation.publishRequirement"), "requirement"));
    }

    @Test
    void missingRequiredSetIsTypedBlockedInsteadOfBeingInferredNotRequired() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        fixture.addResultWithoutValidation("step-1", "activation-1");

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.EXECUTOR, "step-1", "activation-1",
                        null, null, null))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void readinessAmbiguityIsBlockedWithoutChoosingHigherEventSequence() {
        Fixture fixture = Fixture.create(List.of(), List.of(
                step("step-1", Set.of(), List.of())));
        var first = notRequiredReadiness();
        var second = new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-2", first.taskId(), "readiness-event-2",
                first.transitionId(), first.readinessScopeKey(),
                first.taskFrameId(), first.finalPlanId(),
                first.finalPlanRevisionId(), first.finalPlanRevisionNumber(),
                first.finalStepId(), first.reviewDecisionId(),
                first.acceptedSet(), first.applicabilityCutEventSequence(),
                null, ChainIdentity.NONE, first.workspaceId(),
                ChainIdentity.NONE, null, null, first.coverage(),
                first.publishRequirement(), first.publishRequirementDigest(),
                first.instructionId(), first.projectVersion(), NOW);
        fixture.readiness.add(first);
        fixture.readiness.add(second);
        fixture.addEvent("readiness-event");
        fixture.addEvent("readiness-event-2");

        assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.PLANNER, null, null, null, null, null))));
    }

    @Test
    void bundleMembershipDigestDriftIsTypedBlocked() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addSet("step-1", "activation-1", requirement);
        BundleData bundle = fixture.addBundle(List.of(set), "step-1");
        fixture.bundleMembers.put(bundle.bundle.validationBundleId(), List.of(
                new ChainPersistenceRecords.ValidationBundleSetRecord(
                        bundle.bundle.validationBundleId(), "task-1",
                        "step-1", "activation-1",
                        set.validation.validationId(), H3,
                        set.validation.receiptSetDigest(),
                        set.validation.conclusionDigest())));
        fixture.readiness.add(readiness(bundle));
        fixture.addEvent("readiness-event");

        assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.PLANNER, null, null, null, null, null))));
    }

    @Test
    void validationEventSourceDriftIsTypedBlocked() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addSet("step-1", "activation-1", requirement);
        int index = java.util.stream.IntStream.range(0, fixture.events.size())
                .filter(value -> fixture.events.get(value).eventId().equals(
                        set.validation.eventId())).findFirst().orElseThrow();
        var event = fixture.events.get(index);
        fixture.events.set(index,
                new ChainPersistenceRecords.AuthorityEventRecord(
                        event.eventId(), event.taskId(), event.eventSequence(),
                        event.eventType(), null, H3, NOW));

        assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.EXECUTOR, "step-1", "activation-1",
                        set.validation.validationId(),
                        set.validation.requestDigest(),
                        set.validation.receiptSetDigest()))));
    }

    @Test
    void bundleRootIdDifferentFromReadinessIsTypedBlocked() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addSet("step-1", "activation-1", requirement);
        BundleData bundle = fixture.addBundle(List.of(set), "step-1");
        var value = bundle.bundle;
        var wrongRoot = new ChainPersistenceRecords.ValidationBundleRecord(
                "validation-bundle.wrong-root", value.taskId(),
                value.eventId(), value.taskFrameId(), value.planId(),
                value.planRevisionId(), value.planRevisionNumber(),
                value.instructionId(), value.finalStepId(),
                value.requestDigest(), value.receiptSetDigest(),
                value.conclusionDigest(), value.conclusion(),
                value.idempotencyKey(), value.createdAt());
        fixture.bundleValues.put(value.validationBundleId(), wrongRoot);
        fixture.readiness.add(readiness(bundle));
        fixture.addEvent("readiness-event");

        assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.PLANNER, null, null, null, null, null))));
    }

    @Test
    void extraBundleMemberForStepWithNoRequirementsIsTypedBlocked() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-required", Set.of(), List.of(requirement)),
                step("step-empty", Set.of(new PlanStepId("step-required")),
                        List.of())));
        SetData set = fixture.addSet(
                "step-required", "activation-required", requirement);
        BundleData bundle = fixture.addBundle(List.of(set), "step-empty");
        List<ChainPersistenceRecords.ValidationBundleSetRecord> members =
                new ArrayList<>(bundle.members);
        members.add(new ChainPersistenceRecords.ValidationBundleSetRecord(
                bundle.bundle.validationBundleId(), "task-1", "step-empty",
                "activation-empty", set.validation.validationId(),
                set.validation.requestDigest(),
                set.validation.receiptSetDigest(),
                set.validation.conclusionDigest()));
        fixture.bundleMembers.put(
                bundle.bundle.validationBundleId(), List.copyOf(members));
        fixture.readiness.add(readiness(bundle));
        fixture.addEvent("readiness-event");

        assertThrows(ChainContextException.class,
                () -> fixture.projector.read(request(building(
                        ChainRole.PLANNER, null, null, null, null, null))));
    }

    @Test
    void answerReadsOriginalTypedReceiptOutputFromExactTerminalBundle() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addSet(
                "step-1", "activation-1", requirement);
        BundleData bundle = fixture.addBundle(List.of(set), "step-1");
        fixture.addTerminal(bundle);
        var item = set.actions().get(0);
        when(fixture.receiptBodies.exactReceiptBody(
                set.validation(), item.actionId(), item.receiptId(),
                item.receiptPayloadSha256())).thenReturn(
                new ExecutionReceipt(new ReceiptId(item.receiptId()),
                        new ToolCallId(item.actionId()),
                        ReceiptStatus.SUCCESS, NOW, NOW.plusSeconds(1),
                        Optional.of(0), Optional.empty(),
                        OutputCapture.inline("compiled and ran", false),
                        OutputCapture.inline("warning", true), List.of(),
                        Optional.empty(), List.of()));

        var projection = fixture.projector.read(request(building(
                ChainRole.ANSWER, "step-1", "activation-1",
                bundle.bundle().validationBundleId(),
                bundle.bundle().requestDigest(),
                bundle.bundle().receiptSetDigest())));

        assertEquals("product-validation-publish-v3",
                projection.projectionVersion());
        String finalValidation = projection.projectionFields().get(
                "validation.finalValidation").toString();
        org.junit.jupiter.api.Assertions.assertTrue(
                finalValidation.contains("compiled and ran"));
        org.junit.jupiter.api.Assertions.assertTrue(
                finalValidation.contains("warning"));
        var terminal = object(projection.projectionFields(),
                "validation.finalValidation");
        var setView = object(array(terminal, "sets").get(0));
        var receiptView = object(array(setView, "receiptBodies").get(0));
        var receipt = object(receiptView, "receipt");
        var stderr = object(receipt, "stderr");
        assertEquals(true, ((ChainContextValue.BooleanValue)
                stderr.get("truncated")).value());
    }

    @Test
    void publicSummaryReadsTheSameExactTerminalValidation() {
        ValidationRequirement requirement = action("validate-action");
        Fixture fixture = Fixture.create(List.of(requirement), List.of(
                step("step-1", Set.of(), List.of(requirement))));
        SetData set = fixture.addSet("step-1", "activation-1", requirement);
        BundleData bundle = fixture.addBundle(List.of(set), "step-1");
        fixture.addTerminal(bundle);
        var item = set.actions().get(0);
        when(fixture.receiptBodies.exactReceiptBody(
                set.validation(), item.actionId(), item.receiptId(),
                item.receiptPayloadSha256())).thenReturn(
                new ExecutionReceipt(new ReceiptId(item.receiptId()),
                        new ToolCallId(item.actionId()),
                        ReceiptStatus.SUCCESS, NOW, NOW.plusSeconds(1),
                        Optional.of(0), Optional.empty(),
                        OutputCapture.inline("validated", false),
                        OutputCapture.inline("", false), List.of(),
                        Optional.empty(), List.of()));

        var summary = fixture.projector.terminalValidation(
                task(), fixture.outcomes.get(0), null);

        assertEquals(bundle.bundle().validationBundleId(),
                summary.validationId());
        assertEquals("PASSED", summary.status());
        assertEquals(bundle.bundle().requestDigest(),
                summary.requestDigest());
        assertEquals(bundle.bundle().receiptSetDigest(),
                summary.receiptDigest());
        assertEquals(1, summary.receipts().size());
        assertEquals("ACTION_RECEIPT", summary.receipts().get(0).subject());
        assertEquals(set.actions().get(0).receiptId(),
                summary.receipts().get(0).receiptId());
    }

    private static final class Fixture {
        final ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        final ProductChainWorkflowRepositoryAdapter workflow = mock(
                ProductChainWorkflowRepositoryAdapter.class);
        final ChainFinalizationRepository finalization = mock(
                ChainFinalizationRepository.class);
        final ProductChainValidationRepositoryAdapter validations = mock(
                ProductChainValidationRepositoryAdapter.class);
        final ProductChainValidationBundleRepositoryAdapter bundles = mock(
                ProductChainValidationBundleRepositoryAdapter.class);
        final ProductPlanBootstrapRepositoryAdapter bootstraps = mock(
                ProductPlanBootstrapRepositoryAdapter.class);
        final ProductChainStepAuthorityAdapter steps = mock(
                ProductChainStepAuthorityAdapter.class);
        final ProductChainValidationAuthority receiptBodies = mock(
                ProductChainValidationAuthority.class);
        final ProductChainTerminalOutcomeAuthority terminalOutcomes = mock(
                ProductChainTerminalOutcomeAuthority.class);
        final ProductChainPublishAuthoritySource publishes = mock(
                ProductChainPublishAuthoritySource.class);
        final List<ChainPersistenceRecords.AuthorityEventRecord> events =
                new ArrayList<>();
        final List<ChainPersistenceRecords.CandidateStepResultRecord> results =
                new ArrayList<>();
        final List<ChainPersistenceRecords.FinalizationReadinessRecord>
                readiness = new ArrayList<>();
        final List<ChainPersistenceRecords.FinalizationCheckRecord> checks =
                new ArrayList<>();
        final List<ChainPersistenceRecords.TaskOutcomeRecord> outcomes =
                new ArrayList<>();
        final Map<String, SetData> sets = new HashMap<>();
        final Map<String, ChainPersistenceRecords.ValidationBundleRecord>
                bundleValues = new HashMap<>();
        final Map<String, List<ChainPersistenceRecords
                .ValidationBundleSetRecord>> bundleMembers = new HashMap<>();
        final ProductValidationPublishContextProjector projector;
        final TaskRequirements requirements;
        final PlanRevision revision;

        private Fixture(TaskRequirements requirements, PlanRevision revision) {
            this.requirements = requirements;
            this.revision = revision;
            when(foundations.findTask("task-1")).thenReturn(Optional.of(task()));
            when(foundations.highestAuthorityEventSequence("task-1"))
                    .thenAnswer(call -> (long) events.size());
            when(foundations.findAuthorityEvents(
                    org.mockito.ArgumentMatchers.eq("task-1"),
                    org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(call -> List.copyOf(events));
            when(workflow.findCandidateStepResults("task-1"))
                    .thenAnswer(call -> List.copyOf(results));
            when(workflow.findWorkspaceCandidates("task-1"))
                    .thenReturn(List.of());
            when(finalization.findReadiness("task-1"))
                    .thenAnswer(call -> List.copyOf(readiness));
            when(finalization.findTaskOutcome("task-1"))
                    .thenAnswer(call -> outcomes.stream().findFirst());
            when(finalization.findFinalizationChecks(
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> List.copyOf(checks));
            when(validations.findValidation(
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> Optional.ofNullable(
                            sets.get(call.getArgument(0))).map(
                            value -> value.validation));
            when(validations.findCandidateItems(
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> Optional.ofNullable(
                            sets.get(call.getArgument(0))).map(
                            value -> value.candidates).orElse(List.of()));
            when(validations.findActionReceiptItems(
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> Optional.ofNullable(
                            sets.get(call.getArgument(0))).map(
                            value -> value.actions).orElse(List.of()));
            when(bundles.findBundle(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> Optional.ofNullable(
                            bundleValues.get(call.getArgument(0))));
            when(bundles.findBundleSets(
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> bundleMembers.getOrDefault(
                            call.getArgument(0), List.of()));
            when(steps.findPlanRevision("task-1", "revision-1"))
                    .thenReturn(Optional.of(revision));
            bootstrap(requirements);
            projector = new ProductValidationPublishContextProjector(
                    foundations, workflow, finalization, validations, bundles,
                    bootstraps, steps, receiptBodies, terminalOutcomes,
                    publishes);
        }

        static Fixture create(
                List<ValidationRequirement> requirements,
                List<PlanStep> steps) {
            return new Fixture(TaskRequirements.explicit(
                    requirements, PublishRequirement.NOT_REQUIRED),
                    new PlanRevision(new PlanRevisionId("revision-1"),
                            new TaskFrameId("frame-1"), 1, Optional.empty(),
                            "initial", NOW, steps, Map.of()));
        }

        private void bootstrap(TaskRequirements taskRequirements) {
            PersistedPlanBootstrap stored = mock(PersistedPlanBootstrap.class);
            TaskFrame frame = mock(TaskFrame.class);
            Plan plan = mock(Plan.class);
            when(stored.taskFrame()).thenReturn(frame);
            when(stored.plan()).thenReturn(plan);
            when(frame.id()).thenReturn(new TaskFrameId("frame-1"));
            when(frame.requirements()).thenReturn(taskRequirements);
            when(plan.id()).thenReturn(new PlanId("plan-1"));
            when(bootstraps.find(new PlanId("plan-1")))
                    .thenReturn(Optional.of(stored));
        }

        SetData addSet(
                String stepId, String activation,
                ValidationRequirement requirement) {
            String requirementDigest = ChainValidationIdentity
                    .requirementDigest(requirement);
            var placeholder = new ChainPersistenceRecords
                    .ActionReceiptValidationItemRecord(
                    "placeholder", requirement.requirementId(), "task-1",
                    requirementDigest, "action-" + stepId,
                    "receipt-" + stepId, H1, H2,
                    ChainValidationConclusion.PASSED);
            var scope = new ChainValidationIdentity.SetScope(
                    "task-1", "frame-1", "plan-1", "revision-1", 1,
                    stepId, activation);
            String request = ChainValidationIdentity.requestDigest(scope,
                    List.of(new ChainValidationIdentity.RequestIdentity(
                            requirement.requirementId(), requirementDigest,
                            requirement.subject(), ChainValidationIdentity
                            .actionSubject(placeholder))));
            String receipt = ChainValidationIdentity.receiptSetDigest(List.of(
                    new ChainValidationIdentity.ReceiptIdentity(
                            requirement.requirementId(),
                            placeholder.receiptId(), H1)));
            String conclusion = ChainValidationIdentity.conclusionDigest(
                    List.of(new ChainValidationIdentity.ConclusionIdentity(
                            requirement.requirementId(),
                            ChainValidationConclusion.PASSED)));
            String id = "validation." + sha256("task-1\0revision-1\0"
                    + stepId + "\0" + activation + "\0" + request + "\0"
                    + receipt);
            var item = new ChainPersistenceRecords
                    .ActionReceiptValidationItemRecord(
                    id, requirement.requirementId(), "task-1",
                    requirementDigest, placeholder.actionId(),
                    placeholder.receiptId(), H1, H2,
                    ChainValidationConclusion.PASSED);
            var validation = new ChainPersistenceRecords.ValidationSetRecord(
                    id, "task-1", "validation-event-" + stepId,
                    "frame-1", "plan-1", "revision-1", 1, stepId,
                    activation, request, receipt, conclusion,
                    ChainValidationConclusion.PASSED, "key-" + stepId, NOW);
            SetData data = new SetData(validation, List.of(), List.of(item));
            sets.put(id, data);
            addValidationEvent(validation);
            var result = result(stepId, activation, id, request, receipt,
                    item.receiptId());
            results.add(result);
            addEvent(result.eventId());
            return data;
        }

        SetData addCandidateSet(
                String stepId, String activation,
                ValidationRequirement requirement) {
            String requirementDigest = ChainValidationIdentity
                    .requirementDigest(requirement);
            var placeholder = new ChainPersistenceRecords
                    .CandidateValidationItemRecord(
                    "placeholder", requirement.requirementId(), "task-1",
                    requirementDigest, "candidate-action-" + stepId,
                    "validation-action-" + stepId, "receipt-" + stepId,
                    H1, H2, "candidate-1", "workspace-1", 10L, H3, H3,
                    ChainValidationConclusion.PASSED);
            var scope = new ChainValidationIdentity.SetScope(
                    "task-1", "frame-1", "plan-1", "revision-1", 1,
                    stepId, activation);
            String request = ChainValidationIdentity.requestDigest(scope,
                    List.of(new ChainValidationIdentity.RequestIdentity(
                            requirement.requirementId(), requirementDigest,
                            requirement.subject(), ChainValidationIdentity
                            .candidateSubject(placeholder))));
            String receipt = ChainValidationIdentity.receiptSetDigest(List.of(
                    new ChainValidationIdentity.ReceiptIdentity(
                            requirement.requirementId(),
                            placeholder.receiptId(), H1)));
            String conclusion = ChainValidationIdentity.conclusionDigest(
                    List.of(new ChainValidationIdentity.ConclusionIdentity(
                            requirement.requirementId(),
                            ChainValidationConclusion.PASSED)));
            String id = "validation." + sha256("task-1\0revision-1\0"
                    + stepId + "\0" + activation + "\0" + request + "\0"
                    + receipt);
            var item = new ChainPersistenceRecords
                    .CandidateValidationItemRecord(
                    id, requirement.requirementId(), "task-1",
                    requirementDigest, placeholder.candidateActionId(),
                    placeholder.validationActionId(), placeholder.receiptId(),
                    H1, H2, placeholder.workspaceCandidateId(),
                    placeholder.workspaceId(), placeholder.artifactId(),
                    placeholder.candidateFingerprint(),
                    placeholder.baseProjectVersion(),
                    ChainValidationConclusion.PASSED);
            var validation = new ChainPersistenceRecords.ValidationSetRecord(
                    id, "task-1", "validation-event-" + stepId,
                    "frame-1", "plan-1", "revision-1", 1, stepId,
                    activation, request, receipt, conclusion,
                    ChainValidationConclusion.PASSED, "key-" + stepId, NOW);
            SetData data = new SetData(validation, List.of(item), List.of());
            sets.put(id, data);
            addValidationEvent(validation);
            var result = result(stepId, activation, id, request, receipt,
                    item.receiptId());
            results.add(result);
            addEvent(result.eventId());
            return data;
        }

        void addResultWithoutValidation(String stepId, String activation) {
            var result = result(stepId, activation, null, null, null,
                    "receipt-" + stepId);
            results.add(result);
            addEvent(result.eventId());
        }

        BundleData addBundle(List<SetData> values, String finalStepId) {
            List<ChainPersistenceRecords.ValidationBundleSetRecord> members =
                    values.stream().map(value ->
                    new ChainPersistenceRecords.ValidationBundleSetRecord(
                            "placeholder", "task-1",
                            value.validation.stepId(),
                            value.validation.activationEventId(),
                            value.validation.validationId(),
                            value.validation.requestDigest(),
                            value.validation.receiptSetDigest(),
                            value.validation.conclusionDigest())).toList();
            var scope = new ChainValidationBundleIdentity.Scope(
                    "task-1", "frame-1", "plan-1", "revision-1", 1,
                    "instruction-1", finalStepId);
            var aggregate = ChainValidationBundleIdentity.aggregate(
                    scope, members.stream().map(value ->
                    new ChainValidationBundleIdentity.Member(
                            value.stepId(), value.validationId(),
                            value.validationRequestDigest(),
                            value.validationReceiptSetDigest(),
                            value.validationConclusionDigest())).toList());
            String request = aggregate.requestDigest();
            String receipt = aggregate.receiptSetDigest();
            String conclusion = aggregate.conclusionDigest();
            String id = ChainValidationBundleIdentity.bundleId(
                    scope, aggregate);
            var bundle = new ChainPersistenceRecords.ValidationBundleRecord(
                    id, "task-1", "bundle-event", "frame-1", "plan-1",
                    "revision-1", 1, "instruction-1", finalStepId,
                    request, receipt, conclusion,
                    ChainValidationConclusion.PASSED, "bundle-key", NOW);
            List<ChainPersistenceRecords.ValidationBundleSetRecord> exact =
                    members.stream().map(value ->
                    new ChainPersistenceRecords.ValidationBundleSetRecord(
                            id, value.taskId(), value.stepId(),
                            value.activationEventId(), value.validationId(),
                            value.validationRequestDigest(),
                            value.validationReceiptSetDigest(),
                            value.validationConclusionDigest())).toList();
            bundleValues.put(id, bundle);
            bundleMembers.put(id, exact);
            addBundleEvent(bundle);
            return new BundleData(bundle, exact);
        }

        void addValidationEvent(
                ChainPersistenceRecords.ValidationSetRecord value) {
            addEvent(value.eventId(), "VALIDATION", sha256(
                    value.validationId() + "\0" + value.requestDigest()
                            + "\0" + value.receiptSetDigest() + "\0"
                            + value.conclusionDigest()));
        }

        void addBundleEvent(
                ChainPersistenceRecords.ValidationBundleRecord value) {
            addEvent(value.eventId(), "VALIDATION_BUNDLE",
                    ChainValidationBundleIdentity.eventSourceIdentity(
                            value.validationBundleId(),
                            new ChainValidationBundleIdentity.Aggregate(
                                    value.requestDigest(),
                                    value.receiptSetDigest(),
                                    value.conclusionDigest())));
        }

        void addEvent(String id) {
            addEvent(id, "TEST", H1);
        }

        void addEvent(String id, String type, String source) {
            if (events.stream().anyMatch(value -> value.eventId().equals(id))) {
                return;
            }
            events.add(new ChainPersistenceRecords.AuthorityEventRecord(
                    id, "task-1", events.size() + 1L, type, null, source, NOW));
        }

        void addTerminal(BundleData bundle) {
            var ready = readiness(bundle);
            readiness.add(ready);
            var check = new ChainPersistenceRecords.FinalizationCheckRecord(
                    "check-1", "task-1", "check-event",
                    ready.readinessId(), "transition-finalization", 1,
                    ready.taskFrameId(), ready.finalPlanRevisionId(), H1,
                    ready.candidateKey(), ready.workspaceId(),
                    ready.validationId(), ready.validationRequestDigest(),
                    ready.validationReceiptDigest(),
                    ready.publishRequirementDigest(), ready.instructionId(),
                    ready.projectVersion(), H1, H2, H3,
                    io.paperagent.v2.chain.ChainFinalization.Outcome.PASSED,
                    null,
                    io.paperagent.v2.chain.ChainFinalization
                            .FailureHandling.NONE,
                    io.paperagent.v2.chain.ChainRuntimePolicy.V1
                            .policyVersion(), NOW);
            checks.add(check);
            outcomes.add(new ChainPersistenceRecords.TaskOutcomeRecord(
                    "outcome-1", "task-1", "outcome-event", "command-1",
                    io.paperagent.v2.chain.ChainTaskOutcomeStatus.COMPLETED,
                    ready.instructionId(), ready.taskFrameId(),
                    ready.finalPlanId(), ready.finalPlanRevisionId(),
                    canonical("{}"), ready.acceptedSet(), null,
                    ready.candidateKey(), ready.readinessId(),
                    check.finalizationCheckId(), ready.validationId(),
                    ready.validationRequestDigest(),
                    ready.validationReceiptDigest(),
                    ready.publishRequirement(),
                    ready.publishRequirementDigest(), null, null, null, null,
                    canonical("[]"), canonical("[]"), canonical("[]"),
                    null, null, "review-1", NOW));
            when(terminalOutcomes.requireExact(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(outcomes.get(0))))
                    .thenReturn(new ProductChainTerminalOutcomeAuthority
                            .TerminalFacts(ready, check, ready.finalStepId(),
                            "activation-1", ready.projectVersion(),
                            new ProductChainTerminalOutcomeAuthority
                                    .ValidationIdentity(
                                    ready.validationId(),
                                    ready.validationRequestDigest(),
                                    ready.validationReceiptDigest())));
            addEvent(ready.eventId());
            addEvent(check.eventId());
            addEvent("outcome-event");
        }
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord result(
            String stepId, String activation, String validationId,
            String request, String receipt, String receiptId) {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                "result-" + stepId, "task-1", "result-event-" + stepId,
                "proposal-" + stepId, "content-" + stepId,
                "instruction-1", "frame-1", "plan-1", "revision-1", 1,
                stepId, activation, null, null, null,
                canonical("[\"" + receiptId + "\"]"), validationId,
                request, receipt, canonical("[]"), H3, NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord readiness(
            BundleData value) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "readiness-event",
                "transition-readiness", H1, "frame-1", "plan-1",
                "revision-1", 1, value.bundle.finalStepId(), "review-1",
                canonical("[]"), 0, null, ChainIdentity.NONE,
                ChainIdentity.NONE, value.bundle.validationBundleId(),
                value.bundle.requestDigest(), value.bundle.receiptSetDigest(),
                canonical("{}"), ChainPublishRequirement.NOT_REQUIRED, H2,
                "instruction-1", H3, NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            notRequiredReadiness() {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "readiness-event",
                "transition-readiness", H1, "frame-1", "plan-1",
                "revision-1", 1, "step-1", "review-1", canonical("[]"),
                0, null, ChainIdentity.NONE, ChainIdentity.NONE,
                ChainIdentity.NONE, null, null, canonical("{}"),
                ChainPublishRequirement.NOT_REQUIRED, H2,
                "instruction-1", H3, NOW);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                1, 2, 3, null, "request-1", H1,
                10L, H3, 100, NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String stepId, String activationId,
            String validationId, String request, String receipt) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, role,
                role == ChainRole.PLANNER ? ChainWorkState.PLANNING
                        : role == ChainRole.ANSWER
                        ? ChainWorkState.DELIVERING
                        : ChainWorkState.EXECUTING,
                "TEST", "instruction-1", "frame-1", "plan-1",
                "revision-1", 1L, stepId, activationId,
                10L, H3,
                ChainIdentity.NONE, null, null,
                validationId, request, receipt, "projectors-v1", "pages-v1",
                io.paperagent.v2.chain.ChainRuntimePolicy.V1.policyVersion(),
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static PlanStep step(
            String id, Set<PlanStepId> dependencies,
            List<ValidationRequirement> requirements) {
        return new PlanStep(new PlanStepId(id), "execute", "done",
                dependencies,
                requirements.isEmpty() ? List.of("done") : requirements
                        .stream().map(ValidationRequirement
                                ::completionCondition).toList(),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)),
                List.of(), false, null, requirements.stream()
                .map(ValidationRequirement::requirementId).toList());
    }

    private static ValidationRequirement action(String id) {
        return new ValidationRequirement(
                id, ValidationSubject.ACTION_RECEIPT, id + " complete");
    }

    private static ValidationRequirement candidate(String id) {
        return new ValidationRequirement(
                id, ValidationSubject.CANDIDATE, id + " complete");
    }

    private static ChainContextProjectionRequest request(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static Map<String, ChainContextValue> object(
            Map<String, ChainContextValue> values, String key) {
        return object(values.get(key));
    }

    private static Map<String, ChainContextValue> object(
            ChainContextValue value) {
        return ((ChainContextValue.ObjectValue) value).values();
    }

    private static List<ChainContextValue> array(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.ArrayValue) values.get(key)).values();
    }

    private static String text(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(
            String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static String sha256(String value) {
        return ProductChainContractProjectionCodec.sha256(value);
    }

    private record SetData(
            ChainPersistenceRecords.ValidationSetRecord validation,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidates,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actions) {
    }

    private record BundleData(
            ChainPersistenceRecords.ValidationBundleRecord bundle,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> members) {
    }
}
