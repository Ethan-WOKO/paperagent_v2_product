package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectSandboxExecutionIntentTest {

    @Test
    void positiveExecutionRequestRequiresGovernedSandbox() {
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "Compile and run Worker23Calculator.java, then report stdout.")).isTrue();
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "\u8bf7\u7f16\u8bd1\u5e76\u8fd0\u884c Worker23Calculator.java\u3002")).isTrue();
    }

    @Test
    void negatedExecutionWordsRemainReadOnly() {
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "\u8bfb\u53d6 Worker23Calculator.java \u5e76\u89e3\u91ca\u4ee3\u7801\uff0c\u4e0d\u8981\u6267\u884c\u4ee3\u7801\u3002")).isFalse();
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "Review Worker23Calculator.java without running code.")).isFalse();
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "\u53ea\u8bfb\u5ba1\u67e5 Worker23Calculator.java\uff0c\u4e0d\u9700\u8981\u4ee3\u7801\u6267\u884c\u3002")).isFalse();
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "Do not compile or run code.")).isFalse();
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "\u4e0d\u8981\u7f16\u8bd1\u6216\u8fd0\u884c\u4ee3\u7801\u3002")).isFalse();
    }

    @Test
    void anotherPositiveActionStillRequiresSandboxAfterNegatedAction() {
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "Do not compile Worker23Calculator.java, but run the code.")).isTrue();
        assertThat(ProjectSandboxExecutionIntent.requiresGovernedExecution(
                "\u4e0d\u8981\u7f16\u8bd1 Worker23Calculator.java\uff0c\u4f46\u8981\u8fd0\u884c\u4ee3\u7801\u3002")).isTrue();
    }
}
