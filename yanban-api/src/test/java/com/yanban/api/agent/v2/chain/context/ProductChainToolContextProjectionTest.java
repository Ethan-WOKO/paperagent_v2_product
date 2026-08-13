package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainToolContextProjectionTest {
    @Test
    void projectsEveryGrantedFormalSchemaInStableToolIdOrder() {
        var projection = ProductChainToolContextProjection.project(frame(
                Set.of(Capability.values()),
                NetworkPolicy.ALLOWLIST_ONLY,
                List.of("product-literature-search")));

        List<String> expectedIds = V2ProductToolCatalog.entries().stream()
                .map(entry -> entry.descriptor().id().value())
                .sorted()
                .toList();
        assertEquals(ProductChainToolContextProjection.SCHEMA_VERSION,
                projection.schemaVersion());
        assertEquals("task-frame.tool-context", projection.taskFrameRef());
        assertEquals("SANDBOX_STANDARD", projection.permissionTierRef());
        assertEquals(expectedIds, projection.completeToolSchemas().stream()
                .map(schema -> schema.descriptor().id().value())
                .toList());
        assertEquals(
                List.of(
                        "permission.literature-network-external",
                        "permission.project-read",
                        "permission.project-write",
                        "permission.sandbox-execute-install"),
                projection.permissionRefs());
        assertEquals(V2ProductToolCatalog.entries().size(),
                projection.completeToolSchemas().size());
        assertTrue(projection.summary().startsWith(
                V2ProductToolCatalog.entries().size()
                        + " product tools granted by the frozen permission authority and profile: "));

        for (var schema : projection.completeToolSchemas()) {
            var entry = V2ProductToolCatalog.entry(schema.descriptor().id())
                    .orElseThrow();
            assertSame(entry.descriptor(), schema.descriptor());
            assertEquals(entry.publicAlias(), schema.publicAlias());
            assertEquals(entry.publicDescription(), schema.summary());
            assertEquals(entry.permissionRef(), schema.permissionRef());
            assertEquals(entry.executionTarget(), schema.executionTarget());
            assertEquals(entry.routingRequirements().stream()
                            .sorted(Comparator.comparing(Enum::name)).toList(),
                    schema.routingRequirements());
        }
    }

    @Test
    void intersectsProjectSchemasWithFrozenCapabilities() {
        var readOnly = ProductChainToolContextProjection.project(frame(
                Set.of(Capability.READ_PROJECT),
                NetworkPolicy.DENY_ALL,
                List.of()));

        assertEquals(14, readOnly.completeToolSchemas().size());
        assertTrue(readOnly.completeToolSchemas().stream().allMatch(schema ->
                schema.descriptor().id().value().startsWith("project.")
                        && !schema.descriptor().id().value().equals(
                        "project.candidate.compose")));
        assertEquals(List.of("permission.project-read"),
                readOnly.permissionRefs());

        var projectWrite = ProductChainToolContextProjection.project(frame(
                Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                NetworkPolicy.DENY_ALL,
                List.of()));
        assertTrue(projectWrite.completeToolSchemas().stream().anyMatch(
                schema -> schema.descriptor().id().value().equals(
                        "project.candidate.compose")));
        assertEquals(
                List.of("permission.project-read", "permission.project-write"),
                projectWrite.permissionRefs());

        var writeWithoutRead = ProductChainToolContextProjection.project(frame(
                Set.of(Capability.WRITE_WORKSPACE),
                NetworkPolicy.DENY_ALL,
                List.of()));
        assertTrue(writeWithoutRead.completeToolSchemas().isEmpty());
    }

    @Test
    void appliesFormalNetworkPolicyAndAllowlistWithoutToolIdSpecialCases() {
        Set<Capability> networkCapabilities = Set.of(
                Capability.ACCESS_NETWORK,
                Capability.INVOKE_EXTERNAL_TOOL);
        var wrongAuthority = ProductChainToolContextProjection.project(frame(
                networkCapabilities,
                NetworkPolicy.ALLOWLIST_ONLY,
                List.of("some-other-network-authority")));
        assertTrue(wrongAuthority.completeToolSchemas().isEmpty());

        var granted = ProductChainToolContextProjection.project(frame(
                networkCapabilities,
                NetworkPolicy.ALLOWLIST_ONLY,
                List.of("product-literature-search")));
        assertEquals(List.of("literature.search"),
                granted.completeToolSchemas().stream()
                        .map(schema -> schema.descriptor().id().value())
                        .toList());
        assertEquals(List.of("product-literature-search"),
                granted.completeToolSchemas().get(0)
                        .requiredNetworkAllowlistEntries());
        assertEquals(List.of("permission.literature-network-external"),
                granted.permissionRefs());
    }

    @Test
    void exposesOnlySandboxWhenOnlyExecutionCapabilitiesAreGranted() {
        var projection = ProductChainToolContextProjection.project(frame(
                Set.of(Capability.EXECUTE_COMMAND,
                        Capability.INSTALL_DEPENDENCY),
                NetworkPolicy.DENY_ALL,
                List.of()));

        assertEquals(List.of("sandbox.execute"),
                projection.completeToolSchemas().stream()
                        .map(schema -> schema.descriptor().id().value())
                        .toList());
        assertEquals(List.of("permission.sandbox-execute-install"),
                projection.permissionRefs());
        assertFalse(projection.completeToolSchemas().get(0)
                .descriptor().parameterSchema().values().isEmpty());
    }

    @Test
    void emptyGrantHasStableSummaryAndNoPermissionRefs() {
        var projection = ProductChainToolContextProjection.project(frame(
                Set.of(), NetworkPolicy.DENY_ALL, List.of()));

        assertTrue(projection.completeToolSchemas().isEmpty());
        assertTrue(projection.permissionRefs().isEmpty());
        assertEquals("No product tools are granted by the frozen permission authority and profile.",
                projection.summary());
    }

    @Test
    void selectedSkillNarrowsAfterProfileUsingAliasOrFormalToolId() {
        ProductChainTaskSkillSnapshot skill =
                ProductChainTaskSkillSnapshot.selected(
                        "task.tools", "instruction.tools", "build",
                        "Use the selected tools.",
                        List.of("project_read", "sandbox.execute"),
                        Instant.parse("2026-08-08T00:00:00Z"));

        var projection = ProductChainToolContextProjection.project(frame(
                Set.of(Capability.READ_PROJECT,
                        Capability.EXECUTE_COMMAND,
                        Capability.INSTALL_DEPENDENCY),
                NetworkPolicy.DENY_ALL, List.of()), skill);

        assertEquals(List.of("project.read", "sandbox.execute"),
                projection.completeToolSchemas().stream()
                        .map(schema -> schema.descriptor().id().value())
                        .toList());
    }

    @Test
    void selectedSkillWithEmptyAllowedToolsIsDenyAllWhileNoSkillInherits() {
        TaskFrame frame = frame(Set.of(Capability.READ_PROJECT),
                NetworkPolicy.DENY_ALL, List.of());
        ProductChainTaskSkillSnapshot noSkill =
                ProductChainTaskSkillSnapshot.none(
                        "task.none", "instruction.none",
                        Instant.parse("2026-08-08T00:00:00Z"));
        ProductChainTaskSkillSnapshot denyAllSkill =
                ProductChainTaskSkillSnapshot.selected(
                        "task.empty", "instruction.empty", "read-none",
                        "Do not expose tools.", List.of(),
                        Instant.parse("2026-08-08T00:00:00Z"));

        assertEquals(14, ProductChainToolContextProjection.project(
                frame, noSkill).completeToolSchemas().size());
        assertTrue(ProductChainToolContextProjection.project(
                frame, denyAllSkill).completeToolSchemas().isEmpty());
    }

    @Test
    void skillCannotExpandProfileAndUnknownIdentifierBlocksContext() {
        TaskFrame readOnly = frame(Set.of(Capability.READ_PROJECT),
                NetworkPolicy.DENY_ALL, List.of());
        ProductChainTaskSkillSnapshot sandbox =
                ProductChainTaskSkillSnapshot.selected(
                        "task.sandbox", "instruction.sandbox", "sandbox",
                        "Run commands.", List.of("sandbox_execute"),
                        Instant.parse("2026-08-08T00:00:00Z"));
        ProductChainTaskSkillSnapshot unknown =
                ProductChainTaskSkillSnapshot.selected(
                        "task.unknown", "instruction.unknown", "unknown",
                        "Use a missing tool.", List.of("not_a_formal_tool"),
                        Instant.parse("2026-08-08T00:00:00Z"));

        assertTrue(ProductChainToolContextProjection.project(
                readOnly, sandbox).completeToolSchemas().isEmpty());
        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> ProductChainToolContextProjection.project(
                        readOnly, unknown));
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static TaskFrame frame(
            Set<Capability> capabilities,
            NetworkPolicy networkPolicy,
            List<String> networkAllowlist) {
        return new TaskFrame(
                new TaskFrameId("task-frame.tool-context"),
                "Use only the frozen authorities.",
                List.of("project"),
                List.of("verified result"),
                List.of(),
                Optional.of(new ProjectVersionRef(
                        "project-tool-context", "version-tool-context")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        capabilities,
                        networkPolicy,
                        networkAllowlist,
                        new ResourceLimits(
                                Duration.ofMinutes(1),
                                Duration.ofSeconds(30),
                                256L * 1024 * 1024,
                                1024 * 1024,
                                4),
                        Set.of()),
                Instant.parse("2026-08-08T00:00:00Z"));
    }
}
