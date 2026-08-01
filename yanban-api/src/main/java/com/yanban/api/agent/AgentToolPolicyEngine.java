package com.yanban.api.agent;

import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolDescriptor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AgentToolPolicyEngine {

    private static final String SEARCH_KNOWLEDGE = "search_knowledge";
    private static final int GENERAL_MAX_TOOL_CALLS = 6;
    private static final int PROJECT_INITIAL_MAX_TOOL_CALLS = 12;
    private static final Set<String> LEGACY_CHAT_RESEARCH_PERMISSIONS = Set.of("research:web", "research:literature");
    private static final Set<String> HIDDEN_AGENT_TOOLS = Set.of(
            "literature_search_start",
            "literature_search_status",
            "literature_search_result",
            "literature_search_cancel"
    );

    private final ToolRegistry toolRegistry;
    @Autowired
    public AgentToolPolicyEngine(ToolRegistry toolRegistry,
                                 AgentLangChain4jTools langChain4jTools) {
        this.toolRegistry = toolRegistry;
    }

    AgentToolPolicyEngine(ToolRegistry toolRegistry) {
        this(toolRegistry, null);
    }

    public Decision decide(String userMessage, boolean ragDisabled, Set<String> skillAllowedTools) {
        return decide(new ToolPolicyRequest(
                ToolDescriptor.CapabilityProfile.CHAT,
                skillAllowedTools,
                LEGACY_CHAT_RESEARCH_PERMISSIONS,
                null,
                Set.of(ToolDescriptor.ResourceScope.SESSION, ToolDescriptor.ResourceScope.USER_KNOWLEDGE,
                        ToolDescriptor.ResourceScope.EXTERNAL),
                ragDisabled));
    }

    /** Project tools are visible only with the server-attested READ_ONLY capability. */
    public Decision decideProject(Set<String> skillAllowedTools, Set<String> stepAllowedTools) {
        return decide(new ToolPolicyRequest(
                ToolDescriptor.CapabilityProfile.PROJECT,
                skillAllowedTools,
                // Research executors additionally re-attest ProjectService READ_ONLY ownership.
                // This permission only makes a registered descriptor eligible; the resolved
                // skill/step allow-list remains the final model and execution boundary.
                Set.of("project:read", "research:project-read",
                        ProjectCandidateProposalToolExecutor.REQUIRED_PERMISSION),
                stepAllowedTools,
                Set.of(ToolDescriptor.ResourceScope.PROJECT),
                true));
    }

    /**
     * Resolves the exact allow-list shared by model exposure and execution. A null skill or
     * step allow-list inherits the preceding boundary; an empty list explicitly denies all.
     */
    public Decision decide(ToolPolicyRequest request) {
        if (request == null || request.capabilityProfile() == null) {
            return new Decision(List.of(), 0, 1, "missing_capability_profile");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (ToolDescriptor descriptor : toolRegistry.listDescriptors()) {
            if (isEligible(descriptor, request)) {
                allowed.add(descriptor.name());
            }
        }
        allowed = intersectIfExplicit(allowed, request.skillAllowedTools());
        allowed = intersectIfExplicit(allowed, request.stepAllowedTools());
        String reason = request.stepAllowedTools() != null ? "step_allowlist"
                : request.skillAllowedTools() != null ? "skill_allowlist"
                : request.ragDisabled() ? "llm_native_tool_routing_rag_disabled"
                : "llm_native_tool_routing";
        List<String> resolved = List.copyOf(allowed);
        int initialBudget = request.capabilityProfile() == ToolDescriptor.CapabilityProfile.PROJECT
                ? PROJECT_INITIAL_MAX_TOOL_CALLS : GENERAL_MAX_TOOL_CALLS;
        return new Decision(resolved, resolved.isEmpty() ? 0 : initialBudget, 1, reason);
    }

    private boolean isEligible(ToolDescriptor descriptor, ToolPolicyRequest request) {
        boolean projectSideEffectAllowed = request.capabilityProfile() != ToolDescriptor.CapabilityProfile.PROJECT
                || descriptor.sideEffectType() == ToolDescriptor.SideEffectType.NONE
                || descriptor.sideEffectType() == ToolDescriptor.SideEffectType.READ_ONLY
                || isCandidateProposalOnly(descriptor);
        return descriptor.modelVisible()
                && descriptor.sideEffectType() != ToolDescriptor.SideEffectType.UNKNOWN
                && projectSideEffectAllowed
                && descriptor.confirmationPolicy() == ToolDescriptor.ConfirmationPolicy.NEVER
                && descriptor.supportedProfiles().contains(request.capabilityProfile())
                && request.userPermissions().containsAll(descriptor.requiredPermissions())
                && request.availableResourceScopes().containsAll(descriptor.resourceScopes())
                && !HIDDEN_AGENT_TOOLS.contains(descriptor.name())
                && (!request.ragDisabled() || !SEARCH_KNOWLEDGE.equals(descriptor.name()));
    }

    private boolean isCandidateProposalOnly(ToolDescriptor descriptor) {
        return ProjectCandidateProposalToolExecutor.TOOL_NAME.equals(descriptor.name())
                && ProjectCandidateProposalToolExecutor.CAPABILITY_DOMAIN.equals(descriptor.capabilityDomain())
                && descriptor.sideEffectType() == ToolDescriptor.SideEffectType.CREATE
                && descriptor.requiredPermissions().equals(List.of(
                        ProjectCandidateProposalToolExecutor.REQUIRED_PERMISSION))
                && descriptor.resourceScopes().equals(List.of(ToolDescriptor.ResourceScope.PROJECT));
    }

    private Set<String> intersectIfExplicit(Set<String> candidates, Set<String> allowlist) {
        if (allowlist == null) {
            return candidates;
        }
        Set<String> resolved = new LinkedHashSet<>(candidates);
        resolved.retainAll(allowlist);
        return resolved;
    }

    public record ToolPolicyRequest(
            ToolDescriptor.CapabilityProfile capabilityProfile,
            Set<String> skillAllowedTools,
            Set<String> userPermissions,
            Set<String> stepAllowedTools,
            Set<ToolDescriptor.ResourceScope> availableResourceScopes,
            boolean ragDisabled
    ) {
        public ToolPolicyRequest {
            userPermissions = userPermissions == null ? Set.of() : Set.copyOf(userPermissions);
            availableResourceScopes = availableResourceScopes == null ? Set.of() : Set.copyOf(availableResourceScopes);
            skillAllowedTools = skillAllowedTools == null ? null : Set.copyOf(skillAllowedTools);
            stepAllowedTools = stepAllowedTools == null ? null : Set.copyOf(stepAllowedTools);
        }
    }

    public record Decision(
            List<String> allowedTools,
            int maxToolCalls,
            int maxDuplicateToolCalls,
            String reason
    ) {
        public Decision {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }

        public ResolvedToolPolicy resolved() {
            return new ResolvedToolPolicy(allowedTools, maxToolCalls, maxDuplicateToolCalls, reason);
        }
    }
}
