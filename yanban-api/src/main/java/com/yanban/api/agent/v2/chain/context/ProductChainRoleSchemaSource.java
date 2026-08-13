package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Role output schemas derived from the existing typed provider protocol. */
public final class ProductChainRoleSchemaSource {
    public static final String SCHEMA_VERSION = "product-chain-role-schema-v12";

    private static final List<String> TOOL_ACTION_SELF_REPAIR_FIELDS = List.of(
            "priorErrorRef", "priorActionRef", "changeFromPriorAction",
            "expectedProgress");

    private ProductChainRoleSchemaSource() {
    }

    public static SchemaProjection schema(ChainRole role) {
        return schema(role, null);
    }

    public static SchemaProjection schema(
            ChainRole role, String plannerCallReason) {
        Objects.requireNonNull(role, "role");
        Map<String, Class<?>> payloads = visiblePayloadTypes(
                role, plannerCallReason);
        List<ChainContextValue> variants = payloads.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> (ChainContextValue) ChainContextValue.object(Map.of(
                        "kind", ChainContextValue.text(entry.getKey()),
                        "payload", recordSchema(entry.getValue()))))
                .toList();
        ChainContextValue value = ChainContextValue.object(Map.of(
                "schemaVersion", ChainContextValue.text(SCHEMA_VERSION),
                "providerSchemaVersion", ChainContextValue.text(
                        ProviderRoleOutput.SCHEMA_VERSION),
                "role", ChainContextValue.text(role.name()),
                "rootFields", strings(List.of(
                        "kind", "payload", "schemaVersion")),
                "additionalRootFieldsAllowed", ChainContextValue.bool(false),
                "variants", ChainContextValue.array(variants)));
        String canonical = ProductChainContractProjectionCodec
                .canonicalJson(value);
        return new SchemaProjection(
                role, value,
                ProductChainContractProjectionCodec.sha256(canonical),
                canonical);
    }

    private static Map<String, Class<?>> visiblePayloadTypes(
            ChainRole role, String plannerCallReason) {
        Map<String, Class<?>> all = payloadTypes(role);
        if (role != ChainRole.PLANNER || plannerCallReason == null) {
            return all;
        }
        Set<String> allowed = switch (plannerCallReason) {
            case "INITIAL_INTAKE" -> Set.of(
                    "DIRECT_ROUTE", "PERSISTENT_PLAN", "NEED_USER_INPUT",
                    "NEED_PERMISSION", "PLANNING_BLOCKED");
            case "PERSISTENT_PLAN" -> Set.of(
                    "PERSISTENT_PLAN", "NEED_USER_INPUT",
                    "NEED_PERMISSION", "PLANNING_BLOCKED");
            case "PLAN_REVISION" -> Set.of(
                    "PLAN_REVISION", "NEED_USER_INPUT",
                    "NEED_PERMISSION", "PLANNING_BLOCKED");
            case "USER_INSTRUCTION_DISPOSITION" -> Set.of(
                    "USER_INSTRUCTION_DISPOSITION", "NEED_USER_INPUT",
                    "NEED_PERMISSION", "PLANNING_BLOCKED");
            default -> all.keySet();
        };
        TreeMap<String, Class<?>> visible = new TreeMap<>();
        all.forEach((kind, type) -> {
            if (allowed.contains(kind)) {
                visible.put(kind, type);
            }
        });
        if (visible.isEmpty()) {
            throw new IllegalStateException(
                    "Planner call reason exposes no proposal kinds: "
                            + plannerCallReason);
        }
        return Map.copyOf(visible);
    }

    private static Map<String, Class<?>> payloadTypes(ChainRole role) {
        Class<?> contract = switch (role) {
            case PLANNER -> PlannerPayload.class;
            case EXECUTOR -> ExecutorPayload.class;
            case REFLECTOR -> ReflectorPayload.class;
            case ANSWER -> AnswerPayload.class;
        };
        TreeMap<String, Class<?>> result = new TreeMap<>();
        for (Class<?> payloadType : contract.getPermittedSubclasses()) {
            String wireName = wireName(payloadType.getSimpleName());
            ChainProposalKind kind;
            try {
                kind = ChainProposalKind.resolve(role, wireName);
            } catch (IllegalArgumentException mismatch) {
                throw new IllegalStateException(
                        "typed payload has no matching proposal kind: "
                                + payloadType.getName(), mismatch);
            }
            if (!ChainProposalPayload.class.isAssignableFrom(payloadType)
                    || result.put(kind.wireName(), payloadType) != null) {
                throw new IllegalStateException(
                        "invalid typed payload schema for " + wireName);
            }
        }
        long formalKinds = Arrays.stream(ChainProposalKind.values())
                .filter(kind -> kind.role() == role).count();
        if (result.size() != formalKinds) {
            throw new IllegalStateException(
                    "role payload hierarchy and proposal kinds differ for "
                            + role);
        }
        return Map.copyOf(result);
    }

    private static ChainContextValue recordSchema(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalStateException(
                    "provider payload field is not a record: " + type);
        }
        TreeMap<String, ChainContextValue> fields = new TreeMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            fields.put(component.getName(), componentSchema(type, component));
        }
        return ChainContextValue.object(Map.of(
                "jsonType", ChainContextValue.text("object"),
                "recordType", ChainContextValue.text(type.getName()),
                "fieldNames", strings(fields.keySet().stream().toList()),
                "fields", ChainContextValue.object(fields),
                "crossFieldRules", recordCrossFieldRules(type),
                "additionalFieldsAllowed", ChainContextValue.bool(false),
                "canonicalConstructorValidation", ChainContextValue.bool(true)));
    }

    private static ChainContextValue recordCrossFieldRules(Class<?> type) {
        if (type == io.paperagent.v2.chain.ProposalFields.RoutingBoundary.class) {
            return ChainContextValue.array(List.of(ChainContextValue.object(
                    Map.of(
                            "rule", ChainContextValue.text(
                                    "AT_LEAST_ONE_TRUE"),
                            "members", strings(List.of(
                                    "needsTool", "needsNetwork",
                                    "needsProject",
                                    "needsPersistentProgress")),
                            "allFalseRequiresKind",
                            ChainContextValue.text("DIRECT_ROUTE")))));
        }
        if (type == PlannerPayload.PersistentPlan.class) {
            return ChainContextValue.array(List.of(
                    ChainContextValue.object(Map.ofEntries(
                            Map.entry("rule", ChainContextValue.text(
                                    "EXACT_VALIDATION_REQUIREMENT_BINDING")),
                            Map.entry("declarationMember", ChainContextValue.text(
                                    "taskFrameDraft.requirements.validationRequirements[]")),
                            Map.entry("bindingMember", ChainContextValue.text(
                                    "initialPlan.steps[].validationRequirementIds[]")),
                            Map.entry("conditionMember", ChainContextValue.text(
                                    "initialPlan.steps[].completionConditions[]")),
                            Map.entry("eachRequirementBoundExactlyOnce",
                                    ChainContextValue.bool(true)),
                            Map.entry("completionConditionCopiedByteForByte",
                                    ChainContextValue.bool(true)))),
                    ChainContextValue.object(Map.ofEntries(
                            Map.entry("rule", ChainContextValue.text(
                                    "FINAL_CANDIDATE_VALIDATION_LINK")),
                            Map.entry("candidateRequirementCount",
                                    ChainContextValue.text(
                                            "EXACTLY_ONE_WHEN_ANY_STEP_MAY_CHANGE_CANDIDATE")),
                            Map.entry("bindingStep", ChainContextValue.text(
                                    "LATER_NON_CHANGING_STEP_TRANSITIVELY_DEPENDENT_ON_LAST_CANDIDATE_CHANGING_STEP")),
                            Map.entry("exactCopyMembers", strings(List.of(
                                    "validationRequirement.completionCondition",
                                    "bindingStep.completionConditions[]",
                                    "bindingStep.candidateValidationCompletionCondition")))))));
        }
        if (type == PlannerPayload.UserInstructionDisposition.class) {
            return ChainContextValue.array(List.of(ChainContextValue.object(
                    Map.ofEntries(
                            Map.entry("rule", ChainContextValue.text(
                                    "COMPLETED_CLASSIFICATION_ONLY")),
                            Map.entry("classificationMustBeComplete",
                                    ChainContextValue.bool(true)),
                            Map.entry("unclassifiableRootKind",
                                    ChainContextValue.text("NEED_USER_INPUT")),
                            Map.entry("permissionGapRootKind",
                                    ChainContextValue.text("NEED_PERMISSION")),
                            Map.entry("controlFlowValuesForbiddenInClassification",
                                    strings(List.of("NEED_USER_INPUT",
                                            "NEED_PERMISSION",
                                            "PLANNING_BLOCKED")))))));
        }
        if (type == io.paperagent.v2.chain.ProposalFields.StepDraft.class) {
            return ChainContextValue.array(List.of(
                    ChainContextValue.object(Map.of(
                            "rule", ChainContextValue.text(
                                    "NON_EMPTY_STEP_COLLECTIONS"),
                            "members", strings(List.of(
                                    "completionConditions", "deliverables",
                                    "scopes")))),
                    ChainContextValue.object(Map.ofEntries(
                            Map.entry("rule", ChainContextValue.text(
                                    "CANDIDATE_VALIDATION_EXACT_MEMBER")),
                            Map.entry("nullableMember", ChainContextValue.text(
                                    "candidateValidationCompletionCondition")),
                            Map.entry("requiredContainerMember",
                                    ChainContextValue.text(
                                            "completionConditions[]")),
                            Map.entry("copyMode", ChainContextValue.text(
                                    "BYTE_FOR_BYTE"))))));
        }
        if (type == ExecutorPayload.StepResult.class) {
            return ChainContextValue.array(List.of(ChainContextValue.object(Map.of(
                    "rule", ChainContextValue.text("EXACT_SET_MATCH"),
                    "outputMember", ChainContextValue.text(
                            "validationSources[].requirementId"),
                    "contextMember", ChainContextValue.text(
                            "activeStep.validationRequirementIds"),
                    "eachRequiredExactlyOnce", ChainContextValue.bool(true),
                    "extraIdsAllowed", ChainContextValue.bool(false),
                    "emptyOnlyWhenActiveStepHasNoValidationRequirements",
                    ChainContextValue.bool(true)))));
        }
        if (type != ExecutorPayload.ToolAction.class) {
            return ChainContextValue.array(List.of());
        }
        return ChainContextValue.array(List.of(ChainContextValue.object(Map.of(
                "rule", ChainContextValue.text("ALL_OR_NONE"),
                "group", ChainContextValue.text("TOOL_ACTION_SELF_REPAIR"),
                "members", strings(TOOL_ACTION_SELF_REPAIR_FIELDS),
                "activationCondition", ChainContextValue.text(
                        "ALL_FOUR_NONBLANK_ONLY_WHEN_EXACT_VISIBLE_PRIOR_ERROR_AND_ACTION_AUTHORITIES_EXIST"),
                "futureContingencyIsNotPriorFailure", ChainContextValue.bool(true)))));
    }

    private static ChainContextValue componentSchema(
            Class<?> recordType, RecordComponent component) {
        if (recordType == io.paperagent.v2.chain.ProposalFields.StepDraft.class
                && component.getName().equals(
                "candidateValidationCompletionCondition")) {
            return nullable(typeSchema(component.getGenericType()),
                    "JSON_NULL_WHEN_ABSENT_OTHERWISE_EXACT_MEMBER_OF_SAME_STEP_COMPLETION_CONDITIONS");
        }
        if (recordType == io.paperagent.v2.chain.ProposalFields.TaskFrameDraft.class
                && component.getName().equals("objects")) {
            return withProperties(
                    authorityRefs(typeSchema(component.getGenericType())),
                    Map.of("minItems", ChainContextValue.number(1)));
        }
        if (nonEmptyListComponent(recordType, component.getName())) {
            return withProperties(typeSchema(component.getGenericType()),
                    Map.of("minItems", ChainContextValue.number(1),
                            "emptyArrayAllowed", ChainContextValue.bool(false)));
        }
        if (recordType == io.paperagent.v2.contracts.ValidationRequirement.class
                && component.getName().equals("completionCondition")) {
            return withProperties(typeSchema(component.getGenericType()),
                    Map.of("minLength", ChainContextValue.number(1),
                            "bindingRule", ChainContextValue.text(
                            "COPY_BYTE_FOR_BYTE_TO_THE_ONE_BOUND_STEP_COMPLETION_CONDITIONS")));
        }
        if (recordType == PlannerPayload.UserInstructionDisposition.class
                && component.getName().equals("classification")) {
            return withProperties(typeSchema(component.getGenericType()),
                    Map.of(
                            "semanticType", ChainContextValue.text(
                                    "COMPLETED_INSTRUCTION_CLASSIFICATION"),
                            "forbiddenValues", strings(List.of(
                                    "NEED_USER_INPUT", "NEED_PERMISSION",
                                    "PLANNING_BLOCKED")),
                            "unclassifiableBehavior", ChainContextValue.text(
                                    "RETURN_ROOT_KIND_NEED_USER_INPUT")));
        }
        if (recordType == PlannerPayload.NeedPermission.class
                && component.getName().equals("lowerPrivilegeAlternative")) {
            return withProperties(typeSchema(component.getGenericType()),
                    Map.of("minLength", ChainContextValue.number(1),
                            "blankAllowed", ChainContextValue.bool(false)));
        }
        if (recordType == io.paperagent.v2.chain.GapValidation.Check.class
                && component.getName().equals("factRef")) {
            return withProperties(typeSchema(component.getGenericType()),
                    Map.of("minLength", ChainContextValue.number(1),
                            "semanticType", ChainContextValue.text(
                                    "EXACT_VISIBLE_ANSWER_AUTHORITY_REF")));
        }
        if (ChainProposalPayload.class.isAssignableFrom(recordType)
                && component.getName().equals("gapValidation")) {
            return nullable(typeSchema(component.getGenericType()),
                    "JSON_NULL_UNLESS_VALIDATING_THE_BOUND_PENDING_ITEM");
        }
        if (recordType == ExecutorPayload.ToolAction.class
                && TOOL_ACTION_SELF_REPAIR_FIELDS.contains(component.getName())) {
            return nullableGroup(typeSchema(component.getGenericType()),
                    "JSON_NULL_FOR_ALL_GROUP_MEMBERS_UNLESS_REPAIRING_PRIOR_ACTION",
                    "TOOL_ACTION_SELF_REPAIR", TOOL_ACTION_SELF_REPAIR_FIELDS);
        }
        if (recordType == ExecutorPayload.ToolAction.class
                && component.getName().equals("toolId")) {
            return withProperties(typeSchema(component.getGenericType()), Map.of(
                    "semanticType", ChainContextValue.text(
                            "EXACT_DESCRIPTOR_ID_FROM_ONE_VISIBLE_COMPLETE_TOOL_SCHEMA"),
                    "publicAliasAllowed", ChainContextValue.bool(false),
                    "capabilityNameAllowed", ChainContextValue.bool(false)));
        }
        if (recordType == ExecutorPayload.ToolAction.class
                && component.getName().equals("requiredPermission")) {
            return withProperties(typeSchema(component.getGenericType()), Map.of(
                    "semanticType", ChainContextValue.text(
                            "EXACT_PERMISSION_REF_FROM_SAME_SELECTED_TOOL_SCHEMA"),
                    "capabilityNameAllowed", ChainContextValue.bool(false)));
        }
        if (recordType == ExecutorPayload.StepResult.class
                && component.getName().equals("validationSources")) {
            return withProperties(typeSchema(component.getGenericType()), Map.of(
                    "semanticType", ChainContextValue.text(
                            "EXACT_ACTIVE_STEP_VALIDATION_REQUIREMENT_TO_VISIBLE_RECEIPT_BINDINGS"),
                    "receiptRefMustAlsoAppearInReceiptRefs",
                    ChainContextValue.bool(true)));
        }
        if (recordType == ExecutorPayload.WorkspaceChange.class
                && component.getName().equals("baseCandidateRef")) {
            return withProperties(typeSchema(component.getGenericType()), Map.of(
                    "semanticType", ChainContextValue.text(
                            "EXACT_VISIBLE_FROZEN_BASE_CANDIDATE_REF"),
                    "noneLiteral", ChainContextValue.text("NONE"),
                    "noneAllowedOnlyWhenVisibleBoundaryHasNoBaseCandidate",
                    ChainContextValue.bool(true),
                    "blankAllowed", ChainContextValue.bool(false)));
        }
        if (recordType == ExecutorPayload.WorkspaceChange.class
                && component.getName().equals("manifestChanges")) {
            return withProperties(typeSchema(component.getGenericType()), Map.of(
                    "requiredCardinality", ChainContextValue.text("EMPTY"),
                    "separateManifestMutationSupported",
                    ChainContextValue.bool(false),
                    "fileAddDeleteEncoding", ChainContextValue.text(
                            "USE_CANONICAL_CHANGES_ARRAY_OPERATIONS")));
        }
        if (recordType == ExecutorPayload.WorkspaceChange.class
                && component.getName().equals("inlineCanonicalChangeBody")) {
            return ChainContextValue.object(Map.of(
                    "jsonType", ChainContextValue.text("string"),
                    "format", ChainContextValue.text(
                            "CANONICAL_CHANGE_BUNDLE_V1"),
                    "protocol",
                    ProductChainGenericRoleRules.workspaceChangeProtocol()));
        }
        return typeSchema(component.getGenericType());
    }

    private static boolean nonEmptyListComponent(
            Class<?> recordType, String componentName) {
        if (recordType == io.paperagent.v2.chain.ProposalFields.TaskFrameDraft.class) {
            return componentName.equals("deliverables");
        }
        if (recordType == io.paperagent.v2.chain.ProposalFields.StepDraft.class) {
            return Set.of("completionConditions", "deliverables", "scopes")
                    .contains(componentName);
        }
        if (recordType == io.paperagent.v2.chain.ProposalFields.PlanDraft.class) {
            return componentName.equals("steps");
        }
        if (recordType == PlannerPayload.PersistentPlan.class
                || recordType == PlannerPayload.PlanRevision.class) {
            return componentName.equals("requirementCoverage");
        }
        if (recordType == PlannerPayload.NeedUserInput.class) {
            return Set.of("missingFields", "closingConditions")
                    .contains(componentName);
        }
        if (recordType == PlannerPayload.PlanningBlocked.class) {
            return componentName.equals("knownFactRefs");
        }
        if (recordType == io.paperagent.v2.chain.GapValidation.class) {
            return componentName.equals("checks");
        }
        return false;
    }

    private static ChainContextValue nullable(
            ChainContextValue source, String condition) {
        ChainContextValue.ObjectValue object =
                (ChainContextValue.ObjectValue) source;
        TreeMap<String, ChainContextValue> values =
                new TreeMap<>(object.values());
        values.put("nullable", ChainContextValue.bool(true));
        values.put("nullabilityCondition",
                ChainContextValue.text(condition));
        return ChainContextValue.object(values);
    }

    private static ChainContextValue nullableGroup(
            ChainContextValue source,
            String condition,
            String group,
            List<String> members) {
        ChainContextValue.ObjectValue nullable =
                (ChainContextValue.ObjectValue) nullable(source, condition);
        TreeMap<String, ChainContextValue> values =
                new TreeMap<>(nullable.values());
        values.put("allOrNoneGroup", ChainContextValue.text(group));
        values.put("allOrNoneGroupMembers", strings(members));
        return ChainContextValue.object(values);
    }

    private static ChainContextValue authorityRefs(ChainContextValue source) {
        ChainContextValue.ObjectValue object =
                (ChainContextValue.ObjectValue) source;
        TreeMap<String, ChainContextValue> values =
                new TreeMap<>(object.values());
        values.put("semanticType",
                ChainContextValue.text("EXACT_VISIBLE_AUTHORITY_REFS"));
        values.put("projectPathsAllowed", ChainContextValue.bool(false));
        return ChainContextValue.object(values);
    }

    private static ChainContextValue withProperties(
            ChainContextValue source,
            Map<String, ChainContextValue> properties) {
        ChainContextValue.ObjectValue object =
                (ChainContextValue.ObjectValue) source;
        TreeMap<String, ChainContextValue> values =
                new TreeMap<>(object.values());
        values.putAll(properties);
        return ChainContextValue.object(values);
    }

    private static ChainContextValue typeSchema(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == List.class) {
            return ChainContextValue.object(Map.of(
                    "jsonType", ChainContextValue.text("array"),
                    "items", typeSchema(
                            parameterized.getActualTypeArguments()[0])));
        }
        if (!(type instanceof Class<?> raw)) {
            throw new IllegalStateException(
                    "unsupported provider schema type: " + type);
        }
        if (raw == String.class) {
            return scalar("string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return scalar("boolean");
        }
        if (raw == int.class || raw == Integer.class
                || raw == long.class || raw == Long.class) {
            return scalar("integer");
        }
        if (raw.isEnum()) {
            return ChainContextValue.object(Map.of(
                    "jsonType", ChainContextValue.text("string"),
                    "enum", strings(Arrays.stream(raw.getEnumConstants())
                            .map(value -> ((Enum<?>) value).name())
                            .sorted().toList())));
        }
        if (raw.isRecord()) {
            return recordSchema(raw);
        }
        throw new IllegalStateException(
                "unsupported provider schema type: " + raw.getName());
    }

    private static ChainContextValue scalar(String jsonType) {
        return ChainContextValue.object(Map.of(
                "jsonType", ChainContextValue.text(jsonType)));
    }

    private static ChainContextValue.ArrayValue strings(List<String> values) {
        return ChainContextValue.array(values.stream().sorted()
                .map(ChainContextValue::text).toList());
    }

    private static String wireName(String simpleName) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < simpleName.length(); index++) {
            char current = simpleName.charAt(index);
            if (Character.isUpperCase(current) && index > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(current));
        }
        return result.toString();
    }

    public record SchemaProjection(
            ChainRole role,
            ChainContextValue value,
            String sha256,
            String canonicalJson) {
        public SchemaProjection {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(value, "value");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be canonical");
            }
            Objects.requireNonNull(canonicalJson, "canonicalJson");
        }
    }
}
