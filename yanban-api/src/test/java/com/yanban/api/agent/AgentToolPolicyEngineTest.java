package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentToolPolicyEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesGeneralAgentToolsWithoutKeywordFiltering() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("西瓜的功效", false, null);

        assertThat(decision.allowedTools()).containsExactly(
                "search_web",
                "recommend_literature",
                "search_knowledge",
                "paper_polish_status",
                "paper_polish_result",
                "paper_task_cancel"
        );
        assertThat(decision.reason()).isEqualTo("llm_native_tool_routing");
        assertThat(decision.maxToolCalls()).isEqualTo(6);
    }

    @Test
    void honorsRagDisabledForKnowledgeTool() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("根据我上传的文档总结要点", true, null);

        assertThat(decision.allowedTools()).doesNotContain("search_knowledge");
        assertThat(decision.reason()).isEqualTo("llm_native_tool_routing_rag_disabled");
    }

    @Test
    void selectedSkillAllowlistStillRestrictsVisibleTools() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide("西瓜的功效", false, Set.of("search_web", "missing_tool"));

        assertThat(decision.allowedTools()).containsExactly("search_web");
        assertThat(decision.reason()).isEqualTo("skill_allowlist");
        assertThat(decision.maxToolCalls()).isEqualTo(6);
    }

    @Test
    void selectedSkillAllowlistCannotExposeHiddenLiteratureAsyncTools() {
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry());

        AgentToolPolicyEngine.Decision decision = engine.decide(
                "find papers",
                false,
                Set.of("recommend_literature", "literature_search_start", "literature_search_status", "literature_search_result", "literature_search_cancel")
        );

        assertThat(decision.allowedTools()).containsExactly("recommend_literature");
        assertThat(decision.reason()).isEqualTo("skill_allowlist");
        assertThat(decision.maxToolCalls()).isEqualTo(6);
    }

    @Test
    void normalReactDecisionProducesANonNullResolvedPolicy() {
        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry()).decide("search", false, null);

        assertThat(decision.resolved().allowedTools()).isEqualTo(decision.allowedTools());
        assertThat(decision.resolved().maxToolCalls()).isEqualTo(decision.maxToolCalls());
    }

    @Test
    void emptySkillAllowlistDeniesAll() {
        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry())
                .decide("search", false, Set.of());

        assertThat(decision.allowedTools()).isEmpty();
        assertThat(decision.maxToolCalls()).isZero();
        assertThat(decision.reason()).isEqualTo("skill_allowlist");
    }

    @Test
    void resolvesCapabilitySkillPermissionAndStepByIntersection() {
        ToolRegistry registry = new ToolRegistry()
                .register(new StubTool("project_read", ToolDescriptor.CapabilityProfile.PROJECT,
                        Set.of("project:read"), Set.of(ToolDescriptor.ResourceScope.PROJECT), true,
                        ToolDescriptor.SideEffectType.NONE))
                .register(new StubTool("chat_search", ToolDescriptor.CapabilityProfile.CHAT,
                        Set.of(), Set.of(ToolDescriptor.ResourceScope.EXTERNAL), true,
                        ToolDescriptor.SideEffectType.NONE));

        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry).decide(
                new AgentToolPolicyEngine.ToolPolicyRequest(
                        ToolDescriptor.CapabilityProfile.PROJECT,
                        Set.of("project_read", "chat_search"),
                        Set.of("project:read"),
                        Set.of("project_read"),
                        Set.of(ToolDescriptor.ResourceScope.PROJECT), false));

        assertThat(decision.allowedTools()).containsExactly("project_read");
        assertThat(decision.reason()).isEqualTo("step_allowlist");
    }

    @Test
    void projectPolicyExposesAllReadOnlyToolsButNotFutureSideEffects() {
        ToolRegistry registry = new ToolRegistry()
                .register(new StubTool("project_read", ToolDescriptor.CapabilityProfile.PROJECT,
                        Set.of("project:read"), Set.of(ToolDescriptor.ResourceScope.PROJECT), true,
                        ToolDescriptor.SideEffectType.NONE))
                .register(new StubTool("project_audit", ToolDescriptor.CapabilityProfile.PROJECT,
                        Set.of("research:project-read"), Set.of(ToolDescriptor.ResourceScope.PROJECT), true,
                        ToolDescriptor.SideEffectType.READ_ONLY))
                .register(new StubTool("project_write", ToolDescriptor.CapabilityProfile.PROJECT,
                        Set.of("project:read"), Set.of(ToolDescriptor.ResourceScope.PROJECT), true,
                        ToolDescriptor.SideEffectType.MODIFY));

        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry).decideProject(null, null);

        assertThat(decision.allowedTools()).containsExactly("project_read", "project_audit");
        assertThat(decision.allowedTools()).doesNotContain("project_write");
        assertThat(decision.maxToolCalls()).isEqualTo(12);
    }

    @Test
    void projectPolicyAllowsOnlyTheExactProposalCreateDescriptorAndChatCannotSeeIt() {
        ToolRegistry registry = new ToolRegistry()
                .register(candidateProposalStub(ProjectCandidateProposalToolExecutor.TOOL_NAME,
                        ProjectCandidateProposalToolExecutor.CAPABILITY_DOMAIN,
                        ProjectCandidateProposalToolExecutor.REQUIRED_PERMISSION))
                .register(candidateProposalStub("project_write_candidate", "project-write", "project:read"));
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry);

        assertThat(engine.decideProject(null, null).allowedTools())
                .containsExactly(ProjectCandidateProposalToolExecutor.TOOL_NAME);
        assertThat(engine.decide("propose", true,
                Set.of(ProjectCandidateProposalToolExecutor.TOOL_NAME)).allowedTools()).isEmpty();
        assertThat(engine.decideProject(Set.of(), null).allowedTools()).isEmpty();
        assertThat(engine.decideProject(Set.of(ProjectCandidateProposalToolExecutor.TOOL_NAME), Set.of()).allowedTools())
                .isEmpty();
    }

    @Test
    void unknownSideEffectsAndHiddenInternalNamesFailClosed() {
        ToolRegistry registry = new ToolRegistry()
                .register(new StubTool("unknown", ToolDescriptor.CapabilityProfile.CHAT,
                        Set.of(), Set.of(), true, ToolDescriptor.SideEffectType.UNKNOWN))
                .register(new StubTool("literature_search_status", ToolDescriptor.CapabilityProfile.CHAT,
                        Set.of(), Set.of(), true, ToolDescriptor.SideEffectType.NONE));

        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry).decide("status", false,
                Set.of("unknown", "literature_search_status"));

        assertThat(decision.allowedTools()).isEmpty();
    }

    @Test
    void legacyChatKeepsAuthorizedResearchToolsWhileHidingInternalAndUnconfirmedTools() {
        ToolRegistry registry = new ToolRegistry()
                .register(catalogBackedStub("search_web"))
                .register(catalogBackedStub("recommend_literature"))
                .register(catalogBackedStub("search_knowledge"))
                .register(catalogBackedStub("paper_task_cancel"))
                .register(catalogBackedStub("literature_search_start"))
                .register(catalogBackedStub("literature_search_status"))
                .register(catalogBackedStub("literature_search_result"));

        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry).decide("research", false, null);

        assertThat(decision.allowedTools()).containsExactly("search_web", "recommend_literature", "search_knowledge");
    }

    @Test
    void confirmationAndMissingPermissionFailClosedBeforeCoordinatorExists() {
        ToolRegistry registry = new ToolRegistry()
                .register(new StubTool("write", ToolDescriptor.CapabilityProfile.CHAT, Set.of(), Set.of(), true,
                        ToolDescriptor.SideEffectType.MODIFY, ToolDescriptor.ConfirmationPolicy.ON_SIDE_EFFECT))
                .register(new StubTool("external", ToolDescriptor.CapabilityProfile.CHAT, Set.of("external:search"),
                        Set.of(), true, ToolDescriptor.SideEffectType.NONE));

        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry).decide(
                new AgentToolPolicyEngine.ToolPolicyRequest(ToolDescriptor.CapabilityProfile.CHAT, null, Set.of(), null,
                        Set.of(), false));

        assertThat(decision.allowedTools()).isEmpty();
    }

    @Test
    void oneVisibleToolKeepsIndependentMultiCallBudget() {
        ToolRegistry registry = new ToolRegistry().register(new StubTool("poll", ToolDescriptor.CapabilityProfile.CHAT,
                Set.of(), Set.of(), true, ToolDescriptor.SideEffectType.NONE));

        AgentToolPolicyEngine.Decision decision = new AgentToolPolicyEngine(registry).decide("poll", false, null);

        assertThat(decision.allowedTools()).containsExactly("poll");
        assertThat(decision.maxToolCalls()).isEqualTo(6);
    }

    @Test
    void researchToolsAreProjectOnlyAndStillRequireResolvedAllowListIntersection() {
        com.yanban.api.project.ProjectService projects = org.mockito.Mockito.mock(com.yanban.api.project.ProjectService.class);
        ToolRegistry registry = new ToolRegistry()
                .register(new ProjectLatexOutlineToolExecutor(projects, objectMapper))
                .register(new ProjectBibtexAuditToolExecutor(projects, objectMapper))
                .register(new ProjectCodeSymbolsToolExecutor(projects, objectMapper))
                .register(new ProjectExperimentSummaryToolExecutor(projects, objectMapper))
                .register(new ProjectCrossMaterialSearchToolExecutor(projects, objectMapper));
        AgentToolPolicyEngine engine = new AgentToolPolicyEngine(registry);

        assertThat(engine.decide("research", false, Set.of("project_latex_outline")).allowedTools()).isEmpty();
        assertThat(engine.decideProject(Set.of("project_latex_outline", "project_bibtex_audit"),
                Set.of("project_bibtex_audit")).allowedTools()).containsExactly("project_bibtex_audit");
        assertThat(engine.decideProject(Set.of("project_latex_outline", "project_bibtex_audit", "project_code_symbols",
                "project_experiment_summary", "project_cross_material_search"), null).allowedTools())
                .containsExactly("project_latex_outline", "project_bibtex_audit", "project_code_symbols",
                        "project_experiment_summary", "project_cross_material_search");
        assertThat(engine.decideProject(Set.of(), null).allowedTools()).isEmpty();
    }

    private ToolRegistry registry() {
        return new ToolRegistry()
                .register(new StubTool("search_web"))
                .register(new StubTool("recommend_literature"))
                .register(new StubTool("search_knowledge"))
                .register(new StubTool("literature_search_start"))
                .register(new StubTool("literature_search_status"))
                .register(new StubTool("literature_search_result"))
                .register(new StubTool("literature_search_cancel"))
                .register(new StubTool("paper_polish_status"))
                .register(new StubTool("paper_polish_result"))
                .register(new StubTool("paper_task_cancel"))
                .register(new StubTool("echo"))
                .register(new StubTool("mcp_private"));
    }

    private ToolExecutor catalogBackedStub(String name) {
        ToolDefinition definition = new ToolDefinition(name, "catalog " + name, objectMapper.createObjectNode().put("type", "object"));
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
            }
        };
    }

    private ToolExecutor candidateProposalStub(String name, String domain, String permission) {
        ToolDefinition definition = new ToolDefinition(name,
                "candidate proposal", objectMapper.createObjectNode().put("type", "object"));
        return new ToolExecutor() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "v1", domain,
                        List.of(ToolDescriptor.CapabilityProfile.PROJECT), List.of(permission),
                        List.of(ToolDescriptor.ResourceScope.PROJECT), ToolDescriptor.SideEffectType.CREATE,
                        ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
            }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
            }
        };
    }

    private class StubTool implements ToolExecutor {
        private final ToolDefinition definition;
        private final ToolDescriptor descriptor;

        private StubTool(String name) {
            this(name, ToolDescriptor.CapabilityProfile.CHAT, Set.of(), Set.of(),
                    !"echo".equals(name) && !name.startsWith("mcp_"), ToolDescriptor.SideEffectType.NONE);
        }

        private StubTool(String name, ToolDescriptor.CapabilityProfile profile, Set<String> permissions,
                         Set<ToolDescriptor.ResourceScope> scopes, boolean modelVisible,
                         ToolDescriptor.SideEffectType sideEffectType) {
            this(name, profile, permissions, scopes, modelVisible, sideEffectType, ToolDescriptor.ConfirmationPolicy.NEVER);
        }

        private StubTool(String name, ToolDescriptor.CapabilityProfile profile, Set<String> permissions,
                         Set<ToolDescriptor.ResourceScope> scopes, boolean modelVisible,
                         ToolDescriptor.SideEffectType sideEffectType,
                         ToolDescriptor.ConfirmationPolicy confirmationPolicy) {
            this.definition = new ToolDefinition(
                    name,
                    "stub " + name,
                    objectMapper.createObjectNode().put("type", "object")
            );
            this.descriptor = new ToolDescriptor(name, "v-test", "test", List.of(profile), List.copyOf(permissions),
                    List.copyOf(scopes), sideEffectType, confirmationPolicy,
                    ToolDescriptor.AsyncMode.SYNC, ToolDescriptor.IdempotencyPolicy.NONE,
                    ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, modelVisible);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
        }
    }
}
