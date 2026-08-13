package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure role-field construction for the runtime-rules Context module. */
final class ProductChainRuntimeRuleValues {
    static final String RULES_VERSION = "product-chain-role-rules-v7";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductChainRuntimeRuleValues() {
    }

    static Map<String, ChainContextValue> fields(
            ContextRevisionRecord revision,
            ProductChainRoleSchemaSource.SchemaProjection schema,
            ProductChainToolContextValueCodec.Projection tools,
            ProductChainPermissionPolicySource.Projection permission,
            ProductChainPermissionPolicySource.Projection productPolicy,
            ProductChainTaskSkillSnapshot skill,
            ExecutionProfile profile) {
        Objects.requireNonNull(revision, "revision");
        ChainRole role = revision.role();
        RuleProjection rules = roleRules(role);
        ChainContextValue skillValue = skill(skill);
        ChainContextValue common = ChainContextValue.object(Map.of(
                "role", ChainContextValue.text(role.name()),
                "roleRules", rules.value(),
                "roleSchemaSha256", reference(schema.sha256()),
                "toolCatalogSha256", reference(role == ChainRole.PLANNER
                        ? tools.plannerSha256() : tools.sha256()),
                "skillSnapshotSha256", reference(skill.snapshotSha256()),
                "permissionSnapshotSha256", reference(permission.sha256()),
                "productBoundaryVersion", reference(
                        ProductChainPermissionPolicySource.POLICY_VERSION)));
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        values.put("foundation.roleRulesSchemaPermissionBoundaryAndSkills",
                common);
        switch (role) {
            case PLANNER -> planner(values, schema, tools, skillValue,
                    permission, productPolicy, profile, rules);
            case EXECUTOR -> executor(values, revision, schema, tools,
                    skillValue, permission, profile, rules);
            case REFLECTOR -> reflector(values, schema, skillValue,
                    permission, rules);
            case ANSWER -> answer(values, schema, rules);
        }
        return Map.copyOf(values);
    }

    static RuleProjection roleRules(ChainRole role) {
        Objects.requireNonNull(role, "role");
        ChainContextValue roleDefinition =
                ProductChainGenericRoleRules.definition(role);
        ChainContextValue value = ChainContextValue.object(Map.of(
                "rulesVersion", ChainContextValue.text(RULES_VERSION),
                "role", ChainContextValue.text(role.name()),
                "definition", roleDefinition));
        String canonical = ProductChainContractProjectionCodec
                .canonicalJson(value);
        return new RuleProjection(value,
                ProductChainContractProjectionCodec.sha256(canonical));
    }

    private static void planner(
            Map<String, ChainContextValue> values,
            ProductChainRoleSchemaSource.SchemaProjection schema,
            ProductChainToolContextValueCodec.Projection tools,
            ChainContextValue skill,
            ProductChainPermissionPolicySource.Projection permission,
            ProductChainPermissionPolicySource.Projection boundary,
            ExecutionProfile profile,
            RuleProjection rules) {
        values.put("rules.plannerSchema", schema.value());
        values.put("rules.capabilities", ChainContextValue.object(Map.of(
                "grantedCapabilities", strings(profile.capabilities().stream()
                        .map(Enum::name).sorted().toList()),
                "networkPolicy", ChainContextValue.text(
                        profile.networkPolicy().name()),
                "networkAllowlist", strings(profile.networkAllowlist().stream()
                        .sorted().toList()))));
        values.put("rules.toolCategories", tools.plannerValue());
        values.put("rules.skills", skill);
        values.put("rules.permissions", permission.value());
        values.put("rules.hardBoundary", ChainContextValue.object(Map.ofEntries(
                Map.entry("productBoundaryVersion", reference(
                        ProductChainPermissionPolicySource.POLICY_VERSION)),
                Map.entry("productBoundarySha256", reference(
                        boundary.sha256())),
                Map.entry("roleRules", definition(
                        rules, "planningBoundary")))));
    }

    private static void executor(
            Map<String, ChainContextValue> values,
            ContextRevisionRecord revision,
            ProductChainRoleSchemaSource.SchemaProjection schema,
            ProductChainToolContextValueCodec.Projection tools,
            ChainContextValue skill,
            ProductChainPermissionPolicySource.Projection permission,
            ExecutionProfile profile,
            RuleProjection rules) {
        if (revision.workspaceId() == null
                || revision.workspaceId().isBlank()) {
            throw ProductChainContextProjectionSupport.blocked(
                    io.paperagent.v2.chain.ChainContextModule
                            .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                    "Executor Workspace authority is unavailable");
        }
        values.put("rules.executorSchema", schema.value());
        values.put("rules.completeToolSchemas", tools.value());
        values.put("rules.skills", skill);
        values.put("rules.permissions", permission.value());
        String workspaceRef = "workspace:" + revision.workspaceId();
        ChainContextValue.ObjectValue pathRules = definition(
                rules, "pathRules");
        ChainContextValue pathRoot = ChainContextValue.object(Map.of(
                "workspaceRef", ChainContextValue.referencedText(
                        workspaceRef, workspaceRef),
                "pathNamespace", pathRules.values().get("pathNamespace"),
                "root", pathRules.values().get("root")));
        values.put("rules.workingDirectory", pathRoot);
        values.put("rules.writeScope", ChainContextValue.object(Map.of(
                "workspaceRef", ChainContextValue.referencedText(
                        workspaceRef, workspaceRef),
                "pathNamespace", pathRules.values().get("pathNamespace"),
                "root", pathRules.values().get("root"),
                "writeAllowed", ChainContextValue.bool(profile.capabilities()
                        .contains(Capability.WRITE_WORKSPACE)))));
    }

    private static void reflector(
            Map<String, ChainContextValue> values,
            ProductChainRoleSchemaSource.SchemaProjection schema,
            ChainContextValue skill,
            ProductChainPermissionPolicySource.Projection permission,
            RuleProjection rules) {
        values.put("rules.reflectorSchema", schema.value());
        values.put("rules.acceptanceRules", definition(
                rules, "acceptanceRules"));
        values.put("rules.permissions", permission.value());
        values.put("rules.skillAcceptanceRequirements", skill);
        values.put("rules.finalizationRules", definition(
                rules, "finalizationRules"));
    }

    private static void answer(
            Map<String, ChainContextValue> values,
            ProductChainRoleSchemaSource.SchemaProjection schema,
            RuleProjection rules) {
        values.put("rules.answerSchema", schema.value());
        values.put("rules.expressionRequirements", definition(
                rules, "expressionRequirements"));
        values.put("rules.noDiscoveryExecutionOrWrite", definition(
                rules, "noDiscoveryExecutionOrWrite"));
    }

    private static ChainContextValue.ObjectValue definition(
            RuleProjection rules, String name) {
        ChainContextValue.ObjectValue root = (ChainContextValue.ObjectValue)
                rules.value();
        ChainContextValue.ObjectValue definitions =
                (ChainContextValue.ObjectValue) root.values().get("definition");
        return (ChainContextValue.ObjectValue) definitions.values().get(name);
    }

    private static ChainContextValue skill(
            ProductChainTaskSkillSnapshot snapshot) {
        List<String> tools;
        try {
            tools = JSON.readValue(snapshot.allowedTools().json(),
                    new TypeReference<List<String>>() { });
        } catch (Exception invalid) {
            throw ProductChainContextProjectionSupport.blocked(
                    io.paperagent.v2.chain.ChainContextModule
                            .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                    "Skill tool authority cannot be decoded");
        }
        String snapshotRef = "task-skill:" + snapshot.taskId()
                + ":" + snapshot.snapshotSha256();
        return ChainContextValue.object(Map.of(
                "snapshotRef", ChainContextValue.referencedText(
                        snapshotRef, snapshotRef),
                "selectionKind", ChainContextValue.text(
                        snapshot.selectionKind().name()),
                "skillId", snapshot.skillId() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.text(snapshot.skillId()),
                "promptSha256", snapshot.promptSha256() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.referencedText(
                                snapshot.promptSha256(), snapshotRef),
                "prompt", snapshot.promptBody() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.referencedText(
                                snapshot.promptBody(), snapshotRef),
                "allowedTools", ChainContextValue.array(tools.stream()
                        .sorted().map(ChainContextValue::text).toList()),
                "allowedToolsSha256", ChainContextValue.referencedText(
                        snapshot.allowedTools().sha256(), snapshotRef)));
    }

    private static ChainContextValue reference(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static ChainContextValue.ArrayValue strings(
            List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ChainContextValue::text).toList());
    }

    record RuleProjection(ChainContextValue value, String sha256) {
        RuleProjection {
            Objects.requireNonNull(value, "value");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be canonical");
            }
        }
    }
}
