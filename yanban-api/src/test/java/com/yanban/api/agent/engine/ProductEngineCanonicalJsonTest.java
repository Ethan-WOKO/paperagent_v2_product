package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductEngineCanonicalJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void matchesFrozenTaskSubmissionFixture() throws Exception {
        JsonNode fixture = json.readTree(Files.readString(Path.of("..", "agent-engine-contract",
                "conformance", "fixtures", "positive", "task-submission.json")));
        ProductEngineCanonicalJson canonical = new ProductEngineCanonicalJson(json);

        assertThat(canonical.digest(fixture.path("authority")))
                .isEqualTo(fixture.path("requestDigest").asText());
        assertThat(canonical.canonical(json.readTree("{\"😀\":1,\"a\":2,\"é\":3}")))
                .isEqualTo("{\"a\":2,\"é\":3,\"😀\":1}");
    }
}
