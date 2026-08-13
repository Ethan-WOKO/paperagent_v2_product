package com.yanban.api.agent.v2.chain.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.V2SandboxEffectExecutionComposer;
import com.yanban.api.agent.sandbox.V2SandboxEffectExecutionOutcome;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionCommand;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionOutcome;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionCommand;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionOutcome;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class ProductChainEffectAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String FENCE = "b".repeat(64);

    @Test
    void freshPrepareDispatchesExactlyOnce() {
        Fixture fixture = fixture();
        stubFreshEffect(fixture);
        when(fixture.projectEffects.executeChain(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AuthenticatedProjectEffectExecutionOutcome(
                        fixture.result, false));

        ChainEffectRuntime.PreparedEffect prepared =
                fixture.authority.prepare(fixture.action);
        ChainEffectRuntime.EffectReconciliation dispatched =
                fixture.authority.dispatch(prepared);

        assertEquals(ChainEffectRuntime.EffectStatus.SUCCEEDED,
                dispatched.status());
        assertNotNull(prepared.dispatchPermit());
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.dispatch(prepared));
        verify(fixture.projectEffects).executeChain(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<AuthenticatedProjectEffectExecutionCommand> command =
                ArgumentCaptor.forClass(
                        AuthenticatedProjectEffectExecutionCommand.class);
        verify(fixture.projectEffects).executeChain(eq(7L), eq(42L),
                command.capture());
        assertEquals(fixture.workspaceAuthority,
                command.getValue().chainAuthority());
        verify(fixture.projectEffects, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void laterStepAcceptsItsFrozenRevisionAfterRecoveryPlanAdvances() {
        Fixture fixture = fixture();
        PlanRevision advancedRevision = mock(PlanRevision.class);
        when(advancedRevision.id()).thenReturn(
                new PlanRevisionId("revision.completed-prior-step"));
        when(fixture.recoveryPlan.latestRevision()).thenReturn(
                advancedRevision);
        stubFreshEffect(fixture);

        ChainEffectRuntime.PreparedEffect prepared =
                fixture.authority.prepare(fixture.action);

        assertNotNull(prepared.dispatchPermit());
        verify(fixture.intents).persist(any());
    }

    @Test
    void pathlessProjectSearchUsesItsFormalReadScope() {
        Fixture fixture = fixture(
                "project.search",
                "{\"query\":\"needle\",\"maxResults\":5}",
                new ObjectValue(Map.of(
                        "query", new TextValue("needle"),
                        "maxResults", new NumberValue(
                        BigDecimal.valueOf(5)))),
                List.of("paper.md"), List.of());
        stubFreshEffect(fixture);
        when(fixture.projectEffects.executeChain(eq(7L), eq(42L), any()))
                .thenReturn(new AuthenticatedProjectEffectExecutionOutcome(
                        fixture.result, false));

        fixture.authority.dispatch(fixture.authority.prepare(fixture.action));

        verify(fixture.workspaceAuthorities).create(
                any(), eq(fixture.action), eq(List.of("paper.md")),
                eq(List.of()));
        ArgumentCaptor<AuthenticatedProjectEffectExecutionCommand> command =
                ArgumentCaptor.forClass(
                        AuthenticatedProjectEffectExecutionCommand.class);
        verify(fixture.projectEffects).executeChain(eq(7L), eq(42L),
                command.capture());
        assertEquals(List.of("paper.md"),
                command.getValue().chainAuthority().readScopes());
    }

    @Test
    void literatureTargetRoutesWithoutWorkspaceAuthority() {
        Fixture fixture = fixture(
                "literature.search", "{\"query\":\"agents\"}",
                new ObjectValue(Map.of(
                        "query", new TextValue("agents"))),
                List.of(), List.of());
        stubFreshEffect(fixture);
        when(fixture.literatureEffects.executeChain(
                eq(7L), eq(42L), any()))
                .thenReturn(
                        new AuthenticatedLiteratureSearchEffectExecutionOutcome(
                                fixture.result, false));

        ChainEffectRuntime.EffectReconciliation result =
                fixture.authority.dispatch(
                        fixture.authority.prepare(fixture.action));

        assertEquals(ChainEffectRuntime.EffectStatus.SUCCEEDED,
                result.status());
        ArgumentCaptor<AuthenticatedLiteratureSearchEffectExecutionCommand>
                command = ArgumentCaptor.forClass(
                AuthenticatedLiteratureSearchEffectExecutionCommand.class);
        verify(fixture.literatureEffects).executeChain(
                eq(7L), eq(42L), command.capture());
        assertEquals(new ToolCallId("action.1"),
                command.getValue().toolCallId());
        verify(fixture.workspaceAuthorities, never()).create(
                any(), any(), any(), any());
        verify(fixture.projectEffects, never()).executeChain(
                any(), any(), any());
        verify(fixture.sandboxEffects, never()).executeChain(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void sandboxTargetRoutesWithExactReadOnlyWorkspaceAuthority() {
        ObjectValue arguments = new ObjectValue(Map.of(
                "paths", new ListValue(List.of(
                        new TextValue("Main.java"))),
                "argv", new ListValue(List.of(
                        new TextValue("java"),
                        new TextValue("Main.java")))));
        Fixture fixture = fixture(
                "sandbox.execute",
                "{\"paths\":[\"Main.java\"],"
                        + "\"argv\":[\"java\",\"Main.java\"]}",
                arguments, List.of("Main.java"), List.of());
        stubFreshEffect(fixture);
        when(fixture.sandboxEffects.executeChain(
                eq(7L), eq(42L), eq(new PlanId("plan.1")),
                eq(new ToolCallId("action.1")), any(),
                eq(fixture.workspaceAuthority)))
                .thenReturn(new V2SandboxEffectExecutionOutcome(
                        fixture.result, false));

        ChainEffectRuntime.EffectReconciliation result =
                fixture.authority.dispatch(
                        fixture.authority.prepare(fixture.action));

        assertEquals(ChainEffectRuntime.EffectStatus.SUCCEEDED,
                result.status());
        verify(fixture.sandboxEffects).executeChain(
                eq(7L), eq(42L), eq(new PlanId("plan.1")),
                eq(new ToolCallId("action.1")), any(),
                eq(fixture.workspaceAuthority));
        verify(fixture.projectEffects, never()).executeChain(
                any(), any(), any());
        verify(fixture.literatureEffects, never()).executeChain(
                any(), any(), any());
    }

    @Test
    void intentOnlyRestartIsUnknownAndCannotDispatch() {
        Fixture fixture = fixture();
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(notFound("effectResult"));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(PersistenceResult.found(fixture.intent));

        ChainEffectRuntime.EffectReconciliation reconciled =
                fixture.authority.reconcile(fixture.action);

        assertEquals(ChainEffectRuntime.EffectStatus.UNKNOWN,
                reconciled.status());
        assertEquals("effect-intent.action.1", reconciled.uncertaintyRef());
        assertEquals(null, reconciled.errorRef());
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));
        assertThrows(IllegalStateException.class, () ->
                fixture.authority.dispatch(new ChainEffectRuntime.PreparedEffect(
                        "effect-intent.action.1", "action.1", "key.1",
                        FENCE, "lost-process-permit")));
        verify(fixture.projectEffects, never()).executeChain(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finalResultReplayNeverExecutesComposer() {
        Fixture fixture = fixture();
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(PersistenceResult.found(fixture.result));

        ChainEffectRuntime.EffectReconciliation reconciled =
                fixture.authority.reconcile(fixture.action);

        assertEquals(ChainEffectRuntime.EffectStatus.SUCCEEDED,
                reconciled.status());
        assertEquals("receipt.1", reconciled.receiptRef());
        verify(fixture.projectEffects, never()).executeChain(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void replannedActionUsesFrozenTaskFramePermissionAuthority() {
        Fixture fixture = fixture();
        stubFreshEffect(fixture);

        ChainEffectRuntime.PreparedEffect prepared =
                fixture.authority.prepare(fixture.action);

        assertNotNull(prepared);
        verify(fixture.intents).persist(any());
        verify(fixture.bootstrapPlan, never()).latestRevision();
    }

    @Test
    void prepareRejectsPathsOutsideScopesAndUnfrozenPermission() {
        Fixture fixture = fixture();
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(notFound("effectResult"));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(notFound("effectIntent"));

        ChainPersistenceRecords.ModelProposalRecord outsideScope =
                proposal(fixture.proposal,
                        "{\"completeArguments\":\"{\\\"path\\\":\\\"secret.md\\\"}\","
                                + "\"toolId\":\"project.read\","
                                + "\"requiredPermission\":\"permission.project-read\","
                                + "\"readScopes\":[\"paper.md\"],"
                                + "\"writeScopes\":[]}",
                        "[\"permission.project-read\",\"project.read\"]");
        when(fixture.models.findProposal("proposal.1"))
                .thenReturn(Optional.of(outsideScope));
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        ChainPersistenceRecords.ModelProposalRecord missingPermission =
                proposal(fixture.proposal,
                        fixture.proposal.payload().json(),
                        "[\"project.read\"]");
        when(fixture.models.findProposal("proposal.1"))
                .thenReturn(Optional.of(missingPermission));
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        ChainPersistenceRecords.ModelProposalRecord duplicateScope =
                proposal(fixture.proposal,
                        "{\"completeArguments\":\"{\\\"path\\\":\\\"paper.md\\\"}\","
                                + "\"toolId\":\"project.read\","
                                + "\"requiredPermission\":\"permission.project-read\","
                                + "\"readScopes\":[\"paper.md\",\"paper.md\"],"
                                + "\"writeScopes\":[]}",
                        "[\"permission.project-read\",\"project.read\"]");
        when(fixture.models.findProposal("proposal.1"))
                .thenReturn(Optional.of(duplicateScope));
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        ChainPersistenceRecords.ModelProposalRecord nonCanonicalScope =
                proposal(fixture.proposal,
                        "{\"completeArguments\":\"{\\\"path\\\":\\\"paper.md\\\"}\","
                                + "\"toolId\":\"project.read\","
                                + "\"requiredPermission\":\"permission.project-read\","
                                + "\"readScopes\":[\"./paper.md\"],"
                                + "\"writeScopes\":[]}",
                        "[\"permission.project-read\",\"project.read\"]");
        when(fixture.models.findProposal("proposal.1"))
                .thenReturn(Optional.of(nonCanonicalScope));
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        String candidateArguments = "{\"operation\":\"compose\","
                + "\"paths\":[\"paper.md\"],"
                + "\"replacements\":[{\"path\":\"paper.md\","
                + "\"text\":\"new\"}]}";
        ChainPersistenceRecords.ModelProposalRecord outsideWriteScope =
                proposal(fixture.proposal,
                        "{\"completeArguments\":"
                                + jsonString(candidateArguments) + ","
                                + "\"toolId\":\"project.candidate.compose\","
                                + "\"requiredPermission\":\"permission.project-write\","
                                + "\"readScopes\":[\"paper.md\"],"
                                + "\"writeScopes\":[\"other.md\"]}",
                        "[\"permission.project-write\","
                                + "\"project.candidate.compose\"]");
        when(fixture.models.findProposal("proposal.1"))
                .thenReturn(Optional.of(outsideWriteScope));
        when(fixture.executionProfile.capabilities()).thenReturn(Set.of(
                Capability.READ_PROJECT, Capability.WRITE_WORKSPACE));
        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));
        verify(fixture.intents, never()).persist(any());
    }

    @Test
    void prepareRejectsSelfReportedLowPermissionForWriteTool() {
        String arguments = "{\"operation\":\"compose\","
                + "\"paths\":[\"paper.md\"],"
                + "\"replacements\":[{\"path\":\"paper.md\","
                + "\"text\":\"new\"}]}";
        Fixture fixture = fixture(
                "project.candidate.compose", arguments,
                new ObjectValue(Map.of()),
                List.of("paper.md"), List.of("paper.md"));
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(notFound("effectResult"));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(notFound("effectIntent"));
        ChainPersistenceRecords.ModelProposalRecord forged = proposal(
                fixture.proposal,
                "{\"completeArguments\":" + jsonString(arguments) + ","
                        + "\"toolId\":\"project.candidate.compose\","
                        + "\"requiredPermission\":\"permission.project-read\","
                        + "\"readScopes\":[\"paper.md\"],"
                        + "\"writeScopes\":[\"paper.md\"]}",
                "[\"permission.project-read\","
                        + "\"project.candidate.compose\"]");
        when(fixture.models.findProposal("proposal.1"))
                .thenReturn(Optional.of(forged));

        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        verify(fixture.intents, never()).persist(any());
    }

    @Test
    void prepareRejectsCapabilitiesAbsentFromFormalTaskFrame() {
        Fixture fixture = fixture(
                "sandbox.execute",
                "{\"paths\":[\"Main.java\"],"
                        + "\"argv\":[\"java\",\"Main.java\"]}",
                new ObjectValue(Map.of()),
                List.of("Main.java"), List.of());
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(notFound("effectResult"));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(notFound("effectIntent"));
        when(fixture.executionProfile.capabilities())
                .thenReturn(Set.of(Capability.READ_PROJECT));

        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        verify(fixture.intents, never()).persist(any());
    }

    @Test
    void prepareRejectsNetworkToolWithoutGovernedNetworkGrant() {
        Fixture fixture = fixture(
                "literature.search", "{\"query\":\"agents\"}",
                new ObjectValue(Map.of()), List.of(), List.of());
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(notFound("effectResult"));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(notFound("effectIntent"));
        when(fixture.executionProfile.networkPolicy())
                .thenReturn(NetworkPolicy.ALLOWLIST_ONLY);
        when(fixture.executionProfile.networkAllowlist())
                .thenReturn(List.of("unrelated-service"));

        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(fixture.action));

        verify(fixture.intents, never()).persist(any());
    }

    @Test
    void prepareRejectsAnyMutationThatDiffersFromFormalActionBinding() {
        Fixture fixture = fixture();
        ChainEffectRuntime.FrozenMutation forged =
                new ChainEffectRuntime.FrozenMutation(
                        ChainEffectRuntime.SourceKind.TOOL_ACTION,
                        fixture.action.taskId(), fixture.action.actionId(),
                        fixture.action.idempotencyKey(),
                        fixture.action.proposalId(),
                        fixture.action.instructionId(),
                        fixture.action.taskFrameId(), fixture.action.planId(),
                        fixture.action.planRevisionId(),
                        fixture.action.stepId(),
                        fixture.action.activationEventId(),
                        "workspace.forged", fixture.action.baseCandidateKey(),
                        fixture.action.actionSignatureSha256(),
                        fixture.action.versionFenceSha256());

        assertThrows(IllegalStateException.class,
                () -> fixture.authority.prepare(forged));

        verify(fixture.intents, never()).persist(any());
        verify(fixture.workspaceAuthorities, never()).create(
                any(), any(), any(), any());
    }

    @Test
    void mutationFenceLocksTaskBeforeCurrentGateAndSupplier() throws Exception {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        ProductChainCurrentAuthorityGate gate = mock(
                ProductChainCurrentAuthorityGate.class);
        List<String> order = new ArrayList<>();
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            order.add("lock-query");
            return query;
        });
        when(query.setParameter("taskId", "task.1")).thenReturn(query);
        when(query.getResultList()).thenAnswer(invocation -> {
            order.add("task-row-locked");
            return List.of("task.1");
        });
        when(gate.classify(any())).thenAnswer(invocation -> {
            order.add("current-gate");
            return ChainEffectRuntime.GateStatus.CURRENT;
        });
        ProductChainTaskMutationFence fence =
                new ProductChainTaskMutationFence(entityManager, gate);
        ChainEffectRuntime.PreparedEffect expected =
                new ChainEffectRuntime.PreparedEffect(
                        "intent.1", "action.1", "key.1", FENCE, "permit.1");

        ChainEffectRuntime.PreparedEffect actual = fence.prepareCurrent(
                fixture().action, () -> {
                    order.add("durable-mutation");
                    return expected;
                });

        assertEquals(expected, actual);
        assertEquals(List.of("lock-query", "task-row-locked",
                "current-gate", "durable-mutation"), order);
        assertNotNull(ProductChainTaskMutationFence.class
                .getMethod("prepareCurrent",
                        ChainEffectRuntime.FrozenMutation.class,
                        Supplier.class)
                .getAnnotation(Transactional.class));

        when(gate.classify(any())).thenReturn(
                ChainEffectRuntime.GateStatus.CANCELLED);
        boolean[] invoked = {false};
        assertThrows(IllegalStateException.class, () -> fence.prepareCurrent(
                fixture().action, () -> {
                    invoked[0] = true;
                    return expected;
                }));
        assertFalse(invoked[0]);
    }

    private Fixture fixture() {
        return fixture(
                "project.read", "{\"path\":\"paper.md\"}",
                new ObjectValue(Map.of(
                        "path", new TextValue("paper.md"))),
                List.of("paper.md"), List.of());
    }

    private Fixture fixture(
            String toolId,
            String completeArguments,
            ObjectValue effectArguments,
            List<String> readScopes,
            List<String> writeScopes) {
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        ChainModelRepository models = mock(ChainModelRepository.class);
        LeaseRepository leases = mock(LeaseRepository.class);
        StepRecoveryRepository recovery = mock(StepRecoveryRepository.class);
        EffectIntentRepository intents = mock(EffectIntentRepository.class);
        EffectOutcomeRepository outcomes = mock(EffectOutcomeRepository.class);
        AuthenticatedLiteratureSearchEffectExecutionComposer literatureEffects =
                mock(AuthenticatedLiteratureSearchEffectExecutionComposer.class);
        AuthenticatedProjectEffectExecutionComposer projectEffects = mock(
                AuthenticatedProjectEffectExecutionComposer.class);
        V2SandboxEffectExecutionComposer sandboxEffects = mock(
                V2SandboxEffectExecutionComposer.class);
        ProductChainActionWorkspaceAuthorityFactory workspaceAuthorities =
                mock(ProductChainActionWorkspaceAuthorityFactory.class);
        ProductChainTaskMutationFence mutationFence = mock(
                ProductChainTaskMutationFence.class);
        ProductPlanBootstrapRepositoryAdapter planBootstraps = mock(
                ProductPlanBootstrapRepositoryAdapter.class);
        when(mutationFence.prepareCurrent(any(), any())).thenAnswer(invocation ->
                ((Supplier<ChainEffectRuntime.PreparedEffect>)
                        invocation.getArgument(1)).get());
        ProductChainEffectAuthority authority = new ProductChainEffectAuthority(
                foundations, workflow, models, leases, recovery,
                intents, outcomes, literatureEffects, projectEffects,
                sandboxEffects, workspaceAuthorities, mutationFence,
                planBootstraps,
                new ObjectMapper());

        ChainPersistenceRecords.ActionBindingRecord binding =
                new ChainPersistenceRecords.ActionBindingRecord(
                        "action.1", "task.1", "event.action.1",
                        "proposal.1", 1, HASH, "key.1",
                        "instruction.1", "task-frame.1", "plan.1",
                        "revision.1", "step.1", "activation.1",
                        "workspace.1", ChainIdentity.NONE,
                        null, null, null, null, FENCE, NOW);
        when(workflow.findActionBindings("task.1"))
                .thenReturn(List.of(binding));
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        "proposal.1", "task.1", "invocation.1", 1,
                        ChainRole.EXECUTOR,
                        ChainProposalKind.EXECUTOR_TOOL_ACTION,
                        new ChainPersistenceRecords.CanonicalJson(
                                1, HASH,
                                "{\"completeArguments\":"
                                        + jsonString(completeArguments) + ","
                                        + "\"toolId\":" + jsonString(toolId) + ","
                                        + "\"requiredPermission\":"
                                        + jsonString(permissionFor(toolId)) + ","
                                        + "\"readScopes\":" + jsonArray(readScopes) + ","
                                        + "\"writeScopes\":" + jsonArray(writeScopes) + "}"),
                        new ChainPersistenceRecords.CanonicalJson(
                                1, HASH,
                                "[" + jsonString(permissionFor(toolId)) + ","
                                        + jsonString(toolId) + "]"),
                        null, null, NOW);
        when(models.findProposal("proposal.1"))
                .thenReturn(Optional.of(proposal));

        PlanId planId = new PlanId("plan.1");
        LeaseRecord lease = new LeaseRecord(
                planId, "owner", "token", 1L,
                NOW.minusSeconds(1), NOW.plusSeconds(60));
        when(leases.find(planId)).thenReturn(PersistenceResult.found(lease));
        PersistedStepRecoveryActive active = mock(
                PersistedStepRecoveryActive.class);
        Plan plan = mock(Plan.class);
        PlanRevision revision = mock(PlanRevision.class);
        PersistedStepActivation activation = mock(
                PersistedStepActivation.class);
        EventEnvelope event = mock(EventEnvelope.class);
        when(active.plan()).thenReturn(plan);
        when(active.activation()).thenReturn(activation);
        when(plan.id()).thenReturn(planId);
        when(plan.taskFrameId()).thenReturn(new TaskFrameId("task-frame.1"));
        when(plan.latestRevision()).thenReturn(revision);
        when(revision.id()).thenReturn(new PlanRevisionId("revision.1"));
        when(activation.stepId()).thenReturn(new PlanStepId("step.1"));
        when(activation.activationEvent()).thenReturn(event);
        when(activation.leaseOwnerId()).thenReturn("owner");
        when(activation.fencingToken()).thenReturn(1L);
        when(event.id()).thenReturn(new EventId("activation.1"));
        when(recovery.inspect(planId))
                .thenReturn(PersistenceResult.found(active));
        TaskFrame taskFrame = mock(TaskFrame.class);
        ExecutionProfile executionProfile = mock(ExecutionProfile.class);
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        Plan bootstrapPlan = mock(Plan.class);
        when(taskFrame.id()).thenReturn(new TaskFrameId("task-frame.1"));
        when(taskFrame.executionProfile()).thenReturn(executionProfile);
        when(executionProfile.capabilities()).thenReturn(
                V2ProductToolCatalog.requireDescriptor(new ToolId(toolId))
                        .requiredCapabilities());
        when(executionProfile.networkPolicy()).thenReturn(
                "literature.search".equals(toolId)
                        ? NetworkPolicy.ALLOWLIST_ONLY
                        : NetworkPolicy.DENY_ALL);
        when(executionProfile.networkAllowlist()).thenReturn(
                "literature.search".equals(toolId)
                        ? List.of("product-literature-search")
                        : List.of());
        when(bootstrap.taskFrame()).thenReturn(taskFrame);
        when(bootstrap.plan()).thenReturn(bootstrapPlan);
        when(bootstrapPlan.id()).thenReturn(planId);
        when(bootstrapPlan.taskFrameId()).thenReturn(
                new TaskFrameId("task-frame.1"));
        when(planBootstraps.find(planId)).thenReturn(Optional.of(bootstrap));
        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.TaskRecord(
                        "task.1", "command.1", "instruction.1", null,
                        7L, 9L, 42L, null, "client.1", HASH,
                        8L, "project-version.1", 0L, NOW)));

        EffectIntent effectIntent = new EffectIntent(
                new ToolCallId("action.1"), planId,
                new PlanStepId("step.1"), toolId, effectArguments);
        PersistedEffectIntent intent = new PersistedEffectIntent(
                effectIntent, "owner", 1L,
                new EventId("activation.1"));
        ExecutionReceipt receipt = new ExecutionReceipt(
                new ReceiptId("receipt.1"), new ToolCallId("action.1"),
                ReceiptStatus.SUCCESS, NOW, NOW,
                Optional.of(0), Optional.empty(),
                OutputCapture.inline("{}", false), OutputCapture.empty(),
                List.of(), Optional.empty(), List.of());
        PersistedEffectResult result = new PersistedEffectResult(
                receipt, "owner", 1L);
        ChainEffectRuntime.FrozenMutation action =
                new ChainEffectRuntime.FrozenMutation(
                        ChainEffectRuntime.SourceKind.TOOL_ACTION,
                        "task.1", "action.1", "key.1", "proposal.1",
                        "instruction.1", "task-frame.1", "plan.1",
                        "revision.1", "step.1", "activation.1",
                        "workspace.1", ChainIdentity.NONE, HASH, FENCE);
        ChainActionWorkspaceAuthority workspaceAuthority =
                readScopes.isEmpty() && writeScopes.isEmpty()
                ? null
                : new ChainActionWorkspaceAuthority(
                        "action.1", FENCE, "workspace.1",
                        readScopes, writeScopes,
                        new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                                ChainIdentity.NONE, "project-version.1",
                                null, List.of()));
        if (!readScopes.isEmpty() || !writeScopes.isEmpty()) {
            when(workspaceAuthorities.create(
                    any(), any(), any(), any()))
                    .thenReturn(workspaceAuthority);
        }
        return new Fixture(authority, action, intent, result,
                intents, outcomes, literatureEffects, projectEffects,
                sandboxEffects, workspaceAuthorities, workspaceAuthority,
                models, proposal, executionProfile, bootstrapPlan, plan);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            ChainPersistenceRecords.ModelProposalRecord source,
            String payload,
            String sourceRefs) {
        return new ChainPersistenceRecords.ModelProposalRecord(
                source.proposalId(), source.taskId(), source.invocationId(),
                source.schemaVersion(), source.role(), source.proposalKind(),
                new ChainPersistenceRecords.CanonicalJson(1, HASH, payload),
                new ChainPersistenceRecords.CanonicalJson(1, HASH, sourceRefs),
                source.bodyAuthorityType(), source.bodyAuthorityRef(),
                source.createdAt());
    }

    private static <T> PersistenceResult<T> notFound(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.NOT_FOUND, path);
    }

    private static void stubFreshEffect(Fixture fixture) {
        when(fixture.outcomes.findResult(new ToolCallId("action.1")))
                .thenReturn(notFound("effectResult"));
        when(fixture.intents.find(new ToolCallId("action.1")))
                .thenReturn(notFound("effectIntent"),
                        PersistenceResult.found(fixture.intent),
                        PersistenceResult.found(fixture.intent));
        when(fixture.intents.persist(any()))
                .thenReturn(PersistenceResult.applied(fixture.intent));
    }

    private static String jsonString(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String jsonArray(List<String> values) {
        try {
            return new ObjectMapper().writeValueAsString(values);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String permissionFor(String toolId) {
        Set<Capability> capabilities = V2ProductToolCatalog
                .requireDescriptor(new ToolId(toolId)).requiredCapabilities();
        if (capabilities.equals(Set.of(Capability.READ_PROJECT))) {
            return "permission.project-read";
        }
        if (capabilities.equals(Set.of(
                Capability.READ_PROJECT, Capability.WRITE_WORKSPACE))) {
            return "permission.project-write";
        }
        if (capabilities.equals(Set.of(
                Capability.EXECUTE_COMMAND, Capability.INSTALL_DEPENDENCY))) {
            return "permission.sandbox-execute-install";
        }
        if (capabilities.equals(Set.of(
                Capability.ACCESS_NETWORK,
                Capability.INVOKE_EXTERNAL_TOOL))) {
            return "permission.literature-network-external";
        }
        throw new AssertionError("unmapped test tool capabilities");
    }

    private record Fixture(
            ProductChainEffectAuthority authority,
            ChainEffectRuntime.FrozenMutation action,
            PersistedEffectIntent intent,
            PersistedEffectResult result,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            AuthenticatedLiteratureSearchEffectExecutionComposer literatureEffects,
            AuthenticatedProjectEffectExecutionComposer projectEffects,
            V2SandboxEffectExecutionComposer sandboxEffects,
            ProductChainActionWorkspaceAuthorityFactory workspaceAuthorities,
            ChainActionWorkspaceAuthority workspaceAuthority,
            ChainModelRepository models,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ExecutionProfile executionProfile,
            Plan bootstrapPlan,
            Plan recoveryPlan) {
    }
}
