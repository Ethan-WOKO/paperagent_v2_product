package com.yanban.api.agent.v2.context;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ShadowContextAccountant {
    private final VersionedTokenCounter counter;

    public ShadowContextAccountant(VersionedTokenCounter counter) {
        if (counter == null) {
            throw new IllegalArgumentException("counter is required");
        }
        this.counter = counter;
    }

    public ShadowContextMeasurement measure(
            ModelContextProfile profile,
            Map<ContextSectionType, ? extends Iterable<String>> content) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (!profile.tokenCounterVersion().equals(counter.version())) {
            throw new IllegalArgumentException(
                    "profile token counter version does not match counter");
        }
        EnumMap<ContextSectionType, Long> tokenCounts =
                new EnumMap<>(ContextSectionType.class);
        if (content != null) {
            content.forEach((section, fragments) -> tokenCounts.put(
                    section, count(fragments)));
        }
        List<ContextSectionUsage> usages = new ArrayList<>();
        long inputTokens = 0L;
        for (ContextSectionBudget budget : profile.budgetProfile()
                .budgets(profile.contextWindowTokens())) {
            long tokens = tokenCounts.getOrDefault(budget.section(), 0L);
            usages.add(new ContextSectionUsage(
                    budget.section(), budget.tokenLimit(), tokens,
                    tokens > budget.tokenLimit()));
            if (budget.section() != ContextSectionType.OUTPUT_RESERVE
                    && budget.section() != ContextSectionType.SAFETY_MARGIN) {
                inputTokens = Math.addExact(inputTokens, tokens);
            }
        }
        return new ShadowContextMeasurement(
                profile.budgetProfile().version(), counter.version(),
                profile.contextWindowTokens(), profile.maxOutputTokens(),
                inputTokens, usages);
    }

    private long count(Iterable<String> fragments) {
        if (fragments == null) {
            return 0L;
        }
        long tokens = 0L;
        for (String fragment : fragments) {
            tokens = Math.addExact(tokens, counter.count(fragment));
        }
        return tokens;
    }
}
