package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainRoleSchemaSourceTest {
    @Test
    void everyRoleSchemaIsDerivedFromAllFormalProposalKinds() {
        for (ChainRole role : ChainRole.values()) {
            var schema = ProductChainRoleSchemaSource.schema(role);
            ChainContextValue.ObjectValue root =
                    (ChainContextValue.ObjectValue) schema.value();
            ChainContextValue.ArrayValue variants =
                    (ChainContextValue.ArrayValue) root.values().get("variants");
            List<String> actualKinds = variants.values().stream()
                    .map(value -> (ChainContextValue.ObjectValue) value)
                    .map(value -> (ChainContextValue.Text)
                            value.values().get("kind"))
                    .map(ChainContextValue.Text::value)
                    .toList();
            List<String> expectedKinds = Arrays.stream(
                            ChainProposalKind.values())
                    .filter(kind -> kind.role() == role)
                    .map(ChainProposalKind::wireName)
                    .sorted().toList();

            assertEquals(expectedKinds, actualKinds);
            assertEquals(role, schema.role());
            assertEquals(schema, ProductChainRoleSchemaSource.schema(role));
            assertFalse(schema.canonicalJson().contains("Sort.java"));
        }
    }

    @Test
    void nestedRecordFieldsComeFromTypedProtocolComponents() {
        String schema = ProductChainRoleSchemaSource
                .schema(ChainRole.REFLECTOR).canonicalJson();

        assertTrue(schema.contains("ACCEPT_STEP_AND_READY_TO_FINALIZE"));
        assertTrue(schema.contains("canonicalConstructorValidation"));
        assertTrue(schema.contains("conditionJudgements"));
        assertTrue(schema.contains("finalization"));
        assertTrue(schema.contains("additionalFieldsAllowed\":false"));
    }

    @Test
    void plannerSchemaExposesTypedRequirementsAndValidationBindings() {
        String schema = ProductChainRoleSchemaSource
                .schema(ChainRole.PLANNER).canonicalJson();

        assertTrue(schema.contains("TaskRequirements"));
        assertTrue(schema.contains("deliveryRequirement"));
        assertTrue(schema.contains("validationRequirements"));
        assertTrue(schema.contains("publishRequirement"));
        assertTrue(schema.contains("validationRequirementIds"));
        assertTrue(schema.contains("routingBoundary"));
        assertTrue(schema.contains("AT_LEAST_ONE_TRUE"));
        assertTrue(schema.contains("allFalseRequiresKind"));
        assertTrue(schema.contains("DIRECT_ROUTE"));
        assertTrue(schema.contains("minItems\":1"));
        assertTrue(schema.contains("product-chain-role-schema-v13"));
        assertTrue(schema.contains("inlineAnswerBody"));
        assertTrue(schema.contains(
                "COMPLETE_USER_VISIBLE_ANSWER_FROM_THIS_PROVIDER_CALL"));
        assertTrue(schema.contains(
                "RUNTIME_OWNED_ANSWER_BODY_REF_FORBIDDEN_IN_PROVIDER_OUTPUT"));
        assertTrue(schema.contains("EXACT_VALIDATION_REQUIREMENT_BINDING"));
        assertTrue(schema.contains(
                "completionConditionCopiedByteForByte"));
        assertTrue(schema.contains("NON_EMPTY_STEP_COLLECTIONS"));
        assertTrue(schema.contains("emptyArrayAllowed\":false"));
        assertTrue(schema.contains("COMPLETED_CLASSIFICATION_ONLY"));
        assertTrue(schema.contains("forbiddenValues"));
        assertTrue(schema.contains(
                "RETURN_ROOT_KIND_NEED_USER_INPUT"));
    }

    @Test
    void executorSchemaExposesCanonicalChangeBundleProtocol() {
        String schema = ProductChainRoleSchemaSource
                .schema(ChainRole.EXECUTOR).canonicalJson();

        assertTrue(schema.contains("inlineCanonicalChangeBody"));
        assertTrue(schema.contains("CANONICAL_CHANGE_BUNDLE_V1"));
        assertTrue(schema.contains("expectedBaselineSha256"));
        assertTrue(schema.contains("deleteForbidsText"));
        assertTrue(schema.contains(
                "targetFilesExactlyMatchChangePathsInOrder"));
        assertTrue(schema.contains("product-chain-role-schema-v13"));
        assertTrue(schema.contains(
                "EXACT_DESCRIPTOR_ID_FROM_ONE_VISIBLE_COMPLETE_TOOL_SCHEMA"));
        assertTrue(schema.contains(
                "EXACT_PERMISSION_REF_FROM_SAME_SELECTED_TOOL_SCHEMA"));
        assertTrue(schema.contains("publicAliasAllowed\":false"));
        assertTrue(schema.contains("capabilityNameAllowed\":false"));
        assertTrue(schema.contains("EXACT_SET_MATCH"));
        assertTrue(schema.contains(
                "validationSources[].requirementId"));
        assertTrue(schema.contains(
                "activeStep.validationRequirementIds"));
        assertTrue(schema.contains(
                "EXACT_ACTIVE_STEP_VALIDATION_REQUIREMENT_TO_VISIBLE_RECEIPT_BINDINGS"));
        assertTrue(schema.contains(
                "receiptRefMustAlsoAppearInReceiptRefs\":true"));
        assertTrue(schema.contains(
                "EXACT_VISIBLE_FROZEN_BASE_CANDIDATE_REF"));
        assertTrue(schema.contains(
                "noneAllowedOnlyWhenVisibleBoundaryHasNoBaseCandidate"));
        assertTrue(schema.contains("blankAllowed\":false"));
        assertTrue(schema.contains("requiredCardinality\":\"EMPTY"));
        assertTrue(schema.contains(
                "separateManifestMutationSupported\":false"));
        assertTrue(schema.contains(
                "USE_CANONICAL_CHANGES_ARRAY_OPERATIONS"));
        assertTrue(schema.contains("TOOL_ACTION_SELF_REPAIR"));
        assertTrue(schema.contains("allOrNoneGroupMembers"));
        assertTrue(schema.contains("crossFieldRules"));
        assertTrue(schema.contains("futureContingencyIsNotPriorFailure"));
        assertTrue(schema.contains(
                "ALL_FOUR_NONBLANK_ONLY_WHEN_EXACT_VISIBLE_PRIOR_ERROR_AND_ACTION_AUTHORITIES_EXIST"));
        assertTrue(schema.contains(
                "JSON_NULL_FOR_ALL_GROUP_MEMBERS_UNLESS_REPAIRING_PRIOR_ACTION"));
        assertTrue(schema.contains("priorErrorRef"));
        assertTrue(schema.contains("priorActionRef"));
        assertTrue(schema.contains("changeFromPriorAction"));
        assertTrue(schema.contains("expectedProgress"));
    }

    @Test
    void plannerSchemaExposesOnlyKindsAllowedByKnownInvocationStage() {
        String initial = ProductChainRoleSchemaSource.schema(
                ChainRole.PLANNER, "INITIAL_INTAKE").canonicalJson();
        assertTrue(initial.contains("DIRECT_ROUTE"));
        assertTrue(initial.contains("PERSISTENT_PLAN"));
        assertFalse(initial.contains("PLAN_REVISION"));
        assertFalse(initial.contains("USER_INSTRUCTION_DISPOSITION"));

        String revision = ProductChainRoleSchemaSource.schema(
                ChainRole.PLANNER, "PLAN_REVISION").canonicalJson();
        assertTrue(revision.contains("PLAN_REVISION"));
        assertTrue(revision.contains("NEED_USER_INPUT"));
        assertFalse(revision.contains("DIRECT_ROUTE"));
        assertFalse(revision.contains("PERSISTENT_PLAN"));

        String pending = ProductChainRoleSchemaSource.schema(
                ChainRole.PLANNER,
                "PENDING_ITEM_VALIDATION").canonicalJson();
        assertTrue(pending.contains("DIRECT_ROUTE"));
        assertTrue(pending.contains("PLAN_REVISION"));
        assertTrue(pending.contains("USER_INSTRUCTION_DISPOSITION"));
    }
}
