package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RepairContextTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void redactsSecretsAndHostPathsAndBoundsAttempts() {
        RepairContext context = RepairContext.create(json, "project_read_file", """
                {"relativePath":"src/Main.java","apiKey":"top-secret","host":"C:\\\\Users\\\\me\\\\secret.txt"}
                """, "validation_error", "Missing field at C:\\Users\\me\\secret.txt token=abcd", true, 9);

        String projected = context.toJson(json);

        assertThat(projected).contains("project_read_file", "src/Main.java", "VALIDATION_ERROR", "<redacted>")
                .doesNotContain("top-secret", "Users", "abcd");
        assertThat(context.remainingAttempts()).isEqualTo(1);
        assertThat(context.retryable()).isTrue();
    }

    @Test
    void exhaustedAttemptCannotRemainRetryable() {
        RepairContext context = RepairContext.create(json, "project_search", "{}",
                "PERMISSION_DENIED", "denied", true, 0);

        assertThat(context.retryable()).isFalse();
        assertThat(context.remainingAttempts()).isZero();
    }
}
