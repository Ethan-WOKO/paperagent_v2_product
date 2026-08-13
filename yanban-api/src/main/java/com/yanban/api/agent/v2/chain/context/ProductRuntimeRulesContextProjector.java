package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects module 12 from versioned policy and persisted runtime authority. */
@Component
public final class ProductRuntimeRulesContextProjector
        implements ProductChainContextAuthorityReader {
    static final String PROJECTION_VERSION =
            "product-runtime-rules-projector-v3";
    private static final ChainContextModule MODULE =
            ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS;

    private final ChainWorkflowRepository workflow;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductChainSkillSnapshotRepository skills;

    public ProductRuntimeRulesContextProjector(
            ChainWorkflowRepository workflow,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainSkillSnapshotRepository skills) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        var revision = request.buildingRevision();
        ProductChainTaskSkillSnapshot skill = skills.findByTaskId(
                        revision.taskId())
                .orElseThrow(() -> blocked("Task Skill snapshot is missing"));
        Authority authority = authority(revision);
        ProductChainRoleSchemaSource.SchemaProjection schema =
                ProductChainRoleSchemaSource.schema(
                        revision.role(), revision.callReason());
        ProductChainPermissionPolicySource.Projection productPolicy =
                ProductChainPermissionPolicySource.policy();
        ProductChainPermissionPolicySource.Projection permission =
                authority.productPolicyView()
                        ? productPolicy
                        : ProductChainPermissionPolicySource.profile(
                                authority.profile());
        ProductChainToolContextProjection.Projection toolProjection =
                authority.policyToolView()
                        ? ProductChainToolContextProjection.projectPolicy(
                                authority.permissionAuthorityRef(),
                                authority.profile(), skill)
                        : ProductChainToolContextProjection.project(
                                authority.taskFrame(), skill);
        ProductChainToolContextValueCodec.Projection tools =
                ProductChainToolContextValueCodec.encode(toolProjection);
        Map<String, ChainContextValue> fields =
                ProductChainRuntimeRuleValues.fields(
                        revision, schema, tools, permission,
                        productPolicy, skill, authority.profile());
        ProductChainRuntimeRuleValues.RuleProjection roleRules =
                ProductChainRuntimeRuleValues.roleRules(revision.role());

        String permissionDecisionRef = workflow.findPermissionDecisions(
                        revision.taskId()).stream()
                .reduce((left, right) -> right)
                .map(value -> value.permissionDecisionId()
                        + "@" + value.eventId())
                .orElse("NONE");
        String permissionAuthorityRef = authority.permissionAuthorityRef();
        Map<String, ChainContextValue> sourceVersion = Map.of(
                "contractSchema", referenced(
                        ProductChainContractProjectionCodec.SCHEMA_VERSION),
                "rolePrompt", ChainContextValue.object(Map.of(
                        "version", referenced(
                                ProductChainRuntimeRuleValues.RULES_VERSION),
                        "sha256", referenced(roleRules.sha256()),
                        "rules", roleRules.value())),
                "providerSchema", referenced(
                        ProviderRoleOutput.SCHEMA_VERSION),
                "toolCatalogDigest", referenced(
                        revision.role() == ChainRole.PLANNER
                                ? tools.plannerSha256() : tools.sha256()),
                "skillVersion", referenced(skill.snapshotSha256()),
                "permissionSnapshot", referenced(permission.sha256()),
                "productBoundaryVersion", referenced(
                        ProductChainPermissionPolicySource.POLICY_VERSION));
        Map<String, ChainContextValue> readBoundary = Map.of(
                "role", ChainContextValue.text(revision.role().name()),
                "authenticatedPermissionCut", ChainContextValue.object(Map.of(
                        "authorityRef", referenced(permissionAuthorityRef),
                        "permissionDecisionRef", referenced(
                                permissionDecisionRef),
                        "skillSnapshotRef", referenced(
                                skill.snapshotSha256()))));
        Map<String, ChainContextValue> parameters = Map.of(
                "taskId", ChainContextValue.referencedText(
                        revision.taskId(), revision.taskId()),
                "permissionAuthorityRef", referenced(
                        permissionAuthorityRef),
                "role", ChainContextValue.text(revision.role().name()));
        List<String> required = request.requiredFields(MODULE);
        return ProductChainContextProjectionSupport.present(
                MODULE, sourceVersion, readBoundary,
                PROJECTION_VERSION, "none-v1", parameters, fields,
                required.toArray(String[]::new));
    }

    private Authority authority(
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .ContextRevisionRecord revision) {
        if (revision.taskFrameId() == null) {
            if (ProductDirectAnswerContextAuthority.isDirectAnswer(revision)) {
                var route = ProductDirectAnswerContextAuthority.require(
                        revision, workflow);
                return new Authority(null,
                        ProductChainPermissionPolicySource
                                .directAnswerProfile(),
                        false, true, "route:" + route.routeDecisionId());
            }
            if (revision.role() != ChainRole.PLANNER
                    || revision.planId() != null
                    || revision.planRevisionId() != null) {
                throw blocked("TaskFrame and Plan identity is incomplete");
            }
            return new Authority(null,
                    ProductChainPermissionPolicySource.planningProfile(),
                    true, true, "permission-policy:"
                    + ProductChainPermissionPolicySource.POLICY_VERSION);
        }
        if (revision.planId() == null || revision.planRevisionId() == null
                || revision.planRevisionNumber() == null) {
            throw blocked("TaskFrame and Plan identity is incomplete");
        }
        var bindings = workflow.findPlanBindings(revision.taskId()).stream()
                .filter(value -> value.taskFrameId().equals(
                        revision.taskFrameId()))
                .filter(value -> value.planId().equals(revision.planId()))
                .filter(value -> value.planRevisionId().equals(
                        revision.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == revision.planRevisionNumber())
                .toList();
        if (bindings.size() != 1) {
            throw blocked("exact TaskFrame and Plan binding is unavailable");
        }
        PersistedPlanBootstrap bootstrap = bootstraps.find(
                        new PlanId(revision.planId()))
                .orElseThrow(() -> blocked("Plan bootstrap is unavailable"));
        TaskFrame frame = bootstrap.taskFrame();
        if (!frame.id().value().equals(revision.taskFrameId())
                || !bootstrap.plan().taskFrameId().equals(frame.id())
                || !bootstrap.plan().id().value().equals(revision.planId())) {
            throw blocked("Plan bootstrap identity does not match Context");
        }
        return new Authority(frame, frame.executionProfile(), false,
                false, frame.id().value());
    }

    private static ChainContextValue referenced(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    private record Authority(
            TaskFrame taskFrame,
            ExecutionProfile profile,
            boolean productPolicyView,
            boolean policyToolView,
            String permissionAuthorityRef) {
        private Authority {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(permissionAuthorityRef,
                    "permissionAuthorityRef");
            if (policyToolView == (taskFrame != null)) {
                throw new IllegalArgumentException(
                        "policy tool authority and TaskFrame are exclusive");
            }
        }
    }
}
