package com.yanban.api.agent.v2.context;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class KnownModelContextProfileRegistry {
    private static final long DEEPSEEK_CONTEXT_WINDOW = 1_000_000L;
    private static final long DEEPSEEK_MAX_OUTPUT = 384_000L;

    private final Map<ModelKey, ModelContextProfile> profiles;

    public KnownModelContextProfileRegistry() {
        FixedContextBudgetProfile budgetProfile =
                FixedContextBudgetProfile.layeredV1();
        ModelContextProfile flash = new ModelContextProfile(
                "deepseek", "deepseek-v4-flash",
                DEEPSEEK_CONTEXT_WINDOW, DEEPSEEK_MAX_OUTPUT,
                Utf8ByteTokenCounter.VERSION, budgetProfile);
        ModelContextProfile pro = new ModelContextProfile(
                "deepseek", "deepseek-v4-pro",
                DEEPSEEK_CONTEXT_WINDOW, DEEPSEEK_MAX_OUTPUT,
                Utf8ByteTokenCounter.VERSION, budgetProfile);
        this.profiles = Map.of(
                ModelKey.of(flash.provider(), flash.model()), flash,
                ModelKey.of(pro.provider(), pro.model()), pro);
    }

    public Optional<ModelContextProfile> find(
            String provider,
            String model) {
        if (provider == null || provider.isBlank()
                || model == null || model.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profiles.get(ModelKey.of(provider, model)));
    }

    private record ModelKey(String provider, String model) {
        private static ModelKey of(String provider, String model) {
            return new ModelKey(normalize(provider), normalize(model));
        }

        private static String normalize(String value) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
