package com.yanban.api.agent.sandbox;

import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.api.agent.SandboxPlanAuthorityResolver;
import com.yanban.api.skills.ResolvedSkill;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/** Single current authority source used at Plan creation, recovery, execution and projection. */
@Service
public final class SandboxCapabilityPolicyResolver {
    private final SandboxExecutionProperties properties;

    public SandboxCapabilityPolicyResolver(SandboxExecutionProperties properties) { this.properties = properties; }

    public ResolvedToolPolicy resolve(ResolvedToolPolicy base, ResolvedSkill skill) {
        if (base == null) return null;
        boolean skillAllows = skill == null || skill.allowedTools().contains(SandboxPlanAuthorityResolver.TOOL_NAME);
        if (!properties.isEnabled() || !skillAllows) return withoutSandbox(base, "sandbox_authority_revoked");
        LinkedHashSet<String> tools = new LinkedHashSet<>(base.allowedTools());
        tools.add(SandboxPlanAuthorityResolver.TOOL_NAME);
        return new ResolvedToolPolicy(List.copyOf(tools), Math.max(1, base.maxToolCalls()),
                Math.max(1, base.maxDuplicateToolCalls()), base.reason() + "+governed_sandbox");
    }

    public boolean currentlyAllows(ResolvedToolPolicy policy) {
        return properties.isEnabled() && policy != null
                && policy.allowedTools().contains(SandboxPlanAuthorityResolver.TOOL_NAME);
    }

    private ResolvedToolPolicy withoutSandbox(ResolvedToolPolicy base, String reason) {
        return new ResolvedToolPolicy(base.allowedTools().stream()
                .filter(tool -> !SandboxPlanAuthorityResolver.TOOL_NAME.equals(tool)).toList(),
                base.maxToolCalls(), base.maxDuplicateToolCalls(), reason);
    }
}
