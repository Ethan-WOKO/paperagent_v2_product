package com.yanban.api.agent.v2.effect;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NaturalLanguageEffectAuthoritySourceTest {
    @Test
    void authorizesOnlyOwnedPersistentTurnAndBoundedInternalToolCatalog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class),
                eq(7L), eq(11L), eq("plan-1")))
                .thenReturn(List.of("""
                        [{"stepId":"read-1","publicAlias":"project_read",
                          "internalToolId":"project.read"}]
                        """));
        var source = new NaturalLanguageEffectAuthoritySource(
                jdbc, new ObjectMapper());
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1", "project.read"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1", "project.search"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1", "project.bibtex.audit"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1", "project.latex.outline"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.latex.crossref.audit"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.latex.float.audit"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.latex.protected.inventory"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.paper.acronym.audit"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.paper.language.stats"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1", "project.code.symbols"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.experiment.summary"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1",
                "project.cross-material.search"));
        assertTrue(source.authorizes(
                7L, 11L, "plan-1", "read-1", "sandbox.execute"));
        assertFalse(source.authorizes(
                7L, 11L, "plan-1", "read-1", "host.shell"));
        assertFalse(source.authorizes(
                7L, 11L, "plan-1", " ", "project.read"));
    }

    @Test
    void malformedOrDuplicateAuthorityFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class),
                any(), any(), any()))
                .thenReturn(List.of("[]", "[]"));
        assertFalse(new NaturalLanguageEffectAuthoritySource(
                jdbc, new ObjectMapper()).authorizes(
                7L, 11L, "plan-1", "read-1", "project.read"));
    }
}
