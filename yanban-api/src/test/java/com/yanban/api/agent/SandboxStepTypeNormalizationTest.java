package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import com.yanban.sandbox.contract.SandboxCommandProfiles;
import com.yanban.core.agent.AgentPlanStep;
import org.junit.jupiter.api.Test;

class SandboxStepTypeNormalizationTest {

    @Test
    void projectSandboxDispatchUsesStableProjectAuthorityInsteadOfSingleStepLimit() {
        ResolvedToolPolicy step = new ResolvedToolPolicy(List.of("sandbox_execute"), 1, 1, "single_step");
        ResolvedToolPolicy project = new ResolvedToolPolicy(List.of("project_read_file", "sandbox_execute"),
                12, 1, "project_authority");

        assertThat(PlanAgentService.sandboxDispatchAuthorityPolicy(true, step, project)).isSameAs(project);
        assertThat(PlanAgentService.sandboxDispatchAuthorityPolicy(false, step, project)).isSameAs(step);
    }
    @Test
    void upgradesExplicitRunStepWhenSandboxToolWasAlreadyAuthorized() {
        var step = step("ANALYSIS", List.of("project_read_file", "sandbox_execute"),
                "Read the Java source and report its output");

        assertThat(PlanAgentService.governedSandboxStepType(
                true, true, "\u8bf7\u7f16\u8bd1\u5e76\u8fd0\u884c src/main/java/xhs_1111.java", step))
                .isEqualTo("SANDBOX_EXECUTE");
    }

    @Test
    void doesNotWidenReadOnlyOrDisabledPlans() {
        var readOnly = step("ANALYSIS", List.of("project_read_file"), "Inspect source code");
        var suggestedSandbox = step("ANALYSIS", List.of("sandbox_execute"), "Inspect source code");

        assertThat(PlanAgentService.governedSandboxStepType(true, true, "Inspect source", readOnly))
                .isEqualTo("ANALYSIS");
        assertThat(PlanAgentService.governedSandboxStepType(false, true, "Run source", suggestedSandbox))
                .isEqualTo("ANALYSIS");
        assertThat(PlanAgentService.governedSandboxStepType(true, false, "Run source", suggestedSandbox))
                .isEqualTo("ANALYSIS");
        assertThat(PlanAgentService.governedSandboxStepType(true, true, "Inspect source", suggestedSandbox))
                .isEqualTo("ANALYSIS");
    }

    @Test
    void selectsSingleFileJavaSourceModeWithoutShell() {
        assertThat(PlanAgentService.governedSandboxCommand(
                java.util.Set.of("src/main/java/xhs_1111.java")))
                .containsExactly("java", "src/main/java/xhs_1111.java");
        assertThat(PlanAgentService.governedSandboxCommand(
                java.util.Set.of("pom.xml", "src/main/java/App.java")))
                .containsExactly("mvn", "-o", "test");
        assertThat(PlanAgentService.governedSandboxCommand(
                java.util.Set.of("src/main/java/Other.java", "src/main/java/xhs_1111.java"),
                "Run src/main/java/xhs_1111.java and return stdout"))
                .containsExactly("java", "src/main/java/xhs_1111.java");
        assertThatThrownBy(() -> PlanAgentService.governedSandboxCommand(
                java.util.Set.of("src/main/java/Other.java", "src/main/java/xhs_1111.java"),
                "Run both src/main/java/Other.java and src/main/java/xhs_1111.java"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "src/main/java/xhs_1111.java"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "-cp", "src/main/java", "xhs_1111")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "../xhs_1111.java")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesTrustedJavaPathCaseAndCollapsesTitleBasenameAlias() {
        AgentPlanStep step = new AgentPlanStep(1L, "sandbox", 1, "Run LRUCache.java",
                "Run src/main/java/LRUCache.java in sandbox", "SANDBOX_EXECUTE", "[]",
                "[\"sandbox_execute\"]", "Report src/main/java/LRUCache.java output");

        var paths = PlanAgentService.requiredSandboxMaterialPaths(step);

        assertThat(paths).containsExactly("src/main/java/LRUCache.java");
        assertThat(PlanAgentService.governedSandboxCommand(paths, step.getDescription()))
                .containsExactly("java", "src/main/java/LRUCache.java");
        assertThatCode(() -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "src/main/java/LRUCache.java"))).doesNotThrowAnyException();
    }

    @Test
    void doesNotTreatGeneratedClassArtifactAsCSourceMaterial() {
        AgentPlanStep step = new AgentPlanStep(1L, "sandbox", 1, "Compile Worker23Calculator.java",
                "Compile Worker23Calculator.java with javac", "SANDBOX_EXECUTE", "[]",
                "[\"sandbox_execute\"]", "Generate Worker23Calculator.class");

        assertThat(PlanAgentService.requiredSandboxMaterialPaths(step))
                .containsExactly("Worker23Calculator.java");
    }

    @Test
    void reusesOneExplicitGoalSourceForAPathlessFollowUpSandboxStep() {
        AgentPlanStep step = new AgentPlanStep(1L, "run", 2, "Run Worker23Calculator",
                "Run the compiled Worker23Calculator class", "SANDBOX_EXECUTE", "[\"compile\"]",
                "[\"sandbox_execute\"]", "Capture stdout and stderr");

        assertThat(PlanAgentService.requiredSandboxMaterialPaths(step,
                "Compile and run Worker23Calculator.java after confirmation"))
                .containsExactly("Worker23Calculator.java");
        assertThat(PlanAgentService.requiredSandboxMaterialPaths(step,
                "Run First.java and Second.java"))
                .isEmpty();
    }

    @Test
    void missingSandboxTargetIsAnInvalidPathInsteadOfInfrastructureUnavailability() {
        assertThat(PlanAgentService.sandboxTargetValidationError(Set.of()))
                .isEqualTo("INVALID_PATH: an explicit Project-relative sandbox target is required.");
        assertThat(PlanAgentService.sandboxTargetValidationError(
                Set.of("src/main/java/xhs_1111.java"))).isNull();
    }

    private PlanningAgentPlanner.StepSpec step(String type, List<String> tools, String description) {
        return new PlanningAgentPlanner.StepSpec(
                "s1", "Inspect", description, type, List.of(), tools, "Report governed result");
    }
}
