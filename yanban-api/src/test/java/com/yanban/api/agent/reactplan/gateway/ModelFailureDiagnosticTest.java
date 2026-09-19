package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ModelFailureDiagnosticTest {
    @Test
    void reportsBalanceEvidenceWithoutLeakingRawProviderBody() {
        var failure = ModelFailureDiagnostic.from(new RuntimeException(
                "HTTP 429 {\"error\":{\"code\":\"1113\",\"message\":\"余额不足 Bearer private-key sk-secret\"}}"));
        var problem = new AgentEngineGatewayExceptionHandler().gateway(failure).getBody();
        assertThat(problem.code()).isEqualTo("MODEL_PROVIDER_QUOTA_EXHAUSTED");
        assertThat(problem.message()).contains("余额", "HTTP 429", "providerCode=1113")
                .doesNotContain("private-key", "sk-secret");
        assertThat(problem.retryable()).isFalse();
    }

    @Test
    void doesNotInferBalanceFromRateLimitAlone() {
        assertThat(ModelFailureDiagnostic.from(new RuntimeException("HTTP 429")).code())
                .isEqualTo("MODEL_PROVIDER_RATE_LIMITED");
        assertThat(ModelFailureDiagnostic.from(new RuntimeException("HTTP 401 invalid api key")).code())
                .isEqualTo("MODEL_PROVIDER_AUTH_FAILED");
        assertThat(ModelFailureDiagnostic.from(new RuntimeException("unknown secret response")).getMessage())
                .doesNotContain("unknown secret response");
    }
}
