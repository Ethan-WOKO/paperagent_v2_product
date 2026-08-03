package com.yanban.api.agent.v2.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedContextBudgetProfileTest {

    @Test
    void oneMillionProfileHasExactFixedNonBorrowingLimits() {
        FixedContextBudgetProfile profile =
                FixedContextBudgetProfile.layeredV1();
        var budgets = profile.budgets(1_000_000L);

        assertThat(budgets)
                .extracting(
                        ContextSectionBudget::section,
                        ContextSectionBudget::percentage,
                        ContextSectionBudget::tokenLimit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.CORE_AUTHORITY,
                                10, 100_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.RECENT_CONVERSATION,
                                20, 200_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.CONVERSATION_SUMMARY,
                                10, 100_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.TOOL_RESULTS,
                                20, 200_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.STEP_STATE,
                                15, 150_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.LONG_TERM_MEMORY,
                                10, 100_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.RAG_EVIDENCE,
                                5, 50_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.OUTPUT_RESERVE,
                                5, 50_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                ContextSectionType.SAFETY_MARGIN,
                                5, 50_000L));
        assertThat(budgets).extracting(ContextSectionBudget::percentage)
                .satisfies(values -> assertThat(values.stream()
                        .mapToInt(Integer::intValue).sum()).isEqualTo(100));
        assertThat(budgets).extracting(ContextSectionBudget::tokenLimit)
                .satisfies(values -> assertThat(values.stream()
                        .mapToLong(Long::longValue).sum())
                        .isEqualTo(1_000_000L));
        assertThat(profile.compactionTargetPercentage()).isEqualTo(70);
        assertThat(profile.compactionTargetTokens(
                1_000_000L, ContextSectionType.CORE_AUTHORITY))
                .isEqualTo(70_000L);
        assertThat(profile.compactionTargetTokens(
                1_000_000L, ContextSectionType.RECENT_CONVERSATION))
                .isEqualTo(140_000L);
        assertThat(profile.compactionTargetTokens(
                1_000_000L, ContextSectionType.RAG_EVIDENCE))
                .isEqualTo(35_000L);
    }

    @Test
    void registryRecognizesOnlyFrozenDeepSeekProfiles() {
        KnownModelContextProfileRegistry registry =
                new KnownModelContextProfileRegistry();

        assertThat(registry.find(" DEEPSEEK ", "DeepSeek-V4-Flash"))
                .get()
                .satisfies(profile -> {
                    assertThat(profile.contextWindowTokens())
                            .isEqualTo(1_000_000L);
                    assertThat(profile.maxOutputTokens())
                            .isEqualTo(384_000L);
                    assertThat(profile.tokenCounterVersion())
                            .isEqualTo("utf8-byte-v1");
                    assertThat(profile.budgetProfile().version())
                            .isEqualTo("layered-v1");
                });
        assertThat(registry.find("deepseek", "deepseek-v4-pro"))
                .isPresent();
        assertThat(registry.find("deepseek", "unknown"))
                .isEmpty();
        assertThat(registry.find(null, "deepseek-v4-flash"))
                .isEmpty();
    }
}
