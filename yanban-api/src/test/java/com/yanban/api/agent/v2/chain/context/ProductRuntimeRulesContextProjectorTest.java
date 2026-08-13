package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextManifestCodec;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextSourceSnapshot;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductRuntimeRulesContextProjectorTest {
    private static final Instant CREATED =
            Instant.parse("2026-08-08T00:00:00Z");
    private static final String EMPTY_JSON_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    private static final ChainContextModule MODULE =
            ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS;

    private ChainWorkflowRepository workflow;
    private ProductPlanBootstrapRepositoryAdapter bootstraps;
    private ProductChainSkillSnapshotRepository skills;
    private ProductRuntimeRulesContextProjector projector;

    @BeforeEach
    void setUp() {
        workflow = mock(ChainWorkflowRepository.class);
        bootstraps = mock(ProductPlanBootstrapRepositoryAdapter.class);
        skills = mock(ProductChainSkillSnapshotRepository.class);
        projector = new ProductRuntimeRulesContextProjector(
                workflow, bootstraps, skills);
        when(workflow.findPermissionDecisions("task.1"))
                .thenReturn(List.of());
        when(skills.findByTaskId("task.1")).thenReturn(Optional.of(
                ProductChainTaskSkillSnapshot.none(
                        "task.1", "instruction.1", CREATED)));
    }

    @Test
    void initialPlannerUsesVersionedPolicyWithoutTaskFrameFallback() {
        var result = projector.read(new ChainContextProjectionRequest(
                revision(ChainRole.PLANNER, false), 1_000_000));

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(ChainContextInputMatrix.requiredProjectionFields(
                        ChainRole.PLANNER, MODULE).stream().sorted().toList(),
                result.projectionFields().keySet().stream().sorted().toList());
        assertEquals(List.of("contractSchema", "permissionSnapshot",
                        "productBoundaryVersion", "providerSchema",
                        "rolePrompt", "skillVersion", "toolCatalogDigest"),
                result.sourceVersionComponents().keySet().stream()
                        .sorted().toList());
        ChainContextValue.ObjectValue rolePrompt = assertInstanceOf(
                ChainContextValue.ObjectValue.class,
                result.sourceVersionComponents().get("rolePrompt"));
        assertEquals(ProductChainRuntimeRuleValues.RULES_VERSION,
                ((ChainContextValue.Text) rolePrompt.values()
                        .get("version")).value());
        assertTrue(result.projectionFields().get("rules.permissions")
                .authorityRefs().isEmpty());
        verify(bootstraps, never()).find(new PlanId("plan.1"));
    }

    @Test
    void directAnswerHasNoToolsOrProjectCapabilitiesWithoutTaskFrame() {
        when(workflow.findRouteDecisions("task.1"))
                .thenReturn(List.of(directRoute()));

        var result = projector.read(new ChainContextProjectionRequest(
                directRevision(), 1_000_000));

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(ChainContextInputMatrix.requiredProjectionFields(
                        ChainRole.ANSWER, MODULE).stream().sorted().toList(),
                result.projectionFields().keySet().stream().sorted().toList());
        assertEquals("route:route.direct",
                ((ChainContextValue.Text) result.projectionParameters().get(
                        "permissionAuthorityRef")).value());
        verify(bootstraps, never()).find(new PlanId("plan.1"));
    }

    @Test
    void projectsEveryRequiredModuleFieldForAllFourRoles() {
        TaskFrame frame = frame();
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        Plan plan = mock(Plan.class);
        when(bootstrap.taskFrame()).thenReturn(frame);
        when(bootstrap.plan()).thenReturn(plan);
        when(plan.id()).thenReturn(new PlanId("plan.1"));
        when(plan.taskFrameId()).thenReturn(frame.id());
        when(bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding()));

        for (ChainRole role : ChainRole.values()) {
            var result = projector.read(new ChainContextProjectionRequest(
                    revision(role, true), 1_000_000));
            assertEquals(ChainContextModuleStatus.PRESENT,
                    result.presenceKind());
            assertEquals(ChainContextInputMatrix.requiredProjectionFields(
                            role, MODULE).stream().sorted().toList(),
                    result.projectionFields().keySet().stream()
                            .sorted().toList());
        }

        var executor = projector.read(new ChainContextProjectionRequest(
                revision(ChainRole.EXECUTOR, true), 1_000_000));
        ChainContextValue.ObjectValue workingDirectory = assertInstanceOf(
                ChainContextValue.ObjectValue.class,
                executor.projectionFields().get("rules.workingDirectory"));
        assertEquals("PROJECT_RELATIVE", ((ChainContextValue.Text)
                workingDirectory.values().get("pathNamespace")).value());
        assertEquals(".", ((ChainContextValue.Text)
                workingDirectory.values().get("root")).value());
        ChainContextValue.ObjectValue writeScope = assertInstanceOf(
                ChainContextValue.ObjectValue.class,
                executor.projectionFields().get("rules.writeScope"));
        assertTrue(((ChainContextValue.BooleanValue) writeScope.values()
                .get("writeAllowed")).value());

        var reflector = projector.read(new ChainContextProjectionRequest(
                revision(ChainRole.REFLECTOR, true), 1_000_000));
        ChainContextValue.ObjectValue rolePrompt = (ChainContextValue.ObjectValue)
                reflector.sourceVersionComponents().get("rolePrompt");
        ChainContextValue rules = rolePrompt.values().get("rules");
        assertEquals(ProductChainContractProjectionCodec.sha256(
                        ProductChainContractProjectionCodec.canonicalJson(rules)),
                ((ChainContextValue.Text) rolePrompt.values()
                        .get("sha256")).value());
        ChainContextValue.ObjectValue definition = (ChainContextValue.ObjectValue)
                ((ChainContextValue.ObjectValue) rules).values()
                        .get("definition");
        assertEquals(definition.values().get("acceptanceRules"),
                reflector.projectionFields().get("rules.acceptanceRules"));
        assertEquals(definition.values().get("finalizationRules"),
                reflector.projectionFields().get("rules.finalizationRules"));
    }

    @Test
    void finalCanonicalPromptKeepsRoleBodiesOnceAndOmitsIrrelevantToolSchemas()
            throws Exception {
        TaskFrame frame = frame();
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        Plan plan = mock(Plan.class);
        when(bootstrap.taskFrame()).thenReturn(frame);
        when(bootstrap.plan()).thenReturn(plan);
        when(plan.id()).thenReturn(new PlanId("plan.1"));
        when(plan.taskFrameId()).thenReturn(frame.id());
        when(bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(binding()));

        for (ChainRole role : ChainRole.values()) {
            var request = new ChainContextProjectionRequest(
                    revision(role, true), 1_000_000);
            var result = projector.read(request);
            String prompt = canonicalPrompt(request, result);
            JsonNode fields = runtimeFields(prompt);
            JsonNode common = fields.get(
                    "foundation.roleRulesSchemaPermissionBoundaryAndSkills");

            assertEquals(Set.of("role", "roleRules", "roleSchemaSha256",
                            "toolCatalogSha256", "skillSnapshotSha256",
                            "permissionSnapshotSha256",
                            "productBoundaryVersion"), fieldNames(common));
            assertEquals("product-runtime-rules-projector-v3",
                    result.projectionVersion());

            String schemaField = switch (role) {
                case PLANNER -> "rules.plannerSchema";
                case EXECUTOR -> "rules.executorSchema";
                case REFLECTOR -> "rules.reflectorSchema";
                case ANSWER -> "rules.answerSchema";
            };
            String schemaBody = ProductChainContractProjectionCodec
                    .canonicalJson(result.projectionFields().get(schemaField));
            assertEquals(1, occurrences(prompt, schemaBody), role.name());

            if (role == ChainRole.PLANNER || role == ChainRole.EXECUTOR) {
                String toolField = role == ChainRole.PLANNER
                        ? "rules.toolCategories"
                        : "rules.completeToolSchemas";
                String toolBody = ProductChainContractProjectionCodec
                        .canonicalJson(result.projectionFields().get(toolField));
                assertEquals(1, occurrences(prompt, toolBody), role.name());
                if (role == ChainRole.PLANNER) {
                    assertTrue(toolBody.contains(
                            "\"availableCapabilities\""));
                    assertTrue(toolBody.contains(
                            "\"availableRoutingRequirements\""));
                    assertTrue(toolBody.contains(
                            "\"grantedOperationCount\""));
                    assertFalse(toolBody.contains(
                            "\"availableOperations\""));
                    assertFalse(toolBody.contains(
                            "\"publicAlias\""));
                    assertFalse(toolBody.contains(
                            "\"completeToolSchemas\""));
                    assertFalse(toolBody.contains(
                            "\"parameterSchema\""));
                    assertFalse(toolBody.contains(
                            "\"permissionRef\""));
                    assertFalse(toolBody.contains(
                            "\"executionTarget\""));
                } else {
                    assertTrue(toolBody.contains(
                            "\"completeToolSchemas\""));
                    assertTrue(toolBody.contains(
                            "\"parameterSchema\""));
                }
            } else {
                assertFalse(prompt.contains("\"completeToolSchemas\""),
                        role.name());
            }
        }
    }

    @Test
    void roleRulesAreGenericAndCarryRequirementPreservationSemantics() {
        String planner = canonicalRoleRules(ChainRole.PLANNER);
        assertTrue(planner.contains("coverEveryExplicitUserRequirement"));
        assertTrue(planner.contains(
                "placeStepSpecificRequirementsInStepConstraints"));
        assertTrue(planner.contains(
                "additiveRequestsPreserveUnaffectedContentAndBehavior"));
        assertTrue(planner.contains("splitOnRealDependency"));
        assertTrue(planner.contains(
                "plannedAndUnsatisfiedCoverageRequireEmptyFactRefs"));
        assertTrue(planner.contains(
                "gapValidationOnlyWhenValidatingBoundPendingItem"));
        assertTrue(planner.contains("otherwiseGapValidationMustBeNull"));
        assertTrue(planner.contains(
                "candidateValidationConditionExactlyMatchesStepCompletionCondition"));
        assertTrue(planner.contains(
                "absentCandidateValidationConditionUsesJsonNullNeverEmptyString"));
        assertTrue(planner.contains(
                "schemaRepairPreservesSemanticProposalKindUnlessAuthorityChangesRoute"));
        assertTrue(planner.contains(
                "planningBlockedRequiresVisibleKnownFactRefs"));

        String executor = canonicalRoleRules(ChainRole.EXECUTOR);
        assertTrue(executor.contains("stepConstraintsAreMandatory"));
        assertTrue(executor.contains("useOnlyVisibleFormalToolSchemas"));
        assertTrue(executor.contains("CANONICAL_CHANGE_BUNDLE_V1"));
        assertTrue(executor.contains("expectedBaselineSha256"));
        assertTrue(executor.contains("addBaselineIsLiteralNone"));
        assertTrue(executor.contains("modifyAndDeleteBaselineIsLowercaseSha256"));
        assertTrue(executor.contains("deleteForbidsText"));
        assertTrue(executor.contains(
                "targetFilesExactlyMatchChangePathsInOrder"));
        assertEquals("product-chain-role-rules-v7",
                ProductChainRuntimeRuleValues.RULES_VERSION);
        assertTrue(planner.contains(
                "allBoundaryFlagsFalseRequiresDirectRoute"));
        assertTrue(planner.contains(
                "persistentProgressMustComeFromTaskNotSelectedKind"));
        String plannerSchema = ProductChainRoleSchemaSource
                .schema(ChainRole.PLANNER).canonicalJson();
        assertTrue(plannerSchema.contains(
                "JSON_NULL_WHEN_ABSENT_OTHERWISE_EXACT_MEMBER_OF_SAME_STEP_COMPLETION_CONDITIONS"));
        assertTrue(plannerSchema.contains(
                "JSON_NULL_UNLESS_VALIDATING_THE_BOUND_PENDING_ITEM"));
        assertTrue(plannerSchema.contains("EXACT_VISIBLE_AUTHORITY_REFS"));
        assertTrue(plannerSchema.contains("projectPathsAllowed"));
        String answer = canonicalRoleRules(ChainRole.ANSWER);
        assertTrue(answer.contains("answerTheEffectiveUserRequest"));
        assertTrue(answer.contains(
                "blockDeliveryWhenRequiredFactsAreMissingOrConflict"));

        for (ChainRole role : ChainRole.values()) {
            String value = canonicalRoleRules(role);
            assertFalse(value.contains("Sort.java"));
            assertFalse(value.contains("mergeSort"));
            assertFalse(value.contains("sandbox.execute"));
        }
    }

    @Test
    void nonInitialProjectionBlocksWithoutExactPlanBinding() {
        when(workflow.findPlanBindings("task.1")).thenReturn(List.of());

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> projector.read(new ChainContextProjectionRequest(
                        revision(ChainRole.EXECUTOR, true), 1_000_000)));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
        verify(bootstraps, never()).find(new PlanId("plan.1"));
    }

    private static ContextRevisionRecord revision(
            ChainRole role, boolean persistentAuthority) {
        return new ContextRevisionRecord(
                "context.1", "task.1", null, role,
                role == ChainRole.PLANNER
                        ? ChainWorkState.PLANNING : ChainWorkState.EXECUTING,
                "project-runtime-rules", "instruction.1",
                persistentAuthority ? "task-frame.1" : null,
                persistentAuthority ? "plan.1" : null,
                persistentAuthority ? "revision.1" : null,
                persistentAuthority ? 1L : null,
                null, null, 41L, "project-version.1",
                persistentAuthority ? "workspace.1" : null,
                null, null, null, null, null,
                "product-projectors-v1", "stable-id-v1",
                "chain-runtime-v1", ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, CREATED, null);
    }

    private static String canonicalPrompt(
            ChainContextProjectionRequest request,
            ProductChainContextAuthorityProjection result) {
        ChainContextSourceSnapshot runtime = new ChainContextSourceSnapshot(
                MODULE, result.presenceKind(),
                result.sourceVersionComponents(),
                result.readBoundaryComponents(), result.projectionVersion(),
                result.paginationVersion(), result.projectionParameters(),
                result.projectionFields(), result.emptyWatermark());
        CanonicalJson empty = new CanonicalJson(
                1, EMPTY_JSON_SHA256, "{}");
        List<ContextModuleRecord> modules = new ArrayList<>();
        for (ChainContextModule module : ChainContextModule.values()) {
            boolean runtimeModule = module == MODULE;
            modules.add(new ContextModuleRecord(
                    request.buildingRevision().contextRevisionId(),
                    request.buildingRevision().taskId(),
                    module.ordinalCode(), module,
                    runtimeModule ? runtime.presenceKind()
                            : ChainContextModuleStatus.EMPTY,
                    runtimeModule ? runtime.sourceVersion() : empty,
                    runtimeModule ? runtime.readBoundary() : empty,
                    runtimeModule ? runtime.projectionVersion() : "test-v1",
                    runtimeModule ? runtime.paginationVersion() : "test-v1",
                    runtimeModule ? runtime.projectionParameters() : empty,
                    runtimeModule ? runtime.projection() : empty,
                    CREATED));
        }
        return new ProductChainContextManifestCodec(new ObjectMapper())
                .canonicalPrompt(modules);
    }

    private static JsonNode runtimeFields(String prompt) throws Exception {
        for (JsonNode module : new ObjectMapper().readTree(prompt)
                .path("modules")) {
            if (MODULE.wireName().equals(module.path("kind").asText())) {
                return module.path("projection").path("fields");
            }
        }
        throw new AssertionError("runtime rules module is missing");
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static int occurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private static ContextRevisionRecord directRevision() {
        return new ContextRevisionRecord(
                "context.direct", "task.1", null, ChainRole.ANSWER,
                ChainWorkState.DIRECT_ANSWERING, "DIRECT_ROUTE",
                "instruction.1", null, null, null, null, null, null,
                41L, "project-version.1", null, null, null, null, null,
                null, "product-projectors-v1", "stable-id-v1",
                "chain-runtime-v1", ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, CREATED, null);
    }

    private static io.paperagent.v2.chain.ChainPersistenceRecords
            .RouteDecisionRecord directRoute() {
        return new io.paperagent.v2.chain.ChainPersistenceRecords
                .RouteDecisionRecord(
                "route.direct", "task.1", "route.event", "instruction.1",
                "proposal.route",
                io.paperagent.v2.chain.ChainPersistenceRecords
                        .RouteDecisionType.INITIAL,
                0, ChainExecutionMode.DIRECT, "plain answer",
                new io.paperagent.v2.chain.ChainPersistenceRecords
                        .CanonicalJson(1, "0".repeat(64), "{}"),
                new io.paperagent.v2.chain.ChainPersistenceRecords
                        .CanonicalJson(1, "0".repeat(64), "[]"),
                new io.paperagent.v2.chain.ChainPersistenceRecords
                        .CanonicalJson(1, "0".repeat(64), "[]"),
                false, false, false, false, null, null, null, CREATED);
    }

    private static String canonicalRoleRules(ChainRole role) {
        return ProductChainContractProjectionCodec.canonicalJson(
                ProductChainGenericRoleRules.definition(role));
    }

    private static TaskFrame frame() {
        return new TaskFrame(
                new TaskFrameId("task-frame.1"), "Execute accepted work.",
                List.of("project"), List.of("verified delivery"), List.of(),
                Optional.of(new ProjectVersionRef(
                        "project-41", "project-version.1")),
                ProductChainPermissionPolicySource.executionProfile(true),
                CREATED);
    }

    private static PlanBindingRecord binding() {
        return new PlanBindingRecord(
                "binding.1", "task.1", "event.1", "instruction.1",
                "route.1", "task-frame.1", "plan.1", "revision.1",
                1, "PLAN_COMMIT", "plan.1", "0".repeat(64), null,
                CREATED);
    }
}
