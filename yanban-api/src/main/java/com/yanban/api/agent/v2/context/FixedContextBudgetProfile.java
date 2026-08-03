package com.yanban.api.agent.v2.context;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class FixedContextBudgetProfile {
    public static final String LAYERED_V1 = "layered-v1";
    public static final int LAYERED_V1_COMPACTION_TARGET_PERCENTAGE = 70;

    private final String version;
    private final Map<ContextSectionType, Integer> percentages;
    private final int compactionTargetPercentage;

    private FixedContextBudgetProfile(
            String version,
            Map<ContextSectionType, Integer> percentages) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        EnumMap<ContextSectionType, Integer> validated =
                new EnumMap<>(ContextSectionType.class);
        validated.putAll(percentages);
        if (validated.size() != ContextSectionType.values().length) {
            throw new IllegalArgumentException(
                    "every context section must have a percentage");
        }
        int total = validated.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        if (total != 100) {
            throw new IllegalArgumentException(
                    "context section percentages must total 100");
        }
        for (ContextSectionType section : ContextSectionType.values()) {
            Integer percentage = validated.get(section);
            if (percentage == null || percentage <= 0
                    || percentage != section.percentage()) {
                throw new IllegalArgumentException(
                        "context section percentage does not match "
                                + LAYERED_V1);
            }
        }
        this.version = version;
        this.percentages = Map.copyOf(validated);
        this.compactionTargetPercentage =
                LAYERED_V1_COMPACTION_TARGET_PERCENTAGE;
    }

    public static FixedContextBudgetProfile layeredV1() {
        EnumMap<ContextSectionType, Integer> percentages =
                new EnumMap<>(ContextSectionType.class);
        for (ContextSectionType section : ContextSectionType.values()) {
            percentages.put(section, section.percentage());
        }
        return new FixedContextBudgetProfile(LAYERED_V1, percentages);
    }

    public String version() {
        return version;
    }

    public int compactionTargetPercentage() {
        return compactionTargetPercentage;
    }

    public List<ContextSectionBudget> budgets(long contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must be positive");
        }
        return java.util.Arrays.stream(ContextSectionType.values())
                .map(section -> new ContextSectionBudget(
                        section,
                        percentages.get(section),
                        Math.multiplyExact(
                                contextWindowTokens,
                                percentages.get(section)) / 100L))
                .toList();
    }

    public ContextSectionBudget budget(
            long contextWindowTokens,
            ContextSectionType section) {
        if (section == null) {
            throw new IllegalArgumentException("section is required");
        }
        return budgets(contextWindowTokens).stream()
                .filter(value -> value.section() == section)
                .findFirst()
                .orElseThrow();
    }

    public long compactionTargetTokens(
            long contextWindowTokens,
            ContextSectionType section) {
        long sectionLimit = budget(contextWindowTokens, section).tokenLimit();
        return Math.multiplyExact(
                sectionLimit, compactionTargetPercentage) / 100L;
    }
}
