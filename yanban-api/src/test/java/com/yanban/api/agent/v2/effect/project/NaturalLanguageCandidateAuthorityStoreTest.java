package com.yanban.api.agent.v2.effect.project;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class NaturalLanguageCandidateAuthorityStoreTest {
    @Test
    void exactReplayConvergesAndChangedAuthorityFailsClosed()
            throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:natural-candidate-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = Files.readString(Path.of(
                "src/test/resources/db/migration-h2/"
                        + "V62__create_agent_v2_adaptive_turns.sql"));
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
        var store = new NaturalLanguageCandidateAuthorityStore(
                jdbc, new ObjectMapper());
        String arguments =
                "{\"operation\":\"compose\",\"paths\":[\"README.md\"]}";

        var first = store.bind(
                7L, 9L, 42L, "plan", "step", 8L,
                "version", "improve", arguments, List.of("README.md"));
        var replay = store.bind(
                7L, 9L, 42L, "plan", "step", 8L,
                "version", "improve", arguments, List.of("README.md"));

        assertEquals(first, replay);
        assertThrows(IllegalStateException.class, () -> store.bind(
                7L, 9L, 42L, "plan", "step", 8L,
                "version", "different", arguments,
                List.of("README.md")));
        assertFalse(store.hasPreparedCandidate("plan"));

        store.bindPrepared(
                "plan", Map.of("README.md", "new text"),
                "d".repeat(64));
        store.bindPrepared(
                "plan", Map.of("README.md", "new text"),
                "d".repeat(64));
        assertEquals(Map.of("README.md", "new text"),
                store.requirePrepared("plan").replacements());
        assertTrue(store.hasPreparedCandidate("plan"));
        store.bindCandidate(
                "plan", 77L, "c".repeat(64), "d".repeat(64));
        store.bindCandidate(
                "plan", 77L, "c".repeat(64), "d".repeat(64));
        assertEquals(77L,
                store.candidateArtifactId("plan").orElseThrow());
        assertTrue(store.hasPreparedCandidate("plan"));
        assertThrows(IllegalStateException.class, () ->
                store.bindCandidate(
                        "plan", 78L, "e".repeat(64), "d".repeat(64)));
    }
}
