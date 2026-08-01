package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.api.skills.ResolvedSkill;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxCapabilityPolicyResolverTest {
    @Test void currentFeatureAndSkillPolicyAreBothRequiredAndRevocable() {
        SandboxExecutionProperties properties = new SandboxExecutionProperties();
        properties.setEnabled(true);
        SandboxCapabilityPolicyResolver resolver = new SandboxCapabilityPolicyResolver(properties);
        ResolvedToolPolicy base = new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project");
        assertThat(resolver.resolve(base, null).allowedTools()).contains("sandbox_execute");
        assertThat(resolver.resolve(base, new ResolvedSkill("skill", "", Set.of("project_read_file"))).allowedTools())
                .doesNotContain("sandbox_execute");
        properties.setEnabled(false);
        assertThat(resolver.resolve(base, null).allowedTools()).doesNotContain("sandbox_execute");
    }
}
